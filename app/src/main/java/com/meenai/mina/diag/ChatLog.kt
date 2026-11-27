package com.meenai.mina.diag

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.meenai.mina.BuildConfig
import com.meenai.mina.core.diag.ChatTrace
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Appends [ChatTrace] lines to `Download/harness-chat-log.txt` and synchronously persists uncaught
 * exceptions with full stack traces to `Download/harness-crash-log.txt` and `filesDir/last-crash.txt`.
 */
object ChatLog {

    /** Keep the log small: roughly the last few hundred model calls. */
    private const val MAX_CHARS = 200_000
    private const val CRASH_MAX_CHARS = 120_000
    private const val MAX_CRASH_STACK_CHARS = 16_000

    /**
     * MediaStore appends `.txt` to a text/plain DISPLAY_NAME, so the lookup has to
     * match what it actually stored. One stable uri per process keeps every record
     * in the same file instead of creating `harness-chat-log (1).txt`, `(2)`, …
     */
    private const val DISPLAY_NAME = "harness-chat-log.txt"
    private const val CRASH_DISPLAY_NAME = "harness-crash-log.txt"
    private const val CRASH_FALLBACK_FILE = "last-crash.txt"

    @Volatile
    private var fileUri: Uri? = null

    @Volatile
    private var crashUri: Uri? = null

    @Volatile
    private var installed = false

    private val handlingUncaught = AtomicBoolean(false)

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-log").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Install as [ChatTrace.sink] and register the uncaught exception crash writer once per process. */
    fun install(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (!installed) {
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                val handler = installUncaughtHandler(previous) { thread, throwable ->
                    recordUncaughtCrashSync(app, thread, throwable)
                }
                Thread.setDefaultUncaughtExceptionHandler(handler)
                installed = true
            }
        }
        ChatTrace.sink = { line -> io.execute { runCatching { append(app, line) } } }
        ChatTrace.record("app start ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})")
    }

    internal fun formatCrashBlock(
        threadName: String,
        throwable: Throwable,
        versionName: String = BuildConfig.VERSION_NAME,
        gitHash: String = BuildConfig.GIT_HASH,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val stack = stackTraceString(throwable).take(MAX_CRASH_STACK_CHARS)
        val ts = synchronized(stamp) { stamp.format(Date(nowMs)) }
        return buildString {
            append("=== FATAL CRASH ").append(ts)
            append(" | v").append(versionName).append(" (").append(gitHash).append(")")
            append(" | thread=").append(threadName).append(" ===\n")
            append(stack).append('\n')
        }
    }

    internal fun formatCrashSummary(
        threadName: String,
        throwable: Throwable,
        versionName: String = BuildConfig.VERSION_NAME,
        gitHash: String = BuildConfig.GIT_HASH,
    ): String {
        val oneLine = stackTraceString(throwable).replace('\n', ' ').replace('\r', ' ').trim().take(3800)
        return "FATAL v$versionName ($gitHash) [$threadName] $oneLine"
    }

    /** Formats a throwable with its full stack trace and causal chain. */
    internal fun stackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    internal fun installUncaughtHandler(
        previous: Thread.UncaughtExceptionHandler?,
        onCrash: (Thread, Throwable) -> Unit,
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
        if (handlingUncaught.compareAndSet(false, true)) {
            try {
                runCatching { onCrash(thread, throwable) }
            } finally {
                handlingUncaught.set(false)
            }
        }
        previous?.uncaughtException(thread, throwable)
    }

    internal fun writeFallbackCrashFile(filesDir: File, block: String) {
        if (!filesDir.exists()) filesDir.mkdirs()
        File(filesDir, CRASH_FALLBACK_FILE).writeText(block, Charsets.UTF_8)
    }

    private fun recordUncaughtCrashSync(app: Context, thread: Thread, throwable: Throwable) {
        val block = formatCrashBlock(thread.name, throwable)
        runCatching { writeFallbackCrashFile(app.filesDir, block) }
        runCatching {
            crashUri = appendToMediaStore(
                app = app,
                displayName = CRASH_DISPLAY_NAME,
                existingUri = crashUri,
                entry = block,
                maxChars = CRASH_MAX_CHARS,
            )
        }
        runCatching {
            val summary = formatCrashSummary(thread.name, throwable)
            append(app, summary)
        }
    }

    private fun collection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun find(app: Context, displayName: String): Uri? = runCatching {
        app.contentResolver.query(
            collection(),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection(), cursor.getLong(0)) else null
        }
    }.getOrNull()

    private fun append(app: Context, line: String) {
        val ts = synchronized(stamp) { stamp.format(Date()) }
        val entry = ts + " " + line.replace('\n', ' ').take(4000) + "\n"
        fileUri = appendToMediaStore(
            app = app,
            displayName = DISPLAY_NAME,
            existingUri = fileUri,
            entry = entry,
            maxChars = MAX_CHARS,
        )
    }

    private fun appendToMediaStore(
        app: Context,
        displayName: String,
        existingUri: Uri?,
        entry: String,
        maxChars: Int,
    ): Uri? {
        val uri = existingUri ?: find(app, displayName) ?: app.contentResolver.insert(collection(), ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }) ?: return null
        val existing = runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        val text = existing + entry
        val capped = if (text.length > maxChars) text.takeLast(maxChars / 2) else text
        val written = runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(capped.toByteArray()) }
            true
        }.getOrDefault(false)
        return if (written) uri else null
    }
}
