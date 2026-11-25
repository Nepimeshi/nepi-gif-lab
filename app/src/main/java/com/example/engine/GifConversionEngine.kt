package com.example.engine

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ConversionProgress(
    val percent: Float, // 0f..1f (real progress; never fabricated)
    val currentFrame: Int,
    val totalFrames: Int,
    val stage: String,
    val isIndeterminate: Boolean = false
)

data class EncodedGifStats(
    val width: Int,
    val height: Int,
    val fps: Float,
    val durationMs: Long,
    val frameCount: Int,
    val fileSizeBytes: Long,
    val effectiveColorCount: Int,
    val ditherMode: DitherMode,
    val outputFormat: OutputFormat = OutputFormat.GIF,
    val webpQualityPreset: WebPQualityPreset? = null,
    val webpQuality: Int? = null,
    val hasTransparency: Boolean = false
)

/**
 * Genuine streaming animated GIF89a encoder backed by [Gif89aStreamEncoder]
 * and [FrameQuantizer] (shared global-palette Median Cut 2..256 + temporally consistent
 * None/Ordered/Floyd-Steinberg dithering).
 *
 * Uses a two-pass memory-bounded pipeline:
 * 1. Samples representative frames across the full selected segment into a constant-size
 *    (~512 KB) 5-bit RGB histogram to build one consistent [FrameQuantizer.GlobalPalette].
 * 2. Streams, transforms, quantizes against that exact global palette, and LZW-encodes
 *    each frame sequentially into a single Global Color Table GIF89a stream while recycling
 *    every intermediate bitmap immediately.
 */
object GifConversionEngine {

    private const val MIN_REQUIRED_FREE_BYTES = 16L * 1024L * 1024L // 16 MB safety margin
    private const val MAX_PALETTE_SAMPLE_FRAMES = 24
    private const val MAX_PALETTE_ANALYSIS_EDGE = 240

    suspend fun encodeToFile(
        frameSource: VideoFrameSource,
        settings: GifConversionSettings,
        outputFile: File,
        onProgress: (ConversionProgress) -> Unit = {}
    ): EncodedGifStats = withContext(Dispatchers.Default) {
        val cleanSettings = settings.sanitized()
        val metadata = frameSource.metadata

        // Verify storage space before heavy work
        outputFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
            checkAvailableStorage(parent)
        }

        val mediaLabel = if (metadata.isGifInput) "GIF" else "video"
        onProgress(
            ConversionProgress(
                percent = 0f,
                currentFrame = 0,
                totalFrames = 0,
                stage = "Preparing $mediaLabel frames…",
                isIndeterminate = true
            )
        )
        coroutineContext.ensureActive()

        val outputDims = GifGeometryCalculator.calculateOutputDimensions(
            orientedSourceWidth = metadata.orientedWidth,
            orientedSourceHeight = metadata.orientedHeight,
            settings = cleanSettings
        )

        if (outputDims.width > 65535 || outputDims.height > 65535) {
            throw IllegalStateException(
                "Requested resolution (${outputDims.width} × ${outputDims.height} px) exceeds GIF specification maximum of 65535 px."
            )
        }

        val requiredBytes = outputDims.width.toLong() * outputDims.height.toLong() * 4L
        val maxMemory = Runtime.getRuntime().maxMemory()
        if (requiredBytes > maxMemory / 2L) {
            throw IllegalStateException(
                "Requested resolution (${outputDims.width} × ${outputDims.height} px) requires " +
                    "${StudioFormatters.formatFileSize(requiredBytes)} RAM per frame, which exceeds available device memory. " +
                    "Please choose a lower resolution."
            )
        }

        val schedule = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = cleanSettings.trimStartMs,
            trimEndMs = if (cleanSettings.trimEndMs > 0L) cleanSettings.trimEndMs else metadata.durationMs,
            videoDurationMs = metadata.durationMs,
            fps = cleanSettings.fps,
            speed = cleanSettings.speed,
            reverse = cleanSettings.reverse,
            pingPong = cleanSettings.pingPong,
            useOriginalFps = cleanSettings.useOriginalFps,
            sourceFps = metadata.frameRate,
            sourceFrameTimestampsUs = metadata.frameTimestampsUs,
            sourceFrameDelaysMs = metadata.frameDelaysMs
        )

        if (schedule.frames.isEmpty()) {
            throw IllegalStateException("Selected clip range produced zero frames.")
        }

        val totalFrames = schedule.frames.size
        val decodeHintMaxEdge = computeDecodeMaxEdge(metadata, cleanSettings, outputDims)

        // Pass 1: Build one shared global palette from representative frames across the segment
        val globalPalette = buildGlobalPalette(
            frameSource = frameSource,
            settings = cleanSettings,
            outputDims = outputDims,
            schedule = schedule,
            decodeHintMaxEdge = decodeHintMaxEdge,
            maxSampleFrames = MAX_PALETTE_SAMPLE_FRAMES,
            onSampleProgress = { sampleIdx, sampleTotal ->
                val fraction = (0.15f * (sampleIdx.toFloat() / sampleTotal.coerceAtLeast(1).toFloat()))
                    .coerceIn(0f, 0.15f)
                onProgress(
                    ConversionProgress(
                        percent = fraction,
                        currentFrame = 0,
                        totalFrames = totalFrames,
                        stage = "Quantizing colors ($sampleIdx/$sampleTotal)…",
                        isIndeterminate = false
                    )
                )
            }
        )

        // Reuse pixel buffer across frames to minimize allocations
        val pixelBuffer = IntArray(outputDims.width * outputDims.height)

        val optimizationStats: GifOptimizationStats
        try {
            optimizationStats = BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024).use { stream ->
                encodeFramesToStream(
                    frameSource = frameSource,
                    settings = cleanSettings,
                    outputDims = outputDims,
                    schedule = schedule,
                    globalPalette = globalPalette,
                    decodeHintMaxEdge = decodeHintMaxEdge,
                    pixelBuffer = pixelBuffer,
                    outputStream = stream,
                    onProgress = { idx ->
                        val fraction = 0.15f + 0.80f * ((idx + 1).toFloat() / totalFrames.toFloat())
                        onProgress(
                            ConversionProgress(
                                percent = fraction.coerceIn(0.15f, 0.95f),
                                currentFrame = idx + 1,
                                totalFrames = totalFrames,
                                stage = "Encoding GIF frame ${idx + 1} of $totalFrames",
                                isIndeterminate = false
                            )
                        )
                    }
                )
            }
        } catch (t: Throwable) {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw t
        }

        coroutineContext.ensureActive()
        onProgress(
            ConversionProgress(
                percent = 0.96f,
                currentFrame = totalFrames,
                totalFrames = totalFrames,
                stage = "Verifying GIF89a output…",
                isIndeterminate = false
            )
        )

        if (!outputFile.exists() || outputFile.length() < 14L) {
            throw IOException("GIF encoding failed: output file is empty.")
        }
        verifyGifHeader(outputFile)

        EncodedGifStats(
            width = outputDims.width,
            height = outputDims.height,
            fps = schedule.effectiveFps,
            durationMs = schedule.totalDurationMs,
            frameCount = optimizationStats.emittedFrameCount,
            fileSizeBytes = outputFile.length(),
            effectiveColorCount = globalPalette.effectiveColorCount,
            ditherMode = cleanSettings.ditherMode,
            outputFormat = OutputFormat.GIF,
            hasTransparency = globalPalette.hasTransparency || metadata.hasTransparency
        )
    }

    /**
     * Memory-efficiently analyzes evenly distributed representative frames from [schedule]
     * (applying the exact crop & rotation so cropped-out pixels do not skew palette allocation)
     * and produces one shared [FrameQuantizer.GlobalPalette] for the entire animation.
     */
    internal suspend fun buildGlobalPalette(
        frameSource: VideoFrameSource,
        settings: GifConversionSettings,
        outputDims: OutputDimensions,
        schedule: FrameSchedule,
        decodeHintMaxEdge: Int?,
        maxSampleFrames: Int = MAX_PALETTE_SAMPLE_FRAMES,
        onSampleProgress: (sampleIndex: Int, totalSamples: Int) -> Unit = { _, _ -> }
    ): FrameQuantizer.GlobalPalette {
        val uniqueSourceTimes = schedule.frames.map { it.sourceTimeUs }.distinct()

        // Preserve original Global Color Table on GIF-to-GIF edits when the user did not resize
        // or request fewer colors than the source palette, avoiding lossy re-quantization and
        // double-dithering while preserving transparency cleanly.
        if (frameSource is AnimatedGifFrameSource) {
            val croppedDims = GifGeometryCalculator.croppedSourceDimensions(
                orientedSourceWidth = frameSource.metadata.orientedWidth,
                orientedSourceHeight = frameSource.metadata.orientedHeight,
                extraRotationDegrees = settings.rotationDegrees,
                cropRect = settings.cropRect
            )
            val isUnresized = outputDims.width == croppedDims.width && outputDims.height == croppedDims.height
            if (isUnresized && frameSource.canReuseGlobalPaletteForSegment(uniqueSourceTimes)) {
                val preserved = frameSource.buildPreservedGlobalPalette(settings.resolvedColorCount)
                if (preserved != null) {
                    onSampleProgress(1, 1)
                    return preserved
                }
            }
        }

        val sampleTimes = selectRepresentativeTimes(uniqueSourceTimes, maxSampleFrames)

        // Cap palette-analysis dimensions for fast bounded-memory histogram accumulation
        // while keeping small/exact-color sources at 1:1 resolution.
        val maxOut = max(outputDims.width, outputDims.height)
        val analysisScale = if (maxOut > MAX_PALETTE_ANALYSIS_EDGE) {
            MAX_PALETTE_ANALYSIS_EDGE.toDouble() / maxOut.toDouble()
        } else {
            1.0
        }
        val analysisW = max(2, (outputDims.width * analysisScale).roundToInt())
        val analysisH = max(2, (outputDims.height * analysisScale).roundToInt())
        val analysisDecodeEdge = decodeHintMaxEdge?.let {
            if (settings.cropRect.isFullFrame()) min(it, MAX_PALETTE_ANALYSIS_EDGE * 2) else it
        }

        val accumulator = FrameQuantizer.GlobalPaletteAccumulator()
        val pixelBuffer = IntArray(analysisW * analysisH)

        // Lightweight streaming scene-balance clusters (64-bin 4x4x4 RGB signatures) so
        // shorter/rarer scenes in multi-scene clips are never starved by long dark/uniform scenes.
        val sceneClusters = ArrayList<FloatArray>(8)
        val sceneCounts = ArrayList<Int>(8)

        for ((idx, timeUs) in sampleTimes.withIndex()) {
            coroutineContext.ensureActive()
            val rawBitmap = frameSource.getFrameAtTimeUs(timeUs, analysisDecodeEdge)
            if (rawBitmap != null) {
                val transformed = try {
                    FrameTransformer.transformFrame(
                        source = rawBitmap,
                        extraRotationDegrees = settings.rotationDegrees,
                        cropRect = settings.cropRect,
                        targetWidth = analysisW,
                        targetHeight = analysisH
                    )
                } finally {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                }
                try {
                    transformed.getPixels(pixelBuffer, 0, analysisW, 0, 0, analysisW, analysisH)
                } finally {
                    if (!transformed.isRecycled) transformed.recycle()
                }
                val frameWeight = computeStreamingSceneWeight(pixelBuffer, sceneClusters, sceneCounts)
                accumulator.addFramePixels(pixelBuffer, respectAlpha = true, frameWeight = frameWeight)
            }
            onSampleProgress(idx + 1, sampleTimes.size)
        }

        return accumulator.build(
            requestedColorCount = settings.resolvedColorCount,
            forceTransparency = frameSource.metadata.hasTransparency
        )
    }

    private fun computeStreamingSceneWeight(
        pixels: IntArray,
        clusters: MutableList<FloatArray>,
        counts: MutableList<Int>
    ): Double {
        if (pixels.isEmpty()) return 1.0
        val sig = FloatArray(64)
        val step = (pixels.size / 2048).coerceAtLeast(1)
        var total = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val bin = (((p ushr 22) and 0x03) shl 4) or (((p ushr 14) and 0x03) shl 2) or ((p ushr 6) and 0x03)
            sig[bin] += 1f
            total++
            i += step
        }
        if (total == 0) return 1.0
        val invTotal = 1f / total.toFloat()
        for (b in 0 until 64) sig[b] *= invTotal

        var bestCluster = -1
        var bestDist = Float.MAX_VALUE
        for (c in clusters.indices) {
            val ref = clusters[c]
            var dist = 0f
            for (b in 0 until 64) {
                dist += abs(sig[b] - ref[b])
            }
            if (dist < bestDist) {
                bestDist = dist
                bestCluster = c
            }
        }

        return if (bestCluster >= 0 && bestDist < 0.35f) {
            val newCount = counts[bestCluster] + 1
            counts[bestCluster] = newCount
            (1.0 / sqrt(newCount.toDouble())).coerceAtLeast(0.35)
        } else {
            clusters.add(sig)
            counts.add(1)
            1.5
        }
    }

    internal fun selectRepresentativeTimes(uniqueSourceTimes: List<Long>, maxSamples: Int): List<Long> {
        if (uniqueSourceTimes.size <= maxSamples) return uniqueSourceTimes
        val count = maxSamples.coerceAtLeast(1)
        if (count == 1) return listOf(uniqueSourceTimes.first())
        val lastIdx = uniqueSourceTimes.lastIndex
        return List(count) { i ->
            val idx = ((i.toDouble() / (count - 1).toDouble()) * lastIdx).roundToInt().coerceIn(0, lastIdx)
            uniqueSourceTimes[idx]
        }.distinct()
    }

    internal suspend fun encodeFramesToStream(
        frameSource: VideoFrameSource,
        settings: GifConversionSettings,
        outputDims: OutputDimensions,
        schedule: FrameSchedule,
        globalPalette: FrameQuantizer.GlobalPalette,
        decodeHintMaxEdge: Int?,
        pixelBuffer: IntArray,
        outputStream: OutputStream,
        onProgress: (Int) -> Unit = {}
    ): GifOptimizationStats {
        val encoder = Gif89aStreamEncoder(
            outputStream = outputStream,
            screenWidth = outputDims.width,
            screenHeight = outputDims.height,
            loopCount = settings.loopCount,
            globalPalette = globalPalette
        )
        val optimizer = GifFrameOptimizer(
            encoder = encoder,
            screenWidth = outputDims.width,
            screenHeight = outputDims.height,
            globalPalette = globalPalette,
            optimizeOutput = settings.optimizeOutput
        )
        var lastValidQuantized: FrameQuantizer.QuantizedFrame? = null
        // Shared deterministic exact 24-bit Oklab lookup cache across all frames
        val lookupCache = FrameQuantizer.ExactLookupCache()
        // Avoid double-dithering when preserving an imported GIF's exact original Global Color Table
        val effectiveDitherMode = if (globalPalette.isPreservedSourcePalette) {
            DitherMode.NONE
        } else {
            settings.ditherMode
        }

        for ((index, item) in schedule.frames.withIndex()) {
            coroutineContext.ensureActive()

            val rawBitmap = frameSource.getFrameAtTimeUs(item.sourceTimeUs, decodeHintMaxEdge)
            val quantized = if (rawBitmap != null) {
                val transformed = try {
                    FrameTransformer.transformFrame(
                        source = rawBitmap,
                        extraRotationDegrees = settings.rotationDegrees,
                        cropRect = settings.cropRect,
                        targetWidth = outputDims.width,
                        targetHeight = outputDims.height
                    )
                } finally {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                }
                try {
                    transformed.getPixels(
                        pixelBuffer,
                        0,
                        outputDims.width,
                        0,
                        0,
                        outputDims.width,
                        outputDims.height
                    )
                } finally {
                    if (!transformed.isRecycled) transformed.recycle()
                }
                val q = FrameQuantizer.quantizeWithPalette(
                    sourcePixels = pixelBuffer,
                    width = outputDims.width,
                    height = outputDims.height,
                    globalPalette = globalPalette,
                    ditherMode = effectiveDitherMode,
                    lookupCache = lookupCache
                )
                lastValidQuantized = q
                q
            } else {
                lastValidQuantized ?: FrameQuantizer.quantizeWithPalette(
                    sourcePixels = IntArray(outputDims.width * outputDims.height),
                    width = outputDims.width,
                    height = outputDims.height,
                    globalPalette = globalPalette,
                    ditherMode = DitherMode.NONE,
                    lookupCache = lookupCache
                )
            }

            optimizer.addFrame(
                quantized = quantized,
                delayMs = item.delayMs
            )
            onProgress(index)
        }

        val stats = optimizer.finish()
        outputStream.flush()
        return stats
    }

    internal fun computeDecodeMaxEdge(
        metadata: VideoMetadata,
        settings: GifConversionSettings,
        outputDims: OutputDimensions
    ): Int? {
        if (!settings.cropRect.isFullFrame()) {
            // Preserve crisp detail inside cropped region
            val minFrac = minOf(settings.cropRect.widthFraction, settings.cropRect.heightFraction)
            val neededEdge = (maxOf(outputDims.width, outputDims.height) / minFrac).toInt()
            val nativeMax = maxOf(metadata.orientedWidth, metadata.orientedHeight)
            return neededEdge.coerceAtMost(nativeMax)
        }
        // Slightly above output size for clean bilinear downscaling
        return (maxOf(outputDims.width, outputDims.height) * 2)
            .coerceAtMost(maxOf(metadata.orientedWidth, metadata.orientedHeight))
    }

    private fun checkAvailableStorage(directory: File) {
        try {
            val stat = StatFs(directory.absolutePath)
            val availableBytes = stat.availableBytes
            if (availableBytes in 1 until MIN_REQUIRED_FREE_BYTES) {
                throw IOException(
                    "Insufficient storage space (${StudioFormatters.formatFileSize(availableBytes)} free). " +
                        "Please free up at least 16 MB and try again."
                )
            }
        } catch (io: IOException) {
            throw io
        } catch (_: Throwable) {
            // Ignore StatFs unavailable in simulated environments
        }
    }

    fun verifyGifHeader(file: File): Boolean {
        val header = ByteArray(6)
        file.inputStream().use { input ->
            val read = input.read(header)
            if (read < 6) throw IOException("Corrupted GIF output: truncated header.")
        }
        val magic = String(header, Charsets.US_ASCII)
        if (magic != "GIF89a" && magic != "GIF87a") {
            throw IOException("Invalid GIF magic header: $magic")
        }
        return true
    }
}
