package com.example

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.ConversionHistoryEntity
import com.example.data.ConversionHistorySnapshot
import com.example.data.ConversionHistorySnapshotCodec
import com.example.data.GifStudioDatabase
import com.example.data.UserPreferencesRepository
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.GifConversionSettings
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.VideoMetadata
import com.example.engine.WebPQualityPreset
import com.example.ui.components.AndroidStudioHapticsController
import com.example.ui.components.SliderHapticDetentTracker
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioHapticsController
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsBottomSheet
import com.example.ui.theme.GifStudioTheme
import com.example.ui.theme.ThemeMode
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive Robolectric verification for:
 * - Feature 2: Haptic Feedback (controller gating, system preference respect, missing vibrator
 *   graceful handling, slider detent throttling, DataStore persistence, Settings toggle UI)
 * - Feature 3: Conversion History Settings Snapshots (GIF & WebP JSON codec, immutability when
 *   global preferences change, Room DB persistence, non-destructive MIGRATION_2_3 preserving
 *   legacy v2 records, deleted source/thumbnail resilience, compact History cards + 6-section
 *   View Details bottom sheet + "Settings unavailable" for legacy entries)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class HapticsAndHistoryRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -------------------------------------------------------------------------
    // Haptic Feedback Unit & UI Tests
    // -------------------------------------------------------------------------

    @Test
    fun `haptics controller respects user setting, system setting, and missing vibrator hardware`() {
        val dummyView = View(context)
        val triggered = mutableListOf<Int>()

        // 1. Enabled user preference + system enabled + vibrator present -> triggers feedback
        val enabledCtrl = AndroidStudioHapticsController(
            view = dummyView,
            context = context,
            userPreferenceEnabled = true,
            systemHapticsProvider = { true },
            hasVibratorProvider = { true },
            performFeedbackCallback = { constant ->
                triggered.add(constant)
                true
            }
        )
        assertTrue(enabledCtrl.isEffectivelyEnabled)
        assertTrue(enabledCtrl.perform(StudioHapticEvent.SELECTION))
        assertTrue(enabledCtrl.perform(StudioHapticEvent.EXPORT_SUCCESS))
        assertEquals(2, triggered.size)

        // 2. Disabled user preference -> suppresses feedback
        triggered.clear()
        val disabledCtrl = AndroidStudioHapticsController(
            view = dummyView,
            context = context,
            userPreferenceEnabled = false,
            systemHapticsProvider = { true },
            hasVibratorProvider = { true },
            performFeedbackCallback = {
                triggered.add(it)
                true
            }
        )
        assertFalse(disabledCtrl.isEffectivelyEnabled)
        assertFalse(disabledCtrl.perform(StudioHapticEvent.PRIMARY_ACTION))
        assertTrue(triggered.isEmpty())

        // 3. System haptics OFF always suppresses feedback even when user pref is true
        val systemOffCtrl = AndroidStudioHapticsController(
            view = dummyView,
            context = context,
            userPreferenceEnabled = true,
            systemHapticsProvider = { false },
            hasVibratorProvider = { true },
            performFeedbackCallback = {
                triggered.add(it)
                true
            }
        )
        assertFalse(systemOffCtrl.isEffectivelyEnabled)
        assertFalse(systemOffCtrl.perform(StudioHapticEvent.HANDLE_GRAB))
        assertTrue(triggered.isEmpty())

        // 4. Null user preference defaults to following Android system setting
        val followSystemOn = AndroidStudioHapticsController(
            view = dummyView,
            context = context,
            userPreferenceEnabled = null,
            systemHapticsProvider = { true },
            hasVibratorProvider = { true },
            performFeedbackCallback = { true }
        )
        assertTrue(followSystemOn.isEffectivelyEnabled)

        // 5. Missing vibrator hardware gracefully returns false without exception
        val noVibratorCtrl = AndroidStudioHapticsController(
            view = dummyView,
            context = context,
            userPreferenceEnabled = true,
            systemHapticsProvider = { true },
            hasVibratorProvider = { false },
            performFeedbackCallback = { true }
        )
        assertFalse(noVibratorCtrl.isEffectivelyEnabled)
        assertFalse(noVibratorCtrl.perform(StudioHapticEvent.CONVERSION_START))
    }

    @Test
    fun `haptics event constant mapping covers legacy and modern Android SDK levels`() {
        for (event in StudioHapticEvent.entries) {
            val api24 = AndroidStudioHapticsController.mapEventToConstant(event, Build.VERSION_CODES.N)
            val api30 = AndroidStudioHapticsController.mapEventToConstant(event, Build.VERSION_CODES.R)
            val api34 = AndroidStudioHapticsController.mapEventToConstant(event, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            assertTrue(api24 >= 0)
            assertTrue(api30 >= 0)
            assertTrue(api34 >= 0)
        }
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            AndroidStudioHapticsController.mapEventToConstant(StudioHapticEvent.SELECTION, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertEquals(
            HapticFeedbackConstants.CONFIRM,
            AndroidStudioHapticsController.mapEventToConstant(StudioHapticEvent.EXPORT_SUCCESS, Build.VERSION_CODES.R)
        )
        assertEquals(
            HapticFeedbackConstants.REJECT,
            AndroidStudioHapticsController.mapEventToConstant(StudioHapticEvent.ERROR, Build.VERSION_CODES.R)
        )
    }

    @Test
    fun `slider detent tracker avoids micro-drag spam and fires on meaningful intervals and release`() {
        var nowMs = 1000L
        val events = mutableListOf<StudioHapticEvent>()
        val recorder = object : StudioHapticsController {
            override val isEffectivelyEnabled: Boolean = true
            override fun perform(event: StudioHapticEvent): Boolean {
                events.add(event)
                return true
            }
        }

        val tracker = SliderHapticDetentTracker(
            stepInterval = 5f,
            snapValues = listOf(50f, 75f, 100f),
            minIntervalMs = 45L,
            clockMs = { nowMs }
        )

        // Initial touch establishes bucket -> no spam tick
        assertFalse(tracker.onValueChanged(70f, recorder))
        // Micro drag inside same bucket -> no tick
        nowMs += 60L
        assertFalse(tracker.onValueChanged(71f, recorder))
        assertFalse(tracker.onValueChanged(72f, recorder))

        // Crossing into snap value (75f) after cooldown -> triggers single detent
        nowMs += 60L
        assertTrue(tracker.onValueChanged(75f, recorder))
        assertEquals(listOf(StudioHapticEvent.SLIDER_DETENT), events)

        // Rapid drag within 45ms cooldown -> suppressed
        nowMs += 10L
        assertFalse(tracker.onValueChanged(82f, recorder))

        // Crossing next interval after cooldown -> triggers detent
        nowMs += 60L
        assertTrue(tracker.onValueChanged(88f, recorder))

        // Releasing slider -> triggers HANDLE_RELEASE
        assertTrue(tracker.onValueChangeFinished(recorder))
        assertEquals(
            listOf(
                StudioHapticEvent.SLIDER_DETENT,
                StudioHapticEvent.SLIDER_DETENT,
                StudioHapticEvent.HANDLE_RELEASE
            ),
            events
        )
    }

    @Test
    fun `user preferences persist haptics toggle and WebP format defaults in DataStore`() = runBlocking {
        val repo = UserPreferencesRepository(context)

        repo.setHapticsEnabled(false)
        assertEquals(false, repo.hapticsPreferenceFlow.first())

        repo.setHapticsEnabled(true)
        assertEquals(true, repo.hapticsPreferenceFlow.first())

        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        assertFalse(UserPreferencesRepository.resolveEffectiveHapticsEnabled(context, userPreference = true))
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        assertTrue(UserPreferencesRepository.resolveEffectiveHapticsEnabled(context, userPreference = null))

        val webpSettings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = WebPQualityPreset.HIGH,
            webpQuality = 88
        )
        repo.saveLastUsedSettings(webpSettings)
        val restored = repo.savedSettingsFlow.first()
        assertEquals(OutputFormat.WEBP, restored.outputFormat)
        assertEquals(WebPQualityPreset.HIGH, restored.webpQualityPreset)
        assertEquals(88, restored.webpQuality)
    }

    @Test
    fun `settings bottom sheet displays and toggles Haptic Feedback preference`() {
        var hapticsEnabled = true
        var lastToggled: Boolean? = null

        composeRule.setContent {
            GifStudioTheme {
                SettingsBottomSheet(
                    currentThemeMode = ThemeMode.DARK,
                    onSelectThemeMode = {},
                    onResetDefaults = {},
                    onDismiss = {},
                    hapticsEnabled = hapticsEnabled,
                    onToggleHaptics = { next ->
                        hapticsEnabled = next
                        lastToggled = next
                    }
                )
            }
        }

        composeRule.onNodeWithText("Haptic Feedback").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_haptics_toggle")
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()

        assertEquals(false, lastToggled)
    }

    // -------------------------------------------------------------------------
    // Conversion History Snapshot, Room Persistence & Non-Destructive Migration
    // -------------------------------------------------------------------------

    @Test
    fun `snapshot codec stores only format-relevant settings for GIF and Animated WebP`() {
        val videoMeta = VideoMetadata(
            uri = Uri.parse("content://media/video/1"),
            displayName = "beach_sunset.mp4",
            durationMs = 5000L,
            rawWidth = 1920,
            rawHeight = 1080,
            metadataRotation = 0,
            frameRate = 30f,
            fileSizeBytes = 8_000_000L,
            inputType = MediaInputType.VIDEO
        )
        val gifSettings = GifConversionSettings(
            outputFormat = OutputFormat.GIF,
            resolutionPreset = ResolutionPreset.P480,
            useOriginalFps = false,
            fps = 15f,
            colorQualityPreset = ColorQualityPreset.C128,
            ditherMode = DitherMode.FLOYD_STEINBERG,
            speed = 1.5f,
            reverse = true,
            pingPong = false,
            loopCount = 0,
            aspectRatioPreset = AspectRatioPreset.SQUARE_1_1,
            cropRect = NormalizedCropRect(0.1f, 0.0f, 0.9f, 1.0f),
            rotationDegrees = 90,
            trimStartMs = 500L,
            trimEndMs = 3500L
        )
        val gifStats = EncodedGifStats(
            width = 480,
            height = 480,
            fps = 15f,
            durationMs = 2000L,
            frameCount = 30,
            fileSizeBytes = 940_000L,
            effectiveColorCount = 128,
            ditherMode = DitherMode.FLOYD_STEINBERG,
            outputFormat = OutputFormat.GIF
        )

        val gifSnap = ConversionHistorySnapshot.fromConversion(videoMeta, gifSettings, gifStats)
        val gifJson = ConversionHistorySnapshotCodec.encode(gifSnap)
        val gifDecoded = ConversionHistorySnapshotCodec.decode(gifJson)

        assertNotNull(gifDecoded)
        assertEquals(gifSnap, gifDecoded)
        assertEquals("Video → GIF", gifDecoded!!.directionLabel)
        assertEquals(128, gifDecoded.effectiveColorCount)
        assertEquals(DitherMode.FLOYD_STEINBERG, gifDecoded.ditherMode)
        assertNull("GIF snapshot must not populate WebP quality preset", gifDecoded.webpQualityPreset)
        assertNull("GIF snapshot must not populate WebP quality", gifDecoded.webpQuality)
        assertNull("GIF snapshot must not populate WebP lossless flag", gifDecoded.webpLossless)

        // Animated WebP from GIF input
        val gifMeta = videoMeta.copy(
            displayName = "sticker.gif",
            inputType = MediaInputType.GIF,
            hasTransparency = true
        )
        val webpSettings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.ORIGINAL,
            useOriginalFps = true,
            webpQualityPreset = WebPQualityPreset.HIGH,
            webpQuality = 88,
            speed = 1f,
            loopCount = 3
        )
        val webpStats = EncodedGifStats(
            width = 512,
            height = 512,
            fps = 20f,
            durationMs = 1500L,
            frameCount = 30,
            fileSizeBytes = 190_000L,
            effectiveColorCount = 0,
            ditherMode = DitherMode.NONE,
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = WebPQualityPreset.HIGH,
            webpQuality = 88,
            hasTransparency = true
        )

        val webpSnap = ConversionHistorySnapshot.fromConversion(gifMeta, webpSettings, webpStats)
        val webpJson = ConversionHistorySnapshotCodec.encode(webpSnap)
        val webpDecoded = ConversionHistorySnapshotCodec.decode(webpJson)

        assertNotNull(webpDecoded)
        assertEquals(webpSnap, webpDecoded)
        assertEquals("GIF → WebP", webpDecoded!!.directionLabel)
        assertEquals(WebPQualityPreset.HIGH, webpDecoded.webpQualityPreset)
        assertEquals(88, webpDecoded.webpQuality)
        assertEquals(false, webpDecoded.webpLossless)
        assertTrue(webpDecoded.outputHasTransparency)
        assertNull("WebP snapshot must not populate GIF color preset", webpDecoded.colorQualityPreset)
        assertNull("WebP snapshot must not populate GIF dithering", webpDecoded.ditherMode)
    }

    @Test
    fun `history snapshot persists across db reopen`() = runBlocking {
        val dbName = "hp.db"
        context.deleteDatabase(dbName)
        val repo = UserPreferencesRepository(context)
        val meta = VideoMetadata(
            uri = Uri.parse("content://media/video/99"),
            displayName = "immutable_source.mp4",
            durationMs = 2000L,
            rawWidth = 640,
            rawHeight = 360,
            metadataRotation = 0,
            frameRate = 24f,
            fileSizeBytes = 1_000_000L,
            inputType = MediaInputType.VIDEO
        )
        val originalSettings = GifConversionSettings(
            outputFormat = OutputFormat.WEBP,
            resolutionPreset = ResolutionPreset.P360,
            webpQualityPreset = WebPQualityPreset.SMALLER,
            webpQuality = 52
        )
        val stats = EncodedGifStats(
            width = 640,
            height = 360,
            fps = 24f,
            durationMs = 2000L,
            frameCount = 48,
            fileSizeBytes = 140_000L,
            effectiveColorCount = 0,
            ditherMode = DitherMode.NONE,
            outputFormat = OutputFormat.WEBP,
            webpQualityPreset = WebPQualityPreset.SMALLER,
            webpQuality = 52
        )
        val snapshotJson = ConversionHistorySnapshotCodec.encode(
            ConversionHistorySnapshot.fromConversion(meta, originalSettings, stats)
        )

        val db1 = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbName)
            .addMigrations(GifStudioDatabase.MIGRATION_1_2, GifStudioDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        db1.historyDao().insert(
            ConversionHistoryEntity(
                contentUri = "content://media/external/images/media/501",
                fileName = "NepiGifLab_immutable_source.webp",
                thumbnailPath = "/nonexistent/deleted_thumb.jpg",
                fileSizeBytes = stats.fileSizeBytes,
                width = stats.width,
                height = stats.height,
                fps = stats.fps,
                durationMs = stats.durationMs,
                frameCount = stats.frameCount,
                colorCount = 0,
                ditherModeName = "Smaller (52)",
                sourceVideoName = "immutable_source.mp4",
                outputFormat = OutputFormat.WEBP.name,
                sourceInputType = MediaInputType.VIDEO.name,
                settingsSnapshotJson = snapshotJson
            )
        )
        db1.close()

        // Mutate global user preferences afterward
        repo.saveLastUsedSettings(
            GifConversionSettings(
                outputFormat = OutputFormat.GIF,
                resolutionPreset = ResolutionPreset.P240,
                webpQualityPreset = WebPQualityPreset.LOSSLESS,
                webpQuality = 100
            )
        )

        // Reopen database and verify saved snapshot is completely unchanged
        val db2 = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbName)
            .addMigrations(GifStudioDatabase.MIGRATION_1_2, GifStudioDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val all = db2.historyDao().observeAll().first()
            assertEquals(1, all.size)
            val saved = all.first()
            assertEquals(OutputFormat.WEBP, saved.parsedOutputFormat)
            val parsed = saved.parsedSnapshot()
            assertNotNull(parsed)
            assertEquals(WebPQualityPreset.SMALLER, parsed!!.webpQualityPreset)
            assertEquals(52, parsed.webpQuality)
            assertEquals(ResolutionPreset.P360, parsed.resolutionPreset)
        } finally {
            db2.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `room migration 2 to 3 preserves legacy records`() = runBlocking {
        val dbName = "m23.db"
        context.deleteDatabase(dbName)

        // 1. Create a real version-2 SQLite database directly
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `conversion_history` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `contentUri` TEXT NOT NULL,
                            `fileName` TEXT NOT NULL,
                            `thumbnailPath` TEXT,
                            `createdAtMillis` INTEGER NOT NULL,
                            `fileSizeBytes` INTEGER NOT NULL,
                            `width` INTEGER NOT NULL,
                            `height` INTEGER NOT NULL,
                            `fps` REAL NOT NULL,
                            `durationMs` INTEGER NOT NULL,
                            `frameCount` INTEGER NOT NULL,
                            `colorCount` INTEGER NOT NULL,
                            `ditherModeName` TEXT NOT NULL,
                            `sourceVideoName` TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    GifStudioDatabase.MIGRATION_1_2.migrate(db)

                    // Insert a legacy v2 history row (no snapshot column)
                    db.execSQL(
                        """
                        INSERT INTO `conversion_history` (
                            `id`, `contentUri`, `fileName`, `thumbnailPath`, `createdAtMillis`,
                            `fileSizeBytes`, `width`, `height`, `fps`, `durationMs`,
                            `frameCount`, `colorCount`, `ditherModeName`, `sourceVideoName`
                        ) VALUES (
                            1, 'content://media/legacy/1', 'NepiGifLab_legacy.gif', NULL, 1700000000000,
                            320000, 360, 240, 15.0, 2500, 38, 128, 'Floyd–Steinberg', 'old_clip.mp4'
                        )
                        """.trimIndent()
                    )
                    // Insert a legacy v2 batch queue row
                    db.execSQL(
                        """
                        INSERT INTO `batch_queue_items` (
                            `id`, `sortOrder`, `uri`, `displayName`, `durationMs`, `width`, `height`,
                            `inputType`, `thumbnailPath`, `status`, `progress`, `isIndeterminate`,
                            `stage`, `errorMessage`, `savedUri`, `savedFileName`, `savedWidth`,
                            `savedHeight`, `savedFps`, `savedDurationMs`, `savedFrameCount`,
                            `savedFileSizeBytes`, `savedColorCount`, `savedDitherMode`,
                            `savedThumbnailPath`, `customSettingsEncoded`, `cancelRequested`
                        ) VALUES (
                            'legacy-batch-1', 0, 'content://media/video/1', 'clip.mp4', 2000, 640, 360,
                            'VIDEO', NULL, 'COMPLETED', 1.0, 0, 'Saved', NULL,
                            'content://media/gif/1', 'clip.gif', 360, 202, 15.0, 2000, 30,
                            180000, 128, 'None', NULL, NULL, 0
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val rawHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        rawHelper.writableDatabase.close()
        rawHelper.close()

        // 2. Open with Room v3 using MIGRATION_1_2 and MIGRATION_2_3 (NO destructive fallback!)
        val migratedDb = Room.databaseBuilder(context, GifStudioDatabase::class.java, dbName)
            .addMigrations(GifStudioDatabase.MIGRATION_1_2, GifStudioDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val legacyRows = migratedDb.historyDao().observeAll().first()
            assertEquals("Legacy row must be preserved by MIGRATION_2_3", 1, legacyRows.size)
            val legacy = legacyRows.first()
            assertEquals("NepiGifLab_legacy.gif", legacy.fileName)
            assertEquals("old_clip.mp4", legacy.sourceVideoName)
            assertEquals(OutputFormat.GIF.name, legacy.outputFormat)
            assertEquals(MediaInputType.VIDEO.name, legacy.sourceInputType)
            assertNull("Legacy v2 row should have null snapshot JSON", legacy.settingsSnapshotJson)
            assertNull(legacy.parsedSnapshot())

            val batchRows = migratedDb.batchQueueDao().getAll()
            assertEquals(1, batchRows.size)
            assertEquals(OutputFormat.GIF.name, batchRows.first().savedOutputFormat)

            // Verify new v3 WebP history row inserts cleanly alongside legacy row
            migratedDb.historyDao().insert(
                ConversionHistoryEntity(
                    contentUri = "content://media/v3/2",
                    fileName = "NepiGifLab_new.webp",
                    thumbnailPath = null,
                    createdAtMillis = 1700000100000L,
                    fileSizeBytes = 125_000L,
                    width = 480,
                    height = 270,
                    fps = 20f,
                    durationMs = 1500L,
                    frameCount = 30,
                    colorCount = 0,
                    ditherModeName = "Balanced (75)",
                    sourceVideoName = "new_clip.mp4",
                    outputFormat = OutputFormat.WEBP.name,
                    sourceInputType = MediaInputType.VIDEO.name,
                    settingsSnapshotJson = "{}"
                )
            )
            assertEquals(2, migratedDb.historyDao().observeAll().first().size)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `history screen displays compact cards, opens 6-section details sheet, and handles legacy records gracefully`() {
        val gifSnapshot = ConversionHistorySnapshot(
            sourceFileName = "deleted_original_video.mp4",
            sourceType = MediaInputType.VIDEO,
            sourceDurationMs = 4000L,
            sourceWidth = 1280,
            sourceHeight = 720,
            sourceFps = 30f,
            sourceIsVariableTiming = false,
            outputFormat = OutputFormat.GIF,
            outputWidth = 480,
            outputHeight = 270,
            outputFps = 15f,
            outputDurationMs = 3000L,
            outputFileSizeBytes = 680_000L,
            outputFrameCount = 45,
            outputHasTransparency = false,
            resolutionPreset = ResolutionPreset.P240,
            customResolutionShortEdge = null,
            useOriginalFps = false,
            manualFps = 15f,
            detectedSourceFps = null,
            speed = 1f,
            reverse = false,
            pingPong = true,
            loopCount = 0,
            aspectRatioPreset = AspectRatioPreset.RATIO_16_9,
            cropRect = NormalizedCropRect.FULL,
            rotationDegrees = 0,
            trimStartMs = 500L,
            trimEndMs = 3500L,
            colorQualityPreset = ColorQualityPreset.C128,
            requestedColorCount = 128,
            effectiveColorCount = 128,
            ditherMode = DitherMode.FLOYD_STEINBERG,
            optimizeOutput = true
        )

        val webpSnapshot = ConversionHistorySnapshot(
            sourceFileName = "deleted_source_sticker.gif",
            sourceType = MediaInputType.GIF,
            sourceDurationMs = 1500L,
            sourceWidth = 320,
            sourceHeight = 320,
            sourceFps = 20f,
            sourceIsVariableTiming = true,
            outputFormat = OutputFormat.WEBP,
            outputWidth = 320,
            outputHeight = 320,
            outputFps = 20f,
            outputDurationMs = 1500L,
            outputFileSizeBytes = 142_000L,
            outputFrameCount = 30,
            outputHasTransparency = true,
            resolutionPreset = ResolutionPreset.ORIGINAL,
            customResolutionShortEdge = null,
            useOriginalFps = true,
            manualFps = null,
            detectedSourceFps = 20f,
            speed = 1f,
            reverse = false,
            pingPong = false,
            loopCount = 2,
            aspectRatioPreset = AspectRatioPreset.ORIGINAL,
            cropRect = NormalizedCropRect.FULL,
            rotationDegrees = 0,
            trimStartMs = 0L,
            trimEndMs = 1500L,
            webpQualityPreset = WebPQualityPreset.HIGH,
            webpQuality = 88,
            webpLossless = false
        )

        val items = listOf(
            ConversionHistoryEntity(
                id = 10L,
                contentUri = "content://media/external/images/media/10",
                fileName = "NepiGifLab_webp_sticker.webp",
                thumbnailPath = "/nonexistent/deleted_thumb_10.png",
                fileSizeBytes = 142_000L,
                width = 320,
                height = 320,
                fps = 20f,
                durationMs = 1500L,
                frameCount = 30,
                colorCount = 0,
                ditherModeName = "High (88)",
                sourceVideoName = "deleted_source_sticker.gif",
                outputFormat = OutputFormat.WEBP.name,
                sourceInputType = MediaInputType.GIF.name,
                settingsSnapshotJson = ConversionHistorySnapshotCodec.encode(webpSnapshot)
            ),
            ConversionHistoryEntity(
                id = 11L,
                contentUri = "content://media/external/images/media/11",
                fileName = "NepiGifLab_gif_clip.gif",
                thumbnailPath = null,
                fileSizeBytes = 680_000L,
                width = 480,
                height = 270,
                fps = 15f,
                durationMs = 3000L,
                frameCount = 45,
                colorCount = 128,
                ditherModeName = "Floyd–Steinberg",
                sourceVideoName = "deleted_original_video.mp4",
                outputFormat = OutputFormat.GIF.name,
                sourceInputType = MediaInputType.VIDEO.name,
                settingsSnapshotJson = ConversionHistorySnapshotCodec.encode(gifSnapshot)
            ),
            ConversionHistoryEntity(
                id = 12L,
                contentUri = "content://media/external/images/media/12",
                fileName = "NepiGifLab_legacy_record.gif",
                thumbnailPath = null,
                fileSizeBytes = 250_000L,
                width = 360,
                height = 240,
                fps = 12f,
                durationMs = 2000L,
                frameCount = 24,
                colorCount = 64,
                ditherModeName = "Ordered",
                sourceVideoName = "old_video.mp4",
                outputFormat = OutputFormat.GIF.name,
                sourceInputType = MediaInputType.VIDEO.name,
                settingsSnapshotJson = null
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                HistoryScreen(
                    items = items,
                    onBack = {},
                    onOpenGif = {},
                    onShareGif = {},
                    onDeleteItem = { _, _ -> },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Compact cards display direction badges & View Details action
        composeRule.onNodeWithText("GIF → WebP").assertIsDisplayed()
        composeRule.onAllNodesWithText("Video → GIF").onFirst().assertIsDisplayed()

        // 1. Open WebP entry details -> all 6 structured sections + WebP-specific settings
        composeRule.onNodeWithTag("history_details_10").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_details_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Source Information").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Output Information").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Resolution & Timing").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Color & Quality").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("High Quality").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Playback & Looping").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Crop & Trim").performScrollTo().assertIsDisplayed()
        // GIF-only labels should not appear in a WebP snapshot sheet
        composeRule.onNodeWithText("Dithering").assertDoesNotExist()
    }

    @Test
    fun `history details sheet displays Settings unavailable for legacy record without snapshot`() {
        val legacyItem = ConversionHistoryEntity(
            id = 42L,
            contentUri = "content://media/external/images/media/42",
            fileName = "NepiGifLab_legacy_v2.gif",
            thumbnailPath = null,
            fileSizeBytes = 250_000L,
            width = 360,
            height = 240,
            fps = 12f,
            durationMs = 2000L,
            frameCount = 24,
            colorCount = 64,
            ditherModeName = "Ordered",
            sourceVideoName = "old_video.mp4",
            outputFormat = OutputFormat.GIF.name,
            sourceInputType = MediaInputType.VIDEO.name,
            settingsSnapshotJson = null
        )

        composeRule.setContent {
            GifStudioTheme {
                HistoryScreen(
                    items = listOf(legacyItem),
                    onBack = {},
                    onOpenGif = {},
                    onShareGif = {},
                    onDeleteItem = { _, _ -> },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithTag("history_details_42").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_settings_unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Settings unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("old_video.mp4").performScrollTo().assertIsDisplayed()
    }
}
