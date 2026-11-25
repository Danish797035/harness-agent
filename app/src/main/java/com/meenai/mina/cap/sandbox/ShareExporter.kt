package com.meenai.mina.cap.sandbox

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.core.workspace.GatedFile
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.cap.sandbox.ShareRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

class FileTooLarge(val bytes: Long) : Exception("file is larger than ${ShareRules.MAX_BYTES shr 20} MB")

/** Save the files in the workspace to "Download/Mina" (MediaStore, no storage permission required) on your phone. */
class ShareExporter(private val context: Context) {
    /**
     * [file] has passed the checkpoint verification (do not follow the link to open it once, press the
     * handle to confirm that it is in its own workspace, it is an ordinary file, and there is only one
     * hard link); here again, the size does not exceed 100 MB, and then copied from the same handle.
     * Even if it is replaced with a link or other file after checking, the outside content cannot be
     * read. Returns the exported file, in which savedAs is "Download/Mina/<actual name>" (the system adds the
     * serial number when the name is the same); Mina's private path will not be handed over.
     */
    suspend fun export(file: GatedFile, requestedName: String): SharedFile = withContext(Dispatchers.IO) {
        if (!ShareRules.plainName(requestedName)) throw SandboxError("invalid_arguments", "name: must be a plain file name", recoverable = true)
        val name = ShareRules.fitName(requestedName)
        if (ShareRules.refusedType(name) || ShareRules.refusedType(file.name)) {
            throw SandboxError("share_refused_type", "App installation packages cannot be shared.", recoverable = false)
        }
        if (file.size > ShareRules.MAX_BYTES) throw FileTooLarge(file.size)
        copyOut(file.input(), name)
    }

    private suspend fun copyOut(input: InputStream, name: String): SharedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Harness/")
            mimeOf(name)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        var size = 0L
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    // When the session is closed (task cancelled, command ended), no more writing will
                    // be done.
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    // After checking, the file became larger again: the calculation is also based on the
                    // upper limit.
                    if (size > ShareRules.MAX_BYTES) throw FileTooLarge(size)
                    out.write(buf, 0, n)
                }
            }
            currentCoroutineContext().ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val actual = displayName(uri) ?: name
        return SharedFile(actual, size, "Download/Harness/$actual", uri.toString())
    }

    /**
     * Delete the unfinished entries left by this App in "Download/Mina" (the process will be killed when
     * the process is killed in the middle of writing, leaving an empty shell of IS_PENDING). Called on
     * startup.
     */
    fun cleanPending() {
        val resolver = context.contentResolver
        for (folder in listOf("Harness", "Mina")) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${android.os.Environment.DIRECTORY_DOWNLOADS}/$folder/%", context.packageName))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            val ids = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }.orEmpty()
            for (id in ids) runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun mimeOf(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}
