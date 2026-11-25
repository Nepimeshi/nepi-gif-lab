package com.example.engine

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedGifResult(
    val contentUri: Uri,
    val fileName: String,
    val relativeFolder: String,
    val thumbnailPath: String?,
    val stats: EncodedGifStats
)

/**
 * Exports genuine animated GIFs and Animated WebPs to the Android Gallery under
 * `Pictures/Nepi GIF Lab` using MediaStore (zero storage permissions on Android 10+),
 * plus native Share and Open in Viewer intents with accurate MIME types.
 */
object GalleryExporter {

    const val GALLERY_FOLDER_DISPLAY = "Pictures/Nepi GIF Lab"
    private const val SUBFOLDER_NAME = "Nepi GIF Lab"

    fun generateFileName(
        sourceBaseName: String? = null,
        outputFormat: OutputFormat = OutputFormat.GIF
    ): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val sanitized = sourceBaseName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?.take(24)
            ?.trim('_')
            ?.takeIf { it.isNotBlank() }
        val ext = outputFormat.extension
        return if (sanitized != null) {
            "NepiGifLab_${sanitized}_$timestamp.$ext"
        } else {
            "NepiGifLab_$timestamp.$ext"
        }
    }

    fun mimeTypeFor(outputFormat: OutputFormat): String = outputFormat.mimeType

    fun mimeTypeForFileName(fileName: String?, fallbackFormat: OutputFormat = OutputFormat.GIF): String {
        val lower = fileName?.lowercase(Locale.US).orEmpty()
        return when {
            lower.endsWith(".webp") -> OutputFormat.WEBP.mimeType
            lower.endsWith(".gif") -> OutputFormat.GIF.mimeType
            else -> fallbackFormat.mimeType
        }
    }

    suspend fun saveGifToGallery(
        context: Context,
        tempGifFile: File,
        sourceName: String?,
        stats: EncodedGifStats,
        thumbnailBitmap: Bitmap? = null
    ): SavedGifResult = saveAnimationToGallery(
        context = context,
        tempFile = tempGifFile,
        sourceName = sourceName,
        stats = stats,
        thumbnailBitmap = thumbnailBitmap
    )

    suspend fun saveAnimationToGallery(
        context: Context,
        tempFile: File,
        sourceName: String?,
        stats: EncodedGifStats,
        thumbnailBitmap: Bitmap? = null
    ): SavedGifResult = withContext(Dispatchers.IO) {
        val format = stats.outputFormat
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw IOException("Encoded ${format.label} file is missing or empty.")
        }

        val fileName = generateFileName(sourceName, format)
        val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStoreApi29Plus(context, tempFile, fileName, format.mimeType)
        } else {
            saveViaLegacyPicturesDir(context, tempFile, fileName, format.mimeType)
        }

        val thumbPath = thumbnailBitmap?.let { saveHistoryThumbnail(context, it, fileName) }

        SavedGifResult(
            contentUri = savedUri,
            fileName = fileName,
            relativeFolder = GALLERY_FOLDER_DISPLAY,
            thumbnailPath = thumbPath,
            stats = stats
        )
    }

    private fun saveViaMediaStoreApi29Plus(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER_NAME"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create MediaStore entry in $GALLERY_FOLDER_DISPLAY.")

        try {
            val expectedBytes = sourceFile.length()
            val copiedBytes = resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    val written = input.copyTo(out, bufferSize = 64 * 1024)
                    out.flush()
                    if (out is FileOutputStream) {
                        runCatching { out.fd.sync() }
                    }
                    written
                }
            } ?: throw IOException("Failed to open MediaStore output stream for $fileName.")

            if (copiedBytes != expectedBytes) {
                throw IOException(
                    "Incomplete MediaStore write for $fileName: copied $copiedBytes of $expectedBytes bytes."
                )
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            return uri
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyPicturesDir(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): Uri {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        val targetDir = File(externalDir, SUBFOLDER_NAME).apply { mkdirs() }
        val targetFile = File(targetDir, fileName)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetFile).use { out ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(mimeType),
            null
        )
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            targetFile
        )
    }

    private fun saveHistoryThumbnail(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) {
                runCatching { noMedia.createNewFile() }
            }
            val hasAlpha = bitmap.hasAlpha() && hasTransparentPixels(bitmap)
            val ext = if (hasAlpha) "png" else "jpg"
            val baseName = fileName.substringBeforeLast('.')
            val thumbFile = File(dir, "$baseName.$ext")
            FileOutputStream(thumbFile).use { out ->
                if (hasAlpha) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            thumbFile.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (((bitmap.getPixel(x, y) ushr 24) and 0xFF) < 250) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    fun buildShareIntent(
        uri: Uri,
        title: String = "Share GIF",
        mimeType: String = OutputFormat.GIF.mimeType
    ): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildOpenViewerIntent(
        uri: Uri,
        mimeType: String = OutputFormat.GIF.mimeType
    ): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun deleteGalleryFile(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Throwable) {
            false
        }
    }
}
