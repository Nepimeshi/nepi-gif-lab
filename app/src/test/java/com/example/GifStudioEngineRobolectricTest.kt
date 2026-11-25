package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConversionHistoryEntity
import com.example.data.GifStudioDatabase
import com.example.data.HistoryRepository
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.FrameQuantizer
import com.example.engine.GalleryExporter
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.GifGeometryCalculator
import com.example.engine.GifSizeEstimator
import com.example.engine.LiveGifPreviewRenderer
import com.example.engine.NormalizedCropRect
import com.example.engine.ResolutionPreset
import com.example.engine.StudioFormatters
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * End-to-end Robolectric verification of GIF Studio's actual native GIF89a conversion engine,
 * geometry/trim/crop/rotation, color quantization, dithering modes, FPS & speed, reverse &
 * ping-pong loops, live GIF preview, realistic file-size estimation, cancellation, Gallery
 * intents, and Room history persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GifStudioEngineRobolectricTest {

    /**
     * Deterministic synthetic video source generating smooth RGB gradients + time-varying motion
     * marker so every frame at timestamp T has distinct, verifiable visual content.
     */
    private class SyntheticVideoFrameSource(
        override val metadata: VideoMetadata
    ) : VideoFrameSource {

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val w = metadata.orientedWidth.coerceAtMost(maxEdgePx ?: metadata.orientedWidth).coerceAtLeast(8)
            val h = metadata.orientedHeight.coerceAtMost(maxEdgePx ?: metadata.orientedHeight).coerceAtLeast(8)
            val progress = (timeUs.toFloat() / (metadata.durationMs.coerceAtLeast(1L) * 1000f)).coerceIn(0f, 1f)

            val pixels = IntArray(w * h)
            val cx = w * (0.15f + 0.7f * progress)
            val cy = h * 0.5f
            val radius = minOf(w, h) * 0.16f
            val radiusSq = radius * radius

            var idx = 0
            for (y in 0 until h) {
                val fy = y.toFloat() / (h - 1).coerceAtLeast(1)
                for (x in 0 until w) {
                    val fx = x.toFloat() / (w - 1).coerceAtLeast(1)
                    val dx = x - cx
                    val dy = y - cy
                    pixels[idx++] = if (dx * dx + dy * dy <= radiusSq) {
                        Color.rgb(203, 178, 255)
                    } else {
                        val r = ((fx * 220f) + (progress * 35f)).toInt().coerceIn(0, 255)
                        val g = ((fy * 210f) + ((1f - progress) * 40f)).toInt().coerceIn(0, 255)
                        val b = (((1f - fx * 0.6f) * 200f) + (fy * 55f)).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    }
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        override fun close() = Unit
    }

    private fun readGifHeaderDimensions(gifFile: File): Pair<Int, Int> {
        val bytes = gifFile.readBytes()
        val magic = String(bytes, 0, 6, Charsets.US_ASCII)
        assertEquals("GIF89a", magic)
        val bb = ByteBuffer.wrap(bytes, 6, 4).order(ByteOrder.LITTLE_ENDIAN)
        val width = bb.short.toInt() and 0xFFFF
        val height = bb.short.toInt() and 0xFFFF
        return width to height
    }

    private fun createTempGifFile(name: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply { if (exists()) delete() }
    }

    @Test
    fun `landscape and portrait videos export genuine GIF89a with exact aspect and no upscaling`() = runBlocking {
        // Landscape 1280x720 at 240p -> 426x240
        val landscapeSrc = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/landscape.mp4"), "landscape.mp4", 1280, 720, 0, 1500L, 30f, 1_000_000L)
        )
        val landscapeFile = createTempGifFile("landscape_240p.gif")
        val landscapeStats = GifConversionEngine.encodeToFile(
            frameSource = landscapeSrc,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.P240,
                useOriginalFps = false,
                fps = 8f,
                trimStartMs = 0L,
                trimEndMs = 1000L
            ),
            outputFile = landscapeFile
        )
        val (lw, lh) = readGifHeaderDimensions(landscapeFile)
        assertEquals(426, lw)
        assertEquals(240, lh)
        assertEquals(lw, landscapeStats.width)
        assertEquals(lh, landscapeStats.height)
        assertTrue(landscapeFile.length() > 500L)

        // Portrait 1080x1920 (raw 1920x1080 with 90° metadata rotation) at 240p -> 240x426
        val portraitSrc = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/portrait.mp4"), "portrait.mp4", 1920, 1080, 90, 1500L, 30f, 1_000_000L)
        )
        val portraitFile = createTempGifFile("portrait_240p.gif")
        val portraitStats = GifConversionEngine.encodeToFile(
            frameSource = portraitSrc,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.P240,
                useOriginalFps = false,
                fps = 8f,
                trimStartMs = 0L,
                trimEndMs = 1000L
            ),
            outputFile = portraitFile
        )
        val (pw, ph) = readGifHeaderDimensions(portraitFile)
        assertEquals(240, pw)
        assertEquals(426, ph)
        assertEquals(pw, portraitStats.width)
        assertEquals(ph, portraitStats.height)

        // Small source 160x120 at 480p must NOT upscale
        val noUpscale = GifGeometryCalculator.calculateOutputDimensions(
            160, 120,
            GifConversionSettings(resolutionPreset = ResolutionPreset.P480)
        )
        assertEquals(160, noUpscale.width)
        assertEquals(120, noUpscale.height)
    }

    @Test
    fun `trimming crop rotation and timestamp parsing produce exact dimensions and duration`() = runBlocking {
        assertEquals(2500L, StudioFormatters.parseTimestampMs("00:02.50"))
        assertEquals("00:02.50", StudioFormatters.formatTimestamp(2500L))
        assertEquals(65_250L, StudioFormatters.parseTimestampMs("01:05.25"))

        val src = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/clip.mp4"), "clip.mp4", 640, 360, 0, 5000L, 30f, 500_000L)
        )

        // Crop 1:1 square + Rotate 90°, trimmed from 00:01.00 to 00:02.50 (1.5s) at 10 FPS
        val squareCrop = NormalizedCropRect.centeredForAspectRatio(360, 640, AspectRatioPreset.SQUARE_1_1.ratio)
        val outFile = createTempGifFile("cropped_trimmed.gif")
        val stats = GifConversionEngine.encodeToFile(
            frameSource = src,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 200,
                useOriginalFps = false,
                fps = 10f,
                speed = 1f,
                rotationDegrees = 90,
                cropRect = squareCrop,
                aspectRatioPreset = AspectRatioPreset.SQUARE_1_1,
                trimStartMs = 1000L,
                trimEndMs = 2500L
            ),
            outputFile = outFile
        )

        val (w, h) = readGifHeaderDimensions(outFile)
        assertEquals(200, w)
        assertEquals(200, h)
        assertEquals(15, stats.frameCount)
        assertEquals(1500L, stats.durationMs)
    }

    @Test
    fun `color quantization limits palette and dithering modes produce distinct outputs`() = runBlocking {
        val src = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/gradient.mp4"), "gradient.mp4", 160, 120, 0, 1000L, 30f, 100_000L)
        )
        val bmp = src.getFrameAtTimeUs(500_000L)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()

        val q16None = FrameQuantizer.quantizeAndDither(pixels, 160, 120, 16, DitherMode.NONE)
        val q16Ordered = FrameQuantizer.quantizeAndDither(pixels, 160, 120, 16, DitherMode.ORDERED)
        val q16Floyd = FrameQuantizer.quantizeAndDither(pixels, 160, 120, 16, DitherMode.FLOYD_STEINBERG)
        val q12Custom = FrameQuantizer.quantizeAndDither(pixels, 160, 120, 12, DitherMode.NONE)

        assertTrue(FrameQuantizer.countUniqueColors(q16None.rgbPixels) <= 16)
        assertTrue(FrameQuantizer.countUniqueColors(q16Ordered.rgbPixels) <= 16)
        assertTrue(FrameQuantizer.countUniqueColors(q16Floyd.rgbPixels) <= 16)
        assertTrue(FrameQuantizer.countUniqueColors(q12Custom.rgbPixels) <= 12)

        // All three dithering modes must actually differ on a smooth gradient
        assertTrue(FrameQuantizer.meanPixelDifference(q16None.rgbPixels, q16Ordered.rgbPixels) > 0.5)
        assertTrue(FrameQuantizer.meanPixelDifference(q16None.rgbPixels, q16Floyd.rgbPixels) > 0.5)
        assertTrue(FrameQuantizer.meanPixelDifference(q16Ordered.rgbPixels, q16Floyd.rgbPixels) > 0.5)
    }

    @Test
    fun `fps speed reverse and ping-pong schedules are mathematically accurate without duplicate endpoints`() {
        val fps5 = GifGeometryCalculator.buildFrameSchedule(0, 2000, 2000, fps = 5f, speed = 1f, reverse = false, pingPong = false)
        val fps20 = GifGeometryCalculator.buildFrameSchedule(0, 2000, 2000, fps = 20f, speed = 1f, reverse = false, pingPong = false)
        assertEquals(10, fps5.frames.size)
        assertEquals(40, fps20.frames.size)

        // 2x speed halves output duration
        val speed2x = GifGeometryCalculator.buildFrameSchedule(0, 2000, 2000, fps = 10f, speed = 2f, reverse = false, pingPong = false)
        assertEquals(1000L, speed2x.totalDurationMs)
        assertEquals(10, speed2x.frames.size)

        // 0.5x speed doubles output duration
        val speedHalf = GifGeometryCalculator.buildFrameSchedule(0, 2000, 2000, fps = 10f, speed = 0.5f, reverse = false, pingPong = false)
        assertEquals(4000L, speedHalf.totalDurationMs)
        assertEquals(40, speedHalf.frames.size)

        // Reverse flips timestamp sequence
        val forward = GifGeometryCalculator.buildFrameSchedule(0, 1000, 1000, fps = 5f, speed = 1f, reverse = false, pingPong = false)
        val reversed = GifGeometryCalculator.buildFrameSchedule(0, 1000, 1000, fps = 5f, speed = 1f, reverse = true, pingPong = false)
        assertEquals(forward.frames.map { it.sourceTimeUs }.reversed(), reversed.frames.map { it.sourceTimeUs })

        // Ping-pong on 5 forward frames [0,1,2,3,4] -> [0,1,2,3,4,3,2,1] = 8 frames, no adjacent duplicate endpoints
        val pingPong = GifGeometryCalculator.buildFrameSchedule(0, 1000, 1000, fps = 5f, speed = 1f, reverse = false, pingPong = true)
        assertEquals(8, pingPong.frames.size)
        for (i in 0 until pingPong.frames.size - 1) {
            assertNotEquals(
                "Ping-pong must not duplicate adjacent turnaround frames",
                pingPong.frames[i].sourceTimeUs,
                pingPong.frames[i + 1].sourceTimeUs
            )
        }
    }

    @Test
    fun `live gif preview and file size estimator match real encoded GIF output closely`() = runBlocking {
        val src = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/sample.mp4"), "sample.mp4", 320, 240, 0, 1200L, 30f, 300_000L)
        )
        val settings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.P240,
            colorQualityPreset = ColorQualityPreset.C64,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 10f,
            speed = 1f,
            pingPong = true,
            trimStartMs = 0L,
            trimEndMs = 1000L
        )

        val preview = LiveGifPreviewRenderer.renderPreview(src, settings)
        assertEquals(320, preview.outputWidth)
        assertEquals(240, preview.outputHeight)
        assertTrue(preview.frames.size >= 10)
        assertTrue(preview.resolvedColorCount <= 64)
        preview.recycleAll()

        val estimate = GifSizeEstimator.estimate(src, settings)
        val actualFile = createTempGifFile("estimated_vs_actual.gif")
        val actualStats = GifConversionEngine.encodeToFile(src, settings, actualFile)

        // Realistic estimate should be within reasonable factor of actual compressed GIF bytes
        val ratio = estimate.estimatedBytes.toDouble() / actualStats.fileSizeBytes.toDouble()
        assertTrue(
            "Expected realistic estimate ratio (0.4..2.2), got $ratio (est=${estimate.estimatedBytes}, actual=${actualStats.fileSizeBytes})",
            ratio in 0.4..2.2
        )

        // Reducing colors/FPS must reduce estimated file size
        val smallerEstimate = GifSizeEstimator.estimate(
            src,
            settings.copy(colorQualityPreset = ColorQualityPreset.C16, ditherMode = DitherMode.NONE, fps = 5f, pingPong = false)
        )
        assertTrue(smallerEstimate.estimatedBytes < estimate.estimatedBytes)
    }

    @Test
    fun `cooperative cancellation aborts encoding cleanly and deletes partial file`() = runBlocking {
        val src = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/long.mp4"), "long.mp4", 320, 240, 0, 8000L, 30f, 2_000_000L)
        )
        val outFile = createTempGifFile("cancelled_export.gif")
        var cancelledCaught = false

        val job = launch {
            try {
                GifConversionEngine.encodeToFile(
                    frameSource = src,
                    settings = GifConversionSettings(
                        resolutionPreset = ResolutionPreset.P240,
                        useOriginalFps = false,
                        fps = 15f,
                        trimStartMs = 0L,
                        trimEndMs = 8000L
                    ),
                    outputFile = outFile,
                    onProgress = { p ->
                        if (p.currentFrame >= 2) {
                            this.cancel()
                        }
                    }
                )
            } catch (_: CancellationException) {
                cancelledCaught = true
            }
        }
        job.join()

        assertTrue("CancellationException should trigger during encoding", cancelledCaught)
        assertTrue("Partial temp file should be cleaned up on cancellation", !outFile.exists())
    }

    @Test
    fun `room history persistence and share open intents work reliably`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, GifStudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = HistoryRepository(db.historyDao())

        val id = repo.recordExport(
            ConversionHistoryEntity(
                contentUri = "content://media/external/images/media/42",
                fileName = "GIFStudio_clip_20261121.gif",
                thumbnailPath = null,
                fileSizeBytes = 842_110L,
                width = 480,
                height = 270,
                fps = 15f,
                durationMs = 2400L,
                frameCount = 36,
                colorCount = 128,
                ditherModeName = "Floyd–Steinberg",
                sourceVideoName = "clip.mp4"
            )
        )
        val savedList = repo.allHistory.first()
        assertEquals(1, savedList.size)
        assertEquals("GIFStudio_clip_20261121.gif", savedList.first().fileName)

        repo.deleteItem(id)
        assertTrue(repo.allHistory.first().isEmpty())
        db.close()

        val uri = Uri.parse("content://media/external/images/media/42")
        val shareIntent = GalleryExporter.buildShareIntent(uri)
        val openIntent = GalleryExporter.buildOpenViewerIntent(uri)
        assertEquals(android.content.Intent.ACTION_CHOOSER, shareIntent.action)
        assertEquals(android.content.Intent.ACTION_VIEW, openIntent.action)
        assertEquals("image/gif", openIntent.type)
    }

    @Test
    fun `gallery exporter saves valid GIF89a via MediaStore with image_gif mime and pending publish`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/export.mp4"), "my clip!.mp4", 240, 160, 0, 800L, 30f, 100_000L)
        )
        val tempGif = createTempGifFile("gallery_export_test.gif")
        val stats = GifConversionEngine.encodeToFile(
            frameSource = src,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 6f,
                trimStartMs = 0L,
                trimEndMs = 800L
            ),
            outputFile = tempGif
        )

        val insertedValues = mutableListOf<android.content.ContentValues>()
        val updatedValues = mutableListOf<android.content.ContentValues>()
        val writtenMediaFile = File(context.cacheDir, "fake_mediastore_saved.gif").apply { if (exists()) delete() }
        var deleteCalled = false

        val fakeMediaProvider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri {
                if (values != null) insertedValues.add(android.content.ContentValues(values))
                return Uri.parse("content://media/external/images/media/999")
            }
            override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
                writtenMediaFile.parentFile?.mkdirs()
                if (!writtenMediaFile.exists()) writtenMediaFile.createNewFile()
                return android.os.ParcelFileDescriptor.open(
                    writtenMediaFile,
                    android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
                )
            }
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
                if (values != null) updatedValues.add(android.content.ContentValues(values))
                return 1
            }
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                deleteCalled = true
                writtenMediaFile.delete()
                return 1
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
            override fun getType(uri: Uri): String = "image/gif"
        }
        val providerInfo = android.content.pm.ProviderInfo().apply {
            authority = android.provider.MediaStore.AUTHORITY
        }
        fakeMediaProvider.attachInfo(context, providerInfo)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            android.provider.MediaStore.AUTHORITY,
            fakeMediaProvider
        )

        val thumb = src.getFrameAtTimeUs(0L, 120)
        val saved = GalleryExporter.saveGifToGallery(
            context = context,
            tempGifFile = tempGif,
            sourceName = "my clip!.mp4",
            stats = stats,
            thumbnailBitmap = thumb
        )
        thumb.recycle()

        assertEquals(Uri.parse("content://media/external/images/media/999"), saved.contentUri)
        assertTrue(saved.fileName.startsWith("NepiGifLab_my_clip_"))
        assertTrue(saved.fileName.endsWith(".gif"))
        assertEquals(1, insertedValues.size)
        assertEquals("image/gif", insertedValues.first().getAsString(android.provider.MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/Nepi GIF Lab", insertedValues.first().getAsString(android.provider.MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, insertedValues.first().getAsInteger(android.provider.MediaStore.Images.Media.IS_PENDING))
        assertEquals(1, updatedValues.size)
        assertEquals(0, updatedValues.first().getAsInteger(android.provider.MediaStore.Images.Media.IS_PENDING))

        // Verify the exact encoded GIF89a bytes were copied to MediaStore
        val savedBytes = writtenMediaFile.readBytes()
        assertEquals(tempGif.length().toInt(), savedBytes.size)
        assertEquals("GIF89a", String(savedBytes, 0, 6, Charsets.US_ASCII))
        val thumbPath = saved.thumbnailPath
        assertTrue("Thumbnail JPEG should be saved", thumbPath != null && File(thumbPath).exists())

        assertTrue(GalleryExporter.deleteGalleryFile(context, saved.contentUri.toString()))
        assertTrue(deleteCalled)
    }

    @Test
    fun `live gif preview reflects crop rotation palette dithering fps reverse and ping-pong changes`() = runBlocking {
        val src = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/preview.mp4"), "preview.mp4", 320, 180, 0, 1500L, 30f, 300_000L)
        )
        val baseSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            colorQualityPreset = ColorQualityPreset.C16,
            ditherMode = DitherMode.NONE,
            useOriginalFps = false,
            fps = 6f,
            speed = 1f,
            reverse = false,
            pingPong = false,
            trimStartMs = 0L,
            trimEndMs = 1000L
        )
        val basePreview = LiveGifPreviewRenderer.renderPreview(src, baseSettings)
        val ditherPreview = LiveGifPreviewRenderer.renderPreview(src, baseSettings.copy(ditherMode = DitherMode.FLOYD_STEINBERG))
        val rotatedCroppedPreview = LiveGifPreviewRenderer.renderPreview(
            src,
            baseSettings.copy(
                rotationDegrees = 90,
                cropRect = NormalizedCropRect.centeredForAspectRatio(180, 320, AspectRatioPreset.SQUARE_1_1.ratio)
            )
        )
        val pingPongPreview = LiveGifPreviewRenderer.renderPreview(src, baseSettings.copy(pingPong = true))

        // Dither mode produces different quantized pixels on the rendered preview frame
        val b0 = basePreview.frames.first()
        val d0 = ditherPreview.frames.first()
        val bPixels = IntArray(b0.width * b0.height).also { b0.getPixels(it, 0, b0.width, 0, 0, b0.width, b0.height) }
        val dPixels = IntArray(d0.width * d0.height).also { d0.getPixels(it, 0, d0.width, 0, 0, d0.width, d0.height) }
        assertTrue("Live preview must reflect dithering changes", FrameQuantizer.meanPixelDifference(bPixels, dPixels) > 0.5)
        assertTrue("Live preview must quantize colors to selected palette", FrameQuantizer.countUniqueColors(bPixels) <= 16)

        // Rotation + 1:1 crop produces square output dimensions
        assertEquals(rotatedCroppedPreview.outputWidth, rotatedCroppedPreview.outputHeight)
        assertEquals(180, rotatedCroppedPreview.outputWidth)

        // Ping-pong increases rendered frame count (N -> 2N - 2)
        assertTrue("Ping-pong preview should have more frames than single pass", pingPongPreview.frames.size > basePreview.frames.size)

        basePreview.recycleAll()
        ditherPreview.recycleAll()
        rotatedCroppedPreview.recycleAll()
        pingPongPreview.recycleAll()
    }

    @Test
    fun `batch sequential conversion exports each clip and cancellation stops remaining items`() = runBlocking {
        val sources = listOf(
            SyntheticVideoFrameSource(VideoMetadata(Uri.parse("content://test/b1.mp4"), "b1.mp4", 240, 160, 0, 800L, 30f, 100_000L)),
            SyntheticVideoFrameSource(VideoMetadata(Uri.parse("content://test/b2.mp4"), "b2.mp4", 160, 240, 0, 1200L, 30f, 100_000L)),
            SyntheticVideoFrameSource(VideoMetadata(Uri.parse("content://test/b3.mp4"), "b3.mp4", 200, 200, 0, 1000L, 30f, 100_000L))
        )
        val sharedSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.P240,
            colorQualityPreset = ColorQualityPreset.C32,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 6f,
            speed = 1f
        )

        val outputs = mutableListOf<File>()
        val batchJob = launch {
            for ((index, src) in sources.withIndex()) {
                val outFile = createTempGifFile("batch_item_$index.gif")
                GifConversionEngine.encodeToFile(
                    frameSource = src,
                    settings = sharedSettings.copy(trimStartMs = 0L, trimEndMs = src.metadata.durationMs),
                    outputFile = outFile,
                    onProgress = { p ->
                        if (index == 1 && p.currentFrame >= 2) {
                            this.cancel()
                        }
                    }
                )
                outputs.add(outFile)
            }
        }
        batchJob.join()

        // First clip finished as valid GIF89a; second was cancelled and cleaned up; third never started
        assertEquals(1, outputs.size)
        val (w0, h0) = readGifHeaderDimensions(outputs[0])
        assertEquals(240, w0)
        assertEquals(160, h0)
        assertTrue(!createTempGifFile("batch_item_1.gif").exists())
        assertTrue(!createTempGifFile("batch_item_2.gif").exists())
    }

    @Test
    fun `worker input data serializes all conversion settings accurately`() {
        val uri = Uri.parse("content://media/external/video/media/77")
        val settings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.CUSTOM,
            customResolutionShortEdge = 320,
            colorQualityPreset = ColorQualityPreset.CUSTOM,
            customColorCount = 48,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 18f,
            speed = 1.5f,
            reverse = true,
            pingPong = true,
            rotationDegrees = 270,
            cropRect = NormalizedCropRect(0.1f, 0.2f, 0.9f, 0.8f),
            trimStartMs = 1250L,
            trimEndMs = 4750L
        )
        val data = com.example.worker.GifConversionWorker.buildInputData(uri, settings)
        assertEquals(uri.toString(), data.getString(com.example.worker.GifConversionWorker.KEY_VIDEO_URI))
        assertEquals("CUSTOM", data.getString(com.example.worker.GifConversionWorker.KEY_RESOLUTION_PRESET))
        assertEquals(320, data.getInt(com.example.worker.GifConversionWorker.KEY_CUSTOM_RES, 0))
        assertEquals("CUSTOM", data.getString(com.example.worker.GifConversionWorker.KEY_COLOR_PRESET))
        assertEquals(48, data.getInt(com.example.worker.GifConversionWorker.KEY_CUSTOM_COLORS, 0))
        assertEquals("ORDERED", data.getString(com.example.worker.GifConversionWorker.KEY_DITHER))
        assertEquals(false, data.getBoolean(com.example.worker.GifConversionWorker.KEY_USE_ORIGINAL_FPS, true))
        assertEquals(18f, data.getFloat(com.example.worker.GifConversionWorker.KEY_FPS, 0f), 0.001f)
        assertEquals(1.5f, data.getFloat(com.example.worker.GifConversionWorker.KEY_SPEED, 0f), 0.001f)
        assertTrue(data.getBoolean(com.example.worker.GifConversionWorker.KEY_REVERSE, false))
        assertTrue(data.getBoolean(com.example.worker.GifConversionWorker.KEY_PING_PONG, false))
        assertEquals(270, data.getInt(com.example.worker.GifConversionWorker.KEY_ROTATION, 0))
        assertEquals(1250L, data.getLong(com.example.worker.GifConversionWorker.KEY_TRIM_START, 0L))
        assertEquals(4750L, data.getLong(com.example.worker.GifConversionWorker.KEY_TRIM_END, 0L))

        val defaultData = com.example.worker.GifConversionWorker.buildInputData(uri, GifConversionSettings.DEFAULT)
        assertTrue(defaultData.getBoolean(com.example.worker.GifConversionWorker.KEY_USE_ORIGINAL_FPS, false))
    }

    @Test
    fun `original source fps is default and preserves 24fps and 30fps without reducing to 15fps`() = runBlocking {
        assertTrue("Original (Source) FPS must be default", GifConversionSettings.DEFAULT.useOriginalFps)

        // 24 FPS clip (1 second)
        val schedule24 = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = 0L,
            trimEndMs = 1000L,
            videoDurationMs = 1000L,
            fps = 15f,
            speed = 1f,
            reverse = false,
            pingPong = false,
            useOriginalFps = true,
            sourceFps = 24f
        )
        assertEquals(24, schedule24.frames.size)
        assertTrue("24 FPS should preserve ~24 FPS (got ${schedule24.effectiveFps})", abs(schedule24.effectiveFps - 24f) < 0.8f)
        assertTrue("24 FPS centisecond timing should mark approximation honestly", schedule24.isGifTimingApproximated)
        assertTrue("All frame delays must be GIF-safe >= 20ms", schedule24.frames.all { it.delayMs >= 20 && it.delayMs % 10 == 0 })

        // 30 FPS clip (1 second)
        val src30 = SyntheticVideoFrameSource(
            VideoMetadata(Uri.parse("content://test/fps30.mp4"), "fps30.mp4", 160, 120, 0, 1000L, 30f, 100_000L)
        )
        val out30 = createTempGifFile("original_30fps.gif")
        val stats30 = GifConversionEngine.encodeToFile(
            frameSource = src30,
            settings = GifConversionSettings.DEFAULT.copy(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                trimStartMs = 0L,
                trimEndMs = 1000L
            ),
            outputFile = out30
        )
        assertEquals(30, stats30.frameCount)
        assertTrue("30 FPS clip must not be reduced to 15 FPS (got ${stats30.fps})", stats30.fps > 28f)
        assertTrue("Total duration should stay close to 1000ms (got ${stats30.durationMs})", abs(stats30.durationMs - 1000L) <= 20L)
    }

    @Test
    fun `original source fps preserves VFR timestamps and respects GIF 20ms ceiling for 60fps`() {
        // Synthetic VFR clip: first half ~10 FPS (100ms gaps), second half ~25 FPS (40ms gaps)
        val vfrTimestampsUs = listOf(
            0L, 100_000L, 200_000L, 300_000L, 400_000L, 500_000L,
            540_000L, 580_000L, 620_000L, 660_000L, 700_000L, 740_000L, 780_000L, 820_000L, 860_000L, 900_000L, 940_000L, 980_000L
        )
        val vfrSchedule = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = 0L,
            trimEndMs = 1000L,
            videoDurationMs = 1000L,
            fps = 15f,
            speed = 1f,
            reverse = false,
            pingPong = false,
            useOriginalFps = true,
            sourceFps = 18f,
            sourceFrameTimestampsUs = vfrTimestampsUs
        )
        assertTrue("VFR schedule must report variable timing", vfrSchedule.isVariableTiming)
        assertEquals(100, vfrSchedule.frames.first().delayMs)
        assertEquals(40, vfrSchedule.frames[6].delayMs)
        assertEquals(vfrTimestampsUs.size, vfrSchedule.frames.size)

        // 60 FPS clip must coalesce sub-20ms frames to GIF's 20ms (50 FPS) viewer-safe ceiling while preserving ~1000ms duration
        val schedule60 = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = 0L,
            trimEndMs = 1000L,
            videoDurationMs = 1000L,
            fps = 15f,
            speed = 1f,
            reverse = false,
            pingPong = false,
            useOriginalFps = true,
            sourceFps = 60f
        )
        assertTrue(schedule60.isGifTimingApproximated)
        assertTrue(schedule60.frames.all { it.delayMs >= 20 })
        assertTrue("60 FPS capped to 50 FPS GIF ceiling", schedule60.effectiveFps <= 50.5f && schedule60.effectiveFps >= 48f)
        assertTrue("Duration preserved near 1000ms", abs(schedule60.totalDurationMs - 1000L) <= 20L)

        // Manual override replaces Original (Source) FPS
        val manualOverride = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = 0L,
            trimEndMs = 1000L,
            videoDurationMs = 1000L,
            fps = 12f,
            speed = 1f,
            reverse = false,
            pingPong = false,
            useOriginalFps = false,
            sourceFps = 30f,
            sourceFrameTimestampsUs = vfrTimestampsUs
        )
        assertTrue("Manual 12 FPS must override 30 FPS source", abs(manualOverride.effectiveFps - 12.5f) < 1.5f)
        assertTrue(manualOverride.frames.size in 12..13)
    }

    @Test
    fun `custom resolutions above 480 are accepted without artificial caps and encoded properly`() = runBlocking {
        // 1. Settings sanitization preserves resolutions above 480 (e.g. 720p, 1080p, 1440p)
        val s720 = GifConversionSettings(
            resolutionPreset = ResolutionPreset.CUSTOM,
            customResolutionShortEdge = 720
        ).sanitized()
        assertEquals(720, s720.customResolutionShortEdge)

        val s1080 = GifConversionSettings(
            resolutionPreset = ResolutionPreset.CUSTOM,
            customResolutionShortEdge = 1080
        ).sanitized()
        assertEquals(1080, s1080.customResolutionShortEdge)

        val s1440 = GifConversionSettings(
            resolutionPreset = ResolutionPreset.CUSTOM,
            customResolutionShortEdge = 1440
        ).sanitized()
        assertEquals(1440, s1440.customResolutionShortEdge)

        // 2. Geometry calculations on 1080p video (1920x1080)
        // 720p custom resolution produces 1280x720, aspect ratio locked
        val dims720 = GifGeometryCalculator.calculateOutputDimensions(
            1920, 1080,
            GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 720
            )
        )
        assertEquals(1280, dims720.width)
        assertEquals(720, dims720.height)

        // 1080p custom resolution on 1920x1080 video produces 1920x1080
        val dims1080 = GifGeometryCalculator.calculateOutputDimensions(
            1920, 1080,
            GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 1080
            )
        )
        assertEquals(1920, dims1080.width)
        assertEquals(1080, dims1080.height)

        // Does not upscale beyond source (e.g. 1440p requested on 1080p source yields 1920x1080)
        val dimsCapped = GifGeometryCalculator.calculateOutputDimensions(
            1920, 1080,
            GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 1440
            )
        )
        assertEquals(1920, dimsCapped.width)
        assertEquals(1080, dimsCapped.height)

        // 3. 4K source (3840x2160) supports 1440p and 2160p
        val dims4kTo1440 = GifGeometryCalculator.calculateOutputDimensions(
            3840, 2160,
            GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 1440
            )
        )
        assertEquals(2560, dims4kTo1440.width)
        assertEquals(1440, dims4kTo1440.height)

        // 4. Actual GIF encoding at custom resolution 720p (> 480p)
        val src1080 = SyntheticVideoFrameSource(
            VideoMetadata(
                uri = Uri.parse("content://test/hd.mp4"),
                displayName = "hd.mp4",
                rawWidth = 1280,
                rawHeight = 720,
                metadataRotation = 0,
                durationMs = 1000L,
                frameRate = 30f,
                fileSizeBytes = 2_000_000L
            )
        )
        val outFile = createTempGifFile("hd_720p_custom.gif")
        val stats = GifConversionEngine.encodeToFile(
            frameSource = src1080,
            settings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 720,
                useOriginalFps = false,
                fps = 5f,
                trimStartMs = 0L,
                trimEndMs = 500L
            ),
            outputFile = outFile
        )
        val (w, h) = readGifHeaderDimensions(outFile)
        assertEquals(1280, w)
        assertEquals(720, h)
        assertEquals(1280, stats.width)
        assertEquals(720, stats.height)
        assertTrue(outFile.length() > 500L)
    }
}
