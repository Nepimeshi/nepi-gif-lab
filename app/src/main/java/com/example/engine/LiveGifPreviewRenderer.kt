package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LiveGifPreviewData(
    val frames: List<Bitmap>,
    val frameDelayMs: Int,
    val frameDelaysMs: List<Int> = emptyList(),
    val outputWidth: Int,
    val outputHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val resolvedColorCount: Int,
    val totalClipFrames: Int,
    val isSampledPreview: Boolean,
    val globalPaletteColors: List<Int> = emptyList(),
    val outputFormat: OutputFormat = OutputFormat.GIF,
    val webpQualityLabel: String? = null,
    val hasTransparency: Boolean = false,
    val renderedDitherMode: DitherMode? = null
) {
    fun recycleAll() {
        frames.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}

/**
 * Memory-budgeted preview resolution & frame-count plan derived from actual output dimensions,
 * measured preview viewport pixel size, and device memory headroom.
 */
internal data class PreviewRenderBudget(
    val previewWidth: Int,
    val previewHeight: Int,
    val maxFrames: Int,
    val memoryBudgetBytes: Long
)

/**
 * Generates an accurate, display-matched preview reflecting every conversion setting:
 * - For [OutputFormat.GIF]: shared global color palette + dithering mode matching [GifConversionEngine].
 *   Uses exact 1:1 output resolution whenever output fits within the display/memory budget,
 *   eliminating upscaled dithering grain and double-scaling blur.
 * - For [OutputFormat.WEBP]: genuine WebP lossless/lossy compression round-trip preserving 24-bit
 *   sRGB color and 8-bit alpha channel matching [WebPConversionEngine].
 */
object LiveGifPreviewRenderer {

    internal const val DEFAULT_PREVIEW_LONG_EDGE = 720
    internal const val MIN_PREVIEW_LONG_EDGE = 360
    internal const val MAX_PREVIEW_LONG_EDGE = 960
    internal const val LOW_RAM_MAX_PREVIEW_LONG_EDGE = 540

    private const val MAX_PREVIEW_FRAMES = 36
    private const val MIN_PREVIEW_FRAMES = 8

    private const val STANDARD_MEMORY_BUDGET_BYTES = 24L * 1024L * 1024L
    private const val LOW_RAM_MEMORY_BUDGET_BYTES = 10L * 1024L * 1024L
    private const val LOW_RAM_HEAP_THRESHOLD_BYTES = 256L * 1024L * 1024L

    internal fun computePreviewBudget(
        outputDims: OutputDimensions,
        targetMaxEdgePx: Int = DEFAULT_PREVIEW_LONG_EDGE,
        isLowRamDevice: Boolean = false,
        memoryBudgetBytesOverride: Long? = null
    ): PreviewRenderBudget {
        val maxEdgeCap = if (isLowRamDevice) LOW_RAM_MAX_PREVIEW_LONG_EDGE else MAX_PREVIEW_LONG_EDGE
        val requestedEdge = targetMaxEdgePx.coerceIn(MIN_PREVIEW_LONG_EDGE, maxEdgeCap)
        val memoryBudget = (memoryBudgetBytesOverride ?: if (isLowRamDevice) {
            LOW_RAM_MEMORY_BUDGET_BYTES
        } else {
            STANDARD_MEMORY_BUDGET_BYTES
        }).coerceAtLeast(2L * 1024L * 1024L)

        val maxOut = max(outputDims.width, outputDims.height).coerceAtLeast(2)
        // Never upscale above actual export dimensions: preserve authentic low-res output look
        // when exporting small sizes, and render 1:1 whenever output fits inside requestedEdge.
        var previewW: Int
        var previewH: Int
        if (maxOut <= requestedEdge) {
            previewW = outputDims.width.coerceAtLeast(2)
            previewH = outputDims.height.coerceAtLeast(2)
        } else {
            val scale = requestedEdge.toDouble() / maxOut.toDouble()
            previewW = max(2, (outputDims.width * scale).roundToInt())
            previewH = max(2, (outputDims.height * scale).roundToInt())
        }

        // Enforce strict total preview RAM budget even for extreme aspect ratios or constrained devices.
        var frameBytes = previewW.toLong() * previewH.toLong() * 4L
        val maxBytesPerFrameForMinCount = (memoryBudget / MIN_PREVIEW_FRAMES).coerceAtLeast(16_384L)
        if (frameBytes > maxBytesPerFrameForMinCount) {
            val downscale = sqrt(maxBytesPerFrameForMinCount.toDouble() / frameBytes.toDouble())
            previewW = max(2, (previewW * downscale).roundToInt())
            previewH = max(2, (previewH * downscale).roundToInt())
            frameBytes = previewW.toLong() * previewH.toLong() * 4L
        }

        val maxFrames = (memoryBudget / frameBytes.coerceAtLeast(1L))
            .toInt()
            .coerceIn(MIN_PREVIEW_FRAMES, MAX_PREVIEW_FRAMES)

        return PreviewRenderBudget(
            previewWidth = previewW,
            previewHeight = previewH,
            maxFrames = maxFrames,
            memoryBudgetBytes = memoryBudget
        )
    }

    private fun isLowMemoryEnvironment(context: Context?): Boolean {
        val amLowRam = try {
            val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        } catch (_: Throwable) {
            false
        }
        val smallHeap = Runtime.getRuntime().maxMemory() in 1L until LOW_RAM_HEAP_THRESHOLD_BYTES
        return amLowRam || smallHeap
    }

    suspend fun renderPreview(
        frameSource: VideoFrameSource,
        settings: GifConversionSettings,
        context: Context? = null,
        targetMaxEdgePx: Int = DEFAULT_PREVIEW_LONG_EDGE
    ): LiveGifPreviewData = withContext(Dispatchers.Default) {
        val clean = settings.sanitized()
        val metadata = frameSource.metadata

        val outputDims = GifGeometryCalculator.calculateOutputDimensions(
            orientedSourceWidth = metadata.orientedWidth,
            orientedSourceHeight = metadata.orientedHeight,
            settings = clean
        )

        val schedule = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = clean.trimStartMs,
            trimEndMs = if (clean.trimEndMs > 0L) clean.trimEndMs else metadata.durationMs,
            videoDurationMs = metadata.durationMs,
            fps = clean.fps,
            speed = clean.speed,
            reverse = clean.reverse,
            pingPong = clean.pingPong,
            useOriginalFps = clean.useOriginalFps,
            sourceFps = metadata.frameRate,
            sourceFrameTimestampsUs = metadata.frameTimestampsUs,
            sourceFrameDelaysMs = metadata.frameDelaysMs
        )

        val budget = computePreviewBudget(
            outputDims = outputDims,
            targetMaxEdgePx = targetMaxEdgePx,
            isLowRamDevice = isLowMemoryEnvironment(context)
        )
        val previewW = budget.previewWidth
        val previewH = budget.previewHeight

        val totalFrames = schedule.frames.size
        val isSampled = totalFrames > budget.maxFrames
        val previewCount = min(totalFrames, budget.maxFrames)
        val sampleIndices = buildSampleIndices(totalFrames, previewCount)
        val sampleDelaysMs = computeSampledFrameDelays(schedule.frames, sampleIndices)
        val effectivePreviewDelayMs = if (sampleDelaysMs.isNotEmpty()) {
            (sampleDelaysMs.sum().toDouble() / sampleDelaysMs.size.toDouble()).roundToInt().coerceIn(20, 1000)
        } else {
            67
        }

        // For GIF sources, AnimatedGifFrameSource already composites onto its native logical canvas;
        // passing null avoids a redundant single-pass bilinear scale before high-quality transform.
        val isGifSource = frameSource is AnimatedGifFrameSource
        val decodeEdge = if (isGifSource) {
            null
        } else {
            GifConversionEngine.computeDecodeMaxEdge(
                metadata = metadata,
                settings = clean,
                outputDims = OutputDimensions(previewW, previewH)
            )
        }

        if (clean.outputFormat == OutputFormat.WEBP) {
            return@withContext renderWebPPreview(
                frameSource = frameSource,
                clean = clean,
                outputDims = outputDims,
                previewW = previewW,
                previewH = previewH,
                schedule = schedule,
                sampleIndices = sampleIndices,
                sampleDelaysMs = sampleDelaysMs,
                isSampled = isSampled,
                effectivePreviewDelayMs = effectivePreviewDelayMs,
                decodeEdge = decodeEdge,
                context = context
            )
        }

        // Build the shared global palette using the exact same decode hint & strategy as GifConversionEngine
        // so preview palette colors match final export identically.
        val paletteDecodeEdge = if (isGifSource) {
            null
        } else {
            GifConversionEngine.computeDecodeMaxEdge(
                metadata = metadata,
                settings = clean,
                outputDims = outputDims
            )
        }
        val globalPalette = GifConversionEngine.buildGlobalPalette(
            frameSource = frameSource,
            settings = clean,
            outputDims = outputDims,
            schedule = schedule,
            decodeHintMaxEdge = paletteDecodeEdge
        )

        val lookupCache = FrameQuantizer.ExactLookupCache()
        val effectiveDitherMode = if (globalPalette.isPreservedSourcePalette) {
            DitherMode.NONE
        } else {
            clean.ditherMode
        }
        val isExactOutputResolution = previewW == outputDims.width && previewH == outputDims.height
        // When an unresized GIF preserves its exact original palette and only needs display-sized
        // downscaling for preview, re-quantizing blended edge pixels with DitherMode.NONE creates
        // jagged color fringes that do not exist in the full-resolution exported GIF.
        val skipPostDownscaleRequantization = globalPalette.isPreservedSourcePalette && !isExactOutputResolution

        val bitmaps = ArrayList<Bitmap>(sampleIndices.size)
        val frameDelays = ArrayList<Int>(sampleIndices.size)
        val pixelBuffer = if (skipPostDownscaleRequantization) null else IntArray(previewW * previewH)
        var detectedAlpha = metadata.hasTransparency || globalPalette.hasTransparency

        try {
            for ((slotIdx, scheduleIdx) in sampleIndices.withIndex()) {
                coroutineContext.ensureActive()
                val item = schedule.frames[scheduleIdx]
                val raw = frameSource.getFrameAtTimeUs(item.sourceTimeUs, decodeEdge) ?: continue
                val transformed = try {
                    FrameTransformer.transformFrame(
                        source = raw,
                        extraRotationDegrees = clean.rotationDegrees,
                        cropRect = clean.cropRect,
                        targetWidth = previewW,
                        targetHeight = previewH,
                        highQualityDownscale = true
                    )
                } finally {
                    if (!raw.isRecycled) raw.recycle()
                }

                if (!detectedAlpha && hasTransparentPixels(transformed)) {
                    detectedAlpha = true
                }

                if (skipPostDownscaleRequantization) {
                    bitmaps.add(transformed)
                } else {
                    val buf = pixelBuffer!!
                    try {
                        transformed.getPixels(buf, 0, previewW, 0, 0, previewW, previewH)
                    } finally {
                        if (!transformed.isRecycled) transformed.recycle()
                    }
                    val quantized = FrameQuantizer.quantizeWithPalette(
                        sourcePixels = buf,
                        width = previewW,
                        height = previewH,
                        globalPalette = globalPalette,
                        ditherMode = effectiveDitherMode,
                        lookupCache = lookupCache
                    )
                    bitmaps.add(quantized.toBitmap())
                }
                frameDelays.add(sampleDelaysMs[slotIdx])
            }
        } catch (t: Throwable) {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            throw t
        }

        LiveGifPreviewData(
            frames = bitmaps,
            frameDelayMs = effectivePreviewDelayMs,
            frameDelaysMs = frameDelays,
            outputWidth = outputDims.width,
            outputHeight = outputDims.height,
            previewWidth = previewW,
            previewHeight = previewH,
            resolvedColorCount = globalPalette.effectiveColorCount,
            totalClipFrames = totalFrames,
            isSampledPreview = isSampled,
            globalPaletteColors = globalPalette.colors.toList(),
            outputFormat = OutputFormat.GIF,
            webpQualityLabel = null,
            hasTransparency = detectedAlpha,
            renderedDitherMode = effectiveDitherMode
        )
    }

    private suspend fun renderWebPPreview(
        frameSource: VideoFrameSource,
        clean: GifConversionSettings,
        outputDims: OutputDimensions,
        previewW: Int,
        previewH: Int,
        schedule: FrameSchedule,
        sampleIndices: List<Int>,
        sampleDelaysMs: List<Int>,
        isSampled: Boolean,
        effectivePreviewDelayMs: Int,
        decodeEdge: Int?,
        @Suppress("UNUSED_PARAMETER") context: Context?
    ): LiveGifPreviewData = withContext(Dispatchers.Default) {
        val totalFrames = schedule.frames.size
        val bitmaps = ArrayList<Bitmap>(sampleIndices.size)
        val frameDelays = ArrayList<Int>(sampleIndices.size)
        val quality = clean.resolvedWebpQuality.coerceIn(0, 100)
        val isLossless = clean.isWebpLossless
        val qualityLabel = if (isLossless) {
            "Lossless"
        } else {
            "${clean.webpQualityPreset.shortLabel} ($quality)"
        }
        var detectedAlpha = frameSource.metadata.hasTransparency

        try {
            for ((slotIdx, scheduleIdx) in sampleIndices.withIndex()) {
                coroutineContext.ensureActive()
                val item = schedule.frames[scheduleIdx]
                val raw = frameSource.getFrameAtTimeUs(item.sourceTimeUs, decodeEdge) ?: continue
                val transformed = try {
                    FrameTransformer.transformFrame(
                        source = raw,
                        extraRotationDegrees = clean.rotationDegrees,
                        cropRect = clean.cropRect,
                        targetWidth = previewW,
                        targetHeight = previewH,
                        highQualityDownscale = true
                    )
                } finally {
                    if (!raw.isRecycled) raw.recycle()
                }

                if (!detectedAlpha && hasTransparentPixels(transformed)) {
                    detectedAlpha = true
                }

                // Apply actual WebP compression characteristics at preview resolution for lossy modes
                // so the user sees accurate WebP quality directly in Live Preview (lossless is bit-exact).
                val previewBitmap = if (isLossless) {
                    transformed
                } else {
                    roundTripWebPPreviewFrame(transformed, quality) ?: transformed
                }
                bitmaps.add(previewBitmap)
                frameDelays.add(sampleDelaysMs[slotIdx])
            }
        } catch (t: Throwable) {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            throw t
        }

        LiveGifPreviewData(
            frames = bitmaps,
            frameDelayMs = effectivePreviewDelayMs,
            frameDelaysMs = frameDelays,
            outputWidth = outputDims.width,
            outputHeight = outputDims.height,
            previewWidth = previewW,
            previewHeight = previewH,
            resolvedColorCount = 0,
            totalClipFrames = totalFrames,
            isSampledPreview = isSampled,
            globalPaletteColors = emptyList(),
            outputFormat = OutputFormat.WEBP,
            webpQualityLabel = qualityLabel,
            hasTransparency = detectedAlpha,
            renderedDitherMode = null
        )
    }

    private fun buildSampleIndices(totalFrames: Int, previewCount: Int): List<Int> {
        if (totalFrames <= 0 || previewCount <= 0) return emptyList()
        if (previewCount >= totalFrames) return (0 until totalFrames).toList()
        val stride = totalFrames.toDouble() / previewCount.toDouble()
        return List(previewCount) { i ->
            (i * stride).roundToInt().coerceIn(0, totalFrames - 1)
        }.distinct()
    }

    /**
     * Preserves authentic variable frame timing and total clip duration when frames are sampled
     * by summing the actual delays of all schedule frames spanned by each sampled preview slot.
     */
    private fun computeSampledFrameDelays(
        scheduleFrames: List<FrameScheduleItem>,
        sampleIndices: List<Int>
    ): List<Int> {
        if (sampleIndices.isEmpty() || scheduleFrames.isEmpty()) return emptyList()
        return List(sampleIndices.size) { slotIdx ->
            val startIdx = sampleIndices[slotIdx]
            val endExclusive = if (slotIdx + 1 < sampleIndices.size) {
                sampleIndices[slotIdx + 1].coerceAtLeast(startIdx + 1)
            } else {
                scheduleFrames.size
            }
            var summedDelay = 0L
            for (k in startIdx until endExclusive.coerceAtMost(scheduleFrames.size)) {
                summedDelay += scheduleFrames[k].delayMs.toLong().coerceAtLeast(10L)
            }
            summedDelay.toInt().coerceIn(20, 2000)
        }
    }

    @Suppress("DEPRECATION")
    private fun roundTripWebPPreviewFrame(source: Bitmap, quality: Int): Bitmap? {
        return try {
            val baos = ByteArrayOutputStream(32 * 1024)
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            if (!source.compress(format, quality.coerceIn(0, 100), baos)) {
                return null
            }
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (decoded != null && decoded.width == source.width && decoded.height == source.height) {
                if (!source.isRecycled) source.recycle()
                decoded
            } else {
                if (decoded != null && !decoded.isRecycled) decoded.recycle()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (bitmap.config != Bitmap.Config.ARGB_8888 && !bitmap.hasAlpha()) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (((bitmap.getPixel(x, y) ushr 24) and 0xFF) < 250) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /**
     * Decodes a horizontal filmstrip of lightweight thumbnail bitmaps across the video duration.
     */
    suspend fun extractFilmstrip(
        frameSource: VideoFrameSource,
        count: Int = 10,
        thumbMaxEdge: Int = 160
    ): List<Bitmap> = withContext(Dispatchers.Default) {
        val durationMs = frameSource.metadata.durationMs.coerceAtLeast(100L)
        val thumbs = ArrayList<Bitmap>(count)
        try {
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val frac = if (count <= 1) 0.0 else i.toDouble() / (count - 1).toDouble()
                val timeUs = (durationMs * frac * 1000.0).toLong()
                val bmp = frameSource.getFrameAtTimeUs(timeUs, thumbMaxEdge)
                if (bmp != null) {
                    val scale = thumbMaxEdge.toFloat() / max(bmp.width, bmp.height).coerceAtLeast(1)
                    if (scale < 1f) {
                        val scaled = FrameTransformer.scaleBitmapHighQuality(
                            bmp,
                            max(2, (bmp.width * scale).roundToInt()),
                            max(2, (bmp.height * scale).roundToInt())
                        )
                        bmp.recycle()
                        thumbs.add(scaled)
                    } else {
                        thumbs.add(bmp)
                    }
                }
            }
        } catch (t: Throwable) {
            thumbs.forEach { if (!it.isRecycled) it.recycle() }
            throw t
        }
        thumbs
    }
}
