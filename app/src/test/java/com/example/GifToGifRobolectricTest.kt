package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConversionHistoryEntity
import com.example.data.GifStudioDatabase
import com.example.data.HistoryRepository
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.FrameQuantizer
import com.example.engine.GalleryExporter
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.GifGeometryCalculator
import com.example.engine.GifSizeEstimator
import com.example.engine.LiveGifPreviewRenderer
import com.example.engine.MediaFrameSourceOpener
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.ResolutionPreset
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Comprehensive Robolectric verification for GIF-to-GIF editing support across the full
 * pipeline: animated GIF87a/89a decoding, variable timing, alpha transparency, disposal
 * methods (0/1/2/3), partial sub-rect compositing, 4-pass interlacing, trimming, cropping,
 * rotation, unrestricted custom resolution, quantization, dithering, reverse/ping-pong/speed,
 * live preview, file-size estimation, Gallery export & Room history, cancellation, and mixed
 * video+GIF batch conversion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GifToGifRobolectricTest {

    private fun tempFile(name: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply { if (exists()) delete() }
    }

    /**
     * Builds a real animated GIF89a file with arbitrary per-frame pixel arrays, delays, and
     * optional transparency using [Gif89aStreamEncoder].
     */
    private fun writeSyntheticGif(
        fileName: String,
        width: Int,
        height: Int,
        frames: List<Pair<IntArray, Int>>, // (ARGB pixels, delayMs)
        loopCount: Int = 0,
        disposal: Int = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND
    ): File {
        val file = tempFile(fileName)
        FileOutputStream(file).use { fos ->
            val encoder = Gif89aStreamEncoder(fos, width, height, loopCount = loopCount)
            for ((pixels, delayMs) in frames) {
                val q = FrameQuantizer.quantizeAndDither(
                    sourcePixels = pixels,
                    width = width,
                    height = height,
                    requestedColorCount = 64,
                    ditherMode = DitherMode.NONE
                )
                encoder.addFrame(q, delayMs = delayMs, disposalMethod = disposal)
            }
            encoder.finish()
        }
        return file
    }

    /**
     * Minimal uncompressed-LZW (clear-code-only) GIF89a writer for crafting low-level spec test
     * fixtures with exact Global Color Tables, partial sub-rectangles, disposal methods 0/1/2/3,
     * transparency indices, NETSCAPE2.0 loop counts, and 4-pass interlacing.
     */
    private class RawGifBuilder(
        width: Int,
        height: Int,
        gctRgb: IntArray, // power of 2 length (2..256), 0x00RRGGBB
        bgIndex: Int = 0,
        loopCount: Int? = 0
    ) {
        private val out = ByteArrayOutputStream()

        init {
            out.write("GIF89a".toByteArray(Charsets.US_ASCII))
            writeU16(width)
            writeU16(height)
            var tableBits = 1
            while ((1 shl tableBits) < gctRgb.size) tableBits++
            val lsdPacked = 0x80 or ((tableBits - 1) shl 4) or (tableBits - 1)
            out.write(lsdPacked)
            out.write(bgIndex and 0xFF)
            out.write(0)
            for (i in 0 until (1 shl tableBits)) {
                val rgb = if (i < gctRgb.size) gctRgb[i] else 0
                out.write((rgb ushr 16) and 0xFF)
                out.write((rgb ushr 8) and 0xFF)
                out.write(rgb and 0xFF)
            }
            if (loopCount != null) {
                out.write(0x21)
                out.write(0xFF)
                out.write(11)
                out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
                out.write(3)
                out.write(1)
                writeU16(loopCount)
                out.write(0)
            }
        }

        fun addFrame(
            left: Int,
            top: Int,
            frameW: Int,
            frameH: Int,
            indices: ByteArray,
            delayMs: Int,
            disposalMethod: Int,
            transparentIndex: Int? = null,
            interlaced: Boolean = false
        ) {
            // GCE
            out.write(0x21)
            out.write(0xF9)
            out.write(4)
            val packed = ((disposalMethod and 0x07) shl 2) or (if (transparentIndex != null) 1 else 0)
            out.write(packed)
            writeU16((delayMs / 10).coerceAtLeast(1))
            out.write((transparentIndex ?: 0) and 0xFF)
            out.write(0)

            // Image Descriptor
            out.write(0x2C)
            writeU16(left)
            writeU16(top)
            writeU16(frameW)
            writeU16(frameH)
            out.write(if (interlaced) 0x40 else 0x00)

            // Valid LZW stream using clear codes so each literal fits in minCodeSize+1 bits
            val minCodeSize = 4 // supports indices 0..15 cleanly
            val clearCode = 1 shl minCodeSize
            val eoiCode = clearCode + 1
            val codeBits = minCodeSize + 1 // 5 bits

            out.write(minCodeSize)
            val bytes = ByteArrayOutputStream()
            var bitBuf = 0
            var bitCount = 0
            fun emit(code: Int) {
                bitBuf = bitBuf or (code shl bitCount)
                bitCount += codeBits
                while (bitCount >= 8) {
                    bytes.write(bitBuf and 0xFF)
                    bitBuf = bitBuf ushr 8
                    bitCount -= 8
                }
            }

            emit(clearCode)
            var sinceClear = 0
            for (b in indices) {
                emit(b.toInt() and 0x0F)
                sinceClear++
                // Re-emit clearCode before decoder bumps codeSize at available==32
                if (sinceClear >= 12) {
                    emit(clearCode)
                    sinceClear = 0
                }
            }
            emit(eoiCode)
            if (bitCount > 0) bytes.write(bitBuf and 0xFF)

            val compressed = bytes.toByteArray()
            var pos = 0
            while (pos < compressed.size) {
                val chunk = minOf(255, compressed.size - pos)
                out.write(chunk)
                out.write(compressed, pos, chunk)
                pos += chunk
            }
            out.write(0)
        }

        fun writeTo(file: File): File {
            out.write(0x3B)
            file.writeBytes(out.toByteArray())
            return file
        }

        private fun writeU16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
    }

    private fun solidPixels(w: Int, h: Int, argb: Int): IntArray = IntArray(w * h) { argb }

    private fun gradientMotionPixels(w: Int, h: Int, frameIdx: Int, totalFrames: Int): IntArray {
        val progress = frameIdx.toFloat() / (totalFrames - 1).coerceAtLeast(1)
        val pixels = IntArray(w * h)
        val cx = w * (0.2f + 0.6f * progress)
        val cy = h * 0.5f
        val rSq = (minOf(w, h) * 0.22f).let { it * it }
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                pixels[idx++] = if (dx * dx + dy * dy <= rSq) {
                    Color.argb(255, 220, 80, 240)
                } else {
                    val r = ((x.toFloat() / w) * 200f + progress * 40f).toInt().coerceIn(0, 255)
                    val g = ((y.toFloat() / h) * 200f).toInt().coerceIn(0, 255)
                    val b = (220 - (x.toFloat() / w) * 180f).toInt().coerceIn(0, 255)
                    Color.argb(255, r, g, b)
                }
            }
        }
        return pixels
    }

    private fun transparentShapePixels(w: Int, h: Int, xOffset: Int, fgColor: Int): IntArray {
        val pixels = IntArray(w * h) { 0x00000000 }
        val boxW = w / 3
        val boxH = h / 2
        val y0 = h / 4
        for (y in y0 until (y0 + boxH).coerceAtMost(h)) {
            for (x in xOffset until (xOffset + boxW).coerceAtMost(w)) {
                pixels[y * w + x] = fgColor
            }
        }
        return pixels
    }

    // -------------------------------------------------------------------------
    // 1, 2, 3: Multi-frame GIF import, frame count, delays, variable timing,
    //          ContentResolver opening, and manual FPS override
    // -------------------------------------------------------------------------

    @Test
    fun `importing multi-frame GIF reads exact dimensions frame count loop count and variable delays`() = runBlocking {
        val delays = listOf(40, 120, 300, 80)
        val frames = listOf(
            solidPixels(64, 48, Color.RED) to delays[0],
            solidPixels(64, 48, Color.GREEN) to delays[1],
            solidPixels(64, 48, Color.BLUE) to delays[2],
            solidPixels(64, 48, Color.YELLOW) to delays[3]
        )
        val gifFile = writeSyntheticGif("variable_timing.gif", 64, 48, frames, loopCount = 3)

        // Open via MediaFrameSourceOpener + ContentResolver (tests real Android Uri path)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.example.test.gifprovider"
        val gifUri = Uri.parse("content://$authority/variable_timing.gif")
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(gifFile, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun getType(uri: Uri): String = "image/gif"
            override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
            override fun insert(u: Uri, v: ContentValues?): Uri? = null
            override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
            override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)

        assertTrue(AnimatedGifFrameSource.isGifUri(context, gifUri))

        val opened = MediaFrameSourceOpener.open(context, gifUri)
        assertTrue(opened is AnimatedGifFrameSource)
        try {
            val meta = opened.metadata
            assertEquals(MediaInputType.GIF, meta.inputType)
            assertTrue(meta.isGifInput)
            assertEquals(64, meta.orientedWidth)
            assertEquals(48, meta.orientedHeight)
            assertEquals(3, meta.loopCount)
            assertEquals(delays, meta.frameDelaysMs)
            assertEquals(listOf(0L, 40_000L, 160_000L, 460_000L), meta.frameTimestampsUs)
            assertEquals(540L, meta.durationMs)
            assertTrue(meta.isVariableFrameRate)

            // Verify each frame's decoded color is distinct and accurate
            val expectedColors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            for (i in expectedColors.indices) {
                val bmp = opened.getFrameAtTimeUs(meta.frameTimestampsUs[i])
                assertNotNull(bmp)
                assertEquals(expectedColors[i], bmp!!.getPixel(32, 24))
                bmp.recycle()
            }

            // Default GIF-to-GIF export preserves exact variable frame timing without dropping/forcing FPS
            val outDefault = tempFile("out_preserve_timing.gif")
            val defaultStats = GifConversionEngine.encodeToFile(
                frameSource = opened,
                settings = GifConversionSettings.DEFAULT.copy(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs,
                    loopCount = meta.loopCount
                ),
                outputFile = outDefault
            )
            assertEquals(4, defaultStats.frameCount)
            assertEquals(540L, defaultStats.durationMs)

            AnimatedGifFrameSource.openFile(outDefault).use { redecoded ->
                assertEquals(4, redecoded.frames.size)
                assertEquals(delays, redecoded.metadata.frameDelaysMs)
                assertEquals(3, redecoded.metadata.loopCount)
                assertEquals(64, redecoded.metadata.orientedWidth)
                assertEquals(48, redecoded.metadata.orientedHeight)
            }

            // Manual FPS override (e.g. 10 FPS over 540ms -> ~5 frames at 100ms each)
            val outManualFps = tempFile("out_manual_fps.gif")
            val manualStats = GifConversionEngine.encodeToFile(
                frameSource = opened,
                settings = GifConversionSettings.DEFAULT.copy(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    useOriginalFps = false,
                    fps = 10f,
                    trimStartMs = 0L,
                    trimEndMs = meta.durationMs
                ),
                outputFile = outManualFps
            )
            AnimatedGifFrameSource.openFile(outManualFps).use { redecoded ->
                assertEquals(manualStats.frameCount, redecoded.frames.size)
                assertTrue(
                    "Manual 10 FPS delays should be exact multiples of 100ms after lossless deduplication (got ${redecoded.metadata.frameDelaysMs})",
                    redecoded.metadata.frameDelaysMs.all { it >= 100 && it % 100 == 0 }
                )
                assertEquals(500L, redecoded.metadata.frameDelaysMs.sum().toLong())
            }
        } finally {
            opened.close()
        }
    }

    // -------------------------------------------------------------------------
    // 4: Preserving transparency in transparent GIFs end-to-end
    // -------------------------------------------------------------------------

    @Test
    fun `transparent animated GIF preserves alpha end-to-end without black background or halos`() = runBlocking {
        val w = 60
        val h = 40
        val f0 = transparentShapePixels(w, h, xOffset = 4, fgColor = Color.argb(255, 255, 60, 60))
        val f1 = transparentShapePixels(w, h, xOffset = 24, fgColor = Color.argb(255, 60, 220, 120))
        val f2 = transparentShapePixels(w, h, xOffset = 36, fgColor = Color.argb(255, 80, 130, 255))

        val inputFile = writeSyntheticGif(
            fileName = "transparent_in.gif",
            width = w,
            height = h,
            frames = listOf(f0 to 80, f1 to 100, f2 to 120),
            disposal = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND
        )

        AnimatedGifFrameSource.openFile(inputFile).use { src ->
            assertTrue(src.metadata.hasTransparency)

            // Verify decoded frame 0 has transparent background and opaque foreground
            val d0 = src.getFrameAtTimeUs(0L)!!
            assertEquals("Corner pixel must be transparent", 0, Color.alpha(d0.getPixel(0, 0)))
            assertEquals("Foreground box pixel must be opaque", 255, Color.alpha(d0.getPixel(10, 20)))
            d0.recycle()

            // Verify frame 1 cleared frame 0's box (DISPOSAL_RESTORE_BACKGROUND) instead of ghosting
            val d1 = src.getFrameAtTimeUs(80_000L)!!
            assertEquals("Previous box location should be cleared to transparent", 0, Color.alpha(d1.getPixel(10, 20)))
            assertEquals("New box location should be opaque", 255, Color.alpha(d1.getPixel(30, 20)))
            d1.recycle()

            // Verify all three dithering modes preserve transparency cleanly when re-encoding
            for (dither in listOf(DitherMode.NONE, DitherMode.ORDERED, DitherMode.FLOYD_STEINBERG)) {
                val outFile = tempFile("transparent_out_${dither.name}.gif")
                val stats = GifConversionEngine.encodeToFile(
                    frameSource = src,
                    settings = GifConversionSettings.DEFAULT.copy(
                        resolutionPreset = ResolutionPreset.ORIGINAL,
                        colorQualityPreset = ColorQualityPreset.C32,
                        ditherMode = dither,
                        trimStartMs = 0L,
                        trimEndMs = src.metadata.durationMs
                    ),
                    outputFile = outFile
                )
                assertEquals(3, stats.frameCount)

                AnimatedGifFrameSource.openFile(outFile).use { redecoded ->
                    assertTrue("Re-encoded GIF ($dither) must declare transparency", redecoded.metadata.hasTransparency)
                    assertEquals(listOf(80, 100, 120), redecoded.metadata.frameDelaysMs)

                    val outF0 = redecoded.getFrameAtTimeUs(0L)!!
                    val outF1 = redecoded.getFrameAtTimeUs(80_000L)!!
                    val outF2 = redecoded.getFrameAtTimeUs(180_000L)!!

                    assertEquals("Frame 0 corner must stay transparent ($dither)", 0, Color.alpha(outF0.getPixel(0, 0)))
                    assertEquals("Frame 0 shape must stay opaque ($dither)", 255, Color.alpha(outF0.getPixel(10, 20)))

                    assertEquals("Frame 1 must clear frame 0 area ($dither)", 0, Color.alpha(outF1.getPixel(10, 20)))
                    assertEquals("Frame 1 shape must stay opaque ($dither)", 255, Color.alpha(outF1.getPixel(30, 20)))

                    assertEquals("Frame 2 must clear frame 1 area ($dither)", 0, Color.alpha(outF2.getPixel(30, 20)))
                    assertEquals("Frame 2 shape must stay opaque ($dither)", 255, Color.alpha(outF2.getPixel(42, 20)))

                    outF0.recycle()
                    outF1.recycle()
                    outF2.recycle()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5: Frame disposal methods (1, 2, 3), partial sub-rects, interlacing,
    //    single-frame GIFs, and invalid GIF error handling
    // -------------------------------------------------------------------------

    @Test
    fun `decoder handles partial sub-rects disposal methods 1 2 3 interlacing single-frame and invalid GIFs`() {
        // Palette: 0=transparent/bg, 1=Red, 2=Green, 3=Blue, 4=Yellow
        val palette = intArrayOf(
            0x000000, // 0
            0xFF0000, // 1 Red
            0x00FF00, // 2 Green
            0x0000FF, // 3 Blue
            0xFFFF00, // 4 Yellow
            0x000000, 0x000000, 0x000000
        )
        val builder = RawGifBuilder(width = 16, height = 16, gctRgb = palette, bgIndex = 0, loopCount = 0)

        // Frame 0: full 16x16 Red, DISPOSAL_DO_NOT_DISPOSE (1)
        builder.addFrame(0, 0, 16, 16, ByteArray(16 * 16) { 1 }, delayMs = 100, disposalMethod = 1, transparentIndex = 0)

        // Frame 1: partial 8x8 Green at (4,4), DISPOSAL_RESTORE_PREVIOUS (3)
        builder.addFrame(4, 4, 8, 8, ByteArray(8 * 8) { 2 }, delayMs = 100, disposalMethod = 3, transparentIndex = 0)

        // Frame 2: partial 4x4 Blue at (0,0), DISPOSAL_RESTORE_BACKGROUND (2)
        // Because Frame 1 had RESTORE_PREVIOUS, (4,4) must revert to Red before Frame 2 draws!
        builder.addFrame(0, 0, 4, 4, ByteArray(4 * 4) { 3 }, delayMs = 100, disposalMethod = 2, transparentIndex = 0)

        // Frame 3: interlaced 16x16 where top half (rows 0..7) = transparent, bottom half (rows 8..15) = Yellow
        // Because Frame 2 had RESTORE_BACKGROUND, (0,0) must be cleared to transparent!
        val logicalRows = Array(16) { y -> ByteArray(16) { if (y < 8) 0 else 4 } }
        val interlacedData = ByteArrayOutputStream()
        val passStarts = intArrayOf(0, 4, 2, 1)
        val passSteps = intArrayOf(8, 8, 4, 2)
        for (pass in 0..3) {
            var y = passStarts[pass]
            while (y < 16) {
                interlacedData.write(logicalRows[y])
                y += passSteps[pass]
            }
        }
        builder.addFrame(
            left = 0, top = 0, frameW = 16, frameH = 16,
            indices = interlacedData.toByteArray(),
            delayMs = 100,
            disposalMethod = 1,
            transparentIndex = 0,
            interlaced = true
        )

        val complexFile = builder.writeTo(tempFile("disposal_interlace.gif"))

        AnimatedGifFrameSource.openFile(complexFile).use { src ->
            assertEquals(4, src.frames.size)

            val b0 = src.getFrameAtTimeUs(0L)!!
            assertEquals(Color.RED, b0.getPixel(0, 0))
            assertEquals(Color.RED, b0.getPixel(6, 6))
            b0.recycle()

            val b1 = src.getFrameAtTimeUs(100_000L)!!
            assertEquals("Outside sub-rect stays Red (DO_NOT_DISPOSE)", Color.RED, b1.getPixel(0, 0))
            assertEquals("Inside sub-rect is Green", Color.GREEN, b1.getPixel(6, 6))
            b1.recycle()

            val b2 = src.getFrameAtTimeUs(200_000L)!!
            assertEquals("Frame 2 sub-rect at (0,0) is Blue", Color.BLUE, b2.getPixel(1, 1))
            assertEquals("Frame 1 area restored to Red (RESTORE_PREVIOUS)", Color.RED, b2.getPixel(6, 6))
            b2.recycle()

            val b3 = src.getFrameAtTimeUs(300_000L)!!
            assertEquals("Frame 2 area cleared to transparent (RESTORE_BACKGROUND)", 0, Color.alpha(b3.getPixel(1, 1)))
            assertEquals("Non-cleared top half keeps Red through transparent pixels", Color.RED, b3.getPixel(10, 2))
            assertEquals("Interlaced bottom half decoded to Yellow", Color.YELLOW, b3.getPixel(10, 12))
            b3.recycle()

            // Random backward jump must still composite accurately via reset/checkpoint
            val b1Again = src.getFrameAtTimeUs(100_000L)!!
            assertEquals(Color.GREEN, b1Again.getPixel(6, 6))
            assertEquals(Color.RED, b1Again.getPixel(0, 0))
            b1Again.recycle()
        }

        // Single-frame GIF must decode cleanly
        val singleGif = writeSyntheticGif("single_frame.gif", 32, 24, listOf(solidPixels(32, 24, Color.CYAN) to 100))
        AnimatedGifFrameSource.openFile(singleGif).use { single ->
            assertEquals(1, single.frames.size)
            assertEquals(32, single.metadata.orientedWidth)
            assertEquals(24, single.metadata.orientedHeight)
            val bmp = single.getFrameAtTimeUs(0L)!!
            assertEquals(Color.CYAN, bmp.getPixel(16, 12))
            bmp.recycle()
        }

        // Invalid / non-GIF file must throw meaningful IllegalArgumentException
        val corruptFile = tempFile("not_a_gif.gif").apply { writeBytes("NOT_A_GIF_HEADER_12345".toByteArray()) }
        try {
            AnimatedGifFrameSource.openFile(corruptFile)
            fail("Expected IllegalArgumentException for invalid GIF header")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("GIF", ignoreCase = true))
        }
    }

    // -------------------------------------------------------------------------
    // 6, 7, 8: Trimming, cropping, rotation, and unrestricted resolution on GIFs
    // -------------------------------------------------------------------------

    @Test
    fun `trimming cropping rotation and unrestricted custom resolution work accurately on animated GIFs`() = runBlocking {
        // Create 5-frame 640x360 GIF with distinct top-left vs bottom-right quadrant colors & variable delays
        val w = 640
        val h = 360
        val delays = listOf(100, 150, 200, 250, 300) // cumulative: 0, 100, 250, 450, 700, 1000ms
        val frameColors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        val frames = frameColors.mapIndexed { i, c ->
            val px = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    // Left half = frame color, Right half = White
                    px[y * w + x] = if (x < w / 2) c else Color.WHITE
                }
            }
            px to delays[i]
        }
        val inputGif = writeSyntheticGif("edit_ops_in.gif", w, h, frames)

        AnimatedGifFrameSource.openFile(inputGif).use { src ->
            // 6. Trim from 150ms to 650ms -> overlaps frames 1 (100ms remaining), 2 (200ms), 3 (200ms clipped) = 500ms
            val trimmedOut = tempFile("trimmed_gif.gif")
            val trimmedStats = GifConversionEngine.encodeToFile(
                frameSource = src,
                settings = GifConversionSettings.DEFAULT.copy(
                    resolutionPreset = ResolutionPreset.P240,
                    trimStartMs = 150L,
                    trimEndMs = 650L
                ),
                outputFile = trimmedOut
            )
            assertEquals(3, trimmedStats.frameCount)
            assertEquals(500L, trimmedStats.durationMs)
            AnimatedGifFrameSource.openFile(trimmedOut).use { decoded ->
                assertEquals(listOf(100, 200, 200), decoded.metadata.frameDelaysMs)
                assertEquals(426, decoded.metadata.orientedWidth)
                assertEquals(240, decoded.metadata.orientedHeight)
            }

            // 7. Crop 1:1 + Rotate 90° clockwise (left half Red rotates to top half Red!)
            val crop1to1 = NormalizedCropRect.centeredForAspectRatio(360, 640, AspectRatioPreset.SQUARE_1_1.ratio)
            val rotCropOut = tempFile("rot_crop_gif.gif")
            val rotStats = GifConversionEngine.encodeToFile(
                frameSource = src,
                settings = GifConversionSettings.DEFAULT.copy(
                    resolutionPreset = ResolutionPreset.CUSTOM,
                    customResolutionShortEdge = 200,
                    rotationDegrees = 90,
                    cropRect = crop1to1,
                    aspectRatioPreset = AspectRatioPreset.SQUARE_1_1,
                    trimStartMs = 0L,
                    trimEndMs = 250L
                ),
                outputFile = rotCropOut
            )
            assertEquals(200, rotStats.width)
            assertEquals(200, rotStats.height)
            AnimatedGifFrameSource.openFile(rotCropOut).use { decoded ->
                assertEquals(200, decoded.metadata.orientedWidth)
                assertEquals(200, decoded.metadata.orientedHeight)
                val f0 = decoded.getFrameAtTimeUs(0L)!!
                // After 90° CW rotation, original left half (Red) is now at the top half!
                val topPixel = f0.getPixel(100, 20)
                val bottomPixel = f0.getPixel(100, 180)
                assertTrue("Top pixel after 90° rotation should be red-dominant", Color.red(topPixel) > 200 && Color.green(topPixel) < 60)
                assertTrue("Bottom pixel after 90° rotation should be white", Color.red(bottomPixel) > 200 && Color.green(bottomPixel) > 200 && Color.blue(bottomPixel) > 200)
                f0.recycle()
            }

            // 8. Unrestricted custom resolution > 480p on larger GIF, never upscaling beyond source
            val bigGif = writeSyntheticGif(
                "big_source.gif", 960, 720,
                listOf(solidPixels(960, 720, Color.RED) to 80, solidPixels(960, 720, Color.BLUE) to 80)
            )
            AnimatedGifFrameSource.openFile(bigGif).use { bigSrc ->
                val custom600Out = tempFile("custom_600p.gif")
                val customStats = GifConversionEngine.encodeToFile(
                    frameSource = bigSrc,
                    settings = GifConversionSettings.DEFAULT.copy(
                        resolutionPreset = ResolutionPreset.CUSTOM,
                        customResolutionShortEdge = 600,
                        trimStartMs = 0L,
                        trimEndMs = bigSrc.metadata.durationMs
                    ),
                    outputFile = custom600Out
                )
                assertEquals(800, customStats.width)
                assertEquals(600, customStats.height)

                // Requesting 1200p on 960x720 source must NOT upscale beyond 960x720
                val clampedDims = GifGeometryCalculator.calculateOutputDimensions(
                    bigSrc.metadata.orientedWidth,
                    bigSrc.metadata.orientedHeight,
                    GifConversionSettings(resolutionPreset = ResolutionPreset.CUSTOM, customResolutionShortEdge = 1200)
                )
                assertEquals(960, clampedDims.width)
                assertEquals(720, clampedDims.height)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 9, 10: Quantization, dithering, reverse, ping-pong, and speed on GIFs
    // -------------------------------------------------------------------------

    @Test
    fun `quantization dithering reverse ping-pong and speed work accurately on animated GIFs`() = runBlocking {
        val delays = listOf(60, 120, 200, 100)
        val frames = List(4) { i -> gradientMotionPixels(120, 90, i, 4) to delays[i] }
        val inputGif = writeSyntheticGif("motion_grad.gif", 120, 90, frames)

        AnimatedGifFrameSource.openFile(inputGif).use { src ->
            // 9. Quantization & dithering modes
            val qNoneFile = tempFile("gif_dither_none.gif")
            val qOrdFile = tempFile("gif_dither_ordered.gif")
            val qFsFile = tempFile("gif_dither_fs.gif")

            val base = GifConversionSettings.DEFAULT.copy(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C16,
                trimStartMs = 0L,
                trimEndMs = src.metadata.durationMs
            )
            GifConversionEngine.encodeToFile(src, base.copy(ditherMode = DitherMode.NONE), qNoneFile)
            GifConversionEngine.encodeToFile(src, base.copy(ditherMode = DitherMode.ORDERED), qOrdFile)
            GifConversionEngine.encodeToFile(src, base.copy(ditherMode = DitherMode.FLOYD_STEINBERG), qFsFile)

            fun firstFramePixels(file: File): IntArray = AnimatedGifFrameSource.openFile(file).use { s ->
                val b = s.getFrameAtTimeUs(0L)!!
                IntArray(b.width * b.height).also {
                    b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
                    b.recycle()
                }
            }
            val pNone = firstFramePixels(qNoneFile)
            val pOrd = firstFramePixels(qOrdFile)
            val pFs = firstFramePixels(qFsFile)
            assertTrue(FrameQuantizer.countUniqueColors(pNone) <= 16)
            assertTrue(FrameQuantizer.countUniqueColors(pOrd) <= 16)
            assertTrue(FrameQuantizer.countUniqueColors(pFs) <= 16)
            assertTrue(FrameQuantizer.meanPixelDifference(pNone, pOrd) > 0.5)
            assertTrue(FrameQuantizer.meanPixelDifference(pNone, pFs) > 0.5)

            // 10a. Reverse preserves each frame's individual delay in reversed order
            val revFile = tempFile("gif_reverse.gif")
            GifConversionEngine.encodeToFile(src, base.copy(reverse = true), revFile)
            AnimatedGifFrameSource.openFile(revFile).use { rev ->
                assertEquals(delays.reversed(), rev.metadata.frameDelaysMs)
            }

            // 10b. Ping-pong on 4 frames [0,1,2,3] -> [0,1,2,3,2,1] = 6 frames, no duplicate turnaround
            val ppFile = tempFile("gif_pingpong.gif")
            GifConversionEngine.encodeToFile(src, base.copy(pingPong = true), ppFile)
            AnimatedGifFrameSource.openFile(ppFile).use { pp ->
                assertEquals(6, pp.frames.size)
                assertEquals(listOf(60, 120, 200, 100, 200, 120), pp.metadata.frameDelaysMs)
            }

            // 10c. Speed 2x halves delays; Speed 0.5x doubles delays
            val fastFile = tempFile("gif_2x.gif")
            GifConversionEngine.encodeToFile(src, base.copy(speed = 2f), fastFile)
            AnimatedGifFrameSource.openFile(fastFile).use { fast ->
                assertEquals(listOf(30, 60, 100, 50), fast.metadata.frameDelaysMs)
            }

            val slowFile = tempFile("gif_halfx.gif")
            GifConversionEngine.encodeToFile(src, base.copy(speed = 0.5f), slowFile)
            AnimatedGifFrameSource.openFile(slowFile).use { slow ->
                assertEquals(listOf(120, 240, 400, 200), slow.metadata.frameDelaysMs)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 11, 12: Live GIF preview + file-size estimation on animated GIF inputs
    // -------------------------------------------------------------------------

    @Test
    fun `live gif preview filmstrip and file size estimator work accurately on animated GIF inputs`() = runBlocking {
        val frames = List(6) { i -> gradientMotionPixels(180, 120, i, 6) to (60 + i * 20) }
        val inputGif = writeSyntheticGif("preview_est_in.gif", 180, 120, frames)

        AnimatedGifFrameSource.openFile(inputGif).use { src ->
            val settings = GifConversionSettings.DEFAULT.copy(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C64,
                ditherMode = DitherMode.ORDERED,
                trimStartMs = 0L,
                trimEndMs = src.metadata.durationMs
            )

            // 11. Live GIF preview & filmstrip
            val preview = LiveGifPreviewRenderer.renderPreview(src, settings)
            assertEquals(180, preview.outputWidth)
            assertEquals(120, preview.outputHeight)
            assertEquals(6, preview.frames.size)
            assertEquals(src.metadata.frameDelaysMs, preview.frameDelaysMs)
            assertTrue(preview.resolvedColorCount <= 64)
            preview.recycleAll()

            val strip = LiveGifPreviewRenderer.extractFilmstrip(src, count = 4)
            assertEquals(4, strip.size)
            strip.forEach { it.recycle() }

            // 12. File-size estimation matches actual exported GIF size closely
            val estimate = GifSizeEstimator.estimate(src, settings)
            val actualOut = tempFile("actual_gif_est.gif")
            val actualStats = GifConversionEngine.encodeToFile(src, settings, actualOut)

            val ratio = estimate.estimatedBytes.toDouble() / actualStats.fileSizeBytes.toDouble()
            assertTrue(
                "GIF-to-GIF size estimate ratio should be realistic (0.4..2.2), got $ratio (est=${estimate.estimatedBytes}, actual=${actualStats.fileSizeBytes})",
                ratio in 0.4..2.2
            )

            val smallerEstimate = GifSizeEstimator.estimate(
                src,
                settings.copy(colorQualityPreset = ColorQualityPreset.C16, ditherMode = DitherMode.NONE)
            )
            assertTrue(smallerEstimate.estimatedBytes < estimate.estimatedBytes)
        }
    }

    // -------------------------------------------------------------------------
    // 13: GIF-to-GIF export + Gallery MediaStore saving + PNG transparent
    //     thumbnail + Room history persistence
    // -------------------------------------------------------------------------

    @Test
    fun `gif-to-gif gallery export saves valid animated GIF with PNG transparent thumbnail and Room history`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val w = 80
        val h = 60
        val inputGif = writeSyntheticGif(
            fileName = "sticker_anim.gif",
            width = w,
            height = h,
            frames = listOf(
                transparentShapePixels(w, h, 5, Color.argb(255, 255, 100, 160)) to 90,
                transparentShapePixels(w, h, 35, Color.argb(255, 140, 220, 255)) to 110
            )
        )

        val tempExport = tempFile("gif_gallery_export.gif")
        val stats = AnimatedGifFrameSource.openFile(inputGif).use { src ->
            GifConversionEngine.encodeToFile(
                frameSource = src,
                settings = GifConversionSettings.DEFAULT.copy(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    trimStartMs = 0L,
                    trimEndMs = src.metadata.durationMs
                ),
                outputFile = tempExport
            )
        }

        val writtenMediaFile = tempFile("mediastore_gif_to_gif.gif")
        val insertedValues = mutableListOf<ContentValues>()
        val fakeMediaProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                if (values != null) insertedValues.add(ContentValues(values))
                return Uri.parse("content://media/external/images/media/2026")
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (!writtenMediaFile.exists()) writtenMediaFile.createNewFile()
                return ParcelFileDescriptor.open(
                    writtenMediaFile,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
                )
            }
            override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 1
            override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 1
            override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
            override fun getType(uri: Uri): String = "image/gif"
        }
        fakeMediaProvider.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, fakeMediaProvider)

        val thumb = AnimatedGifFrameSource.openFile(tempExport).use { it.getFrameAtTimeUs(0L)!! }
        val saved = GalleryExporter.saveGifToGallery(
            context = context,
            tempGifFile = tempExport,
            sourceName = "sticker_anim.gif",
            stats = stats,
            thumbnailBitmap = thumb
        )
        thumb.recycle()

        assertEquals("image/gif", insertedValues.first().getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/Nepi GIF Lab", insertedValues.first().getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertTrue("Transparent GIF history thumbnail should be PNG", saved.thumbnailPath?.endsWith(".png") == true)
        assertTrue(File(saved.thumbnailPath!!).exists())

        // Verify saved MediaStore file is a valid animated transparent GIF with both frames & delays
        AnimatedGifFrameSource.openFile(writtenMediaFile).use { verified ->
            assertEquals(2, verified.frames.size)
            assertEquals(listOf(90, 110), verified.metadata.frameDelaysMs)
            assertTrue(verified.metadata.hasTransparency)
        }

        // Verify Room history records the GIF-to-GIF export cleanly
        val db = Room.inMemoryDatabaseBuilder(context, GifStudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = HistoryRepository(db.historyDao())
        val rowId = repo.recordExport(
            ConversionHistoryEntity(
                contentUri = saved.contentUri.toString(),
                fileName = saved.fileName,
                thumbnailPath = saved.thumbnailPath,
                fileSizeBytes = stats.fileSizeBytes,
                width = stats.width,
                height = stats.height,
                fps = stats.fps,
                durationMs = stats.durationMs,
                frameCount = stats.frameCount,
                colorCount = stats.effectiveColorCount,
                ditherModeName = stats.ditherMode.label,
                sourceVideoName = "sticker_anim.gif"
            )
        )
        val entries = repo.allHistory.first()
        assertEquals(1, entries.size)
        assertEquals("sticker_anim.gif", entries.first().sourceVideoName)
        assertEquals(rowId, entries.first().id)
        db.close()
    }

    // -------------------------------------------------------------------------
    // 14: Cancelling a GIF-to-GIF conversion cleanly deletes partial file
    // -------------------------------------------------------------------------

    @Test
    fun `cooperative cancellation aborts GIF-to-GIF conversion and deletes partial output file`() = runBlocking {
        val frames = List(30) { i -> gradientMotionPixels(160, 120, i, 30) to 50 }
        val inputGif = writeSyntheticGif("long_cancel_in.gif", 160, 120, frames)
        val outFile = tempFile("cancelled_gif_to_gif.gif")
        var cancelledCaught = false

        AnimatedGifFrameSource.openFile(inputGif).use { src ->
            val job = launch {
                try {
                    GifConversionEngine.encodeToFile(
                        frameSource = src,
                        settings = GifConversionSettings.DEFAULT.copy(
                            resolutionPreset = ResolutionPreset.ORIGINAL,
                            trimStartMs = 0L,
                            trimEndMs = src.metadata.durationMs
                        ),
                        outputFile = outFile,
                        onProgress = { progress ->
                            if (progress.currentFrame >= 3) {
                                this.cancel()
                            }
                        }
                    )
                } catch (_: CancellationException) {
                    cancelledCaught = true
                }
            }
            job.join()
        }

        assertTrue("CancellationException must trigger during GIF-to-GIF conversion", cancelledCaught)
        assertFalse("Partial output file must be deleted on cancellation", outFile.exists())
    }

    // -------------------------------------------------------------------------
    // 15: Mixed Video + GIF batch conversion
    // -------------------------------------------------------------------------

    @Test
    fun `mixed video and GIF batch conversion exports valid animated GIFs for every item`() = runBlocking {
        val syntheticVideo = object : VideoFrameSource {
            override val metadata = VideoMetadata(
                uri = Uri.parse("content://test/clip.mp4"),
                displayName = "clip.mp4",
                rawWidth = 240,
                rawHeight = 160,
                metadataRotation = 0,
                durationMs = 600L,
                frameRate = 10f,
                fileSizeBytes = 100_000L,
                inputType = MediaInputType.VIDEO
            )
            override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
                val frameIdx = (timeUs / 100_000L).toInt().coerceIn(0, 5)
                val px = gradientMotionPixels(240, 160, frameIdx, 6)
                return Bitmap.createBitmap(px, 240, 160, Bitmap.Config.ARGB_8888)
            }
            override fun close() = Unit
        }

        val gifInputFile = writeSyntheticGif(
            fileName = "batch_anim.gif",
            width = 160,
            height = 120,
            frames = listOf(
                transparentShapePixels(160, 120, 10, Color.argb(255, 255, 80, 80)) to 70,
                transparentShapePixels(160, 120, 60, Color.argb(255, 80, 200, 255)) to 130,
                transparentShapePixels(160, 120, 90, Color.argb(255, 160, 255, 100)) to 100
            )
        )

        val sharedSettings = GifConversionSettings.DEFAULT.copy(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            colorQualityPreset = ColorQualityPreset.C32,
            ditherMode = DitherMode.ORDERED
        )

        AnimatedGifFrameSource.openFile(gifInputFile).use { gifSource ->
            val batchSources: List<VideoFrameSource> = listOf(syntheticVideo, gifSource)
            val outputs = mutableListOf<File>()

            for ((idx, src) in batchSources.withIndex()) {
                val out = tempFile("mixed_batch_$idx.gif")
                GifConversionEngine.encodeToFile(
                    frameSource = src,
                    settings = sharedSettings.copy(
                        trimStartMs = 0L,
                        trimEndMs = src.metadata.durationMs,
                        loopCount = src.metadata.loopCount
                    ),
                    outputFile = out
                )
                outputs.add(out)
            }

            assertEquals(2, outputs.size)

            // Verify video output GIF
            AnimatedGifFrameSource.openFile(outputs[0]).use { vidOut ->
                assertEquals(240, vidOut.metadata.orientedWidth)
                assertEquals(160, vidOut.metadata.orientedHeight)
                assertEquals(6, vidOut.frames.size)
                assertFalse(vidOut.metadata.hasTransparency)
            }

            // Verify GIF-to-GIF output preserved dimensions, variable timing, and transparency
            AnimatedGifFrameSource.openFile(outputs[1]).use { gifOut ->
                assertEquals(160, gifOut.metadata.orientedWidth)
                assertEquals(120, gifOut.metadata.orientedHeight)
                assertEquals(3, gifOut.frames.size)
                assertEquals(listOf(70, 130, 100), gifOut.metadata.frameDelaysMs)
                assertTrue(gifOut.metadata.hasTransparency)
            }
        }
    }
}
