package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.BatchQueueEntity
import com.example.data.BatchQueueRepository
import com.example.data.GifSettingsCodec
import com.example.data.GifStudioDatabase
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.GifConversionSettings
import com.example.engine.MediaInputType
import com.example.engine.ResolutionPreset
import com.example.engine.SavedGifResult
import com.example.worker.BatchQueueProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchQueueManagementRobolectricTest {

    private fun openInMemoryDb(context: Context): GifStudioDatabase =
        Room.inMemoryDatabaseBuilder(context, GifStudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun makeEntity(
        id: String,
        name: String,
        type: MediaInputType = MediaInputType.VIDEO,
        customSettings: GifConversionSettings? = null
    ) = BatchQueueEntity(
        id = id,
        sortOrder = 0,
        uri = "file:///test/$name",
        displayName = name,
        durationMs = 2_000L,
        width = 640,
        height = 360,
        inputType = type.name,
        customSettingsEncoded = customSettings?.let { GifSettingsCodec.encode(it) }
    )

    private fun fakeSaved(name: String, settings: GifConversionSettings) = SavedGifResult(
        contentUri = Uri.parse("content://media/external/images/media/${name.hashCode()}"),
        fileName = "${name.substringBeforeLast('.')}.gif",
        relativeFolder = "Pictures/Nepi GIF Lab",
        thumbnailPath = null,
        stats = EncodedGifStats(
            width = 360,
            height = 202,
            fps = settings.fps,
            durationMs = 2_000L,
            frameCount = 24,
            fileSizeBytes = 120_000L,
            effectiveColorCount = settings.resolvedColorCount ?: 128,
            ditherMode = settings.ditherMode
        )
    )

    @Test
    fun `queue supports reordering, individual item removal, mixed video and GIF, and per-item settings`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = BatchQueueRepository(db.batchQueueDao())
            val customOverride = GifConversionSettings(
                resolutionPreset = ResolutionPreset.P240,
                useOriginalFps = false,
                fps = 10f,
                colorQualityPreset = ColorQualityPreset.C32,
                ditherMode = DitherMode.NONE
            )

            repo.appendItems(
                listOf(
                    makeEntity("a", "clip_a.mp4", MediaInputType.VIDEO),
                    makeEntity("b", "anim_b.gif", MediaInputType.GIF, customSettings = customOverride),
                    makeEntity("c", "clip_c.mp4", MediaInputType.VIDEO)
                )
            )

            // Move item "c" up to index 1 -> order becomes [a, c, b]
            assertTrue(repo.moveItem("c", -1))
            assertEquals(listOf("a", "c", "b"), repo.getAll().map { it.id })

            // Remove "c" -> order becomes [a, b]
            repo.removeItem("c")
            assertEquals(listOf("a", "b"), repo.getAll().map { it.id })

            val shared = GifConversionSettings(
                resolutionPreset = ResolutionPreset.P480,
                useOriginalFps = false,
                fps = 20f,
                colorQualityPreset = ColorQualityPreset.C256,
                ditherMode = DitherMode.FLOYD_STEINBERG
            )

            val observedSettingsById = mutableMapOf<String, GifConversionSettings>()
            val summary = BatchQueueProcessor.processQueue(
                queueRepo = repo,
                sharedSettings = shared,
                converter = object : BatchQueueProcessor.ItemConverter {
                    override suspend fun convertItem(
                        item: BatchQueueEntity,
                        effectiveSettings: GifConversionSettings,
                        onProgress: (Float, String, Boolean) -> Unit,
                        isItemCancelRequested: () -> Boolean
                    ): SavedGifResult {
                        observedSettingsById[item.id] = effectiveSettings
                        return fakeSaved(item.displayName, effectiveSettings)
                    }
                }
            )

            assertEquals(2, summary.completedCount)
            assertEquals(0, summary.failedCount)
            // Item "a" inherited shared settings
            assertEquals(ResolutionPreset.P480, observedSettingsById["a"]?.resolutionPreset)
            assertEquals(ColorQualityPreset.C256, observedSettingsById["a"]?.colorQualityPreset)
            // Item "b" (GIF) used its custom override
            assertEquals(ResolutionPreset.P240, observedSettingsById["b"]?.resolutionPreset)
            assertEquals(ColorQualityPreset.C32, observedSettingsById["b"]?.colorQualityPreset)
            assertEquals(DitherMode.NONE, observedSettingsById["b"]?.ditherMode)
        } finally {
            db.close()
        }
    }

    @Test
    fun `individual active cancellation skips only that item and completes remaining items`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = BatchQueueRepository(db.batchQueueDao())
            repo.appendItems(
                listOf(
                    makeEntity("1", "one.mp4"),
                    makeEntity("2", "two.gif", MediaInputType.GIF),
                    makeEntity("3", "three.mp4")
                )
            )
            // Pre-flag item "2" for individual cancellation
            repo.requestCancelItem("2")

            val summary = BatchQueueProcessor.processQueue(
                queueRepo = repo,
                sharedSettings = GifConversionSettings.DEFAULT,
                converter = object : BatchQueueProcessor.ItemConverter {
                    override suspend fun convertItem(
                        item: BatchQueueEntity,
                        effectiveSettings: GifConversionSettings,
                        onProgress: (Float, String, Boolean) -> Unit,
                        isItemCancelRequested: () -> Boolean
                    ): SavedGifResult {
                        return fakeSaved(item.displayName, effectiveSettings)
                    }
                }
            )

            assertEquals(2, summary.completedCount)
            assertEquals(1, summary.cancelledCount)
            assertEquals(0, summary.failedCount)
            val byId = repo.getAll().associateBy { it.id }
            assertEquals("COMPLETED", byId["1"]?.status)
            assertEquals("CANCELLED", byId["2"]?.status)
            assertEquals("COMPLETED", byId["3"]?.status)
        } finally {
            db.close()
        }
    }

    @Test
    fun `failure of one item preserves completed outputs and allows retrying failed item`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = BatchQueueRepository(db.batchQueueDao())
            repo.appendItems(
                listOf(
                    makeEntity("ok1", "first.mp4"),
                    makeEntity("flaky", "second.mp4"),
                    makeEntity("ok2", "third.gif", MediaInputType.GIF)
                )
            )

            var failFlaky = true
            val converter = object : BatchQueueProcessor.ItemConverter {
                override suspend fun convertItem(
                    item: BatchQueueEntity,
                    effectiveSettings: GifConversionSettings,
                    onProgress: (Float, String, Boolean) -> Unit,
                    isItemCancelRequested: () -> Boolean
                ): SavedGifResult {
                    if (item.id == "flaky" && failFlaky) {
                        throw IllegalStateException("Transient decoder error")
                    }
                    return fakeSaved(item.displayName, effectiveSettings)
                }
            }

            val run1 = BatchQueueProcessor.processQueue(repo, GifConversionSettings.DEFAULT, converter)
            assertEquals(2, run1.completedCount)
            assertEquals(1, run1.failedCount)
            val afterRun1 = repo.getAll().associateBy { it.id }
            assertEquals("COMPLETED", afterRun1["ok1"]?.status)
            assertEquals("FAILED", afterRun1["flaky"]?.status)
            assertEquals("Transient decoder error", afterRun1["flaky"]?.errorMessage)
            assertEquals("COMPLETED", afterRun1["ok2"]?.status)

            // Retry only the failed item
            failFlaky = false
            repo.requeueItem("flaky")
            val run2 = BatchQueueProcessor.processQueue(repo, GifConversionSettings.DEFAULT, converter)
            assertEquals(3, run2.completedCount)
            assertEquals(0, run2.failedCount)

            // Remove completed items clears them cleanly
            repo.removeCompleted()
            assertTrue(repo.getAll().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `whole queue cancellation preserves already completed items and marks remaining cancelled`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = BatchQueueRepository(db.batchQueueDao())
            repo.appendItems(
                listOf(
                    makeEntity("done", "done.mp4"),
                    makeEntity("active", "active.mp4"),
                    makeEntity("queued", "queued.mp4")
                )
            )

            var caughtCancel = false
            try {
                BatchQueueProcessor.processQueue(
                    queueRepo = repo,
                    sharedSettings = GifConversionSettings.DEFAULT,
                    converter = object : BatchQueueProcessor.ItemConverter {
                        override suspend fun convertItem(
                            item: BatchQueueEntity,
                            effectiveSettings: GifConversionSettings,
                            onProgress: (Float, String, Boolean) -> Unit,
                            isItemCancelRequested: () -> Boolean
                        ): SavedGifResult {
                            if (item.id == "active") {
                                throw CancellationException("User cancelled whole queue")
                            }
                            return fakeSaved(item.displayName, effectiveSettings)
                        }
                    }
                )
            } catch (_: CancellationException) {
                caughtCancel = true
            }

            assertTrue(caughtCancel)
            val byId = repo.getAll().associateBy { it.id }
            assertEquals("COMPLETED", byId["done"]?.status)
            assertNotNull(byId["done"]?.savedUri)
            assertEquals("CANCELLED", byId["active"]?.status)
            assertEquals("CANCELLED", byId["queued"]?.status)
        } finally {
            db.close()
        }
    }

    @Test
    fun `batch queue persists across db reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(System.getProperty("java.io.tmpdir"), "nepi_q_${System.nanoTime()}.db")
        val dbPath = dbFile.absolutePath
        try {
            val db1 = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbPath)
                .allowMainThreadQueries()
                .build()
            val repo1 = BatchQueueRepository(db1.batchQueueDao())
            repo1.appendItems(
                listOf(
                    makeEntity("p1", "persisted_video.mp4", MediaInputType.VIDEO),
                    makeEntity("p2", "persisted_anim.gif", MediaInputType.GIF)
                )
            )
            db1.close()

            val db2 = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbPath)
                .allowMainThreadQueries()
                .build()
            val repo2 = BatchQueueRepository(db2.batchQueueDao())
            val reloaded = repo2.getAll()
            assertEquals(2, reloaded.size)
            assertEquals(listOf("p1", "p2"), reloaded.map { it.id })
            assertEquals(MediaInputType.GIF.name, reloaded[1].inputType)
            db2.close()
        } finally {
            dbFile.delete()
            File("$dbPath-wal").delete()
            File("$dbPath-shm").delete()
        }
    }
}
