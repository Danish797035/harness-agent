package com.meenai.mina.ui.image

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded in-memory [ImageBitmap] LRU cache for chat, markdown, and generated images.
 *
 * Decoding and file-system checks happen off the main thread in [loadFromFile], while [peekByPath]
 * performs a lock-free lookup for zero-flash initial Compose frames.
 */
internal object ImageBitmapCache {
    private const val MAX_BYTES = 24 * 1024 * 1024 // 24 MiB
    const val DEFAULT_MAX_DIMENSION = 1280

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 4).coerceAtLeast(1)
    }

    // Maps "$path#$maxDimension" -> newest full versioned cache key for O(1) UI seeding.
    private val latestKeys = ConcurrentHashMap<String, String>()

    fun keyFor(path: String, maxDimension: Int, lastModified: Long, length: Long): String =
        "$path#$maxDimension#$lastModified#$length"

    fun peekByPath(path: String, maxDimension: Int = DEFAULT_MAX_DIMENSION): ImageBitmap? {
        val lookupKey = "$path#$maxDimension"
        val versioned = latestKeys[lookupKey] ?: return null
        val hit = cache.get(versioned)
        if (hit == null) latestKeys.remove(lookupKey, versioned)
        return hit
    }

    suspend fun loadFromFile(path: String, maxDimension: Int = DEFAULT_MAX_DIMENSION): ImageBitmap? =
        withContext(Dispatchers.IO) {
            loadFromFileSync(path, maxDimension)
        }

    fun loadFromFileSync(path: String, maxDimension: Int = DEFAULT_MAX_DIMENSION): ImageBitmap? {
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.canRead()) {
            invalidatePath(path)
            return null
        }
        val absPath = file.absolutePath
        val versionedKey = keyFor(absPath, maxDimension, file.lastModified(), file.length())
        cache.get(versionedKey)?.let {
            latestKeys["$absPath#$maxDimension"] = versionedKey
            if (path != absPath) latestKeys["$path#$maxDimension"] = versionedKey
            return it
        }
        val decoded = decodeSampledImageBitmap(absPath, maxDimension) ?: return null
        cache.put(versionedKey, decoded)
        latestKeys["$absPath#$maxDimension"] = versionedKey
        if (path != absPath) latestKeys["$path#$maxDimension"] = versionedKey
        return decoded
    }

    fun invalidatePath(path: String) {
        val absPath = File(path).absolutePath
        for (k in cache.snapshot().keys) {
            if (k.startsWith("$absPath#") || k.startsWith("$path#")) {
                cache.remove(k)
            }
        }
        latestKeys.keys.removeIf { it.startsWith("$absPath#") || it.startsWith("$path#") }
    }

    fun clear() {
        cache.evictAll()
        latestKeys.clear()
    }
}

internal fun decodeSampledImageBitmap(path: String, maxDimension: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
    }
    val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
    return bitmap.asImageBitmap()
}
