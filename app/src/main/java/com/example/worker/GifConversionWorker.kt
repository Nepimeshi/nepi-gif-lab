package com.example.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.ConversionHistoryEntity
import com.example.data.ConversionHistorySnapshot
import com.example.data.ConversionHistorySnapshotCodec
import com.example.data.GifStudioDatabase
import com.example.data.HistoryRepository
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.FrameTransformer
import com.example.engine.GalleryExporter
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.MediaFrameSourceOpener
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.WebPConversionEngine
import com.example.engine.WebPQualityPreset
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * WorkManager CoroutineWorker for reliable, Play-policy-compliant background GIF and Animated WebP
 * conversion. Supports real-time progress reporting, foreground progress notifications with Cancel,
 * completion notifications with Open/Share, failure notifications with Retry,
 * cooperative cancellation, automatic Gallery saving, and immutable export history snapshots.
 */
class GifConversionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val videoUriStr = inputData.getString(KEY_VIDEO_URI) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing media URI.")
        )
        val initialSourceName = inputData.getString(KEY_SOURCE_NAME)
        val suppressNotifications = inputData.getBoolean(KEY_SUPPRESS_COMPLETION_NOTIFICATION, false)
        val settings = parseSettings(inputData)
        val outputFormat = settings.outputFormat
        val tempFile = File(applicationContext.cacheDir, "anim_export_${id}.${outputFormat.extension}")
        var resolvedSourceName = initialSourceName

        // Best-effort foreground notification for long conversions (never fails if notification permission denied)
        try {
            setForeground(
                ConversionNotificationHelper.createSingleForegroundInfo(
                    context = applicationContext,
                    workId = id,
                    sourceName = resolvedSourceName,
                    progressPercent = 0,
                    stage = "Preparing media frames…",
                    indeterminate = true,
                    outputFormat = outputFormat
                )
            )
        } catch (_: Throwable) {
            // Continue smoothly in background if foreground promotion isn't permitted
        }

        return try {
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_PERCENT to 0f,
                    KEY_PROGRESS_STAGE to "Preparing media frames…",
                    KEY_PROGRESS_INDETERMINATE to true,
                    KEY_CURRENT_FRAME to 0,
                    KEY_TOTAL_FRAMES to 0
                )
            )
            val videoUri = Uri.parse(videoUriStr)
            MediaFrameSourceOpener.open(applicationContext, videoUri).use { source ->
                resolvedSourceName = source.metadata.displayName.takeIf { it.isNotBlank() } ?: resolvedSourceName
                val adjustedSettings = settings.copy(
                    trimEndMs = if (settings.trimEndMs > 0L) settings.trimEndMs else source.metadata.durationMs
                )

                val progressCallback: (com.example.engine.ConversionProgress) -> Unit = { progress ->
                    val clampedPct = progress.percent.coerceIn(0f, 0.96f)
                    val pctInt = (clampedPct * 100f).toInt().coerceIn(0, 96)
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_PERCENT to clampedPct,
                            KEY_PROGRESS_STAGE to progress.stage,
                            KEY_PROGRESS_INDETERMINATE to progress.isIndeterminate,
                            KEY_CURRENT_FRAME to progress.currentFrame,
                            KEY_TOTAL_FRAMES to progress.totalFrames
                        )
                    )
                    try {
                        setForegroundAsync(
                            ConversionNotificationHelper.createSingleForegroundInfo(
                                context = applicationContext,
                                workId = id,
                                sourceName = resolvedSourceName,
                                progressPercent = pctInt,
                                stage = progress.stage,
                                indeterminate = progress.isIndeterminate,
                                outputFormat = outputFormat
                            )
                        )
                    } catch (_: Throwable) {
                    }
                }

                val stats = if (outputFormat == OutputFormat.WEBP) {
                    WebPConversionEngine.encodeToFile(
                        context = applicationContext,
                        frameSource = source,
                        settings = adjustedSettings,
                        outputFile = tempFile,
                        onProgress = progressCallback
                    )
                } else {
                    GifConversionEngine.encodeToFile(
                        frameSource = source,
                        settings = adjustedSettings,
                        outputFile = tempFile,
                        onProgress = progressCallback
                    )
                }

                val savingStage = "Saving to ${GalleryExporter.GALLERY_FOLDER_DISPLAY}…"
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_PERCENT to 0.97f,
                        KEY_PROGRESS_STAGE to savingStage,
                        KEY_PROGRESS_INDETERMINATE to true,
                        KEY_CURRENT_FRAME to stats.frameCount,
                        KEY_TOTAL_FRAMES to stats.frameCount
                    )
                )
                try {
                    setForegroundAsync(
                        ConversionNotificationHelper.createSingleForegroundInfo(
                            context = applicationContext,
                            workId = id,
                            sourceName = resolvedSourceName,
                            progressPercent = 97,
                            stage = savingStage,
                            indeterminate = true,
                            outputFormat = outputFormat
                        )
                    )
                } catch (_: Throwable) {
                }

                // Extract a crisp, aspect-ratio-preserving thumbnail for History
                val thumbBitmap = try {
                    val maxThumbEdge = 320
                    val maxDim = maxOf(stats.width, stats.height).coerceAtLeast(1)
                    val thumbScale = if (maxDim > maxThumbEdge) maxThumbEdge.toFloat() / maxDim.toFloat() else 1f
                    val thumbW = (stats.width * thumbScale).toInt().coerceAtLeast(2)
                    val thumbH = (stats.height * thumbScale).toInt().coerceAtLeast(2)
                    source.getFrameAtTimeUs(adjustedSettings.trimStartMs * 1000L, maxThumbEdge)?.let { raw ->
                        try {
                            FrameTransformer.transformFrame(
                                source = raw,
                                extraRotationDegrees = adjustedSettings.rotationDegrees,
                                cropRect = adjustedSettings.cropRect,
                                targetWidth = thumbW,
                                targetHeight = thumbH
                            )
                        } finally {
                            if (!raw.isRecycled) raw.recycle()
                        }
                    }
                } catch (_: Throwable) {
                    null
                }

                val saved = try {
                    GalleryExporter.saveAnimationToGallery(
                        context = applicationContext,
                        tempFile = tempFile,
                        sourceName = source.metadata.displayName,
                        stats = stats,
                        thumbnailBitmap = thumbBitmap
                    )
                } finally {
                    if (thumbBitmap != null && !thumbBitmap.isRecycled) thumbBitmap.recycle()
                }

                // Build an immutable point-in-time snapshot of the conversion settings for History
                val snapshot = ConversionHistorySnapshot.fromConversion(
                    metadata = source.metadata,
                    settings = adjustedSettings,
                    stats = stats
                )
                val snapshotJson = ConversionHistorySnapshotCodec.encode(snapshot)

                // Persist to local Room history immediately
                val repo = HistoryRepository(GifStudioDatabase.getInstance(applicationContext).historyDao())
                repo.recordExport(
                    ConversionHistoryEntity(
                        contentUri = saved.contentUri.toString(),
                        fileName = saved.fileName,
                        thumbnailPath = saved.thumbnailPath,
                        createdAtMillis = System.currentTimeMillis(),
                        fileSizeBytes = stats.fileSizeBytes,
                        width = stats.width,
                        height = stats.height,
                        fps = stats.fps,
                        durationMs = stats.durationMs,
                        frameCount = stats.frameCount,
                        colorCount = stats.effectiveColorCount,
                        ditherModeName = if (outputFormat == OutputFormat.WEBP) {
                            (stats.webpQualityPreset ?: adjustedSettings.webpQualityPreset).label
                        } else {
                            stats.ditherMode.label
                        },
                        sourceVideoName = source.metadata.displayName,
                        outputFormat = outputFormat.name,
                        sourceInputType = source.metadata.inputType.name,
                        settingsSnapshotJson = snapshotJson
                    )
                )

                ConversionNotificationHelper.cancelNotification(
                    applicationContext,
                    ConversionNotificationHelper.NOTIFICATION_ID_SINGLE_ACTIVE
                )
                if (!suppressNotifications) {
                    ConversionNotificationHelper.postCompletionNotification(
                        context = applicationContext,
                        outputFileName = saved.fileName,
                        fileSizeBytes = stats.fileSizeBytes,
                        contentUri = saved.contentUri,
                        outputFormat = outputFormat
                    )
                }

                Result.success(
                    workDataOf(
                        KEY_OUT_URI to saved.contentUri.toString(),
                        KEY_OUT_FILE_NAME to saved.fileName,
                        KEY_OUT_FOLDER to saved.relativeFolder,
                        KEY_OUT_THUMB_PATH to (saved.thumbnailPath ?: ""),
                        KEY_OUT_WIDTH to stats.width,
                        KEY_OUT_HEIGHT to stats.height,
                        KEY_OUT_FPS to stats.fps,
                        KEY_OUT_DURATION_MS to stats.durationMs,
                        KEY_OUT_FRAME_COUNT to stats.frameCount,
                        KEY_OUT_FILE_SIZE to stats.fileSizeBytes,
                        KEY_OUT_COLOR_COUNT to stats.effectiveColorCount,
                        KEY_OUT_DITHER to stats.ditherMode.name,
                        KEY_OUT_FORMAT to stats.outputFormat.name,
                        KEY_OUT_WEBP_QUALITY_PRESET to (stats.webpQualityPreset?.name ?: ""),
                        KEY_OUT_WEBP_QUALITY to (stats.webpQuality ?: -1),
                        KEY_OUT_HAS_TRANSPARENCY to stats.hasTransparency
                    )
                )
            }
        } catch (ce: CancellationException) {
            ConversionNotificationHelper.cancelNotification(
                applicationContext,
                ConversionNotificationHelper.NOTIFICATION_ID_SINGLE_ACTIVE
            )
            throw ce
        } catch (t: Throwable) {
            val errorMsg = t.message?.takeIf { it.isNotBlank() }
                ?: "Conversion failed due to an unexpected error."
            ConversionNotificationHelper.cancelNotification(
                applicationContext,
                ConversionNotificationHelper.NOTIFICATION_ID_SINGLE_ACTIVE
            )
            if (!suppressNotifications) {
                ConversionNotificationHelper.postFailureNotification(
                    context = applicationContext,
                    sourceName = resolvedSourceName,
                    errorMessage = errorMsg,
                    retryInputData = inputData,
                    outputFormat = outputFormat
                )
            }
            Result.failure(workDataOf(KEY_ERROR to errorMsg))
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    companion object {
        const val TAG_SINGLE_EXPORT = "nepi_single_gif_export"
        const val TAG_BATCH_WORK = "nepi_batch_gif_queue"

        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_SOURCE_NAME = "source_name"
        const val KEY_SUPPRESS_COMPLETION_NOTIFICATION = "suppress_completion_notif"
        const val KEY_RESOLUTION_PRESET = "res_preset"
        const val KEY_CUSTOM_RES = "custom_res"
        const val KEY_COLOR_PRESET = "color_preset"
        const val KEY_CUSTOM_COLORS = "custom_colors"
        const val KEY_DITHER = "dither"
        const val KEY_USE_ORIGINAL_FPS = "use_original_fps"
        const val KEY_FPS = "fps"
        const val KEY_SPEED = "speed"
        const val KEY_REVERSE = "reverse"
        const val KEY_PING_PONG = "ping_pong"
        const val KEY_ROTATION = "rotation"
        const val KEY_CROP_L = "crop_l"
        const val KEY_CROP_T = "crop_t"
        const val KEY_CROP_R = "crop_r"
        const val KEY_CROP_B = "crop_b"
        const val KEY_ASPECT_RATIO = "aspect_ratio"
        const val KEY_TRIM_START = "trim_start"
        const val KEY_TRIM_END = "trim_end"
        const val KEY_LOOP_COUNT = "loop_count"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_WEBP_QUALITY_PRESET = "webp_quality_preset"
        const val KEY_WEBP_QUALITY = "webp_quality"

        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_INDETERMINATE = "progress_indeterminate"
        const val KEY_CURRENT_FRAME = "current_frame"
        const val KEY_TOTAL_FRAMES = "total_frames"
        const val KEY_ERROR = "error"

        const val KEY_OUT_URI = "out_uri"
        const val KEY_OUT_FILE_NAME = "out_file_name"
        const val KEY_OUT_FOLDER = "out_folder"
        const val KEY_OUT_THUMB_PATH = "out_thumb_path"
        const val KEY_OUT_WIDTH = "out_width"
        const val KEY_OUT_HEIGHT = "out_height"
        const val KEY_OUT_FPS = "out_fps"
        const val KEY_OUT_DURATION_MS = "out_duration_ms"
        const val KEY_OUT_FRAME_COUNT = "out_frame_count"
        const val KEY_OUT_FILE_SIZE = "out_file_size"
        const val KEY_OUT_COLOR_COUNT = "out_color_count"
        const val KEY_OUT_DITHER = "out_dither"
        const val KEY_OUT_FORMAT = "out_format"
        const val KEY_OUT_WEBP_QUALITY_PRESET = "out_webp_quality_preset"
        const val KEY_OUT_WEBP_QUALITY = "out_webp_quality"
        const val KEY_OUT_HAS_TRANSPARENCY = "out_has_transparency"

        fun buildInputData(
            videoUri: Uri,
            settings: GifConversionSettings,
            sourceName: String? = null,
            suppressCompletionNotification: Boolean = false
        ): Data = workDataOf(
            KEY_VIDEO_URI to videoUri.toString(),
            KEY_SOURCE_NAME to (sourceName ?: ""),
            KEY_SUPPRESS_COMPLETION_NOTIFICATION to suppressCompletionNotification,
            KEY_RESOLUTION_PRESET to settings.resolutionPreset.name,
            KEY_CUSTOM_RES to settings.customResolutionShortEdge,
            KEY_COLOR_PRESET to settings.colorQualityPreset.name,
            KEY_CUSTOM_COLORS to settings.customColorCount,
            KEY_DITHER to settings.ditherMode.name,
            KEY_USE_ORIGINAL_FPS to settings.useOriginalFps,
            KEY_FPS to settings.fps,
            KEY_SPEED to settings.speed,
            KEY_REVERSE to settings.reverse,
            KEY_PING_PONG to settings.pingPong,
            KEY_ROTATION to settings.rotationDegrees,
            KEY_CROP_L to settings.cropRect.left,
            KEY_CROP_T to settings.cropRect.top,
            KEY_CROP_R to settings.cropRect.right,
            KEY_CROP_B to settings.cropRect.bottom,
            KEY_ASPECT_RATIO to settings.aspectRatioPreset.name,
            KEY_TRIM_START to settings.trimStartMs,
            KEY_TRIM_END to settings.trimEndMs,
            KEY_LOOP_COUNT to settings.loopCount,
            KEY_OUTPUT_FORMAT to settings.outputFormat.name,
            KEY_WEBP_QUALITY_PRESET to settings.webpQualityPreset.name,
            KEY_WEBP_QUALITY to settings.webpQuality
        )

        internal fun parseSettings(data: Data): GifConversionSettings {
            val resPreset = ResolutionPreset.entries.firstOrNull {
                it.name == data.getString(KEY_RESOLUTION_PRESET)
            } ?: ResolutionPreset.P360
            val colorPreset = ColorQualityPreset.entries.firstOrNull {
                it.name == data.getString(KEY_COLOR_PRESET)
            } ?: ColorQualityPreset.AUTO
            val dither = DitherMode.entries.firstOrNull {
                it.name == data.getString(KEY_DITHER)
            } ?: DitherMode.FLOYD_STEINBERG
            val aspectRatio = AspectRatioPreset.entries.firstOrNull {
                it.name == data.getString(KEY_ASPECT_RATIO)
            } ?: AspectRatioPreset.ORIGINAL
            val outputFormat = OutputFormat.entries.firstOrNull {
                it.name == data.getString(KEY_OUTPUT_FORMAT)
            } ?: OutputFormat.GIF
            val webpPreset = WebPQualityPreset.entries.firstOrNull {
                it.name == data.getString(KEY_WEBP_QUALITY_PRESET)
            } ?: WebPQualityPreset.BALANCED

            return GifConversionSettings(
                resolutionPreset = resPreset,
                customResolutionShortEdge = data.getInt(KEY_CUSTOM_RES, 360),
                colorQualityPreset = colorPreset,
                customColorCount = data.getInt(KEY_CUSTOM_COLORS, 128),
                ditherMode = dither,
                useOriginalFps = data.getBoolean(KEY_USE_ORIGINAL_FPS, true),
                fps = data.getFloat(KEY_FPS, 15f),
                speed = data.getFloat(KEY_SPEED, 1f),
                reverse = data.getBoolean(KEY_REVERSE, false),
                pingPong = data.getBoolean(KEY_PING_PONG, false),
                rotationDegrees = data.getInt(KEY_ROTATION, 0),
                cropRect = NormalizedCropRect(
                    left = data.getFloat(KEY_CROP_L, 0f),
                    top = data.getFloat(KEY_CROP_T, 0f),
                    right = data.getFloat(KEY_CROP_R, 1f),
                    bottom = data.getFloat(KEY_CROP_B, 1f)
                ),
                aspectRatioPreset = aspectRatio,
                trimStartMs = data.getLong(KEY_TRIM_START, 0L),
                trimEndMs = data.getLong(KEY_TRIM_END, 0L),
                loopCount = data.getInt(KEY_LOOP_COUNT, 0),
                outputFormat = outputFormat,
                webpQualityPreset = webpPreset,
                webpQuality = data.getInt(KEY_WEBP_QUALITY, 75)
            ).sanitized()
        }
    }
}
