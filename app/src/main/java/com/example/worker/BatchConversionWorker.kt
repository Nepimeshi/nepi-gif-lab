package com.example.worker

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.data.BatchQueueEntity
import com.example.data.BatchQueueRepository
import com.example.data.ConversionHistoryEntity
import com.example.data.ConversionHistorySnapshot
import com.example.data.ConversionHistorySnapshotCodec
import com.example.data.GifSettingsCodec
import com.example.data.GifStudioDatabase
import com.example.data.HistoryRepository
import com.example.engine.BatchSettingsResolver
import com.example.engine.GalleryExporter
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.MediaFrameSourceOpener
import com.example.engine.OutputFormat
import com.example.engine.SavedGifResult
import com.example.engine.VideoFrameSource
import com.example.engine.WebPConversionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Result summary for a completed or cancelled batch queue run.
 */
data class BatchRunSummary(
    val completedCount: Int,
    val failedCount: Int,
    val cancelledCount: Int
)

private class SingleBatchItemCancelledException : Exception("Item cancelled by user")

/**
 * Core sequential batch queue processor shared by [BatchConversionWorker] and Robolectric tests.
 *
 * Reads live queue state from [BatchQueueRepository] before each item so mid-batch reordering,
 * item additions/removals, individual item cancellation, and retries are honored reliably while
 * preserving completed outputs and conversion history across failures or cancellations.
 */
object BatchQueueProcessor {

    interface ItemConverter {
        suspend fun convertItem(
            item: BatchQueueEntity,
            effectiveSettings: GifConversionSettings,
            onProgress: (percent: Float, stage: String, isIndeterminate: Boolean) -> Unit,
            isItemCancelRequested: () -> Boolean
        ): SavedGifResult
    }

    class DefaultItemConverter(
        private val context: Context,
        private val historyRepo: HistoryRepository
    ) : ItemConverter {
        override suspend fun convertItem(
            item: BatchQueueEntity,
            effectiveSettings: GifConversionSettings,
            onProgress: (percent: Float, stage: String, isIndeterminate: Boolean) -> Unit,
            isItemCancelRequested: () -> Boolean
        ): SavedGifResult {
            val uri = Uri.parse(item.uri)
            val outputFormat = effectiveSettings.outputFormat
            val tempDir = File(context.cacheDir, "gif_exports").apply { mkdirs() }
            val tempOutputFile = File(
                tempDir,
                "batch_${item.id}_${System.currentTimeMillis()}.${outputFormat.extension}"
            )
            var source: VideoFrameSource? = null

            try {
                source = MediaFrameSourceOpener.open(context, uri)
                val sourceName = source.metadata.displayName
                val progressCallback: (com.example.engine.ConversionProgress) -> Unit = { prog ->
                    if (isItemCancelRequested()) {
                        throw SingleBatchItemCancelledException()
                    }
                    onProgress(prog.percent.coerceIn(0f, 0.96f), prog.stage, prog.isIndeterminate)
                }

                val stats = if (outputFormat == OutputFormat.WEBP) {
                    WebPConversionEngine.encodeToFile(
                        context = context,
                        frameSource = source,
                        settings = effectiveSettings,
                        outputFile = tempOutputFile,
                        onProgress = progressCallback
                    )
                } else {
                    GifConversionEngine.encodeToFile(
                        frameSource = source,
                        settings = effectiveSettings,
                        outputFile = tempOutputFile,
                        onProgress = progressCallback
                    )
                }

                if (isItemCancelRequested()) {
                    throw SingleBatchItemCancelledException()
                }

                onProgress(0.97f, "Saving to ${GalleryExporter.GALLERY_FOLDER_DISPLAY}…", false)
                val saved = GalleryExporter.saveAnimationToGallery(
                    context = context,
                    tempFile = tempOutputFile,
                    sourceName = sourceName,
                    stats = stats
                )

                val snapshot = ConversionHistorySnapshot.fromConversion(
                    metadata = source.metadata,
                    settings = effectiveSettings,
                    stats = stats
                )
                historyRepo.recordExport(
                    ConversionHistoryEntity(
                        contentUri = saved.contentUri.toString(),
                        fileName = saved.fileName,
                        thumbnailPath = saved.thumbnailPath,
                        fileSizeBytes = saved.stats.fileSizeBytes,
                        width = saved.stats.width,
                        height = saved.stats.height,
                        fps = saved.stats.fps,
                        durationMs = saved.stats.durationMs,
                        frameCount = saved.stats.frameCount,
                        colorCount = saved.stats.effectiveColorCount,
                        ditherModeName = if (outputFormat == OutputFormat.WEBP) {
                            (saved.stats.webpQualityPreset ?: effectiveSettings.webpQualityPreset).label
                        } else {
                            saved.stats.ditherMode.label
                        },
                        sourceVideoName = sourceName,
                        outputFormat = outputFormat.name,
                        sourceInputType = source.metadata.inputType.name,
                        settingsSnapshotJson = ConversionHistorySnapshotCodec.encode(snapshot)
                    )
                )

                return saved
            } finally {
                runCatching { source?.close() }
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }
            }
        }
    }

    suspend fun processQueue(
        queueRepo: BatchQueueRepository,
        sharedSettings: GifConversionSettings,
        converter: ItemConverter,
        onBatchNotificationUpdate: suspend (
            currentFileName: String?,
            overallPercent: Int,
            completedCount: Int,
            remainingCount: Int,
            stage: String,
            indeterminate: Boolean
        ) -> Unit = { _, _, _, _, _, _ -> }
    ): BatchRunSummary {
        val cleanShared = BatchSettingsResolver.resolveEffectiveSettings(
            sharedSettings = sharedSettings,
            customSettings = null,
            sourceDurationMs = 0L
        )

        try {
            while (true) {
                val snapshot = queueRepo.getAll()
                val nextItem = snapshot.firstOrNull { it.status == "QUEUED" } ?: break

                val totalCount = snapshot.size.coerceAtLeast(1)
                val doneBefore = snapshot.count { it.status == "COMPLETED" || it.status == "FAILED" || it.status == "CANCELLED" }
                val completedBefore = snapshot.count { it.status == "COMPLETED" }
                val remainingIncludingCurrent = snapshot.count { it.status == "QUEUED" || it.status == "PROCESSING" }

                if (nextItem.cancelRequested) {
                    queueRepo.updateStatus(
                        id = nextItem.id,
                        status = "CANCELLED",
                        progress = 0f,
                        isIndeterminate = false,
                        stage = "Cancelled",
                        cancelRequested = false
                    )
                    continue
                }

                queueRepo.updateStatus(
                    id = nextItem.id,
                    status = "PROCESSING",
                    progress = 0f,
                    isIndeterminate = true,
                    stage = "Preparing…",
                    errorMessage = null,
                    cancelRequested = false
                )

                val initialOverallPct = ((doneBefore.toFloat() / totalCount.toFloat()) * 100f)
                    .roundToInt().coerceIn(0, 99)
                onBatchNotificationUpdate(
                    nextItem.displayName,
                    initialOverallPct,
                    completedBefore,
                    remainingIncludingCurrent,
                    "Preparing…",
                    doneBefore == 0
                )

                val itemSettings = BatchSettingsResolver.resolveEffectiveSettings(
                    sharedSettings = cleanShared,
                    customSettings = GifSettingsCodec.decode(nextItem.customSettingsEncoded),
                    sourceDurationMs = nextItem.durationMs
                )

                val cachedCancelRequested = AtomicBoolean(false)
                var lastCancelCheckMs = 0L
                var lastUpdateMs = 0L

                try {
                    val saved = converter.convertItem(
                        item = nextItem,
                        effectiveSettings = itemSettings,
                        onProgress = { pct, stage, isIndeterminate ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdateMs >= 160L || pct >= 0.96f) {
                                lastUpdateMs = now
                                val overallPct = (((doneBefore + pct) / totalCount.toFloat()) * 100f)
                                    .roundToInt().coerceIn(0, 99)
                                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                                    val cancelled = queueRepo.isCancelRequested(nextItem.id)
                                    cachedCancelRequested.set(cancelled)
                                    if (!cancelled) {
                                        queueRepo.updateStatus(
                                            id = nextItem.id,
                                            status = "PROCESSING",
                                            progress = pct,
                                            isIndeterminate = isIndeterminate,
                                            stage = stage
                                        )
                                        onBatchNotificationUpdate(
                                            nextItem.displayName,
                                            overallPct,
                                            completedBefore,
                                            remainingIncludingCurrent,
                                            stage,
                                            isIndeterminate && doneBefore == 0
                                        )
                                    }
                                }
                            }
                        },
                        isItemCancelRequested = {
                            val now = SystemClock.elapsedRealtime()
                            if (!cachedCancelRequested.get() && now - lastCancelCheckMs >= 120L) {
                                lastCancelCheckMs = now
                                cachedCancelRequested.set(
                                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                                        queueRepo.isCancelRequested(nextItem.id)
                                    }
                                )
                            }
                            cachedCancelRequested.get()
                        }
                    )

                    queueRepo.markCompleted(
                        id = nextItem.id,
                        savedUri = saved.contentUri.toString(),
                        savedFileName = saved.fileName,
                        savedWidth = saved.stats.width,
                        savedHeight = saved.stats.height,
                        savedFps = saved.stats.fps,
                        savedDurationMs = saved.stats.durationMs,
                        savedFrameCount = saved.stats.frameCount,
                        savedFileSizeBytes = saved.stats.fileSizeBytes,
                        savedColorCount = saved.stats.effectiveColorCount,
                        savedDitherMode = if (saved.stats.outputFormat == OutputFormat.WEBP) {
                            (saved.stats.webpQualityPreset ?: itemSettings.webpQualityPreset).name
                        } else {
                            saved.stats.ditherMode.name
                        },
                        savedThumbnailPath = saved.thumbnailPath,
                        savedOutputFormat = saved.stats.outputFormat.name
                    )
                } catch (_: SingleBatchItemCancelledException) {
                    queueRepo.updateStatus(
                        id = nextItem.id,
                        status = "CANCELLED",
                        progress = 0f,
                        isIndeterminate = false,
                        stage = "Cancelled",
                        errorMessage = null,
                        cancelRequested = false
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val reason = t.message?.takeIf { it.isNotBlank() } ?: "Conversion failed"
                    queueRepo.updateStatus(
                        id = nextItem.id,
                        status = "FAILED",
                        progress = 0f,
                        isIndeterminate = false,
                        stage = reason,
                        errorMessage = reason,
                        cancelRequested = false
                    )
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                queueRepo.markPendingAndActiveCancelled()
            }
            throw ce
        }

        val finalItems = queueRepo.getAll()
        return BatchRunSummary(
            completedCount = finalItems.count { it.status == "COMPLETED" },
            failedCount = finalItems.count { it.status == "FAILED" },
            cancelledCount = finalItems.count { it.status == "CANCELLED" }
        )
    }
}

/**
 * WorkManager foreground worker executing the persisted batch conversion queue in the background
 * (including when the user switches apps or turns off the screen).
 */
class BatchConversionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = GifStudioDatabase.getInstance(applicationContext)
        val queueRepo = BatchQueueRepository(db.batchQueueDao())
        val historyRepo = HistoryRepository(db.historyDao())
        val sharedSettings = GifSettingsCodec.decode(inputData.getString(KEY_SHARED_SETTINGS))
            ?: GifConversionSettings.DEFAULT

        val initialItems = queueRepo.getAll()
        val initialRemaining = initialItems.count { it.status == "QUEUED" || it.status == "PROCESSING" }
        val initialCompleted = initialItems.count { it.status == "COMPLETED" }

        runCatching {
            setForeground(
                ConversionNotificationHelper.createBatchForegroundInfo(
                    context = applicationContext,
                    currentFileName = initialItems.firstOrNull { it.status == "QUEUED" }?.displayName,
                    overallPercent = 0,
                    completedCount = initialCompleted,
                    remainingCount = initialRemaining,
                    stage = "Starting batch queue…",
                    indeterminate = true
                )
            )
        }

        return try {
            val summary = BatchQueueProcessor.processQueue(
                queueRepo = queueRepo,
                sharedSettings = sharedSettings,
                converter = BatchQueueProcessor.DefaultItemConverter(applicationContext, historyRepo),
                onBatchNotificationUpdate = { fileName, overallPct, done, remaining, stage, indeterminate ->
                    runCatching {
                        setForeground(
                            ConversionNotificationHelper.createBatchForegroundInfo(
                                context = applicationContext,
                                currentFileName = fileName,
                                overallPercent = overallPct,
                                completedCount = done,
                                remainingCount = remaining,
                                stage = stage,
                                indeterminate = indeterminate
                            )
                        )
                    }
                }
            )

            ConversionNotificationHelper.cancelNotification(
                applicationContext,
                ConversionNotificationHelper.NOTIFICATION_ID_BATCH_ACTIVE
            )
            ConversionNotificationHelper.postBatchFinishedNotification(
                context = applicationContext,
                completedCount = summary.completedCount,
                failedCount = summary.failedCount,
                cancelledCount = summary.cancelledCount
            )
            Result.success()
        } catch (ce: CancellationException) {
            ConversionNotificationHelper.cancelNotification(
                applicationContext,
                ConversionNotificationHelper.NOTIFICATION_ID_BATCH_ACTIVE
            )
            throw ce
        } catch (_: Throwable) {
            ConversionNotificationHelper.cancelNotification(
                applicationContext,
                ConversionNotificationHelper.NOTIFICATION_ID_BATCH_ACTIVE
            )
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nepi_batch_queue"
        const val KEY_SHARED_SETTINGS = "batch_shared_settings"

        fun buildInputData(sharedSettings: GifConversionSettings): Data {
            return Data.Builder()
                .putString(KEY_SHARED_SETTINGS, GifSettingsCodec.encode(sharedSettings))
                .build()
        }
    }
}
