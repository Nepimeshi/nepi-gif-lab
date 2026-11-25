package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.ColorQualityPreset
import com.example.engine.ConversionProgress
import com.example.engine.DitherMode
import com.example.engine.FrameQuantizer
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.LiveGifPreviewRenderer
import com.example.engine.ResolutionPreset
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies global palette quantization, temporal dithering stability, zero per-frame
 * Local Color Tables, color count limits, preview-vs-export consistency, GIF-to-GIF
 * global palette behavior, and bounded representative sampling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalPaletteConsistencyRobolectricTest {

    /**
     * Synthetic video where the top half is a rich static multi-tone gradient (identical RGB
     * across every frame) while the bottom half injects a strong moving, color-shifting object.
     * With independent per-frame palettes, the bottom-half color shift skews Median Cut splits
     * and causes visible background color flickering on the static top half. With a shared
     * global palette, the static top half must stay completely stable.
     */
    private class FlickerStressVideoSource(
        override val metadata: VideoMetadata
    ) : VideoFrameSource {

        val decodeCalls = AtomicInteger(0)

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            decodeCalls.incrementAndGet()
            val w = metadata.orientedWidth.coerceAtMost(maxEdgePx ?: metadata.orientedWidth).coerceAtLeast(16)
            val h = metadata.orientedHeight.coerceAtMost(maxEdgePx ?: metadata.orientedHeight).coerceAtLeast(16)
            val t = (timeUs.toFloat() / (metadata.durationMs.coerceAtLeast(1L) * 1000f)).coerceIn(0f, 1f)

            val pixels = IntArray(w * h)
            val splitY = h / 2

            // Moving vivid block in bottom half that drastically shifts frame histogram per frame
            val blockColors = intArrayOf(
                Color.rgb(250, 30, 30),
                Color.rgb(30, 245, 45),
                Color.rgb(250, 235, 25),
                Color.rgb(30, 220, 250),
                Color.rgb(245, 40, 220),
                Color.rgb(255, 130, 20)
            )
            val frameColorIdx = ((t * (blockColors.size - 1)).toInt()).coerceIn(0, blockColors.lastIndex)
            val movingColor = blockColors[frameColorIdx]
            val bxStart = (t * w * 0.45f).toInt()
            val bxEnd = (bxStart + (w * 0.45f).toInt()).coerceAtMost(w)

            var idx = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (y < splitY) {
                        // Subtle static multi-tone gradient in top half (never changes between frames)
                        val r = 42 + (x * 110) / w.coerceAtLeast(1)
                        val g = 54 + (y * 95) / splitY.coerceAtLeast(1)
                        val b = 88 + ((w - 1 - x) * 85) / w.coerceAtLeast(1)
                        pixels[idx++] = Color.rgb(r, g, b)
                    } else {
                        if (x in bxStart until bxEnd) {
                            pixels[idx++] = movingColor
                        } else {
                            val r = 28 + (x * 35) / w.coerceAtLeast(1)
                            val g = 26 + ((y - splitY) * 40) / (h - splitY).coerceAtLeast(1)
                            val b = 44 + (x * 30) / w.coerceAtLeast(1)
                            pixels[idx++] = Color.rgb(r, g, b)
                        }
                    }
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        override fun close() = Unit
    }

    private fun createTempGifFile(name: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply { if (exists()) delete() }
    }

    private data class BinaryGifPaletteAudit(
        val hasGlobalColorTable: Boolean,
        val globalColorTableEntries: Int,
        val frameCount: Int,
        val framesWithLocalColorTable: Int
    )

    /**
     * Parses raw GIF89a binary stream to verify whether a Global Color Table is present and
     * count how many individual frame Image Descriptors carry a Local Color Table.
     */
    private fun auditGifBinaryColorTables(bytes: ByteArray): BinaryGifPaletteAudit {
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        val lsdPacked = bytes[10].toInt() and 0xFF
        val hasGct = (lsdPacked and 0x80) != 0
        val gctEntries = if (hasGct) 1 shl ((lsdPacked and 0x07) + 1) else 0
        var pos = 13 + if (hasGct) gctEntries * 3 else 0

        var frameCount = 0
        var lctFrames = 0

        fun skipSubBlocks() {
            while (pos < bytes.size) {
                val blockLen = bytes[pos++].toInt() and 0xFF
                if (blockLen == 0) break
                pos += blockLen
            }
        }

        while (pos < bytes.size) {
            when (bytes[pos++].toInt() and 0xFF) {
                0x3B -> break // Trailer
                0x21 -> { // Extension
                    if (pos >= bytes.size) break
                    pos++ // label
                    skipSubBlocks()
                }
                0x2C -> { // Image Descriptor
                    if (pos + 9 > bytes.size) break
                    val imgPacked = bytes[pos + 8].toInt() and 0xFF
                    pos += 9
                    val hasLct = (imgPacked and 0x80) != 0
                    if (hasLct) {
                        lctFrames++
                        val lctSize = 1 shl ((imgPacked and 0x07) + 1)
                        pos += lctSize * 3
                    }
                    pos++ // LZW minimum code size
                    skipSubBlocks()
                    frameCount++
                }
                else -> break
            }
        }

        return BinaryGifPaletteAudit(
            hasGlobalColorTable = hasGct,
            globalColorTableEntries = gctEntries,
            frameCount = frameCount,
            framesWithLocalColorTable = lctFrames
        )
    }

    private fun extractTopHalfPixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val halfH = bitmap.height / 2
        val pixels = IntArray(w * halfH)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, halfH)
        return pixels
    }

    private fun countChangedPixels(a: IntArray, b: IntArray): Int {
        var changed = 0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) changed++
        }
        return changed
    }

    @Test
    fun `exported GIF uses single Global Color Table with zero Local Color Tables`() = runBlocking {
        val src = FlickerStressVideoSource(
            VideoMetadata(Uri.parse("content://test/gct.mp4"), "gct.mp4", 120, 80, 0, 1200L, 10f, 100_000L)
        )
        val outFile = createTempGifFile("single_gct_only.gif")
        val stats = GifConversionEngine.encodeToFile(
            frameSource = src,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C64,
                ditherMode = DitherMode.ORDERED,
                useOriginalFps = false,
                fps = 10f,
                trimStartMs = 0L,
                trimEndMs = 1200L
            ),
            outputFile = outFile
        )

        val audit = auditGifBinaryColorTables(outFile.readBytes())
        assertTrue("Exported GIF must have a Global Color Table", audit.hasGlobalColorTable)
        assertTrue("Global Color Table must have <= 64 entries for C64", audit.globalColorTableEntries <= 64)
        assertEquals(stats.frameCount, audit.frameCount)
        assertEquals(
            "Every frame must reuse the Global Color Table with ZERO Local Color Tables",
            0,
            audit.framesWithLocalColorTable
        )

        AnimatedGifFrameSource.openFile(outFile).use { decoded ->
            assertTrue(decoded.hasGlobalColorTable)
            assertNotNull(decoded.globalColorTableCopy)
            assertEquals(stats.frameCount, decoded.frameCount)
        }
    }

    @Test
    fun `global palette eliminates color flickering on static regions across consecutive frames`() = runBlocking {
        val src = FlickerStressVideoSource(
            VideoMetadata(Uri.parse("content://test/flicker.mp4"), "flicker.mp4", 96, 64, 0, 1000L, 10f, 80_000L)
        )

        // Baseline: verify independent per-frame quantization WOULD flicker on the static top half
        var independentChangedTotal = 0
        var prevIndependentTop: IntArray? = null
        for (i in 0 until 6) {
            val bmp = src.getFrameAtTimeUs(i * 180_000L)
            val fullPixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(fullPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            val q = FrameQuantizer.quantizeAndDither(fullPixels, 96, 64, 32, DitherMode.NONE)
            val topHalf = q.rgbPixels.copyOf(96 * 32)
            prevIndependentTop?.let { prev ->
                independentChangedTotal += countChangedPixels(prev, topHalf)
            }
            prevIndependentTop = topHalf
        }
        assertTrue(
            "Stress fixture must trigger measurable flicker under independent per-frame palettes (got $independentChangedTotal)",
            independentChangedTotal > 200
        )

        // Now verify our real exported GIF89a with shared global palette under NONE, ORDERED, and FLOYD_STEINBERG
        for (mode in listOf(DitherMode.NONE, DitherMode.ORDERED, DitherMode.FLOYD_STEINBERG)) {
            val outFile = createTempGifFile("no_flicker_${mode.name.lowercase()}.gif")
            GifConversionEngine.encodeToFile(
                frameSource = src,
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    colorQualityPreset = ColorQualityPreset.C32,
                    ditherMode = mode,
                    useOriginalFps = false,
                    fps = 10f,
                    trimStartMs = 0L,
                    trimEndMs = 1000L
                ),
                outputFile = outFile
            )

            AnimatedGifFrameSource.openFile(outFile).use { decoded ->
                assertTrue(decoded.frameCount >= 8)
                var totalStaticDiff = 0
                var prevTop: IntArray? = null
                for (f in 0 until decoded.frameCount) {
                    val bmp = decoded.getFrameAtTimeUs(decoded.metadata.frameTimestampsUs[f])!!
                    val top = extractTopHalfPixels(bmp)
                    bmp.recycle()
                    prevTop?.let { prev ->
                        totalStaticDiff += countChangedPixels(prev, top)
                    }
                    prevTop = top
                }
                // For NONE, ORDERED, AND FLOYD_STEINBERG (since top half is scanned before bottom half),
                // every pixel in the static top half must be 100% identical across all consecutive frames!
                assertEquals(
                    "Static top-half pixels must have ZERO color flickering under $mode",
                    0,
                    totalStaticDiff
                )
            }
        }
    }

    @Test
    fun `global palette strictly respects Auto and custom color counts from 2 to 256`() = runBlocking {
        val src = FlickerStressVideoSource(
            VideoMetadata(Uri.parse("content://test/colors.mp4"), "colors.mp4", 96, 64, 0, 800L, 8f, 60_000L)
        )

        val customCounts = listOf(2, 12, 37, 96, 256)
        for (requested in customCounts) {
            val outFile = createTempGifFile("custom_colors_$requested.gif")
            val stats = GifConversionEngine.encodeToFile(
                frameSource = src,
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    colorQualityPreset = ColorQualityPreset.CUSTOM,
                    customColorCount = requested,
                    ditherMode = DitherMode.NONE,
                    useOriginalFps = false,
                    fps = 5f,
                    trimStartMs = 0L,
                    trimEndMs = 800L
                ),
                outputFile = outFile
            )
            assertTrue("Stats color count (${stats.effectiveColorCount}) must be <= $requested", stats.effectiveColorCount <= requested)

            // Collect union of unique RGB colors across ALL decoded frames of the GIF
            val allAnimationColors = HashSet<Int>()
            AnimatedGifFrameSource.openFile(outFile).use { decoded ->
                for (tUs in decoded.metadata.frameTimestampsUs) {
                    val bmp = decoded.getFrameAtTimeUs(tUs)!!
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp.recycle()
                    for (c in px) allAnimationColors.add(c and 0x00FFFFFF)
                }
            }
            assertTrue(
                "Entire animation must use <= $requested unique colors across all frames (found ${allAnimationColors.size})",
                allAnimationColors.size <= requested
            )
        }

        // Auto color selection
        val autoFile = createTempGifFile("auto_colors.gif")
        val autoStats = GifConversionEngine.encodeToFile(
            frameSource = src,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.AUTO,
                ditherMode = DitherMode.ORDERED,
                useOriginalFps = false,
                fps = 5f,
                trimStartMs = 0L,
                trimEndMs = 800L
            ),
            outputFile = autoFile
        )
        assertTrue("Auto color count should be in 2..256 (got ${autoStats.effectiveColorCount})", autoStats.effectiveColorCount in 2..256)
        val audit = auditGifBinaryColorTables(autoFile.readBytes())
        assertTrue(audit.hasGlobalColorTable)
        assertEquals(0, audit.framesWithLocalColorTable)
    }

    @Test
    fun `live GIF preview and final export share consistent global palette and matching quantized frames`() = runBlocking {
        val src = FlickerStressVideoSource(
            VideoMetadata(Uri.parse("content://test/preview_match.mp4"), "preview_match.mp4", 120, 80, 0, 1000L, 10f, 90_000L)
        )
        val settings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            colorQualityPreset = ColorQualityPreset.C32,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 6f,
            trimStartMs = 0L,
            trimEndMs = 1000L
        )

        val preview = LiveGifPreviewRenderer.renderPreview(src, settings)
        val exportedFile = createTempGifFile("preview_vs_export.gif")
        GifConversionEngine.encodeToFile(src, settings, exportedFile)

        try {
            assertTrue("Preview must expose its shared global palette", preview.globalPaletteColors.isNotEmpty())
            assertTrue(preview.globalPaletteColors.size <= 32)

            AnimatedGifFrameSource.openFile(exportedFile).use { decoded ->
                val exportGct = decoded.globalColorTableCopy
                assertNotNull(exportGct)

                // Every palette color generated by Live Preview must match the exported Global Color Table
                for (i in preview.globalPaletteColors.indices) {
                    assertEquals(
                        "Palette entry $i must match between Live Preview and Final Export",
                        preview.globalPaletteColors[i] and 0x00FFFFFF,
                        exportGct!![i] and 0x00FFFFFF
                    )
                }

                // First frame pixels should match exactly because both use identical dimensions, global palette, and deterministic Bayer dithering
                val previewFirst = preview.frames.first()
                val exportFirst = decoded.getFrameAtTimeUs(decoded.metadata.frameTimestampsUs.first())!!
                val pPx = IntArray(previewFirst.width * previewFirst.height).also {
                    previewFirst.getPixels(it, 0, previewFirst.width, 0, 0, previewFirst.width, previewFirst.height)
                }
                val ePx = IntArray(exportFirst.width * exportFirst.height).also {
                    exportFirst.getPixels(it, 0, exportFirst.width, 0, 0, exportFirst.width, exportFirst.height)
                }
                exportFirst.recycle()

                assertEquals(
                    "Live Preview and Final Export must produce identical quantized pixels",
                    0,
                    countChangedPixels(pPx, ePx)
                )
            }
        } finally {
            preview.recycleAll()
        }
    }

    @Test
    fun `gif to gif re-encoding uses single Global Color Table without flicker and preserves transparency`() = runBlocking {
        // Build a 6-frame transparent input GIF with a static colored border and moving inner bar
        val w = 64
        val h = 48
        val inputGif = createTempGifFile("transparent_input.gif")
        val baos = ByteArrayOutputStream()
        val acc = FrameQuantizer.GlobalPaletteAccumulator()
        val rawFrames = (0 until 6).map { f ->
            val px = IntArray(w * h)
            val movingColors = intArrayOf(
                Color.rgb(250, 40, 40),
                Color.rgb(40, 240, 50),
                Color.rgb(40, 120, 250),
                Color.rgb(245, 220, 30),
                Color.rgb(220, 45, 235),
                Color.rgb(40, 235, 220)
            )
            for (y in 0 until h) {
                for (x in 0 until w) {
                    px[y * w + x] = when {
                        y < 8 -> Color.rgb(65 + x * 2, 80 + y * 4, 150) // Static colored header
                        y in 20..36 && x in (f * 6)..(f * 6 + 18) -> movingColors[f]
                        else -> Color.TRANSPARENT
                    }
                }
            }
            acc.addFramePixels(px, respectAlpha = true)
            px
        }
        val inPalette = acc.build(requestedColorCount = 32, forceTransparency = true)
        val encoder = Gif89aStreamEncoder(baos, w, h, loopCount = 0, globalPalette = inPalette)
        rawFrames.forEach { px ->
            val q = FrameQuantizer.quantizeWithPalette(px, w, h, inPalette, DitherMode.NONE)
            encoder.addFrame(q, delayMs = 80)
        }
        encoder.finish()
        inputGif.writeBytes(baos.toByteArray())

        // Re-encode via GifConversionEngine (GIF-to-GIF)
        val reencodedGif = createTempGifFile("transparent_reencoded.gif")
        AnimatedGifFrameSource.openFile(inputGif).use { gifSrc ->
            assertTrue(gifSrc.metadata.hasTransparency)
            GifConversionEngine.encodeToFile(
                frameSource = gifSrc,
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    colorQualityPreset = ColorQualityPreset.C32,
                    ditherMode = DitherMode.ORDERED,
                    useOriginalFps = true,
                    trimStartMs = 0L,
                    trimEndMs = gifSrc.metadata.durationMs
                ),
                outputFile = reencodedGif
            )
        }

        val audit = auditGifBinaryColorTables(reencodedGif.readBytes())
        assertTrue(audit.hasGlobalColorTable)
        assertEquals(0, audit.framesWithLocalColorTable)

        AnimatedGifFrameSource.openFile(reencodedGif).use { decoded ->
            assertTrue("Re-encoded GIF must preserve transparency", decoded.metadata.hasTransparency)
            assertEquals(6, decoded.frameCount)

            // Static header rows (y < 8) must have zero flicker across all 6 frames
            var prevHeader: IntArray? = null
            var headerDiffs = 0
            for (tUs in decoded.metadata.frameTimestampsUs) {
                val bmp = decoded.getFrameAtTimeUs(tUs)!!
                val header = IntArray(w * 8)
                bmp.getPixels(header, 0, w, 0, 0, w, 8)
                // Also confirm transparent corner stays transparent
                assertEquals(0, Color.alpha(bmp.getPixel(w - 2, h - 2)))
                bmp.recycle()
                prevHeader?.let { prev -> headerDiffs += countChangedPixels(prev, header) }
                prevHeader = header
            }
            assertEquals("Static header must not flicker across re-encoded GIF frames", 0, headerDiffs)
        }
    }

    @Test
    fun `long clip bounds representative palette sampling and emits truthful progress stages`() = runBlocking {
        // 4 seconds at 30 FPS = 120 frames
        val src = FlickerStressVideoSource(
            VideoMetadata(Uri.parse("content://test/long120.mp4"), "long120.mp4", 64, 48, 0, 4000L, 30f, 500_000L)
        )
        val outFile = createTempGifFile("long_120_frames.gif")
        val progressEvents = mutableListOf<ConversionProgress>()

        val stats = GifConversionEngine.encodeToFile(
            frameSource = src,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C64,
                ditherMode = DitherMode.NONE,
                useOriginalFps = true,
                trimStartMs = 0L,
                trimEndMs = 4000L
            ),
            outputFile = outFile,
            onProgress = { progressEvents.add(it) }
        )

        assertTrue("Expected deduplicated or full frame count in 1..120 (got ${stats.frameCount})", stats.frameCount in 1..120)
        assertEquals(4000L, stats.durationMs)
        // Representative sampling takes <= 20 frames + 120 sequential encoding frames <= 140 total decodes
        assertTrue(
            "Expected bounded representative sampling (<= 145 total frame decodes, got ${src.decodeCalls.get()})",
            src.decodeCalls.get() <= 145
        )
        assertTrue(progressEvents.any { it.stage.contains("Preparing", ignoreCase = true) && it.isIndeterminate })
        assertTrue(progressEvents.any { it.stage.contains("Quantizing", ignoreCase = true) })
        assertTrue(progressEvents.any { it.stage.contains("Encoding", ignoreCase = true) })
        assertTrue(progressEvents.any { it.stage.contains("Verifying", ignoreCase = true) })
        assertTrue(
            "Progress callback must never report 100% before export finishes",
            progressEvents.all { it.percent < 1f }
        )

        val audit = auditGifBinaryColorTables(outFile.readBytes())
        assertTrue(audit.hasGlobalColorTable)
        assertEquals(stats.frameCount, audit.frameCount)
        assertEquals(0, audit.framesWithLocalColorTable)
    }
}
