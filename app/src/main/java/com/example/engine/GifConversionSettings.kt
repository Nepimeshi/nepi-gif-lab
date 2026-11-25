package com.example.engine

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class OutputFormat(
    val label: String,
    val shortLabel: String,
    val extension: String,
    val mimeType: String
) {
    GIF("GIF", "GIF", "gif", "image/gif"),
    WEBP("Animated WebP", "WebP", "webp", "image/webp")
}

enum class WebPQualityPreset(
    val label: String,
    val shortLabel: String,
    val description: String,
    val isLossless: Boolean,
    val defaultQuality: Int
) {
    LOSSLESS("Lossless", "Lossless", "Bit-exact colors and alpha with zero generation loss", true, 100),
    HIGH("High Quality", "High", "Crisp detail and smooth gradients at moderate size", false, 88),
    BALANCED("Balanced", "Balanced", "Great visual quality and compact animated size", false, 75),
    SMALLER("Smaller File", "Smaller", "Higher compression for lightweight sharing", false, 52),
    CUSTOM("Custom", "Custom", "Custom numeric quality level (0–100)", false, 75)
}

enum class ResolutionPreset(val label: String, val shortEdgePx: Int?) {
    ORIGINAL("Original", null),
    P240("240p", 240),
    P360("360p", 360),
    P480("480p", 480),
    CUSTOM("Custom", null)
}

enum class ColorQualityPreset(val label: String, val colorCount: Int?) {
    AUTO("Auto", null),
    C16("16", 16),
    C32("32", 32),
    C64("64", 64),
    C96("96", 96),
    C128("128", 128),
    C192("192", 192),
    C256("256", 256),
    CUSTOM("Custom", null)
}

enum class DitherMode(val label: String, val description: String) {
    NONE("None", "Sharp flat colors, smallest file size"),
    ORDERED("Ordered", "Bayer 4×4 matrix pattern, crisp retro look"),
    FLOYD_STEINBERG("Floyd–Steinberg", "Smooth error diffusion, best gradients")
}

enum class AspectRatioPreset(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    SQUARE_1_1("1:1", 1f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_9_16("9:16", 9f / 16f)
}

data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val widthFraction: Float get() = (right - left).coerceIn(0.05f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0.05f, 1f)

    fun isFullFrame(): Boolean =
        left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    fun clamped(): NormalizedCropRect {
        val l = left.coerceIn(0f, 0.95f)
        val t = top.coerceIn(0f, 0.95f)
        val r = right.coerceIn(l + 0.05f, 1f)
        val b = bottom.coerceIn(t + 0.05f, 1f)
        return NormalizedCropRect(l, t, r, b)
    }

    companion object {
        val FULL = NormalizedCropRect(0f, 0f, 1f, 1f)

        fun centeredForAspectRatio(
            sourceWidth: Int,
            sourceHeight: Int,
            targetRatio: Float?
        ): NormalizedCropRect {
            if (targetRatio == null || sourceWidth <= 0 || sourceHeight <= 0) return FULL
            val sourceRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            return if (targetRatio > sourceRatio) {
                val hFrac = (sourceRatio / targetRatio).coerceIn(0.1f, 1f)
                val pad = (1f - hFrac) / 2f
                NormalizedCropRect(0f, pad, 1f, 1f - pad)
            } else {
                val wFrac = (targetRatio / sourceRatio).coerceIn(0.1f, 1f)
                val pad = (1f - wFrac) / 2f
                NormalizedCropRect(pad, 0f, 1f - pad, 1f)
            }
        }
    }
}

data class OutputDimensions(
    val width: Int,
    val height: Int
)

data class FrameScheduleItem(
    val sourceTimeUs: Long,
    val delayMs: Int
)

data class FrameSchedule(
    val frames: List<FrameScheduleItem>,
    val totalDurationMs: Long,
    val effectiveFps: Float,
    val isVariableTiming: Boolean = false,
    val isGifTimingApproximated: Boolean = false
)

data class GifConversionSettings(
    val resolutionPreset: ResolutionPreset = ResolutionPreset.P360,
    val customResolutionShortEdge: Int = 360,
    val colorQualityPreset: ColorQualityPreset = ColorQualityPreset.AUTO,
    val customColorCount: Int = 128,
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val useOriginalFps: Boolean = true,
    val fps: Float = 15f,
    val speed: Float = 1f,
    val reverse: Boolean = false,
    val pingPong: Boolean = false,
    val rotationDegrees: Int = 0,
    val cropRect: NormalizedCropRect = NormalizedCropRect.FULL,
    val aspectRatioPreset: AspectRatioPreset = AspectRatioPreset.ORIGINAL,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val loopCount: Int = 0,
    val optimizeOutput: Boolean = true,
    val outputFormat: OutputFormat = OutputFormat.GIF,
    val webpQualityPreset: WebPQualityPreset = WebPQualityPreset.BALANCED,
    val webpQuality: Int = 75
) {
    val resolvedColorCount: Int?
        get() = when (colorQualityPreset) {
            ColorQualityPreset.AUTO -> null
            ColorQualityPreset.CUSTOM -> customColorCount.coerceIn(2, 256)
            else -> colorQualityPreset.colorCount
        }

    val isWebpLossless: Boolean
        get() = webpQualityPreset == WebPQualityPreset.LOSSLESS

    val resolvedWebpQuality: Int
        get() = when (webpQualityPreset) {
            WebPQualityPreset.LOSSLESS -> 100
            WebPQualityPreset.CUSTOM -> webpQuality.coerceIn(0, 100)
            else -> webpQualityPreset.defaultQuality
        }

    fun sanitized(): GifConversionSettings = copy(
        customResolutionShortEdge = customResolutionShortEdge.coerceAtLeast(2),
        customColorCount = customColorCount.coerceIn(2, 256),
        fps = fps.coerceIn(1f, 30f),
        speed = speed.coerceIn(0.1f, 4f),
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        cropRect = cropRect.clamped(),
        webpQuality = webpQuality.coerceIn(0, 100)
    )

    companion object {
        val FPS_PRESETS = listOf(5f, 8f, 10f, 12f, 15f, 20f, 24f, 30f)
        val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val DEFAULT = GifConversionSettings()
    }
}

object GifGeometryCalculator {

    /**
     * Dimensions of the video frame after applying user's extra rotation (0/90/180/270).
     * Assumes [orientedSourceWidth] and [orientedSourceHeight] already account for video metadata rotation.
     */
    fun rotatedDimensions(
        orientedSourceWidth: Int,
        orientedSourceHeight: Int,
        extraRotationDegrees: Int
    ): OutputDimensions {
        val normRot = ((extraRotationDegrees % 360) + 360) % 360
        return if (normRot == 90 || normRot == 270) {
            OutputDimensions(orientedSourceHeight.coerceAtLeast(2), orientedSourceWidth.coerceAtLeast(2))
        } else {
            OutputDimensions(orientedSourceWidth.coerceAtLeast(2), orientedSourceHeight.coerceAtLeast(2))
        }
    }

    /**
     * Exact pixel crop rectangle on the rotated source frame.
     */
    fun croppedSourceDimensions(
        orientedSourceWidth: Int,
        orientedSourceHeight: Int,
        extraRotationDegrees: Int,
        cropRect: NormalizedCropRect
    ): OutputDimensions {
        val rotated = rotatedDimensions(orientedSourceWidth, orientedSourceHeight, extraRotationDegrees)
        val c = cropRect.clamped()
        val cropW = (rotated.width * c.widthFraction).roundToInt().coerceIn(2, rotated.width)
        val cropH = (rotated.height * c.heightFraction).roundToInt().coerceIn(2, rotated.height)
        return OutputDimensions(cropW, cropH)
    }

    /**
     * Calculates final GIF width & height:
     * - Preserves cropped aspect ratio
     * - Accounts for portrait and landscape videos by scaling based on the shorter edge (like 240p/360p/480p video standards)
     * - Never upscales beyond the cropped source dimensions
     * - Produces clean even dimensions (>= 2px)
     */
    fun calculateOutputDimensions(
        orientedSourceWidth: Int,
        orientedSourceHeight: Int,
        settings: GifConversionSettings
    ): OutputDimensions {
        val cropped = croppedSourceDimensions(
            orientedSourceWidth = orientedSourceWidth,
            orientedSourceHeight = orientedSourceHeight,
            extraRotationDegrees = settings.rotationDegrees,
            cropRect = settings.cropRect
        )

        val targetShortEdge: Int? = when (settings.resolutionPreset) {
            ResolutionPreset.ORIGINAL -> null
            ResolutionPreset.CUSTOM -> settings.customResolutionShortEdge.coerceAtLeast(2)
            else -> settings.resolutionPreset.shortEdgePx
        }

        if (targetShortEdge == null) {
            return OutputDimensions(makeEven(cropped.width), makeEven(cropped.height))
        }

        val currentShortEdge = min(cropped.width, cropped.height)
        // Never upscale unnecessarily
        if (currentShortEdge <= targetShortEdge) {
            return OutputDimensions(makeEven(cropped.width), makeEven(cropped.height))
        }

        val scale = targetShortEdge.toDouble() / currentShortEdge.toDouble()
        val outW = makeEven((cropped.width * scale).roundToInt())
        val outH = makeEven((cropped.height * scale).roundToInt())
        return OutputDimensions(outW, outH)
    }

    /**
     * Builds the exact frame extraction schedule for encoding and preview.
     * - When [useOriginalFps] is true, preserves the source video's detected frame rate and (when
     *   available) actual VFR presentation timestamps as closely as GIF's 10ms centisecond timing
     *   and 20ms viewer-safe minimum delay permit, using error-diffused centisecond delays.
     * - When [useOriginalFps] is false, uses the user's explicit manual [fps] setting.
     * - Changing playback speed scales the output duration and frame timing.
     * - Reverse reverses frame order.
     * - Ping-pong appends reversed interior frames (N-2 down to 1) so endpoints are never duplicated.
     */
    fun buildFrameSchedule(
        trimStartMs: Long,
        trimEndMs: Long,
        videoDurationMs: Long,
        fps: Float,
        speed: Float,
        reverse: Boolean,
        pingPong: Boolean,
        useOriginalFps: Boolean = false,
        sourceFps: Float? = null,
        sourceFrameTimestampsUs: List<Long> = emptyList(),
        sourceFrameDelaysMs: List<Int> = emptyList()
    ): FrameSchedule {
        val safeDuration = max(100L, videoDurationMs)
        val start = trimStartMs.coerceIn(0L, safeDuration - 50L)
        val end = (if (trimEndMs <= start) safeDuration else trimEndMs).coerceIn(start + 50L, safeDuration)
        val clipDurationMs = max(50L, end - start)
        val safeSpeed = speed.coerceIn(0.1f, 4f)

        val baseItems: List<FrameScheduleItem>
        var isVariableTiming = false
        var isGifApproximated = false

        if (useOriginalFps) {
            val startUs = start * 1000L
            val endUs = end * 1000L
            val hasExplicitFrameDelays = sourceFrameDelaysMs.isNotEmpty() &&
                sourceFrameDelaysMs.size == sourceFrameTimestampsUs.size

            if (hasExplicitFrameDelays) {
                // Exact per-frame delay table (e.g., animated GIF inputs):
                // preserves individual variable delays, total duration, and single-frame GIFs
                // without unnecessarily dropping or duplicating frames.
                val overlapping = ArrayList<Pair<Long, Long>>(sourceFrameDelaysMs.size)
                for (i in sourceFrameDelaysMs.indices) {
                    val frameStartMs = sourceFrameTimestampsUs[i] / 1000L
                    val frameDelayMs = sourceFrameDelaysMs[i].toLong().coerceAtLeast(10L)
                    val frameEndMs = frameStartMs + frameDelayMs
                    val clippedStart = max(frameStartMs, start)
                    val clippedEnd = min(frameEndMs, end)
                    if (clippedEnd > clippedStart) {
                        overlapping.add(sourceFrameTimestampsUs[i] to (clippedEnd - clippedStart))
                    }
                }
                if (overlapping.isEmpty() && sourceFrameTimestampsUs.isNotEmpty()) {
                    // Fallback to closest frame inside trim range
                    val closestIdx = sourceFrameTimestampsUs.indexOfLast { it <= startUs }.coerceAtLeast(0)
                    overlapping.add(sourceFrameTimestampsUs[closestIdx] to clipDurationMs)
                }

                val built = ArrayList<FrameScheduleItem>(overlapping.size)
                var accumulatedMs = 0.0
                var pendingSourceTs = overlapping[0].first
                val distinctDelays = HashSet<Int>()

                for (i in overlapping.indices) {
                    val (_, clippedMs) = overlapping[i]
                    val scaledMs = clippedMs.toDouble() / safeSpeed.toDouble()
                    accumulatedMs += scaledMs

                    val isLast = i == overlapping.size - 1
                    if (accumulatedMs < 19.5 && !isLast) {
                        isGifApproximated = true
                        continue
                    }

                    val roundedCs = (accumulatedMs / 10.0).roundToInt().coerceIn(2, 6553)
                    val delayMs = roundedCs * 10
                    if (kotlin.math.abs(accumulatedMs - delayMs) > 0.5) {
                        isGifApproximated = true
                    }
                    accumulatedMs -= delayMs
                    distinctDelays.add(delayMs)
                    built.add(FrameScheduleItem(sourceTimeUs = pendingSourceTs, delayMs = delayMs))
                    if (!isLast) {
                        pendingSourceTs = overlapping[i + 1].first
                    }
                }
                baseItems = built
                isVariableTiming = distinctDelays.size > 1
            } else {
                val inRangeTs = if (sourceFrameTimestampsUs.size >= 2) {
                    sourceFrameTimestampsUs.filter { it in startUs..endUs }
                } else {
                    emptyList()
                }

                if (inRangeTs.size >= 2) {
                    // Variable- or constant-frame-rate clip with real container presentation timestamps
                    val built = ArrayList<FrameScheduleItem>(inRangeTs.size)
                    var accumulatedMs = 0.0
                    var pendingSourceTs = inRangeTs[0]
                    val distinctDelays = HashSet<Int>()

                    for (i in 0 until inRangeTs.size) {
                        val rawDeltaUs = if (i < inRangeTs.size - 1) {
                            (inRangeTs[i + 1] - inRangeTs[i]).coerceAtLeast(1000L)
                        } else {
                            val tailUs = (endUs - inRangeTs[i]).coerceAtLeast(0L)
                            val prevDeltaUs = (inRangeTs[i] - inRangeTs[i - 1]).coerceAtLeast(1000L)
                            if (tailUs > prevDeltaUs / 3L) tailUs.coerceAtMost(prevDeltaUs * 2L) else prevDeltaUs
                        }
                        val scaledMs = (rawDeltaUs / 1000.0) / safeSpeed.toDouble()
                        accumulatedMs += scaledMs

                        // GIF viewers reliably support >= 20ms (2 centiseconds). Coalesce faster sub-20ms
                        // source frames (e.g. 60fps or 2x speed) so total duration stays accurate.
                        val isLast = i == inRangeTs.size - 1
                        if (accumulatedMs < 19.5 && !isLast) {
                            isGifApproximated = true
                            continue
                        }

                        val roundedCs = (accumulatedMs / 10.0).roundToInt().coerceIn(2, 6553)
                        val delayMs = roundedCs * 10
                        if (kotlin.math.abs(accumulatedMs - delayMs) > 0.5) {
                            isGifApproximated = true
                        }
                        accumulatedMs -= delayMs
                        distinctDelays.add(delayMs)
                        built.add(FrameScheduleItem(sourceTimeUs = pendingSourceTs, delayMs = delayMs))
                        if (i < inRangeTs.size - 1) {
                            pendingSourceTs = inRangeTs[i + 1]
                        }
                    }
                    baseItems = built
                    isVariableTiming = distinctDelays.size > 1
                } else {
                    // Source FPS without individual timestamp table: preserve true source FPS up to
                    // GIF's 50 FPS (20ms min delay) ceiling with error-diffused centisecond delays.
                    val detectedFps = (sourceFps ?: fps).coerceIn(1f, 120f)
                    val targetGifFps = detectedFps.coerceAtMost(50f)
                    if (detectedFps > 50f) {
                        isGifApproximated = true
                    }
                    val idealDelayMs = 1000.0 / targetGifFps.toDouble()
                    if (kotlin.math.abs(idealDelayMs - ((idealDelayMs / 10.0).roundToInt() * 10.0)) > 0.25) {
                        isGifApproximated = true
                    }

                    val singlePassOutputMs = (clipDurationMs / safeSpeed.toDouble()).roundToLong().coerceAtLeast(20L)
                    val baseFrameCount = max(1, (singlePassOutputMs.toDouble() / idealDelayMs).roundToInt())
                    var errorMs = 0.0
                    val distinctDelays = HashSet<Int>()

                    baseItems = List(baseFrameCount) { index ->
                        val progress = if (baseFrameCount == 1) 0.0 else index.toDouble() / (baseFrameCount - 1).toDouble()
                        val timeMs = start + (clipDurationMs * progress).roundToLong()
                        val targetWithErr = idealDelayMs + errorMs
                        val delayMs = ((targetWithErr / 10.0).roundToInt() * 10).coerceIn(20, 1000)
                        errorMs = targetWithErr - delayMs
                        distinctDelays.add(delayMs)
                        FrameScheduleItem(sourceTimeUs = timeMs.coerceIn(start, end) * 1000L, delayMs = delayMs)
                    }
                    isVariableTiming = distinctDelays.size > 1
                }
            }
        } else {
            // Explicit manual FPS mode
            val safeFps = fps.coerceIn(1f, 30f)
            val rawDelayMs = (1000.0 / safeFps).roundToInt()
            val delayMs = ((rawDelayMs / 10.0).roundToInt() * 10).coerceIn(20, 1000)
            if (kotlin.math.abs((1000.0 / safeFps) - delayMs) > 0.5) {
                isGifApproximated = true
            }

            val singlePassOutputMs = (clipDurationMs / safeSpeed.toDouble()).roundToLong().coerceAtLeast(delayMs.toLong())
            val baseFrameCount = max(1, (singlePassOutputMs.toDouble() / delayMs.toDouble()).roundToInt())

            baseItems = List(baseFrameCount) { index ->
                val progress = if (baseFrameCount == 1) 0.0 else index.toDouble() / (baseFrameCount - 1).toDouble()
                val timeMs = start + (clipDurationMs * progress).roundToLong()
                FrameScheduleItem(sourceTimeUs = timeMs.coerceIn(start, end) * 1000L, delayMs = delayMs)
            }
        }

        val orderedItems = if (reverse) baseItems.asReversed() else baseItems

        val finalItems = if (pingPong && orderedItems.size > 2) {
            val result = ArrayList<FrameScheduleItem>(orderedItems.size * 2 - 2)
            result.addAll(orderedItems)
            // Exclude last (N-1) and first (0) to avoid duplicate turnaround frames
            for (i in orderedItems.size - 2 downTo 1) {
                result.add(orderedItems[i])
            }
            result
        } else {
            orderedItems
        }

        val totalDurationMs = finalItems.sumOf { it.delayMs.toLong() }.coerceAtLeast(20L)
        val effectiveFps = (finalItems.size * 1000f) / totalDurationMs.toFloat()
        return FrameSchedule(
            frames = finalItems,
            totalDurationMs = totalDurationMs,
            effectiveFps = effectiveFps,
            isVariableTiming = isVariableTiming,
            isGifTimingApproximated = isGifApproximated
        )
    }

    private fun makeEven(value: Int): Int {
        val clamped = value.coerceAtLeast(2)
        return if (clamped % 2 == 0) clamped else (clamped - 1).coerceAtLeast(2)
    }
}

object StudioFormatters {
    fun formatTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centiseconds = (clamped % 1000L) / 10L
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, centiseconds)
    }

    /**
     * Parses time strings such as "00:02.50", "02.50", "2.5", "1:05.25", or "12".
     * Returns milliseconds or null if invalid.
     */
    fun parseTimestampMs(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.contains(":")) {
                val parts = trimmed.split(":")
                if (parts.size != 2) return null
                val mins = parts[0].toLong()
                val secs = parts[1].toDouble()
                if (mins < 0 || secs < 0.0 || secs >= 60.0) return null
                (mins * 60_000L) + (secs * 1000.0).roundToLong()
            } else {
                val secs = trimmed.toDouble()
                if (secs < 0.0) return null
                (secs * 1000.0).roundToLong()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return "0 KB"
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb.coerceAtLeast(0.1))
        }
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun formatDurationShort(ms: Long): String {
        val sec = ms.coerceAtLeast(0L) / 1000.0
        return String.format(Locale.US, "%.2fs", sec)
    }
}
