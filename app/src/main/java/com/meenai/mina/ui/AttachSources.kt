package com.meenai.mina.ui

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.chat.AttachFilesProvider
import com.meenai.mina.core.workspace.SandboxPaths
import com.meenai.mina.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Things to read for attachments on the conversation page: select the name and size of the file, the camera placement point, and click to open the sent attachment. All disk reading is in the background thread. */
object AttachFiles {
    /** The name and size of the selected file (content URI string); if the name cannot be read, the last segment of the address is used, and the size is 0 if the name cannot be read. */
    suspend fun describe(context: Context, uris: List<String>): List<PendingAttachment> = withContext(Dispatchers.IO) {
        uris.map { s ->
            val uri = Uri.parse(s)
            var name: String? = null
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            PendingAttachment(s, name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "attachment", size)
        }
    }

    /** Where are the photos taken by the camera written: the new files under camera/ in the cache (the old photos from a day ago are conveniently deleted) and the address of the system camera. */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "attach/camera").apply { mkdirs() }
        val stale = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var f = File(dir, "IMG_$stamp.jpg")
        var n = 2
        while (f.exists()) f = File(dir, "IMG_${stamp}_${n++}.jpg")
        return FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), f)
    }

    /** The photo failed (cancelled): Delete the empty file. */
    fun discardCamera(context: Context, uri: Uri) {
        val name = uri.lastPathSegment ?: return
        File(context.cacheDir, "attach/camera/$name").takeIf { it.name == name }?.delete()
    }

    /** The path in the workspace (`/workspace/inbox/…`) is replaced with the path relative to the workspace; if it is not in the workspace, it is null. */
    fun relative(path: String): String? =
        path.removePrefix(SandboxPaths.WORKSPACE + "/").takeIf { it != path && it.isNotEmpty() }

    /**
     * Click on the sent attachment: copy a copy from the conversation workspace (via the file gate) to
     * viewing/in the cache (clear the last one first), and hand it over to the viewing page in the App;
     * for types that cannot be viewed, hand it over to the App that can open it in the system. Returns
     * false if it cannot be read.
     */
    suspend fun openSent(context: Context, conversationId: String, path: String): Boolean {
        val rel = relative(path) ?: return false
        val name = path.substringAfterLast('/')
        val copy = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.cacheDir, "attach/viewing")
                root.deleteRecursively()
                val f = File(root, UUID.randomUUID().toString().take(8) + "/" + name).apply { parentFile?.mkdirs() }
                val dirs = SandboxDirs(context)
                dirs.gate.openRead(dirs.workspace(conversationId), rel).use { g -> f.outputStream().use { g.input().copyTo(it) } }
                f
            }.getOrNull()
        } ?: return false
        FileViewer.open(context, FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), copy), name)
        return true
    }
}

/** Can the photo be read directly: all, only part of it (from Android 14), not given. */
enum class PhotoAccess { Full, Partial, None }

/** "Recent photos" in the attachment panel: permissions and photos in the media library. */
object RecentPhotos {
    private const val PREFS = "attach"
    private const val ASKED = "photos_asked"

    /** Permissions to apply for: Android 14 and above together with "some photos", 13 to read photos, 12 and earlier to read external storage. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): PhotoAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Full
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Full
            else -> PhotoAccess.None
        }
    }

    /** Ask once when the panel is first opened; then only when the user clicks "Allow access to photos". */
    fun asked(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()

    /** Photos recently added to the media library, new ones first; those that cannot be read (no permissions) are empty. */
    suspend fun query(context: Context, limit: Int = 120): List<PendingAttachment> = withContext(Dispatchers.IO) {
        val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE)
        runCatching {
            context.contentResolver.query(base, projection, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(base, c.getLong(0)).toString()
                        add(PendingAttachment(uri, c.getString(1) ?: "photo.jpg", if (c.isNull(2)) 0L else c.getLong(2), photo = true))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}

/** A picture in the attachment: either unsent (content address) or has been sent (path in the conversation workspace). */
sealed interface AttachImage {
    data class Picked(val uri: String) : AttachImage
    data class Sent(val conversationId: String, val path: String) : AttachImage
}

/** Read a reduced image according to the desired side length (pixels); if not read, it is null. The interface test is replaced with a fake one. */
fun interface AttachImages {
    suspend fun load(image: AttachImage, px: Int): ImageBitmap?

    /** Those that have been solved will be given directly (the placeholder will not flash when the list slides back and forth); if not, it will be null. */
    fun cached(image: AttachImage, px: Int): ImageBitmap? = null
}

/** If given, use it to read the attached pictures (test); if not given, use it to read the pictures on the mobile phone. */
val LocalAttachImages = staticCompositionLocalOf<AttachImages?> { null }

/** The attached picture on the mobile phone: zoom out and solve it (not the whole picture), in the background thread, the solved ones are recorded by the number of bytes. */
private class StoredAttachImages(private val context: Context) : AttachImages {
    override fun cached(image: AttachImage, px: Int): ImageBitmap? = cache.get("$image@$px")

    override suspend fun load(image: AttachImage, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$image@$px"
        cache.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            when (image) {
                is AttachImage.Picked -> context.contentResolver.loadThumbnail(Uri.parse(image.uri), Size(px, px), null).asImageBitmap()
                is AttachImage.Sent -> sentBytes(image)?.let { decodeSmall(it, px) }
            }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }

    private fun sentBytes(image: AttachImage.Sent): ByteArray? {
        val rel = AttachFiles.relative(image.path) ?: return null
        val dirs = SandboxDirs(context)
        return dirs.gate.openRead(dirs.workspace(image.conversationId), rel).use { f ->
            if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
        }
    }

    companion object {
        /** It’s hard to understand if a picture exceeds this size (only the placeholder is shown). */
        const val MAX_BYTES = 40L shl 20
        val cache = object : LruCache<String, ImageBitmap>(24 shl 20) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }
    }
}

/** Solve it into a reduced image with the short side no less than [px] (reduced by a multiple of 2), and adjust the direction of the photo according to EXIF. */
private fun decodeSmall(bytes: ByteArray, px: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val short = minOf(info.size.width, info.size.height)
        var sample = 1
        while (short / (sample * 2) >= px) sample *= 2
        d.setTargetSampleSize(sample)
    }.asImageBitmap()

/** Read an attached image (in the background); it is null when it has not been read or cannot be read. */
@Composable
fun rememberAttachImage(image: AttachImage, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val given = LocalAttachImages.current
    val images = remember(given, context) { given ?: StoredAttachImages(context.applicationContext) }
    val bmp by produceState(images.cached(image, px), image, px) {
        value = images.cached(image, px)
        if (value == null) value = runCatching { images.load(image, px) }.getOrNull()
    }
    return bmp
}
