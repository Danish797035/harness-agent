package com.meenai.mina.diag

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.meenai.mina.core.diag.ChatTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Appends [ChatTrace] lines to `Download/harness-chat.log`.
 *
 * A chat failure cannot be diagnosed from a screenshot: the HTTP status and body
 * never reach the UI. This writes them where they can be read (and shared) as text.
 * The file is capped so it never grows without limit, and every write is best-effort.
 */
object ChatLog {

    /** Keep the log small: roughly the last few hundred model calls. */
    private const val MAX_CHARS = 200_000

    /**
     * MediaStore appends `.txt` to a text/plain DISPLAY_NAME, so the lookup has to
     * match what it actually stored. One stable uri per process keeps every record
     * in the same file instead of creating `harness-chat.log (1).txt`, `(2)`, …
     */
    private const val DISPLAY_NAME = "harness-chat-log.txt"

    @Volatile
    private var fileUri: Uri? = null

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-log").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Install as [ChatTrace.sink] once, from the application object. */
    fun install(context: Context) {
        val app = context.applicationContext
        ChatTrace.sink = { line -> io.execute { runCatching { append(app, line) } } }
        ChatTrace.record(
            "app start ${com.meenai.mina.BuildConfig.VERSION_NAME} (${com.meenai.mina.BuildConfig.GIT_HASH})"
        )
    }

    private fun collection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun find(app: Context): Uri? = runCatching {
        app.contentResolver.query(
            collection(),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(DISPLAY_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection(), cursor.getLong(0)) else null
        }
    }.getOrNull()

    private fun append(app: Context, line: String) {
        val entry = stamp.format(Date()) + " " + line.replace('\n', ' ').take(4000)
        val uri = fileUri ?: find(app)?.also { fileUri = it } ?: app.contentResolver.insert(collection(), ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, DISPLAY_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        })?.also { fileUri = it } ?: return
        val existing = runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        val text = existing + entry + "\n"
        val capped = if (text.length > MAX_CHARS) text.takeLast(MAX_CHARS / 2) else text
        val written = runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(capped.toByteArray()) }
            true
        }.getOrDefault(false)
        if (!written) fileUri = null
    }
}
