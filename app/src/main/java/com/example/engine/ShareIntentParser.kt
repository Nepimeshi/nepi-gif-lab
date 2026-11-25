package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

sealed interface IncomingShareResult {
    data object None : IncomingShareResult
    data class Single(val uri: Uri, val mediaType: MediaInputType) : IncomingShareResult
    data class Multiple(val uris: List<Uri>, val skippedCount: Int = 0) : IncomingShareResult
    data class Unsupported(val message: String) : IncomingShareResult
}

/**
 * Parses incoming Android share intents ([Intent.ACTION_SEND] and [Intent.ACTION_SEND_MULTIPLE])
 * for videos and animated GIFs, validates MIME types and magic headers, and ensures safe read
 * access across background WorkManager conversions.
 */
object ShareIntentParser {

    private val SUPPORTED_VIDEO_EXTENSIONS = setOf(
        "mp4", "mov", "mkv", "webm", "3gp", "m4v", "avi", "ts", "mpeg", "mpg"
    )

    fun extractRawUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val action = intent.action ?: return emptyList()
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return emptyList()
        }

        val collected = LinkedHashSet<Uri>()

        if (action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (streamUri != null) collected.add(streamUri)
        } else {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (list != null) collected.addAll(list)
        }

        // ClipData fallback (many Android gallery / file apps populate ClipData alongside or instead of EXTRA_STREAM)
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { collected.add(it) }
            }
        }

        return collected.toList()
    }

    fun parse(context: Context, intent: Intent?): IncomingShareResult {
        if (intent == null) return IncomingShareResult.None
        val action = intent.action ?: return IncomingShareResult.None
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return IncomingShareResult.None
        }

        val rawUris = extractRawUris(intent)
        if (rawUris.isEmpty()) {
            return IncomingShareResult.Unsupported(
                "No supported video or animated GIF file was included in the share request."
            )
        }

        val intentMime = intent.type?.lowercase(Locale.US)
        val allowPersistable = (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        val validItems = mutableListOf<Pair<Uri, MediaInputType>>()
        var skipped = 0

        for (uri in rawUris) {
            val detected = detectSupportedMediaType(context, uri, intentMime)
            if (detected != null) {
                val durableUri = obtainDurableReadUri(
                    context = context,
                    uri = uri,
                    mediaType = detected,
                    allowPersistable = allowPersistable
                )
                validItems.add(durableUri to detected)
            } else {
                skipped++
            }
        }

        if (validItems.isEmpty()) {
            return IncomingShareResult.Unsupported(
                "Unsupported file format. Nepi GIF Lab supports video files and animated GIFs (.gif)."
            )
        }

        return if (validItems.size == 1 && action == Intent.ACTION_SEND) {
            val (uri, type) = validItems.first()
            IncomingShareResult.Single(uri = uri, mediaType = type)
        } else {
            IncomingShareResult.Multiple(
                uris = validItems.map { it.first },
                skippedCount = skipped
            )
        }
    }

    fun detectSupportedMediaType(
        context: Context,
        uri: Uri,
        fallbackIntentMime: String? = null
    ): MediaInputType? {
        val resolverMime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?.lowercase(Locale.US)

        if (resolverMime == "image/gif") return MediaInputType.GIF
        if (resolverMime != null && resolverMime.startsWith("video/")) return MediaInputType.VIDEO

        // Reject clearly unsupported explicit MIME types (e.g., PNG, JPEG, PDF, text)
        if (resolverMime != null &&
            resolverMime != "*/*" &&
            resolverMime != "application/octet-stream"
        ) {
            return null
        }

        // Check actual GIF magic bytes when stream is readable
        if (AnimatedGifFrameSource.isGifUri(context, uri)) {
            return MediaInputType.GIF
        }

        val ext = queryExtension(context, uri)
        if (ext == "gif") return MediaInputType.GIF
        if (ext != null && ext in SUPPORTED_VIDEO_EXTENSIONS) return MediaInputType.VIDEO

        if (fallbackIntentMime == "image/gif") return MediaInputType.GIF
        if (fallbackIntentMime != null && fallbackIntentMime.startsWith("video/")) return MediaInputType.VIDEO

        return null
    }

    /**
     * Obtains persistable URI read permission when supported by the provider; otherwise spools
     * transient `content://` shares into internal cache so background WorkManager conversions
     * and process restarts never lose read permission.
     */
    fun obtainDurableReadUri(
        context: Context,
        uri: Uri,
        mediaType: MediaInputType,
        allowPersistable: Boolean = false
    ): Uri {
        if (uri.scheme == "file") return uri
        if (uri.scheme == "content") {
            if (allowPersistable) {
                val persisted = try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    true
                } catch (_: Throwable) {
                    false
                }
                if (persisted) return uri
            }

            // Transient share URI (e.g., Google Photos / Gallery share sheet): spool locally if stream opens
            return runCatching {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@runCatching uri
                val dir = File(context.cacheDir, "shared_imports").apply { mkdirs() }
                val rawName = queryDisplayName(context, uri)
                    ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    ?.take(48)
                    ?.takeIf { it.isNotBlank() }
                val defaultExt = if (mediaType == MediaInputType.GIF) "gif" else "mp4"
                val fileName = rawName?.let {
                    if (it.contains('.')) it else "$it.$defaultExt"
                } ?: "shared_${System.currentTimeMillis()}.$defaultExt"
                val outFile = File(dir, "${System.currentTimeMillis()}_$fileName")
                inputStream.use { input ->
                    FileOutputStream(outFile).use { out ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                if (outFile.length() > 0L) Uri.fromFile(outFile) else uri
            }.getOrDefault(uri)
        }
        return uri
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        return cursor.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun queryExtension(context: Context, uri: Uri): String? {
        val name = queryDisplayName(context, uri) ?: return null
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase(Locale.US)
    }
}
