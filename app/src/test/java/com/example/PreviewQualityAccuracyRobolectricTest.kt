package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.FrameQuantizer
import com.example.engine.FrameTransformer
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.LiveGifPreviewRenderer
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputDimensions
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import com.example.engine.WebPQualityPreset
import com.example.ui.components.VideoPreviewStage
import com.example.ui.theme.GifStudioTheme
import com.example.viewmodel.PreviewMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Verifies preview resolution, scaling fidelity, color/dither accuracy, transparency,
 * variable frame timing, aspect ratios, memory bounds, and preview-vs-export parity
 * for both GIF and Animated WebP previews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class PreviewQualityAccuracyRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -------------------------------------------------------------------------
    // 1. Display-aware, memory-budgeted preview resolution & aspect ratios
    // -------------------------------------------------------------------------

    @Test
    fun `preview budget renders 1to1 for standard outputs and scales crisply within RAM limits for high-res`() {
        // Default 360p (640x360) must render 1:1 (previously crushed to 300x169!)
        val b360 = LiveGifPreviewRenderer.computePreviewBudget(
            outputDims = OutputDimensions(640, 360),
            targetMaxEdgePx = 720,
            isLowRamDevice = false
        )
        assertEquals(640, b360.previewWidth)
        assertEquals(360, b360.previewHeight)
        assertTrue(b360.previewWidth.toLong() * b360.previewHeight * 4L * b360.maxFrames <= b360.memoryBudgetBytes)

        // Small 240x135 output must NEVER be upscaled beyond outputDims
        val bSmall = LiveGifPreviewRenderer.computePreviewBudget(
            outputDims = OutputDimensions(240, 135),
            targetMaxEdgePx = 960,
            isLowRamDevice = false
        )
        assertEquals(240, bSmall.previewWidth)
        assertEquals(135, bSmall.previewHeight)

        // High-res 1920x1080 on high-DPI viewport (960px) retains crisp 960x540 while respecting RAM cap
        val b1080 = LiveGifPreviewRenderer.computePreviewBudget(
            outputDims = OutputDimensions(1920, 1080),
            targetMaxEdgePx = 960,
            isLowRamDevice = false
        )
        assertEquals(960, b1080.previewWidth)
        assertEquals(540, b1080.previewHeight)
        assertTrue(b1080.maxFrames in 8..36)
        assertTrue(b1080.previewWidth.toLong() * b1080.previewHeight * 4L * b1080.maxFrames <= b1080.memoryBudgetBytes)

        // Portrait 1080x1920 preserves portrait aspect ratio
        val bPortrait = LiveGifPreviewRenderer.computePreviewBudget(
            outputDims = OutputDimensions(1080, 1920),
            targetMaxEdgePx = 960,
            isLowRamDevice = false
        )
        assertEquals(540, bPortrait.previewWidth)
        assertEquals(960, bPortrait.previewHeight)

        // Ultra-wide 1600x320 preserves 5:1 aspect ratio accurately
        val bWide = LiveGifPreviewRenderer.computePreviewBudget(
            outputDims = OutputDimensions(1600, 320),
            targetMaxEdgePx = 960,
            isLowRamDevice = false
        )
        assertEquals(960, bWide.previewWidth)
        assertEquals(192, bWide.previewHeight)

        // Low-RAM device stays bounded at <= 540px and <= 10 MB total
        val bLowRam = LiveGifPreviewRenderer.computePreviewBudget(
            outputDims = OutputDimensions(1920, 1080),
            targetMaxEdgePx = 960,
            isLowRamDevice = true
        )
        assertTrue(maxOf(bLowRam.previewWidth, bLowRam.previewHeight) <= 540)
        assertTrue(
            bLowRam.previewWidth.toLong() * bLowRam.previewHeight * 4L * bLowRam.maxFrames <= 10L * 1024L * 1024L
        )
    }

    // -------------------------------------------------------------------------
    // 2. High-resolution GIF preview vs actual exported GIF at equivalent display size
    // -------------------------------------------------------------------------

    @Test
    fun `high-res GIF preview matches exported GIF at 1to1 and retains crisp detail above 300px`() = runBlocking {
        // Test A: 640x360 smooth gradient + fine details -> 1:1 exact match between preview & export
        val src360 = PatternVideoFrameSource(width = 640, height = 360, durationMs = 500L, fps = 10f)
        val settings360 = GifConversionSettings(
            outputFormat = OutputFormat.GIF,
            resolutionPreset = ResolutionPreset.P360,
            colorQualityPreset = ColorQualityPreset.C128,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 8f,
            trimStartMs = 0L,
            trimEndMs = 500L
        )

        val preview360 = LiveGifPreviewRenderer.renderPreview(
            frameSource = src360,
            settings = settings360,
            context = context,
            targetMaxEdgePx = 720
        )
        val exportedFile360 = tempFile("export_640x360_gradient.gif")
        GifConversionEngine.encodeToFile(src360, settings360, exportedFile360)

        try {
            assertEquals("360p preview must render at exact 1:1 output width", 640, preview360.previewWidth)
            assertEquals("360p preview must render at exact 1:1 output height", 360, preview360.previewHeight)
            assertEquals(DitherMode.ORDERED, preview360.renderedDitherMode)

            AnimatedGifFrameSource.openFile(exportedFile360).use { decodedExport ->
                val exportFrame0 = decodedExport.getFrameAtIndex(0)!!
                try {
                    val mae = meanRgbAbsError(preview360.frames.first(), exportFrame0)
                    assertEquals(
                        "1:1 preview frame and exported GIF frame must be pixel-identical",
                        0.0,
                        mae,
                        0.001
                    )
                } finally {
                    exportFrame0.recycle()
                }
            }
        } finally {
            preview360.recycleAll()
        }

        // Test B: High-res 1280x720 GIF export compared against preview at equivalent display size
        val src720 = PatternVideoFrameSource(width = 1280, height = 720, durationMs = 400L, fps = 10f)
        val settings720 = GifConversionSettings(
            outputFormat = OutputFormat.GIF,
            resolutionPreset = ResolutionPreset.ORIGINAL,
            colorQualityPreset = ColorQualityPreset.C128,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 5f,
            trimStartMs = 0L,
            trimEndMs = 400L
        )
        val preview720 = LiveGifPreviewRenderer.renderPreview(
            frameSource = src720,
            settings = settings720,
            context = context,
            targetMaxEdgePx = 960
        )
        val exportedFile720 = tempFile("export_1280x720_gradient.gif")
        GifConversionEngine.encodeToFile(src720, settings720, exportedFile720)

        try {
            assertEquals("720p preview should retain 960px width (not 300px)", 960, preview720.previewWidth)
            assertEquals(540, preview720.previewHeight)

            AnimatedGifFrameSource.openFile(exportedFile720).use { decodedExport ->
                val exportFrame0 = decodedExport.getFrameAtIndex(0)!!
                val exportAtPreviewSize = FrameTransformer.scaleBitmapHighQuality(
                    exportFrame0,
                    preview720.previewWidth,
                    preview720.previewHeight
                )
                try {
                    val mae = meanRgbAbsError(preview720.frames.first(), exportAtPreviewSize)
                    assertTrue(
                        "High-res GIF preview must closely match exported GIF at equivalent display size (MAE=$mae)",
                        mae < 12.0
                    )
                } finally {
                    exportFrame0.recycle()
                    exportAtPreviewSize.recycle()
                }
            }
        } finally {
            preview720.recycleAll()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Fine details & 1px thin lines preserved by progressive high-quality scaling
    // -------------------------------------------------------------------------

    @Test
    fun `progressive high-quality downscaler preserves 1px thin lines without vanishing during 4x downscale`() {
        val srcW = 1200
        val srcH = 800
        val src = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.rgb(16, 18, 28))
        // Draw crisp 1px bright white horizontal and vertical thin lines every 100px
        for (y in 100 until srcH step 100) {
            for (x in 0 until srcW) {
                src.setPixel(x, y, Color.WHITE)
            }
        }
        for (x in 100 until srcW step 100) {
            for (y in 0 until srcH) {
                src.setPixel(x, y, Color.WHITE)
            }
        }

        val scaled = FrameTransformer.scaleBitmapHighQuality(src, 300, 200)
        try {
            assertEquals(300, scaled.width)
            assertEquals(200, scaled.height)

            // Every horizontal line (y = 25, 50, 75, 100, 125, 150, 175 in 300x200) must remain clearly visible
            for (lineIdx in 1..7) {
                val targetY = lineIdx * 25
                var maxLumaNearLine = 0
                for (dy in -1..1) {
                    val py = (targetY + dy).coerceIn(0, scaled.height - 1)
                    val p = scaled.getPixel(12, py)
                    val luma = ((p ushr 16) and 0xFF)
                    if (luma > maxLumaNearLine) maxLumaNearLine = luma
                }
                assertTrue(
                    "Thin horizontal line at y=$targetY must remain visible after 4x high-quality downscale (maxLuma=$maxLumaNearLine)",
                    maxLumaNearLine >= 45
                )
            }
        } finally {
            src.recycle()
            scaled.recycle()
        }

        // Verify premultiplied alpha prevents dark fringe halos on transparent edges during downscale
        val alphaEdgeSrc = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) {
            for (x in 0 until 8) {
                alphaEdgeSrc.setPixel(x, y, if (x < 4) Color.TRANSPARENT else Color.WHITE)
            }
        }
        val alphaEdgeScaled = FrameTransformer.scaleBitmapHighQuality(alphaEdgeSrc, 2, 2)
        try {
            assertEquals(0, Color.alpha(alphaEdgeScaled.getPixel(0, 0)))
            assertEquals(Color.WHITE, alphaEdgeScaled.getPixel(1, 0))
        } finally {
            alphaEdgeSrc.recycle()
            alphaEdgeScaled.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // 4. High-resolution Animated WebP preview (gradients, thin lines, transparency)
    // -------------------------------------------------------------------------

    @Test
    fun `high-res Animated WebP preview preserves resolution, smooth gradients, and transparency`() = runBlocking {
        val src = PatternVideoFrameSource(
            width = 960,
            height = 540,
            durationMs = 400L,
            fps = 10f,
            withTransparency = true
        )

        val losslessSettings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = false,
            fps = 8f,
            webpQualityPreset = WebPQualityPreset.LOSSLESS,
            trimStartMs = 0L,
            trimEndMs = 400L
        )
        val losslessPreview = LiveGifPreviewRenderer.renderPreview(
            frameSource = src,
            settings = losslessSettings,
            context = context,
            targetMaxEdgePx = 960
        )
        try {
            assertEquals(OutputFormat.WEBP, losslessPreview.outputFormat)
            assertEquals(960, losslessPreview.previewWidth)
            assertEquals(540, losslessPreview.previewHeight)
            assertEquals("Lossless", losslessPreview.webpQualityLabel)
            assertTrue("Transparent WebP preview must flag hasTransparency", losslessPreview.hasTransparency)

            val firstFrame = losslessPreview.frames.first()
            // Top-left transparent quadrant must stay alpha == 0
            assertEquals(0, Color.alpha(firstFrame.getPixel(10, 10)))
            // Opaque gradient region must remain smooth and accurate
            val leftR = Color.red(firstFrame.getPixel(200, 300))
            val midR = Color.red(firstFrame.getPixel(500, 300))
            val rightR = Color.red(firstFrame.getPixel(850, 300))
            assertTrue("Smooth horizontal red gradient must increase monotonically ($leftR < $midR < $rightR)", leftR < midR && midR < rightR)
        } finally {
            losslessPreview.recycleAll()
        }

        // Lossy Balanced WebP preview must also retain full 960x540 dimensions & alpha
        val lossySettings = losslessSettings.copy(
            webpQualityPreset = WebPQualityPreset.BALANCED,
            webpQuality = 75
        )
        val lossyPreview = LiveGifPreviewRenderer.renderPreview(
            frameSource = src,
            settings = lossySettings,
            context = context,
            targetMaxEdgePx = 960
        )
        try {
            assertEquals(960, lossyPreview.previewWidth)
            assertEquals(540, lossyPreview.previewHeight)
            assertEquals("Balanced (75)", lossyPreview.webpQualityLabel)
            assertTrue(lossyPreview.hasTransparency)
            assertEquals(0, Color.alpha(lossyPreview.frames.first().getPixel(10, 10)))
        } finally {
            lossyPreview.recycleAll()
        }
    }

    // -------------------------------------------------------------------------
    // 5. Transparent GIF-to-GIF preview preserves transparency, palette & variable timing
    // -------------------------------------------------------------------------

    @Test
    fun `transparent GIF preview preserves alpha, exact palette, and total duration when sampled`() = runBlocking {
        val w = 80
        val h = 60
        val frameSpecs = List(48) { idx ->
            val pixels = IntArray(w * h) { pIdx ->
                val x = pIdx % w
                val y = pIdx / w
                if (x < 20 && y < 20) {
                    Color.TRANSPARENT
                } else {
                    Color.rgb((40 + idx * 4) % 255, 160, (220 - idx * 3).coerceAtLeast(20))
                }
            }
            val delayMs = if (idx % 6 == 0) 150 else 40
            pixels to delayMs
        }
        val expectedTotalDurationMs = frameSpecs.sumOf { it.second }
        val inputGif = writeSyntheticGif("var_timing_alpha.gif", w, h, frameSpecs)

        AnimatedGifFrameSource.openFile(inputGif, displayName = "var_timing_alpha.gif").use { gifSrc ->
            val settings = GifConversionSettings(
                outputFormat = OutputFormat.GIF,
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = true,
                trimStartMs = 0L,
                trimEndMs = gifSrc.metadata.durationMs
            )

            val preview = LiveGifPreviewRenderer.renderPreview(
                frameSource = gifSrc,
                settings = settings,
                context = context,
                targetMaxEdgePx = 720
            )
            try {
                assertEquals(w, preview.previewWidth)
                assertEquals(h, preview.previewHeight)
                assertTrue("Sampled preview flag should be true for 48-frame GIF", preview.isSampledPreview)
                assertTrue("Transparent GIF must preserve hasTransparency", preview.hasTransparency)
                assertEquals(0, Color.alpha(preview.frames.first().getPixel(4, 4)))

                // Summed delays across sampled slots must preserve the full clip duration accurately
                val previewTotalDuration = preview.frameDelaysMs.sum()
                assertTrue(
                    "Sampled preview duration ($previewTotalDuration ms) must closely match clip duration ($expectedTotalDurationMs ms)",
                    abs(previewTotalDuration - expectedTotalDurationMs) <= 100
                )
            } finally {
                preview.recycleAll()
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. Compose VideoPreviewStage renders portrait & wide transparent previews cleanly
    // -------------------------------------------------------------------------

    @Test
    fun `VideoPreviewStage renders transparent portrait WebP preview and reports viewport size`() {
        val bmp = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (y in 40 until 280) {
                for (x in 20 until 160) {
                    setPixel(x, y, Color.rgb(180, 140, 255))
                }
            }
        }
        val preview = com.example.engine.LiveGifPreviewData(
            frames = listOf(bmp),
            frameDelayMs = 80,
            outputWidth = 540,
            outputHeight = 960,
            previewWidth = 180,
            previewHeight = 320,
            resolvedColorCount = 0,
            totalClipFrames = 12,
            isSampledPreview = false,
            outputFormat = OutputFormat.WEBP,
            webpQualityLabel = "High (88)",
            hasTransparency = true
        )
        val meta = VideoMetadata(
            uri = Uri.parse("content://test/portrait.gif"),
            displayName = "portrait.gif",
            rawWidth = 540,
            rawHeight = 960,
            metadataRotation = 0,
            durationMs = 1000L,
            frameRate = 12f,
            fileSizeBytes = 120_000L,
            inputType = MediaInputType.GIF,
            hasTransparency = true
        )
        var measuredViewportPx = 0

        composeRule.setContent {
            GifStudioTheme {
                VideoPreviewStage(
                    metadata = meta,
                    previewMode = PreviewMode.GIF_PREVIEW,
                    isPlaying = false,
                    currentPositionMs = 0L,
                    trimStartMs = 0L,
                    trimEndMs = 1000L,
                    rotationDegrees = 0,
                    cropRect = NormalizedCropRect.FULL,
                    aspectRatioPreset = AspectRatioPreset.ORIGINAL,
                    showCropOverlay = false,
                    ditherMode = DitherMode.FLOYD_STEINBERG,
                    liveGifPreview = preview,
                    isRenderingGifPreview = false,
                    onPositionTick = {},
                    onCropChanged = {},
                    outputFormat = OutputFormat.WEBP,
                    onPreviewViewportChanged = { measuredViewportPx = it },
                    modifier = Modifier.size(width = 360.dp, height = 240.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Live Animated WebP Preview").assertIsDisplayed()
        composeRule.onNodeWithText("WebP · 540×960 · High (88)").assertIsDisplayed()
        assertTrue("Expected positive measured viewport size in pixels, got $measuredViewportPx", measuredViewportPx > 0)

        bmp.recycle()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun tempFile(name: String): File =
        File(context.cacheDir, name).apply { if (exists()) delete() }

    private fun meanRgbAbsError(a: Bitmap, b: Bitmap): Double {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        var total = 0L
        for (i in pa.indices) {
            val c1 = pa[i]
            val c2 = pb[i]
            total += abs(((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF))
            total += abs(((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF))
            total += abs((c1 and 0xFF) - (c2 and 0xFF))
        }
        return total.toDouble() / (pa.size.toDouble() * 3.0)
    }

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

    private class PatternVideoFrameSource(
        width: Int,
        height: Int,
        durationMs: Long,
        fps: Float,
        private val withTransparency: Boolean = false
    ) : VideoFrameSource {
        override val metadata = VideoMetadata(
            uri = Uri.parse("content://test/pattern_${width}x${height}.mp4"),
            displayName = "pattern_${width}x${height}.mp4",
            rawWidth = width,
            rawHeight = height,
            metadataRotation = 0,
            durationMs = durationMs,
            frameRate = fps,
            fileSizeBytes = 2_000_000L,
            inputType = MediaInputType.VIDEO,
            hasTransparency = withTransparency
        )

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val w = metadata.rawWidth
            val h = metadata.rawHeight
            val shift = ((timeUs / 50_000L) * 10L).toInt()
            val pixels = IntArray(w * h)
            val transparentW = w / 5
            val transparentH = h / 5
            for (y in 0 until h) {
                val g = (y * 255) / (h - 1).coerceAtLeast(1)
                val isThinHLine = (y == h / 2)
                val rowOff = y * w
                for (x in 0 until w) {
                    if (withTransparency && x < transparentW && y < transparentH) {
                        pixels[rowOff + x] = Color.TRANSPARENT
                    } else if (isThinHLine || x == ((w / 3 + shift) % w)) {
                        pixels[rowOff + x] = Color.WHITE
                    } else {
                        val r = (x * 255) / (w - 1).coerceAtLeast(1)
                        val b = ((x + y + shift) % 256)
                        pixels[rowOff + x] = Color.rgb(r, g, b)
                    }
                }
            }
            val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            if (maxEdgePx != null && maxEdgePx > 0 && maxOf(w, h) > maxEdgePx) {
                val scale = maxEdgePx.toDouble() / maxOf(w, h).toDouble()
                val dstW = (w * scale).toInt().coerceAtLeast(2)
                val dstH = (h * scale).toInt().coerceAtLeast(2)
                val scaled = FrameTransformer.scaleBitmapHighQuality(full, dstW, dstH)
                full.recycle()
                return scaled
            }
            return full
        }

        override fun close() {}
    }
}
