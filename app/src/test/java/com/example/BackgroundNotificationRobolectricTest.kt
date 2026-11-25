package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionSettings
import com.example.engine.ResolutionPreset
import com.example.worker.ConversionActionReceiver
import com.example.worker.ConversionNotificationHelper
import com.example.worker.GifConversionWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Verifies Stage 1: active conversion notifications, completion notifications (filename, size,
 * Open & Share actions), failure notifications (meaningful error, Retry action), batch
 * notifications, notification Cancel/Retry actions, and Android 13+ POST_NOTIFICATIONS denial.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackgroundNotificationRobolectricTest {

    private lateinit var context: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        ConversionActionReceiver.testDelegate = null
    }

    @Test
    fun `active single conversion notification shows filename, real progress, stage, tap intent and Cancel action`() {
        val workId = UUID.randomUUID()
        val notification = ConversionNotificationHelper.buildActiveSingleNotification(
            context = context,
            workId = workId,
            sourceName = "vacation_clip.mp4",
            progressPercent = 42,
            stage = "Encoding GIF frame 21 of 50",
            indeterminate = false
        )

        val extras = notification.extras
        assertEquals("Converting vacation_clip.mp4", extras.getString(Notification.EXTRA_TITLE))
        assertEquals("42% · Encoding GIF frame 21 of 50", extras.getString(Notification.EXTRA_TEXT))
        assertEquals(100, extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(42, extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertNotNull("Tap action must open active conversion screen", notification.contentIntent)

        val cancelAction = notification.actions?.firstOrNull { it.title == "Cancel Conversion" }
        assertNotNull("Active conversion notification must include Cancel Conversion action", cancelAction)
        assertNotNull(cancelAction?.actionIntent)

        // Indeterminate state must NOT fabricate percentage
        val indet = ConversionNotificationHelper.buildActiveSingleNotification(
            context = context,
            workId = workId,
            sourceName = "vacation_clip.mp4",
            progressPercent = 0,
            stage = "Preparing video frames…",
            indeterminate = true
        )
        assertTrue(indet.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("Preparing video frames…", indet.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `completion notification includes filename, actual file size, Open tap intent and Share action`() {
        val uri = Uri.parse("content://media/external/images/media/501")
        val posted = ConversionNotificationHelper.postCompletionNotification(
            context = context,
            outputFileName = "NepiGifLab_demo_20261125.gif",
            fileSizeBytes = 1_572_864L, // 1.50 MB
            contentUri = uri,
            notificationId = 9001
        )
        assertTrue(posted)

        val shadowNm = Shadows.shadowOf(notificationManager)
        val n = shadowNm.getNotification(9001)
        assertNotNull(n)
        assertTrue(n.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("1.50 MB"))
        assertEquals("NepiGifLab_demo_20261125.gif", n.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(n.contentIntent)
        val actionTitles = n.actions?.map { it.title.toString() }.orEmpty()
        assertTrue("Completion notification must include Open action", actionTitles.contains("Open"))
        assertTrue("Completion notification must include Share action", actionTitles.contains("Share"))
    }

    @Test
    fun `failure notification shows meaningful error and Retry action, never reports success`() {
        val inputData = GifConversionWorker.buildInputData(
            videoUri = Uri.parse("content://test/corrupt.mp4"),
            settings = GifConversionSettings.DEFAULT,
            sourceName = "corrupt.mp4"
        )
        val posted = ConversionNotificationHelper.postFailureNotification(
            context = context,
            sourceName = "corrupt.mp4",
            errorMessage = "Insufficient storage space (8.0 MB free).",
            retryInputData = inputData,
            notificationId = 9002
        )
        assertTrue(posted)

        val n = Shadows.shadowOf(notificationManager).getNotification(9002)
        assertNotNull(n)
        val title = n.extras.getString(Notification.EXTRA_TITLE).orEmpty()
        assertTrue("Failure title must clearly indicate failure", title.contains("Failed"))
        assertFalse("Failure title must never report success", title.contains("Saved", ignoreCase = true))
        assertTrue(n.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("Insufficient storage space"))
        assertTrue(
            "Failure notification must include Retry action",
            n.actions?.any { it.title == "Retry" } == true
        )
    }

    @Test
    fun `batch notification displays overall progress, current filename, counts and Cancel Queue action`() {
        val activeBatch = ConversionNotificationHelper.buildActiveBatchNotification(
            context = context,
            currentFileName = "clip_two.mp4",
            overallPercent = 55,
            completedCount = 2,
            remainingCount = 3,
            stage = "Encoding GIF frame 10 of 20",
            indeterminate = false
        )
        assertEquals("Batch: clip_two.mp4", activeBatch.extras.getString(Notification.EXTRA_TITLE))
        val text = activeBatch.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("55%"))
        assertTrue(text.contains("Done 2"))
        assertTrue(text.contains("Remaining 3"))
        assertTrue(activeBatch.actions?.any { it.title == "Cancel Queue" } == true)

        val finishedPosted = ConversionNotificationHelper.postBatchFinishedNotification(
            context = context,
            completedCount = 4,
            failedCount = 1,
            cancelledCount = 0
        )
        assertTrue(finishedPosted)
        val finished = Shadows.shadowOf(notificationManager)
            .getNotification(ConversionNotificationHelper.NOTIFICATION_ID_BATCH_RESULT)
        assertNotNull(finished)
        assertTrue(finished.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("4 completed"))
        assertTrue(finished.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("1 failed"))
    }

    @Test
    fun `notification permission denial is handled gracefully without throwing`() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertFalse(ConversionNotificationHelper.canPostNotifications(context))
            val posted = ConversionNotificationHelper.postCompletionNotification(
                context = context,
                outputFileName = "denied_test.gif",
                fileSizeBytes = 10_000L,
                contentUri = Uri.parse("content://media/external/images/media/1"),
                notificationId = 9003
            )
            assertFalse("Posting should gracefully return false when POST_NOTIFICATIONS denied", posted)
            assertEquals(null, Shadows.shadowOf(notificationManager).getNotification(9003))
        }
    }

    @Test
    fun `ConversionActionReceiver handles cancel single, cancel batch, and retry single from notifications`() {
        val cancelledIds = mutableListOf<UUID>()
        var batchCancelled = false
        var retriedData: Data? = null

        ConversionActionReceiver.testDelegate = object : ConversionActionReceiver.ActionDelegate {
            override fun cancelWorkById(context: Context, workId: UUID) {
                cancelledIds.add(workId)
            }

            override fun cancelBatchQueue(context: Context) {
                batchCancelled = true
            }

            override fun enqueueRetrySingle(context: Context, inputData: Data) {
                retriedData = inputData
            }
        }

        val workId = UUID.randomUUID()
        val activeNotif = ConversionNotificationHelper.buildActiveSingleNotification(
            context = context,
            workId = workId,
            sourceName = "a.mp4",
            progressPercent = 20,
            stage = "Encoding…",
            indeterminate = false
        )
        notificationManager.notify(ConversionNotificationHelper.NOTIFICATION_ID_SINGLE_ACTIVE, activeNotif)

        val receiver = ConversionActionReceiver()

        // 1. Cancel active single conversion
        receiver.onReceive(
            context,
            Intent(ConversionActionReceiver.ACTION_CANCEL_WORK).apply {
                putExtra(ConversionActionReceiver.EXTRA_WORK_ID, workId.toString())
            }
        )
        assertEquals(listOf(workId), cancelledIds)
        assertEquals(
            null,
            Shadows.shadowOf(notificationManager).getNotification(ConversionNotificationHelper.NOTIFICATION_ID_SINGLE_ACTIVE)
        )

        // 2. Cancel batch queue
        val activeBatch = ConversionNotificationHelper.buildActiveBatchNotification(
            context, "b.mp4", 40, 1, 2, "Encoding…", false
        )
        notificationManager.notify(ConversionNotificationHelper.NOTIFICATION_ID_BATCH_ACTIVE, activeBatch)
        receiver.onReceive(context, Intent(ConversionActionReceiver.ACTION_CANCEL_BATCH))
        assertTrue(batchCancelled)
        assertEquals(
            null,
            Shadows.shadowOf(notificationManager).getNotification(ConversionNotificationHelper.NOTIFICATION_ID_BATCH_ACTIVE)
        )

        // 3. Retry failed single conversion preserves settings
        val originalSettings = GifConversionSettings(
            resolutionPreset = ResolutionPreset.P480,
            colorQualityPreset = ColorQualityPreset.C64,
            ditherMode = DitherMode.ORDERED,
            useOriginalFps = false,
            fps = 12f
        )
        val retryIntent = Intent(ConversionActionReceiver.ACTION_RETRY_SINGLE).apply {
            putExtra(ConversionActionReceiver.EXTRA_NOTIFICATION_ID, 9002)
            putExtra(GifConversionWorker.KEY_VIDEO_URI, "content://test/retry.mp4")
            putExtra(GifConversionWorker.KEY_SOURCE_NAME, "retry.mp4")
            putExtra(GifConversionWorker.KEY_RESOLUTION_PRESET, originalSettings.resolutionPreset.name)
            putExtra(GifConversionWorker.KEY_COLOR_PRESET, originalSettings.colorQualityPreset.name)
            putExtra(GifConversionWorker.KEY_DITHER, originalSettings.ditherMode.name)
            putExtra(GifConversionWorker.KEY_USE_ORIGINAL_FPS, false)
            putExtra(GifConversionWorker.KEY_FPS, 12f)
        }
        receiver.onReceive(context, retryIntent)
        assertNotNull(retriedData)
        val parsed = GifConversionWorker.parseSettings(retriedData!!)
        assertEquals(ResolutionPreset.P480, parsed.resolutionPreset)
        assertEquals(ColorQualityPreset.C64, parsed.colorQualityPreset)
        assertEquals(DitherMode.ORDERED, parsed.ditherMode)
        assertEquals(false, parsed.useOriginalFps)
        assertEquals(12f, parsed.fps, 0.01f)
    }
}
