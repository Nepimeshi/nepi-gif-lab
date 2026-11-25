package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

enum class MediaInputType {
    VIDEO,
    GIF
}

data class VideoMetadata(
    val uri: Uri,
    val displayName: String,
    val rawWidth: Int,
    val rawHeight: Int,
    val metadataRotation: Int,
    val durationMs: Long,
    val frameRate: Float,
    val fileSizeBytes: Long,
    val frameTimestampsUs: List<Long> = emptyList(),
    val isVariableFrameRate: Boolean = false,
    val inputType: MediaInputType = MediaInputType.VIDEO,
    val frameDelaysMs: List<Int> = emptyList(),
    val loopCount: Int = 0,
    val hasTransparency: Boolean = false,
    val colorStandard: Int? = null,
    val colorTransfer: Int? = null,
    val colorRange: Int? = null,
    val videoCodecMime: String? = null
) {
    /** Width after applying the container's native rotation metadata (portrait vs landscape). */
    val orientedWidth: Int
        get() = if (metadataRotation == 90 || metadataRotation == 270) rawHeight else rawWidth

    /** Height after applying the container's native rotation metadata (portrait vs landscape). */
    val orientedHeight: Int
        get() = if (metadataRotation == 90 || metadataRotation == 270) rawWidth else rawHeight

    val isPortrait: Boolean get() = orientedHeight > orientedWidth

    val isGifInput: Boolean get() = inputType == MediaInputType.GIF

    /** True when the video track signals HDR (PQ/HLG) or BT.2020 wide color gamut metadata. */
    val isHdrOrWideGamut: Boolean
        get() = colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
            colorStandard == MediaFormat.COLOR_STANDARD_BT2020
}

/**
 * Abstraction for streaming video or animated GIF frame extraction.
 * Allows real Android [MediaMetadataRetriever] video decoding, [AnimatedGifFrameSource] GIF
 * decoding in production, and deterministic synthetic frame sources in Robolectric/JVM tests.
 */
interface VideoFrameSource : Closeable {
    val metadata: VideoMetadata

    /**
     * Extracts a single frame at [timeUs] already oriented according to container metadata.
     * Caller owns the returned [Bitmap] and should recycle it promptly to minimize peak memory.
     */
    fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int? = null): Bitmap?
}

/**
 * Unified entry point that inspects a [Uri] and opens either [AnimatedGifFrameSource] for GIFs
 * or [AndroidVideoFrameSource] for video files.
 */
object MediaFrameSourceOpener {
    fun open(context: Context, uri: Uri): VideoFrameSource {
        return if (AnimatedGifFrameSource.isGifUri(context, uri)) {
            AnimatedGifFrameSource.open(context, uri)
        } else {
            AndroidVideoFrameSource.open(context, uri)
        }
    }
}

class AndroidVideoFrameSource private constructor(
    override val metadata: VideoMetadata,
    private val retriever: MediaMetadataRetriever
) : VideoFrameSource {

    @Synchronized
    override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap? {
        val clampedUs = timeUs.coerceIn(0L, (metadata.durationMs * 1000L).coerceAtLeast(0L))
        return if (maxEdgePx != null && maxEdgePx > 0) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // Explicitly request ARGB_8888 so codecs never silently downgrade smooth gradients
                    // to RGB_565 (5-6-5 bits) and Android 13+ tone-maps HDR frames cleanly to SDR.
                    val params = MediaMetadataRetriever.BitmapParams().apply {
                        preferredConfig = Bitmap.Config.ARGB_8888
                    }
                    retriever.getScaledFrameAtTime(
                        clampedUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        maxEdgePx,
                        maxEdgePx,
                        params
                    ) ?: retriever.getScaledFrameAtTime(
                        clampedUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxEdgePx,
                        maxEdgePx,
                        params
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> {
                    retriever.getScaledFrameAtTime(
                        clampedUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        maxEdgePx,
                        maxEdgePx
                    ) ?: retriever.getScaledFrameAtTime(
                        clampedUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        maxEdgePx,
                        maxEdgePx
                    )
                }
                else -> {
                    retriever.getFrameAtTime(clampedUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(clampedUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val params = MediaMetadataRetriever.BitmapParams().apply {
                    preferredConfig = Bitmap.Config.ARGB_8888
                }
                retriever.getFrameAtTime(clampedUs, MediaMetadataRetriever.OPTION_CLOSEST, params)
                    ?: retriever.getFrameAtTime(clampedUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, params)
            } else {
                retriever.getFrameAtTime(clampedUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(clampedUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }
    }

    @Synchronized
    override fun close() {
        try {
            retriever.release()
        } catch (_: Throwable) {
            // Safe cleanup
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): AndroidVideoFrameSource {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
            } catch (t: Throwable) {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
                throw IllegalArgumentException(
                    "Unable to open video. The file may be corrupted or in an unsupported format.",
                    t
                )
            }

            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (rawW <= 0 || rawH <= 0 || durationMs <= 0L) {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
                throw IllegalArgumentException(
                    "Video metadata is missing or invalid (dimensions: ${rawW}x${rawH}, duration: ${durationMs}ms)."
                )
            }

            val timingInfo = extractTrackTimingInfo(context, uri)

            val captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
            val frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            } else null

            val computedFps = when {
                timingInfo.measuredAverageFps != null && timingInfo.measuredAverageFps in 1f..120f ->
                    timingInfo.measuredAverageFps
                timingInfo.trackHeaderFps != null && timingInfo.trackHeaderFps in 1f..120f ->
                    timingInfo.trackHeaderFps
                captureRate != null && captureRate in 1f..120f -> captureRate
                frameCount != null && frameCount > 0L && durationMs > 0L ->
                    ((frameCount * 1000f) / durationMs).coerceIn(1f, 120f)
                else -> 30f
            }

            val (displayName, fileSize) = queryDisplayNameAndSize(context, uri)

            val metadata = VideoMetadata(
                uri = uri,
                displayName = displayName,
                rawWidth = rawW,
                rawHeight = rawH,
                metadataRotation = ((rotation % 360) + 360) % 360,
                durationMs = durationMs,
                frameRate = computedFps,
                fileSizeBytes = fileSize,
                frameTimestampsUs = timingInfo.frameTimestampsUs,
                isVariableFrameRate = timingInfo.isVariableFrameRate,
                colorStandard = timingInfo.colorStandard,
                colorTransfer = timingInfo.colorTransfer,
                colorRange = timingInfo.colorRange,
                videoCodecMime = timingInfo.videoCodecMime
            )
            return AndroidVideoFrameSource(metadata, retriever)
        }

        private data class TrackTimingInfo(
            val trackHeaderFps: Float? = null,
            val measuredAverageFps: Float? = null,
            val frameTimestampsUs: List<Long> = emptyList(),
            val isVariableFrameRate: Boolean = false,
            val colorStandard: Int? = null,
            val colorTransfer: Int? = null,
            val colorRange: Int? = null,
            val videoCodecMime: String? = null
        )

        /**
         * Uses [MediaExtractor] to inspect the container's real video frame presentation timestamps
         * and color-space metadata (without decoding pixel buffers) so both CFR/VFR clips preserve
         * authentic frame timing and wide-gamut/HDR clips are detected cleanly.
         */
        private fun extractTrackTimingInfo(context: Context, uri: Uri): TrackTimingInfo {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, uri, null)
                var videoTrackIdx = -1
                var videoFormat: MediaFormat? = null
                var videoMime: String? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("video/")) {
                        videoTrackIdx = i
                        videoFormat = fmt
                        videoMime = mime
                        break
                    }
                }
                if (videoTrackIdx < 0 || videoFormat == null) {
                    return TrackTimingInfo()
                }

                val colorStandard = if (videoFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    try { videoFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD) } catch (_: Throwable) { null }
                } else null
                val colorTransfer = if (videoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    try { videoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) } catch (_: Throwable) { null }
                } else null
                val colorRange = if (videoFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    try { videoFormat.getInteger(MediaFormat.KEY_COLOR_RANGE) } catch (_: Throwable) { null }
                } else null

                val headerFps = when {
                    videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE) -> {
                        try {
                            videoFormat.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (_: Throwable) {
                            try {
                                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }
                    else -> null
                }?.takeIf { it in 1f..120f }

                extractor.selectTrack(videoTrackIdx)
                val maxSamplesToScan = 4500
                val rawTimestamps = ArrayList<Long>(512)
                while (rawTimestamps.size < maxSamplesToScan) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0L) break
                    rawTimestamps.add(sampleTime)
                    if (!extractor.advance()) break
                }

                if (rawTimestamps.size < 2) {
                    return TrackTimingInfo(
                        trackHeaderFps = headerFps,
                        colorStandard = colorStandard,
                        colorTransfer = colorTransfer,
                        colorRange = colorRange,
                        videoCodecMime = videoMime
                    )
                }

                // B-frames can arrive out of presentation order; sort and deduplicate timestamps
                rawTimestamps.sort()
                val sortedUnique = ArrayList<Long>(rawTimestamps.size)
                var prev = -1L
                for (ts in rawTimestamps) {
                    if (ts != prev) {
                        sortedUnique.add(ts)
                        prev = ts
                    }
                }

                if (sortedUnique.size < 2) {
                    return TrackTimingInfo(
                        trackHeaderFps = headerFps,
                        colorStandard = colorStandard,
                        colorTransfer = colorTransfer,
                        colorRange = colorRange,
                        videoCodecMime = videoMime
                    )
                }

                val spanUs = (sortedUnique.last() - sortedUnique.first()).coerceAtLeast(1L)
                val avgFps = (((sortedUnique.size - 1) * 1_000_000.0) / spanUs.toDouble()).toFloat()
                val avgDeltaUs = spanUs.toDouble() / (sortedUnique.size - 1).toDouble()

                var varyingDeltas = 0
                val totalDeltas = sortedUnique.size - 1
                for (i in 0 until totalDeltas) {
                    val delta = (sortedUnique[i + 1] - sortedUnique[i]).toDouble()
                    if (abs(delta - avgDeltaUs) > avgDeltaUs * 0.15) {
                        varyingDeltas++
                    }
                }
                val isVfr = totalDeltas >= 4 && (varyingDeltas.toDouble() / totalDeltas.toDouble()) > 0.12

                TrackTimingInfo(
                    trackHeaderFps = headerFps,
                    measuredAverageFps = avgFps.takeIf { it in 1f..120f },
                    frameTimestampsUs = sortedUnique,
                    isVariableFrameRate = isVfr,
                    colorStandard = colorStandard,
                    colorTransfer = colorTransfer,
                    colorRange = colorRange,
                    videoCodecMime = videoMime
                )
            } catch (_: Throwable) {
                TrackTimingInfo()
            } finally {
                try {
                    extractor.release()
                } catch (_: Throwable) {
                }
            }
        }

        private fun queryDisplayNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
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
    }
}

/**
 * Applies color-space normalization to sRGB ARGB_8888, user's 90° rotation, normalized crop
 * rectangle, and target resolution scaling to an extracted video or GIF frame while recycling
 * intermediate bitmaps promptly.
 */
object FrameTransformer {

    private val LINEAR_TO_SRGB_LUT: IntArray = IntArray(4097) { i ->
        val lin = i / 4096.0
        val srgb = if (lin <= 0.0031308) {
            lin * 12.92
        } else {
            1.055 * lin.pow(1.0 / 2.4) - 0.055
        }
        (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    /**
     * Ensures [source] is in software [Bitmap.Config.ARGB_8888] and the standard sRGB color space.
     *
     * Wide-gamut (Display-P3, BT.2020) and HDR (HLG, PQ, RGBA_F16, RGBA_1010102) video frames
     * decoded by Android codecs carry non-sRGB [ColorSpace] tags; reading their raw bytes directly
     * via [Bitmap.getPixels] without color-space conversion produces noticeably washed-out or
     * hue-shifted GIFs. Normal sRGB ARGB_8888 bitmaps take a zero-copy fast path.
     */
    fun ensureSrgbArgb8888(source: Bitmap): Bitmap {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        val srcColorSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) source.colorSpace else null
        val isSrgb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            srcColorSpace == null || srcColorSpace.isSrgb
        } else {
            true
        }

        if (!isHardware && source.config == Bitmap.Config.ARGB_8888 && isSrgb) {
            return source
        }

        // For software ARGB_8888 frames in an SDR wide-gamut ColorSpace.Rgb (e.g. Display-P3,
        // BT.2020 SDR, Adobe RGB, DCI-P3), apply exact Bradford-adapted LUT + 3x3 gamut conversion
        // directly so results are consistent across every Android API 26..36 implementation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !isHardware &&
            source.config == Bitmap.Config.ARGB_8888 &&
            srcColorSpace is ColorSpace.Rgb &&
            !isHdrOrExtendedColorSpace(srcColorSpace)
        ) {
            return convertSdrRgbColorSpaceToSrgb(source, srcColorSpace)
        }

        // For RGB_565, RGBA_F16, RGBA_1010102, HARDWARE, or HDR (BT2020_HLG / BT2020_PQ /
        // EXTENDED_SRGB), render into an explicit sRGB ARGB_8888 bitmap via Canvas so Android's
        // native Skia color-management / HDR-to-SDR tone-mapping pipeline runs cleanly without
        // pre-dithering.
        val softwareSource = if (isHardware) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val dst = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap.createBitmap(
                softwareSource.width,
                softwareSource.height,
                Bitmap.Config.ARGB_8888,
                true,
                ColorSpace.get(ColorSpace.Named.SRGB)
            )
        } else {
            Bitmap.createBitmap(softwareSource.width, softwareSource.height, Bitmap.Config.ARGB_8888)
        }
        val paint = Paint().apply {
            isFilterBitmap = false
            isDither = false
        }
        Canvas(dst).drawBitmap(softwareSource, 0f, 0f, paint)
        if (softwareSource !== source && !softwareSource.isRecycled) {
            softwareSource.recycle()
        }
        return dst
    }

    private fun isHdrOrExtendedColorSpace(cs: ColorSpace): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val id = cs.id
        if (id == ColorSpace.Named.EXTENDED_SRGB.ordinal ||
            id == ColorSpace.Named.LINEAR_EXTENDED_SRGB.ordinal
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (id == ColorSpace.Named.BT2020_HLG.ordinal ||
                id == ColorSpace.Named.BT2020_PQ.ordinal
            ) {
                return true
            }
        }
        // Any transfer range extending beyond [0, 1] indicates HDR / extended dynamic range
        return cs.getMaxValue(0) > 1.001f || cs.getMinValue(0) < -0.001f
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun convertSdrRgbColorSpaceToSrgb(source: Bitmap, srcCs: ColorSpace.Rgb): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
        val adaptedSrc = ColorSpace.adapt(srcCs, srgb.whitePoint) as ColorSpace.Rgb
        val mSrcToXyz = adaptedSrc.transform // column-major 3x3
        val mXyzToSrgb = srgb.inverseTransform // column-major 3x3

        // Multiply M = mXyzToSrgb * mSrcToXyz (both column-major)
        val m = FloatArray(9)
        for (col in 0..2) {
            val b0 = mSrcToXyz[col * 3 + 0]
            val b1 = mSrcToXyz[col * 3 + 1]
            val b2 = mSrcToXyz[col * 3 + 2]
            m[col * 3 + 0] = mXyzToSrgb[0] * b0 + mXyzToSrgb[3] * b1 + mXyzToSrgb[6] * b2
            m[col * 3 + 1] = mXyzToSrgb[1] * b0 + mXyzToSrgb[4] * b1 + mXyzToSrgb[7] * b2
            m[col * 3 + 2] = mXyzToSrgb[2] * b0 + mXyzToSrgb[5] * b1 + mXyzToSrgb[8] * b2
        }
        val m00 = m[0]; val m01 = m[3]; val m02 = m[6]
        val m10 = m[1]; val m11 = m[4]; val m12 = m[7]
        val m20 = m[2]; val m21 = m[5]; val m22 = m[8]

        // Precompute 256-entry EOTF LUT for the source color space
        val eotf = srcCs.eotf
        val eotfLut = FloatArray(256) { i ->
            eotf.applyAsDouble(i / 255.0).toFloat()
        }

        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val lut = LINEAR_TO_SRGB_LUT
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p and 0xFF000000.toInt()
            val lr = eotfLut[(p ushr 16) and 0xFF]
            val lg = eotfLut[(p ushr 8) and 0xFF]
            val lb = eotfLut[p and 0xFF]

            val sr = (m00 * lr + m01 * lg + m02 * lb).coerceIn(0f, 1f)
            val sg = (m10 * lr + m11 * lg + m12 * lb).coerceIn(0f, 1f)
            val sb = (m20 * lr + m21 * lg + m22 * lb).coerceIn(0f, 1f)

            val rOut = lut[(sr * 4096f + 0.5f).toInt().coerceIn(0, 4096)]
            val gOut = lut[(sg * 4096f + 0.5f).toInt().coerceIn(0, 4096)]
            val bOut = lut[(sb * 4096f + 0.5f).toInt().coerceIn(0, 4096)]
            pixels[i] = a or (rOut shl 16) or (gOut shl 8) or bOut
        }

        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB))
        dst.setPixels(pixels, 0, w, 0, 0, w, h)
        return dst
    }

    fun transformFrame(
        source: Bitmap,
        extraRotationDegrees: Int,
        cropRect: NormalizedCropRect,
        targetWidth: Int,
        targetHeight: Int,
        highQualityDownscale: Boolean = false
    ): Bitmap {
        var current = ensureSrgbArgb8888(source)
        var ownsCurrent = current !== source

        val normRot = ((extraRotationDegrees % 360) + 360) % 360
        if (normRot != 0) {
            val rotated = rotateRightAngle(current, normRot)
            if (ownsCurrent && current !== rotated) current.recycle()
            current = rotated
            ownsCurrent = true
        }

        val c = cropRect.clamped()
        if (!c.isFullFrame()) {
            val cropX = (c.left * current.width).roundToInt().coerceIn(0, current.width - 1)
            val cropY = (c.top * current.height).roundToInt().coerceIn(0, current.height - 1)
            val cropW = (c.widthFraction * current.width).roundToInt().coerceIn(1, current.width - cropX)
            val cropH = (c.heightFraction * current.height).roundToInt().coerceIn(1, current.height - cropY)
            val cropped = Bitmap.createBitmap(current, cropX, cropY, cropW, cropH)
            if (ownsCurrent && current !== cropped) current.recycle()
            current = cropped
            ownsCurrent = true
        }

        val safeW = targetWidth.coerceAtLeast(2)
        val safeH = targetHeight.coerceAtLeast(2)
        if (current.width != safeW || current.height != safeH) {
            val scaled = try {
                if (highQualityDownscale) {
                    scaleBitmapHighQuality(current, safeW, safeH)
                } else {
                    Bitmap.createScaledBitmap(current, safeW, safeH, true)
                }
            } catch (oom: OutOfMemoryError) {
                throw IllegalStateException(
                    "Insufficient device memory to scale frame to ${safeW} × ${safeH} px. Please choose a lower resolution.",
                    oom
                )
            }
            if (ownsCurrent && current !== scaled) current.recycle()
            return scaled
        }

        return if (ownsCurrent) current else current.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Exact premultiplied-alpha separable area-averaging (box) downscaler / bilinear upscaler.
     *
     * Unlike single-pass `Bitmap.createScaledBitmap` (2x2 bilinear taps that skip pixels when
     * downscaling > 2x), exact area integration averages 100% of every source pixel contributing
     * to each output pixel along X and Y independently. Premultiplied alpha weighting prevents
     * dark halos around transparent edges, preserves 1px thin lines and fine details without
     * aliasing, keeps gradients smooth, and produces identical deterministic output across all
     * Android API levels.
     */
    fun scaleBitmapHighQuality(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val dstW = targetWidth.coerceAtLeast(2)
        val dstH = targetHeight.coerceAtLeast(2)
        val srcW = source.width
        val srcH = source.height
        if (srcW == dstW && srcH == dstH) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        // Pass 1: Horizontal resample (srcW x srcH -> dstW x srcH)
        val horizPixels = if (srcW == dstW) {
            srcPixels
        } else {
            resampleHorizontal(srcPixels, srcW, srcH, dstW)
        }

        // Pass 2: Vertical resample (dstW x srcH -> dstW x dstH)
        val outPixels = if (srcH == dstH) {
            horizPixels
        } else {
            resampleVertical(horizPixels, dstW, srcH, dstH)
        }

        val dst = createSrgbBitmap(dstW, dstH)
        dst.setPixels(outPixels, 0, dstW, 0, 0, dstW, dstH)
        return dst
    }

    private fun resampleHorizontal(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dstW: Int
    ): IntArray {
        val dst = IntArray(dstW * srcH)
        if (dstW < srcW) {
            // Exact area-averaging box filter for downscaling
            val scale = srcW.toDouble() / dstW.toDouble()
            val xStart = IntArray(dstW)
            val xEnd = IntArray(dstW)
            val x0Arr = DoubleArray(dstW)
            val x1Arr = DoubleArray(dstW)
            for (dx in 0 until dstW) {
                val x0 = dx * scale
                val x1 = (dx + 1) * scale
                x0Arr[dx] = x0
                x1Arr[dx] = x1
                xStart[dx] = x0.toInt().coerceIn(0, srcW - 1)
                xEnd[dx] = kotlin.math.ceil(x1).toInt().coerceIn(xStart[dx] + 1, srcW)
            }

            for (y in 0 until srcH) {
                val srcRow = y * srcW
                val dstRow = y * dstW
                for (dx in 0 until dstW) {
                    val x0 = x0Arr[dx]
                    val x1 = x1Arr[dx]
                    val sx0 = xStart[dx]
                    val sx1 = xEnd[dx]
                    var sumW = 0.0
                    var sumA = 0.0
                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    for (sx in sx0 until sx1) {
                        val left = if (sx.toDouble() > x0) sx.toDouble() else x0
                        val right = if ((sx + 1).toDouble() < x1) (sx + 1).toDouble() else x1
                        val w = right - left
                        if (w <= 0.0) continue
                        val p = src[srcRow + sx]
                        val a = ((p ushr 24) and 0xFF).toDouble()
                        sumW += w
                        if (a > 0.0) {
                            val wa = w * a
                            sumA += wa
                            sumR += wa * ((p ushr 16) and 0xFF)
                            sumG += wa * ((p ushr 8) and 0xFF)
                            sumB += wa * (p and 0xFF)
                        }
                    }
                    dst[dstRow + dx] = packPremultiplied(sumW, sumA, sumR, sumG, sumB)
                }
            }
        } else {
            // Premultiplied bilinear interpolation for upscaling
            val scale = (srcW - 1).toDouble() / (dstW - 1).coerceAtLeast(1).toDouble()
            val idx0 = IntArray(dstW)
            val idx1 = IntArray(dstW)
            val frac = DoubleArray(dstW)
            for (dx in 0 until dstW) {
                val fx = dx * scale
                val i0 = fx.toInt().coerceIn(0, srcW - 1)
                val i1 = (i0 + 1).coerceIn(0, srcW - 1)
                idx0[dx] = i0
                idx1[dx] = i1
                frac[dx] = fx - i0
            }
            for (y in 0 until srcH) {
                val srcRow = y * srcW
                val dstRow = y * dstW
                for (dx in 0 until dstW) {
                    val f = frac[dx]
                    val w0 = 1.0 - f
                    val p0 = src[srcRow + idx0[dx]]
                    val p1 = src[srcRow + idx1[dx]]
                    val a0 = ((p0 ushr 24) and 0xFF).toDouble()
                    val a1 = ((p1 ushr 24) and 0xFF).toDouble()
                    val wa0 = w0 * a0
                    val wa1 = f * a1
                    val sumA = wa0 + wa1
                    val sumR = wa0 * ((p0 ushr 16) and 0xFF) + wa1 * ((p1 ushr 16) and 0xFF)
                    val sumG = wa0 * ((p0 ushr 8) and 0xFF) + wa1 * ((p1 ushr 8) and 0xFF)
                    val sumB = wa0 * (p0 and 0xFF) + wa1 * (p1 and 0xFF)
                    dst[dstRow + dx] = packPremultiplied(1.0, sumA, sumR, sumG, sumB)
                }
            }
        }
        return dst
    }

    private fun resampleVertical(
        src: IntArray,
        width: Int,
        srcH: Int,
        dstH: Int
    ): IntArray {
        val dst = IntArray(width * dstH)
        if (dstH < srcH) {
            val scale = srcH.toDouble() / dstH.toDouble()
            for (dy in 0 until dstH) {
                val y0 = dy * scale
                val y1 = (dy + 1) * scale
                val sy0 = y0.toInt().coerceIn(0, srcH - 1)
                val sy1 = kotlin.math.ceil(y1).toInt().coerceIn(sy0 + 1, srcH)
                val dstRow = dy * width
                for (x in 0 until width) {
                    var sumW = 0.0
                    var sumA = 0.0
                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    for (sy in sy0 until sy1) {
                        val top = if (sy.toDouble() > y0) sy.toDouble() else y0
                        val bottom = if ((sy + 1).toDouble() < y1) (sy + 1).toDouble() else y1
                        val w = bottom - top
                        if (w <= 0.0) continue
                        val p = src[sy * width + x]
                        val a = ((p ushr 24) and 0xFF).toDouble()
                        sumW += w
                        if (a > 0.0) {
                            val wa = w * a
                            sumA += wa
                            sumR += wa * ((p ushr 16) and 0xFF)
                            sumG += wa * ((p ushr 8) and 0xFF)
                            sumB += wa * (p and 0xFF)
                        }
                    }
                    dst[dstRow + x] = packPremultiplied(sumW, sumA, sumR, sumG, sumB)
                }
            }
        } else {
            val scale = (srcH - 1).toDouble() / (dstH - 1).coerceAtLeast(1).toDouble()
            for (dy in 0 until dstH) {
                val fy = dy * scale
                val i0 = fy.toInt().coerceIn(0, srcH - 1)
                val i1 = (i0 + 1).coerceIn(0, srcH - 1)
                val f = fy - i0
                val w0 = 1.0 - f
                val row0 = i0 * width
                val row1 = i1 * width
                val dstRow = dy * width
                for (x in 0 until width) {
                    val p0 = src[row0 + x]
                    val p1 = src[row1 + x]
                    val a0 = ((p0 ushr 24) and 0xFF).toDouble()
                    val a1 = ((p1 ushr 24) and 0xFF).toDouble()
                    val wa0 = w0 * a0
                    val wa1 = f * a1
                    val sumA = wa0 + wa1
                    val sumR = wa0 * ((p0 ushr 16) and 0xFF) + wa1 * ((p1 ushr 16) and 0xFF)
                    val sumG = wa0 * ((p0 ushr 8) and 0xFF) + wa1 * ((p1 ushr 8) and 0xFF)
                    val sumB = wa0 * (p0 and 0xFF) + wa1 * (p1 and 0xFF)
                    dst[dstRow + x] = packPremultiplied(1.0, sumA, sumR, sumG, sumB)
                }
            }
        }
        return dst
    }

    private fun packPremultiplied(
        sumW: Double,
        sumA: Double,
        sumR: Double,
        sumG: Double,
        sumB: Double
    ): Int {
        if (sumW <= 0.0 || sumA <= 0.0) return 0
        val outA = (sumA / sumW + 0.5).toInt().coerceIn(0, 255)
        if (outA == 0) return 0
        val invA = 1.0 / sumA
        val outR = (sumR * invA + 0.5).toInt().coerceIn(0, 255)
        val outG = (sumG * invA + 0.5).toInt().coerceIn(0, 255)
        val outB = (sumB * invA + 0.5).toInt().coerceIn(0, 255)
        return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun createSrgbBitmap(width: Int, height: Int): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
                true,
                ColorSpace.get(ColorSpace.Named.SRGB)
            )
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun rotateRightAngle(source: Bitmap, normRot: Int): Bitmap {
        val w = source.width
        val h = source.height
        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        return when (normRot) {
            90 -> {
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    val rowOff = y * w
                    val dstX = h - 1 - y
                    for (x in 0 until w) {
                        out[x * h + dstX] = srcPixels[rowOff + x]
                    }
                }
                Bitmap.createBitmap(out, h, w, Bitmap.Config.ARGB_8888)
            }
            180 -> {
                val out = IntArray(w * h)
                val total = w * h
                for (i in 0 until total) {
                    out[total - 1 - i] = srcPixels[i]
                }
                Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            }
            270 -> {
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    val rowOff = y * w
                    for (x in 0 until w) {
                        val dstY = w - 1 - x
                        out[dstY * h + y] = srcPixels[rowOff + x]
                    }
                }
                Bitmap.createBitmap(out, h, w, Bitmap.Config.ARGB_8888)
            }
            else -> {
                val matrix = Matrix().apply { postRotate(normRot.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, w, h, matrix, true)
            }
        }
    }
}
