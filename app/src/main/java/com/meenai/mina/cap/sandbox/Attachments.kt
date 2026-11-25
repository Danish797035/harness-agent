package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.SandboxTree
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.SandboxPaths
import com.meenai.mina.core.workspace.WorkspaceFileGate
import com.meenai.mina.core.cap.sandbox.ShareRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** A batch of files are attached: [added] is (sandbox path, size), [rejected] is the name of the confiscated file (too large or cannot be read). */
data class ImportResult(val added: List<Pair<String, Long>>, val rejected: List<String>)

/** Files attached by the user in the chat: Copied into inbox/ in the current conversation workspace, and the sandbox is in /workspace/inbox/. */
object Attachments {
    const val MAX_BYTES = 200L shl 20
    const val MIN_FREE = 500L shl 20
    const val WORKSPACE_MAX = 4L shl 30
    private const val CHECK_EVERY = 16L shl 20
    private const val MAX_NAME = 120

    /** Remove path separators, control characters and format characters, retain Chinese characters and spaces, and no more than 120 characters; when nothing is left, it is called attachment. */
    fun safeName(display: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < display.length) {
            val cp = display.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '/'.code || cp == '\\'.code || ShareRules.hiddenChar(cp)) continue
            sb.appendCodePoint(cp)
        }
        val name = sb.toString().trim().trimStart('.')
        // The length of the file system is calculated in bytes: the Chinese name is 120 characters and
        // may exceed 255 bytes; the extension is retained when truncated
        return ShareRules.fitName(name.ifEmpty { "attachment" }, maxChars = MAX_NAME)
    }

    /** If there is already a file with the same name, add "(2)" "(3)"... and leave the extension at the end. */
    fun numbered(name: String, n: Int): String = WorkspaceFileGate.numbered(name, n)

    /**
     * Copy to inbox/ under [workspace]; each file is ≤ 200 MB (anything exceeding will be deleted and
     * recorded as rejected). Check the space before starting each file (also every 16 MB during
     * copying): if the available space on the mobile phone is less than 500 MB, or the workspace exceeds
     * 4 GB, it will not be charged. Created in inbox/ by [gate] (not overwritten, verified by handle):
     * inbox cannot be written even if it is replaced by a link by the command in the sandbox; when it
     * fails, only the file created by itself is deleted.
     */
    suspend fun importInto(
        context: Context, uris: List<Uri>, workspace: File, gate: WorkspaceFileGate, tree: SandboxTree,
        freeBytes: () -> Long = { workspace.usableSpace }, workspaceBytes: () -> Long = { 0L },
    ): ImportResult = tree.shared { withContext(Dispatchers.IO) { // Write to files/sandbox/: and reset mutex
        val added = mutableListOf<Pair<String, Long>>()
        val rejected = mutableListOf<String>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val display = displayName(context, uri) ?: uri.lastPathSegment ?: "attachment"
            val base = safeName(display)
            if (!roomLeft(freeBytes, workspaceBytes)) { rejected += base; continue }
            val result = try { copyOne(context, uri, workspace, gate, base) { roomLeft(freeBytes, workspaceBytes) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (result == null) rejected += base else added += result
        }
        ImportResult(added, rejected)
    } }

    private fun roomLeft(freeBytes: () -> Long, workspaceBytes: () -> Long) =
        freeBytes() >= MIN_FREE && workspaceBytes() <= WORKSPACE_MAX

    private suspend fun copyOne(context: Context, uri: Uri, workspace: File, gate: WorkspaceFileGate, base: String, room: () -> Boolean): Pair<String, Long>? {
        workspace.mkdirs()
        val (name, g) = try { gate.createNumbered(workspace, INBOX, base, max = 1000) } catch (e: SandboxError) { return null }
        var size = 0L
        g.use {
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open")
                input.use {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = it.read(buf)
                        if (r < 0) break
                        size += r
                        if (size > MAX_BYTES) throw IllegalStateException("too large")
                        if (size % CHECK_EVERY < r && !room()) throw IllegalStateException("storage low")
                        g.write(if (r == buf.size) buf else buf.copyOf(r))
                    }
                }
            } catch (e: Throwable) {
                g.delete()
                throw e
            }
        }
        return "${SandboxPaths.WORKSPACE}/$INBOX/$name" to size
    }

    private const val INBOX = "inbox"

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}
