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

    fun savePngSnapshot(context: Context, pngBytes: ByteArray): Result<Uri> = runCatching {
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
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore image entry")
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
        uri
    }

    fun saveMp4Video(context: Context, mp4File: File): Result<Uri> = runCatching {
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
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore video entry")
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
        mp4File.delete()
        uri
    }
}
