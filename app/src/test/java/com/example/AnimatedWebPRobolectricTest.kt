package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.AnimatedWebPBackend
import com.example.engine.AnimatedWebPInspector
import com.example.engine.AnimatedWebPSession
import com.example.engine.AnimatedWebPSessionConfig
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.FrameQuantizer
import com.example.engine.FullCanvasWebPContainerMuxer
import com.example.engine.GalleryExporter
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionSettings
import com.example.engine.LibWebPAnimEncoderBackend
import com.example.engine.LiveGifPreviewRenderer
import com.example.engine.MediaInputType
import com.example.engine.MediaSizeEstimator
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.SavedGifResult
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import com.example.engine.WebPConversionEngine
import com.example.engine.WebPQualityPreset
import com.example.engine.WebPSizeEstimator
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.ExportScreen
import com.example.ui.theme.GifStudioTheme
import com.example.viewmodel.EditorTab
import com.example.viewmodel.EditorUiState
import com.example.viewmodel.ExportUiState
import com.example.worker.ConversionNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Comprehensive Robolectric verification for Animated WebP export:
 * - Spec-compliant RIFF/WEBP (VP8X + ANIM + ANMF + ALPH + VP8/VP8L) container output
 * - Multi-frame variable frame duration preservation (GIF-to-WebP & Video-to-WebP)
 * - Portrait & landscape orientation handling
 * - Alpha transparency preservation
 * - Loop metadata (infinite and custom count)
 * - Lossless, High, Balanced, Smaller, and Custom quality modes + native WebPConfig verification
 * - Independent WebP size estimation (distinct from GIF estimation)
 * - Independent WebP live preview rendering
 * - Cancellation & partial temp file cleanup
 * - Gallery MediaStore export (.webp, image/webp), Share/Open intents, and WebP notifications
 * - Compose UI format selector & format-specific controls on EditorScreen and ExportScreen
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class AnimatedWebPRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Application
    private lateinit var fakeBackend: FakeRiffWebPBackend

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeBackend = FakeRiffWebPBackend()
        WebPConversionEngine.testBackendOverride = fakeBackend
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        WebPConversionEngine.testBackendOverride = null
    }

    // -------------------------------------------------------------------------
    // 1. Native LibWebPAnimEncoderBackend config & options verification
    // -------------------------------------------------------------------------

    @Test
    fun `libwebp backend configures compatibility-first options, lossless, lossy, alpha, loopCount and lowMemory correctly`() {
        val losslessCfg = AnimatedWebPSessionConfig(
            width = 360,
            height = 640,
            loopCount = 0,
            lossless = true,
            quality = 100
        )
        val losslessWebP = LibWebPAnimEncoderBackend.buildWebPConfig(losslessCfg)
        val losslessOpts = LibWebPAnimEncoderBackend.buildEncoderOptions(losslessCfg)

        assertEquals(WebPConfig.COMPRESSION_LOSSLESS, losslessWebP.lossless)
        assertEquals(100f, losslessWebP.quality ?: -1f, 0.01f)
        assertEquals(WebPConfig.ALPHA_COMPRESSION_WITH_LOSSLESS, losslessWebP.alphaCompression)
        assertEquals(100, losslessWebP.alphaQuality)
        assertEquals(true, losslessWebP.lowMemory)
        assertEquals(true, losslessWebP.exact)
        assertEquals(false, losslessCfg.minimizeSize)
        assertEquals(false, losslessOpts.minimizeSize)
        assertEquals(0, losslessOpts.kmin)
        assertEquals(1, losslessOpts.kmax)
        assertEquals(false, losslessOpts.allowMixed)
        assertEquals(0, losslessOpts.animParams?.loopCount)

        val lossyCfg = AnimatedWebPSessionConfig(
            width = 480,
            height = 270,
            loopCount = 4,
            lossless = false,
            quality = 52
        )
        val lossyWebP = LibWebPAnimEncoderBackend.buildWebPConfig(lossyCfg)
        val lossyOpts = LibWebPAnimEncoderBackend.buildEncoderOptions(lossyCfg)

        assertEquals(WebPConfig.COMPRESSION_LOSSY, lossyWebP.lossless)
        assertEquals(52f, lossyWebP.quality ?: -1f, 0.01f)
        assertEquals(WebPConfig.ALPHA_COMPRESSION_WITH_LOSSLESS, lossyWebP.alphaCompression)
        assertTrue((lossyWebP.alphaQuality ?: 0) >= 60)
        assertEquals(true, lossyWebP.lowMemory)
        assertEquals(false, lossyWebP.exact)
        assertEquals(false, lossyOpts.minimizeSize)
        assertEquals(0, lossyOpts.kmin)
        assertEquals(1, lossyOpts.kmax)
        assertEquals(4, lossyOpts.animParams?.loopCount)
    }

    // -------------------------------------------------------------------------
    // 1b. Compatibility-first FullCanvasWebPContainerMuxer & Spec Regression Tests
    // -------------------------------------------------------------------------

    @Test
    fun `full-canvas muxer builds spec-compliant opaque lossy animated WebP without spurious ALPH`() {
        val w = 160
        val h = 120
        val frame0 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = false, includeExif = true),
            expectedWidth = w,
            expectedHeight = h
        )
        val frame1 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = false, includeExif = true),
            expectedWidth = w,
            expectedHeight = h
        )
        val frame2 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = false),
            expectedWidth = w,
            expectedHeight = h
        )

        val outFile = tempFile("compat_mux_opaque.webp")
        FullCanvasWebPContainerMuxer.assembleInMemory(
            width = w,
            height = h,
            loopCount = 0,
            backgroundColor = 0x00000000,
            frames = listOf(0L to frame0, 50L to frame1, 170L to frame2),
            endTimestampMs = 290L,
            outputFile = outFile
        )

        val info = AnimatedWebPInspector.inspect(outFile)
        assertTrue("Container must pass all compatibility-first checks", info.isCompatibilityFirstContainer)
        assertTrue(info.hasAnimationFlag)
        assertFalse("Opaque animation must NOT set VP8X ALPH flag", info.hasAlphaFlag)
        assertFalse("Optional EXIF/metadata chunks must be stripped", info.hasUnknownOrMetadataChunks)
        assertTrue(info.allFramesFullCanvas)
        assertEquals(0, info.loopCount)
        assertEquals(0x00000000, info.backgroundColor)
        assertEquals(listOf(50, 120, 120), info.frames.map { it.durationMs })
        assertTrue("Opaque frames must use NO_BLEND (overwrite)", info.frames.all { !it.blendEnabled })
        assertTrue("Opaque frames must use DISPOSE_NONE", info.frames.all { !it.disposeToBackground })
        assertTrue("Opaque frames must not carry ALPH chunks", info.frames.all { !it.hasAlphaChunk })
        assertTrue(info.frames.all { it.payloadChunkType == "VP8" })
    }

    @Test
    fun `full-canvas muxer builds transparent lossy and lossless animated WebP with NO_BLEND plus DISPOSE_BG and even padding`() {
        val w = 65 // Odd width to exercise odd canvas dimensions
        val h = 49 // Odd height
        val oddLossyAlphaFrame0 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = true, oddPayloadSize = true),
            expectedWidth = w,
            expectedHeight = h
        )
        val opaqueLossyFrame1 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = false),
            expectedWidth = w,
            expectedHeight = h
        )

        val alphaLossyOut = tempFile("compat_mux_alpha_lossy.webp")
        FullCanvasWebPContainerMuxer.assembleInMemory(
            width = w,
            height = h,
            loopCount = 3,
            backgroundColor = 0x00000000,
            frames = listOf(0L to oddLossyAlphaFrame0, 80L to opaqueLossyFrame1),
            endTimestampMs = 240L,
            outputFile = alphaLossyOut
        )

        val lossyInfo = AnimatedWebPInspector.inspect(alphaLossyOut)
        assertTrue(lossyInfo.isCompatibilityFirstContainer)
        assertTrue("Mixed/transparent animation must set VP8X ALPH flag", lossyInfo.hasAlphaFlag)
        assertEquals(w, lossyInfo.canvasWidth)
        assertEquals(h, lossyInfo.canvasHeight)
        assertEquals(3, lossyInfo.loopCount)
        assertEquals(listOf(80, 160), lossyInfo.frames.map { it.durationMs })
        assertTrue("Transparent animation must use NO_BLEND", lossyInfo.frames.all { !it.blendEnabled })
        assertTrue("Transparent animation must use DISPOSE_BG to prevent ghosting", lossyInfo.frames.all { it.disposeToBackground })
        assertTrue(lossyInfo.frames[0].hasAlphaChunk)
        assertFalse(lossyInfo.frames[1].hasAlphaChunk)

        // Lossless VP8L with odd payload size (verifying ANMF sub-chunk even-byte zero padding)
        val oddVp8lAlpha = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = true, hasAlpha = true, oddPayloadSize = true),
            expectedWidth = w,
            expectedHeight = h
        )
        val oddVp8lOpaque = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = true, hasAlpha = false, oddPayloadSize = true),
            expectedWidth = w,
            expectedHeight = h
        )
        assertEquals("Even-padded VP8L sub-chunk must have even total length", 0, oddVp8lAlpha.subChunksBytes.size and 1)
        assertTrue(oddVp8lAlpha.hasAlpha)
        assertFalse(oddVp8lOpaque.hasAlpha)

        val losslessAlphaOut = tempFile("compat_mux_vp8l_alpha.webp")
        FullCanvasWebPContainerMuxer.assembleInMemory(
            width = w,
            height = h,
            loopCount = 0,
            backgroundColor = 0x00000000,
            frames = listOf(0L to oddVp8lAlpha, 100L to oddVp8lOpaque),
            endTimestampMs = 200L,
            outputFile = losslessAlphaOut
        )
        val vp8lAlphaInfo = AnimatedWebPInspector.inspect(losslessAlphaOut)
        assertTrue(vp8lAlphaInfo.isCompatibilityFirstContainer)
        assertTrue(vp8lAlphaInfo.hasAlphaFlag)
        assertTrue(vp8lAlphaInfo.frames.all { it.payloadChunkType == "VP8L" && !it.hasAlphaChunk })

        val losslessOpaqueOut = tempFile("compat_mux_vp8l_opaque.webp")
        FullCanvasWebPContainerMuxer.assembleInMemory(
            width = w,
            height = h,
            loopCount = 0,
            backgroundColor = 0x00000000,
            frames = listOf(0L to oddVp8lOpaque, 100L to oddVp8lOpaque),
            endTimestampMs = 200L,
            outputFile = losslessOpaqueOut
        )
        val vp8lOpaqueInfo = AnimatedWebPInspector.inspect(losslessOpaqueOut)
        assertTrue(vp8lOpaqueInfo.isCompatibilityFirstContainer)
        assertFalse("Opaque VP8L must NOT set VP8X ALPH flag", vp8lOpaqueInfo.hasAlphaFlag)
        assertTrue(vp8lOpaqueInfo.frames.all { !it.blendEnabled && !it.disposeToBackground })
    }

    @Test
    fun `full-canvas spool streaming matches in-memory assembly and rejects mismatched frame dimensions`() {
        val w = 80
        val h = 60
        val f0 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = false),
            expectedWidth = w,
            expectedHeight = h
        )
        val f1 = FullCanvasWebPContainerMuxer.extractSingleFramePayload(
            singleFrameWebP = buildSyntheticSingleFrameWebP(w, h, lossless = false, hasAlpha = true, oddPayloadSize = true),
            expectedWidth = w,
            expectedHeight = h
        )

        val spoolFile = tempFile("mux_test.spool")
        FileOutputStream(spoolFile).use { fos ->
            fos.write(f0.subChunksBytes)
            fos.write(f1.subChunksBytes)
        }
        val fromSpoolOut = tempFile("mux_from_spool.webp")
        val inMemoryOut = tempFile("mux_in_memory.webp")

        FullCanvasWebPContainerMuxer.assembleFromSpool(
            width = w,
            height = h,
            loopCount = 2,
            backgroundColor = 0x00000000,
            frames = listOf(
                FullCanvasWebPContainerMuxer.SpoolFrameEntry(0L, f0.subChunksBytes.size, f0.hasAlpha),
                FullCanvasWebPContainerMuxer.SpoolFrameEntry(90L, f1.subChunksBytes.size, f1.hasAlpha)
            ),
            endTimestampMs = 240L,
            spoolFile = spoolFile,
            outputFile = fromSpoolOut
        )
        FullCanvasWebPContainerMuxer.assembleInMemory(
            width = w,
            height = h,
            loopCount = 2,
            backgroundColor = 0x00000000,
            frames = listOf(0L to f0, 90L to f1),
            endTimestampMs = 240L,
            outputFile = inMemoryOut
        )

        assertTrue(
            "Spool-streamed output must be byte-for-byte identical to in-memory assembly",
            fromSpoolOut.readBytes().contentEquals(inMemoryOut.readBytes())
        )
        val info = AnimatedWebPInspector.inspect(fromSpoolOut)
        assertTrue(info.isCompatibilityFirstContainer)
        assertTrue(info.hasAlphaFlag)
        assertEquals(listOf(90, 150), info.frames.map { it.durationMs })

        // Verify mismatched dimensions are rejected before muxing
        var dimRejected = false
        try {
            FullCanvasWebPContainerMuxer.extractSingleFramePayload(
                singleFrameWebP = buildSyntheticSingleFrameWebP(w - 4, h, lossless = false, hasAlpha = false),
                expectedWidth = w,
                expectedHeight = h
            )
            fail("Expected IOException for VP8 frame dimension mismatch")
        } catch (_: java.io.IOException) {
            dimRejected = true
        }
        assertTrue(dimRejected)
    }

    @Test
    fun `regression - partial delta sub-rectangle output fails compatibility verification`() = runBlocking {
        fakeBackend.forcePartialDeltaFrame = true
        val src = SyntheticVideoFrameSource(width = 136, height = 120, durationMs = 400L, fps = 10f)
        val outFile = tempFile("partial_delta_rejected.webp")

        var rejected = false
        try {
            WebPConversionEngine.encodeToFile(
                context = context,
                frameSource = src,
                settings = GifConversionSettings(
                    outputFormat = OutputFormat.WEBP,
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    useOriginalFps = false,
                    fps = 10f,
                    webpQualityPreset = WebPQualityPreset.BALANCED
                ),
                outputFile = outFile
            )
            fail("Expected IOException when backend emits partial delta sub-rectangle")
        } catch (io: java.io.IOException) {
            rejected = true
            assertTrue(io.message.orEmpty().contains("fullCanvas=false"))
        }
        assertTrue(rejected)
    }

    @Test
    fun `gradient, full-frame motion, small moving object, and animated WebP re-encode all produce full-canvas containers`() = runBlocking {
        // 1. Gradient-heavy + small moving object video source
        val gradientMovingSrc = object : VideoFrameSource {
            override val metadata = VideoMetadata(
                uri = Uri.parse("content://test/gradient_moving.mp4"),
                displayName = "gradient_moving.mp4",
                durationMs = 500L,
                rawWidth = 96,
                rawHeight = 64,
                metadataRotation = 0,
                frameRate = 10f,
                fileSizeBytes = 80_000L,
                inputType = MediaInputType.VIDEO,
                hasTransparency = false
            )

            override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                val bmp = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                val step = ((timeUs / 100_000L).toInt() * 10) % 72
                for (y in 0 until 64) {
                    for (x in 0 until 96) {
                        val isDot = x in step until (step + 8) && y in 24 until 32
                        val c = if (isDot) Color.rgb(255, 255, 0) else Color.rgb((x * 255) / 96, (y * 255) / 64, 160)
                        bmp.setPixel(x, y, c)
                    }
                }
                return bmp
            }

            override fun close() {}
        }

        val gradOut = tempFile("gradient_moving_compat.webp")
        val gradStats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = gradientMovingSrc,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 10f,
                webpQualityPreset = WebPQualityPreset.HIGH,
                loopCount = 0
            ),
            outputFile = gradOut
        )
        val gradInfo = AnimatedWebPInspector.inspect(gradOut)
        assertTrue(gradInfo.isCompatibilityFirstContainer)
        assertTrue(gradInfo.allFramesFullCanvas)
        assertFalse(gradInfo.hasAlphaFlag)
        assertFalse(gradStats.hasTransparency)
        assertEquals(gradStats.frameCount, gradInfo.frames.size)
        assertTrue(gradInfo.frames.size >= 4)

        // 2. Animated WebP input re-encoded with variable frame durations preserved
        val webpInputSrc = object : VideoFrameSource {
            override val metadata = VideoMetadata(
                uri = Uri.parse("content://test/input_anim.webp"),
                displayName = "input_anim.webp",
                durationMs = 460L,
                rawWidth = 80,
                rawHeight = 80,
                metadataRotation = 0,
                frameRate = 10f,
                fileSizeBytes = 25_000L,
                inputType = MediaInputType.GIF,
                hasTransparency = true,
                frameTimestampsUs = listOf(0L, 70_000L, 210_000L, 360_000L),
                frameDelaysMs = listOf(70, 140, 150, 100)
            )

            override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                val bmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                val idx = when {
                    timeUs < 70_000L -> 0
                    timeUs < 210_000L -> 1
                    timeUs < 360_000L -> 2
                    else -> 3
                }
                bmp.eraseColor(Color.TRANSPARENT)
                val ox = idx * 16
                for (y in ox until ox + 16) {
                    for (x in ox until ox + 16) {
                        bmp.setPixel(x, y, Color.argb(255, 50 + idx * 50, 180, 220))
                    }
                }
                return bmp
            }

            override fun close() {}
        }

        val reencodedOut = tempFile("webp_reencoded_compat.webp")
        val reencodedStats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = webpInputSrc,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = true,
                webpQualityPreset = WebPQualityPreset.BALANCED,
                loopCount = 5
            ),
            outputFile = reencodedOut
        )
        val reInfo = AnimatedWebPInspector.inspect(reencodedOut)
        assertTrue(reInfo.isCompatibilityFirstContainer)
        assertTrue(reInfo.hasAlphaFlag)
        assertTrue(reencodedStats.hasTransparency)
        assertEquals(5, reInfo.loopCount)
        assertEquals(listOf(70, 140, 150, 100), reInfo.frames.map { it.durationMs })
        assertTrue(reInfo.frames.all { !it.blendEnabled && it.disposeToBackground })
    }

    // -------------------------------------------------------------------------
    // 2. Video-to-WebP (Landscape & Portrait) + Loop Count + Quality Modes
    // -------------------------------------------------------------------------

    @Test
    fun `video to animated WebP produces valid RIFF WEBP container for landscape and portrait`() = runBlocking {
        // Landscape 640x360
        val landscapeSrc = SyntheticVideoFrameSource(width = 640, height = 360, durationMs = 600L, fps = 10f)
        val landscapeOut = tempFile("landscape_out.webp")
        val landscapeSettings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.P240,
            useOriginalFps = false,
            fps = 10f,
            webpQualityPreset = WebPQualityPreset.BALANCED,
            loopCount = 0
        )

        val landscapeStats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = landscapeSrc,
            settings = landscapeSettings,
            outputFile = landscapeOut
        )

        assertTrue(landscapeOut.exists())
        val landscapeInfo = AnimatedWebPInspector.inspect(landscapeOut)
        assertTrue(landscapeInfo.isValidAnimatedWebP)
        assertTrue(landscapeInfo.hasAnimationFlag)
        assertEquals(landscapeStats.width, landscapeInfo.canvasWidth)
        assertEquals(landscapeStats.height, landscapeInfo.canvasHeight)
        assertTrue("Landscape width must exceed height", landscapeInfo.canvasWidth > landscapeInfo.canvasHeight)
        assertEquals(0, landscapeInfo.loopCount)
        assertTrue(landscapeInfo.frames.size >= 4)
        assertEquals(OutputFormat.WEBP, landscapeStats.outputFormat)
        assertEquals(WebPQualityPreset.BALANCED, landscapeStats.webpQualityPreset)
        assertEquals(75, landscapeStats.webpQuality)
        assertEquals("VP8", landscapeInfo.frames.first().payloadChunkType)

        // Portrait 360x640 with custom loop count & Lossless mode
        val portraitSrc = SyntheticVideoFrameSource(width = 360, height = 640, durationMs = 500L, fps = 10f)
        val portraitOut = tempFile("portrait_out.webp")
        val portraitSettings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.P240,
            useOriginalFps = false,
            fps = 10f,
            webpQualityPreset = WebPQualityPreset.LOSSLESS,
            loopCount = 3
        )

        val portraitStats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = portraitSrc,
            settings = portraitSettings,
            outputFile = portraitOut
        )

        val portraitInfo = AnimatedWebPInspector.inspect(portraitOut)
        assertTrue(portraitInfo.isValidAnimatedWebP)
        assertTrue("Portrait height must exceed width", portraitInfo.canvasHeight > portraitInfo.canvasWidth)
        assertEquals(3, portraitInfo.loopCount)
        assertEquals(WebPQualityPreset.LOSSLESS, portraitStats.webpQualityPreset)
        assertEquals(100, portraitStats.webpQuality)
        assertEquals("VP8L", portraitInfo.frames.first().payloadChunkType)
        assertTrue(
            "Lossless WebP output should be larger than balanced lossy for equivalent area",
            portraitOut.length() > landscapeOut.length() * 0.5
        )
    }

    // -------------------------------------------------------------------------
    // 3. GIF-to-WebP preserving variable frame durations & alpha transparency
    // -------------------------------------------------------------------------

    @Test
    fun `gif to animated WebP preserves variable frame durations and alpha transparency`() = runBlocking {
        val w = 32
        val h = 32
        fun frameWithAlpha(color: Int): IntArray = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x < 10 && y < 10) Color.TRANSPARENT else color
        }

        val inputGif = writeSyntheticGif(
            fileName = "transparent_var_timing.gif",
            width = w,
            height = h,
            frames = listOf(
                frameWithAlpha(Color.rgb(240, 60, 80)) to 60,
                frameWithAlpha(Color.rgb(60, 220, 120)) to 140,
                frameWithAlpha(Color.rgb(70, 130, 240)) to 220
            )
        )

        val gifSource = AnimatedGifFrameSource.openFile(inputGif, displayName = "transparent_var_timing.gif")
        gifSource.use { src ->
            assertTrue("Source GIF should detect transparency", src.metadata.hasTransparency)

            val outputWebP = tempFile("gif_to_webp_alpha.webp")
            val settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = true,
                webpQualityPreset = WebPQualityPreset.HIGH,
                loopCount = 2
            )

            val stats = WebPConversionEngine.encodeToFile(
                context = context,
                frameSource = src,
                settings = settings,
                outputFile = outputWebP
            )

            val info = AnimatedWebPInspector.inspect(outputWebP)
            assertTrue(info.isValidAnimatedWebP)
            assertTrue("VP8X alpha flag must be set", info.hasAlphaFlag)
            assertTrue("Stats must record transparency", stats.hasTransparency)
            assertEquals(2, info.loopCount)
            assertEquals(3, info.frames.size)
            assertEquals(listOf(60, 140, 220), info.frames.map { it.durationMs })
            assertTrue("Lossy alpha frame should carry ALPH sub-chunk", info.frames.all { it.hasAlphaChunk })
        }
    }

    // -------------------------------------------------------------------------
    // 4. Independent WebP Size Estimator & MediaSizeEstimator dispatch
    // -------------------------------------------------------------------------

    @Test
    fun `webp size estimator is independent of GIF estimator and reflects quality modes`() = runBlocking {
        val src = SyntheticVideoFrameSource(width = 480, height = 270, durationMs = 1000L, fps = 12f)

        val gifSettings = GifConversionSettings(
            outputFormat = OutputFormat.GIF,
            resolutionPreset = ResolutionPreset.P240,
            useOriginalFps = false,
            fps = 12f,
            colorQualityPreset = ColorQualityPreset.C64
        )
        val webpSmaller = gifSettings.copy(
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = WebPQualityPreset.SMALLER,
            webpQuality = WebPQualityPreset.SMALLER.defaultQuality
        )
        val webpHigh = gifSettings.copy(
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = WebPQualityPreset.HIGH,
            webpQuality = WebPQualityPreset.HIGH.defaultQuality
        )
        val webpLossless = gifSettings.copy(
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = WebPQualityPreset.LOSSLESS,
            webpQuality = 100
        )

        val gifEst = MediaSizeEstimator.estimate(context, src, gifSettings)
        val smallerEst = MediaSizeEstimator.estimate(context, src, webpSmaller)
        val highEst = MediaSizeEstimator.estimate(context, src, webpHigh)
        val losslessEst = WebPSizeEstimator.estimate(context, src, webpLossless)

        assertEquals(OutputFormat.GIF, gifEst.outputFormat)
        assertEquals(OutputFormat.WEBP, smallerEst.outputFormat)
        assertEquals(OutputFormat.WEBP, highEst.outputFormat)
        assertEquals(OutputFormat.WEBP, losslessEst.outputFormat)
        assertEquals(0, smallerEst.resolvedColorCount)
        assertEquals(WebPQualityPreset.SMALLER, smallerEst.webpQualityPreset)
        assertEquals(52, smallerEst.webpQuality)

        assertTrue(
            "Smaller WebP estimate (${smallerEst.estimatedBytes}) must be < High (${highEst.estimatedBytes})",
            smallerEst.estimatedBytes < highEst.estimatedBytes
        )
        assertTrue(
            "High WebP estimate (${highEst.estimatedBytes}) must be < Lossless (${losslessEst.estimatedBytes})",
            highEst.estimatedBytes < losslessEst.estimatedBytes
        )
        assertTrue(
            "WebP estimate must be independently calculated and not identical to GIF estimate",
            smallerEst.estimatedBytes != gifEst.estimatedBytes
        )
    }

    // -------------------------------------------------------------------------
    // 5. Independent WebP Live Preview Renderer
    // -------------------------------------------------------------------------

    @Test
    fun `live preview renders independent WebP preview with quality label`() = runBlocking {
        val src = SyntheticVideoFrameSource(width = 320, height = 180, durationMs = 500L, fps = 10f)
        val settings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.P240,
            useOriginalFps = false,
            fps = 10f,
            webpQualityPreset = WebPQualityPreset.HIGH
        )

        val preview = LiveGifPreviewRenderer.renderPreview(src, settings, context)
        try {
            assertEquals(OutputFormat.WEBP, preview.outputFormat)
            assertEquals("High (88)", preview.webpQualityLabel)
            assertEquals(0, preview.resolvedColorCount)
            assertTrue(preview.globalPaletteColors.isEmpty())
            assertTrue("Expected preview frames", preview.frames.isNotEmpty())
        } finally {
            preview.recycleAll()
        }
    }

    // -------------------------------------------------------------------------
    // 6. Cancellation deletes partial temp file cleanly
    // -------------------------------------------------------------------------

    @Test
    fun `cancelling animated WebP conversion cleans up partial output file`() = runBlocking {
        fakeBackend.perFrameDelayMs = 25L
        val src = SyntheticVideoFrameSource(width = 320, height = 180, durationMs = 2000L, fps = 20f)
        val outFile = tempFile("cancelled_output.webp")
        val settings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.P240,
            useOriginalFps = false,
            fps = 20f,
            webpQualityPreset = WebPQualityPreset.BALANCED
        )

        var caughtCancel = false
        val job = launch {
            try {
                WebPConversionEngine.encodeToFile(
                    context = context,
                    frameSource = src,
                    settings = settings,
                    outputFile = outFile
                ) { progress ->
                    if (progress.currentFrame >= 2) {
                        cancel("User cancelled WebP export")
                    }
                }
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                caughtCancel = true
            }
        }
        job.join()

        assertTrue("CancellationException should have been thrown", caughtCancel)
        assertFalse("Partial output file must be deleted after cancel", outFile.exists())
    }

    // -------------------------------------------------------------------------
    // 7. GalleryExporter (.webp / image/webp), Share/Open Intents & Notifications
    // -------------------------------------------------------------------------

    @Test
    fun `gallery export inserts only one animated webp into MediaStore with zero jpgs and isolates thumbnail`() = runBlocking {
        val sinkFile = tempFile("mediastore_sink.webp")
        val provider = CapturingMediaStoreProvider(sinkFile)
        val info = ProviderInfo().apply { authority = MediaStore.AUTHORITY }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val src = SyntheticVideoFrameSource(width = 160, height = 120, durationMs = 400L, fps = 10f)
        val encodedFile = tempFile("encoded_for_gallery.webp")
        val stats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = src,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 10f,
                webpQualityPreset = WebPQualityPreset.BALANCED
            ),
            outputFile = encodedFile
        )

        val saved = GalleryExporter.saveAnimationToGallery(
            context = context,
            tempFile = encodedFile,
            sourceName = "sunset_clip.mp4",
            stats = stats,
            thumbnailBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        )

        // Exactly ONE MediaStore insertion (no extra .jpg or thumbnail row in Gallery)
        assertEquals("Gallery export must insert exactly one MediaStore entry", 1, provider.insertedEntries.size)
        val inserted = provider.insertedEntries.single()
        assertEquals("image/webp", inserted.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertTrue(
            "Inserted DISPLAY_NAME must end with .webp",
            inserted.getAsString(MediaStore.Images.Media.DISPLAY_NAME)?.endsWith(".webp") == true
        )
        assertFalse(
            "No JPEG entry may be inserted into Gallery MediaStore",
            provider.insertedEntries.any {
                val mime = it.getAsString(MediaStore.Images.Media.MIME_TYPE).orEmpty()
                val name = it.getAsString(MediaStore.Images.Media.DISPLAY_NAME).orEmpty().lowercase()
                mime.contains("jpeg") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            }
        )

        // Read saved Gallery URI back through ContentResolver and verify exact SHA-256 match
        // against the original encoded WebP bytes (proving Gallery & Share reference identical bytes)
        val encodedBytes = encodedFile.readBytes()
        val readBackBytes = context.contentResolver.openInputStream(saved.contentUri)?.use { it.readBytes() }
        assertNotNull("ContentResolver must return saved Gallery stream", readBackBytes)
        assertEquals("Gallery stream byte length must match encoded WebP", encodedBytes.size, readBackBytes!!.size)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val encodedHash = sha256.digest(encodedBytes).joinToString("") { "%02x".format(it) }
        val galleryHash = sha256.digest(readBackBytes).joinToString("") { "%02x".format(it) }
        assertEquals("Gallery ContentResolver bytes must match original encoded WebP SHA-256", encodedHash, galleryHash)

        // Saved MediaStore bytes themselves must parse as a genuine multi-frame Animated WebP
        val savedWebPInfo = AnimatedWebPInspector.inspect(readBackBytes)
        assertTrue("Saved Gallery file must be a valid Animated WebP", savedWebPInfo.isValidAnimatedWebP)
        assertTrue("Saved Gallery file must have > 1 frame", savedWebPInfo.hasMultipleFrames)
        assertTrue("Expected at least 3 frames in saved Gallery WebP", savedWebPInfo.frames.size >= 3)

        // History thumbnail must stay strictly in internal app-private storage with .nomedia guard
        val thumbPath = saved.thumbnailPath
        assertNotNull(thumbPath)
        val thumbFile = File(thumbPath!!)
        assertTrue(thumbFile.exists())
        assertTrue(
            "History thumbnail must be stored inside app-private filesDir",
            thumbFile.absolutePath.startsWith(File(context.filesDir, "thumbnails").absolutePath)
        )
        assertTrue(
            "Internal thumbnail directory should carry .nomedia marker",
            File(context.filesDir, "thumbnails/.nomedia").exists()
        )

        assertTrue("File name must end with .webp", saved.fileName.endsWith(".webp"))
        assertEquals("image/webp", provider.lastInsertedMimeType)
        assertEquals("image/webp", GalleryExporter.mimeTypeForFileName(saved.fileName))

        val shareChooser = GalleryExporter.buildShareIntent(
            uri = saved.contentUri,
            title = "Share Animated WebP",
            mimeType = OutputFormat.WEBP.mimeType
        )
        @Suppress("DEPRECATION")
        val innerShare = shareChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(innerShare)
        assertEquals("image/webp", innerShare?.type)
        assertEquals(saved.contentUri, innerShare?.clipData?.getItemAt(0)?.uri)

        val viewIntent = GalleryExporter.buildOpenViewerIntent(saved.contentUri, OutputFormat.WEBP.mimeType)
        assertEquals("image/webp", viewIntent.type)
        assertEquals(saved.contentUri, viewIntent.data)

        // Active and completion notifications must clearly identify Animated WebP
        val activeNotif = ConversionNotificationHelper.buildActiveSingleNotification(
            context = context,
            workId = UUID.randomUUID(),
            sourceName = "sunset_clip.mp4",
            progressPercent = 65,
            stage = "Encoding WebP frame 2 of 3",
            indeterminate = false,
            outputFormat = OutputFormat.WEBP
        )
        assertEquals("Converting sunset_clip.mp4 to WebP", activeNotif.extras.getString(Notification.EXTRA_TITLE))

        val posted = ConversionNotificationHelper.postCompletionNotification(
            context = context,
            outputFileName = saved.fileName,
            fileSizeBytes = stats.fileSizeBytes,
            contentUri = saved.contentUri,
            notificationId = 4321,
            outputFormat = OutputFormat.WEBP
        )
        assertTrue(posted)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val statusBarNotif = Shadows.shadowOf(nm).getNotification(4321)
        assertNotNull(statusBarNotif)
        assertTrue(
            "Completion notification title must identify Animated WebP",
            statusBarNotif.extras.getString(Notification.EXTRA_TITLE)?.startsWith("Animated WebP Saved") == true
        )
    }

    @Test
    fun `opaque video reports hasTransparency false even when VP8X delta alpha bit is set`() = runBlocking {
        fakeBackend.forceVp8xAlphaFlag = true
        val opaqueSrc = SyntheticVideoFrameSource(width = 160, height = 120, durationMs = 400L, fps = 10f, withAlpha = false)
        val outFile = tempFile("opaque_delta_alpha.webp")

        val stats = WebPConversionEngine.encodeToFile(
            context = context,
            frameSource = opaqueSrc,
            settings = GifConversionSettings(
                outputFormat = OutputFormat.WEBP,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 10f,
                webpQualityPreset = WebPQualityPreset.BALANCED
            ),
            outputFile = outFile
        )

        val info = AnimatedWebPInspector.inspect(outFile)
        assertTrue("Container simulates libwebp delta-mask VP8X alpha flag", info.hasAlphaFlag)
        assertTrue("Must still be valid multi-frame Animated WebP", info.hasMultipleFrames)
        assertFalse("Opaque source video must not falsely report transparency", stats.hasTransparency)
    }

    @Test
    fun `multi-frame export fails fast if encoder collapses output to single frame`() = runBlocking {
        fakeBackend.forceSingleFrameCollapse = true
        val src = SyntheticVideoFrameSource(width = 160, height = 120, durationMs = 500L, fps = 10f)
        val outFile = tempFile("collapsed_single_frame.webp")

        var threwIo = false
        try {
            WebPConversionEngine.encodeToFile(
                context = context,
                frameSource = src,
                settings = GifConversionSettings(
                    outputFormat = OutputFormat.WEBP,
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    useOriginalFps = false,
                    fps = 10f,
                    webpQualityPreset = WebPQualityPreset.BALANCED
                ),
                outputFile = outFile
            )
            fail("Expected IOException when multi-frame input collapses to 1 frame")
        } catch (io: java.io.IOException) {
            threwIo = true
            assertTrue(io.message.orEmpty().contains("multiple animated frames"))
        }
        assertTrue(threwIo)
    }

    // -------------------------------------------------------------------------
    // 8. Compose UI: Format selector on EditorScreen & Animated WebP ExportScreen
    // -------------------------------------------------------------------------

    @Test
    fun `editor defaults to GIF and switching to Animated WebP reveals WebP controls and hides GIF controls`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/clip.mp4"),
            displayName = "clip.mp4",
            durationMs = 3000L,
            rawWidth = 640,
            rawHeight = 360,
            metadataRotation = 0,
            frameRate = 24f,
            fileSizeBytes = 1_500_000L,
            inputType = MediaInputType.VIDEO
        )

        var editorState by mutableStateOf(
            EditorUiState(
                metadata = metadata,
                settings = GifConversionSettings.DEFAULT,
                activeTab = EditorTab.COLOR_DITHER
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = editorState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = { tab -> editorState = editorState.copy(activeTab = tab) },
                    onUpdateSettings = { transform ->
                        editorState = editorState.copy(settings = transform(editorState.settings))
                    },
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Default format is GIF: GIF dithering option is present, WebP presets are absent
        assertEquals(OutputFormat.GIF, editorState.settings.outputFormat)
        composeRule.onNodeWithTag("format_selector_gif").assertIsDisplayed()
        composeRule.onNodeWithTag("format_selector_webp").assertIsDisplayed()
        composeRule.onNodeWithTag("dither_option_floyd_steinberg").performScrollTo().assertIsDisplayed()

        // Switch to Animated WebP (format selector is pinned visibly above the scroll workbench)
        composeRule.onNodeWithTag("format_selector_webp").performClick()
        composeRule.waitForIdle()

        assertEquals(OutputFormat.WEBP, editorState.settings.outputFormat)
        composeRule.onNodeWithTag("webp_preset_lossless").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webp_preset_high").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webp_preset_balanced").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webp_preset_smaller").performScrollTo().assertIsDisplayed()

        // GIF dithering control must no longer exist in the tree when WebP is selected
        composeRule.onNodeWithTag("dither_option_floyd_steinberg").assertDoesNotExist()
    }

    @Test
    fun `export success screen displays Animated WebP labels, quality stat, and Share WebP button`() {
        val result = SavedGifResult(
            contentUri = Uri.parse("content://media/external/images/media/202"),
            fileName = "NepiGifLab_sunset_20261125.webp",
            relativeFolder = "Pictures/Nepi GIF Lab",
            thumbnailPath = null,
            stats = EncodedGifStats(
                width = 480,
                height = 270,
                fps = 24f,
                durationMs = 2000L,
                frameCount = 48,
                fileSizeBytes = 245_000L,
                effectiveColorCount = 0,
                ditherMode = DitherMode.NONE,
                outputFormat = OutputFormat.WEBP,
                webpQualityPreset = WebPQualityPreset.HIGH,
                webpQuality = 88,
                hasTransparency = true
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                ExportScreen(
                    state = ExportUiState.Success(result = result, estimatedBytes = 250_000L),
                    onCancelExport = {},
                    onRetryExport = {},
                    onShareGif = {},
                    onOpenGif = {},
                    onDone = {},
                    onBackToEditor = {},
                    outputFormat = OutputFormat.WEBP,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithText("Animated WebP Saved to Gallery").assertIsDisplayed()
        composeRule.onNodeWithText("FINAL WEBP").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("WebP Quality").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Share WebP").performScrollTo().assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // Helpers & Spec-Compliant Synthetic RIFF/WEBP Backend
    // -------------------------------------------------------------------------

    private fun tempFile(name: String): File =
        File(context.cacheDir, name).apply { if (exists()) delete() }

    private fun writeSyntheticGif(
        fileName: String,
        width: Int,
        height: Int,
        frames: List<Pair<IntArray, Int>>
    ): File {
        val file = tempFile(fileName)
        FileOutputStream(file).use { fos ->
            val encoder = Gif89aStreamEncoder(fos, width, height, loopCount = 0)
            for ((pixels, delayMs) in frames) {
                val q = FrameQuantizer.quantizeAndDither(
                    sourcePixels = pixels,
                    width = width,
                    height = height,
                    requestedColorCount = 32,
                    ditherMode = DitherMode.NONE
                )
                encoder.addFrame(q, delayMs = delayMs, disposalMethod = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND)
            }
            encoder.finish()
        }
        return file
    }

    private class SyntheticVideoFrameSource(
        width: Int,
        height: Int,
        durationMs: Long,
        fps: Float,
        private val withAlpha: Boolean = false
    ) : VideoFrameSource {
        override val metadata = VideoMetadata(
            uri = Uri.parse("content://test/synthetic.mp4"),
            displayName = "synthetic.mp4",
            durationMs = durationMs,
            rawWidth = width,
            rawHeight = height,
            metadataRotation = 0,
            frameRate = fps,
            fileSizeBytes = 500_000L,
            inputType = MediaInputType.VIDEO,
            hasTransparency = withAlpha
        )

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val bmp = Bitmap.createBitmap(metadata.rawWidth, metadata.rawHeight, Bitmap.Config.ARGB_8888)
            val t = ((timeUs / 1000L) % 255L).toInt()
            val color = if (withAlpha) Color.argb(128, (40 + t) % 255, 160, 220)
            else Color.rgb((40 + t) % 255, 140, 200)
            bmp.eraseColor(color)
            return bmp
        }

        override fun close() {}
    }

    private fun buildSyntheticSingleFrameWebP(
        width: Int,
        height: Int,
        lossless: Boolean,
        hasAlpha: Boolean,
        oddPayloadSize: Boolean = false,
        includeExif: Boolean = false
    ): ByteArray {
        val body = ByteArrayOutputStream()
        fun writeChunk(fourCc: String, data: ByteArray) {
            body.write(fourCc.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            body.write(data)
            if ((data.size and 1) == 1) body.write(0)
        }
        if ((hasAlpha && !lossless) || includeExif) {
            val vp8x = ByteArray(10)
            var flags = 0
            if (hasAlpha) flags = flags or 0x10
            if (includeExif) flags = flags or 0x08
            vp8x[0] = flags.toByte()
            vp8x[4] = ((width - 1) and 0xFF).toByte()
            vp8x[5] = (((width - 1) ushr 8) and 0xFF).toByte()
            vp8x[6] = (((width - 1) ushr 16) and 0xFF).toByte()
            vp8x[7] = ((height - 1) and 0xFF).toByte()
            vp8x[8] = (((height - 1) ushr 8) and 0xFF).toByte()
            vp8x[9] = (((height - 1) ushr 16) and 0xFF).toByte()
            writeChunk("VP8X", vp8x)
        }
        if (lossless) {
            val payloadLen = if (oddPayloadSize) 17 else 16
            val vp8l = ByteArray(payloadLen)
            vp8l[0] = 0x2F
            val bits = ((width - 1) and 0x3FFF) or
                (((height - 1) and 0x3FFF) shl 14) or
                ((if (hasAlpha) 1 else 0) shl 28)
            vp8l[1] = (bits and 0xFF).toByte()
            vp8l[2] = ((bits ushr 8) and 0xFF).toByte()
            vp8l[3] = ((bits ushr 16) and 0xFF).toByte()
            vp8l[4] = ((bits ushr 24) and 0xFF).toByte()
            writeChunk("VP8L", vp8l)
        } else {
            if (hasAlpha) {
                writeChunk("ALPH", ByteArray(if (oddPayloadSize) 15 else 16) { 0x01 })
            }
            val vp8 = ByteArray(24)
            vp8[0] = 0x00 // keyframe bit = 0
            vp8[3] = 0x9D.toByte()
            vp8[4] = 0x01
            vp8[5] = 0x2A
            vp8[6] = (width and 0xFF).toByte()
            vp8[7] = ((width ushr 8) and 0x3F).toByte()
            vp8[8] = (height and 0xFF).toByte()
            vp8[9] = ((height ushr 8) and 0x3F).toByte()
            writeChunk("VP8 ", vp8)
        }
        if (includeExif) {
            writeChunk("EXIF", ByteArray(10) { 0x45 })
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(12 + bodyBytes.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + bodyBytes.size).array())
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    /**
     * Spec-compliant RIFF/WEBP writer for JVM Robolectric tests where native Android `.so`
     * binaries cannot execute. Writes real VP8X, ANIM, ANMF, optional ALPH, and valid-header
     * VP8/VP8L chunks whose payload sizes scale deterministically with dimensions and quality settings.
     */
    private class FakeRiffWebPBackend : AnimatedWebPBackend {
        var perFrameDelayMs: Long = 0L
        var forceVp8xAlphaFlag: Boolean = false
        var forceSingleFrameCollapse: Boolean = false
        var forcePartialDeltaFrame: Boolean = false

        private data class CapturedFrame(
            val timestampMs: Long,
            val width: Int,
            val height: Int,
            val hasAlpha: Boolean
        )

        override suspend fun <T> withSession(
            config: AnimatedWebPSessionConfig,
            block: suspend (AnimatedWebPSession) -> T
        ): T {
            val frames = ArrayList<CapturedFrame>()
            val session = object : AnimatedWebPSession {
                override fun addFrame(timestampMs: Long, bitmap: Bitmap, shouldContinue: () -> Boolean) {
                    if (!shouldContinue()) throw CancellationException("Cancelled")
                    if (perFrameDelayMs > 0L) Thread.sleep(perFrameDelayMs)
                    if (!shouldContinue()) throw CancellationException("Cancelled")
                    var alphaFound = false
                    if (bitmap.config == Bitmap.Config.ARGB_8888 || bitmap.hasAlpha()) {
                        val row = IntArray(bitmap.width)
                        outer@ for (y in 0 until bitmap.height) {
                            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                            for (x in 0 until bitmap.width) {
                                if (((row[x] ushr 24) and 0xFF) < 255) {
                                    alphaFound = true
                                    break@outer
                                }
                            }
                        }
                    }
                    frames.add(CapturedFrame(timestampMs, bitmap.width, bitmap.height, alphaFound))
                }

                override fun assemble(endTimestampMs: Long, outputFile: File) {
                    outputFile.parentFile?.mkdirs()
                    val body = ByteArrayOutputStream()
                    val anyAlpha = forceVp8xAlphaFlag || frames.any { it.hasAlpha }

                    // 1. VP8X chunk (10 bytes)
                    val vp8x = ByteArray(10)
                    var vp8xFlags = 0x02 // ANIM bit
                    if (anyAlpha) vp8xFlags = vp8xFlags or 0x10 // ALPH bit
                    vp8x[0] = vp8xFlags.toByte()
                    writeUint24LE(vp8x, 4, (config.width - 1).coerceAtLeast(0))
                    writeUint24LE(vp8x, 7, (config.height - 1).coerceAtLeast(0))
                    writeChunk(body, "VP8X", vp8x)

                    // 2. ANIM chunk (6 bytes)
                    val anim = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(config.backgroundColor)
                        .putShort(config.loopCount.coerceIn(0, 65535).toShort())
                        .array()
                    writeChunk(body, "ANIM", anim)

                    // 3. ANMF chunks
                    val pixelCount = max(1, config.width * config.height)
                    val bppFactor = if (config.lossless) {
                        1.40
                    } else {
                        0.08 + (config.quality.coerceIn(0, 100) / 100.0) * 0.55
                    }
                    val framePayloadSize = max(32, (pixelCount * bppFactor * 0.15).roundToInt()) and 0x7FFFFFFE

                    val emittedFrames = if (forceSingleFrameCollapse && frames.isNotEmpty()) {
                        listOf(frames.first())
                    } else {
                        frames
                    }

                    val anmfFlags = (if (anyAlpha) 0x03 else 0x02).toByte()

                    for (i in emittedFrames.indices) {
                        val f = emittedFrames[i]
                        val nextTs = if (i + 1 < emittedFrames.size) emittedFrames[i + 1].timestampMs else endTimestampMs
                        val durationMs = (nextTs - f.timestampMs).toInt().coerceAtLeast(10)
                        val isPartial = forcePartialDeltaFrame && i > 0
                        val fx = if (isPartial) 4 else 0
                        val fy = if (isPartial) 4 else 0
                        val fw = if (isPartial) (f.width - 8).coerceAtLeast(2) else f.width
                        val fh = if (isPartial) (f.height - 8).coerceAtLeast(2) else f.height

                        val anmf = ByteArrayOutputStream()
                        val header = ByteArray(16)
                        writeUint24LE(header, 0, fx / 2)
                        writeUint24LE(header, 3, fy / 2)
                        writeUint24LE(header, 6, (fw - 1).coerceAtLeast(0))
                        writeUint24LE(header, 9, (fh - 1).coerceAtLeast(0))
                        writeUint24LE(header, 12, durationMs)
                        header[15] = anmfFlags
                        anmf.write(header)

                        if (config.lossless) {
                            val vp8l = ByteArray(framePayloadSize) { ((it + i) and 0xFF).toByte() }
                            vp8l[0] = 0x2F
                            val bits = ((fw - 1) and 0x3FFF) or
                                (((fh - 1) and 0x3FFF) shl 14) or
                                ((if (f.hasAlpha) 1 else 0) shl 28)
                            vp8l[1] = (bits and 0xFF).toByte()
                            vp8l[2] = ((bits ushr 8) and 0xFF).toByte()
                            vp8l[3] = ((bits ushr 16) and 0xFF).toByte()
                            vp8l[4] = ((bits ushr 24) and 0xFF).toByte()
                            writeChunk(anmf, "VP8L", vp8l)
                        } else {
                            if (f.hasAlpha) {
                                writeChunk(anmf, "ALPH", ByteArray(16) { 0x01 })
                            }
                            val vp8 = ByteArray(framePayloadSize) { ((it + i) and 0xFF).toByte() }
                            vp8[0] = 0x00 // keyframe bit = 0
                            vp8[3] = 0x9D.toByte()
                            vp8[4] = 0x01
                            vp8[5] = 0x2A
                            vp8[6] = (fw and 0xFF).toByte()
                            vp8[7] = ((fw ushr 8) and 0x3F).toByte()
                            vp8[8] = (fh and 0xFF).toByte()
                            vp8[9] = ((fh ushr 8) and 0x3F).toByte()
                            writeChunk(anmf, "VP8 ", vp8)
                        }

                        writeChunk(body, "ANMF", anmf.toByteArray())
                    }

                    val bodyBytes = body.toByteArray()
                    val riffHeader = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                        .put("RIFF".toByteArray(Charsets.US_ASCII))
                        .putInt(4 + bodyBytes.size)
                        .put("WEBP".toByteArray(Charsets.US_ASCII))
                        .array()

                    FileOutputStream(outputFile).use { fos ->
                        fos.write(riffHeader)
                        fos.write(bodyBytes)
                    }
                }

                override fun close() {}
            }
            return try {
                block(session)
            } finally {
                session.close()
            }
        }

        private fun writeChunk(out: ByteArrayOutputStream, fourCc: String, data: ByteArray) {
            out.write(fourCc.toByteArray(Charsets.US_ASCII))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            out.write(data)
            if ((data.size and 1) == 1) out.write(0)
        }

        private fun writeUint24LE(dest: ByteArray, offset: Int, value: Int) {
            dest[offset] = (value and 0xFF).toByte()
            dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            dest[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        }
    }

    private class CapturingMediaStoreProvider(private val sinkFile: File) : ContentProvider() {
        var lastInsertedMimeType: String? = null
        val insertedEntries: MutableList<ContentValues> = mutableListOf()
        override fun onCreate(): Boolean = true
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String = lastInsertedMimeType ?: "image/webp"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            values?.let { insertedEntries.add(ContentValues(it)) }
            lastInsertedMimeType = values?.getAsString(MediaStore.Images.Media.MIME_TYPE)
            return Uri.withAppendedPath(uri, (9000 + insertedEntries.size).toString())
        }
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 1
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 1
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            sinkFile.parentFile?.mkdirs()
            if (!sinkFile.exists()) sinkFile.createNewFile()
            return ParcelFileDescriptor.open(sinkFile, ParcelFileDescriptor.MODE_READ_WRITE)
        }
    }
}
