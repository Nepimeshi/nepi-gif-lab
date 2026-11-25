package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.ResolutionPreset
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GifOptimizationRobolectricTest {

    /**
     * Static richly-detailed background + small moving sprite + intentional repeated hold frames.
     * Tests both identical consecutive frame deduplication and changed-region sub-frame cropping.
     */
    private class StaticBgMovingSpriteSource(
        private val w: Int = 160,
        private val h: Int = 120,
        private val withTransparency: Boolean = false,
        private val includeHoldFrames: Boolean = true
    ) : VideoFrameSource {
        override val metadata = VideoMetadata(
            uri = Uri.parse("file:///test/opt_scene.mp4"),
            displayName = "opt_scene.mp4",
            rawWidth = w,
            rawHeight = h,
            metadataRotation = 0,
            durationMs = 1_200L,
            frameRate = 10f,
            fileSizeBytes = 100_000L,
            hasTransparency = withTransparency
        )

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val rawFrameIdx = ((timeUs + 5_000L) / 100_000L).toInt().coerceIn(0, 11)
            // Group into hold stretches (0,0,0, 1, 2,2,2, 3, 4,4, 5, 6) when includeHoldFrames is true
            val stateIdx = if (includeHoldFrames) {
                when (rawFrameIdx) {
                    in 0..2 -> 0
                    3 -> 1
                    in 4..6 -> 2
                    7 -> 3
                    in 8..9 -> 4
                    10 -> 5
                    else -> 6
                }
            } else {
                rawFrameIdx
            }

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)

            if (!withTransparency) {
                // Static textured colorful background (identical across every frame)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val r = ((x * 220 / w) + ((x / 8 + y / 8) and 1) * 24).coerceIn(0, 255)
                        val g = ((y * 200 / h) + ((x * 7 + y * 5) and 0x1F)).coerceIn(0, 255)
                        val b = (((x + y) * 160 / (w + h)) + 40).coerceIn(0, 255)
                        pixels[y * w + x] = Color.rgb(r, g, b)
                    }
                }
            } else {
                // Transparent background -> opaque sprite moves across canvas (tests opaque -> transparent transitions!)
                pixels.fill(Color.TRANSPARENT)
            }

            // Small 16x16 moving colored sprite
            val sx = 12 + stateIdx * 16
            val sy = 44 + (stateIdx % 2) * 8
            val spriteColor = when (stateIdx % 3) {
                0 -> Color.rgb(255, 64, 64)
                1 -> Color.rgb(64, 240, 120)
                else -> Color.rgb(255, 220, 40)
            }
            for (y in sy until (sy + 16).coerceAtMost(h)) {
                for (x in sx until (sx + 16).coerceAtMost(w)) {
                    pixels[y * w + x] = spriteColor
                }
            }

            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            return bmp
        }

        override fun close() {}
    }

    private fun assertVisuallyIdenticalAtEveryTimestamp(
        unoptimizedFile: File,
        optimizedFile: File,
        sampleStepMs: Long = 50L
    ) {
        AnimatedGifFrameSource.openFile(unoptimizedFile).use { unopt ->
            AnimatedGifFrameSource.openFile(optimizedFile).use { opt ->
                assertEquals(unopt.metadata.orientedWidth, opt.metadata.orientedWidth)
                assertEquals(unopt.metadata.orientedHeight, opt.metadata.orientedHeight)
                assertEquals(
                    "Total animation duration must match exactly",
                    unopt.metadata.durationMs,
                    opt.metadata.durationMs
                )

                val w = unopt.metadata.orientedWidth
                val h = unopt.metadata.orientedHeight
                val bufA = IntArray(w * h)
                val bufB = IntArray(w * h)

                var tMs = 10L
                while (tMs < unopt.metadata.durationMs) {
                    val bmpA = unopt.getFrameAtTimeUs(tMs * 1000L)!!
                    val bmpB = opt.getFrameAtTimeUs(tMs * 1000L)!!
                    try {
                        bmpA.getPixels(bufA, 0, w, 0, 0, w, h)
                        bmpB.getPixels(bufB, 0, w, 0, 0, w, h)
                        var diffCount = 0
                        for (i in bufA.indices) {
                            if (bufA[i] != bufB[i]) {
                                diffCount++
                            }
                        }
                        assertEquals(
                            "Expected 0 pixel differences at t=${tMs}ms (got $diffCount differing pixels)",
                            0,
                            diffCount
                        )
                    } finally {
                        bmpA.recycle()
                        bmpB.recycle()
                    }
                    tMs += sampleStepMs
                }
            }
        }
    }

    @Test
    fun `opaque animation optimization deduplicates identical frames, crops changed regions, shrinks size, and is pixel-identical`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = StaticBgMovingSpriteSource(withTransparency = false, includeHoldFrames = true)
        val baseSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = false,
            fps = 10f,
            colorQualityPreset = ColorQualityPreset.C64,
            ditherMode = DitherMode.NONE,
            trimStartMs = 0L,
            trimEndMs = 1_200L
        )

        val unoptFile = File(context.cacheDir, "opt_opaque_off_${System.nanoTime()}.gif")
        val optFile = File(context.cacheDir, "opt_opaque_on_${System.nanoTime()}.gif")
        try {
            val unoptStats = GifConversionEngine.encodeToFile(
                frameSource = source,
                settings = baseSettings.copy(optimizeOutput = false),
                outputFile = unoptFile
            )
            val optStats = GifConversionEngine.encodeToFile(
                frameSource = source,
                settings = baseSettings.copy(optimizeOutput = true),
                outputFile = optFile
            )

            // Identical frames were merged (12 -> 7 frames) while preserving exact duration
            assertEquals(12, unoptStats.frameCount)
            assertEquals(7, optStats.frameCount)
            assertEquals(unoptStats.durationMs, optStats.durationMs)
            assertEquals(unoptStats.effectiveColorCount, optStats.effectiveColorCount)

            // File size must be substantially smaller than unoptimized output
            assertTrue(
                "Optimized GIF (${optStats.fileSizeBytes} B) should be meaningfully smaller than unoptimized (${unoptStats.fileSizeBytes} B)",
                optStats.fileSizeBytes < (unoptStats.fileSizeBytes * 0.65).toLong()
            )

            // Sub-frame rectangles were used after Frame 0
            AnimatedGifFrameSource.openFile(optFile).use { decoded ->
                assertTrue(
                    "Expected cropped sub-frames after frame 0",
                    decoded.frames.drop(1).any { it.width < 160 || it.height < 120 }
                )
            }

            // Pixel-for-pixel identical at every timestamp across the full animation
            assertVisuallyIdenticalAtEveryTimestamp(unoptFile, optFile)
        } finally {
            unoptFile.delete()
            optFile.delete()
        }
    }

    @Test
    fun `transparent animation optimization prevents ghosting on opaque-to-transparent transitions and reduces size`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = StaticBgMovingSpriteSource(withTransparency = true, includeHoldFrames = true)
        val baseSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = false,
            fps = 10f,
            colorQualityPreset = ColorQualityPreset.C32,
            ditherMode = DitherMode.NONE,
            trimStartMs = 0L,
            trimEndMs = 1_200L
        )

        val unoptFile = File(context.cacheDir, "opt_trans_off_${System.nanoTime()}.gif")
        val optFile = File(context.cacheDir, "opt_trans_on_${System.nanoTime()}.gif")
        try {
            val unoptStats = GifConversionEngine.encodeToFile(
                frameSource = source,
                settings = baseSettings.copy(optimizeOutput = false),
                outputFile = unoptFile
            )
            val optStats = GifConversionEngine.encodeToFile(
                frameSource = source,
                settings = baseSettings.copy(optimizeOutput = true),
                outputFile = optFile
            )

            assertTrue(
                "Optimized transparent GIF (${optStats.fileSizeBytes} B) should be smaller than unoptimized (${unoptStats.fileSizeBytes} B)",
                optStats.fileSizeBytes < unoptStats.fileSizeBytes
            )
            assertEquals(7, optStats.frameCount)

            // Verify zero ghosting and 0 differing pixels at every timestamp
            assertVisuallyIdenticalAtEveryTimestamp(unoptFile, optFile)
        } finally {
            unoptFile.delete()
            optFile.delete()
        }
    }

    @Test
    fun `reverse and ping-pong playback remain pixel-for-pixel identical when optimized`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = StaticBgMovingSpriteSource(withTransparency = false, includeHoldFrames = false)
        val modes = listOf(
            GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 10f,
                colorQualityPreset = ColorQualityPreset.C64,
                ditherMode = DitherMode.ORDERED,
                reverse = true,
                pingPong = false,
                trimStartMs = 0L,
                trimEndMs = 800L
            ),
            GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                useOriginalFps = false,
                fps = 10f,
                colorQualityPreset = ColorQualityPreset.C64,
                ditherMode = DitherMode.FLOYD_STEINBERG,
                reverse = false,
                pingPong = true,
                trimStartMs = 0L,
                trimEndMs = 600L
            )
        )

        for ((idx, mode) in modes.withIndex()) {
            val unoptFile = File(context.cacheDir, "opt_mode_${idx}_off_${System.nanoTime()}.gif")
            val optFile = File(context.cacheDir, "opt_mode_${idx}_on_${System.nanoTime()}.gif")
            try {
                val unoptStats = GifConversionEngine.encodeToFile(source, mode.copy(optimizeOutput = false), unoptFile)
                val optStats = GifConversionEngine.encodeToFile(source, mode.copy(optimizeOutput = true), optFile)

                assertTrue(
                    "Mode $idx optimized (${optStats.fileSizeBytes}) should be smaller than unoptimized (${unoptStats.fileSizeBytes})",
                    optStats.fileSizeBytes < unoptStats.fileSizeBytes
                )
                assertVisuallyIdenticalAtEveryTimestamp(unoptFile, optFile)
            } finally {
                unoptFile.delete()
                optFile.delete()
            }
        }
    }
}
