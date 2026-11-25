package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.data.GifSettingsCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Resolves the exact [GifConversionSettings] applied to any individual batch queue item.
 *
 * Shared identically by both [BatchEstimateManager] and [com.example.worker.BatchQueueProcessor]
 * so every pre-conversion size estimate uses the exact same settings that will be used during
 * final export.
 */
object BatchSettingsResolver {

    fun resolveEffectiveSettings(
        sharedSettings: GifConversionSettings,
        customSettings: GifConversionSettings?,
        sourceDurationMs: Long = 0L
    ): GifConversionSettings {
        val base = if (customSettings != null) {
            customSettings.sanitized()
        } else {
            sharedSettings.copy(
                trimStartMs = 0L,
                trimEndMs = 0L,
                cropRect = NormalizedCropRect.FULL,
                rotationDegrees = 0
            ).sanitized()
        }
        return if (sourceDurationMs > 0L) {
            val start = base.trimStartMs.coerceIn(0L, (sourceDurationMs - 50L).coerceAtLeast(0L))
            val end = (if (base.trimEndMs <= start) sourceDurationMs else base.trimEndMs)
                .coerceIn((start + 50L).coerceAtMost(sourceDurationMs), sourceDurationMs)
            base.copy(trimStartMs = start, trimEndMs = end)
        } else {
            base
        }
    }

    /**
     * Canonical cache key covering every setting that affects output GIF size.
     */
    fun settingsCacheKey(effectiveSettings: GifConversionSettings): String {
        val s = effectiveSettings.sanitized()
        return "${GifSettingsCodec.encode(s)}|opt=${s.optimizeOutput}"
    }
}

/**
 * Per-item output size estimation state for a batch queue entry.
 */
sealed interface BatchItemEstimateState {
    data object Idle : BatchItemEstimateState
    data object Estimating : BatchItemEstimateState
    data class Ready(
        val estimate: SizeEstimate,
        val effectiveSettings: GifConversionSettings,
        val settingsKey: String
    ) : BatchItemEstimateState
    data class Unavailable(val reason: String) : BatchItemEstimateState
}

/**
 * Aggregated batch output size estimate across queued/actionable items, kept strictly distinct
 * from actual exported byte counts of already-completed items.
 */
data class BatchTotalSizeEstimate(
    val totalEstimatedBytes: Long = 0L,
    val readyItemCount: Int = 0,
    val totalEstimatableCount: Int = 0,
    val estimatingCount: Int = 0,
    val unavailableCount: Int = 0,
    val actualCompletedBytes: Long = 0L,
    val completedCount: Int = 0
) {
    val isEstimating: Boolean get() = estimatingCount > 0
    val hasAnyEstimate: Boolean get() = readyItemCount > 0 && totalEstimatedBytes > 0L
    val isPartial: Boolean get() = readyItemCount < totalEstimatableCount
}

/**
 * Lightweight estimation request describing a single item in the batch queue.
 */
data class BatchEstimateRequest(
    val id: String,
    val uri: Uri,
    val durationMs: Long,
    val isCompleted: Boolean,
    val isReadable: Boolean,
    val customSettings: GifConversionSettings?
)

/**
 * Concurrency-limited, cache-backed coordinator that computes individual output GIF size
 * estimates for every item in the batch queue using [GifSizeEstimator].
 *
 * Guarantees:
 * - Limits simultaneous estimation jobs via a [Semaphore] (default: 2) to keep RAM bounded.
 * - Caches valid estimates keyed by `(sourceUri, canonicalEffectiveSettingsKey)` and serves
 *   cache hits instantly without re-opening or re-decoding media.
 * - Automatically invalidates and recalculates when shared settings, presets, or per-item
 *   overrides change.
 * - Cancels superseded or removed item jobs immediately.
 * - Never fabricates estimates or treats `0` bytes as a valid estimate.
 */
class BatchEstimateManager(
    private val scope: CoroutineScope,
    private val sourceOpener: suspend (Uri) -> VideoFrameSource,
    maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val context: Context? = null
) {
    private data class CacheKey(val uri: String, val settingsKey: String)
    private data class ActiveJob(val uri: String, val settingsKey: String, val job: Job)

    private val semaphore = Semaphore(maxConcurrentJobs.coerceAtLeast(1))
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<CacheKey, SizeEstimate>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, SizeEstimate>?): Boolean {
            return size > maxCacheEntries
        }
    }
    private val activeJobs = mutableMapOf<String, ActiveJob>()

    private val _estimates = MutableStateFlow<Map<String, BatchItemEstimateState>>(emptyMap())
    val estimates: StateFlow<Map<String, BatchItemEstimateState>> = _estimates.asStateFlow()

    @Synchronized
    fun syncQueue(
        requests: List<BatchEstimateRequest>,
        sharedSettings: GifConversionSettings
    ) {
        val currentIds = requests.map { it.id }.toSet()

        // Cancel and prune removed items
        val removedIds = activeJobs.keys.filter { it !in currentIds }
        for (id in removedIds) {
            activeJobs.remove(id)?.job?.cancel()
        }

        val nextStates = LinkedHashMap<String, BatchItemEstimateState>(_estimates.value.size)
        val existingStates = _estimates.value

        for (req in requests) {
            // Preserve prior estimate on already-completed items (for estimated-vs-actual comparison)
            if (req.isCompleted) {
                activeJobs.remove(req.id)?.job?.cancel()
                existingStates[req.id]?.let { nextStates[req.id] = it }
                continue
            }

            if (!req.isReadable) {
                activeJobs.remove(req.id)?.job?.cancel()
                nextStates[req.id] = BatchItemEstimateState.Unavailable("Unsupported or unreadable file")
                continue
            }

            val effectiveSettings = BatchSettingsResolver.resolveEffectiveSettings(
                sharedSettings = sharedSettings,
                customSettings = req.customSettings,
                sourceDurationMs = req.durationMs
            )
            val settingsKey = BatchSettingsResolver.settingsCacheKey(effectiveSettings)
            val uriStr = req.uri.toString()
            val cacheKey = CacheKey(uriStr, settingsKey)

            val cached = synchronized(cacheLock) { cache[cacheKey] }
            if (cached != null && cached.estimatedBytes > 0L) {
                activeJobs.remove(req.id)?.job?.cancel()
                nextStates[req.id] = BatchItemEstimateState.Ready(
                    estimate = cached,
                    effectiveSettings = effectiveSettings,
                    settingsKey = settingsKey
                )
                continue
            }

            val running = activeJobs[req.id]
            if (running != null &&
                running.uri == uriStr &&
                running.settingsKey == settingsKey &&
                running.job.isActive
            ) {
                nextStates[req.id] = BatchItemEstimateState.Estimating
                continue
            }

            // Settings or URI changed -> cancel stale job and start fresh estimation
            running?.job?.cancel()
            nextStates[req.id] = BatchItemEstimateState.Estimating

            val job = scope.launch {
                val resultState = try {
                    semaphore.withPermit {
                        val estimate = withContext(ioDispatcher) {
                            sourceOpener(req.uri).use { src ->
                                val resolved = BatchSettingsResolver.resolveEffectiveSettings(
                                    sharedSettings = sharedSettings,
                                    customSettings = req.customSettings,
                                    sourceDurationMs = src.metadata.durationMs
                                )
                                MediaSizeEstimator.estimate(context, src, resolved)
                            }
                        }
                        if (estimate.estimatedBytes > 0L && estimate.totalFrames > 0) {
                            synchronized(cacheLock) {
                                cache[cacheKey] = estimate
                            }
                            BatchItemEstimateState.Ready(
                                estimate = estimate,
                                effectiveSettings = effectiveSettings,
                                settingsKey = settingsKey
                            )
                        } else {
                            BatchItemEstimateState.Unavailable("Estimate unavailable")
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val reason = t.message?.takeIf { it.isNotBlank() } ?: "Estimate unavailable"
                    BatchItemEstimateState.Unavailable(reason)
                }

                publishResultIfCurrent(req.id, uriStr, settingsKey, resultState)
            }
            activeJobs[req.id] = ActiveJob(uriStr, settingsKey, job)
        }

        _estimates.value = nextStates
    }

    @Synchronized
    private fun publishResultIfCurrent(
        itemId: String,
        uriStr: String,
        settingsKey: String,
        state: BatchItemEstimateState
    ) {
        val current = activeJobs[itemId] ?: return
        if (current.uri != uriStr || current.settingsKey != settingsKey) return
        activeJobs.remove(itemId)
        _estimates.update { map ->
            if (itemId !in map) map else map + (itemId to state)
        }
    }

    @Synchronized
    fun clearQueueState(clearCache: Boolean = false) {
        activeJobs.values.forEach { it.job.cancel() }
        activeJobs.clear()
        if (clearCache) {
            synchronized(cacheLock) { cache.clear() }
        }
        _estimates.value = emptyMap()
    }

    @Synchronized
    fun cancelAll() {
        activeJobs.values.forEach { it.job.cancel() }
        activeJobs.clear()
    }

    fun cacheSize(): Int = synchronized(cacheLock) { cache.size }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_JOBS = 2
        const val DEFAULT_MAX_CACHE_ENTRIES = 64
    }
}
