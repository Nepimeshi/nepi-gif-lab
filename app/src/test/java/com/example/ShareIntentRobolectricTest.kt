package com.example

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.example.engine.IncomingShareResult
import com.example.engine.MediaInputType
import com.example.engine.ShareIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * Verifies Stage 2: Android Share Sheet integration via ACTION_SEND and ACTION_SEND_MULTIPLE
 * for single videos, single animated GIFs, multiple mixed files, transient content:// URI
 * spooling/permission handling, and unsupported file errors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareIntentRobolectricTest {

    private fun registerTestProvider(
        context: Context,
        authority: String,
        filesByPath: Map<String, Pair<File, String>>
    ) {
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun getType(uri: Uri): String? = filesByPath[uri.lastPathSegment]?.second
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val entry = filesByPath[uri.lastPathSegment]
                    ?: throw IllegalArgumentException("Unknown uri: $uri")
                return ParcelFileDescriptor.open(entry.first, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor {
                val name = uri.lastPathSegment ?: "shared"
                val file = filesByPath[name]?.first
                val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
                cursor.addRow(arrayOf<Any?>(name, file?.length() ?: 0L))
                return cursor
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        val info = ProviderInfo().apply { this.authority = authority }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test
    fun `single video ACTION_SEND resolves to Single VIDEO and spools transient content URI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.test.share.video"
        val videoFile = File(context.cacheDir, "sample_share.mp4").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        }
        registerTestProvider(context, authority, mapOf("sample_share.mp4" to (videoFile to "video/mp4")))

        val contentUri = Uri.parse("content://$authority/sample_share.mp4")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val result = ShareIntentParser.parse(context, intent)
        assertTrue("Expected Single video share result, got $result", result is IncomingShareResult.Single)
        val single = result as IncomingShareResult.Single
        assertEquals(MediaInputType.VIDEO, single.mediaType)
        // Transient provider without persistable permission should spool to local readable file URI
        assertEquals("file", single.uri.scheme)
        val spooled = File(single.uri.path!!)
        assertTrue(spooled.exists() && spooled.length() == videoFile.length())
    }

    @Test
    fun `single animated GIF ACTION_SEND resolves to Single GIF`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.test.share.gif"
        val gifFile = File(context.cacheDir, "reaction.gif").apply {
            writeBytes("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.US_ASCII))
        }
        registerTestProvider(context, authority, mapOf("reaction.gif" to (gifFile to "image/gif")))

        val contentUri = Uri.parse("content://$authority/reaction.gif")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/gif"
            putExtra(Intent.EXTRA_STREAM, contentUri)
        }

        val result = ShareIntentParser.parse(context, intent)
        assertTrue(result is IncomingShareResult.Single)
        val single = result as IncomingShareResult.Single
        assertEquals(MediaInputType.GIF, single.mediaType)
        assertTrue(File(single.uri.path!!).exists())
    }

    @Test
    fun `multiple mixed video and GIF ACTION_SEND_MULTIPLE loads valid items and skips unsupported`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.test.share.multi"
        val mp4 = File(context.cacheDir, "m1.mp4").apply { writeBytes(ByteArray(16) { 1 }) }
        val gif = File(context.cacheDir, "m2.gif").apply {
            writeBytes("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.US_ASCII))
        }
        val pdf = File(context.cacheDir, "doc.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }

        registerTestProvider(
            context,
            authority,
            mapOf(
                "m1.mp4" to (mp4 to "video/mp4"),
                "m2.gif" to (gif to "image/gif"),
                "doc.pdf" to (pdf to "application/pdf")
            )
        )

        val uris = arrayListOf(
            Uri.parse("content://$authority/m1.mp4"),
            Uri.parse("content://$authority/m2.gif"),
            Uri.parse("content://$authority/doc.pdf")
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val result = ShareIntentParser.parse(context, intent)
        assertTrue(result is IncomingShareResult.Multiple)
        val multi = result as IncomingShareResult.Multiple
        assertEquals(2, multi.uris.size)
        assertEquals(1, multi.skippedCount)
    }

    @Test
    fun `unsupported file share returns meaningful error and ClipData fallback works`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.test.share.other"
        val png = File(context.cacheDir, "photo.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 80, 78, 71)) }
        val clip = File(context.cacheDir, "clip_only.mp4").apply { writeBytes(ByteArray(12) { 2 }) }

        registerTestProvider(
            context,
            authority,
            mapOf(
                "photo.png" to (png to "image/png"),
                "clip_only.mp4" to (clip to "video/mp4")
            )
        )

        val badIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://$authority/photo.png"))
        }
        val badResult = ShareIntentParser.parse(context, badIntent)
        assertTrue(badResult is IncomingShareResult.Unsupported)
        assertTrue((badResult as IncomingShareResult.Unsupported).message.contains("Unsupported"))

        // ClipData fallback when EXTRA_STREAM is absent
        val clipIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            clipData = ClipData.newRawUri("video", Uri.parse("content://$authority/clip_only.mp4"))
        }
        val clipResult = ShareIntentParser.parse(context, clipIntent)
        assertTrue(clipResult is IncomingShareResult.Single)
        assertEquals(MediaInputType.VIDEO, (clipResult as IncomingShareResult.Single).mediaType)
    }
}
