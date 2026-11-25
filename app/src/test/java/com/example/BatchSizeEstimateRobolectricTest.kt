package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.engine.AnimatedGifFrameSource
import com.example.engine.BatchEstimateManager
import com.example.engine.BatchEstimateRequest
import com.example.engine.BatchItemEstimateState
import com.example.engine.BatchSettingsResolver
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.FrameQuantizer
import com.example.engine.Gif89aStreamEncoder
import com.example.engine.GifConversionEngine
import com.example.engine.GifConversionSettings
import com.example.engine.GifSizeEstimator
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.ResolutionPreset
import com.example.engine.SavedGifResult
import com.example.engine.SizeEstimate
import com.example.engine.StudioFormatters
import com.example.engine.VideoFrameSource
import com.example.engine.VideoMetadata
import com.example.ui.screens.BatchConversionScreen
import com.example.ui.theme.GifStudioTheme
import com.example.viewmodel.BatchItemStatus
import com.example.viewmodel.BatchQueueItem
import com.example.viewmodel.BatchUiState
import com.example.viewmodel.computeBatchTotalEstimate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive verification for Nepi GIF Lab's Individual & Total Batch Output Size Estimates:
 * - Distinct per-item size estimates across heterogeneous videos and animated GIFs
 * - Shared settings, preset changes, and per-item custom overrides (trim, crop, resolution, FPS, colors, ping-pong)
 * - Exact total batch size summation and strict separation from actual exported bytes
 * - LRU caching and surgical cache invalidation on settings changes
 * - Bounded concurrency via Semaphore(2)
 * - Graceful unavailable/error handling without fabricated or zero-byte estimates
 * - Pre-conversion estimate vs actual exported GIF file-size accuracy verification
 * - BatchConversionScreen Compose UI rendering of individual & total estimates
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatchSizeEstimateRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Deterministic synthetic video frame source with configurable visual complexity so different
     * items naturally compress to distinctly different GIF sizes.
     */
    private class SyntheticBatchVideoSource(
        override val metadata: VideoMetadata,
        private val highComplexity: Boolean
    ) : VideoFrameSource {

        override fun getFrameAtTimeUs(timeUs: Long, maxEdgePx: Int?): Bitmap {
            val w = metadata.orientedWidth.coerceAtMost(maxEdgePx ?: metadata.orientedWidth).coerceAtLeast(8)
            val h = metadata.orientedHeight.coerceAtMost(maxEdgePx ?: metadata.orientedHeight).coerceAtLeast(8)
            val progress = (timeUs.toFloat() / (metadata.durationMs.coerceAtLeast(1L) * 1000f)).coerceIn(0f, 1f)
            val pixels = IntArray(w * h)
            val cx = w * (0.15f + 0.7f * progress)
            val cy = h * (0.25f + 0.5f * progress)
            val rSq = (minOf(w, h) * 0.18f).let { it * it }

            var idx = 0
            for (y in 0 until h) {
                val fy = y.toFloat() / (h - 1).coerceAtLeast(1)
                for (x in 0 until w) {
                    val fx = x.toFloat() / (w - 1).coerceAtLeast(1)
                    val dx = x - cx
                    val dy = y - cy
                    pixels[idx++] = if (dx * dx + dy * dy <= rSq) {
                        Color.rgb(203, 178, 255)
                    } else if (highComplexity) {
                        // High-entropy animated pattern + rich gradient
                        val wave = ((x * 13 + y * 7 + (progress * 180f).toInt()) and 63)
                        val r = ((fx * 190f) + wave).toInt().coerceIn(0, 255)
                        val g = ((fy * 185f) + ((x xor y) and 31) + (progress * 35f).toInt()).toInt().coerceIn(0, 255)
                        val b = (((1f - fx * 0.5f) * 190f) + ((y * 5 + (progress * 120f).toInt()) and 47)).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    } else {
                        // Smooth low-entropy gradient with mostly static background
                        val r = (40 + fx * 120f).toInt().coerceIn(0, 255)
                        val g = (35 + fy * 115f).toInt().coerceIn(0, 255)
                        val b = (90 + (1f - fx) * 85f).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    }
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        override fun close() = Unit
    }

    private fun tempFile(name: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply { if (exists()) delete() }
    }

    private fun writeSyntheticGifFile(
        fileName: String,
        frameCount: Int = 12,
        delayMs: Int = 100
    ): File {
        val width = 320
        val height = 240
        val file = tempFile(fileName)
        FileOutputStream(file).use { fos ->
            val encoder = Gif89aStreamEncoder(fos, width, height, loopCount = 0)
            for (f in 0 until frameCount) {
                val pixels = IntArray(width * height)
                val progress = f.toFloat() / (frameCount - 1).coerceAtLeast(1)
                val boxX = (width * (0.1f + 0.55f * progress)).toInt()
                val boxY = (height * 0.25f).toInt()
                val boxW = width / 4
                val boxH = height / 3
                var idx = 0
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val inBox = x in boxX until (boxX + boxW) && y in boxY until (boxY + boxH)
                        val border = x < 16 || x >= width - 16 || y < 16 || y >= height - 16
                        pixels[idx++] = when {
                            border -> Color.TRANSPARENT
                            inBox -> Color.rgb(220, 140 + (f * 8).coerceAtMost(100), 255)
                            else -> Color.rgb(
                                (30 + (x * 160 / width)).coerceIn(0, 255),
                                (24 + (y * 150 / height)).coerceIn(0, 255),
                                110
                            )
                        }
                    }
                }
                val q = FrameQuantizer.quantizeAndDither(
                    sourcePixels = pixels,
                    width = width,
                    height = height,
                    requestedColorCount = 64,
                    ditherMode = DitherMode.NONE
                )
                encoder.addFrame(
                    q,
                    delayMs = delayMs,
                    disposalMethod = Gif89aStreamEncoder.DISPOSAL_RESTORE_BACKGROUND
                )
            }
            encoder.finish()
        }
        return file
    }

    private suspend fun awaitAllTerminal(
        manager: BatchEstimateManager,
        ids: Set<String>
    ): Map<String, BatchItemEstimateState> = withTimeout(kotlin.time.Duration.parse("15s")) {
        manager.estimates.first { map ->
            ids.all { id ->
                val s = map[id]
                s is BatchItemEstimateState.Ready || s is BatchItemEstimateState.Unavailable
            }
        }
    }

    @Test
    fun `individual estimates are distinct per item, cached, and surgically invalidated on settings changes`() = runBlocking {
        val gifFile = writeSyntheticGifFile("batch_est_source.gif", frameCount = 12, delayMs = 100)
        val uriSmall = Uri.parse("content://test/small.mp4")
        val uriLarge = Uri.parse("content://test/large.mp4")
        val uriGif = Uri.fromFile(gifFile)

        val openCounts = ConcurrentHashMap<String, AtomicInteger>()
        fun opensFor(uri: Uri): Int = openCounts[uri.toString()]?.get() ?: 0

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = BatchEstimateManager(
                scope = scope,
                sourceOpener = { uri ->
                    openCounts.computeIfAbsent(uri.toString()) { AtomicInteger(0) }.incrementAndGet()
                    when (uri) {
                        uriSmall -> SyntheticBatchVideoSource(
                            VideoMetadata(uriSmall, "small.mp4", 320, 180, 0, 1000L, 24f, 200_000L),
                            highComplexity = false
                        )
                        uriLarge -> SyntheticBatchVideoSource(
                            VideoMetadata(uriLarge, "large.mp4", 1280, 720, 0, 3000L, 30f, 4_500_000L),
                            highComplexity = true
                        )
                        uriGif -> AnimatedGifFrameSource.openFile(gifFile, uriGif, "batch_est_source.gif")
                        else -> error("Unknown uri $uri")
                    }
                },
                maxConcurrentJobs = 2
            )

            val sharedInitial = GifConversionSettings(
                resolutionPreset = ResolutionPreset.P360,
                useOriginalFps = false,
                fps = 12f,
                colorQualityPreset = ColorQualityPreset.C128,
                ditherMode = DitherMode.FLOYD_STEINBERG
            )

            val requests = listOf(
                BatchEstimateRequest("small", uriSmall, 1000L, isCompleted = false, isReadable = true, customSettings = null),
                BatchEstimateRequest("large", uriLarge, 3000L, isCompleted = false, isReadable = true, customSettings = null),
                BatchEstimateRequest("gif", uriGif, 1200L, isCompleted = false, isReadable = true, customSettings = null)
            )

            manager.syncQueue(requests, sharedInitial)
            val initialMap = awaitAllTerminal(manager, setOf("small", "large", "gif"))

            val smallReady = initialMap["small"] as BatchItemEstimateState.Ready
            val largeReady = initialMap["large"] as BatchItemEstimateState.Ready
            val gifReady = initialMap["gif"] as BatchItemEstimateState.Ready

            // Individual estimates must all be positive and distinctly calculated per file
            assertTrue(smallReady.estimate.estimatedBytes > 0L)
            assertTrue(largeReady.estimate.estimatedBytes > 0L)
            assertTrue(gifReady.estimate.estimatedBytes > 0L)
            assertNotEquals(smallReady.estimate.estimatedBytes, largeReady.estimate.estimatedBytes)
            assertNotEquals(largeReady.estimate.estimatedBytes, gifReady.estimate.estimatedBytes)
            assertTrue(
                "Larger, longer high-motion video should estimate strictly larger than small short smooth clip",
                largeReady.estimate.estimatedBytes > smallReady.estimate.estimatedBytes
            )
            // Verify effective specs (no upscaling on small 180p source; 360p on 720p source; 240p on 240p GIF)
            assertEquals(320, smallReady.estimate.outputWidth)
            assertEquals(180, smallReady.estimate.outputHeight)
            assertEquals(640, largeReady.estimate.outputWidth)
            assertEquals(360, largeReady.estimate.outputHeight)
            // GIF89a centisecond delay quantization: 12 FPS -> 80ms/frame -> 12.5 effective FPS
            assertEquals(12.5f, largeReady.estimate.effectiveFps, 0.25f)
            assertEquals(128, largeReady.estimate.resolvedColorCount)

            assertEquals(1, opensFor(uriSmall))
            assertEquals(1, opensFor(uriLarge))
            assertEquals(1, opensFor(uriGif))

            // Re-syncing identical queue + settings must be a 100% cache hit (0 extra opens)
            manager.syncQueue(requests, sharedInitial)
            assertEquals(1, opensFor(uriSmall))
            assertEquals(1, opensFor(uriLarge))
            assertEquals(1, opensFor(uriGif))

            // Applying a custom per-item override on "large" (trim + lower resolution + fewer colors)
            // must recalculate ONLY "large" while "small" and "gif" stay cached.
            val customSmallOverride = sharedInitial.copy(
                trimStartMs = 0L,
                trimEndMs = 1200L,
                cropRect = NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f),
                resolutionPreset = ResolutionPreset.P240,
                fps = 8f,
                colorQualityPreset = ColorQualityPreset.C32,
                ditherMode = DitherMode.NONE
            )
            val requestsWithCustom = requests.map { req ->
                if (req.id == "large") req.copy(customSettings = customSmallOverride) else req
            }
            manager.syncQueue(requestsWithCustom, sharedInitial)
            val afterCustom = awaitAllTerminal(manager, setOf("small", "large", "gif"))

            val largeAfterCustom = afterCustom["large"] as BatchItemEstimateState.Ready
            assertTrue(
                "Trimmed/240p/8fps/32-color custom override must significantly reduce estimate",
                largeAfterCustom.estimate.estimatedBytes < largeReady.estimate.estimatedBytes
            )
            // GIF89a centisecond delay quantization: 8 FPS -> 130ms/frame -> ~7.69 effective FPS
            assertEquals(7.69f, largeAfterCustom.estimate.effectiveFps, 0.35f)
            assertEquals(32, largeAfterCustom.estimate.resolvedColorCount)
            assertEquals("Untouched small item must hit cache", 1, opensFor(uriSmall))
            assertEquals("Untouched gif item must hit cache", 1, opensFor(uriGif))
            assertEquals("Overridden large item must recalculate once", 2, opensFor(uriLarge))

            // Updating shared settings (e.g. preset change to 240p + ping-pong) recalculates shared items,
            // while custom-overridden "large" hits its cache!
            val sharedPreset = sharedInitial.copy(
                resolutionPreset = ResolutionPreset.P240,
                fps = 15f,
                pingPong = true,
                colorQualityPreset = ColorQualityPreset.C64
            )
            manager.syncQueue(requestsWithCustom, sharedPreset)
            val afterSharedChange = awaitAllTerminal(manager, setOf("small", "large", "gif"))
            val smallAfterShared = afterSharedChange["small"] as BatchItemEstimateState.Ready
            assertTrue(
                "Ping-pong + higher FPS should increase small clip frame count",
                smallAfterShared.estimate.totalFrames > smallReady.estimate.totalFrames
            )
            assertEquals("Custom-overridden large item must stay cached when shared settings change", 2, opensFor(uriLarge))
            assertEquals(2, opensFor(uriSmall))
            assertEquals(2, opensFor(uriGif))

            // Reverting back to sharedInitial without custom override hits previously cached entries
            manager.syncQueue(requests, sharedInitial)
            assertEquals(2, opensFor(uriSmall))
            assertEquals(2, opensFor(uriLarge))
            assertEquals(2, opensFor(uriGif))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrency is strictly bounded to maxConcurrentJobs and unreadable items fail gracefully`() = runBlocking {
        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val failUri = Uri.parse("content://test/corrupt.mp4")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = BatchEstimateManager(
                scope = scope,
                sourceOpener = { uri ->
                    val now = activeCount.incrementAndGet()
                    maxObserved.accumulateAndGet(now) { prev, next -> maxOf(prev, next) }
                    try {
                        delay(80L)
                        if (uri == failUri) {
                            throw IllegalStateException("Corrupt media stream")
                        }
                        SyntheticBatchVideoSource(
                            VideoMetadata(uri, uri.lastPathSegment ?: "clip.mp4", 360, 240, 0, 1200L, 24f, 300_000L),
                            highComplexity = false
                        )
                    } finally {
                        activeCount.decrementAndGet()
                    }
                },
                maxConcurrentJobs = 2
            )

            val requests = (1..6).map { idx ->
                BatchEstimateRequest(
                    id = "item_$idx",
                    uri = Uri.parse("content://test/clip_$idx.mp4"),
                    durationMs = 1200L,
                    isCompleted = false,
                    isReadable = true,
                    customSettings = null
                )
            } + listOf(
                BatchEstimateRequest("corrupt", failUri, 1000L, isCompleted = false, isReadable = true, customSettings = null),
                BatchEstimateRequest("unreadable", Uri.parse("content://test/missing.mp4"), 0L, isCompleted = false, isReadable = false, customSettings = null)
            )

            manager.syncQueue(requests, GifConversionSettings.DEFAULT)
            val resultMap = awaitAllTerminal(manager, requests.map { it.id }.toSet())

            assertTrue(
                "Concurrency exceeded limit of 2: maxObserved=${maxObserved.get()}",
                maxObserved.get() in 1..2
            )
            for (idx in 1..6) {
                val st = resultMap["item_$idx"]
                assertTrue("item_$idx should be Ready, was $st", st is BatchItemEstimateState.Ready)
                assertTrue((st as BatchItemEstimateState.Ready).estimate.estimatedBytes > 0L)
            }
            assertTrue(resultMap["corrupt"] is BatchItemEstimateState.Unavailable)
            assertTrue(resultMap["unreadable"] is BatchItemEstimateState.Unavailable)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `total batch size sums individual estimates accurately and separates completed actual bytes`() {
        val estA = BatchItemEstimateState.Ready(
            estimate = SizeEstimate(
                estimatedBytes = 420_000L,
                totalFrames = 24,
                outputWidth = 480,
                outputHeight = 270,
                estimatedDurationMs = 2000L,
                resolvedColorCount = 128,
                effectiveFps = 12f
            ),
            effectiveSettings = GifConversionSettings.DEFAULT,
            settingsKey = "kA"
        )
        val estB = BatchItemEstimateState.Ready(
            estimate = SizeEstimate(
                estimatedBytes = 980_000L,
                totalFrames = 36,
                outputWidth = 640,
                outputHeight = 360,
                estimatedDurationMs = 2400L,
                resolvedColorCount = 256,
                effectiveFps = 15f
            ),
            effectiveSettings = GifConversionSettings.DEFAULT,
            settingsKey = "kB"
        )
        val estC = BatchItemEstimateState.Ready(
            estimate = SizeEstimate(
                estimatedBytes = 310_000L,
                totalFrames = 12,
                outputWidth = 320,
                outputHeight = 240,
                estimatedDurationMs = 1200L,
                resolvedColorCount = 64,
                effectiveFps = 10f
            ),
            effectiveSettings = GifConversionSettings.DEFAULT,
            settingsKey = "kC"
        )

        val itemA = BatchQueueItem(
            id = "a", uri = Uri.parse("content://test/a.mp4"), displayName = "a.mp4",
            durationMs = 2000L, width = 1280, height = 720, estimateState = estA
        )
        val itemB = BatchQueueItem(
            id = "b", uri = Uri.parse("content://test/b.mp4"), displayName = "b.mp4",
            durationMs = 3000L, width = 1280, height = 720, estimateState = estB
        )
        val itemC = BatchQueueItem(
            id = "c", uri = Uri.parse("content://test/c.gif"), displayName = "c.gif",
            durationMs = 1200L, width = 320, height = 240, inputType = MediaInputType.GIF, estimateState = estC
        )

        // All 3 queued -> total equals exact sum
        val totalAll = computeBatchTotalEstimate(listOf(itemA, itemB, itemC))
        assertEquals(420_000L + 980_000L + 310_000L, totalAll.totalEstimatedBytes)
        assertEquals(3, totalAll.readyItemCount)
        assertEquals(3, totalAll.totalEstimatableCount)
        assertFalse(totalAll.isEstimating)
        assertEquals(0L, totalAll.actualCompletedBytes)

        // Remove item B -> total updates to A + C
        val totalAfterRemove = computeBatchTotalEstimate(listOf(itemA, itemC))
        assertEquals(420_000L + 310_000L, totalAfterRemove.totalEstimatedBytes)
        assertEquals(2, totalAfterRemove.readyItemCount)

        // Mark item A completed with actual size 395_000L -> actualCompletedBytes kept separate from remaining estimate
        val completedA = itemA.copy(
            status = BatchItemStatus.COMPLETED,
            savedResult = SavedGifResult(
                contentUri = Uri.parse("content://media/external/images/media/1"),
                fileName = "a.gif",
                relativeFolder = "Pictures/Nepi GIF Lab",
                thumbnailPath = null,
                stats = EncodedGifStats(480, 270, 12f, 2000L, 24, 395_000L, 128, DitherMode.FLOYD_STEINBERG)
            )
        )
        val mixedTotal = computeBatchTotalEstimate(listOf(completedA, itemC))
        assertEquals(310_000L, mixedTotal.totalEstimatedBytes)
        assertEquals(1, mixedTotal.readyItemCount)
        assertEquals(1, mixedTotal.totalEstimatableCount)
        assertEquals(395_000L, mixedTotal.actualCompletedBytes)
        assertEquals(1, mixedTotal.completedCount)

        // Clear queue -> all zeros
        val emptyTotal = computeBatchTotalEstimate(emptyList())
        assertEquals(0L, emptyTotal.totalEstimatedBytes)
        assertEquals(0, emptyTotal.totalEstimatableCount)
        assertFalse(emptyTotal.hasAnyEstimate)
    }

    @Test
    fun `accuracy verification compares individual and total pre-conversion estimates against actual exported GIFs`() = runBlocking {
        val gifInputFile = writeSyntheticGifFile("accuracy_input.gif", frameCount = 14, delayMs = 90)

        val sharedSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.P360,
            useOriginalFps = false,
            fps = 12f,
            colorQualityPreset = ColorQualityPreset.C128,
            ditherMode = DitherMode.FLOYD_STEINBERG,
            optimizeOutput = true
        )
        val customSettingsPortrait = GifConversionSettings(
            trimStartMs = 400L,
            trimEndMs = 1600L,
            cropRect = NormalizedCropRect(0.1f, 0.15f, 0.9f, 0.85f),
            resolutionPreset = ResolutionPreset.P240,
            useOriginalFps = false,
            fps = 10f,
            colorQualityPreset = ColorQualityPreset.C64,
            ditherMode = DitherMode.ORDERED,
            optimizeOutput = true
        )
        val customSettingsGif = GifConversionSettings(
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = true,
            colorQualityPreset = ColorQualityPreset.C64,
            ditherMode = DitherMode.NONE,
            optimizeOutput = true
        )

        data class Case(
            val label: String,
            val sourceFactory: () -> VideoFrameSource,
            val customSettings: GifConversionSettings?
        )

        val cases = listOf(
            Case(
                label = "Landscape 720p video (shared 360p 12fps 128c FS)",
                sourceFactory = {
                    SyntheticBatchVideoSource(
                        VideoMetadata(Uri.parse("content://test/acc_land.mp4"), "acc_land.mp4", 1280, 720, 0, 2000L, 30f, 3_000_000L),
                        highComplexity = true
                    )
                },
                customSettings = null
            ),
            Case(
                label = "Portrait 720p video (custom trim+crop 240p 10fps 64c Ordered)",
                sourceFactory = {
                    SyntheticBatchVideoSource(
                        VideoMetadata(Uri.parse("content://test/acc_port.mp4"), "acc_port.mp4", 720, 1280, 0, 2400L, 24f, 2_500_000L),
                        highComplexity = false
                    )
                },
                customSettings = customSettingsPortrait
            ),
            Case(
                label = "Animated GIF-to-GIF (original timing 64c None)",
                sourceFactory = {
                    AnimatedGifFrameSource.openFile(gifInputFile, Uri.fromFile(gifInputFile), "accuracy_input.gif")
                },
                customSettings = customSettingsGif
            )
        )

        var totalEstimated = 0L
        var totalActual = 0L

        for ((index, c) in cases.withIndex()) {
            val estimate = c.sourceFactory().use { src ->
                val resolved = BatchSettingsResolver.resolveEffectiveSettings(
                    sharedSettings = sharedSettings,
                    customSettings = c.customSettings,
                    sourceDurationMs = src.metadata.durationMs
                )
                GifSizeEstimator.estimate(src, resolved)
            }

            val outFile = tempFile("batch_accuracy_out_$index.gif")
            val actualStats = c.sourceFactory().use { src ->
                val resolved = BatchSettingsResolver.resolveEffectiveSettings(
                    sharedSettings = sharedSettings,
                    customSettings = c.customSettings,
                    sourceDurationMs = src.metadata.durationMs
                )
                GifConversionEngine.encodeToFile(src, resolved, outFile)
            }

            assertEquals(actualStats.width, estimate.outputWidth)
            assertEquals(actualStats.height, estimate.outputHeight)
            assertEquals(actualStats.frameCount, estimate.totalFrames)
            assertTrue(estimate.estimatedBytes > 0L)
            assertTrue(actualStats.fileSizeBytes > 0L)

            val ratio = estimate.estimatedBytes.toDouble() / actualStats.fileSizeBytes.toDouble()
            println(
                "[BatchAccuracy] ${c.label}: est=${estimate.estimatedBytes} B " +
                    "(${StudioFormatters.formatFileSize(estimate.estimatedBytes)}), " +
                    "actual=${actualStats.fileSizeBytes} B " +
                    "(${StudioFormatters.formatFileSize(actualStats.fileSizeBytes)}), " +
                    "ratio=${"%.2f".format(ratio)}x"
            )
            assertTrue(
                "Estimate for '${c.label}' (${estimate.estimatedBytes} B) should track actual (${actualStats.fileSizeBytes} B) within 0.45x..2.0x (ratio=${"%.2f".format(ratio)})",
                ratio in 0.45..2.0
            )
            totalEstimated += estimate.estimatedBytes
            totalActual += actualStats.fileSizeBytes
        }

        val totalRatio = totalEstimated.toDouble() / totalActual.toDouble()
        println(
            "[BatchAccuracy] TOTAL: est=$totalEstimated B (${StudioFormatters.formatFileSize(totalEstimated)}), " +
                "actual=$totalActual B (${StudioFormatters.formatFileSize(totalActual)}), " +
                "ratio=${"%.2f".format(totalRatio)}x"
        )
        assertTrue(
            "Total batch estimate ($totalEstimated B) should closely match total actual ($totalActual B) (ratio=${"%.2f".format(totalRatio)})",
            totalRatio in 0.55..1.85
        )
    }

    @Test
    fun `batch screen renders individual estimates, estimating state, unavailable state, completed actual vs estimate, and total banner`() {
        val readyItem = BatchQueueItem(
            id = "ready_1",
            uri = Uri.parse("content://test/clip_ready.mp4"),
            displayName = "clip_ready.mp4",
            durationMs = 2500L,
            width = 1280,
            height = 720,
            estimateState = BatchItemEstimateState.Ready(
                estimate = SizeEstimate(
                    estimatedBytes = 1_450_000L,
                    totalFrames = 30,
                    outputWidth = 640,
                    outputHeight = 360,
                    estimatedDurationMs = 2500L,
                    resolvedColorCount = 128,
                    effectiveFps = 12f
                ),
                effectiveSettings = GifConversionSettings.DEFAULT,
                settingsKey = "k1"
            )
        )
        val estimatingItem = BatchQueueItem(
            id = "est_2",
            uri = Uri.parse("content://test/anim_loading.gif"),
            displayName = "anim_loading.gif",
            durationMs = 1200L,
            width = 400,
            height = 300,
            inputType = MediaInputType.GIF,
            estimateState = BatchItemEstimateState.Estimating
        )
        val unavailableItem = BatchQueueItem(
            id = "unavail_3",
            uri = Uri.parse("content://test/broken.mp4"),
            displayName = "broken.mp4",
            durationMs = 0L,
            width = 0,
            height = 0,
            estimateState = BatchItemEstimateState.Unavailable("Unreadable")
        )
        val completedItem = BatchQueueItem(
            id = "done_4",
            uri = Uri.parse("content://test/finished.mp4"),
            displayName = "finished.mp4",
            durationMs = 2000L,
            width = 640,
            height = 360,
            status = BatchItemStatus.COMPLETED,
            stage = "Saved (480×270)",
            estimateState = BatchItemEstimateState.Ready(
                estimate = SizeEstimate(
                    estimatedBytes = 520_000L,
                    totalFrames = 24,
                    outputWidth = 480,
                    outputHeight = 270,
                    estimatedDurationMs = 2000L,
                    resolvedColorCount = 64,
                    effectiveFps = 12f
                ),
                effectiveSettings = GifConversionSettings.DEFAULT,
                settingsKey = "k4"
            ),
            savedResult = SavedGifResult(
                contentUri = Uri.parse("content://media/external/images/media/42"),
                fileName = "finished.gif",
                relativeFolder = "Pictures/Nepi GIF Lab",
                thumbnailPath = null,
                stats = EncodedGifStats(480, 270, 12f, 2000L, 24, 490_000L, 64, DitherMode.FLOYD_STEINBERG)
            )
        )

        val items = listOf(readyItem, estimatingItem, unavailableItem, completedItem)
        val state = BatchUiState(
            items = items,
            totalSizeEstimate = computeBatchTotalEstimate(items)
        )

        composeRule.setContent {
            GifStudioTheme {
                BatchConversionScreen(
                    state = state,
                    onBack = {},
                    onAddVideos = {},
                    onRemoveItem = {},
                    onClearQueue = {},
                    onUpdateSharedSettings = {},
                    onResetDefaults = {},
                    onStartBatch = {},
                    onCancelBatch = {},
                    onOpenGif = {},
                    onShareGif = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Prominent Total Batch Size Estimate directly above Convert All
        composeRule.onNodeWithTag("batch_total_estimate").assertIsDisplayed()
        composeRule.onNodeWithText("Estimated Total Output").assertIsDisplayed()
        composeRule.onNodeWithText("Approx.").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_total_estimate_value")
            .assertIsDisplayed()
            .assertTextContains("~${StudioFormatters.formatFileSize(1_450_000L)}")
        composeRule.onNodeWithTag("batch_total_actual_completed").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_start_button").assertIsDisplayed()

        val listNode = composeRule.onNode(hasScrollToNodeAction())

        // Ready item shows individual estimate + approx + effective output specs
        listNode.performScrollToNode(hasTestTag("batch_item_estimate_ready_1"))
        composeRule.onNodeWithText("640×360 · 12 FPS · 128 colors").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_item_estimate_ready_1").assertIsDisplayed()
        composeRule.onNodeWithText("Est. ~${StudioFormatters.formatFileSize(1_450_000L)}").assertIsDisplayed()
        composeRule.onNodeWithText("(approx.)").assertIsDisplayed()

        // Estimating item shows visible Estimating… state
        listNode.performScrollToNode(hasTestTag("batch_item_estimate_est_2"))
        composeRule.onNodeWithTag("batch_item_estimate_est_2").assertIsDisplayed()
        composeRule.onNodeWithText("Estimating…").assertIsDisplayed()

        // Unavailable item shows graceful Estimate unavailable text
        listNode.performScrollToNode(hasTestTag("batch_item_estimate_unavail_3"))
        composeRule.onNodeWithTag("batch_item_estimate_unavail_3").assertIsDisplayed()
        composeRule.onNodeWithText("Estimate unavailable").assertIsDisplayed()

        // Completed item shows actual exported size clearly distinct from earlier estimate
        listNode.performScrollToNode(hasTestTag("batch_item_actual_size_done_4"))
        composeRule.onNodeWithTag("batch_item_actual_size_done_4")
            .assertIsDisplayed()
            .assertTextContains("Actual ${StudioFormatters.formatFileSize(490_000L)}", substring = true)
            .assertTextContains("est. ~${StudioFormatters.formatFileSize(520_000L)}", substring = true)
    }
}
