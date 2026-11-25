package com.example.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import com.example.MainActivity
import com.nepimeshi.nepigiflab.R
import com.example.StudioRoutes
import com.example.engine.GalleryExporter
import com.example.engine.OutputFormat
import com.example.engine.StudioFormatters
import java.util.UUID

/**
 * Centralized Android notification manager for Nepi GIF Lab's background single and batch
 * conversions. Handles Android 8+ notification channels, Android 13+ POST_NOTIFICATIONS checks,
 * foreground service notifications with real progress, completion notifications with Open/Share,
 * failure notifications with Retry, and batch queue notifications with Cancel Queue.
 */
object ConversionNotificationHelper {

    const val CHANNEL_PROGRESS = "nepi_gif_conversion_progress"
    const val CHANNEL_STATUS = "nepi_gif_conversion_status"

    const val NOTIFICATION_ID_SINGLE_ACTIVE = 4109
    const val NOTIFICATION_ID_BATCH_ACTIVE = 4110
    const val NOTIFICATION_ID_RESULT_BASE = 4200
    const val NOTIFICATION_ID_BATCH_RESULT = 4111

    const val EXTRA_NAV_DESTINATION = "com.example.EXTRA_NAV_DESTINATION"

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return try {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Throwable) {
            true
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS,
            "GIF Conversion Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays active offline GIF conversion progress."
            setShowBadge(false)
        }

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "GIF Conversion Results",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when GIF conversions finish or encounter an error."
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(statusChannel)
    }

    fun buildOpenScreenPendingIntent(
        context: Context,
        destination: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(EXTRA_NAV_DESTINATION, destination)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildCancelWorkPendingIntent(
        context: Context,
        workId: UUID
    ): PendingIntent {
        val intent = Intent(context, ConversionActionReceiver::class.java).apply {
            action = ConversionActionReceiver.ACTION_CANCEL_WORK
            putExtra(ConversionActionReceiver.EXTRA_WORK_ID, workId.toString())
        }
        return PendingIntent.getBroadcast(
            context,
            workId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildCancelBatchPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConversionActionReceiver::class.java).apply {
            action = ConversionActionReceiver.ACTION_CANCEL_BATCH
        }
        return PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID_BATCH_ACTIVE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildRetryWorkPendingIntent(
        context: Context,
        inputData: Data,
        notificationId: Int
    ): PendingIntent? {
        val videoUri = inputData.getString(GifConversionWorker.KEY_VIDEO_URI) ?: return null
        val intent = Intent(context, ConversionActionReceiver::class.java).apply {
            action = ConversionActionReceiver.ACTION_RETRY_SINGLE
            putExtra(ConversionActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(GifConversionWorker.KEY_VIDEO_URI, videoUri)
            putExtra(GifConversionWorker.KEY_SOURCE_NAME, inputData.getString(GifConversionWorker.KEY_SOURCE_NAME))
            putExtra(GifConversionWorker.KEY_RESOLUTION_PRESET, inputData.getString(GifConversionWorker.KEY_RESOLUTION_PRESET))
            putExtra(GifConversionWorker.KEY_CUSTOM_RES, inputData.getInt(GifConversionWorker.KEY_CUSTOM_RES, 360))
            putExtra(GifConversionWorker.KEY_COLOR_PRESET, inputData.getString(GifConversionWorker.KEY_COLOR_PRESET))
            putExtra(GifConversionWorker.KEY_CUSTOM_COLORS, inputData.getInt(GifConversionWorker.KEY_CUSTOM_COLORS, 128))
            putExtra(GifConversionWorker.KEY_DITHER, inputData.getString(GifConversionWorker.KEY_DITHER))
            putExtra(GifConversionWorker.KEY_USE_ORIGINAL_FPS, inputData.getBoolean(GifConversionWorker.KEY_USE_ORIGINAL_FPS, true))
            putExtra(GifConversionWorker.KEY_FPS, inputData.getFloat(GifConversionWorker.KEY_FPS, 15f))
            putExtra(GifConversionWorker.KEY_SPEED, inputData.getFloat(GifConversionWorker.KEY_SPEED, 1f))
            putExtra(GifConversionWorker.KEY_REVERSE, inputData.getBoolean(GifConversionWorker.KEY_REVERSE, false))
            putExtra(GifConversionWorker.KEY_PING_PONG, inputData.getBoolean(GifConversionWorker.KEY_PING_PONG, false))
            putExtra(GifConversionWorker.KEY_ROTATION, inputData.getInt(GifConversionWorker.KEY_ROTATION, 0))
            putExtra(GifConversionWorker.KEY_CROP_L, inputData.getFloat(GifConversionWorker.KEY_CROP_L, 0f))
            putExtra(GifConversionWorker.KEY_CROP_T, inputData.getFloat(GifConversionWorker.KEY_CROP_T, 0f))
            putExtra(GifConversionWorker.KEY_CROP_R, inputData.getFloat(GifConversionWorker.KEY_CROP_R, 1f))
            putExtra(GifConversionWorker.KEY_CROP_B, inputData.getFloat(GifConversionWorker.KEY_CROP_B, 1f))
            putExtra(GifConversionWorker.KEY_TRIM_START, inputData.getLong(GifConversionWorker.KEY_TRIM_START, 0L))
            putExtra(GifConversionWorker.KEY_TRIM_END, inputData.getLong(GifConversionWorker.KEY_TRIM_END, 0L))
            putExtra(GifConversionWorker.KEY_LOOP_COUNT, inputData.getInt(GifConversionWorker.KEY_LOOP_COUNT, 0))
            putExtra(GifConversionWorker.KEY_ASPECT_RATIO, inputData.getString(GifConversionWorker.KEY_ASPECT_RATIO))
            putExtra(GifConversionWorker.KEY_OUTPUT_FORMAT, inputData.getString(GifConversionWorker.KEY_OUTPUT_FORMAT))
            putExtra(GifConversionWorker.KEY_WEBP_QUALITY_PRESET, inputData.getString(GifConversionWorker.KEY_WEBP_QUALITY_PRESET))
            putExtra(GifConversionWorker.KEY_WEBP_QUALITY, inputData.getInt(GifConversionWorker.KEY_WEBP_QUALITY, 75))
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId + 500,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildActiveSingleNotification(
        context: Context,
        workId: UUID,
        sourceName: String?,
        progressPercent: Int,
        stage: String,
        indeterminate: Boolean,
        outputFormat: OutputFormat = OutputFormat.GIF
    ): Notification {
        ensureChannels(context)
        val clampedPct = progressPercent.coerceIn(0, 99)
        val title = when {
            !sourceName.isNullOrBlank() && outputFormat == OutputFormat.WEBP ->
                "Converting $sourceName to WebP"
            !sourceName.isNullOrBlank() ->
                "Converting $sourceName"
            outputFormat == OutputFormat.WEBP ->
                "Nepi GIF Lab · Converting to WebP"
            else ->
                "Nepi GIF Lab · Converting"
        }
        val contentText = if (indeterminate || clampedPct <= 0) {
            stage
        } else {
            "$clampedPct% · $stage"
        }

        val openExportIntent = buildOpenScreenPendingIntent(
            context = context,
            destination = StudioRoutes.EXPORT,
            requestCode = NOTIFICATION_ID_SINGLE_ACTIVE
        )
        val cancelIntent = buildCancelWorkPendingIntent(context, workId)

        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("Nepi GIF Lab")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openExportIntent)
            .setProgress(100, if (indeterminate) 0 else clampedPct, indeterminate)
            .addAction(0, "Cancel Conversion", cancelIntent)
            .build()
    }

    fun createSingleForegroundInfo(
        context: Context,
        workId: UUID,
        sourceName: String?,
        progressPercent: Int,
        stage: String,
        indeterminate: Boolean,
        outputFormat: OutputFormat = OutputFormat.GIF
    ): ForegroundInfo {
        val notification = buildActiveSingleNotification(
            context = context,
            workId = workId,
            sourceName = sourceName,
            progressPercent = progressPercent,
            stage = stage,
            indeterminate = indeterminate,
            outputFormat = outputFormat
        )
        return toForegroundInfo(NOTIFICATION_ID_SINGLE_ACTIVE, notification)
    }

    fun buildActiveBatchNotification(
        context: Context,
        currentFileName: String?,
        overallPercent: Int,
        completedCount: Int,
        remainingCount: Int,
        stage: String,
        indeterminate: Boolean
    ): Notification {
        ensureChannels(context)
        val clampedPct = overallPercent.coerceIn(0, 99)
        val title = if (!currentFileName.isNullOrBlank()) {
            "Batch: $currentFileName"
        } else {
            "Nepi GIF Lab · Batch Queue"
        }
        val countsText = "Done $completedCount · Remaining $remainingCount"
        val body = if (indeterminate || clampedPct <= 0) {
            "$countsText · $stage"
        } else {
            "$clampedPct% · $countsText · $stage"
        }

        val openBatchIntent = buildOpenScreenPendingIntent(
            context = context,
            destination = StudioRoutes.BATCH,
            requestCode = NOTIFICATION_ID_BATCH_ACTIVE
        )
        val cancelQueueIntent = buildCancelBatchPendingIntent(context)

        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("Batch Queue")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openBatchIntent)
            .setProgress(100, if (indeterminate) 0 else clampedPct, indeterminate)
            .addAction(0, "Cancel Queue", cancelQueueIntent)
            .build()
    }

    fun createBatchForegroundInfo(
        context: Context,
        currentFileName: String?,
        overallPercent: Int,
        completedCount: Int,
        remainingCount: Int,
        stage: String,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notification = buildActiveBatchNotification(
            context = context,
            currentFileName = currentFileName,
            overallPercent = overallPercent,
            completedCount = completedCount,
            remainingCount = remainingCount,
            stage = stage,
            indeterminate = indeterminate
        )
        return toForegroundInfo(NOTIFICATION_ID_BATCH_ACTIVE, notification)
    }

    fun postCompletionNotification(
        context: Context,
        outputFileName: String,
        fileSizeBytes: Long,
        contentUri: Uri,
        notificationId: Int = NOTIFICATION_ID_RESULT_BASE + (outputFileName.hashCode() and 0x3FF),
        outputFormat: OutputFormat = if (outputFileName.endsWith(".webp", ignoreCase = true)) {
            OutputFormat.WEBP
        } else {
            OutputFormat.GIF
        }
    ): Boolean {
        if (!canPostNotifications(context)) return false
        ensureChannels(context)

        val sizeLabel = StudioFormatters.formatFileSize(fileSizeBytes)
        val mimeType = outputFormat.mimeType
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            GalleryExporter.buildOpenViewerIntent(contentUri, mimeType),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val shareIntent = PendingIntent.getActivity(
            context,
            notificationId + 1000,
            GalleryExporter.buildShareIntent(contentUri, "Share $outputFileName", mimeType),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val savedTitle = if (outputFormat == OutputFormat.WEBP) {
            "Animated WebP Saved · $sizeLabel"
        } else {
            "GIF Saved · $sizeLabel"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(savedTitle)
            .setContentText(outputFileName)
            .setSubText(GalleryExporter.GALLERY_FOLDER_DISPLAY)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Open", openIntent)
            .addAction(0, "Share", shareIntent)
            .build()

        return safeNotify(context, notificationId, notification)
    }

    fun postFailureNotification(
        context: Context,
        sourceName: String?,
        errorMessage: String,
        retryInputData: Data? = null,
        notificationId: Int = NOTIFICATION_ID_RESULT_BASE + 900,
        outputFormat: OutputFormat = OutputFormat.GIF
    ): Boolean {
        if (!canPostNotifications(context)) return false
        ensureChannels(context)

        val title = when {
            !sourceName.isNullOrBlank() -> "Conversion Failed · $sourceName"
            outputFormat == OutputFormat.WEBP -> "Animated WebP Conversion Failed"
            else -> "GIF Conversion Failed"
        }
        val openExportIntent = buildOpenScreenPendingIntent(
            context = context,
            destination = StudioRoutes.EXPORT,
            requestCode = notificationId
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openExportIntent)

        retryInputData?.let { data ->
            buildRetryWorkPendingIntent(context, data, notificationId)?.let { retryPi ->
                builder.addAction(0, "Retry", retryPi)
            }
        }

        return safeNotify(context, notificationId, builder.build())
    }

    fun postBatchFinishedNotification(
        context: Context,
        completedCount: Int,
        failedCount: Int,
        cancelledCount: Int
    ): Boolean {
        if (!canPostNotifications(context)) return false
        ensureChannels(context)

        val title = when {
            failedCount == 0 && cancelledCount == 0 -> "Batch Conversion Complete"
            completedCount == 0 && failedCount > 0 -> "Batch Conversion Failed"
            else -> "Batch Queue Finished"
        }
        val parts = buildList {
            add("$completedCount completed")
            if (failedCount > 0) add("$failedCount failed")
            if (cancelledCount > 0) add("$cancelledCount cancelled")
        }
        val summary = parts.joinToString(" · ")

        val openBatchIntent = buildOpenScreenPendingIntent(
            context = context,
            destination = StudioRoutes.BATCH,
            requestCode = NOTIFICATION_ID_BATCH_RESULT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(openBatchIntent)
            .build()

        return safeNotify(context, NOTIFICATION_ID_BATCH_RESULT, notification)
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Throwable) {
        }
    }

    private fun toForegroundInfo(notificationId: Int, notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun safeNotify(context: Context, id: Int, notification: Notification): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            manager.notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }
}
