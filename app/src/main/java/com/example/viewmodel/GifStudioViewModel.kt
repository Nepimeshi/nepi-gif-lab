package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.BatchQueueEntity
import com.example.data.BatchQueueRepository
import com.example.data.ConversionHistoryEntity
import com.example.data.ConversionPresetEntity
import com.example.data.GifSettingsCodec
import com.example.data.GifStudioDatabase
import com.example.data.HistoryRepository
import com.example.data.PresetRepository
import com.example.data.UserPreferencesRepository
import com.example.engine.AspectRatioPreset
import com.example.engine.BatchEstimateManager
import com.example.engine.BatchEstimateRequest
import com.example.engine.BatchItemEstimateState
import com.example.engine.BatchTotalSizeEstimate
import com.example.engine.ColorQualityPreset
import com.example.engine.ConversionProgress
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.GalleryExporter
import com.example.engine.GifConversionSettings
import com.example.engine.GifGeometryCalculator
import com.example.engine.GifSizeEstimator
import com.example.engine.LiveGifPreviewData
import com.example.engine.LiveGifPreviewRenderer
import com.example.engine.MediaFrameSourceOpener
import com.example.engine.MediaInputType
import com.example.engine.MediaSizeEstimator
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.SavedGifResult
import com.example.engine.SizeEstimate
import com.example.engine.TargetFileSizeOptimizer
import com.example.engine.TargetSizeRecommendation
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import com.example.engine.WebPQualityPreset
import com.example.ui.theme.ThemeMode
import com.example.worker.BatchConversionWorker
import com.example.worker.GifConversionWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class PreviewMode {
    ORIGINAL,
    GIF_PREVIEW
}

enum class EditorTab {
    TRIM_CROP,
    SIZE_MOTION,
    COLOR_DITHER
}

data class EditorUiState(
    val isLoadingVideo: Boolean = false,
    val errorMessage: String? = null,
    val metadata: VideoMetadata? = null,
    val settings: GifConversionSettings = GifConversionSettings.DEFAULT,
    val filmstrip: List<Bitmap> = emptyList(),
    val isLoadingFilmstrip: Boolean = false,
    val currentPositionMs: Long = 0L,
    val isPlaying: Boolean = true,
    val previewMode: PreviewMode = PreviewMode.ORIGINAL,
    val activeTab: EditorTab = EditorTab.TRIM_CROP,
    val isCropOverlayVisible: Boolean = true,
    val isEstimatingSize: Boolean = false,
    val sizeEstimate: SizeEstimate? = null,
    val isRenderingGifPreview: Boolean = false,
    val liveGifPreview: LiveGifPreviewData? = null,
    val gifPreviewError: String? = null,
    val isAnalyzingTargetSize: Boolean = false,
    val targetSizeRecommendation: TargetSizeRecommendation? = null,
    val targetSizeError: String? = null
)

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Converting(
        val progress: ConversionProgress,
        val workId: UUID
    ) : ExportUiState
    data class Success(
        val result: SavedGifResult,
        val estimatedBytes: Long?
    ) : ExportUiState
    data class Error(val message: String) : ExportUiState
    data object Cancelled : ExportUiState
}

enum class BatchItemStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BatchQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val inputType: MediaInputType = MediaInputType.VIDEO,
    val thumbnail: Bitmap? = null,
    val status: BatchItemStatus = BatchItemStatus.QUEUED,
    val progress: Float = 0f,
    val isIndeterminate: Boolean = false,
    val stage: String = "Queued",
    val savedResult: SavedGifResult? = null,
    val errorMessage: String? = null,
    val customSettings: GifConversionSettings? = null,
    val estimateState: BatchItemEstimateState = BatchItemEstimateState.Idle
)

data class BatchUiState(
    val items: List<BatchQueueItem> = emptyList(),
    val sharedSettings: GifConversionSettings = GifConversionSettings.DEFAULT,
    val isPreparingQueue: Boolean = false,
    val isRunning: Boolean = false,
    val isCancelled: Boolean = false,
    val overallProgress: Float = 0f,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val totalSizeEstimate: BatchTotalSizeEstimate = BatchTotalSizeEstimate()
)

fun computeBatchTotalEstimate(items: List<BatchQueueItem>): BatchTotalSizeEstimate {
    var totalEstBytes = 0L
    var readyCount = 0
    var estimatableCount = 0
    var estimatingCount = 0
    var unavailableCount = 0
    var actualCompletedBytes = 0L
    var completedCount = 0

    for (item in items) {
        if (item.status == BatchItemStatus.COMPLETED) {
            completedCount++
            actualCompletedBytes += item.savedResult?.stats?.fileSizeBytes?.coerceAtLeast(0L) ?: 0L
            continue
        }
        estimatableCount++
        when (val est = item.estimateState) {
            is BatchItemEstimateState.Ready -> {
                if (est.estimate.estimatedBytes > 0L) {
                    totalEstBytes += est.estimate.estimatedBytes
                    readyCount++
                } else {
                    unavailableCount++
                }
            }
            BatchItemEstimateState.Estimating,
            BatchItemEstimateState.Idle -> estimatingCount++
            is BatchItemEstimateState.Unavailable -> unavailableCount++
        }
    }

    return BatchTotalSizeEstimate(
        totalEstimatedBytes = totalEstBytes,
        readyItemCount = readyCount,
        totalEstimatableCount = estimatableCount,
        estimatingCount = estimatingCount,
        unavailableCount = unavailableCount,
        actualCompletedBytes = actualCompletedBytes,
        completedCount = completedCount
    )
}

class GifStudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferencesRepo = UserPreferencesRepository(appContext)
    private val database = GifStudioDatabase.getInstance(appContext)
    private val historyRepo = HistoryRepository(database.historyDao())
    private val presetRepo = PresetRepository(database.presetDao())
    private val batchQueueRepo = BatchQueueRepository(database.batchQueueDao())
    private val workManager = WorkManager.getInstance(appContext)

    val themeMode: StateFlow<ThemeMode> = preferencesRepo.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.DARK)

    val hapticsPreference: StateFlow<Boolean?> = preferencesRepo.hapticsPreferenceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allHistory: StateFlow<List<ConversionHistoryEntity>> = historyRepo.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentHistory: StateFlow<List<ConversionHistoryEntity>> = historyRepo.recentHistory(6)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<ConversionPresetEntity>> = presetRepo.allPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _editorState = MutableStateFlow(EditorUiState())
    val editorState: StateFlow<EditorUiState> = _editorState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _batchState = MutableStateFlow(BatchUiState())
    val batchState: StateFlow<BatchUiState> = _batchState.asStateFlow()

    private val batchThumbCache = mutableMapOf<String, Bitmap>()

    private val batchEstimateManager = BatchEstimateManager(
        scope = viewModelScope,
        sourceOpener = { uri -> MediaFrameSourceOpener.open(appContext, uri) },
        context = appContext
    )

    private var activeFrameSource: VideoFrameSource? = null
    private var loadVideoJob: Job? = null
    private var estimateJob: Job? = null
    private var livePreviewJob: Job? = null
    private var targetSizeJob: Job? = null
    private var exportObserverJob: Job? = null
    private var previewViewportBucketPx: Int = LiveGifPreviewRenderer.DEFAULT_PREVIEW_LONG_EDGE

    init {
        viewModelScope.launch {
            presetRepo.ensureBuiltInPresets()
            val saved = preferencesRepo.savedSettingsFlow.first()
            _editorState.update { it.copy(settings = saved) }
            _batchState.update { it.copy(sharedSettings = saved) }
            syncBatchEstimates()
        }
        recoverActiveSingleExport()
        observePersistedBatchQueue()
        observeBatchEstimateUpdates()
        observeBatchWorkerState()
    }

    private fun recoverActiveSingleExport() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(GifConversionWorker.TAG_SINGLE_EXPORT).collect { infos ->
                val active = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                if (active != null && (_exportState.value is ExportUiState.Idle ||
                        (_exportState.value as? ExportUiState.Converting)?.workId != active.id)
                ) {
                    observeSingleExportWork(active.id, estimatedBytes = null)
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepo.setThemeMode(mode)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setHapticsEnabled(enabled)
        }
    }

    suspend fun decodeOriginalFrame(timeUs: Long, maxEdgePx: Int): Bitmap? {
        val source = activeFrameSource ?: return null
        return withContext(Dispatchers.Default) {
            try {
                if (source is com.example.engine.AnimatedGifFrameSource) {
                    val nativeBmp = source.getFrameAtTimeUs(timeUs, null) ?: return@withContext null
                    val maxDim = maxOf(nativeBmp.width, nativeBmp.height)
                    if (maxEdgePx > 0 && maxDim > maxEdgePx) {
                        val scale = maxEdgePx.toDouble() / maxDim.toDouble()
                        val dstW = (nativeBmp.width * scale).toInt().coerceAtLeast(2)
                        val dstH = (nativeBmp.height * scale).toInt().coerceAtLeast(2)
                        try {
                            com.example.engine.FrameTransformer.scaleBitmapHighQuality(nativeBmp, dstW, dstH)
                        } finally {
                            if (!nativeBmp.isRecycled) nativeBmp.recycle()
                        }
                    } else {
                        nativeBmp
                    }
                } else {
                    source.getFrameAtTimeUs(timeUs, maxEdgePx)
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun onPreviewViewportSizeChanged(maxEdgePx: Int) {
        if (maxEdgePx <= 0) return
        val bucket = when {
            maxEdgePx <= 480 -> 480
            maxEdgePx <= 640 -> 640
            maxEdgePx <= 720 -> 720
            else -> LiveGifPreviewRenderer.MAX_PREVIEW_LONG_EDGE
        }
        val previous = previewViewportBucketPx
        previewViewportBucketPx = bucket
        // If viewport grew into a sharper bucket while Live Preview is open, re-render crisply
        if (bucket > previous && _editorState.value.previewMode == PreviewMode.GIF_PREVIEW) {
            scheduleLiveGifPreview(debounceMs = 80L)
        }
    }

    fun loadVideoForEditor(uri: Uri) {
        loadVideoJob?.cancel()
        estimateJob?.cancel()
        livePreviewJob?.cancel()

        // Recycle previous preview bitmaps cleanly
        _editorState.value.liveGifPreview?.recycleAll()
        _editorState.value.filmstrip.forEach { if (!it.isRecycled) it.recycle() }
        activeFrameSource?.close()
        activeFrameSource = null

        _editorState.update {
            EditorUiState(
                isLoadingVideo = true,
                settings = it.settings
            )
        }

        loadVideoJob = viewModelScope.launch {
            try {
                val savedSettings = preferencesRepo.savedSettingsFlow.first()
                val source = withContext(Dispatchers.IO) {
                    MediaFrameSourceOpener.open(appContext, uri)
                }
                activeFrameSource = source
                val meta = source.metadata

                val initialSettings = savedSettings.copy(
                    useOriginalFps = true,
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs,
                    cropRect = NormalizedCropRect.FULL,
                    aspectRatioPreset = AspectRatioPreset.ORIGINAL,
                    rotationDegrees = 0,
                    loopCount = meta.loopCount
                )

                _editorState.update {
                    it.copy(
                        isLoadingVideo = false,
                        isLoadingFilmstrip = true,
                        errorMessage = null,
                        metadata = meta,
                        settings = initialSettings,
                        currentPositionMs = 0L,
                        isPlaying = true,
                        previewMode = PreviewMode.ORIGINAL
                    )
                }

                // Kick off filmstrip extraction + initial size estimate in parallel
                launch {
                    try {
                        val strip = LiveGifPreviewRenderer.extractFilmstrip(source, count = 10, thumbMaxEdge = 144)
                        _editorState.update { it.copy(filmstrip = strip, isLoadingFilmstrip = false) }
                    } catch (_: Throwable) {
                        _editorState.update { it.copy(isLoadingFilmstrip = false) }
                    }
                }
                scheduleSizeEstimate(debounceMs = 80L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _editorState.update {
                    it.copy(
                        isLoadingVideo = false,
                        errorMessage = t.message ?: "Failed to load selected file."
                    )
                }
            }
        }
    }

    fun updateEditorSettings(transform: (GifConversionSettings) -> GifConversionSettings) {
        val meta = _editorState.value.metadata
        val updated = transform(_editorState.value.settings).sanitized().let { s ->
            if (meta != null) {
                val start = s.trimStartMs.coerceIn(0L, (meta.durationMs - 50L).coerceAtLeast(0L))
                val end = (if (s.trimEndMs <= start) meta.durationMs else s.trimEndMs)
                    .coerceIn((start + 50L).coerceAtMost(meta.durationMs), meta.durationMs)
                s.copy(trimStartMs = start, trimEndMs = end)
            } else s
        }

        val stalePreviewToRecycle = if (_editorState.value.previewMode != PreviewMode.GIF_PREVIEW) {
            _editorState.value.liveGifPreview
        } else {
            null
        }

        _editorState.update { state ->
            val clampedPos = state.currentPositionMs.coerceIn(
                updated.trimStartMs,
                updated.trimEndMs.coerceAtLeast(updated.trimStartMs)
            )
            state.copy(
                settings = updated,
                currentPositionMs = clampedPos,
                liveGifPreview = if (state.previewMode == PreviewMode.GIF_PREVIEW) state.liveGifPreview else null
            )
        }
        stalePreviewToRecycle?.recycleAll()

        // Persist non-clip-specific preferences
        viewModelScope.launch {
            preferencesRepo.saveLastUsedSettings(updated)
        }

        scheduleSizeEstimate()
        if (_editorState.value.previewMode == PreviewMode.GIF_PREVIEW) {
            scheduleLiveGifPreview()
        }
    }

    fun setPreviewMode(mode: PreviewMode) {
        _editorState.update { it.copy(previewMode = mode) }
        if (mode == PreviewMode.GIF_PREVIEW) {
            scheduleLiveGifPreview(debounceMs = 60L)
        } else {
            livePreviewJob?.cancel()
        }
    }

    fun setActiveEditorTab(tab: EditorTab) {
        _editorState.update { it.copy(activeTab = tab) }
    }

    fun toggleCropOverlay() {
        _editorState.update { it.copy(isCropOverlayVisible = !it.isCropOverlayVisible) }
    }

    fun setPlaybackPosition(ms: Long) {
        val state = _editorState.value
        val maxEnd = state.settings.trimEndMs.takeIf { it > 0L } ?: state.metadata?.durationMs ?: 0L
        val clamped = ms.coerceIn(state.settings.trimStartMs, maxEnd.coerceAtLeast(state.settings.trimStartMs))
        _editorState.update { it.copy(currentPositionMs = clamped) }
    }

    fun togglePlayPause() {
        _editorState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun setPlaying(playing: Boolean) {
        _editorState.update { it.copy(isPlaying = playing) }
    }

    fun stepFrame(direction: Int) {
        val state = _editorState.value
        val meta = state.metadata ?: return
        val trimStart = state.settings.trimStartMs
        val trimEnd = state.settings.trimEndMs.coerceAtLeast(trimStart)
        val nextPos = if (meta.isGifInput && meta.frameTimestampsUs.isNotEmpty()) {
            val curUs = state.currentPositionMs * 1000L
            val curIdx = meta.frameTimestampsUs.indexOfLast { it <= curUs }.coerceAtLeast(0)
            val targetIdx = (curIdx + direction).coerceIn(0, meta.frameTimestampsUs.lastIndex)
            (meta.frameTimestampsUs[targetIdx] / 1000L).coerceIn(trimStart, trimEnd)
        } else {
            val frameStepMs = (1000f / meta.frameRate.coerceIn(5f, 60f)).toLong().coerceAtLeast(33L)
            (state.currentPositionMs + direction * frameStepMs).coerceIn(trimStart, trimEnd)
        }
        _editorState.update { it.copy(isPlaying = false, currentPositionMs = nextPos) }
    }

    fun applyAspectRatioPreset(preset: AspectRatioPreset) {
        val meta = _editorState.value.metadata ?: return
        val rotated = GifGeometryCalculator.rotatedDimensions(
            meta.orientedWidth,
            meta.orientedHeight,
            _editorState.value.settings.rotationDegrees
        )
        val rect = NormalizedCropRect.centeredForAspectRatio(
            sourceWidth = rotated.width,
            sourceHeight = rotated.height,
            targetRatio = preset.ratio
        )
        updateEditorSettings { it.copy(aspectRatioPreset = preset, cropRect = rect) }
    }

    fun rotate90Degrees() {
        updateEditorSettings {
            val nextRot = (it.rotationDegrees + 90) % 360
            it.copy(
                rotationDegrees = nextRot,
                cropRect = NormalizedCropRect.FULL,
                aspectRatioPreset = AspectRatioPreset.ORIGINAL
            )
        }
    }

    fun resetSettingsToDefaults() {
        val meta = _editorState.value.metadata
        viewModelScope.launch {
            preferencesRepo.resetConversionSettingsToDefault()
        }
        val reset = GifConversionSettings.DEFAULT.copy(
            trimStartMs = 0L,
            trimEndMs = meta?.durationMs ?: 0L
        )
        val stalePreview = if (_editorState.value.previewMode != PreviewMode.GIF_PREVIEW) {
            _editorState.value.liveGifPreview
        } else {
            null
        }
        _editorState.update { state ->
            state.copy(
                settings = reset,
                liveGifPreview = if (state.previewMode == PreviewMode.GIF_PREVIEW) state.liveGifPreview else null
            )
        }
        stalePreview?.recycleAll()
        _batchState.update { it.copy(sharedSettings = GifConversionSettings.DEFAULT) }
        syncBatchEstimates()
        if (meta != null) {
            scheduleSizeEstimate(debounceMs = 60L)
            if (_editorState.value.previewMode == PreviewMode.GIF_PREVIEW) {
                scheduleLiveGifPreview(debounceMs = 60L)
            }
        }
    }

    // --- Saved Conversion Presets ---

    fun saveCurrentEditorSettingsAsPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val settings = _editorState.value.settings
        viewModelScope.launch(Dispatchers.IO) {
            presetRepo.saveCustomPreset(trimmed, settings)
        }
    }

    fun saveBatchSettingsAsPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val settings = _batchState.value.sharedSettings
        viewModelScope.launch(Dispatchers.IO) {
            presetRepo.saveCustomPreset(trimmed, settings)
        }
    }

    fun applyPresetToEditor(preset: ConversionPresetEntity) {
        updateEditorSettings { current -> preset.applyTo(current) }
    }

    fun applyPresetToBatch(preset: ConversionPresetEntity) {
        updateBatchSharedSettings { current -> preset.applyTo(current) }
    }

    fun renamePreset(id: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            presetRepo.renameCustomPreset(id, trimmed)
        }
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            presetRepo.deleteCustomPreset(id)
        }
    }

    // --- Target File Size Recommendations ---

    fun analyzeTargetFileSize(targetBytes: Long) {
        val source = activeFrameSource ?: return
        targetSizeJob?.cancel()
        val currentSettings = _editorState.value.settings
        _editorState.update {
            it.copy(
                isAnalyzingTargetSize = true,
                targetSizeRecommendation = null,
                targetSizeError = null
            )
        }
        targetSizeJob = viewModelScope.launch {
            try {
                val recommendation = TargetFileSizeOptimizer.analyze(
                    frameSource = source,
                    currentSettings = currentSettings,
                    targetBytes = targetBytes
                )
                _editorState.update {
                    it.copy(
                        isAnalyzingTargetSize = false,
                        targetSizeRecommendation = recommendation,
                        targetSizeError = null
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _editorState.update {
                    it.copy(
                        isAnalyzingTargetSize = false,
                        targetSizeError = t.message ?: "Unable to analyze target file size."
                    )
                }
            }
        }
    }

    fun acceptTargetSizeRecommendation() {
        val rec = _editorState.value.targetSizeRecommendation ?: return
        _editorState.update {
            it.copy(
                targetSizeRecommendation = null,
                targetSizeError = null
            )
        }
        updateEditorSettings { rec.recommendedSettings }
    }

    fun dismissTargetSizeRecommendation() {
        targetSizeJob?.cancel()
        _editorState.update {
            it.copy(
                isAnalyzingTargetSize = false,
                targetSizeRecommendation = null,
                targetSizeError = null
            )
        }
    }

    private fun scheduleSizeEstimate(debounceMs: Long = 260L) {
        val source = activeFrameSource ?: return
        estimateJob?.cancel()
        _editorState.update { it.copy(isEstimatingSize = true) }
        estimateJob = viewModelScope.launch {
            delay(debounceMs)
            val currentSettings = _editorState.value.settings
            try {
                val estimate = MediaSizeEstimator.estimate(appContext, source, currentSettings)
                _editorState.update {
                    it.copy(isEstimatingSize = false, sizeEstimate = estimate)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _editorState.update { it.copy(isEstimatingSize = false) }
            }
        }
    }

    fun retryLiveGifPreview() {
        scheduleLiveGifPreview(debounceMs = 0L)
    }

    private fun scheduleLiveGifPreview(debounceMs: Long = 320L) {
        val source = activeFrameSource ?: return
        livePreviewJob?.cancel()
        _editorState.update {
            it.copy(
                isRenderingGifPreview = true,
                gifPreviewError = null
            )
        }
        livePreviewJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val currentSettings = _editorState.value.settings
            try {
                val preview = LiveGifPreviewRenderer.renderPreview(
                    frameSource = source,
                    settings = currentSettings,
                    context = appContext,
                    targetMaxEdgePx = previewViewportBucketPx
                )
                val previous = _editorState.value.liveGifPreview
                _editorState.update {
                    it.copy(
                        isRenderingGifPreview = false,
                        gifPreviewError = null,
                        liveGifPreview = preview
                    )
                }
                if (previous != null && previous !== preview) {
                    viewModelScope.launch {
                        delay(360L)
                        previous.recycleAll()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val formatName = if (currentSettings.outputFormat == OutputFormat.WEBP) "WebP" else "GIF"
                _editorState.update {
                    it.copy(
                        isRenderingGifPreview = false,
                        gifPreviewError = t.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Unable to render $formatName preview."
                    )
                }
            }
        }
    }

    // --- Single Video Export via WorkManager ---

    fun startSingleExport() {
        val state = _editorState.value
        val meta = state.metadata ?: return
        val estimatedBytes = state.sizeEstimate?.estimatedBytes

        val request = OneTimeWorkRequestBuilder<GifConversionWorker>()
            .setInputData(
                GifConversionWorker.buildInputData(
                    videoUri = meta.uri,
                    settings = state.settings,
                    sourceName = meta.displayName
                )
            )
            .addTag(GifConversionWorker.TAG_SINGLE_EXPORT)
            .build()

        workManager.enqueue(request)
        _exportState.value = ExportUiState.Converting(
            progress = ConversionProgress(
                percent = 0f,
                currentFrame = 0,
                totalFrames = 0,
                stage = "Preparing media frames…",
                isIndeterminate = true
            ),
            workId = request.id
        )

        observeSingleExportWork(request.id, estimatedBytes)
    }

    private fun observeSingleExportWork(workId: UUID, estimatedBytes: Long?) {
        exportObserverJob?.cancel()
        exportObserverJob = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo == null) return@collect
                val fallbackSettings = _editorState.value.settings
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                        val rawPct = workInfo.progress.getFloat(GifConversionWorker.KEY_PROGRESS_PERCENT, 0f)
                        val pct = rawPct.coerceIn(0f, 0.99f)
                        val isIndeterminate = workInfo.progress.getBoolean(
                            GifConversionWorker.KEY_PROGRESS_INDETERMINATE,
                            rawPct <= 0f
                        )
                        val stage = workInfo.progress.getString(GifConversionWorker.KEY_PROGRESS_STAGE)
                            ?: "Preparing media frames…"
                        val cur = workInfo.progress.getInt(GifConversionWorker.KEY_CURRENT_FRAME, 0)
                        val total = workInfo.progress.getInt(GifConversionWorker.KEY_TOTAL_FRAMES, 0)
                        _exportState.value = ExportUiState.Converting(
                            progress = ConversionProgress(
                                percent = pct,
                                currentFrame = cur,
                                totalFrames = total,
                                stage = stage,
                                isIndeterminate = isIndeterminate
                            ),
                            workId = workId
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val out = workInfo.outputData
                        val uriStr = out.getString(GifConversionWorker.KEY_OUT_URI).orEmpty()
                        val dither = DitherMode.entries.firstOrNull {
                            it.name == out.getString(GifConversionWorker.KEY_OUT_DITHER)
                        } ?: fallbackSettings.ditherMode
                        val outFormat = OutputFormat.entries.firstOrNull {
                            it.name == out.getString(GifConversionWorker.KEY_OUT_FORMAT)
                        } ?: fallbackSettings.outputFormat
                        val webpPreset = WebPQualityPreset.entries.firstOrNull {
                            it.name == out.getString(GifConversionWorker.KEY_OUT_WEBP_QUALITY_PRESET)
                        }
                        val rawWebpQuality = out.getInt(GifConversionWorker.KEY_OUT_WEBP_QUALITY, -1)
                        val stats = EncodedGifStats(
                            width = out.getInt(GifConversionWorker.KEY_OUT_WIDTH, 0),
                            height = out.getInt(GifConversionWorker.KEY_OUT_HEIGHT, 0),
                            fps = out.getFloat(GifConversionWorker.KEY_OUT_FPS, fallbackSettings.fps),
                            durationMs = out.getLong(GifConversionWorker.KEY_OUT_DURATION_MS, 0L),
                            frameCount = out.getInt(GifConversionWorker.KEY_OUT_FRAME_COUNT, 0),
                            fileSizeBytes = out.getLong(GifConversionWorker.KEY_OUT_FILE_SIZE, 0L),
                            effectiveColorCount = out.getInt(GifConversionWorker.KEY_OUT_COLOR_COUNT, 128),
                            ditherMode = dither,
                            outputFormat = outFormat,
                            webpQualityPreset = if (outFormat == OutputFormat.WEBP) {
                                webpPreset ?: fallbackSettings.webpQualityPreset
                            } else {
                                null
                            },
                            webpQuality = if (outFormat == OutputFormat.WEBP) {
                                rawWebpQuality.takeIf { it >= 0 } ?: fallbackSettings.resolvedWebpQuality
                            } else {
                                null
                            },
                            hasTransparency = out.getBoolean(GifConversionWorker.KEY_OUT_HAS_TRANSPARENCY, false)
                        )
                        val defaultName = if (outFormat == OutputFormat.WEBP) "output.webp" else "output.gif"
                        val result = SavedGifResult(
                            contentUri = Uri.parse(uriStr),
                            fileName = out.getString(GifConversionWorker.KEY_OUT_FILE_NAME) ?: defaultName,
                            relativeFolder = out.getString(GifConversionWorker.KEY_OUT_FOLDER)
                                ?: GalleryExporter.GALLERY_FOLDER_DISPLAY,
                            thumbnailPath = out.getString(GifConversionWorker.KEY_OUT_THUMB_PATH)?.takeIf { it.isNotBlank() },
                            stats = stats
                        )
                        _exportState.value = ExportUiState.Success(result, estimatedBytes)
                    }
                    WorkInfo.State.FAILED -> {
                        val err = workInfo.outputData.getString(GifConversionWorker.KEY_ERROR)
                            ?: "Conversion failed. Please try again with different settings."
                        _exportState.value = ExportUiState.Error(err)
                    }
                    WorkInfo.State.CANCELLED -> {
                        _exportState.value = ExportUiState.Cancelled
                    }
                    WorkInfo.State.BLOCKED -> Unit
                }
            }
        }
    }

    fun cancelSingleExport() {
        val current = _exportState.value
        if (current is ExportUiState.Converting) {
            workManager.cancelWorkById(current.workId)
        }
    }

    fun resetExportState() {
        exportObserverJob?.cancel()
        _exportState.value = ExportUiState.Idle
    }

    // --- Persistent Background Batch Conversion Queue ---

    private fun observePersistedBatchQueue() {
        viewModelScope.launch {
            batchQueueRepo.allItems.collect { entities ->
                val activeIds = entities.map { it.id }.toSet()
                // Evict stale thumbnails cleanly
                val staleKeys = batchThumbCache.keys.filter { it !in activeIds }
                staleKeys.forEach { key ->
                    batchThumbCache.remove(key)?.let { if (!it.isRecycled) it.recycle() }
                }

                val estimateMap = batchEstimateManager.estimates.value
                val uiItems = withContext(Dispatchers.IO) {
                    entities.map { entity ->
                        entity.toUiItem(
                            estimateState = estimateMap[entity.id] ?: BatchItemEstimateState.Idle
                        )
                    }
                }

                val total = uiItems.size
                val completed = uiItems.count { it.status == BatchItemStatus.COMPLETED }
                val failed = uiItems.count { it.status == BatchItemStatus.FAILED }
                val cancelled = uiItems.count { it.status == BatchItemStatus.CANCELLED }
                val activeFraction = uiItems
                    .filter { it.status == BatchItemStatus.PROCESSING }
                    .sumOf { it.progress.toDouble() }
                val overall = if (total == 0) {
                    0f
                } else {
                    ((completed + failed + cancelled + activeFraction) / total.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f)
                }

                _batchState.update { state ->
                    state.copy(
                        items = uiItems,
                        completedCount = completed,
                        failedCount = failed,
                        overallProgress = overall,
                        totalSizeEstimate = computeBatchTotalEstimate(uiItems)
                    )
                }

                syncBatchEstimates(uiItems, _batchState.value.sharedSettings)
            }
        }
    }

    private fun observeBatchEstimateUpdates() {
        viewModelScope.launch {
            batchEstimateManager.estimates.collect { estimateMap ->
                _batchState.update { state ->
                    if (state.items.isEmpty()) {
                        state.copy(totalSizeEstimate = BatchTotalSizeEstimate())
                    } else {
                        val updatedItems = state.items.map { item ->
                            val nextEstimate = estimateMap[item.id] ?: BatchItemEstimateState.Idle
                            if (item.estimateState == nextEstimate) item else item.copy(estimateState = nextEstimate)
                        }
                        state.copy(
                            items = updatedItems,
                            totalSizeEstimate = computeBatchTotalEstimate(updatedItems)
                        )
                    }
                }
            }
        }
    }

    private fun syncBatchEstimates(
        items: List<BatchQueueItem> = _batchState.value.items,
        sharedSettings: GifConversionSettings = _batchState.value.sharedSettings
    ) {
        if (items.isEmpty()) {
            batchEstimateManager.clearQueueState(clearCache = false)
            return
        }
        val requests = items.map { item ->
            BatchEstimateRequest(
                id = item.id,
                uri = item.uri,
                durationMs = item.durationMs,
                isCompleted = item.status == BatchItemStatus.COMPLETED,
                isReadable = item.durationMs > 0L && item.width > 0 && item.height > 0,
                customSettings = item.customSettings
            )
        }
        batchEstimateManager.syncQueue(requests, sharedSettings)
    }

    private fun observeBatchWorkerState() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(BatchConversionWorker.UNIQUE_WORK_NAME)
                .collect { infos ->
                    val running = infos.any {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    _batchState.update { it.copy(isRunning = running) }
                }
        }
    }

    private fun BatchQueueEntity.toUiItem(
        estimateState: BatchItemEstimateState = BatchItemEstimateState.Idle
    ): BatchQueueItem {
        val statusEnum = BatchItemStatus.entries.firstOrNull { it.name == status } ?: BatchItemStatus.QUEUED
        val typeEnum = MediaInputType.entries.firstOrNull { it.name == inputType } ?: MediaInputType.VIDEO
        val cachedThumb = batchThumbCache[id] ?: thumbnailPath?.let { path ->
            runCatching {
                val file = File(path)
                if (file.exists()) BitmapFactory.decodeFile(path)?.also { batchThumbCache[id] = it } else null
            }.getOrNull()
        }

        val saved = if (statusEnum == BatchItemStatus.COMPLETED && !savedUri.isNullOrBlank()) {
            val savedFmt = OutputFormat.entries.firstOrNull { it.name == savedOutputFormat }
                ?: if (savedFileName?.endsWith(".webp", ignoreCase = true) == true) OutputFormat.WEBP else OutputFormat.GIF
            val dither = DitherMode.entries.firstOrNull { it.name == savedDitherMode } ?: DitherMode.FLOYD_STEINBERG
            val webpPreset = WebPQualityPreset.entries.firstOrNull { it.name == savedDitherMode }
            SavedGifResult(
                contentUri = Uri.parse(savedUri),
                fileName = savedFileName ?: if (savedFmt == OutputFormat.WEBP) "batch.webp" else "batch.gif",
                relativeFolder = GalleryExporter.GALLERY_FOLDER_DISPLAY,
                thumbnailPath = savedThumbnailPath,
                stats = EncodedGifStats(
                    width = savedWidth,
                    height = savedHeight,
                    fps = savedFps,
                    durationMs = savedDurationMs,
                    frameCount = savedFrameCount,
                    fileSizeBytes = savedFileSizeBytes,
                    effectiveColorCount = savedColorCount,
                    ditherMode = dither,
                    outputFormat = savedFmt,
                    webpQualityPreset = if (savedFmt == OutputFormat.WEBP) webpPreset else null,
                    webpQuality = if (savedFmt == OutputFormat.WEBP) webpPreset?.defaultQuality else null
                )
            )
        } else {
            null
        }

        return BatchQueueItem(
            id = id,
            uri = Uri.parse(uri),
            displayName = displayName,
            durationMs = durationMs,
            width = width,
            height = height,
            inputType = typeEnum,
            thumbnail = cachedThumb,
            status = statusEnum,
            progress = progress,
            isIndeterminate = isIndeterminate,
            stage = stage,
            savedResult = saved,
            errorMessage = errorMessage,
            customSettings = GifSettingsCodec.decode(customSettingsEncoded),
            estimateState = estimateState
        )
    }

    fun addBatchVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _batchState.update { it.copy(isPreparingQueue = true, isCancelled = false) }
        viewModelScope.launch {
            try {
                val entities = withContext(Dispatchers.IO) {
                    val thumbDir = File(appContext.cacheDir, "batch_thumbs").apply { mkdirs() }
                    uris.map { uri ->
                        val itemId = UUID.randomUUID().toString()
                        try {
                            MediaFrameSourceOpener.open(appContext, uri).use { src ->
                                val thumb = src.getFrameAtTimeUs(0L, 180)
                                val thumbPath = thumb?.let { bmp ->
                                    batchThumbCache[itemId] = bmp
                                    val thumbFile = File(thumbDir, "$itemId.jpg")
                                    runCatching {
                                        thumbFile.outputStream().use { out ->
                                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                                        }
                                        thumbFile.absolutePath
                                    }.getOrNull()
                                }
                                BatchQueueEntity(
                                    id = itemId,
                                    sortOrder = 0,
                                    uri = uri.toString(),
                                    displayName = src.metadata.displayName,
                                    durationMs = src.metadata.durationMs,
                                    width = src.metadata.orientedWidth,
                                    height = src.metadata.orientedHeight,
                                    inputType = src.metadata.inputType.name,
                                    thumbnailPath = thumbPath,
                                    status = BatchItemStatus.QUEUED.name,
                                    stage = "Queued"
                                )
                            }
                        } catch (_: Throwable) {
                            BatchQueueEntity(
                                id = itemId,
                                sortOrder = 0,
                                uri = uri.toString(),
                                displayName = uri.lastPathSegment ?: "Unsupported file",
                                durationMs = 0L,
                                width = 0,
                                height = 0,
                                inputType = MediaInputType.VIDEO.name,
                                status = BatchItemStatus.FAILED.name,
                                stage = "Unsupported or corrupted file",
                                errorMessage = "Could not read media metadata."
                            )
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    batchQueueRepo.appendItems(entities)
                }
            } finally {
                _batchState.update { it.copy(isPreparingQueue = false) }
            }
        }
    }

    fun moveBatchItemUp(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.moveItem(itemId, -1)
        }
    }

    fun moveBatchItemDown(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.moveItem(itemId, +1)
        }
    }

    fun removeBatchItem(itemId: String) {
        val target = _batchState.value.items.firstOrNull { it.id == itemId } ?: return
        if (target.status == BatchItemStatus.PROCESSING) return
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.removeItem(itemId)
            runCatching { File(appContext.cacheDir, "batch_thumbs/$itemId.jpg").delete() }
        }
    }

    fun removeCompletedBatchItems() {
        viewModelScope.launch(Dispatchers.IO) {
            val completedIds = batchQueueRepo.getAll()
                .filter { it.status == BatchItemStatus.COMPLETED.name }
                .map { it.id }
            batchQueueRepo.removeCompleted()
            completedIds.forEach { id ->
                runCatching { File(appContext.cacheDir, "batch_thumbs/$id.jpg").delete() }
            }
        }
    }

    fun clearBatchQueue() {
        if (_batchState.value.isRunning) return
        batchEstimateManager.clearQueueState(clearCache = false)
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.clearAll()
            runCatching { File(appContext.cacheDir, "batch_thumbs").deleteRecursively() }
        }
        _batchState.update {
            it.copy(
                overallProgress = 0f,
                completedCount = 0,
                failedCount = 0,
                isCancelled = false,
                totalSizeEstimate = BatchTotalSizeEstimate()
            )
        }
    }

    fun updateBatchSharedSettings(transform: (GifConversionSettings) -> GifConversionSettings) {
        val updated = transform(_batchState.value.sharedSettings).sanitized()
        _batchState.update { it.copy(sharedSettings = updated) }
        syncBatchEstimates(sharedSettings = updated)
        viewModelScope.launch {
            preferencesRepo.saveLastUsedSettings(updated)
        }
    }

    fun setBatchItemCustomSettings(itemId: String, settings: GifConversionSettings?) {
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.setCustomSettings(itemId, settings?.sanitized())
        }
    }

    fun retryBatchItem(itemId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                batchQueueRepo.requeueItem(itemId)
            }
            if (!_batchState.value.isRunning) {
                enqueueBatchWorker()
            }
        }
    }

    fun cancelActiveBatchItem(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.requestCancelItem(itemId)
        }
    }

    fun startBatchConversion() {
        val currentState = _batchState.value
        if (currentState.isRunning || currentState.items.isEmpty()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                batchQueueRepo.resetFailedAndCancelledToQueued()
            }
            _batchState.update { it.copy(isCancelled = false) }
            enqueueBatchWorker()
        }
    }

    private fun enqueueBatchWorker() {
        val shared = _batchState.value.sharedSettings
        val request = OneTimeWorkRequestBuilder<BatchConversionWorker>()
            .setInputData(BatchConversionWorker.buildInputData(shared))
            .addTag(GifConversionWorker.TAG_BATCH_WORK)
            .build()

        workManager.enqueueUniqueWork(
            BatchConversionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelBatchConversion() {
        _batchState.update { it.copy(isCancelled = true) }
        workManager.cancelUniqueWork(BatchConversionWorker.UNIQUE_WORK_NAME)
        workManager.cancelAllWorkByTag(GifConversionWorker.TAG_BATCH_WORK)
        viewModelScope.launch(Dispatchers.IO) {
            batchQueueRepo.markPendingAndActiveCancelled()
        }
    }

    // --- History Actions ---

    fun deleteHistoryItem(item: ConversionHistoryEntity, alsoDeleteGalleryFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (alsoDeleteGalleryFile) {
                GalleryExporter.deleteGalleryFile(appContext, item.contentUri)
            }
            item.thumbnailPath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Throwable) {
                }
            }
            historyRepo.deleteItem(item.id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        batchEstimateManager.cancelAll()
        _editorState.value.liveGifPreview?.recycleAll()
        _editorState.value.filmstrip.forEach { if (!it.isRecycled) it.recycle() }
        batchThumbCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
        batchThumbCache.clear()
        activeFrameSource?.close()
    }
}
