package com.example.data

import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.GifConversionSettings
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.StudioFormatters
import com.example.engine.VideoMetadata
import com.example.engine.WebPQualityPreset
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Immutable point-in-time snapshot of the exact source metadata, output metrics, and conversion
 * settings used when an animation was exported.
 *
 * Stored alongside each [ConversionHistoryEntity] so historical entries never drift when global
 * user preferences change later and remain completely inspectable even if the original source
 * file is subsequently deleted from the device.
 */
data class ConversionHistorySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    // 1. Source information
    val sourceFileName: String,
    val sourceType: MediaInputType,
    val sourceDurationMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceFps: Float?,
    val sourceIsVariableTiming: Boolean,
    // 2. Output information
    val outputFormat: OutputFormat,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputFps: Float,
    val outputDurationMs: Long,
    val outputFileSizeBytes: Long,
    val outputFrameCount: Int,
    val outputHasTransparency: Boolean,
    // 3. Resolution & timing settings
    val resolutionPreset: ResolutionPreset,
    val customResolutionShortEdge: Int?,
    val useOriginalFps: Boolean,
    val manualFps: Float?,
    val detectedSourceFps: Float?,
    // 4. Playback & looping
    val speed: Float,
    val reverse: Boolean,
    val pingPong: Boolean,
    val loopCount: Int,
    // 5. Crop & trim
    val aspectRatioPreset: AspectRatioPreset,
    val cropRect: NormalizedCropRect,
    val rotationDegrees: Int,
    val trimStartMs: Long,
    val trimEndMs: Long,
    // 6a. GIF-specific settings (null for Animated WebP exports)
    val colorQualityPreset: ColorQualityPreset? = null,
    val requestedColorCount: Int? = null,
    val effectiveColorCount: Int? = null,
    val ditherMode: DitherMode? = null,
    val optimizeOutput: Boolean? = null,
    // 6b. WebP-specific settings (null for GIF exports)
    val webpQualityPreset: WebPQualityPreset? = null,
    val webpQuality: Int? = null,
    val webpLossless: Boolean? = null
) {
    val directionLabel: String
        get() {
            val src = if (sourceType == MediaInputType.GIF) "GIF" else "Video"
            return "$src → ${outputFormat.shortLabel}"
        }

    fun compactSummaryLines(): Pair<String, String> {
        val dims = "${outputWidth} × ${outputHeight}"
        val speedLabel = formatSpeedShort(speed)
        val sizeLabel = StudioFormatters.formatFileSize(outputFileSizeBytes)

        return if (outputFormat == OutputFormat.WEBP) {
            val qualityLabel = when {
                webpLossless == true || webpQualityPreset == WebPQualityPreset.LOSSLESS -> "Lossless"
                webpQualityPreset == WebPQualityPreset.CUSTOM && webpQuality != null -> "Quality $webpQuality"
                webpQualityPreset != null && webpQuality != null -> "${webpQualityPreset.shortLabel} ($webpQuality)"
                webpQuality != null -> "Quality $webpQuality"
                else -> "Balanced"
            }
            val line1 = "$dims · $qualityLabel · $speedLabel"
            val line2Parts = buildList {
                if (outputHasTransparency) {
                    add("Transparent")
                } else if (useOriginalFps) {
                    add("Original FPS")
                } else {
                    add("${formatFpsCompact(manualFps ?: outputFps)} FPS")
                }
                if (reverse) add("Reverse")
                if (pingPong) add("Ping-pong")
                add(sizeLabel)
            }
            line1 to line2Parts.joinToString(" · ")
        } else {
            val fpsLabel = if (useOriginalFps) {
                "Original FPS"
            } else {
                "${formatFpsCompact(manualFps ?: outputFps)} FPS"
            }
            val colorsCount = effectiveColorCount ?: requestedColorCount
            val colorLabel = if (colorsCount != null && colorsCount > 0) "$colorsCount colors" else "Auto colors"
            val ditherLabel = when (ditherMode) {
                DitherMode.NONE -> "No dithering"
                DitherMode.ORDERED -> "Ordered dither"
                DitherMode.FLOYD_STEINBERG -> "Floyd–Steinberg"
                null -> "Standard"
            }
            val line1 = "$dims · $fpsLabel · $colorLabel"
            val line2 = "$ditherLabel · $speedLabel · $sizeLabel"
            line1 to line2
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun fromConversion(
            metadata: VideoMetadata,
            settings: GifConversionSettings,
            stats: EncodedGifStats
        ): ConversionHistorySnapshot {
            val clean = settings.sanitized()
            val format = stats.outputFormat
            val effectiveTrimEnd = if (clean.trimEndMs > clean.trimStartMs) {
                clean.trimEndMs.coerceAtMost(metadata.durationMs.coerceAtLeast(clean.trimStartMs))
            } else {
                metadata.durationMs
            }

            return ConversionHistorySnapshot(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                sourceFileName = metadata.displayName,
                sourceType = metadata.inputType,
                sourceDurationMs = metadata.durationMs,
                sourceWidth = metadata.orientedWidth,
                sourceHeight = metadata.orientedHeight,
                sourceFps = metadata.frameRate,
                sourceIsVariableTiming = metadata.isVariableFrameRate,
                outputFormat = format,
                outputWidth = stats.width,
                outputHeight = stats.height,
                outputFps = stats.fps,
                outputDurationMs = stats.durationMs,
                outputFileSizeBytes = stats.fileSizeBytes,
                outputFrameCount = stats.frameCount,
                outputHasTransparency = stats.hasTransparency || metadata.hasTransparency,
                resolutionPreset = clean.resolutionPreset,
                customResolutionShortEdge = if (clean.resolutionPreset == ResolutionPreset.CUSTOM) {
                    clean.customResolutionShortEdge
                } else {
                    null
                },
                useOriginalFps = clean.useOriginalFps,
                manualFps = if (clean.useOriginalFps) null else clean.fps,
                detectedSourceFps = if (clean.useOriginalFps) metadata.frameRate else null,
                speed = clean.speed,
                reverse = clean.reverse,
                pingPong = clean.pingPong,
                loopCount = clean.loopCount,
                aspectRatioPreset = clean.aspectRatioPreset,
                cropRect = clean.cropRect,
                rotationDegrees = clean.rotationDegrees,
                trimStartMs = clean.trimStartMs,
                trimEndMs = effectiveTrimEnd,
                // Store only relevant settings for the selected output format
                colorQualityPreset = if (format == OutputFormat.GIF) clean.colorQualityPreset else null,
                requestedColorCount = if (format == OutputFormat.GIF) clean.resolvedColorCount else null,
                effectiveColorCount = if (format == OutputFormat.GIF) stats.effectiveColorCount else null,
                ditherMode = if (format == OutputFormat.GIF) stats.ditherMode else null,
                optimizeOutput = if (format == OutputFormat.GIF) clean.optimizeOutput else null,
                webpQualityPreset = if (format == OutputFormat.WEBP) {
                    stats.webpQualityPreset ?: clean.webpQualityPreset
                } else {
                    null
                },
                webpQuality = if (format == OutputFormat.WEBP) {
                    stats.webpQuality ?: clean.resolvedWebpQuality
                } else {
                    null
                },
                webpLossless = if (format == OutputFormat.WEBP) clean.isWebpLossless else null
            )
        }

        fun formatSpeedShort(speed: Float): String {
            val rounded = speed.roundToInt()
            return if (abs(speed - rounded) < 0.01f) {
                "${rounded}× speed"
            } else {
                val formatted = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
                "${formatted}× speed"
            }
        }

        fun formatFpsCompact(fps: Float): String {
            val rounded = fps.roundToInt()
            return if (abs(fps - rounded) < 0.05f) {
                rounded.toString()
            } else {
                String.format(Locale.US, "%.1f", fps)
            }
        }
    }
}

/**
 * Zero-dependency JSON codec for [ConversionHistorySnapshot] using Android's `org.json.JSONObject`.
 */
object ConversionHistorySnapshotCodec {

    fun encode(snapshot: ConversionHistorySnapshot): String {
        val obj = JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("sourceFileName", snapshot.sourceFileName)
            put("sourceType", snapshot.sourceType.name)
            put("sourceDurationMs", snapshot.sourceDurationMs)
            put("sourceWidth", snapshot.sourceWidth)
            put("sourceHeight", snapshot.sourceHeight)
            snapshot.sourceFps?.let { put("sourceFps", it.toDouble()) }
            put("sourceIsVariableTiming", snapshot.sourceIsVariableTiming)

            put("outputFormat", snapshot.outputFormat.name)
            put("outputWidth", snapshot.outputWidth)
            put("outputHeight", snapshot.outputHeight)
            put("outputFps", snapshot.outputFps.toDouble())
            put("outputDurationMs", snapshot.outputDurationMs)
            put("outputFileSizeBytes", snapshot.outputFileSizeBytes)
            put("outputFrameCount", snapshot.outputFrameCount)
            put("outputHasTransparency", snapshot.outputHasTransparency)

            put("resolutionPreset", snapshot.resolutionPreset.name)
            snapshot.customResolutionShortEdge?.let { put("customResolutionShortEdge", it) }
            put("useOriginalFps", snapshot.useOriginalFps)
            snapshot.manualFps?.let { put("manualFps", it.toDouble()) }
            snapshot.detectedSourceFps?.let { put("detectedSourceFps", it.toDouble()) }

            put("speed", snapshot.speed.toDouble())
            put("reverse", snapshot.reverse)
            put("pingPong", snapshot.pingPong)
            put("loopCount", snapshot.loopCount)

            put("aspectRatioPreset", snapshot.aspectRatioPreset.name)
            put("cropLeft", snapshot.cropRect.left.toDouble())
            put("cropTop", snapshot.cropRect.top.toDouble())
            put("cropRight", snapshot.cropRect.right.toDouble())
            put("cropBottom", snapshot.cropRect.bottom.toDouble())
            put("rotationDegrees", snapshot.rotationDegrees)
            put("trimStartMs", snapshot.trimStartMs)
            put("trimEndMs", snapshot.trimEndMs)

            // Format-specific keys stored ONLY when applicable
            if (snapshot.outputFormat == OutputFormat.GIF) {
                snapshot.colorQualityPreset?.let { put("colorQualityPreset", it.name) }
                snapshot.requestedColorCount?.let { put("requestedColorCount", it) }
                snapshot.effectiveColorCount?.let { put("effectiveColorCount", it) }
                snapshot.ditherMode?.let { put("ditherMode", it.name) }
                snapshot.optimizeOutput?.let { put("optimizeOutput", it) }
            } else {
                snapshot.webpQualityPreset?.let { put("webpQualityPreset", it.name) }
                snapshot.webpQuality?.let { put("webpQuality", it) }
                snapshot.webpLossless?.let { put("webpLossless", it) }
            }
        }
        return obj.toString()
    }

    fun decode(json: String?): ConversionHistorySnapshot? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            val outputFormat = OutputFormat.entries.firstOrNull {
                it.name == obj.optString("outputFormat")
            } ?: OutputFormat.GIF
            val sourceType = MediaInputType.entries.firstOrNull {
                it.name == obj.optString("sourceType")
            } ?: MediaInputType.VIDEO
            val resPreset = ResolutionPreset.entries.firstOrNull {
                it.name == obj.optString("resolutionPreset")
            } ?: ResolutionPreset.P360
            val aspectPreset = AspectRatioPreset.entries.firstOrNull {
                it.name == obj.optString("aspectRatioPreset")
            } ?: AspectRatioPreset.ORIGINAL

            ConversionHistorySnapshot(
                schemaVersion = obj.optInt("schemaVersion", 1),
                sourceFileName = obj.optString("sourceFileName", ""),
                sourceType = sourceType,
                sourceDurationMs = obj.optLong("sourceDurationMs", 0L),
                sourceWidth = obj.optInt("sourceWidth", 0),
                sourceHeight = obj.optInt("sourceHeight", 0),
                sourceFps = if (obj.has("sourceFps") && !obj.isNull("sourceFps")) {
                    obj.optDouble("sourceFps").toFloat().takeIf { it.isFinite() && it > 0f }
                } else {
                    null
                },
                sourceIsVariableTiming = obj.optBoolean("sourceIsVariableTiming", false),
                outputFormat = outputFormat,
                outputWidth = obj.optInt("outputWidth", 0),
                outputHeight = obj.optInt("outputHeight", 0),
                outputFps = obj.optDouble("outputFps", 0.0).toFloat(),
                outputDurationMs = obj.optLong("outputDurationMs", 0L),
                outputFileSizeBytes = obj.optLong("outputFileSizeBytes", 0L),
                outputFrameCount = obj.optInt("outputFrameCount", 0),
                outputHasTransparency = obj.optBoolean("outputHasTransparency", false),
                resolutionPreset = resPreset,
                customResolutionShortEdge = if (obj.has("customResolutionShortEdge") && !obj.isNull("customResolutionShortEdge")) {
                    obj.optInt("customResolutionShortEdge")
                } else {
                    null
                },
                useOriginalFps = obj.optBoolean("useOriginalFps", true),
                manualFps = if (obj.has("manualFps") && !obj.isNull("manualFps")) {
                    obj.optDouble("manualFps").toFloat()
                } else {
                    null
                },
                detectedSourceFps = if (obj.has("detectedSourceFps") && !obj.isNull("detectedSourceFps")) {
                    obj.optDouble("detectedSourceFps").toFloat()
                } else {
                    null
                },
                speed = obj.optDouble("speed", 1.0).toFloat(),
                reverse = obj.optBoolean("reverse", false),
                pingPong = obj.optBoolean("pingPong", false),
                loopCount = obj.optInt("loopCount", 0),
                aspectRatioPreset = aspectPreset,
                cropRect = NormalizedCropRect(
                    left = obj.optDouble("cropLeft", 0.0).toFloat(),
                    top = obj.optDouble("cropTop", 0.0).toFloat(),
                    right = obj.optDouble("cropRight", 1.0).toFloat(),
                    bottom = obj.optDouble("cropBottom", 1.0).toFloat()
                ).clamped(),
                rotationDegrees = obj.optInt("rotationDegrees", 0),
                trimStartMs = obj.optLong("trimStartMs", 0L),
                trimEndMs = obj.optLong("trimEndMs", 0L),
                colorQualityPreset = if (outputFormat == OutputFormat.GIF) {
                    ColorQualityPreset.entries.firstOrNull { it.name == obj.optString("colorQualityPreset") }
                } else {
                    null
                },
                requestedColorCount = if (outputFormat == OutputFormat.GIF && obj.has("requestedColorCount") && !obj.isNull("requestedColorCount")) {
                    obj.optInt("requestedColorCount")
                } else {
                    null
                },
                effectiveColorCount = if (outputFormat == OutputFormat.GIF && obj.has("effectiveColorCount") && !obj.isNull("effectiveColorCount")) {
                    obj.optInt("effectiveColorCount")
                } else {
                    null
                },
                ditherMode = if (outputFormat == OutputFormat.GIF) {
                    DitherMode.entries.firstOrNull { it.name == obj.optString("ditherMode") }
                } else {
                    null
                },
                optimizeOutput = if (outputFormat == OutputFormat.GIF && obj.has("optimizeOutput") && !obj.isNull("optimizeOutput")) {
                    obj.optBoolean("optimizeOutput")
                } else {
                    null
                },
                webpQualityPreset = if (outputFormat == OutputFormat.WEBP) {
                    WebPQualityPreset.entries.firstOrNull { it.name == obj.optString("webpQualityPreset") }
                } else {
                    null
                },
                webpQuality = if (outputFormat == OutputFormat.WEBP && obj.has("webpQuality") && !obj.isNull("webpQuality")) {
                    obj.optInt("webpQuality")
                } else {
                    null
                },
                webpLossless = if (outputFormat == OutputFormat.WEBP && obj.has("webpLossless") && !obj.isNull("webpLossless")) {
                    obj.optBoolean("webpLossless")
                } else {
                    null
                }
            )
        } catch (_: Throwable) {
            null
        }
    }
}
