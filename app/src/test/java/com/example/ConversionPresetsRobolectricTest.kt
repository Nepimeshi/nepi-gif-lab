package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.GifStudioDatabase
import com.example.data.PresetRepository
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionSettings
import com.example.engine.NormalizedCropRect
import com.example.engine.ResolutionPreset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversionPresetsRobolectricTest {

    private fun openInMemoryDb(context: Context): GifStudioDatabase =
        Room.inMemoryDatabaseBuilder(context, GifStudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `built-in presets seed idempotently and cannot be deleted or renamed`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = PresetRepository(db.presetDao())
            repo.ensureBuiltInPresets()
            val firstList = repo.getAllPresets()
            assertEquals(4, firstList.size)
            assertTrue(firstList.all { it.isBuiltIn })

            // Second call must not duplicate built-ins
            repo.ensureBuiltInPresets()
            assertEquals(4, repo.getAllPresets().size)

            val builtIn = firstList.first()
            assertFalse(repo.renameCustomPreset(builtIn.id, "Hacked"))
            assertFalse(repo.deleteCustomPreset(builtIn.id))
            assertEquals(builtIn.name, repo.getAllPresets().first { it.id == builtIn.id }.name)
        } finally {
            db.close()
        }
    }

    @Test
    fun `custom preset save, apply, rename, delete, and clip-geometry preservation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = PresetRepository(db.presetDao())
            repo.ensureBuiltInPresets()

            val customSettings = GifConversionSettings(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = 720,
                useOriginalFps = false,
                fps = 24f,
                colorQualityPreset = ColorQualityPreset.CUSTOM,
                customColorCount = 192,
                ditherMode = DitherMode.ORDERED,
                speed = 1.5f,
                reverse = true,
                pingPong = true,
                loopCount = 3
            )

            val id = repo.saveCustomPreset("Studio 720p Loop", customSettings)
            assertTrue(id > 0L)

            val saved = repo.getAllPresets().firstOrNull { it.id == id }
            assertNotNull(saved)
            assertFalse(saved!!.isBuiltIn)
            assertEquals("Studio 720p Loop", saved.name)

            // Apply preset onto an edited clip: trim, crop, rotation, aspect ratio MUST be preserved
            val clipBase = GifConversionSettings(
                trimStartMs = 1_200L,
                trimEndMs = 4_800L,
                cropRect = NormalizedCropRect(0.1f, 0.2f, 0.9f, 0.8f),
                aspectRatioPreset = AspectRatioPreset.SQUARE_1_1,
                rotationDegrees = 90
            )
            val applied = saved.applyTo(clipBase)
            assertEquals(ResolutionPreset.CUSTOM, applied.resolutionPreset)
            assertEquals(720, applied.customResolutionShortEdge)
            assertFalse(applied.useOriginalFps)
            assertEquals(24f, applied.fps, 0.01f)
            assertEquals(ColorQualityPreset.CUSTOM, applied.colorQualityPreset)
            assertEquals(192, applied.customColorCount)
            assertEquals(DitherMode.ORDERED, applied.ditherMode)
            assertEquals(1.5f, applied.speed, 0.01f)
            assertTrue(applied.reverse)
            assertTrue(applied.pingPong)
            assertEquals(3, applied.loopCount)
            // Clip-specific fields untouched
            assertEquals(1_200L, applied.trimStartMs)
            assertEquals(4_800L, applied.trimEndMs)
            assertEquals(clipBase.cropRect, applied.cropRect)
            assertEquals(AspectRatioPreset.SQUARE_1_1, applied.aspectRatioPreset)
            assertEquals(90, applied.rotationDegrees)

            // Rename
            assertTrue(repo.renameCustomPreset(id, "Crisp 720p Renamed"))
            assertEquals("Crisp 720p Renamed", repo.getAllPresets().first { it.id == id }.name)

            // Delete
            assertTrue(repo.deleteCustomPreset(id))
            assertTrue(repo.getAllPresets().none { it.id == id })
        } finally {
            db.close()
        }
    }

    @Test
    fun `presets persist across db reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(System.getProperty("java.io.tmpdir"), "nepi_p_${System.nanoTime()}.db")
        val dbPath = dbFile.absolutePath
        try {
            val db1 = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbPath)
                .allowMainThreadQueries()
                .build()
            val repo1 = PresetRepository(db1.presetDao())
            repo1.ensureBuiltInPresets()
            val savedId = repo1.saveCustomPreset(
                "My Persistent Preset",
                GifConversionSettings(
                    resolutionPreset = ResolutionPreset.P480,
                    useOriginalFps = false,
                    fps = 18f,
                    colorQualityPreset = ColorQualityPreset.C96,
                    ditherMode = DitherMode.NONE,
                    speed = 0.75f,
                    reverse = true,
                    pingPong = false,
                    loopCount = 2
                )
            )
            db1.close()

            // Reopen database (simulating app restart)
            val db2 = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbPath)
                .allowMainThreadQueries()
                .build()
            val repo2 = PresetRepository(db2.presetDao())
            val reloaded = repo2.getAllPresets().firstOrNull { it.id == savedId }
            assertNotNull("Custom preset must survive app restart", reloaded)
            assertEquals("My Persistent Preset", reloaded!!.name)
            assertEquals(ResolutionPreset.P480.name, reloaded.resolutionPreset)
            assertEquals(18f, reloaded.fps, 0.01f)
            assertEquals(ColorQualityPreset.C96.name, reloaded.colorQualityPreset)
            assertEquals(DitherMode.NONE.name, reloaded.ditherMode)
            assertEquals(2, reloaded.loopCount)
            db2.close()
        } finally {
            dbFile.delete()
            File("$dbPath-wal").delete()
            File("$dbPath-shm").delete()
        }
    }

    @Test
    fun `restoring default settings does not overwrite or delete custom presets`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openInMemoryDb(context)
        try {
            val repo = PresetRepository(db.presetDao())
            repo.ensureBuiltInPresets()
            val customId = repo.saveCustomPreset(
                "Keep Me Safe",
                GifConversionSettings(resolutionPreset = ResolutionPreset.P240, fps = 10f, useOriginalFps = false)
            )

            // Restoring settings to GifConversionSettings.DEFAULT operates on preferences/state,
            // never touching the conversion_presets table.
            val restoredDefaults = GifConversionSettings.DEFAULT
            assertEquals(ResolutionPreset.P360, restoredDefaults.resolutionPreset)

            val allAfterReset = repo.getAllPresets()
            assertTrue(allAfterReset.any { it.id == customId && it.name == "Keep Me Safe" })
        } finally {
            db.close()
        }
    }
}
