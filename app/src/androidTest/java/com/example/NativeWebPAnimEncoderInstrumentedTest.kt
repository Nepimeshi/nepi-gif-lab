package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aureusapps.android.webpandroid.CodecResult
import com.aureusapps.android.webpandroid.decoder.WebPDecoder
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.AnimatedWebPInspector
import com.example.engine.DitherMode
import com.example.engine.FrameQuantizer
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionSettings
import com.example.engine.MediaInputType
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import com.example.engine.WebPConversionEngine
import com.example.engine.WebPQualityPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On-device instrumented compatibility suite verifying the real native Google `libwebp`
 * encoder ([com.example.engine.LibWebPAnimEncoderBackend]) and decoding every emitted frame
 * back through Google's native `libwebpdemux` ([WebPDecoder]).
 *
 * Covers:
 * - Animated GIF input (transparent + variable durations)
 * - Animated WebP round-trip re-encode
 * - Video input with full-frame motion
 * - Transparent moving sprite verifying zero ghosting / transparency trails
 * - Gradient-heavy animation
 * - Small moving object on static opaque background (zero partial delta sub-rects)
 * - Variable frame durations & infinite vs custom loop counts
 * - Lossy (VP8 / ALPH+VP8) and Lossless (VP8L) output modes
 */
@RunWith(AndroidJUnit4::class)
class NativeWebPAnimEncoderInstrumentedTest {

    @Test
    fun nativeLibWebPEncodesMultiFrameLossyAndLosslessAnimatedWebPWithAlpha() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frameSource = object : VideoFrameSource {
            override val metadata = VideoMetadata(
                uri = Uri.parse("content://test/native_alpha.gif"),
                displayName = "native_alpha.gif",
                durationMs = 420L,
                rawWidth = 64,
                rawHeight = 64,
                metadataRotation = 0,
                frameRate = 10f,
                fileSizeBytes = 10_000L,
                inputType = MediaInputType.GIF,
                hasTransparency = true,
                frameTimestampsUs = listOf(0L, 60_000L, 200_000L),
                frameDelaysMs = listOf(60, 140, 220)
            )

            override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val frameIdx = when {
                    timeUs < 60_000L -> 0
                    timeUs < 200_000L -> 1
                    else -> 2
                }
                val fg = when (frameIdx) {
                    0 -> Color.rgb(240, 60, 80)
                    1 -> Color.rgb(60, 220, 120)
                    else -> Color.rgb(70, 130, 240)
                }
                for (y in 0 until 64) {
                    for (x in 0 until 64) {
                        val pixel = if (x < 16 && y < 16) Color.TRANSPARENT else fg
                        bmp.setPixel(x, y, pixel)
                    }
                }
                return bmp
            }

            override fun close() {}
        }

        // 1. Lossy Animated WebP with alpha and custom loop count
        val lossyOut = File(context.cacheDir, "native_lossy_anim.webp").apply { delete() }
        val lossyStats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = frameSource,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = true,
                webpQualityPreset = WebPQualityPreset.BALANCED,
                loopCount = 3
            ),
            outputFile = lossyOut
        )
        val lossyInfo = AnimatedWebPInspector.inspect(lossyOut)
        assertTrue(lossyInfo.isCompatibilityFirstContainer)
        assertTrue(lossyInfo.hasAlphaFlag)
        assertTrue(lossyInfo.allFramesFullCanvas)
        assertEquals(64, lossyInfo.canvasWidth)
        assertEquals(64, lossyInfo.canvasHeight)
        assertEquals(3, lossyInfo.loopCount)
        assertEquals(3, lossyInfo.frames.size)
        assertEquals(listOf(60, 140, 220), lossyInfo.frames.map { it.durationMs })
        assertTrue(lossyInfo.frames.all { !it.blendEnabled && it.disposeToBackground })
        assertTrue(lossyStats.hasTransparency)

        val decodedLossy = decodeAllFramesNatively(context, lossyOut)
        try {
            assertEquals(3, decodedLossy.size)
            assertEquals(0, Color.alpha(decodedLossy[0].getPixel(4, 4)))
            assertTrue(Color.alpha(decodedLossy[0].getPixel(40, 40)) >= 250)
        } finally {
            decodedLossy.forEach { it.recycle() }
        }

        // 2. Lossless Animated WebP
        val losslessOut = File(context.cacheDir, "native_lossless_anim.webp").apply { delete() }
        val losslessStats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = frameSource,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = true,
                webpQualityPreset = WebPQualityPreset.LOSSLESS,
                loopCount = 0
            ),
            outputFile = losslessOut
        )
        val losslessInfo = AnimatedWebPInspector.inspect(losslessOut)
        assertTrue(losslessInfo.isCompatibilityFirstContainer)
        assertTrue(losslessInfo.hasMultipleFrames)
        assertEquals(0, losslessInfo.loopCount)
        assertEquals(3, losslessInfo.frames.size)
        assertTrue(losslessInfo.frames.all { it.payloadChunkType == "VP8L" && !it.hasAlphaChunk })
        assertEquals(WebPQualityPreset.LOSSLESS, losslessStats.webpQualityPreset)
    }

    @Test
    fun transparentMovingSpriteHasFullCanvasFramesAndZeroGhosting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coords = listOf(
            Triple(4, 4, Color.rgb(240, 40, 40)),
            Triple(24, 4, Color.rgb(40, 230, 80)),
            Triple(24, 24, Color.rgb(50, 110, 245)),
            Triple(44, 44, Color.rgb(245, 210, 30))
        )
        val durations = listOf(50, 120, 240, 80)
        val timestamps = listOf(0L, 50_000L, 170_000L, 410_000L)

        val spriteSource = object : VideoFrameSource {
            override val metadata = VideoMetadata(
                uri = Uri.parse("content://test/transparent_sprite.gif"),
                displayName = "transparent_sprite.gif",
                durationMs = 490L,
                rawWidth = 64,
                rawHeight = 64,
                metadataRotation = 0,
                frameRate = 10f,
                fileSizeBytes = 12_000L,
                inputType = MediaInputType.GIF,
                hasTransparency = true,
                frameTimestampsUs = timestamps,
                frameDelaysMs = durations
            )

            override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                val idx = timestamps.indexOfLast { timeUs >= it }.coerceAtLeast(0)
                val (sx, sy, color) = coords[idx]
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.TRANSPARENT)
                for (y in sy until sy + 16) {
                    for (x in sx until sx + 16) {
                        bmp.setPixel(x, y, color)
                    }
                }
                return bmp
            }

            override fun close() {}
        }

        val outFile = File(context.cacheDir, "native_sprite_zero_ghost.webp").apply { delete() }
        WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = spriteSource,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = true,
                webpQualityPreset = WebPQualityPreset.HIGH,
                loopCount = 0
            ),
            outputFile = outFile
        )

        val info = AnimatedWebPInspector.inspect(outFile)
        assertTrue(info.isCompatibilityFirstContainer)
        assertTrue("Every frame of transparent sprite must remain full-canvas", info.allFramesFullCanvas)
        assertTrue(info.hasAlphaFlag)
        assertEquals(durations, info.frames.map { it.durationMs })
        assertTrue(info.frames.all { !it.blendEnabled && it.disposeToBackground })

        // Decode all 4 frames via libwebpdemux and verify ZERO ghosting on previous sprite positions
        val decoded = decodeAllFramesNatively(context, outFile)
        try {
            assertEquals(4, decoded.size)
            for (i in coords.indices) {
                val (sx, sy, _) = coords[i]
                val (prevX, prevY, _) = coords[(i + coords.size - 1) % coords.size]
                val curAlpha = Color.alpha(decoded[i].getPixel(sx + 8, sy + 8))
                val prevAlpha = Color.alpha(decoded[i].getPixel(prevX + 8, prevY + 8))
                assertTrue("Frame $i current sprite must be opaque (alpha=$curAlpha)", curAlpha >= 250)
                assertEquals("Frame $i previous sprite location must be cleared (no ghosting)", 0, prevAlpha)
            }
        } finally {
            decoded.forEach { it.recycle() }
        }
    }

    @Test
    fun opaqueSmallMovingObjectAndGradientAndFullMotionProduceCleanFullCanvasVp8() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gradientAndSmallObject = object : VideoFrameSource {
            override val metadata = VideoMetadata(
                uri = Uri.parse("content://test/opaque_small_obj.mp4"),
                displayName = "opaque_small_obj.mp4",
                durationMs = 500L,
                rawWidth = 96,
                rawHeight = 64,
                metadataRotation = 0,
                frameRate = 10f,
                fileSizeBytes = 40_000L,
                inputType = MediaInputType.VIDEO,
                hasTransparency = false
            )

            override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                val bmp = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                val step = ((timeUs / 100_000L).toInt() * 14) % 72
                for (y in 0 until 64) {
                    for (x in 0 until 96) {
                        val isObj = x in step until (step + 8) && y in 24 until 32
                        val pixel = if (isObj) {
                            Color.rgb(255, 230, 20)
                        } else {
                            Color.rgb((x * 255) / 96, (y * 255) / 64, 150)
                        }
                        bmp.setPixel(x, y, pixel)
                    }
                }
                return bmp
            }

            override fun close() {}
        }

        val outFile = File(context.cacheDir, "native_small_obj_opaque.webp").apply { delete() }
        val stats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = gradientAndSmallObject,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 10f,
                webpQualityPreset = WebPQualityPreset.HIGH,
                loopCount = 0
            ),
            outputFile = outFile
        )

        val info = AnimatedWebPInspector.inspect(outFile)
        assertTrue(info.isCompatibilityFirstContainer)
        assertFalse("Opaque animation must not set VP8X ALPH flag", info.hasAlphaFlag)
        assertFalse(stats.hasTransparency)
        assertTrue("No partial delta rectangles permitted", info.allFramesFullCanvas)
        assertTrue(info.frames.all { !it.hasAlphaChunk && it.payloadChunkType == "VP8" })
        assertTrue(info.frames.all { !it.blendEnabled && !it.disposeToBackground })

        // Verify native libwebpdemux decodes all frames distinctly without collapsing to static
        val decoded = decodeAllFramesNatively(context, outFile)
        try {
            assertEquals(info.frames.size, decoded.size)
            assertTrue(decoded.size >= 4)
            // First frame has yellow dot at x=4,y=28; third frame has moved away
            assertTrue(Color.red(decoded[0].getPixel(4, 28)) > 200)
            assertTrue(Color.red(decoded[2].getPixel(4, 28)) < 80)
        } finally {
            decoded.forEach { it.recycle() }
        }
    }

    @Test
    fun animatedGifAndAnimatedWebPRoundTripPreserveFramesAndTimings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = 48
        val h = 48
        val gifFile = File(context.cacheDir, "native_input.gif").apply { delete() }
        FileOutputStream(gifFile).use { fos ->
            val enc = Gif89aStreamEncoder(fos, w, h, loopCount = 0)
            val colors = listOf(Color.rgb(230, 50, 60), Color.rgb(50, 210, 90), Color.rgb(60, 110, 235))
            val delays = listOf(70, 140, 210)
            for (i in colors.indices) {
                val px = IntArray(w * h) { colors[i] }
                val q = FrameQuantizer.quantizeAndDither(px, w, h, 32, DitherMode.NONE)
                enc.addFrame(q, delayMs = delays[i], disposalMethod = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND)
            }
            enc.finish()
        }

        // 1. GIF -> Animated WebP
        val firstWebp = File(context.cacheDir, "native_from_gif.webp").apply { delete() }
        AnimatedGifFrameSource.openFile(gifFile, displayName = "native_input.gif").use { gifSrc ->
            WebPConversionEngine.encodeToFile(
                context = context,
                frameSource = gifSrc,
                settings = GifConversionSettings(
                    outputFormat = OutputFormat.WEBP,
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    useOriginalFps = true,
                    webpQualityPreset = WebPQualityPreset.BALANCED,
                    loopCount = 2
                ),
                outputFile = firstWebp
            )
        }
        val firstInfo = AnimatedWebPInspector.inspect(firstWebp)
        assertTrue(firstInfo.isCompatibilityFirstContainer)
        assertEquals(listOf(70, 140, 210), firstInfo.frames.map { it.durationMs })
        assertEquals(2, firstInfo.loopCount)

        // 2. Animated WebP -> Animated WebP round-trip using native WebPDecoder frames
        val decodedFirst = decodeAllFramesNatively(context, firstWebp)
        try {
            assertEquals(3, decodedFirst.size)
            val webpSource = object : VideoFrameSource {
                override val metadata = VideoMetadata(
                    uri = Uri.fromFile(firstWebp),
                    displayName = firstWebp.name,
                    durationMs = 420L,
                    rawWidth = w,
                    rawHeight = h,
                    metadataRotation = 0,
                    frameRate = 10f,
                    fileSizeBytes = firstWebp.length(),
                    inputType = MediaInputType.GIF,
                    hasTransparency = false,
                    frameTimestampsUs = listOf(0L, 70_000L, 210_000L),
                    frameDelaysMs = listOf(70, 140, 210)
                )

                override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                    val idx = when {
                        timeUs < 70_000L -> 0
                        timeUs < 210_000L -> 1
                        else -> 2
                    }
                    return decodedFirst[idx].copy(Bitmap.Config.ARGB_8888, false)
                }

                override fun close() {}
            }

            val secondWebp = File(context.cacheDir, "native_roundtrip_webp.webp").apply { delete() }
            WebPConversionEngine.encodeToFile(
                context = context,
                frameSource = webpSource,
                settings = GifConversionSettings(
                    outputFormat = OutputFormat.WEBP,
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    useOriginalFps = true,
                    webpQualityPreset = WebPQualityPreset.HIGH,
                    loopCount = 0
                ),
                outputFile = secondWebp
            )
            val secondInfo = AnimatedWebPInspector.inspect(secondWebp)
            assertTrue(secondInfo.isCompatibilityFirstContainer)
            assertEquals(3, secondInfo.frames.size)
            assertEquals(listOf(70, 140, 210), secondInfo.frames.map { it.durationMs })
            assertEquals(0, secondInfo.loopCount)
        } finally {
            decodedFirst.forEach { it.recycle() }
        }
    }

    private fun decodeAllFramesNatively(context: Context, webpFile: File): List<Bitmap> {
        val decoder = WebPDecoder(context)
        val out = ArrayList<Bitmap>()
        try {
            decoder.setDataSource(Uri.fromFile(webpFile))
            val info = decoder.decodeInfo()
            assertTrue("Native WebPDecoder must recognize animation", info.hasAnimation)
            assertTrue("Native WebPDecoder must report multiple frames", info.frameCount > 1)
            while (decoder.hasNextFrame()) {
                val res = decoder.decodeNextFrame()
                if (res.codecResult == CodecResult.ERROR_NO_MORE_FRAMES) break
                assertEquals(CodecResult.SUCCESS, res.codecResult)
                val bmp = res.frame
                assertNotNull(bmp)
                out.add(bmp!!.copy(Bitmap.Config.ARGB_8888, false))
            }
        } finally {
            decoder.release()
        }
        return out
    }
}
