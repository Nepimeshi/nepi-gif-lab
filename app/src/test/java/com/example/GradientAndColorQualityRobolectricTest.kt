package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.FrameQuantizer
import com.example.engine.FrameTransformer
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.NormalizedCropRect
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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Quantitative perceptual & quality regression suite for:
 * - Smooth gradient preservation (blue-to-purple, black-to-white, sunset/sky, skin tones with
 *   soft shadows, anime background + vivid accent, slowly changing lighting)
 * - Problematic multi-scene clips vs uniform scenes
 * - 6-bit compaction fallback above 49,152 unique 24-bit colors
 * - Color-space (Display-P3 / BT.2020) and RGB_565 normalization in FrameTransformer
 * - GIF-to-GIF original Global Color Table preservation without double-dithering
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GradientAndColorQualityRobolectricTest {

    private data class Oklab(val l: Double, val a: Double, val b: Double)

    private fun srgbToLinear(c: Int): Double {
        val v = (c and 0xFF) / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun rgbToOklab(rgb: Int): Oklab {
        val r = srgbToLinear((rgb ushr 16) and 0xFF)
        val g = srgbToLinear((rgb ushr 8) and 0xFF)
        val b = srgbToLinear(rgb and 0xFF)

        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)

        return Oklab(
            l = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        )
    }

    private fun oklabDeltaE(c1: Int, c2: Int): Double {
        val o1 = rgbToOklab(c1)
        val o2 = rgbToOklab(c2)
        val dl = o1.l - o2.l
        val da = o1.a - o2.a
        val db = o1.b - o2.b
        return sqrt(dl * dl + da * da + db * db)
    }

    private fun meanOklabDeltaE(src: IntArray, encoded: IntArray): Double {
        var sum = 0.0
        for (i in src.indices) {
            sum += oklabDeltaE(src[i], encoded[i])
        }
        return sum / src.size.coerceAtLeast(1)
    }

    /**
     * Localized 3x3 average perceptual Oklab ΔE measuring how closely dithering reproduces
     * true intermediate shades to the human eye.
     */
    private fun meanBlurredOklabDeltaE(src: IntArray, encoded: IntArray, w: Int, h: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                var er = 0.0; var eg = 0.0; var eb = 0.0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val sp = src[row + x + dx]
                        val ep = encoded[row + x + dx]
                        sr += (sp ushr 16) and 0xFF
                        sg += (sp ushr 8) and 0xFF
                        sb += sp and 0xFF
                        er += (ep ushr 16) and 0xFF
                        eg += (ep ushr 8) and 0xFF
                        eb += ep and 0xFF
                    }
                }
                val sAvg = Color.rgb((sr / 9.0).roundToInt(), (sg / 9.0).roundToInt(), (sb / 9.0).roundToInt())
                val eAvg = Color.rgb((er / 9.0).roundToInt(), (eg / 9.0).roundToInt(), (eb / 9.0).roundToInt())
                sum += oklabDeltaE(sAvg, eAvg)
                count++
            }
        }
        return sum / count.coerceAtLeast(1)
    }

    /**
     * Excess horizontal high-frequency dot/grain energy relative to the source image.
     */
    private fun excessDotGrainScore(src: IntArray, encoded: IntArray, w: Int, h: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val sr = ((src[idx] ushr 16) and 0xFF) - (((src[idx - 1] ushr 16) and 0xFF) + ((src[idx + 1] ushr 16) and 0xFF)) * 0.5
                val sg = ((src[idx] ushr 8) and 0xFF) - (((src[idx - 1] ushr 8) and 0xFF) + ((src[idx + 1] ushr 8) and 0xFF)) * 0.5
                val sb = (src[idx] and 0xFF) - ((src[idx - 1] and 0xFF) + (src[idx + 1] and 0xFF)) * 0.5

                val er = ((encoded[idx] ushr 16) and 0xFF) - (((encoded[idx - 1] ushr 16) and 0xFF) + ((encoded[idx + 1] ushr 16) and 0xFF)) * 0.5
                val eg = ((encoded[idx] ushr 8) and 0xFF) - (((encoded[idx - 1] ushr 8) and 0xFF) + ((encoded[idx + 1] ushr 8) and 0xFF)) * 0.5
                val eb = (encoded[idx] and 0xFF) - ((encoded[idx - 1] and 0xFF) + (encoded[idx + 1] and 0xFF)) * 0.5

                val srcHF = abs(sr) + abs(sg) + abs(sb)
                val encHF = abs(er) + abs(eg) + abs(eb)
                sum += max(0.0, encHF - srcHF)
                count++
            }
        }
        return sum / (count.coerceAtLeast(1) * 3.0)
    }

    private fun lerpColor(c0: Int, c1: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = (((c0 ushr 16) and 0xFF) * (1f - f) + ((c1 ushr 16) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val g = (((c0 ushr 8) and 0xFF) * (1f - f) + ((c1 ushr 8) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val b = ((c0 and 0xFF) * (1f - f) + (c1 and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private class ProceduralSceneSource(
        name: String,
        private val w: Int = 180,
        private val h: Int = 100,
        private val frameCount: Int = 8,
        private val fps: Float = 10f,
        private val pixelGenerator: (x: Int, y: Int, w: Int, h: Int, frameIdx: Int, totalFrames: Int) -> Int
    ) : VideoFrameSource {
        override val metadata = VideoMetadata(
            uri = Uri.parse("file:///test/$name.mp4"),
            displayName = "$name.mp4",
            rawWidth = w,
            rawHeight = h,
            metadataRotation = 0,
            durationMs = ((frameCount * 1000f) / fps).toLong(),
            frameRate = fps,
            fileSizeBytes = 250_000L
        )

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val idx = ((timeUs * fps) / 1_000_000.0).toInt().coerceIn(0, frameCount - 1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            var off = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    px[off++] = pixelGenerator(x, y, w, h, idx, frameCount)
                }
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            return bmp
        }

        override fun close() = Unit
    }

    private fun buildAllGradientScenes(): List<Pair<String, ProceduralSceneSource>> = listOf(
        "blue_to_purple" to ProceduralSceneSource("blue_to_purple") { x, y, w, h, f, n ->
            val t = x.toFloat() / (w - 1).toFloat()
            val v = y.toFloat() / (h - 1).toFloat()
            val shift = (f.toFloat() / n.toFloat()) * 0.06f
            val base = lerpColor(Color.rgb(22, 72, 235), Color.rgb(156, 48, 224), (t + shift).coerceIn(0f, 1f))
            lerpColor(base, Color.rgb(12, 18, 56), v * 0.45f)
        },
        "black_to_white" to ProceduralSceneSource("black_to_white") { x, y, w, h, f, _ ->
            val v = ((x.toFloat() / (w - 1).toFloat()) * 255f).roundToInt().coerceIn(0, 255)
            if (y in (h - 8) until (h - 2) && x in (10 + f * 12) until (16 + f * 12)) {
                Color.rgb(200, 200, 200)
            } else {
                Color.rgb(v, v, v)
            }
        },
        "sunset_sky" to ProceduralSceneSource("sunset_sky") { x, y, w, h, f, n ->
            val v = y.toFloat() / (h - 1).toFloat()
            val u = x.toFloat() / (w - 1).toFloat()
            val c = when {
                v < 0.35f -> lerpColor(Color.rgb(24, 34, 92), Color.rgb(122, 54, 134), v / 0.35f)
                v < 0.68f -> lerpColor(Color.rgb(122, 54, 134), Color.rgb(236, 114, 78), (v - 0.35f) / 0.33f)
                else -> lerpColor(Color.rgb(236, 114, 78), Color.rgb(252, 214, 128), (v - 0.68f) / 0.32f)
            }
            val sunX = 0.5f + (f - n / 2) * 0.015f
            val dx = u - sunX
            val dy = (v - 0.72f) * (h.toFloat() / w.toFloat())
            val dist = sqrt(dx * dx + dy * dy)
            val glow = (1f - (dist / 0.25f)).coerceIn(0f, 1f).pow(2f)
            lerpColor(c, Color.rgb(255, 244, 198), glow * 0.65f)
        },
        "skin_soft_shadows" to ProceduralSceneSource("skin_soft_shadows") { x, y, w, h, f, _ ->
            val u = (x.toFloat() / (w - 1).toFloat()) - 0.45f - f * 0.005f
            val v = (y.toFloat() / (h - 1).toFloat()) - 0.45f
            val r = sqrt(u * u + v * v).coerceIn(0f, 1f)
            val warmth = (x.toFloat() / (w - 1).toFloat()) * 0.12f
            val base = when {
                r < 0.45f -> lerpColor(Color.rgb(244, 202, 174), Color.rgb(198, 138, 108), r / 0.45f)
                else -> lerpColor(Color.rgb(198, 138, 108), Color.rgb(74, 48, 44), ((r - 0.45f) / 0.55f).coerceIn(0f, 1f))
            }
            lerpColor(base, Color.rgb(218, 152, 136), warmth)
        },
        "anime_background" to ProceduralSceneSource("anime_background") { x, y, w, h, f, _ ->
            val v = y.toFloat() / (h - 1).toFloat()
            val u = x.toFloat() / (w - 1).toFloat()
            val sky = lerpColor(Color.rgb(66, 148, 242), Color.rgb(196, 232, 255), v)
            val cloudDist = sqrt((u - 0.35f - f * 0.01f).pow(2) + (v - 0.45f).pow(2) * 3.5f)
            val withCloud = if (cloudDist < 0.22f) {
                lerpColor(Color.rgb(252, 250, 255), Color.rgb(214, 224, 244), (cloudDist / 0.22f))
            } else sky
            if (x in (w - 34) until (w - 10) && y in (h - 30) until (h - 8)) {
                lerpColor(Color.rgb(228, 36, 68), Color.rgb(148, 18, 46), ((y - (h - 30)) / 22f))
            } else withCloud
        },
        "changing_lighting" to ProceduralSceneSource("changing_lighting") { x, y, w, h, f, n ->
            val t = f.toFloat() / (n - 1).coerceAtLeast(1).toFloat()
            val u = x.toFloat() / (w - 1).toFloat()
            val v = y.toFloat() / (h - 1).toFloat()
            val topCol = lerpColor(Color.rgb(26, 36, 78), Color.rgb(84, 136, 212), t)
            val botCol = lerpColor(Color.rgb(76, 54, 98), Color.rgb(244, 178, 118), t)
            val ambient = lerpColor(topCol, botCol, v)
            lerpColor(ambient, Color.rgb(255, 236, 200), (1f - abs(u - 0.5f) * 2f) * 0.18f * t)
        },
        "multi_scene_problematic" to ProceduralSceneSource("multi_scene_problematic", frameCount = 10) { x, y, w, h, f, _ ->
            val u = x.toFloat() / (w - 1).toFloat()
            val v = y.toFloat() / (h - 1).toFloat()
            if (f < 7) {
                val base = lerpColor(Color.rgb(24, 28, 36), Color.rgb(58, 66, 78), (u + v) * 0.5f)
                val n = ((x * 7 + y * 13 + f * 3) and 7) - 3
                Color.rgb(
                    (((base ushr 16) and 0xFF) + n).coerceIn(0, 255),
                    (((base ushr 8) and 0xFF) + n).coerceIn(0, 255),
                    ((base and 0xFF) + n).coerceIn(0, 255)
                )
            } else {
                if (v < 0.5f) {
                    lerpColor(Color.rgb(245, 92, 64), Color.rgb(255, 208, 72), u)
                } else {
                    lerpColor(Color.rgb(28, 168, 96), Color.rgb(34, 132, 218), u)
                }
            }
        }
    )

    @Test
    fun `smooth gradients and multi-scene clips meet strict perceptual accuracy shade utilization and low grain thresholds`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenes = buildAllGradientScenes()
        val modes = listOf(
            "256_None" to GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C256,
                ditherMode = DitherMode.NONE,
                useOriginalFps = false,
                fps = 10f
            ),
            "256_Ordered" to GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C256,
                ditherMode = DitherMode.ORDERED,
                useOriginalFps = false,
                fps = 10f
            ),
            "256_Floyd" to GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C256,
                ditherMode = DitherMode.FLOYD_STEINBERG,
                useOriginalFps = false,
                fps = 10f
            ),
            "128_Floyd" to GifConversionSettings(
                resolutionPreset = ResolutionPreset.ORIGINAL,
                colorQualityPreset = ColorQualityPreset.C128,
                ditherMode = DitherMode.FLOYD_STEINBERG,
                useOriginalFps = false,
                fps = 10f
            )
        )

        val tileW = 180
        val tileH = 100

        for ((sceneName, src) in scenes) {
            val inspectTimeUs = if (sceneName == "multi_scene_problematic") 850_000L else 450_000L
            val srcBmp = src.getFrameAtTimeUs(inspectTimeUs)
            val srcPixels = IntArray(tileW * tileH)
            srcBmp.getPixels(srcPixels, 0, tileW, 0, 0, tileW, tileH)
            srcBmp.recycle()

            for ((modeName, modeSettings) in modes) {
                val gifFile = File(context.cacheDir, "qual_${sceneName}_${modeName}_${System.nanoTime()}.gif")
                try {
                    val settings = modeSettings.copy(trimStartMs = 0L, trimEndMs = src.metadata.durationMs)
                    val stats = GifConversionEngine.encodeToFile(src, settings, gifFile)
                    assertTrue(stats.fileSizeBytes > 0L)
                    assertTrue(stats.effectiveColorCount <= (settings.resolvedColorCount ?: 256))

                    AnimatedGifFrameSource.openFile(gifFile).use { decoded ->
                        val decBmp = decoded.getFrameAtTimeUs(inspectTimeUs)!!
                        val decPixels = IntArray(tileW * tileH)
                        decBmp.getPixels(decPixels, 0, tileW, 0, 0, tileW, tileH)
                        decBmp.recycle()

                        val rawDe = meanOklabDeltaE(srcPixels, decPixels)
                        val blurDe = meanBlurredOklabDeltaE(srcPixels, decPixels, tileW, tileH)
                        val grain = excessDotGrainScore(srcPixels, decPixels, tileW, tileH)
                        val uniqueOut = FrameQuantizer.countUniqueColors(decPixels)

                        // Universal perceptual fidelity guard across all scenes & modes
                        assertTrue(
                            "$sceneName / $modeName rawΔE too high: $rawDe",
                            rawDe < 0.011
                        )
                        assertTrue(
                            "$sceneName / $modeName eyeΔE too high: $blurDe",
                            blurDe < 0.0045
                        )
                        assertTrue(
                            "$sceneName / $modeName excess dot grain too high: $grain",
                            grain < 4.0
                        )

                        // Specific anti-regression guards against the old 5-bit (32-shade) & Ordered bugs
                        when (sceneName) {
                            "black_to_white" -> {
                                if (modeName.startsWith("256")) {
                                    assertTrue("256-color grayscale must not be crushed to 32 shades (got $uniqueOut)", uniqueOut >= 160)
                                    assertTrue("256-color grayscale Ordered/None grain must be near zero (got $grain)", grain < 0.1)
                                } else {
                                    assertTrue("128-color grayscale must use >= 120 shades (got $uniqueOut)", uniqueOut >= 120)
                                    assertTrue("128-color grayscale rawΔE must be < 0.0025 (got $rawDe)", rawDe < 0.0025)
                                }
                            }
                            "skin_soft_shadows" -> {
                                if (modeName.startsWith("256")) {
                                    assertTrue("256-color skin tones must use >= 140 shades (got $uniqueOut)", uniqueOut >= 140)
                                    assertTrue("256-color skin Ordered grain must stay smooth (got $grain)", grain < 1.5)
                                } else {
                                    assertTrue("128-color skin tones must use >= 100 shades (got $uniqueOut)", uniqueOut >= 100)
                                }
                            }
                            "anime_background" -> {
                                // Check that the small vivid crimson ribbon accent is preserved accurately
                                val accentIdx = (tileH - 20) * tileW + (tileW - 22)
                                val accentDe = oklabDeltaE(srcPixels[accentIdx], decPixels[accentIdx])
                                assertTrue("Anime crimson accent must not be washed out ($modeName ΔE=$accentDe)", accentDe < 0.025)
                            }
                            "multi_scene_problematic" -> {
                                if (modeName.startsWith("256")) {
                                    assertTrue(
                                        "Multi-scene late vibrant scene must not be starved (expected >= 100 shades, got $uniqueOut)",
                                        uniqueOut >= 100
                                    )
                                    assertTrue(
                                        "Multi-scene late vibrant scene eyeΔE must be < 0.002 (got $blurDe)",
                                        blurDe < 0.002
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    gifFile.delete()
                }
            }
        }

        // Verify Auto color mode grants >= 192 colors to rich gradients instead of starving to 64
        val skinSrc = scenes.first { it.first == "skin_soft_shadows" }.second
        val autoGif = File(context.cacheDir, "qual_auto_skin_${System.nanoTime()}.gif")
        try {
            val autoStats = GifConversionEngine.encodeToFile(
                frameSource = skinSrc,
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.ORIGINAL,
                    colorQualityPreset = ColorQualityPreset.AUTO,
                    ditherMode = DitherMode.ORDERED,
                    useOriginalFps = false,
                    fps = 10f,
                    trimStartMs = 0L,
                    trimEndMs = skinSrc.metadata.durationMs
                ),
                outputFile = autoGif
            )
            assertTrue(
                "Auto color mode should allocate >= 192 colors for smooth skin gradients (got ${autoStats.effectiveColorCount})",
                autoStats.effectiveColorCount >= 192
            )
        } finally {
            autoGif.delete()
        }
    }

    @Test
    fun `accumulator gracefully compacts to 6-bit weighted buckets above 49k unique colors without losing accuracy`() {
        val acc = FrameQuantizer.GlobalPaletteAccumulator()
        // Generate 54,000 distinct 24-bit colors covering a rich RGB ramp
        val pixels = IntArray(54_000) { i ->
            val r = (i * 5) and 0xFF
            val g = ((i / 256) * 3) and 0xFF
            val b = ((i * 17) / 7) and 0xFF
            Color.rgb(r, g, b)
        }
        acc.addFramePixels(pixels, respectAlpha = false)
        val palette = acc.build(requestedColorCount = 256)

        assertTrue("Compacted 6-bit palette should utilize close to 256 slots (got ${palette.colors.size})", palette.colors.size in 220..256)
        assertEquals(256, acc.build(requestedColorCount = null).effectiveColorCount)
    }

    @Test
    fun `FrameTransformer normalizes Display-P3 BT2020 and RGB_565 bitmaps cleanly to sRGB ARGB_8888`() {
        // 1. Display-P3 wide-gamut bitmap -> sRGB ARGB_8888
        val p3Cs = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val p3Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888, true, p3Cs)
        val p3Pixels = IntArray(16 * 16) { idx ->
            if (idx < 128) Color.rgb(128, 128, 128) // Neutral gray
            else Color.rgb(50, 200, 50) // Vivid P3 green
        }
        p3Bitmap.setPixels(p3Pixels, 0, 16, 0, 0, 16, 16)

        val normP3 = FrameTransformer.transformFrame(
            source = p3Bitmap,
            extraRotationDegrees = 0,
            cropRect = NormalizedCropRect.FULL,
            targetWidth = 16,
            targetHeight = 16
        )
        try {
            assertEquals(Bitmap.Config.ARGB_8888, normP3.config)
            assertTrue("Normalized bitmap must be in sRGB ColorSpace", normP3.colorSpace?.isSrgb == true)

            // Neutral gray must stay neutral gray after gamut transform
            val grayOut = normP3.getPixel(4, 2)
            assertTrue(
                "Neutral gray in Display-P3 must remain neutral in sRGB (got ${Color.red(grayOut)},${Color.green(grayOut)},${Color.blue(grayOut)})",
                abs(Color.red(grayOut) - Color.green(grayOut)) <= 1 &&
                    abs(Color.green(grayOut) - Color.blue(grayOut)) <= 1
            )

            // Saturated Display-P3 green maps to a more saturated sRGB green (lower red, higher green than raw bytes)
            val greenOut = normP3.getPixel(4, 12)
            assertTrue(
                "Display-P3 green (50,200,50) must gamut-map to higher G and lower R in sRGB (got R=${Color.red(greenOut)}, G=${Color.green(greenOut)}, B=${Color.blue(greenOut)})",
                Color.green(greenOut) >= 202 && Color.red(greenOut) < 40
            )
        } finally {
            p3Bitmap.recycle()
            normP3.recycle()
        }

        // 2. BT.2020 wide-gamut bitmap -> sRGB ARGB_8888
        val bt2020Cs = ColorSpace.get(ColorSpace.Named.BT2020)
        val bt2020Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888, true, bt2020Cs)
        bt2020Bitmap.eraseColor(Color.rgb(40, 180, 60))
        val normBt2020 = FrameTransformer.transformFrame(
            source = bt2020Bitmap,
            extraRotationDegrees = 0,
            cropRect = NormalizedCropRect.FULL,
            targetWidth = 8,
            targetHeight = 8
        )
        try {
            assertEquals(Bitmap.Config.ARGB_8888, normBt2020.config)
            assertTrue("Normalized BT.2020 bitmap must be in sRGB", normBt2020.colorSpace?.isSrgb == true)
            val outPx = normBt2020.getPixel(4, 4)
            assertTrue("BT.2020 green must convert accurately instead of copying raw bytes", outPx != Color.rgb(40, 180, 60))
        } finally {
            bt2020Bitmap.recycle()
            normBt2020.recycle()
        }

        // 3. RGB_565 bitmap -> sRGB ARGB_8888
        val rgb565 = Bitmap.createBitmap(12, 12, Bitmap.Config.RGB_565)
        rgb565.eraseColor(Color.rgb(96, 144, 208))
        val norm565 = FrameTransformer.transformFrame(
            source = rgb565,
            extraRotationDegrees = 0,
            cropRect = NormalizedCropRect.FULL,
            targetWidth = 12,
            targetHeight = 12
        )
        try {
            assertEquals(Bitmap.Config.ARGB_8888, norm565.config)
            assertTrue(norm565.colorSpace?.isSrgb == true)
        } finally {
            rgb565.recycle()
            norm565.recycle()
        }

        // 4. Standard sRGB ARGB_8888 bitmap remains bit-for-bit unchanged
        val srgbBmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        srgbBmp.eraseColor(Color.rgb(123, 87, 211))
        val normSrgb = FrameTransformer.transformFrame(
            source = srgbBmp,
            extraRotationDegrees = 0,
            cropRect = NormalizedCropRect.FULL,
            targetWidth = 8,
            targetHeight = 8
        )
        try {
            assertEquals(Color.rgb(123, 87, 211), normSrgb.getPixel(3, 3))
        } finally {
            srgbBmp.recycle()
            normSrgb.recycle()
        }
    }

    @Test
    fun `gif-to-gif unresized edit preserves original Global Color Table with zero double-dithering and exact transparency`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val w = 80
        val h = 60

        // Build a source animated GIF with a Global Color Table, dithering already applied, and transparency
        val srcFrames = List(4) { f ->
            IntArray(w * h) { idx ->
                val x = idx % w
                val y = idx / w
                if (x < 10 && y < 10) {
                    0x00000000 // Transparent corner
                } else {
                    val t = x.toFloat() / (w - 1).toFloat()
                    val v = y.toFloat() / (h - 1).toFloat()
                    val base = lerpColor(Color.rgb(30, 80, 220), Color.rgb(180, 60, 210), (t + f * 0.04f).coerceIn(0f, 1f))
                    lerpColor(base, Color.rgb(20, 24, 60), v * 0.4f)
                }
            }
        }
        val acc = FrameQuantizer.GlobalPaletteAccumulator()
        srcFrames.forEach { acc.addFramePixels(it, respectAlpha = true) }
        val originalPalette = acc.build(requestedColorCount = 64, forceTransparency = true)

        val inputGif = File(context.cacheDir, "preserve_gct_in_${System.nanoTime()}.gif")
        val outPreserved = File(context.cacheDir, "preserve_gct_out_${System.nanoTime()}.gif")
        val outReduced = File(context.cacheDir, "reduce_gct_out_${System.nanoTime()}.gif")

        try {
            val baos = ByteArrayOutputStream()
            val encoder = Gif89aStreamEncoder(baos, w, h, loopCount = 0, globalPalette = originalPalette)
            srcFrames.forEach { px ->
                val q = FrameQuantizer.quantizeWithPalette(px, w, h, originalPalette, DitherMode.ORDERED)
                encoder.addFrame(q, delayMs = 90)
            }
            encoder.finish()
            inputGif.writeBytes(baos.toByteArray())

            // Re-export GIF-to-GIF with reverse + ORDERED dithering enabled, NO resize, NO color reduction
            AnimatedGifFrameSource.openFile(inputGif).use { gifSrc ->
                val origDecodedFrames = (0 until gifSrc.frameCount).map { i ->
                    val b = gifSrc.getFrameAtIndex(i)!!
                    IntArray(w * h).also {
                        b.getPixels(it, 0, w, 0, 0, w, h)
                        b.recycle()
                    }
                }

                GifConversionEngine.encodeToFile(
                    frameSource = gifSrc,
                    settings = GifConversionSettings(
                        resolutionPreset = ResolutionPreset.ORIGINAL,
                        colorQualityPreset = ColorQualityPreset.AUTO,
                        ditherMode = DitherMode.ORDERED,
                        reverse = true,
                        useOriginalFps = true,
                        trimStartMs = 0L,
                        trimEndMs = gifSrc.metadata.durationMs
                    ),
                    outputFile = outPreserved
                )

                // Verify bit-for-bit pixel preservation (zero double-dithering, zero new banding, exact alpha!)
                AnimatedGifFrameSource.openFile(outPreserved).use { redecoded ->
                    assertTrue(redecoded.hasGlobalColorTable)
                    assertTrue(redecoded.metadata.hasTransparency)
                    assertEquals(4, redecoded.frameCount)

                    for (i in 0 until 4) {
                        val b = redecoded.getFrameAtIndex(i)!!
                        val outPx = IntArray(w * h)
                        b.getPixels(outPx, 0, w, 0, 0, w, h)
                        b.recycle()

                        val expectedOrig = origDecodedFrames[3 - i] // reversed order
                        var diffCount = 0
                        for (p in outPx.indices) {
                            if (outPx[p] != expectedOrig[p]) diffCount++
                        }
                        assertEquals(
                            "Frame $i must match original GIF frame bit-for-bit without double-dithering",
                            0,
                            diffCount
                        )
                    }
                }

                // Now verify that explicitly reducing colors (C16) DOES re-quantize cleanly
                val reducedStats = GifConversionEngine.encodeToFile(
                    frameSource = gifSrc,
                    settings = GifConversionSettings(
                        resolutionPreset = ResolutionPreset.ORIGINAL,
                        colorQualityPreset = ColorQualityPreset.C16,
                        ditherMode = DitherMode.FLOYD_STEINBERG,
                        useOriginalFps = true,
                        trimStartMs = 0L,
                        trimEndMs = gifSrc.metadata.durationMs
                    ),
                    outputFile = outReduced
                )
                assertTrue(reducedStats.effectiveColorCount <= 16)
            }
        } finally {
            inputGif.delete()
            outPreserved.delete()
            outReduced.delete()
        }
    }
}
