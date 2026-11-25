package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

/**
 * Handles notification actions for background GIF conversions:
 * - Cancel active single conversion
 * - Cancel entire batch conversion queue
 * - Retry a failed single conversion directly from the failure notification
 */
class ConversionActionReceiver : BroadcastReceiver() {

    internal interface ActionDelegate {
        fun cancelWorkById(context: Context, workId: UUID)
        fun cancelBatchQueue(context: Context)
        fun enqueueRetrySingle(context: Context, inputData: Data)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val delegate = testDelegate ?: defaultDelegate

        when (action) {
            ACTION_CANCEL_WORK -> {
                val idStr = intent.getStringExtra(EXTRA_WORK_ID) ?: return
                val workId = runCatching { UUID.fromString(idStr) }.getOrNull() ?: return
                delegate.cancelWorkById(context, workId)
                ConversionNotificationHelper.cancelNotification(
                    context,
                    ConversionNotificationHelper.NOTIFICATION_ID_SINGLE_ACTIVE
                )
            }

            ACTION_CANCEL_BATCH -> {
                delegate.cancelBatchQueue(context)
                ConversionNotificationHelper.cancelNotification(
                    context,
                    ConversionNotificationHelper.NOTIFICATION_ID_BATCH_ACTIVE
                )
            }

            ACTION_RETRY_SINGLE -> {
                val videoUri = intent.getStringExtra(GifConversionWorker.KEY_VIDEO_URI) ?: return
                val notifId = intent.getIntExtra(
                    EXTRA_NOTIFICATION_ID,
                    ConversionNotificationHelper.NOTIFICATION_ID_RESULT_BASE + 900
                )
                ConversionNotificationHelper.cancelNotification(context, notifId)

                val data = Data.Builder()
                    .putString(GifConversionWorker.KEY_VIDEO_URI, videoUri)
                    .putString(
                        GifConversionWorker.KEY_SOURCE_NAME,
                        intent.getStringExtra(GifConversionWorker.KEY_SOURCE_NAME)
                    )
                    .putString(
                        GifConversionWorker.KEY_RESOLUTION_PRESET,
                        intent.getStringExtra(GifConversionWorker.KEY_RESOLUTION_PRESET)
                    )
                    .putInt(
                        GifConversionWorker.KEY_CUSTOM_RES,
                        intent.getIntExtra(GifConversionWorker.KEY_CUSTOM_RES, 360)
                    )
                    .putString(
                        GifConversionWorker.KEY_COLOR_PRESET,
                        intent.getStringExtra(GifConversionWorker.KEY_COLOR_PRESET)
                    )
                    .putInt(
                        GifConversionWorker.KEY_CUSTOM_COLORS,
                        intent.getIntExtra(GifConversionWorker.KEY_CUSTOM_COLORS, 128)
                    )
                    .putString(
                        GifConversionWorker.KEY_DITHER,
                        intent.getStringExtra(GifConversionWorker.KEY_DITHER)
                    )
                    .putBoolean(
                        GifConversionWorker.KEY_USE_ORIGINAL_FPS,
                        intent.getBooleanExtra(GifConversionWorker.KEY_USE_ORIGINAL_FPS, true)
                    )
                    .putFloat(
                        GifConversionWorker.KEY_FPS,
                        intent.getFloatExtra(GifConversionWorker.KEY_FPS, 15f)
                    )
                    .putFloat(
                        GifConversionWorker.KEY_SPEED,
                        intent.getFloatExtra(GifConversionWorker.KEY_SPEED, 1f)
                    )
                    .putBoolean(
                        GifConversionWorker.KEY_REVERSE,
                        intent.getBooleanExtra(GifConversionWorker.KEY_REVERSE, false)
                    )
                    .putBoolean(
                        GifConversionWorker.KEY_PING_PONG,
                        intent.getBooleanExtra(GifConversionWorker.KEY_PING_PONG, false)
                    )
                    .putInt(
                        GifConversionWorker.KEY_ROTATION,
                        intent.getIntExtra(GifConversionWorker.KEY_ROTATION, 0)
                    )
                    .putFloat(
                        GifConversionWorker.KEY_CROP_L,
                        intent.getFloatExtra(GifConversionWorker.KEY_CROP_L, 0f)
                    )
                    .putFloat(
                        GifConversionWorker.KEY_CROP_T,
                        intent.getFloatExtra(GifConversionWorker.KEY_CROP_T, 0f)
                    )
                    .putFloat(
                        GifConversionWorker.KEY_CROP_R,
                        intent.getFloatExtra(GifConversionWorker.KEY_CROP_R, 1f)
                    )
                    .putFloat(
                        GifConversionWorker.KEY_CROP_B,
                        intent.getFloatExtra(GifConversionWorker.KEY_CROP_B, 1f)
                    )
                    .putLong(
                        GifConversionWorker.KEY_TRIM_START,
                        intent.getLongExtra(GifConversionWorker.KEY_TRIM_START, 0L)
                    )
                    .putLong(
                        GifConversionWorker.KEY_TRIM_END,
                        intent.getLongExtra(GifConversionWorker.KEY_TRIM_END, 0L)
                    )
                    .putInt(
                        GifConversionWorker.KEY_LOOP_COUNT,
                        intent.getIntExtra(GifConversionWorker.KEY_LOOP_COUNT, 0)
                    )
                    .putString(
                        GifConversionWorker.KEY_ASPECT_RATIO,
                        intent.getStringExtra(GifConversionWorker.KEY_ASPECT_RATIO)
                    )
                    .putString(
                        GifConversionWorker.KEY_OUTPUT_FORMAT,
                        intent.getStringExtra(GifConversionWorker.KEY_OUTPUT_FORMAT)
                    )
                    .putString(
                        GifConversionWorker.KEY_WEBP_QUALITY_PRESET,
                        intent.getStringExtra(GifConversionWorker.KEY_WEBP_QUALITY_PRESET)
                    )
                    .putInt(
                        GifConversionWorker.KEY_WEBP_QUALITY,
                        intent.getIntExtra(GifConversionWorker.KEY_WEBP_QUALITY, 75)
                    )
                    .build()

                delegate.enqueueRetrySingle(context, data)
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_WORK = "com.example.action.CANCEL_CONVERSION_WORK"
        const val ACTION_CANCEL_BATCH = "com.example.action.CANCEL_BATCH_QUEUE"
        const val ACTION_RETRY_SINGLE = "com.example.action.RETRY_SINGLE_CONVERSION"

        const val EXTRA_WORK_ID = "extra_work_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        internal var testDelegate: ActionDelegate? = null

        private val defaultDelegate = object : ActionDelegate {
            override fun cancelWorkById(context: Context, workId: UUID) {
                runCatching {
                    WorkManager.getInstance(context.applicationContext).cancelWorkById(workId)
                }
            }

            override fun cancelBatchQueue(context: Context) {
                runCatching {
                    WorkManager.getInstance(context.applicationContext)
                        .cancelAllWorkByTag(GifConversionWorker.TAG_BATCH_WORK)
                }
            }

            override fun enqueueRetrySingle(context: Context, inputData: Data) {
                runCatching {
                    val request = OneTimeWorkRequestBuilder<GifConversionWorker>()
                        .setInputData(inputData)
                        .addTag(GifConversionWorker.TAG_SINGLE_EXPORT)
                        .build()
                    WorkManager.getInstance(context.applicationContext).enqueue(request)
                }
            }
        }
    }
}
