package com.meenai.mina.image

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageStorageManager {

    const val CACHE_DIR_NAME = "generated_images"
    const val MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000L // 7 days

    /**
     * Saves image bytes into app's private cache directory.
     * Never automatically saved to gallery.
     */
    fun saveToPrivateCache(cacheDir: File, bytes: ByteArray): File {
        val dir = File(cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    fun saveToPrivateCache(context: Context, bytes: ByteArray): File =
        saveToPrivateCache(context.cacheDir, bytes)

    /**
     * Purges images older than 7 days from the private cache.
     */
    fun cleanOldCache(cacheDir: File, nowMs: Long = System.currentTimeMillis()): Int {
        val dir = File(cacheDir, CACHE_DIR_NAME)
        if (!dir.exists() || !dir.isDirectory) return 0
        var deletedCount = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && nowMs - file.lastModified() > MAX_CACHE_AGE_MS) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    fun cleanOldCache(context: Context, nowMs: Long = System.currentTimeMillis()): Int =
        cleanOldCache(context.cacheDir, nowMs)

    /**
     * Saves image to MediaStore (gallery). Only called on explicit user tap!
     */
    fun saveToGallery(context: Context, imageFile: File): Uri? {
        val resolver = context.contentResolver
        val filename = "IMG_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HarnessAgent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                imageFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    /**
     * Creates a share Intent for the image file.
     */
    fun createShareIntent(context: Context, imageFile: File): Intent {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.attachfiles",
                imageFile
            )
        } catch (_: Exception) {
            Uri.fromFile(imageFile)
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
