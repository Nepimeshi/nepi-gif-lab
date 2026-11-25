package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Streaming, indexed animated GIF87a/GIF89a decoder implementing [VideoFrameSource].
 *
 * Design highlights:
 * - Scans and indexes all frame descriptors in a single pass without decoding pixel buffers,
 *   keeping RAM bounded even for GIFs with hundreds or thousands of frames.
 * - Preserves individual frame delays (variable frame timing), original dimensions,
 *   NETSCAPE2.0/ANIMEXTS1.0 loop counts, alpha transparency, interlacing, partial sub-rectangle
 *   compositing, and all four GIF disposal methods (0: Unspecified, 1: Do Not Dispose,
 *   2: Restore to Background, 3: Restore to Previous).
 * - Handles single-frame GIFs gracefully.
 * - Maintains a single active compositing canvas plus a small bounded checkpoint cache so
 *   both sequential encoding and random-access scrubbing/reverse playback stay fast and memory-safe.
 */
class AnimatedGifFrameSource private constructor(
    override val metadata: VideoMetadata,
    private val backingFile: File,
    private val deleteFileOnClose: Boolean,
    private val logicalWidth: Int,
    private val logicalHeight: Int,
    private val globalColorTable: IntArray?,
    private val bgColorIndex: Int,
    val frames: List<GifFrameDescriptor>
) : VideoFrameSource {

    val hasGlobalColorTable: Boolean get() = globalColorTable != null
    val globalColorTableCopy: IntArray? get() = globalColorTable?.copyOf()

    /**
     * Returns true when this animated GIF uses a single shared Global Color Table across all
     * frames referenced by [sourceTimesUs] (no per-frame Local Color Tables override it).
     */
    fun canReuseGlobalPaletteForSegment(sourceTimesUs: List<Long>): Boolean {
        if (globalColorTable == null || frames.isEmpty()) return false
        if (sourceTimesUs.isEmpty()) return frames.all { it.lctSize == 0 }
        for (tUs in sourceTimesUs) {
            val idx = findFrameIndexForTimeUs(tUs)
            if (frames[idx].lctSize > 0) return false
        }
        return true
    }

    /**
     * Builds a preserved [FrameQuantizer.GlobalPalette] directly from this GIF's original Global
     * Color Table when it fits within [maxRequestedColors] (2..256, or `null` for Auto), avoiding
     * unnecessary re-quantization and double-dithering on unresized GIF-to-GIF edits.
     */
    fun buildPreservedGlobalPalette(maxRequestedColors: Int?): FrameQuantizer.GlobalPalette? {
        val gct = globalColorTable ?: return null
        val limit = (maxRequestedColors ?: 256).coerceIn(2, 256)
        val needsTransparency = metadata.hasTransparency
        val maxOpaque = if (needsTransparency) (limit - 1).coerceIn(1, 255) else limit

        val transparentIndices = frames.filter { it.hasTransparency }.map { it.transparentColorIndex }.distinct()
        val singleTransparentSlot = if (transparentIndices.size == 1) transparentIndices.first() else -1

        val uniqueOpaque = LinkedHashSet<Int>(gct.size)
        for (i in gct.indices) {
            if (needsTransparency && i == singleTransparentSlot) continue
            uniqueOpaque.add(gct[i] and 0x00FFFFFF)
        }
        if (uniqueOpaque.isEmpty()) {
            uniqueOpaque.add(0x000000)
        }
        if (uniqueOpaque.size > maxOpaque) {
            return null
        }
        val colors = uniqueOpaque.toIntArray()
        val transparentIndex = if (needsTransparency) colors.size else -1
        return FrameQuantizer.GlobalPalette(
            colors = colors,
            transparentIndex = transparentIndex,
            isPreservedSourcePalette = true
        )
    }

    data class GifFrameDescriptor(
        val index: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val interlaced: Boolean,
        val delayMs: Int,
        val startTimeUs: Long,
        val disposalMethod: Int,
        val hasTransparency: Boolean,
        val transparentColorIndex: Int,
        val lctOffset: Long,
        val lctSize: Int,
        val imageDataOffset: Long
    )

    private val raf = RandomAccessFile(backingFile, "r")
    private val canvasPixels = IntArray(logicalWidth * logicalHeight)
    private var previousRectBackup: IntArray? = null
    private var previousRectFrame: GifFrameDescriptor? = null
    private var currentFrameIndex = -1
    private var isClosed = false

    // Reusable LZW decoding tables to avoid per-frame allocations
    private val lzwPrefix = IntArray(MAX_LZW_STACK)
    private val lzwSuffix = ByteArray(MAX_LZW_STACK)
    private val lzwPixelStack = ByteArray(MAX_LZW_STACK + 1)
    private val readBlockBuf = ByteArray(256)

    // Bounded checkpoint cache (<= 8 MB total) to accelerate reverse playback and timeline scrubbing
    private val checkpointStride: Int
    private val checkpoints = LinkedHashMap<Int, IntArray>()

    init {
        val frameBytes = logicalWidth.toLong() * logicalHeight.toLong() * 4L
        val maxCheckpoints = if (frameBytes > 0L) (MAX_CHECKPOINT_CACHE_BYTES / frameBytes).toInt().coerceIn(0, 12) else 0
        checkpointStride = if (maxCheckpoints > 0 && frames.size > 16) {
            max(8, frames.size / maxCheckpoints)
        } else {
            Int.MAX_VALUE
        }
    }

    val frameCount: Int get() = frames.size

    @Synchronized
    override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap? {
        if (isClosed || frames.isEmpty()) return null
        val targetIndex = findFrameIndexForTimeUs(timeUs)
        return decodeFrameAtIndexInternal(targetIndex, maxEdgePx)
    }

    @Synchronized
    fun getFrameAtIndex(index: Int, maxEdgePx: Int? = null): Bitmap? {
        if (isClosed || frames.isEmpty()) return null
        return decodeFrameAtIndexInternal(index.coerceIn(0, frames.size - 1), maxEdgePx)
    }

    private fun findFrameIndexForTimeUs(timeUs: Long): Int {
        if (frames.size <= 1 || timeUs <= 0L) return 0
        var low = 0
        var high = frames.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = frames[mid].startTimeUs
            val end = start + frames[mid].delayMs.toLong() * 1000L
            when {
                timeUs < start -> high = mid - 1
                timeUs >= end -> low = mid + 1
                else -> return mid
            }
        }
        return high.coerceIn(0, frames.size - 1)
    }

    private fun decodeFrameAtIndexInternal(targetIndex: Int, maxEdgePx: Int?): Bitmap {
        if (targetIndex < currentFrameIndex) {
            // Restore nearest cached checkpoint <= targetIndex, or reset to initial blank canvas
            val bestCp = checkpoints.keys.filter { it <= targetIndex }.maxOrNull()
            if (bestCp != null) {
                val snap = checkpoints[bestCp]!!
                System.arraycopy(snap, 0, canvasPixels, 0, canvasPixels.size)
                currentFrameIndex = bestCp
                previousRectBackup = null
                previousRectFrame = null
            } else {
                resetCanvas()
            }
        }

        while (currentFrameIndex < targetIndex) {
            val nextIdx = currentFrameIndex + 1
            renderNextFrameOntoCanvas(nextIdx)
            currentFrameIndex = nextIdx
            if (checkpointStride < Int.MAX_VALUE &&
                nextIdx > 0 &&
                nextIdx % checkpointStride == 0 &&
                !checkpoints.containsKey(nextIdx) &&
                frames[nextIdx].disposalMethod != Gif89aStreamEncoder.DISPOSAL_RESTORE_PREVIOUS
            ) {
                checkpoints[nextIdx] = canvasPixels.copyOf()
            }
        }

        val fullBitmap = Bitmap.createBitmap(canvasPixels, logicalWidth, logicalHeight, Bitmap.Config.ARGB_8888)
        if (maxEdgePx != null && maxEdgePx > 0) {
            val maxDim = max(logicalWidth, logicalHeight)
            if (maxDim > maxEdgePx) {
                val scale = maxEdgePx.toDouble() / maxDim.toDouble()
                val scaledW = max(2, (logicalWidth * scale).roundToInt())
                val scaledH = max(2, (logicalHeight * scale).roundToInt())
                val scaled = Bitmap.createScaledBitmap(fullBitmap, scaledW, scaledH, true)
                fullBitmap.recycle()
                return scaled
            }
        }
        return fullBitmap
    }

    private fun resetCanvas() {
        canvasPixels.fill(0x00000000)
        previousRectBackup = null
        previousRectFrame = null
        currentFrameIndex = -1
    }

    private fun renderNextFrameOntoCanvas(index: Int) {
        // 1. Apply disposal of the previously rendered frame
        if (currentFrameIndex >= 0) {
            val prev = frames[currentFrameIndex]
            when (prev.disposalMethod) {
                Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND -> {
                    val fillColor = if (metadata.hasTransparency || prev.hasTransparency) {
                        0x00000000
                    } else if (globalColorTable != null && bgColorIndex in globalColorTable.indices) {
                        globalColorTable[bgColorIndex]
                    } else {
                        0x00000000
                    }
                    fillCanvasRect(prev.left, prev.top, prev.width, prev.height, fillColor)
                }
                Gif89aStreamEncoder.DISPOSAL_RESTORE_PREVIOUS -> {
                    val backup = previousRectBackup
                    val rectFrame = previousRectFrame
                    if (backup != null && rectFrame != null) {
                        restoreCanvasRect(rectFrame.left, rectFrame.top, rectFrame.width, rectFrame.height, backup)
                    }
                }
                // DISPOSAL_UNSPECIFIED (0) and DISPOSAL_DO_NOT_DISPOSE (1): leave canvas intact
            }
            previousRectBackup = null
            previousRectFrame = null
        } else {
            canvasPixels.fill(0x00000000)
        }

        val frame = frames[index]
        if (frame.width <= 0 || frame.height <= 0) return

        // 2. If this frame uses RESTORE_TO_PREVIOUS, snapshot its destination area before compositing
        if (frame.disposalMethod == Gif89aStreamEncoder.DISPOSAL_RESTORE_PREVIOUS) {
            previousRectBackup = snapshotCanvasRect(frame.left, frame.top, frame.width, frame.height)
            previousRectFrame = frame
        }

        // 3. Load active color table (LCT overrides GCT)
        val colorTable = if (frame.lctOffset >= 0L && frame.lctSize > 0) {
            readColorTableAt(frame.lctOffset, frame.lctSize)
        } else {
            globalColorTable
        } ?: return

        // 4. LZW decompress frame indices
        val pixelCount = frame.width * frame.height
        val decodedIndices = decodeLzwPixels(frame.imageDataOffset, pixelCount)

        // 5. Composite onto logical screen canvas (handling interlacing and transparency)
        val transparentIdx = if (frame.hasTransparency) frame.transparentColorIndex else -1
        if (!frame.interlaced) {
            var srcIdx = 0
            for (y in 0 until frame.height) {
                val dstY = frame.top + y
                if (dstY in 0 until logicalHeight) {
                    val rowBase = dstY * logicalWidth
                    for (x in 0 until frame.width) {
                        val dstX = frame.left + x
                        if (dstX in 0 until logicalWidth) {
                            val colorIdx = decodedIndices[srcIdx].toInt() and 0xFF
                            if (colorIdx != transparentIdx && colorIdx < colorTable.size) {
                                canvasPixels[rowBase + dstX] = colorTable[colorIdx]
                            }
                        }
                        srcIdx++
                    }
                } else {
                    srcIdx += frame.width
                }
            }
        } else {
            // GIF 4-pass interlacing
            val passStarts = intArrayOf(0, 4, 2, 1)
            val passSteps = intArrayOf(8, 8, 4, 2)
            var srcRow = 0
            for (pass in 0..3) {
                var y = passStarts[pass]
                val step = passSteps[pass]
                while (y < frame.height) {
                    val dstY = frame.top + y
                    val srcRowOffset = srcRow * frame.width
                    if (dstY in 0 until logicalHeight && srcRowOffset + frame.width <= decodedIndices.size) {
                        val rowBase = dstY * logicalWidth
                        for (x in 0 until frame.width) {
                            val dstX = frame.left + x
                            if (dstX in 0 until logicalWidth) {
                                val colorIdx = decodedIndices[srcRowOffset + x].toInt() and 0xFF
                                if (colorIdx != transparentIdx && colorIdx < colorTable.size) {
                                    canvasPixels[rowBase + dstX] = colorTable[colorIdx]
                                }
                            }
                        }
                    }
                    srcRow++
                    y += step
                }
            }
        }
    }

    private fun fillCanvasRect(left: Int, top: Int, width: Int, height: Int, color: Int) {
        val x0 = left.coerceIn(0, logicalWidth)
        val y0 = top.coerceIn(0, logicalHeight)
        val x1 = (left + width).coerceIn(x0, logicalWidth)
        val y1 = (top + height).coerceIn(y0, logicalHeight)
        for (y in y0 until y1) {
            val rowOff = y * logicalWidth
            canvasPixels.fill(color, rowOff + x0, rowOff + x1)
        }
    }

    private fun snapshotCanvasRect(left: Int, top: Int, width: Int, height: Int): IntArray {
        val x0 = left.coerceIn(0, logicalWidth)
        val y0 = top.coerceIn(0, logicalHeight)
        val x1 = (left + width).coerceIn(x0, logicalWidth)
        val y1 = (top + height).coerceIn(y0, logicalHeight)
        val rw = x1 - x0
        val rh = y1 - y0
        val backup = IntArray(rw * rh)
        for (y in 0 until rh) {
            System.arraycopy(canvasPixels, (y0 + y) * logicalWidth + x0, backup, y * rw, rw)
        }
        return backup
    }

    private fun restoreCanvasRect(left: Int, top: Int, width: Int, height: Int, backup: IntArray) {
        val x0 = left.coerceIn(0, logicalWidth)
        val y0 = top.coerceIn(0, logicalHeight)
        val x1 = (left + width).coerceIn(x0, logicalWidth)
        val y1 = (top + height).coerceIn(y0, logicalHeight)
        val rw = x1 - x0
        val rh = y1 - y0
        if (backup.size < rw * rh) return
        for (y in 0 until rh) {
            System.arraycopy(backup, y * rw, canvasPixels, (y0 + y) * logicalWidth + x0, rw)
        }
    }

    private fun readColorTableAt(offset: Long, count: Int): IntArray {
        raf.seek(offset)
        val raw = ByteArray(count * 3)
        raf.readFully(raw)
        return IntArray(count) { i ->
            val r = raw[i * 3].toInt() and 0xFF
            val g = raw[i * 3 + 1].toInt() and 0xFF
            val b = raw[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun decodeLzwPixels(dataOffset: Long, pixelCount: Int): ByteArray {
        val output = ByteArray(pixelCount)
        raf.seek(dataOffset)
        val minCodeSize = raf.read()
        if (minCodeSize < 2 || minCodeSize > 11) {
            return output
        }

        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var available = eoiCode + 1
        var oldCode = NULL_CODE
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1

        for (c in 0 until clearCode) {
            lzwPrefix[c] = 0
            lzwSuffix[c] = c.toByte()
        }

        var datum = 0
        var bits = 0
        var count = 0
        var bi = 0
        var first = 0
        var top = 0
        var pi = 0

        while (pi < pixelCount) {
            if (top == 0) {
                while (bits < codeSize) {
                    if (count == 0) {
                        val blockSize = raf.read()
                        if (blockSize <= 0) break
                        var readTotal = 0
                        while (readTotal < blockSize) {
                            val r = raf.read(readBlockBuf, readTotal, blockSize - readTotal)
                            if (r < 0) break
                            readTotal += r
                        }
                        count = readTotal
                        bi = 0
                        if (count == 0) break
                    }
                    datum += (readBlockBuf[bi].toInt() and 0xFF) shl bits
                    bits += 8
                    bi++
                    count--
                }
                if (bits < codeSize) break

                var code = datum and codeMask
                datum = datum ushr codeSize
                bits -= codeSize

                if (code == clearCode) {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    available = eoiCode + 1
                    oldCode = NULL_CODE
                    continue
                }
                if (code == eoiCode) {
                    break
                }
                if (oldCode == NULL_CODE) {
                    if (code < clearCode) {
                        output[pi++] = lzwSuffix[code]
                        oldCode = code
                        first = code
                    }
                    continue
                }

                val inCode = code
                if (code >= available) {
                    if (code > available) {
                        // Malformed stream safeguard
                        break
                    }
                    lzwPixelStack[top++] = first.toByte()
                    code = oldCode
                }
                while (code >= clearCode) {
                    lzwPixelStack[top++] = lzwSuffix[code]
                    code = lzwPrefix[code]
                    if (top >= MAX_LZW_STACK) break
                }
                first = lzwSuffix[code].toInt() and 0xFF
                lzwPixelStack[top++] = first.toByte()

                if (available < MAX_LZW_STACK) {
                    lzwPrefix[available] = oldCode
                    lzwSuffix[available] = first.toByte()
                    available++
                    if ((available and codeMask) == 0 && available < MAX_LZW_STACK) {
                        codeSize++
                        codeMask += available
                    }
                }
                oldCode = inCode
            }

            top--
            output[pi++] = lzwPixelStack[top]
        }
        return output
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        checkpoints.clear()
        try {
            raf.close()
        } catch (_: Throwable) {
        }
        if (deleteFileOnClose) {
            try {
                backingFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val MAX_LZW_STACK = 4096
        private const val NULL_CODE = -1
        private const val MAX_CHECKPOINT_CACHE_BYTES = 8L * 1024L * 1024L

        fun isGifHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
            if (length < 6) return false
            val magic = String(bytes, 0, 6, Charsets.US_ASCII)
            return magic == "GIF89a" || magic == "GIF87a"
        }

        /**
         * Opens an animated GIF from an Android [Uri], spooling its raw bytes into a temporary
         * cache file for low-memory indexed random access, then scans its full frame table.
         */
        fun open(context: Context, uri: Uri): AnimatedGifFrameSource {
            val (displayName, queriedSize) = queryDisplayNameAndSize(context, uri)
            val tempFile = File.createTempFile("nepi_gif_src_", ".gif", context.cacheDir)
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Unable to open GIF stream for the selected file.")
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                val sizeBytes = if (queriedSize > 0L) queriedSize else tempFile.length()
                return openFile(
                    file = tempFile,
                    uri = uri,
                    displayName = displayName,
                    fileSizeBytes = sizeBytes,
                    deleteOnClose = true
                )
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
        }

        /**
         * Opens an animated GIF directly from a local [File] (zero copy; ideal for local files & tests).
         */
        fun openFile(
            file: File,
            uri: Uri = Uri.fromFile(file),
            displayName: String = file.name,
            fileSizeBytes: Long = file.length(),
            deleteOnClose: Boolean = false
        ): AnimatedGifFrameSource {
            if (!file.exists() || file.length() < 14L) {
                throw IllegalArgumentException("Invalid or empty GIF file.")
            }

            val parsed = scanGifIndex(file)
            if (parsed.logicalWidth <= 0 || parsed.logicalHeight <= 0 || parsed.frames.isEmpty()) {
                throw IllegalArgumentException(
                    "Invalid or unsupported GIF: no decodable frames found (${parsed.logicalWidth}x${parsed.logicalHeight})."
                )
            }

            val totalDurationMs = parsed.frames.sumOf { it.delayMs.toLong() }.coerceAtLeast(100L)
            val avgFps = ((parsed.frames.size * 1000.0) / totalDurationMs.toDouble())
                .toFloat()
                .coerceIn(1f, 120f)
            val delays = parsed.frames.map { it.delayMs }
            val timestampsUs = parsed.frames.map { it.startTimeUs }
            val isVariable = delays.distinct().size > 1
            val hasTransparency = parsed.frames.any { it.hasTransparency }

            val metadata = VideoMetadata(
                uri = uri,
                displayName = displayName,
                rawWidth = parsed.logicalWidth,
                rawHeight = parsed.logicalHeight,
                metadataRotation = 0,
                durationMs = totalDurationMs,
                frameRate = avgFps,
                fileSizeBytes = fileSizeBytes.coerceAtLeast(file.length()),
                frameTimestampsUs = timestampsUs,
                isVariableFrameRate = isVariable,
                inputType = MediaInputType.GIF,
                frameDelaysMs = delays,
                loopCount = parsed.loopCount,
                hasTransparency = hasTransparency
            )

            return AnimatedGifFrameSource(
                metadata = metadata,
                backingFile = file,
                deleteFileOnClose = deleteOnClose,
                logicalWidth = parsed.logicalWidth,
                logicalHeight = parsed.logicalHeight,
                globalColorTable = parsed.globalColorTable,
                bgColorIndex = parsed.bgColorIndex,
                frames = parsed.frames
            )
        }

        private data class ParsedGifIndex(
            val logicalWidth: Int,
            val logicalHeight: Int,
            val globalColorTable: IntArray?,
            val bgColorIndex: Int,
            val loopCount: Int,
            val frames: List<GifFrameDescriptor>
        )

        private fun scanGifIndex(file: File): ParsedGifIndex {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(6)
                raf.readFully(header)
                if (!isGifHeader(header)) {
                    throw IllegalArgumentException("Selected file is not a valid GIF87a or GIF89a image.")
                }

                val logicalWidth = readU16LE(raf)
                val logicalHeight = readU16LE(raf)
                val lsdPacked = raf.read()
                val bgColorIndex = raf.read()
                raf.read() // pixel aspect ratio

                if (lsdPacked < 0 || bgColorIndex < 0) {
                    throw IllegalArgumentException("Truncated GIF Logical Screen Descriptor.")
                }

                val hasGct = (lsdPacked and 0x80) != 0
                val gctSize = if (hasGct) 1 shl ((lsdPacked and 0x07) + 1) else 0
                val gct = if (hasGct) {
                    val raw = ByteArray(gctSize * 3)
                    raf.readFully(raw)
                    IntArray(gctSize) { i ->
                        val r = raw[i * 3].toInt() and 0xFF
                        val g = raw[i * 3 + 1].toInt() and 0xFF
                        val b = raw[i * 3 + 2].toInt() and 0xFF
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else null

                val frames = ArrayList<GifFrameDescriptor>()
                var loopCount = 0

                // Pending Graphic Control Extension fields for the next Image Descriptor
                var gceDisposal = Gif89aStreamEncoder.DISPOSAL_UNSPECIFIED
                var gceHasTransparency = false
                var gceTransparentIndex = 0
                var gceDelayCs = 0
                var hasGce = false

                var cumulativeTimeUs = 0L

                while (true) {
                    val blockId = raf.read()
                    if (blockId < 0 || blockId == 0x3B) break // EOF or Trailer

                    when (blockId) {
                        0x21 -> { // Extension
                            val label = raf.read()
                            if (label < 0) break
                            when (label) {
                                0xF9 -> { // Graphic Control Extension
                                    val blockSize = raf.read()
                                    if (blockSize == 4) {
                                        val packed = raf.read()
                                        gceDelayCs = readU16LE(raf)
                                        gceTransparentIndex = raf.read().coerceAtLeast(0)
                                        val terminator = raf.read()
                                        if (packed >= 0 && terminator >= 0) {
                                            gceDisposal = (packed ushr 2) and 0x07
                                            gceHasTransparency = (packed and 0x01) != 0
                                            hasGce = true
                                        }
                                    } else {
                                        if (blockSize > 0) raf.seek(raf.filePointer + blockSize)
                                        skipSubBlocks(raf)
                                    }
                                }
                                0xFF -> { // Application Extension
                                    val appBlockSize = raf.read()
                                    if (appBlockSize == 11) {
                                        val appIdBytes = ByteArray(11)
                                        raf.readFully(appIdBytes)
                                        val appId = String(appIdBytes, Charsets.US_ASCII)
                                        if (appId == "NETSCAPE2.0" || appId == "ANIMEXTS1.0") {
                                            val subSize = raf.read()
                                            if (subSize >= 3) {
                                                val subType = raf.read()
                                                val lo = raf.read()
                                                val hi = raf.read()
                                                if (subType == 1 && lo >= 0 && hi >= 0) {
                                                    loopCount = (lo and 0xFF) or ((hi and 0xFF) shl 8)
                                                }
                                                if (subSize > 3) {
                                                    raf.seek(raf.filePointer + (subSize - 3))
                                                }
                                                skipSubBlocks(raf)
                                            } else if (subSize > 0) {
                                                raf.seek(raf.filePointer + subSize)
                                                skipSubBlocks(raf)
                                            }
                                        } else {
                                            skipSubBlocks(raf)
                                        }
                                    } else {
                                        if (appBlockSize > 0) raf.seek(raf.filePointer + appBlockSize)
                                        skipSubBlocks(raf)
                                    }
                                }
                                else -> {
                                    // Comment (0xFE), Plain Text (0x01), or unknown extension
                                    skipSubBlocks(raf)
                                }
                            }
                        }
                        0x2C -> { // Image Descriptor
                            val left = readU16LE(raf)
                            val top = readU16LE(raf)
                            val width = readU16LE(raf)
                            val height = readU16LE(raf)
                            val packed = raf.read()
                            if (packed < 0) break

                            val hasLct = (packed and 0x80) != 0
                            val interlaced = (packed and 0x40) != 0
                            val lctSize = if (hasLct) 1 shl ((packed and 0x07) + 1) else 0
                            val lctOffset = if (hasLct) {
                                val pos = raf.filePointer
                                raf.seek(pos + lctSize * 3L)
                                pos
                            } else {
                                -1L
                            }

                            val imageDataOffset = raf.filePointer
                            val minCodeSize = raf.read()
                            if (minCodeSize < 0) break
                            skipSubBlocks(raf)

                            val delayMs = when {
                                !hasGce || gceDelayCs <= 0 -> 100
                                else -> gceDelayCs * 10
                            }

                            frames.add(
                                GifFrameDescriptor(
                                    index = frames.size,
                                    left = left,
                                    top = top,
                                    width = width,
                                    height = height,
                                    interlaced = interlaced,
                                    delayMs = delayMs,
                                    startTimeUs = cumulativeTimeUs,
                                    disposalMethod = gceDisposal,
                                    hasTransparency = gceHasTransparency,
                                    transparentColorIndex = gceTransparentIndex,
                                    lctOffset = lctOffset,
                                    lctSize = lctSize,
                                    imageDataOffset = imageDataOffset
                                )
                            )
                            cumulativeTimeUs += delayMs.toLong() * 1000L

                            // Reset GCE per GIF89a spec
                            gceDisposal = Gif89aStreamEncoder.DISPOSAL_UNSPECIFIED
                            gceHasTransparency = false
                            gceTransparentIndex = 0
                            gceDelayCs = 0
                            hasGce = false
                        }
                        0x00 -> {
                            // Stray zero byte padding between blocks; ignore safely
                        }
                        else -> {
                            // Unknown block byte; stop scanning safely
                            break
                        }
                    }
                }

                return ParsedGifIndex(
                    logicalWidth = logicalWidth,
                    logicalHeight = logicalHeight,
                    globalColorTable = gct,
                    bgColorIndex = bgColorIndex,
                    loopCount = loopCount,
                    frames = frames
                )
            }
        }

        private fun readU16LE(raf: RandomAccessFile): Int {
            val lo = raf.read()
            val hi = raf.read()
            if (lo < 0 || hi < 0) throw IOException("Unexpected end of GIF stream.")
            return (lo and 0xFF) or ((hi and 0xFF) shl 8)
        }

        private fun skipSubBlocks(raf: RandomAccessFile) {
            while (true) {
                val size = raf.read()
                if (size <= 0) break
                val target = raf.filePointer + size
                if (target > raf.length()) {
                    raf.seek(raf.length())
                    break
                }
                raf.seek(target)
            }
        }

        private fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "animation.gif"
            var size = 0L
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                        }
                        if (sizeIdx >= 0) {
                            size = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return name to size
        }

        /**
         * Sniffs whether [uri] points to a GIF file by inspecting MIME type, magic header bytes,
         * and filename extension.
         */
        fun isGifUri(context: Context, uri: Uri): Boolean {
            try {
                val mime = context.contentResolver.getType(uri)
                if (mime != null && mime.equals("image/gif", ignoreCase = true)) {
                    return true
                }
            } catch (_: Throwable) {
            }
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffered = BufferedInputStream(stream, 16)
                    val header = ByteArray(6)
                    val read = buffered.read(header)
                    if (isGifHeader(header, read)) return true
                }
            } catch (_: Throwable) {
            }
            val path = uri.lastPathSegment ?: uri.toString()
            return path.endsWith(".gif", ignoreCase = true)
        }
    }
}
