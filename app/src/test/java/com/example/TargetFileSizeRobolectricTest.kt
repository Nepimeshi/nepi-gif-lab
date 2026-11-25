package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.GifSizeEstimator
import com.example.engine.ResolutionPreset
import com.example.engine.TargetFileSizeOptimizer
import com.example.engine.TargetSizeRecommendation
import com.example.engine.TargetSizeStatus
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import com.example.ui.components.TargetFileSizeBottomSheet
import com.example.ui.theme.GifStudioTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetFileSizeRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class SyntheticColorfulSource(
        private val w: Int = 240,
        private val h: Int = 160,
        durationMs: Long = 1_500L,
        sourceFps: Float = 16f
    ) : VideoFrameSource {
        override val metadata = VideoMetadata(
            uri = Uri.parse("file:///synthetic/colorful.mp4"),
            displayName = "colorful.mp4",
            rawWidth = w,
            rawHeight = h,
            metadataRotation = 0,
            durationMs = durationMs,
            frameRate = sourceFps,
            fileSizeBytes = 2_000_000L
        )

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val cap = maxEdgePx ?: maxOf(w, h)
            val scale = if (maxOf(w, h) > cap) cap.toFloat() / maxOf(w, h) else 1f
            val bw = (w * scale).toInt().coerceAtLeast(2)
            val bh = (h * scale).toInt().coerceAtLeast(2)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val phase = ((timeUs / 50_000L) % 60L).toInt()
            val pixels = IntArray(bw * bh)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    val r = ((x * 255 / bw) + phase * 7) and 0xFF
                    val g = ((y * 255 / bh) + (x xor y) * 3 + phase * 5) and 0xFF
                    val b = (((x + y) * 190 / (bw + bh)) + ((x * 13 + y * 29 + phase * 17) and 0x3F)) and 0xFF
                    pixels[y * bw + x] = Color.rgb(r, g, b)
                }
            }
            bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
            return bmp
        }

        override fun close() {}
    }

    @Test
    fun `already-under-target returns ALREADY_WITHIN_TARGET without changing settings`() = runTest {
        val source = SyntheticColorfulSource(w = 120, h = 80, durationMs = 1_000L, sourceFps = 10f)
        val settings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = false,
            fps = 10f,
            colorQualityPreset = ColorQualityPreset.C64
        )
        val baseline = GifSizeEstimator.estimate(source, settings)
        val generousTarget = baseline.estimatedBytes * 3L

        val rec = TargetFileSizeOptimizer.analyze(source, settings, generousTarget)
        assertEquals(TargetSizeStatus.ALREADY_WITHIN_TARGET, rec.status)
        assertEquals(settings.sanitized(), rec.recommendedSettings)
        assertTrue(rec.changesSummary.isEmpty())
    }

    @Test
    fun `realistic target produces quality-prioritizing recommendation and real export drops below baseline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = SyntheticColorfulSource(w = 240, h = 160, durationMs = 1_500L, sourceFps = 16f)
        val highSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = false,
            fps = 16f,
            colorQualityPreset = ColorQualityPreset.C256,
            ditherMode = DitherMode.FLOYD_STEINBERG
        )

        val baseline = GifSizeEstimator.estimate(source, highSettings)
        val targetBytes = (baseline.estimatedBytes * 0.45).toLong().coerceAtLeast(16_384L)

        val rec = TargetFileSizeOptimizer.analyze(source, highSettings, targetBytes)
        assertTrue(
            "Expected recommendation below baseline: baseline=${baseline.estimatedBytes}, rec=${rec.recommendedEstimatedBytes}",
            rec.recommendedEstimatedBytes < baseline.estimatedBytes
        )
        assertTrue(
            "Expected recommended estimated bytes <= target ($targetBytes), got ${rec.recommendedEstimatedBytes}",
            rec.recommendedEstimatedBytes <= targetBytes
        )
        assertTrue("Expected human-readable change summary", rec.changesSummary.isNotEmpty())

        // Original settings must NOT have been mutated silently
        assertEquals(ColorQualityPreset.C256, highSettings.colorQualityPreset)
        assertEquals(16f, highSettings.fps, 0.01f)

        // Real encode with recommended settings produces a valid smaller GIF
        val outFile = File(context.cacheDir, "target_size_exported_${System.nanoTime()}.gif")
        try {
            val stats = GifConversionEngine.encodeToFile(
                frameSource = source,
                settings = rec.recommendedSettings,
                outputFile = outFile
            )
            assertTrue("Exported GIF should exist and be non-empty", stats.fileSizeBytes > 0L)
            assertTrue(
                "Actual exported size (${stats.fileSizeBytes}) should be smaller than original baseline (${baseline.estimatedBytes})",
                stats.fileSizeBytes < baseline.estimatedBytes
            )
        } finally {
            outFile.delete()
        }
    }

    @Test
    fun `impossibly tiny target returns DIFFICULT_OR_IMPOSSIBLE with clear warning`() = runTest {
        val source = SyntheticColorfulSource(w = 240, h = 160, durationMs = 2_000L, sourceFps = 15f)
        val settings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = false,
            fps = 15f,
            colorQualityPreset = ColorQualityPreset.C128
        )

        val rec = TargetFileSizeOptimizer.analyze(source, settings, targetBytes = 2_048L)
        assertEquals(TargetSizeStatus.DIFFICULT_OR_IMPOSSIBLE, rec.status)
        assertNotNull("Must provide a clear warning for impossible targets", rec.warningMessage)
        assertTrue(rec.warningMessage!!.isNotBlank())
    }

    @Test
    fun `target file size sheet requires explicit user confirmation before applying settings`() {
        var accepted = false
        var dismissed = false
        val sampleRec = TargetSizeRecommendation(
            status = TargetSizeStatus.RECOMMENDED,
            targetBytes = 1_048_576L,
            currentEstimatedBytes = 2_400_000L,
            recommendedEstimatedBytes = 920_000L,
            recommendedSettings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.P240,
                useOriginalFps = false,
                fps = 12f,
                colorQualityPreset = ColorQualityPreset.C64
            ),
            changesSummary = listOf("Resolution: 480×270 → 360×202", "Frame rate: 15 FPS → 12 FPS"),
            warningMessage = null
        )

        composeRule.setContent {
            GifStudioTheme {
                TargetFileSizeBottomSheet(
                    currentEstimatedBytes = 2_400_000L,
                    isAnalyzing = false,
                    recommendation = sampleRec,
                    errorMessage = null,
                    onAnalyzeTargetBytes = {},
                    onAcceptRecommendation = { accepted = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        // Before clicking Apply, settings have NOT been accepted
        composeRule.onNodeWithTag("target_size_recommendation_card")
            .performScrollTo()
            .assertIsDisplayed()
        assertFalse(accepted)
        assertFalse(dismissed)

        // Clicking Keep Current dismisses without applying
        composeRule.onNodeWithTag("target_size_reject_button")
            .performScrollTo()
            .performClick()
        assertFalse(accepted)
        assertTrue(dismissed)

        // Clicking Apply Settings explicitly invokes confirmation callback
        composeRule.onNodeWithTag("target_size_accept_button")
            .performScrollTo()
            .performClick()
        assertTrue(accepted)
    }
}
