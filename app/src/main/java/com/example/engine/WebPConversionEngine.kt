package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.StatFs
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoderOptions
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.aureusapps.android.webpandroid.encoder.WebPEncoder
import com.aureusapps.android.webpandroid.encoder.WebPMuxAnimParams
import com.aureusapps.android.webpandroid.encoder.WebPPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Configuration passed into an [AnimatedWebPBackend] session.
 *
 * Defaults to the compatibility-first full-canvas profile (`minimizeSize = false`) so every
 * frame is encoded as an independent full-canvas frame without partial delta sub-rectangles.
 */
data class AnimatedWebPSessionConfig(
    val width: Int,
    val height: Int,
    val loopCount: Int,
    val lossless: Boolean,
    val quality: Int,
    val minimizeSize: Boolean = false,
    val backgroundColor: Int = 0x00000000
)

/**
 * Streaming, frame-by-frame animated WebP encoding session.
 * Implementations MUST release native/temporary resources in [close].
 */
interface AnimatedWebPSession : AutoCloseable {
    fun addFrame(timestampMs: Long, bitmap: Bitmap, shouldContinue: () -> Boolean = { true })
    fun assemble(endTimestampMs: Long, outputFile: File)
}

/**
 * Pluggable genuine animated WebP backend.
 * Production uses [LibWebPAnimEncoderBackend] (Google's official `libwebp` encoder via JNI +
 * strict compatibility-first full-canvas RIFF/WEBP container assembly).
 */
interface AnimatedWebPBackend {
    suspend fun <T> withSession(
        config: AnimatedWebPSessionConfig,
        block: suspend (AnimatedWebPSession) -> T
    ): T
}

/**
 * Extracted, spec-validated single-frame WebP payload sub-chunks (`[ALPH] + VP8 ` or `VP8L`)
 * ready for placement inside a full-canvas `ANMF` chunk.
 *
 * Every sub-chunk inside [subChunksBytes] is already even-padded with a single `0x00` byte when
 * its unpadded payload length is odd, matching Google's `libwebp` `ANMF` container convention.
 */
data class ExtractedWebPFramePayload(
    val subChunksBytes: ByteArray,
    val hasAlpha: Boolean,
    val payloadChunkType: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedWebPFramePayload) return false
        return subChunksBytes.contentEquals(other.subChunksBytes) &&
            hasAlpha == other.hasAlpha &&
            payloadChunkType == other.payloadChunkType
    }

    override fun hashCode(): Int {
        var result = subChunksBytes.contentHashCode()
        result = 31 * result + hasAlpha.hashCode()
        result = 31 * result + payloadChunkType.hashCode()
        return result
    }
}

/**
 * Spec-compliant, compatibility-first Animated WebP RIFF/WEBP container muxer.
 *
 * Unlike `libwebp`'s size-minimized `WebPAnimEncoder` (which emits partial delta sub-rectangles,
 * synthetic `ALPH` delta masks on opaque video, and mixed `ALPHA_BLEND` / `DISPOSE_NONE` /
 * `DISPOSE_BG` chains), this muxer produces a maximally compatible container matching the
 * Google WebP Container Specification:
 *
 * - Top-level chunks strictly limited to `VP8X`, `ANIM`, and `ANMF` (zero ICCP/EXIF/XMP/unknown chunks).
 * - `VP8X` sets `ANIM` (`0x02`), and sets `ALPH` (`0x10`) if and only if at least one frame
 *   genuinely carries an `ALPH` chunk or a `VP8L` bitstream with `alpha_is_used == 1`.
 * - Every `ANMF` frame covers the full canvas (`x = 0, y = 0, width = canvasWidth, height = canvasHeight`)
 *   with monotonic non-zero durations (`>= 10 ms`).
 * - Explicit, consistent blending/disposal per animation:
 *   - Opaque animations: `NO_BLEND` (`0x02`) + `DISPOSE_NONE` (`0x00`) -> flags `0x02`.
 *   - Transparent animations: `NO_BLEND` (`0x02`) + `DISPOSE_BACKGROUND` (`0x01`) -> flags `0x03`
 *     with `ANIM.backgroundColor = 0x00000000`, guaranteeing zero ghosting or trails under both
 *     spec-compliant and naive alpha-blending decoders.
 */
object FullCanvasWebPContainerMuxer {

    internal data class SpoolFrameEntry(
        val timestampMs: Long,
        val payloadLength: Int,
        val hasAlpha: Boolean
    )

    private const val MIN_FRAME_DURATION_MS = 10L
    private const val MAX_FRAME_DURATION_MS = 0xFFFFFFL
    private const val VP8X_CHUNK_DISK_SIZE = 8L + 10L
    private const val ANIM_CHUNK_DISK_SIZE = 8L + 6L
    private const val ANMF_HEADER_SIZE = 16
    private const val COPY_BUFFER_BYTES = 16 * 1024

    /**
     * Parses a single-frame RIFF/WEBP file encoded by Google's `libwebp` (`WebPEncode`),
     * validates its `VP8 ` or `VP8L` frame header dimensions against [expectedWidth] and
     * [expectedHeight], strips single-frame `VP8X` and any optional metadata chunks, and returns
     * strictly even-padded `ANMF` sub-chunks (`[ALPH] + VP8 ` or `VP8L`).
     */
    fun extractSingleFramePayload(
        singleFrameWebP: ByteArray,
        expectedWidth: Int,
        expectedHeight: Int
    ): ExtractedWebPFramePayload {
        if (singleFrameWebP.size < 20) {
            throw IOException("Single-frame WebP output is truncated (${singleFrameWebP.size} bytes).")
        }
        val riff = String(singleFrameWebP, 0, 4, Charsets.US_ASCII)
        val webp = String(singleFrameWebP, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || webp != "WEBP") {
            throw IOException("Invalid single-frame WebP header: expected RIFF/WEBP, got $riff/$webp.")
        }

        val bb = ByteBuffer.wrap(singleFrameWebP).order(ByteOrder.LITTLE_ENDIAN)
        val declaredRiffSize = bb.getInt(4).toLong() and 0xFFFFFFFFL
        val riffEnd = minOf(singleFrameWebP.size.toLong(), 8L + declaredRiffSize).toInt()

        var offset = 12
        var alphChunk: ByteArray? = null
        var imageChunk: ByteArray? = null
        var imageChunkType = ""
        var hasAlpha = false

        while (offset + 8 <= riffEnd) {
            val fourCc = String(singleFrameWebP, offset, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(offset + 4)
            val dataStart = offset + 8
            val dataEnd = dataStart + chunkSize
            if (chunkSize < 0 || dataEnd > riffEnd) {
                throw IOException("Truncated chunk '$fourCc' inside single-frame WebP at offset $offset.")
            }

            when (fourCc) {
                "ALPH" -> {
                    if (chunkSize < 1) {
                        throw IOException("Invalid empty ALPH chunk in single-frame WebP.")
                    }
                    alphChunk = copyPaddedChunk(singleFrameWebP, offset, chunkSize)
                    hasAlpha = true
                }

                "VP8 " -> {
                    validateVp8KeyframeHeader(singleFrameWebP, dataStart, chunkSize, expectedWidth, expectedHeight)
                    imageChunk = copyPaddedChunk(singleFrameWebP, offset, chunkSize)
                    imageChunkType = "VP8"
                }

                "VP8L" -> {
                    val vp8lHasAlpha = validateVp8lHeader(
                        singleFrameWebP,
                        dataStart,
                        chunkSize,
                        expectedWidth,
                        expectedHeight
                    )
                    if (vp8lHasAlpha) {
                        hasAlpha = true
                    }
                    imageChunk = copyPaddedChunk(singleFrameWebP, offset, chunkSize)
                    imageChunkType = "VP8L"
                }

                // VP8X and any optional metadata (ICCP, EXIF, XMP ) are stripped intentionally
                // for clean, minimal compatibility-first animation frames.
            }

            offset = dataEnd + (chunkSize and 1)
        }

        val finalImageChunk = imageChunk
            ?: throw IOException("Single-frame WebP encoder produced no VP8 or VP8L image chunk.")

        val out = ByteArrayOutputStream((alphChunk?.size ?: 0) + finalImageChunk.size)
        // Per WebP Container Spec, ALPH precedes VP8 only; VP8L carries its own lossless alpha.
        if (imageChunkType == "VP8" && alphChunk != null) {
            out.write(alphChunk)
        }
        out.write(finalImageChunk)

        return ExtractedWebPFramePayload(
            subChunksBytes = out.toByteArray(),
            hasAlpha = hasAlpha,
            payloadChunkType = imageChunkType
        )
    }

    /**
     * Streams a spec-compliant full-canvas Animated WebP into [outputFile] using frame payloads
     * previously written sequentially to [spoolFile]. Operates in O(1) RAM regardless of frame count.
     */
    internal fun assembleFromSpool(
        width: Int,
        height: Int,
        loopCount: Int,
        backgroundColor: Int,
        frames: List<SpoolFrameEntry>,
        endTimestampMs: Long,
        spoolFile: File,
        outputFile: File
    ) {
        validateCanvasAndFrames(width, height, frames.size)
        val durations = computeMonotonicDurations(frames.map { it.timestampMs }, endTimestampMs)
        val anyAlpha = frames.any { it.hasAlpha }
        val totalRiffPayload = computeTotalRiffPayloadSize(frames.map { it.payloadLength })

        outputFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        BufferedInputStream(FileInputStream(spoolFile), COPY_BUFFER_BYTES).use { spoolIn ->
            BufferedOutputStream(FileOutputStream(outputFile), COPY_BUFFER_BYTES).use { out ->
                writeContainerHeader(
                    out = out,
                    totalRiffPayloadSize = totalRiffPayload,
                    width = width,
                    height = height,
                    hasAlpha = anyAlpha,
                    backgroundColor = backgroundColor,
                    loopCount = loopCount
                )

                val anmfFlags = if (anyAlpha) 0x03 else 0x02
                val copyBuf = ByteArray(COPY_BUFFER_BYTES)
                for (i in frames.indices) {
                    val entry = frames[i]
                    writeAnmfHeader(
                        out = out,
                        width = width,
                        height = height,
                        durationMs = durations[i],
                        flags = anmfFlags,
                        payloadLength = entry.payloadLength
                    )
                    copyExactBytes(spoolIn, out, entry.payloadLength, copyBuf)
                    if (((ANMF_HEADER_SIZE + entry.payloadLength) and 1) == 1) {
                        out.write(0)
                    }
                }
                out.flush()
            }
        }
    }

    /**
     * Assembles a spec-compliant full-canvas Animated WebP directly from in-memory extracted
     * frame payloads (primarily used by deterministic JVM verification tests).
     */
    fun assembleInMemory(
        width: Int,
        height: Int,
        loopCount: Int,
        backgroundColor: Int,
        frames: List<Pair<Long, ExtractedWebPFramePayload>>,
        endTimestampMs: Long,
        outputFile: File
    ) {
        validateCanvasAndFrames(width, height, frames.size)
        val durations = computeMonotonicDurations(frames.map { it.first }, endTimestampMs)
        val anyAlpha = frames.any { it.second.hasAlpha }
        val totalRiffPayload = computeTotalRiffPayloadSize(frames.map { it.second.subChunksBytes.size })

        outputFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }

        BufferedOutputStream(FileOutputStream(outputFile), COPY_BUFFER_BYTES).use { out ->
            writeContainerHeader(
                out = out,
                totalRiffPayloadSize = totalRiffPayload,
                width = width,
                height = height,
                hasAlpha = anyAlpha,
                backgroundColor = backgroundColor,
                loopCount = loopCount
            )
            val anmfFlags = if (anyAlpha) 0x03 else 0x02
            for (i in frames.indices) {
                val payload = frames[i].second.subChunksBytes
                writeAnmfHeader(
                    out = out,
                    width = width,
                    height = height,
                    durationMs = durations[i],
                    flags = anmfFlags,
                    payloadLength = payload.size
                )
                out.write(payload)
                if (((ANMF_HEADER_SIZE + payload.size) and 1) == 1) {
                    out.write(0)
                }
            }
            out.flush()
        }
    }

    private fun validateCanvasAndFrames(width: Int, height: Int, frameCount: Int) {
        if (width !in 1..16383 || height !in 1..16383) {
            throw IOException("Invalid WebP canvas dimensions: ${width}x$height.")
        }
        if (frameCount <= 0) {
            throw IOException("Cannot assemble Animated WebP with zero frames.")
        }
    }

    private fun computeMonotonicDurations(timestampsMs: List<Long>, endTimestampMs: Long): IntArray {
        val result = IntArray(timestampsMs.size)
        var lastTimestamp = 0L
        for (i in timestampsMs.indices) {
            val currentTs = if (i == 0) timestampsMs[i].coerceAtLeast(0L) else max(timestampsMs[i], lastTimestamp)
            val nextTs = if (i + 1 < timestampsMs.size) {
                timestampsMs[i + 1]
            } else {
                endTimestampMs
            }
            val rawDuration = nextTs - currentTs
            val clamped = rawDuration.coerceIn(MIN_FRAME_DURATION_MS, MAX_FRAME_DURATION_MS)
            result[i] = clamped.toInt()
            lastTimestamp = currentTs + clamped
        }
        return result
    }

    private fun computeTotalRiffPayloadSize(payloadLengths: List<Int>): Long {
        // 4 bytes for "WEBP" + VP8X (18) + ANIM (14) + all ANMF chunks
        var total = 4L + VP8X_CHUNK_DISK_SIZE + ANIM_CHUNK_DISK_SIZE
        for (len in payloadLengths) {
            val anmfPayload = ANMF_HEADER_SIZE.toLong() + len.toLong()
            total += 8L + anmfPayload + (anmfPayload and 1L)
        }
        if (total > 0xFFFFFFFEL) {
            throw IOException("Animated WebP size ($total bytes) exceeds RIFF 32-bit container limit.")
        }
        return total
    }

    private fun writeContainerHeader(
        out: OutputStream,
        totalRiffPayloadSize: Long,
        width: Int,
        height: Int,
        hasAlpha: Boolean,
        backgroundColor: Int,
        loopCount: Int
    ) {
        // 1. RIFF header (12 bytes)
        val riff = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(totalRiffPayloadSize.toInt())
            .put("WEBP".toByteArray(Charsets.US_ASCII))
            .array()
        out.write(riff)

        // 2. VP8X chunk (8 + 10 = 18 bytes)
        val vp8x = ByteArray(18)
        vp8x[0] = 'V'.code.toByte()
        vp8x[1] = 'P'.code.toByte()
        vp8x[2] = '8'.code.toByte()
        vp8x[3] = 'X'.code.toByte()
        writeChunkSizeLE(vp8x, 10)
        // Flags: bit 1 = ANIM (0x02), bit 4 = ALPH (0x10); all reserved bits strictly 0
        vp8x[8] = (0x02 or (if (hasAlpha) 0x10 else 0x00)).toByte()
        vp8x[9] = 0
        vp8x[10] = 0
        vp8x[11] = 0
        writeUint24LE(vp8x, 12, width - 1)
        writeUint24LE(vp8x, 15, height - 1)
        out.write(vp8x)

        // 3. ANIM chunk (8 + 6 = 14 bytes)
        val anim = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            .put("ANIM".toByteArray(Charsets.US_ASCII))
            .putInt(6)
            .putInt(backgroundColor)
            .putShort(loopCount.coerceIn(0, 65535).toShort())
            .array()
        out.write(anim)
    }

    private fun writeAnmfHeader(
        out: OutputStream,
        width: Int,
        height: Int,
        durationMs: Int,
        flags: Int,
        payloadLength: Int
    ) {
        val header = ByteArray(8 + ANMF_HEADER_SIZE)
        header[0] = 'A'.code.toByte()
        header[1] = 'N'.code.toByte()
        header[2] = 'M'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeChunkSizeLE(header, ANMF_HEADER_SIZE + payloadLength)
        // Full-canvas frame origin (x = 0, y = 0)
        writeUint24LE(header, 8, 0)
        writeUint24LE(header, 11, 0)
        // Full-canvas dimensions
        writeUint24LE(header, 14, width - 1)
        writeUint24LE(header, 17, height - 1)
        // Non-zero duration in milliseconds
        writeUint24LE(header, 20, durationMs.coerceIn(MIN_FRAME_DURATION_MS.toInt(), MAX_FRAME_DURATION_MS.toInt()))
        // Bits 7..2 reserved = 0; bit 1 = NO_BLEND; bit 0 = DISPOSE_BG
        header[23] = (flags and 0x03).toByte()
        out.write(header)
    }

    private fun copyExactBytes(
        input: BufferedInputStream,
        output: OutputStream,
        length: Int,
        buffer: ByteArray
    ) {
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size)
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) {
                throw IOException("Unexpected EOF while reading spooled WebP frame payload ($remaining bytes missing).")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyPaddedChunk(src: ByteArray, chunkStart: Int, payloadSize: Int): ByteArray {
        val pad = payloadSize and 1
        val result = ByteArray(8 + payloadSize + pad)
        System.arraycopy(src, chunkStart, result, 0, 8 + payloadSize)
        if (pad == 1) {
            result[result.lastIndex] = 0
        }
        return result
    }

    private fun validateVp8KeyframeHeader(
        bytes: ByteArray,
        dataStart: Int,
        chunkSize: Int,
        expectedWidth: Int,
        expectedHeight: Int
    ) {
        if (chunkSize < 10) {
            throw IOException("Truncated VP8 frame chunk ($chunkSize bytes).")
        }
        val frameTag0 = bytes[dataStart].toInt() and 0xFF
        val isKeyframe = (frameTag0 and 0x01) == 0
        val startCodeOk = (bytes[dataStart + 3].toInt() and 0xFF) == 0x9D &&
            (bytes[dataStart + 4].toInt() and 0xFF) == 0x01 &&
            (bytes[dataStart + 5].toInt() and 0xFF) == 0x2A
        if (!isKeyframe || !startCodeOk) {
            throw IOException("Invalid VP8 keyframe header inside encoded frame.")
        }
        val vp8Width = ((bytes[dataStart + 6].toInt() and 0xFF) or
            ((bytes[dataStart + 7].toInt() and 0xFF) shl 8)) and 0x3FFF
        val vp8Height = ((bytes[dataStart + 8].toInt() and 0xFF) or
            ((bytes[dataStart + 9].toInt() and 0xFF) shl 8)) and 0x3FFF
        if (vp8Width != expectedWidth || vp8Height != expectedHeight) {
            throw IOException(
                "Encoded VP8 frame dimensions (${vp8Width}x$vp8Height) do not match canvas (${expectedWidth}x$expectedHeight)."
            )
        }
    }

    private fun validateVp8lHeader(
        bytes: ByteArray,
        dataStart: Int,
        chunkSize: Int,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        if (chunkSize < 5) {
            throw IOException("Truncated VP8L frame chunk ($chunkSize bytes).")
        }
        val signature = bytes[dataStart].toInt() and 0xFF
        if (signature != 0x2F) {
            throw IOException("Invalid VP8L signature byte: 0x${signature.toString(16)}.")
        }
        val bits = (bytes[dataStart + 1].toInt() and 0xFF) or
            ((bytes[dataStart + 2].toInt() and 0xFF) shl 8) or
            ((bytes[dataStart + 3].toInt() and 0xFF) shl 16) or
            ((bytes[dataStart + 4].toInt() and 0xFF) shl 24)
        val vp8lWidth = (bits and 0x3FFF) + 1
        val vp8lHeight = ((bits ushr 14) and 0x3FFF) + 1
        val alphaIsUsed = ((bits ushr 28) and 0x01) != 0
        if (vp8lWidth != expectedWidth || vp8lHeight != expectedHeight) {
            throw IOException(
                "Encoded VP8L frame dimensions (${vp8lWidth}x$vp8lHeight) do not match canvas (${expectedWidth}x$expectedHeight)."
            )
        }
        return alphaIsUsed
    }

    private fun writeUint24LE(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun writeChunkSizeLE(dest: ByteArray, value: Int) {
        dest[4] = (value and 0xFF).toByte()
        dest[5] = ((value ushr 8) and 0xFF).toByte()
        dest[6] = ((value ushr 16) and 0xFF).toByte()
        dest[7] = ((value ushr 24) and 0xFF).toByte()
    }
}

/**
 * Production animated WebP backend powered by Google's official `libwebp` C encoder
 * ([WebPEncoder] via `com.aureusapps.android:webp-android:1.1.2`, MIT / BSD-3-Clause)
 * paired with [FullCanvasWebPContainerMuxer] for strict compatibility-first container output.
 *
 * Key compatibility & safety guarantees:
 * - Every frame is encoded independently at full canvas dimensions (`0, 0, canvasW, canvasH`)
 *   via `libwebp`'s `WebPEncode`, avoiding partial delta sub-rectangles and synthetic alpha
 *   masks on opaque frames that break non-`libwebpdemux` parsers and upload transcoders.
 * - Serializes native `libwebp` JNI calls via a process-wide [Mutex] because the upstream JNI
 *   bridge stores progress callback state in static JNI globals.
 * - Uses cooperative progress listeners returning `false` for cancellation rather than sticky
 *   native cancellation flags.
 * - Spools encoded frame payloads to app-private cache storage so RAM usage stays O(1)
 *   regardless of animation length, and cleans up all temporary files in `finally`.
 * - Enables `lowMemory = true` and lossless alpha encoding so RGBA transparency and RAM
 *   bounds are respected on Android devices.
 */
class LibWebPAnimEncoderBackend(
    private val context: Context
) : AnimatedWebPBackend {

    override suspend fun <T> withSession(
        config: AnimatedWebPSessionConfig,
        block: suspend (AnimatedWebPSession) -> T
    ): T = nativeMutex.withLock {
        val appContext = context.applicationContext
        val webpConfig = buildWebPConfig(config)
        val sessionDir = File(appContext.cacheDir, "webp_compat_${UUID.randomUUID()}").apply { mkdirs() }
        val spoolFile = File(sessionDir, "frames.spool")
        val spoolOut = BufferedOutputStream(FileOutputStream(spoolFile), 16 * 1024)
        val entries = ArrayList<FullCanvasWebPContainerMuxer.SpoolFrameEntry>()
        var spoolClosed = false
        var frameCounter = 0

        val session = object : AnimatedWebPSession {
            override fun addFrame(
                timestampMs: Long,
                bitmap: Bitmap,
                shouldContinue: () -> Boolean
            ) {
                if (!shouldContinue()) {
                    throw CancellationException("Animated WebP encoding cancelled.")
                }

                val rgbaBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IOException("Unable to convert frame to ARGB_8888 for WebP encoding.")
                }

                val frameFile = File(sessionDir, "frame_${frameCounter++}.webp")
                if (frameFile.exists()) frameFile.delete()

                val continueFlag = AtomicBoolean(true)
                val encoder = WebPEncoder(
                    context = appContext,
                    width = config.width,
                    height = config.height
                )
                try {
                    encoder.addProgressListener { _ ->
                        val ok = shouldContinue()
                        if (!ok) continueFlag.set(false)
                        ok
                    }
                    encoder.configure(
                        config = webpConfig,
                        preset = WebPPreset.WEBP_PRESET_DEFAULT
                    )
                    encoder.encode(rgbaBitmap, Uri.fromFile(frameFile))

                    if (!continueFlag.get() || !shouldContinue()) {
                        throw CancellationException("Animated WebP encoding cancelled.")
                    }

                    val rawSingleFrame = frameFile.readBytes()
                    val extracted = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
                        singleFrameWebP = rawSingleFrame,
                        expectedWidth = config.width,
                        expectedHeight = config.height
                    )
                    spoolOut.write(extracted.subChunksBytes)
                    entries.add(
                        FullCanvasWebPContainerMuxer.SpoolFrameEntry(
                            timestampMs = timestampMs,
                            payloadLength = extracted.subChunksBytes.size,
                            hasAlpha = extracted.hasAlpha
                        )
                    )
                } finally {
                    runCatching { encoder.release() }
                    if (frameFile.exists()) {
                        runCatching { frameFile.delete() }
                    }
                    if (rgbaBitmap !== bitmap && !rgbaBitmap.isRecycled) {
                        rgbaBitmap.recycle()
                    }
                }
            }

            override fun assemble(endTimestampMs: Long, outputFile: File) {
                if (!spoolClosed) {
                    spoolOut.flush()
                    spoolOut.close()
                    spoolClosed = true
                }
                FullCanvasWebPContainerMuxer.assembleFromSpool(
                    width = config.width,
                    height = config.height,
                    loopCount = config.loopCount.coerceIn(0, 65535),
                    backgroundColor = config.backgroundColor,
                    frames = entries,
                    endTimestampMs = endTimestampMs,
                    spoolFile = spoolFile,
                    outputFile = outputFile
                )
            }

            override fun close() {
                if (!spoolClosed) {
                    runCatching { spoolOut.close() }
                    spoolClosed = true
                }
                runCatching { sessionDir.deleteRecursively() }
            }
        }

        try {
            block(session)
        } finally {
            session.close()
        }
    }

    companion object {
        private val nativeMutex = Mutex()

        /**
         * Compatibility-first `WebPAnimEncoderOptions` (disables delta minimization and forces
         * every frame to be an independent keyframe with `kmin = 0, kmax = 1, allowMixed = false`).
         */
        internal fun buildEncoderOptions(config: AnimatedWebPSessionConfig): WebPAnimEncoderOptions {
            return WebPAnimEncoderOptions(
                minimizeSize = false,
                kmin = 0,
                kmax = 1,
                allowMixed = false,
                animParams = WebPMuxAnimParams(
                    backgroundColor = config.backgroundColor,
                    loopCount = config.loopCount.coerceIn(0, 65535)
                )
            )
        }

        internal fun buildWebPConfig(config: AnimatedWebPSessionConfig): WebPConfig {
            val safeQuality = config.quality.coerceIn(0, 100)
            return WebPConfig(
                lossless = if (config.lossless) {
                    WebPConfig.COMPRESSION_LOSSLESS
                } else {
                    WebPConfig.COMPRESSION_LOSSY
                },
                quality = safeQuality.toFloat(),
                method = 4,
                alphaCompression = WebPConfig.ALPHA_COMPRESSION_WITH_LOSSLESS,
                alphaFiltering = WebPConfig.ALPHA_FILTERING_FAST,
                alphaQuality = if (config.lossless) 100 else safeQuality.coerceIn(60, 100),
                lowMemory = true,
                exact = config.lossless
            )
        }
    }
}

/**
 * Parsed container metadata from a binary RIFF/WEBP animated file.
 */
data class AnimatedWebPFrameInfo(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val blendEnabled: Boolean,
    val disposeToBackground: Boolean,
    val hasAlphaChunk: Boolean,
    val payloadChunkType: String
)

data class AnimatedWebPInfo(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val hasAnimationFlag: Boolean,
    val hasAlphaFlag: Boolean,
    val backgroundColor: Int,
    val loopCount: Int,
    val frames: List<AnimatedWebPFrameInfo>,
    val riffSizeValid: Boolean = true,
    val reservedBitsZero: Boolean = true,
    val paddingValid: Boolean = true,
    val hasUnknownOrMetadataChunks: Boolean = false,
    val framePayloadsValid: Boolean = true
) {
    val totalDurationMs: Long get() = frames.sumOf { it.durationMs.toLong() }
    val hasMultipleFrames: Boolean get() = frames.size > 1
    val allFramesFullCanvas: Boolean
        get() = frames.isNotEmpty() && frames.all {
            it.x == 0 && it.y == 0 && it.width == canvasWidth && it.height == canvasHeight
        }
    val hasZeroDurationFrame: Boolean
        get() = frames.any { it.durationMs <= 0 }
    val isValidAnimatedWebP: Boolean
        get() = hasAnimationFlag &&
            canvasWidth > 0 &&
            canvasHeight > 0 &&
            frames.isNotEmpty() &&
            totalDurationMs > 0L
    val isCompatibilityFirstContainer: Boolean
        get() = isValidAnimatedWebP &&
            riffSizeValid &&
            reservedBitsZero &&
            paddingValid &&
            allFramesFullCanvas &&
            !hasZeroDurationFrame &&
            !hasUnknownOrMetadataChunks &&
            framePayloadsValid
}

/**
 * Spec-compliant RIFF/WEBP binary container parser & validator (VP8X + ANIM + ANMF chunks).
 * Verifies that exported `.webp` files are genuine Animated WebP files with valid RIFF sizing,
 * zero reserved bits, valid even-byte padding, accurate VP8X/ANIM flags, full-canvas ANMF frames,
 * non-zero frame durations, and valid VP8/VP8L/ALPH frame payloads.
 */
object AnimatedWebPInspector {

    fun inspect(file: File): AnimatedWebPInfo {
        if (!file.exists() || file.length() < 20L) {
            throw IOException("Animated WebP file is missing or truncated (${file.length()} bytes).")
        }
        return inspect(file.readBytes())
    }

    fun inspect(bytes: ByteArray): AnimatedWebPInfo {
        if (bytes.size < 20) {
            throw IOException("Animated WebP data too short (${bytes.size} bytes).")
        }
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val webp = String(bytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || webp != "WEBP") {
            throw IOException("Invalid WebP header: expected RIFF/WEBP, got $riff/$webp.")
        }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val declaredRiffSize = bb.getInt(4).toLong() and 0xFFFFFFFFL
        var riffSizeValid = declaredRiffSize == (bytes.size - 8).toLong()
        var reservedBitsZero = true
        var paddingValid = true
        var hasUnknownOrMetadataChunks = false
        var framePayloadsValid = true

        var offset = 12
        val riffEnd = minOf(bytes.size.toLong(), 8L + declaredRiffSize).toInt()
        var canvasW = 0
        var canvasH = 0
        var hasAnim = false
        var hasAlpha = false
        var bgColor = 0
        var loopCount = 0
        val frames = ArrayList<AnimatedWebPFrameInfo>()

        while (offset + 8 <= riffEnd) {
            val fourCc = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(offset + 4)
            val dataStart = offset + 8
            val dataEnd = dataStart + chunkSize
            val pad = chunkSize and 1
            if (chunkSize < 0 || dataEnd > riffEnd) {
                throw IOException("Truncated WebP chunk '$fourCc' at offset $offset.")
            }
            if (pad == 1 && dataEnd < bytes.size && bytes[dataEnd].toInt() != 0) {
                paddingValid = false
            }

            when (fourCc) {
                "VP8X" -> {
                    if (chunkSize != 10) throw IOException("Invalid VP8X chunk size: $chunkSize")
                    val flags = bytes[dataStart].toInt() and 0xFF
                    if ((flags and 0xC1) != 0 ||
                        bytes[dataStart + 1].toInt() != 0 ||
                        bytes[dataStart + 2].toInt() != 0 ||
                        bytes[dataStart + 3].toInt() != 0
                    ) {
                        reservedBitsZero = false
                    }
                    hasAnim = (flags and 0x02) != 0
                    hasAlpha = (flags and 0x10) != 0
                    canvasW = readUint24LE(bytes, dataStart + 4) + 1
                    canvasH = readUint24LE(bytes, dataStart + 7) + 1
                }

                "ANIM" -> {
                    if (chunkSize != 6) throw IOException("Invalid ANIM chunk size: $chunkSize")
                    bgColor = bb.getInt(dataStart)
                    loopCount = bb.getShort(dataStart + 4).toInt() and 0xFFFF
                }

                "ANMF" -> {
                    if (chunkSize < 16) throw IOException("Invalid ANMF chunk size: $chunkSize")
                    val fx = readUint24LE(bytes, dataStart) * 2
                    val fy = readUint24LE(bytes, dataStart + 3) * 2
                    val fw = readUint24LE(bytes, dataStart + 6) + 1
                    val fh = readUint24LE(bytes, dataStart + 9) + 1
                    val durationMs = readUint24LE(bytes, dataStart + 12)
                    val flags = bytes[dataStart + 15].toInt() and 0xFF
                    if ((flags and 0xFC) != 0) {
                        reservedBitsZero = false
                    }
                    val disposeBg = (flags and 0x01) != 0
                    val blendDisabled = (flags and 0x02) != 0
                    if (canvasW > 0 && canvasH > 0 && (fx + fw > canvasW || fy + fh > canvasH)) {
                        framePayloadsValid = false
                    }

                    // Inspect nested sub-chunks inside ANMF (ALPH, VP8 , VP8L)
                    var subOffset = dataStart + 16
                    val subEnd = dataEnd
                    var foundAlpha = false
                    var payloadType = ""
                    var imageSubChunkCount = 0
                    while (subOffset + 8 <= subEnd) {
                        val subFourCc = String(bytes, subOffset, 4, Charsets.US_ASCII)
                        val subSize = bb.getInt(subOffset + 4)
                        val subDataStart = subOffset + 8
                        val subDataEnd = subDataStart + subSize
                        val subPad = subSize and 1
                        if (subSize < 0 || subDataEnd > subEnd) {
                            framePayloadsValid = false
                            break
                        }
                        if (subPad == 1 && subDataEnd < subEnd && bytes[subDataEnd].toInt() != 0) {
                            paddingValid = false
                        }
                        when (subFourCc) {
                            "ALPH" -> {
                                if (imageSubChunkCount > 0 || subSize < 1) {
                                    framePayloadsValid = false
                                }
                                foundAlpha = true
                            }

                            "VP8 " -> {
                                payloadType = "VP8"
                                imageSubChunkCount++
                                if (!isValidVp8SubChunk(bytes, subDataStart, subSize, fw, fh)) {
                                    framePayloadsValid = false
                                }
                            }

                            "VP8L" -> {
                                payloadType = "VP8L"
                                imageSubChunkCount++
                                if (foundAlpha || !isValidVp8lSubChunk(bytes, subDataStart, subSize, fw, fh)) {
                                    framePayloadsValid = false
                                }
                            }

                            else -> {
                                hasUnknownOrMetadataChunks = true
                            }
                        }
                        subOffset = subDataEnd + subPad
                    }
                    if (subOffset != subEnd || imageSubChunkCount != 1) {
                        framePayloadsValid = false
                    }
                    frames.add(
                        AnimatedWebPFrameInfo(
                            x = fx,
                            y = fy,
                            width = fw,
                            height = fh,
                            durationMs = durationMs,
                            blendEnabled = !blendDisabled,
                            disposeToBackground = disposeBg,
                            hasAlphaChunk = foundAlpha,
                            payloadChunkType = payloadType
                        )
                    )
                }

                else -> {
                    hasUnknownOrMetadataChunks = true
                }
            }

            offset = dataEnd + pad
        }

        if (offset != bytes.size) {
            riffSizeValid = false
        }

        return AnimatedWebPInfo(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            hasAnimationFlag = hasAnim,
            hasAlphaFlag = hasAlpha,
            backgroundColor = bgColor,
            loopCount = loopCount,
            frames = frames,
            riffSizeValid = riffSizeValid,
            reservedBitsZero = reservedBitsZero,
            paddingValid = paddingValid,
            hasUnknownOrMetadataChunks = hasUnknownOrMetadataChunks,
            framePayloadsValid = framePayloadsValid
        )
    }

    private fun isValidVp8SubChunk(
        bytes: ByteArray,
        dataStart: Int,
        size: Int,
        expectedW: Int,
        expectedH: Int
    ): Boolean {
        if (size < 10) return false
        val isKeyframe = ((bytes[dataStart].toInt() and 0xFF) and 0x01) == 0
        val startCodeOk = (bytes[dataStart + 3].toInt() and 0xFF) == 0x9D &&
            (bytes[dataStart + 4].toInt() and 0xFF) == 0x01 &&
            (bytes[dataStart + 5].toInt() and 0xFF) == 0x2A
        if (!isKeyframe || !startCodeOk) return false
        val vw = ((bytes[dataStart + 6].toInt() and 0xFF) or
            ((bytes[dataStart + 7].toInt() and 0xFF) shl 8)) and 0x3FFF
        val vh = ((bytes[dataStart + 8].toInt() and 0xFF) or
            ((bytes[dataStart + 9].toInt() and 0xFF) shl 8)) and 0x3FFF
        return vw == expectedW && vh == expectedH
    }

    private fun isValidVp8lSubChunk(
        bytes: ByteArray,
        dataStart: Int,
        size: Int,
        expectedW: Int,
        expectedH: Int
    ): Boolean {
        if (size < 5) return false
        if ((bytes[dataStart].toInt() and 0xFF) != 0x2F) return false
        val bits = (bytes[dataStart + 1].toInt() and 0xFF) or
            ((bytes[dataStart + 2].toInt() and 0xFF) shl 8) or
            ((bytes[dataStart + 3].toInt() and 0xFF) shl 16) or
            ((bytes[dataStart + 4].toInt() and 0xFF) shl 24)
        val vw = (bits and 0x3FFF) + 1
        val vh = ((bits ushr 14) and 0x3FFF) + 1
        return vw == expectedW && vh == expectedH
    }

    private fun readUint24LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }
}

/**
 * Genuine streaming Animated WebP encoder supporting both Video-to-WebP and GIF-to-WebP.
 *
 * - Processes frames sequentially and recycles every temporary bitmap immediately.
 * - Preserves individual frame durations, loop counts, portrait/landscape orientation,
 *   crop/rotation/trim/speed/reverse/ping-pong, and RGBA alpha transparency.
 * - Produces conservative, compatibility-first full-canvas Animated WebP containers via
 *   Google's `libwebp` ([LibWebPAnimEncoderBackend]) + [FullCanvasWebPContainerMuxer].
 */
object WebPConversionEngine {

    private const val MIN_REQUIRED_FREE_BYTES = 16L * 1024L * 1024L
    private const val WEBP_MAX_DIMENSION = 16383

    /**
     * Optional override hook used exclusively by JVM unit tests where Android JNI `.so` binaries
     * cannot be loaded on the host desktop JVM. Always `null` in production builds.
     */
    @Volatile
    internal var testBackendOverride: AnimatedWebPBackend? = null

    suspend fun encodeToFile(
        context: Context,
        frameSource: VideoFrameSource,
        settings: GifConversionSettings,
        outputFile: File,
        backend: AnimatedWebPBackend? = null,
        onProgress: (ConversionProgress) -> Unit = {}
    ): EncodedGifStats = withContext(Dispatchers.Default) {
        val cleanSettings = settings.sanitized()
        val metadata = frameSource.metadata

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
                stage = "Preparing $mediaLabel frames for Animated WebP…",
                isIndeterminate = true
            )
        )
        coroutineContext.ensureActive()

        val outputDims = GifGeometryCalculator.calculateOutputDimensions(
            orientedSourceWidth = metadata.orientedWidth,
            orientedSourceHeight = metadata.orientedHeight,
            settings = cleanSettings
        )

        if (outputDims.width > WEBP_MAX_DIMENSION || outputDims.height > WEBP_MAX_DIMENSION) {
            throw IllegalStateException(
                "Requested resolution (${outputDims.width} × ${outputDims.height} px) exceeds WebP specification maximum of $WEBP_MAX_DIMENSION px."
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
        val decodeHintMaxEdge = GifConversionEngine.computeDecodeMaxEdge(metadata, cleanSettings, outputDims)
        val resolvedBackend = backend ?: testBackendOverride ?: LibWebPAnimEncoderBackend(context)
        val sessionConfig = AnimatedWebPSessionConfig(
            width = outputDims.width,
            height = outputDims.height,
            loopCount = cleanSettings.loopCount.coerceIn(0, 65535),
            lossless = cleanSettings.isWebpLossless,
            quality = cleanSettings.resolvedWebpQuality,
            minimizeSize = false
        )

        var detectedAlpha = metadata.hasTransparency
        var cumulativeTimestampMs = 0L
        var lastValidFrameBitmap: Bitmap? = null

        try {
            resolvedBackend.withSession(sessionConfig) { session ->
                for ((index, item) in schedule.frames.withIndex()) {
                    coroutineContext.ensureActive()

                    val rawBitmap = frameSource.getFrameAtTimeUs(item.sourceTimeUs, decodeHintMaxEdge)
                    val frameBitmap: Bitmap = if (rawBitmap != null) {
                        val transformed = try {
                            FrameTransformer.transformFrame(
                                source = rawBitmap,
                                extraRotationDegrees = cleanSettings.rotationDegrees,
                                cropRect = cleanSettings.cropRect,
                                targetWidth = outputDims.width,
                                targetHeight = outputDims.height
                            )
                        } finally {
                            if (!rawBitmap.isRecycled) rawBitmap.recycle()
                        }
                        if (!detectedAlpha && hasTransparentPixels(transformed)) {
                            detectedAlpha = true
                        }
                        // Keep a single fallback copy only when needed, recycling previous fallback
                        lastValidFrameBitmap?.let { if (!it.isRecycled) it.recycle() }
                        lastValidFrameBitmap = transformed.copy(Bitmap.Config.ARGB_8888, false)
                        transformed
                    } else {
                        lastValidFrameBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            ?: Bitmap.createBitmap(
                                outputDims.width,
                                outputDims.height,
                                Bitmap.Config.ARGB_8888
                            )
                    }

                    try {
                        session.addFrame(
                            timestampMs = cumulativeTimestampMs,
                            bitmap = frameBitmap,
                            shouldContinue = {
                                try {
                                    coroutineContext.ensureActive()
                                    true
                                } catch (_: CancellationException) {
                                    false
                                }
                            }
                        )
                    } finally {
                        if (!frameBitmap.isRecycled) frameBitmap.recycle()
                    }

                    cumulativeTimestampMs += item.delayMs.toLong().coerceAtLeast(10L)

                    val fraction = 0.05f + 0.85f * ((index + 1).toFloat() / totalFrames.toFloat())
                    onProgress(
                        ConversionProgress(
                            percent = fraction.coerceIn(0.05f, 0.90f),
                            currentFrame = index + 1,
                            totalFrames = totalFrames,
                            stage = "Encoding WebP frame ${index + 1} of $totalFrames",
                            isIndeterminate = false
                        )
                    )
                }

                coroutineContext.ensureActive()
                onProgress(
                    ConversionProgress(
                        percent = 0.92f,
                        currentFrame = totalFrames,
                        totalFrames = totalFrames,
                        stage = "Assembling Animated WebP…",
                        isIndeterminate = false
                    )
                )
                session.assemble(
                    endTimestampMs = cumulativeTimestampMs.coerceAtLeast(20L),
                    outputFile = outputFile
                )
            }
        } catch (t: Throwable) {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw t
        } finally {
            lastValidFrameBitmap?.let { if (!it.isRecycled) it.recycle() }
        }

        coroutineContext.ensureActive()
        onProgress(
            ConversionProgress(
                percent = 0.96f,
                currentFrame = totalFrames,
                totalFrames = totalFrames,
                stage = "Verifying Animated WebP output…",
                isIndeterminate = false
            )
        )

        val info = AnimatedWebPInspector.inspect(outputFile)
        if (!info.isCompatibilityFirstContainer) {
            throw IOException(
                "Animated WebP verification failed: container failed compatibility checks " +
                    "(valid=${info.isValidAnimatedWebP}, riff=${info.riffSizeValid}, " +
                    "reserved=${info.reservedBitsZero}, pad=${info.paddingValid}, " +
                    "fullCanvas=${info.allFramesFullCanvas}, zeroDur=${info.hasZeroDurationFrame}, " +
                    "extraChunks=${info.hasUnknownOrMetadataChunks}, payloads=${info.framePayloadsValid})."
            )
        }
        if (totalFrames > 1 && !info.hasMultipleFrames) {
            throw IOException(
                "Animated WebP verification failed: expected multiple animated frames from $totalFrames input frames, " +
                    "but container only has ${info.frames.size} frame."
            )
        }

        EncodedGifStats(
            width = outputDims.width,
            height = outputDims.height,
            fps = schedule.effectiveFps,
            durationMs = schedule.totalDurationMs,
            frameCount = info.frames.size.coerceAtLeast(1),
            fileSizeBytes = outputFile.length(),
            effectiveColorCount = 0,
            ditherMode = DitherMode.NONE,
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = cleanSettings.webpQualityPreset,
            webpQuality = cleanSettings.resolvedWebpQuality,
            hasTransparency = detectedAlpha
        )
    }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (bitmap.config != Bitmap.Config.ARGB_8888 && !bitmap.hasAlpha()) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (((row[x] ushr 24) and 0xFF) < 255) return true
            }
        }
        return false
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
        }
    }
}

/**
 * Independent Animated WebP output file-size estimator using genuine representative frame
 * sampling and trial Animated WebP encoding via [AnimatedWebPBackend].
 *
 * Never reuses GIF palette/dithering size estimates.
 */
object WebPSizeEstimator {

    private const val MAX_ANALYSIS_EDGE = 360
    private const val ANCHOR_SAMPLES = 4

    suspend fun estimate(
        context: Context,
        frameSource: VideoFrameSource,
        settings: GifConversionSettings,
        backend: AnimatedWebPBackend? = null
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

        val totalFrames = schedule.frames.size.coerceAtLeast(1)
        val maxOut = max(outputDims.width, outputDims.height)
        val analysisScale = if (maxOut > MAX_ANALYSIS_EDGE) {
            MAX_ANALYSIS_EDGE.toDouble() / maxOut.toDouble()
        } else {
            1.0
        }
        val analysisW = max(2, (outputDims.width * analysisScale).roundToInt())
        val analysisH = max(2, (outputDims.height * analysisScale).roundToInt())

        val decodeEdge = GifConversionEngine.computeDecodeMaxEdge(
            metadata = metadata,
            settings = clean,
            outputDims = OutputDimensions(analysisW, analysisH)
        )

        // Pick representative frames (exact full clip for <= 5 frames, else 4 anchors + 1 adjacent pair)
        val decodeFullDirectly = totalFrames <= 5
        val sampleIndices: List<Int> = if (decodeFullDirectly) {
            (0 until totalFrames).toList()
        } else {
            val anchors = ArrayList<Int>(ANCHOR_SAMPLES)
            for (i in 0 until ANCHOR_SAMPLES) {
                val idx = ((i.toDouble() / (ANCHOR_SAMPLES - 1).toDouble()) * (totalFrames - 1))
                    .roundToInt()
                    .coerceIn(0, totalFrames - 1)
                if (idx !in anchors) anchors.add(idx)
            }
            val midIdx = (totalFrames / 2).coerceIn(0, totalFrames - 2)
            val ordered = LinkedHashSet<Int>()
            ordered.addAll(anchors)
            ordered.add(midIdx)
            ordered.add(midIdx + 1)
            ordered.toList().sorted()
        }

        val resolvedBackend = backend ?: WebPConversionEngine.testBackendOverride ?: LibWebPAnimEncoderBackend(context)
        val sessionConfig = AnimatedWebPSessionConfig(
            width = analysisW,
            height = analysisH,
            loopCount = clean.loopCount.coerceIn(0, 65535),
            lossless = clean.isWebpLossless,
            quality = clean.resolvedWebpQuality,
            minimizeSize = false
        )

        val tempDir = File(context.cacheDir, "webp_estimates").apply { mkdirs() }
        val trialFile = File(tempDir, "est_${UUID.randomUUID()}.webp")

        val decodedFrames = ArrayList<Pair<Bitmap, Int>>(sampleIndices.size)
        try {
            for (idx in sampleIndices) {
                coroutineContext.ensureActive()
                val item = schedule.frames[idx]
                val raw = frameSource.getFrameAtTimeUs(item.sourceTimeUs, decodeEdge) ?: continue
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
                decodedFrames.add(transformed to item.delayMs)
            }

            if (decodedFrames.isEmpty()) {
                return@withContext SizeEstimate(
                    estimatedBytes = 0L,
                    totalFrames = totalFrames,
                    outputWidth = outputDims.width,
                    outputHeight = outputDims.height,
                    estimatedDurationMs = schedule.totalDurationMs,
                    resolvedColorCount = 0,
                    effectiveFps = schedule.effectiveFps,
                    outputFormat = OutputFormat.WEBP,
                    webpQualityPreset = clean.webpQualityPreset,
                    webpQuality = clean.resolvedWebpQuality
                )
            }

            resolvedBackend.withSession(sessionConfig) { session ->
                var ts = 0L
                for ((bmp, delayMs) in decodedFrames) {
                    coroutineContext.ensureActive()
                    session.addFrame(ts, bmp)
                    ts += delayMs.toLong().coerceAtLeast(10L)
                }
                session.assemble(ts.coerceAtLeast(20L), trialFile)
            }

            val areaRatio = (outputDims.width.toDouble() * outputDims.height.toDouble()) /
                (analysisW.toDouble() * analysisH.toDouble())
            // WebP VP8/VP8L compression scales slightly sub-linearly with pixel area
            val areaScale = areaRatio.pow(if (clean.isWebpLossless) 0.90 else 0.84)

            val containerHeaderBytes = 44L // RIFF (12) + VP8X (18) + ANIM (14)
            val trialSizeBytes = trialFile.length().coerceAtLeast(containerHeaderBytes + 64L)
            val trialPayloadBytes = (trialSizeBytes - containerHeaderBytes).coerceAtLeast(64L)

            val estimatedBytes = if (decodeFullDirectly && decodedFrames.size == totalFrames) {
                containerHeaderBytes + (trialPayloadBytes.toDouble() * areaScale).roundToLong()
            } else {
                // Compatibility-first profile encodes every frame independently at full canvas
                // without inter-frame delta sub-rectangles, so total payload scales linearly with frame count.
                val avgFrameBytes = trialPayloadBytes.toDouble() / decodedFrames.size.toDouble()
                containerHeaderBytes + (avgFrameBytes * areaScale * totalFrames.toDouble()).roundToLong()
            }

            SizeEstimate(
                estimatedBytes = estimatedBytes.coerceAtLeast(512L),
                totalFrames = totalFrames,
                outputWidth = outputDims.width,
                outputHeight = outputDims.height,
                estimatedDurationMs = schedule.totalDurationMs,
                resolvedColorCount = 0,
                effectiveFps = schedule.effectiveFps,
                outputFormat = OutputFormat.WEBP,
                webpQualityPreset = clean.webpQualityPreset,
                webpQuality = clean.resolvedWebpQuality
            )
        } finally {
            decodedFrames.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
            if (trialFile.exists()) trialFile.delete()
        }
    }
}

/**
 * Format-aware size estimator dispatcher ensuring Animated WebP estimates always use
 * [WebPSizeEstimator] and GIF estimates always use [GifSizeEstimator].
 */
object MediaSizeEstimator {
    suspend fun estimate(
        context: Context?,
        frameSource: VideoFrameSource,
        settings: GifConversionSettings
    ): SizeEstimate {
        return when (settings.outputFormat) {
            OutputFormat.GIF -> GifSizeEstimator.estimate(frameSource, settings)
            OutputFormat.WEBP -> {
                val ctx = context ?: throw IllegalStateException("Android Context required for Animated WebP estimation.")
                WebPSizeEstimator.estimate(ctx, frameSource, settings)
            }
        }
    }
}
