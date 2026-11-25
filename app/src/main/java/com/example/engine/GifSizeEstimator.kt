package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SizeEstimate(
    val estimatedBytes: Long,
    val totalFrames: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val estimatedDurationMs: Long,
    val resolvedColorCount: Int,
    val effectiveFps: Float = 0f,
    val outputFormat: OutputFormat = OutputFormat.GIF,
    val webpQualityPreset: WebPQualityPreset? = null,
    val webpQuality: Int? = null
)

/**
 * Representative-frame GIF size estimator.
 *
 * Samples representative frames across the trimmed clip (video or animated GIF), builds a shared
 * [FrameQuantizer.GlobalPalette] (or preserves an unresized GIF's original Global Color Table just
 * like [GifConversionEngine]), applies the exact crop, rotation, target resolution, and dithering
 * mode, then trial-encodes those sample frames with [Gif89aStreamEncoder] + [GifFrameOptimizer]
 * to measure realistic compressed bytes accurately without encoding the entire clip.
 */
object GifSizeEstimator {

    private const val ANCHOR_SAMPLE_COUNT = 4
    private const val MAX_ANALYSIS_EDGE = 360

    suspend fun estimate(
        frameSource: VideoFrameSource,
        settings: GifConversionSettings
    ): SizeEstimate = withContext(Dispatchers.Default) {
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

        val totalFrames = schedule.frames.size
        if (totalFrames <= 0) {
            return@withContext SizeEstimate(
                estimatedBytes = 0L,
                totalFrames = 0,
                outputWidth = outputDims.width,
                outputHeight = outputDims.height,
                estimatedDurationMs = schedule.totalDurationMs,
                resolvedColorCount = clean.resolvedColorCount ?: 128,
                effectiveFps = schedule.effectiveFps
            )
        }

        // Analyze at actual output size up to MAX_ANALYSIS_EDGE, or scaled proportionally with area adjustment
        val maxOutEdge = max(outputDims.width, outputDims.height)
        val analysisScale = if (maxOutEdge > MAX_ANALYSIS_EDGE) {
            MAX_ANALYSIS_EDGE.toDouble() / maxOutEdge.toDouble()
        } else {
            1.0
        }
        val analysisW = max(2, (outputDims.width * analysisScale).roundToInt())
        val analysisH = max(2, (outputDims.height * analysisScale).roundToInt())

        // Match GifConversionEngine's unresized GIF-to-GIF palette preservation
        val preservedGifPalette: FrameQuantizer.GlobalPalette? = if (frameSource is AnimatedGifFrameSource) {
            val croppedDims = GifGeometryCalculator.croppedSourceDimensions(
                orientedSourceWidth = metadata.orientedWidth,
                orientedSourceHeight = metadata.orientedHeight,
                extraRotationDegrees = clean.rotationDegrees,
                cropRect = clean.cropRect
            )
            val isUnresized = outputDims.width == croppedDims.width && outputDims.height == croppedDims.height
            if (isUnresized && frameSource.canReuseGlobalPaletteForSegment(schedule.frames.map { it.sourceTimeUs }.distinct())) {
                frameSource.buildPreservedGlobalPalette(clean.resolvedColorCount)
            } else null
        } else null

        // Select up to 4 evenly spread anchor frames; when optimizeOutput is enabled on longer clips,
        // also sample 1 adjacent neighbor frame to measure real inter-frame dirty-rect savings.
        val anchorCount = min(ANCHOR_SAMPLE_COUNT, totalFrames)
        val anchorIndices = if (anchorCount == 1) {
            listOf(0)
        } else {
            List(anchorCount) { i ->
                ((i.toDouble() / (anchorCount - 1).toDouble()) * (totalFrames - 1)).roundToInt()
                    .coerceIn(0, totalFrames - 1)
            }.distinct()
        }

        val decodeFullClipDirectly = totalFrames <= 5
        val pairAnchorIdx = if (!decodeFullClipDirectly && clean.optimizeOutput) {
            anchorIndices[anchorIndices.size / 2].coerceAtMost(totalFrames - 2)
        } else {
            -1
        }

        val indicesToDecode = if (decodeFullClipDirectly) {
            (0 until totalFrames).toList()
        } else {
            buildList {
                addAll(anchorIndices)
                if (pairAnchorIdx >= 0 && (pairAnchorIdx + 1) !in this) {
                    add(pairAnchorIdx + 1)
                }
            }.sorted()
        }

        // Decode & transform sample frames once (<= 5 frames at <= 360px -> <= 2.5 MB temporary)
        val decodedByIndex = LinkedHashMap<Int, Pair<IntArray, Int>>(indicesToDecode.size)
        val accumulator = FrameQuantizer.GlobalPaletteAccumulator()

        for (frameIdx in indicesToDecode) {
            coroutineContext.ensureActive()
            val item = schedule.frames[frameIdx]
            val raw = frameSource.getFrameAtTimeUs(item.sourceTimeUs, MAX_ANALYSIS_EDGE * 2) ?: continue
            val transformed = try {
                FrameTransformer.transformFrame(
                    source = raw,
                    extraRotationDegrees = clean.rotationDegrees,
                    cropRect = clean.cropRect,
                    targetWidth = analysisW,
                    targetHeight = analysisH
                )
            } finally {
                if (!raw.isRecycled) raw.recycle()
            }
            val buf = IntArray(analysisW * analysisH)
            try {
                transformed.getPixels(buf, 0, analysisW, 0, 0, analysisW, analysisH)
            } finally {
                if (!transformed.isRecycled) transformed.recycle()
            }
            if (preservedGifPalette == null) {
                accumulator.addFramePixels(buf, respectAlpha = true)
            }
            decodedByIndex[frameIdx] = buf to item.delayMs
        }

        if (decodedByIndex.isEmpty()) {
            return@withContext SizeEstimate(
                estimatedBytes = 0L,
                totalFrames = totalFrames,
                outputWidth = outputDims.width,
                outputHeight = outputDims.height,
                estimatedDurationMs = schedule.totalDurationMs,
                resolvedColorCount = clean.resolvedColorCount ?: 128,
                effectiveFps = schedule.effectiveFps
            )
        }

        val globalPalette = preservedGifPalette ?: accumulator.build(
            requestedColorCount = clean.resolvedColorCount,
            forceTransparency = metadata.hasTransparency
        )
        val resolvedColors = globalPalette.effectiveColorCount
        val lookupCache = FrameQuantizer.ExactLookupCache()
        val effectiveDitherMode = if (globalPalette.isPreservedSourcePalette) {
            DitherMode.NONE
        } else {
            clean.ditherMode
        }

        // Quantize each decoded sample once
        val quantizedByIndex = LinkedHashMap<Int, Pair<FrameQuantizer.QuantizedFrame, Int>>(decodedByIndex.size)
        for ((frameIdx, pair) in decodedByIndex) {
            coroutineContext.ensureActive()
            val (buf, delayMs) = pair
            val q = FrameQuantizer.quantizeWithPalette(
                sourcePixels = buf,
                width = analysisW,
                height = analysisH,
                globalPalette = globalPalette,
                ditherMode = effectiveDitherMode,
                lookupCache = lookupCache
            )
            quantizedByIndex[frameIdx] = q to delayMs
        }

        // Area scaling factor when analysisW x analysisH was downscaled from outputDims
        val areaRatio = (outputDims.width.toDouble() * outputDims.height.toDouble()) /
            (analysisW.toDouble() * analysisH.toDouble())
        val compressionAreaScale = areaRatio.pow(0.88)

        // Exact short-clip path (<= 5 frames): trial-encode all frames directly via GifFrameOptimizer
        if (decodeFullClipDirectly && quantizedByIndex.size == totalFrames) {
            val baos = ByteArrayOutputStream(32 * 1024)
            val encoder = Gif89aStreamEncoder(
                outputStream = baos,
                screenWidth = analysisW,
                screenHeight = analysisH,
                loopCount = clean.loopCount,
                globalPalette = globalPalette
            )
            val optimizer = GifFrameOptimizer(
                encoder = encoder,
                screenWidth = analysisW,
                screenHeight = analysisH,
                globalPalette = globalPalette,
                optimizeOutput = clean.optimizeOutput
            )
            for (i in 0 until totalFrames) {
                val (q, delayMs) = quantizedByIndex[i] ?: continue
                optimizer.addFrame(q, delayMs)
            }
            optimizer.finish()
            val globalHeaderBytes = 32L + encoder.globalColorTableSizeBytes.toLong()
            val payloadBytes = (baos.size().toLong() - globalHeaderBytes).coerceAtLeast(128L)
            val estimatedTotal = globalHeaderBytes + (payloadBytes.toDouble() * compressionAreaScale).roundToLong()
            return@withContext SizeEstimate(
                estimatedBytes = estimatedTotal.coerceAtLeast(1024L),
                totalFrames = totalFrames,
                outputWidth = outputDims.width,
                outputHeight = outputDims.height,
                estimatedDurationMs = schedule.totalDurationMs,
                resolvedColorCount = resolvedColors,
                effectiveFps = schedule.effectiveFps
            )
        }

        // Standard representative sampling path: measure anchor frames + optional adjacent delta frame
        val anchorsPresent = anchorIndices.mapNotNull { idx -> quantizedByIndex[idx] }
        val anchorBaos = ByteArrayOutputStream(64 * 1024)
        val anchorEncoder = Gif89aStreamEncoder(
            outputStream = anchorBaos,
            screenWidth = analysisW,
            screenHeight = analysisH,
            loopCount = clean.loopCount,
            globalPalette = globalPalette
        )
        val anchorOptimizer = GifFrameOptimizer(
            encoder = anchorEncoder,
            screenWidth = analysisW,
            screenHeight = analysisH,
            globalPalette = globalPalette,
            optimizeOutput = clean.optimizeOutput && (metadata.hasTransparency || globalPalette.hasTransparency)
        )
        for ((q, delayMs) in anchorsPresent) {
            coroutineContext.ensureActive()
            anchorOptimizer.addFrame(q, delayMs)
        }
        anchorOptimizer.finish()

        val globalHeaderBytes = 32L + anchorEncoder.globalColorTableSizeBytes.toLong()
        val perFrameDescriptorOverhead = 20L
        val encodedAnchors = anchorsPresent.size.coerceAtLeast(1)
        val anchorPayloadBytes = (anchorBaos.size().toLong() - globalHeaderBytes - (perFrameDescriptorOverhead * encodedAnchors))
            .coerceAtLeast(128L)
        val avgFullFrameLzw = anchorPayloadBytes.toDouble() / encodedAnchors.toDouble()

        // Measure true inter-frame optimization savings on opaque clips using the adjacent frame pair
        var effectiveFollowingFrameLzw = avgFullFrameLzw
        if (clean.optimizeOutput &&
            !metadata.hasTransparency &&
            !globalPalette.hasTransparency &&
            pairAnchorIdx >= 0
        ) {
            val pairA = quantizedByIndex[pairAnchorIdx]
            val pairB = quantizedByIndex[pairAnchorIdx + 1]
            if (pairA != null && pairB != null) {
                val baseBaos = ByteArrayOutputStream(16 * 1024)
                val baseEnc = Gif89aStreamEncoder(baseBaos, analysisW, analysisH, clean.loopCount, globalPalette)
                val baseOpt = GifFrameOptimizer(baseEnc, analysisW, analysisH, globalPalette, optimizeOutput = true)
                baseOpt.addFrame(pairA.first, pairA.second)
                baseOpt.finish()

                val pairBaos = ByteArrayOutputStream(24 * 1024)
                val pairEnc = Gif89aStreamEncoder(pairBaos, analysisW, analysisH, clean.loopCount, globalPalette)
                val pairOpt = GifFrameOptimizer(pairEnc, analysisW, analysisH, globalPalette, optimizeOutput = true)
                pairOpt.addFrame(pairA.first, pairA.second)
                pairOpt.addFrame(pairB.first, pairB.second)
                val pairStats = pairOpt.finish()

                val deltaBytes = (pairBaos.size() - baseBaos.size() - perFrameDescriptorOverhead.toInt()).coerceAtLeast(0)
                // Trust measured adjacent-frame delta when motion is present, with a light full-frame
                // blend so localized pauses never severely underestimate clips with motion elsewhere.
                effectiveFollowingFrameLzw = if (pairStats.emittedFrameCount <= 1) {
                    avgFullFrameLzw * 0.42
                } else {
                    val clampedDelta = deltaBytes.toDouble().coerceIn(avgFullFrameLzw * 0.08, avgFullFrameLzw * 1.15)
                    (0.88 * clampedDelta) + (0.12 * avgFullFrameLzw)
                }
            }
        }

        val scaledFirstFrame = avgFullFrameLzw * compressionAreaScale
        val scaledFollowingFrame = effectiveFollowingFrameLzw * compressionAreaScale
        val followingCount = (totalFrames - 1).coerceAtLeast(0)

        val estimatedTotalBytes = globalHeaderBytes +
            (scaledFirstFrame + perFrameDescriptorOverhead.toDouble()).roundToLong() +
            ((scaledFollowingFrame + perFrameDescriptorOverhead.toDouble()) * followingCount.toDouble()).roundToLong()

        SizeEstimate(
            estimatedBytes = estimatedTotalBytes.coerceAtLeast(1024L),
            totalFrames = totalFrames,
            outputWidth = outputDims.width,
            outputHeight = outputDims.height,
            estimatedDurationMs = schedule.totalDurationMs,
            resolvedColorCount = resolvedColors,
            effectiveFps = schedule.effectiveFps
        )
    }
}
