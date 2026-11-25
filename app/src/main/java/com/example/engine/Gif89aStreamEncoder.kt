package com.example.engine

import java.io.IOException
import java.io.OutputStream
import kotlin.math.max

/**
 * Spec-compliant, streaming GIF89a encoder with shared Global Color Table support,
 * full alpha transparency, per-frame Graphic Control Extensions, configurable disposal
 * methods, NETSCAPE2.0 looping, and zero-allocation primitive-array LZW compression.
 *
 * When [globalPalette] is provided, the encoder writes a single Global Color Table once in
 * the GIF header and omits per-frame Local Color Tables for all matching frames, preventing
 * palette-induced color flickering and reducing output file size.
 */
class Gif89aStreamEncoder(
    private val outputStream: OutputStream,
    private val screenWidth: Int,
    private val screenHeight: Int,
    loopCount: Int = 0,
    private val globalPalette: FrameQuantizer.GlobalPalette? = null
) {
    private var finished = false

    private val gctMinCodeSize: Int
    val globalColorTableSizeBytes: Int

    // Reusable LZW open-addressing hash table (size 5003 prime > 4096)
    private val hashKeys = IntArray(HASH_SIZE)
    private val hashVals = IntArray(HASH_SIZE)
    private val subBlock = ByteArray(256)

    init {
        require((screenWidth in 1..65535) && (screenHeight in 1..65535)) {
            "GIF dimensions must be between 1 and 65535 (got ${screenWidth}x$screenHeight)."
        }
        writeHeader()
        if (globalPalette != null) {
            val usedEntries = max(
                2,
                if (globalPalette.hasTransparency) {
                    max(globalPalette.colors.size, globalPalette.transparentIndex + 1)
                } else {
                    globalPalette.colors.size
                }
            ).coerceAtMost(256)
            var bits = 1
            while ((1 shl bits) < usedEntries && bits < 8) {
                bits++
            }
            val paddedCount = 1 shl bits
            gctMinCodeSize = max(2, bits)
            globalColorTableSizeBytes = paddedCount * 3
            val bgIdx = if (globalPalette.hasTransparency) {
                globalPalette.transparentIndex.coerceIn(0, paddedCount - 1)
            } else {
                0
            }
            writeLogicalScreenDescriptorWithGct(bits - 1, bgIdx)
            writeColorTable(globalPalette.colors, paddedCount)
        } else {
            gctMinCodeSize = 0
            globalColorTableSizeBytes = 0
            writeLogicalScreenDescriptorNoGct()
        }
        if (loopCount >= 0) {
            writeNetscapeLoopExtension(loopCount.coerceIn(0, 65535))
        }
    }

    /**
     * Appends a single quantized frame to the GIF89a stream.
     *
     * @param quantized Frame quantized by [FrameQuantizer] containing palette, indices, and optional transparency.
     * @param delayMs Frame delay in milliseconds (rounded to GIF centiseconds, min 20ms).
     * @param disposalMethod GIF disposal method (1 = DO_NOT_DISPOSE, 2 = RESTORE_TO_BACKGROUND).
     */
    @Throws(IOException::class)
    fun addFrame(
        quantized: FrameQuantizer.QuantizedFrame,
        delayMs: Int,
        disposalMethod: Int = if (quantized.hasTransparency) DISPOSAL_RESTORE_BACKGROUND else DISPOSAL_DO_NOT_DISPOSE,
        left: Int = 0,
        top: Int = 0
    ) {
        check(!finished) { "Cannot add frames after finish() has been called." }
        require(left + quantized.width <= screenWidth && top + quantized.height <= screenHeight) {
            "Frame rect ($left,$top,${quantized.width}x${quantized.height}) exceeds screen (${screenWidth}x$screenHeight)."
        }

        val hasTransparency = quantized.hasTransparency
        val transparentIdx = if (hasTransparency) quantized.transparentIndex.coerceIn(0, 255) else 0

        val useGlobalTable = globalPalette != null &&
            (quantized.palette === globalPalette.colors || quantized.palette.contentEquals(globalPalette.colors)) &&
            (!hasTransparency || (globalPalette.hasTransparency && transparentIdx == globalPalette.transparentIndex))

        // 1. Graphic Control Extension (0x21 0xF9)
        val delayCs = ((delayMs + 5) / 10).coerceIn(2, 65535)
        val gcePacked = ((disposalMethod and 0x07) shl 2) or (if (hasTransparency) 0x01 else 0x00)
        outputStream.write(0x21)
        outputStream.write(0xF9)
        outputStream.write(0x04)
        outputStream.write(gcePacked)
        writeShortLE(delayCs)
        outputStream.write(transparentIdx)
        outputStream.write(0x00)

        // 2. Image Descriptor (0x2C)
        outputStream.write(0x2C)
        writeShortLE(left)
        writeShortLE(top)
        writeShortLE(quantized.width)
        writeShortLE(quantized.height)

        val minCodeSize = if (useGlobalTable) {
            // No Local Color Table: reuse the stream's Global Color Table directly
            outputStream.write(0x00)
            gctMinCodeSize
        } else {
            val usedEntries = max(
                2,
                if (hasTransparency) max(quantized.palette.size, transparentIdx + 1) else quantized.palette.size
            ).coerceAtMost(256)
            var bits = 1
            while ((1 shl bits) < usedEntries && bits < 8) {
                bits++
            }
            val paddedColorCount = 1 shl bits
            outputStream.write(0x80 or ((bits - 1) and 0x07))
            writeColorTable(quantized.palette, paddedColorCount)
            max(2, bits)
        }

        // 3. LZW Image Data
        val indices = if (quantized.colorIndices.size == quantized.width * quantized.height) {
            quantized.colorIndices
        } else {
            buildIndicesFromRgb(quantized)
        }
        writeLzwImageData(indices, minCodeSize)
    }

    @Throws(IOException::class)
    fun finish() {
        if (finished) return
        finished = true
        outputStream.write(0x3B) // GIF Trailer
        outputStream.flush()
    }

    private fun writeHeader() {
        outputStream.write('G'.code)
        outputStream.write('I'.code)
        outputStream.write('F'.code)
        outputStream.write('8'.code)
        outputStream.write('9'.code)
        outputStream.write('a'.code)
    }

    private fun writeLogicalScreenDescriptorNoGct() {
        writeShortLE(screenWidth)
        writeShortLE(screenHeight)
        outputStream.write(0x70) // No GCT, 8-bit color resolution
        outputStream.write(0x00) // Background color index
        outputStream.write(0x00) // Pixel aspect ratio
    }

    private fun writeLogicalScreenDescriptorWithGct(gctSizeField: Int, bgColorIndex: Int) {
        writeShortLE(screenWidth)
        writeShortLE(screenHeight)
        // GCT flag (0x80) | 8-bit color resolution (0x70) | GCT size field (0..7)
        outputStream.write(0x80 or 0x70 or (gctSizeField and 0x07))
        outputStream.write(bgColorIndex and 0xFF)
        outputStream.write(0x00) // Pixel aspect ratio
    }

    private fun writeColorTable(palette: IntArray, paddedColorCount: Int) {
        val bytes = ByteArray(paddedColorCount * 3)
        val limit = minOf(palette.size, paddedColorCount)
        var offset = 0
        for (i in 0 until limit) {
            val rgb = palette[i]
            bytes[offset++] = ((rgb ushr 16) and 0xFF).toByte()
            bytes[offset++] = ((rgb ushr 8) and 0xFF).toByte()
            bytes[offset++] = (rgb and 0xFF).toByte()
        }
        outputStream.write(bytes)
    }

    private fun writeNetscapeLoopExtension(loopCount: Int) {
        outputStream.write(0x21)
        outputStream.write(0xFF)
        outputStream.write(0x0B)
        val sig = "NETSCAPE2.0"
        for (ch in sig) {
            outputStream.write(ch.code)
        }
        outputStream.write(0x03)
        outputStream.write(0x01)
        writeShortLE(loopCount)
        outputStream.write(0x00)
    }

    private fun writeShortLE(value: Int) {
        outputStream.write(value and 0xFF)
        outputStream.write((value ushr 8) and 0xFF)
    }

    private fun writeLzwImageData(indices: ByteArray, minCodeSize: Int) {
        outputStream.write(minCodeSize)

        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var nextCode = eoiCode + 1
        var codeSize = minCodeSize + 1

        var bitBuf = 0
        var bitCount = 0
        var subBlockLen = 0

        fun flushSubBlock() {
            if (subBlockLen > 0) {
                outputStream.write(subBlockLen)
                outputStream.write(subBlock, 0, subBlockLen)
                subBlockLen = 0
            }
        }

        fun emitCode(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                subBlock[subBlockLen++] = (bitBuf and 0xFF).toByte()
                if (subBlockLen == 255) {
                    flushSubBlock()
                }
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        hashKeys.fill(-1)
        emitCode(clearCode)

        if (indices.isNotEmpty()) {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val combinedKey = (prefix shl 8) or k
                var slot = (((k shl 4) xor prefix) and 0x7FFFFFFF) % HASH_SIZE

                var foundCode = -1
                while (true) {
                    val existing = hashKeys[slot]
                    if (existing == combinedKey) {
                        foundCode = hashVals[slot]
                        break
                    }
                    if (existing == -1) {
                        break
                    }
                    slot++
                    if (slot == HASH_SIZE) slot = 0
                }

                if (foundCode >= 0) {
                    prefix = foundCode
                } else {
                    emitCode(prefix)
                    if (nextCode < MAX_TABLE_SIZE) {
                        val assigned = nextCode++
                        hashKeys[slot] = combinedKey
                        hashVals[slot] = assigned
                        if (assigned == (1 shl codeSize) && codeSize < 12) {
                            codeSize++
                        }
                    } else {
                        emitCode(clearCode)
                        hashKeys.fill(-1)
                        nextCode = eoiCode + 1
                        codeSize = minCodeSize + 1
                    }
                    prefix = k
                }
            }
            emitCode(prefix)
        }

        emitCode(eoiCode)

        if (bitCount > 0) {
            subBlock[subBlockLen++] = (bitBuf and 0xFF).toByte()
        }
        flushSubBlock()
        outputStream.write(0x00) // Block terminator
    }

    private fun buildIndicesFromRgb(quantized: FrameQuantizer.QuantizedFrame): ByteArray {
        val map = HashMap<Int, Int>(quantized.palette.size * 2)
        quantized.palette.forEachIndexed { idx, rgb -> map[rgb and 0x00FFFFFF] = idx }
        return ByteArray(quantized.rgbPixels.size) { i ->
            (map[quantized.rgbPixels[i] and 0x00FFFFFF] ?: 0).toByte()
        }
    }

    companion object {
        private const val HASH_SIZE = 5003
        private const val MAX_TABLE_SIZE = 4096

        const val DISPOSAL_UNSPECIFIED = 0
        const val DISPOSAL_DO_NOT_DISPOSE = 1
        const val DISPOSAL_RESTORE_BACKGROUND = 2
        const val DISPOSAL_RESTORE_PREVIOUS = 3
    }
}
