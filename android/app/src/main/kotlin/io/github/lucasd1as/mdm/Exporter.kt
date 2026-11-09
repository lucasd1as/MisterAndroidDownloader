package io.github.lucasd1as.mdm

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * Finished files are written to the app's own folder (no permission needed, and the engine needs
 * real paths for its part files), then copied into the device's public Downloads through
 * MediaStore, which needs no permission either from Android 10 on.
 */
object Exporter {
    fun mimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Copies [item]'s file into Downloads and returns its content URI. Blocking. */
    fun export(context: Context, item: DownloadItem): Uri {
        val source = File(item.path ?: throw IOException("the file is not in the app's folder"))
        if (!source.isFile) throw IOException("${source.name} is missing")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(item.name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads would not take the file")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("cannot write to Downloads")).use { out ->
                source.inputStream().use { it.copyTo(out, 256 * 1024) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** Whether the exported file is still there (the user can delete it in a file manager). */
    fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** The intent that shows a finished file in whatever app opens its type. */
    fun viewIntent(uri: Uri, name: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [item]'s file; false when it isn't there or nothing opens it. */
    fun open(context: Context, item: DownloadItem): Boolean {
        val uri = item.exported?.let(Uri::parse) ?: return false
        if (!exists(context, uri)) return false
        return try {
            context.startActivity(viewIntent(uri, item.name))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
