package com.example.engine

import java.io.IOException

data class GifOptimizationStats(
    val emittedFrameCount: Int,
    val deduplicatedFrameCount: Int,
    val subFrameCount: Int
)

/**
 * Lossless, visually identical GIF89a frame stream optimizer.
 *
 * Operates strictly on already-quantized global-palette index frames without altering resolution,
 * frame rate timing, color count, or palette entries:
 * 1. **Identical consecutive frame deduplication**: merges adjacent frames whose quantized index
 *    buffers are byte-for-byte identical by accumulating their exact centisecond delays (within
 *    GIF's 65535 cs max delay), preserving total duration and frame timing exactly.
 * 2. **Changed-region rectangular sub-frame encoding**:
 *    - For opaque animations: writes Frame 0 full-canvas and subsequent frames as the tightest
 *      bounding rectangle of pixels that changed from the previous frame with
 *      [Gif89aStreamEncoder.DISPOSAL_DO_NOT_DISPOSE].
 *    - For transparent animations: uses [Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND] and
 *      crops each frame to its tightest non-transparent bounding box, guaranteeing that every
 *      opaque-to-transparent transition clears cleanly with zero ghosting across Android and
 *      browser decoders.
 *
 * Peak memory overhead is O(1): at most two full-canvas byte arrays (`W * H` bytes each).
 */
class GifFrameOptimizer(
    private val encoder: Gif89aStreamEncoder,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val globalPalette: FrameQuantizer.GlobalPalette,
    private val optimizeOutput: Boolean = true
) {
    private val fullArea: Int = screenWidth * screenHeight
    private val emptyRgb = IntArray(0)

    private var pendingIndices: ByteArray? = null
    private var pendingDelayCs: Int = 0
    private var pendingTransparentIndex: Int = -1

    // Exact full-canvas indices currently on the decoder canvas (used for opaque inter-frame diffing)
    private var prevEmittedFullIndices: ByteArray? = null

    private var emittedFrameCount: Int = 0
    private var deduplicatedFrameCount: Int = 0
    private var subFrameCount: Int = 0
    private var finished: Boolean = false

    @Throws(IOException::class)
    fun addFrame(
        quantized: FrameQuantizer.QuantizedFrame,
        delayMs: Int
    ) {
        check(!finished) { "Cannot add frames after finish() has been called." }
        val delayCs = msToCentiseconds(delayMs)

        if (!optimizeOutput) {
            val disposal = if (globalPalette.hasTransparency || quantized.hasTransparency) {
                Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND
            } else {
                Gif89aStreamEncoder.DISPOSAL_DO_NOT_DISPOSE
            }
            encoder.addFrame(
                quantized = quantized,
                delayMs = delayCs * 10,
                disposalMethod = disposal,
                left = 0,
                top = 0
            )
            emittedFrameCount++
            return
        }

        val currentPending = pendingIndices
        if (currentPending != null) {
            val sameTransparency = quantized.transparentIndex == pendingTransparentIndex
            if (sameTransparency &&
                quantized.colorIndices.contentEquals(currentPending) &&
                pendingDelayCs + delayCs <= MAX_DELAY_CS
            ) {
                pendingDelayCs += delayCs
                deduplicatedFrameCount++
                return
            }
            flushPending()
        }

        pendingIndices = quantized.colorIndices
        pendingDelayCs = delayCs
        pendingTransparentIndex = quantized.transparentIndex
    }

    @Throws(IOException::class)
    fun finish(): GifOptimizationStats {
        if (!finished) {
            finished = true
            flushPending()
            encoder.finish()
        }
        return GifOptimizationStats(
            emittedFrameCount = emittedFrameCount,
            deduplicatedFrameCount = deduplicatedFrameCount,
            subFrameCount = subFrameCount
        )
    }

    private fun buildFrame(
        width: Int,
        height: Int,
        indices: ByteArray,
        transparentIdx: Int
    ): FrameQuantizer.QuantizedFrame = FrameQuantizer.QuantizedFrame(
        width = width,
        height = height,
        rgbPixels = emptyRgb,
        palette = globalPalette.colors,
        effectiveColorCount = globalPalette.effectiveColorCount,
        colorIndices = indices,
        transparentIndex = transparentIdx
    )

    @Throws(IOException::class)
    private fun flushPending() {
        val curr = pendingIndices ?: return
        val delayMs = pendingDelayCs * 10
        val useTransparency = globalPalette.hasTransparency || pendingTransparentIndex >= 0
        val transparentIdx = if (useTransparency) {
            if (pendingTransparentIndex >= 0) pendingTransparentIndex else globalPalette.transparentIndex
        } else {
            -1
        }

        if (useTransparency) {
            emitTransparentOptimizedFrame(curr, delayMs, transparentIdx)
        } else {
            emitOpaqueDiffFrame(curr, delayMs)
        }

        emittedFrameCount++
        pendingIndices = null
        pendingDelayCs = 0
    }

    /**
     * With DISPOSAL_RESTORE_BACKGROUND on every frame, the decoder canvas is guaranteed 100%
     * transparent before each frame is drawn, so emitting the tight non-transparent bounding box
     * is mathematically lossless and prevents ghosting when opaque pixels become transparent.
     */
    @Throws(IOException::class)
    private fun emitTransparentOptimizedFrame(
        curr: ByteArray,
        delayMs: Int,
        transparentIdx: Int
    ) {
        val tByte = transparentIdx.toByte()
        var minX = screenWidth
        var minY = screenHeight
        var maxX = -1
        var maxY = -1

        var offset = 0
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                if (curr[offset++] != tByte) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX) {
            // Entire frame is transparent -> minimal 1x1 transparent sub-frame at (0, 0)
            encoder.addFrame(
                quantized = buildFrame(1, 1, byteArrayOf(tByte), transparentIdx),
                delayMs = delayMs,
                disposalMethod = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND,
                left = 0,
                top = 0
            )
            subFrameCount++
            return
        }

        val rw = maxX - minX + 1
        val rh = maxY - minY + 1
        if (rw * rh < fullArea) {
            val sub = extractSubRect(curr, minX, minY, rw, rh)
            encoder.addFrame(
                quantized = buildFrame(rw, rh, sub, transparentIdx),
                delayMs = delayMs,
                disposalMethod = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND,
                left = minX,
                top = minY
            )
            subFrameCount++
        } else {
            encoder.addFrame(
                quantized = buildFrame(screenWidth, screenHeight, curr, transparentIdx),
                delayMs = delayMs,
                disposalMethod = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND,
                left = 0,
                top = 0
            )
        }
    }

    /**
     * For opaque animations, every pixel on the canvas is opaque and every frame uses
     * DISPOSAL_DO_NOT_DISPOSE. Frame 0 writes the full canvas; each subsequent frame writes only
     * the tight bounding box of pixels whose palette index changed since the previous frame.
     */
    @Throws(IOException::class)
    private fun emitOpaqueDiffFrame(
        curr: ByteArray,
        delayMs: Int
    ) {
        val prev = prevEmittedFullIndices
        if (prev == null) {
            encoder.addFrame(
                quantized = buildFrame(screenWidth, screenHeight, curr, -1),
                delayMs = delayMs,
                disposalMethod = Gif89aStreamEncoder.DISPOSAL_DO_NOT_DISPOSE,
                left = 0,
                top = 0
            )
            prevEmittedFullIndices = curr
            return
        }

        var minX = screenWidth
        var minY = screenHeight
        var maxX = -1
        var maxY = -1

        var offset = 0
        for (y in 0 until screenHeight) {
            for (x in 0 until screenWidth) {
                if (curr[offset] != prev[offset]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                offset++
            }
        }

        if (maxX < minX) {
            // Identical frame split only due to MAX_DELAY_CS overflow -> minimal 1x1 rewrite at (0,0)
            encoder.addFrame(
                quantized = buildFrame(1, 1, byteArrayOf(curr[0]), -1),
                delayMs = delayMs,
                disposalMethod = Gif89aStreamEncoder.DISPOSAL_DO_NOT_DISPOSE,
                left = 0,
                top = 0
            )
            subFrameCount++
        } else {
            val rw = maxX - minX + 1
            val rh = maxY - minY + 1
            if (rw * rh < fullArea) {
                val sub = extractSubRect(curr, minX, minY, rw, rh)
                encoder.addFrame(
                    quantized = buildFrame(rw, rh, sub, -1),
                    delayMs = delayMs,
                    disposalMethod = Gif89aStreamEncoder.DISPOSAL_DO_NOT_DISPOSE,
                    left = minX,
                    top = minY
                )
                subFrameCount++
            } else {
                encoder.addFrame(
                    quantized = buildFrame(screenWidth, screenHeight, curr, -1),
                    delayMs = delayMs,
                    disposalMethod = Gif89aStreamEncoder.DISPOSAL_DO_NOT_DISPOSE,
                    left = 0,
                    top = 0
                )
            }
        }

        prevEmittedFullIndices = curr
    }

    private fun extractSubRect(
        fullIndices: ByteArray,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): ByteArray {
        val sub = ByteArray(width * height)
        var dstOffset = 0
        for (row in 0 until height) {
            val srcOffset = (top + row) * screenWidth + left
            System.arraycopy(fullIndices, srcOffset, sub, dstOffset, width)
            dstOffset += width
        }
        return sub
    }

    companion object {
        private const val MAX_DELAY_CS = 65535

        internal fun msToCentiseconds(delayMs: Int): Int =
            ((delayMs + 5) / 10).coerceIn(2, MAX_DELAY_CS)
    }
}
