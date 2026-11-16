package com.example.wasm2kt.glitchcamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaStoreHelper {
    private const val ALBUM_FOLDER = "GlitchCamera"

    internal inline fun <U> writePendingMediaEntry(
        insert: () -> U?,
        writeAndPublish: (U) -> Unit,
        deleteEntry: (U) -> Unit,
        cleanupTempFile: () -> Unit = {},
    ): Result<U> = try {
        runCatching {
            val uri = insert() ?: error("Failed to create MediaStore entry")
            try {
                writeAndPublish(uri)
                uri
            } catch (t: Throwable) {
                runCatching { deleteEntry(uri) }
                throw t
            }
        }
    } finally {
        runCatching { cleanupTempFile() }
    }

    fun savePngSnapshot(context: Context, pngBytes: ByteArray): Result<Uri> {
        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val displayName = "GLITCH_$timestamp.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_FOLDER",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return writePendingMediaEntry(
            insert = { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) },
            writeAndPublish = { uri ->
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(pngBytes)
                    stream.flush()
                } ?: error("Failed to open output stream for $uri")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completed = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, completed, null, null)
                }
            },
            deleteEntry = { uri -> resolver.delete(uri, null, null) },
        )
    }

    fun saveMp4Video(context: Context, mp4File: File): Result<Uri> {
        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "GLITCH_$timestamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM_FOLDER",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        return writePendingMediaEntry(
            insert = { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) },
            writeAndPublish = { uri ->
                resolver.openOutputStream(uri)?.use { output ->
                    mp4File.inputStream().use { input ->
                        input.copyTo(output)
                    }
                    output.flush()
                } ?: error("Failed to open output stream for $uri")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completed = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, completed, null, null)
                }
            },
            deleteEntry = { uri -> resolver.delete(uri, null, null) },
            cleanupTempFile = { mp4File.delete() },
        )
    }
}
