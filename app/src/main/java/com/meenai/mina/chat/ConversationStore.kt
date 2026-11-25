package com.meenai.mina.chat

import android.content.Context
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.memory.MemoryNote
import com.meenai.mina.core.memory.NoteKind
import com.meenai.mina.core.skills.SkillNote
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** A small JSON file for each conversation is placed in the App's private directory; it will remain after restarting. */
class ConversationStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Written dialogue numbers: sent once when saving a round, changing a certain round, and removing
     * memory prompts, no matter who wrote it (dialogue page, suspension capsule, timed task). The
     * conversation page and sidebar are reread accordingly, and what's on the screen is the saved copy.
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The deleted conversation number: [delete] is sent once ([changes] is also sent). The suspension capsule accordingly removes the task that just ended the conversation, and the card no longer continues it. */
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    /**
     * Which conversation the user is in at the moment, see [CurrentChat]. The `current` file exists in
     * the session directory (not `.json`, do not enter [list]); it can still be read after the process
     * is restarted, and the capsule does not have to wait for the session file to be written down to
     * know which one is newly opened.
     */
    private val _current = MutableStateFlow(readCurrent())

    val current: StateFlow<CurrentChat?> = _current.asStateFlow()

    fun currentChat(): CurrentChat? = _current.value

    /** Remember which conversation the user is in at the moment; deleted conversations cannot be remembered. After writing it in, send it [current]. */
    @Synchronized
    fun setCurrent(chat: CurrentChat?) {
        val target = when (chat) {
            is CurrentChat.Open -> if (safe(chat.id) in deleted) null else chat
            else -> chat
        }
        if (target == _current.value) return
        dir.mkdirs()
        runCatching {
            when (target) {
                null -> currentFile().delete()
                CurrentChat.Fresh -> currentFile().writeText(CURRENT_FRESH)
                is CurrentChat.Open -> currentFile().writeText(safe(target.id))
            }
        }
        _current.value = target
    }

    private fun readCurrent(): CurrentChat? {
        val f = currentFile().takeIf { it.isFile } ?: return null
        val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        return when {
            text.isEmpty() -> null
            text == CURRENT_FRESH -> CurrentChat.Fresh
            else -> safe(text).takeIf(String::isNotEmpty)?.let(CurrentChat::Open)
        }
    }

    /**
     * Conversation list, the most recently modified one is first; [ConversationSummary.title] is the
     * title (changed or created by the user), if not, it is the first sentence;
     * [ConversationSummary.pinnedAt] is the time that was pinned to the top; [ConversationSummary.page]
     * is the page from which it started. Conversations without content are not listed.
     */
    @Synchronized
    fun list(): List<ConversationSummary> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                val first = turns(id).firstOrNull() ?: return@mapNotNull null
                ConversationSummary(id, title(id) ?: first.userText, f.lastModified(), pinnedAt(id), pageOrigin(id))
            }

    /** A good title for the conversation (see [ConversationTitler]); if not, it is null if it cannot be read. The title is placed in the `.title` file next to the conversation file, leaving the format of the conversation file unchanged. */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * Deleted conversations. The "rewrite" that arrived after deletion (the title created in the
     * background, the ending and explanation after the completion of the task) will not be written back;
     * if you add another round ([append]) to it, you will start using this number again and remove it
     * from here. Only recorded in the process.
     */
    private val deleted = mutableSetOf<String>()

    /**
     * Delete everything that's left of a conversation: conversation files, titles, pins, as well as
     * half-written temporary files and damaged files that have been moved aside. If you delete anything,
     * send it once [changes] and [deletions]. The dialog workspace, scheduled tasks, and running tasks
     * are not managed here, see [ConversationDeleter].
     */
    @Synchronized
    fun delete(conversationId: String) {
        deleted += safe(conversationId)
        val main = file(conversationId).name
        dir.listFiles { f ->
            f.isFile && (f.name == main || f.name.startsWith("$main.") || f.name == titleFile(conversationId).name || f.name == pinFile(conversationId).name ||
                f.name == pageFile(conversationId).name)
        }
            .orEmpty().forEach { it.delete() }
        // What is deleted is exactly what the user is currently looking at: no longer pointing at it,
        // the capsule falls back to the most recent one
        if (_current.value == CurrentChat.Open(safe(conversationId))) setCurrent(null)
        _changes.tryEmit(conversationId)
        _deletions.tryEmit(conversationId)
    }

    /**
     * Write down the title for the conversation; if there is already a title (given or modified by the
     * user), it will not be changed (the title will only be given once). Returns true if written.
     * Therefore, when the background title is late, the name that the user changed first will not be
     * blocked.
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** The user renames the conversation: whether it has a title or not, it will be replaced by [title]; if it is empty, it will not be changed. Returns true if written. */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || safe(conversationId) in deleted) return false
        dir.mkdirs()
        val ok = runCatching { titleFile(conversationId).writeText(t) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** The time when the conversation was pinned to the top; if it is not pinned or cannot be read, it is null. Place it in the `.pin` file next to the conversation file. */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** Pin it to the top ([pinned] is true, note the time [at]) or cancel the pin. Deleted conversations remain unchanged. Changed to return true. */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /**
     * Which page the conversation started from (the user asks Mina on the browser page); if not, it is
     * null if it cannot be read. Place it in the `.page` file next to the conversation file (JSON: url,
     * host, title), and the format of the conversation file will not change; the old conversation does
     * not have this file.
     */
    @Synchronized
    fun pageOrigin(conversationId: String): PageOrigin? = runCatching {
        val f = pageFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        PageOrigin(str(o, "url") ?: return@runCatching null, str(o, "host") ?: return@runCatching null, str(o, "title").orEmpty())
    }.getOrNull()

    /** Note which page the conversation begins on (change to the latest page when you continue on the same page). Deleted conversations remain unchanged. Returns true if written. */
    @Synchronized
    fun setPageOrigin(conversationId: String, origin: PageOrigin): Boolean {
        if (safe(conversationId) in deleted) return false
        dir.mkdirs()
        val json = buildJsonObject { put("url", origin.url); put("host", origin.host); put("title", origin.title) }
        val ok = runCatching { pageFile(conversationId).writeText(json.toString()) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** When a file is damaged, it will be treated as an empty dialog and will not block new tasks. */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    /** Each round is accompanied by the files (file cards) exported for that round. */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val f = file(conversationId)
        if (!f.isFile) return emptyList()
        return read(f).orEmpty()
    }

    /** Returns null if it cannot be read (not a JSON array). */
    private fun read(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(f.readText()) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val turn = Turn(
                field("userText") ?: return@mapNotNull null, field("outcome").orEmpty(), field("status") ?: return@mapNotNull null, field("note"), field("runId"),
                memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                    val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                    MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
                },
                usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                result = field("result"),
                steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                interruptedReplies = (o["interruptedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                finishedReplies = (o["finishedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
                groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                keptPage = field("keptPage"),
                keptPageClosed = field("keptPageClosed"),
            )
            turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
        }
    } catch (e: SerializationException) {
        null
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs") ?: return null, field("uri") ?: return null)
    }

    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        deleted -= safe(conversationId)
        val f = file(conversationId)
        val prior = if (f.isFile) read(f) else emptyList()
        if (prior == null) {
            // Move the damaged files aside and do not overwrite them with new content.
            f.renameTo(File(dir, "${f.name}.corrupt-${System.currentTimeMillis()}"))
        }
        write(conversationId, prior.orEmpty() + (turn to files))
    }

    /** Change the run number to the round numbered [runId] (add the ending, memory and skill tips after the mission); if you can’t find it, don’t move. */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val f = file(conversationId)
        if (!f.isFile) return
        val records = read(f) ?: return
        if (records.none { it.first.runId == runId }) return
        write(conversationId, records.map { (t, fs) -> (if (t.runId == runId) transform(t) else t) to fs })
    }

    /** When the memory is deleted or cleared entirely: the prompts and "used" records pointing to these entries are removed; when [ids] is null, all are removed. */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val records = read(f) ?: return@forEach
            fun keep(id: String) = ids != null && id !in ids
            val stripped = records.map { (t, fs) -> t.copy(memoryNotes = t.memoryNotes.filter { keep(it.memoryId) }, usedMemory = t.usedMemory.filter(::keep)) to fs }
            if (stripped != records) write(f.name.removeSuffix(".json"), stripped)
        }
    }

    private fun write(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        dir.mkdirs()
        val json = JsonArray(all.map { (it, fs) ->
            buildJsonObject {
                put("userText", it.userText); put("outcome", it.outcome); put("status", it.status)
                it.note?.let { n -> put("note", n) }; it.runId?.let { r -> put("runId", r) }; it.result?.let { r -> put("result", r) }
                // Only the type and number are saved: the memory content is only in the encrypted memory
                // file, and the interface can be retrieved immediately
                if (it.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(it.memoryNotes.map { n ->
                    buildJsonObject {
                        put("kind", n.kind.name); put("memoryId", n.memoryId)
                        n.undoToken?.let { t -> put("undoToken", t) }; n.pendingId?.let { p -> put("pendingId", p) }
                    }
                }))
                if (it.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(it.usedMemory.map(::JsonPrimitive)))
                if (it.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(it.unconfirmed.map(::JsonPrimitive)))
                if (it.steers.isNotEmpty()) put("steers", JsonArray(it.steers.map(::JsonPrimitive)))
                if (it.interruptedReplies.any(String::isNotEmpty)) put("interruptedReplies", JsonArray(it.interruptedReplies.map(::JsonPrimitive)))
                if (it.finishedReplies.any(String::isNotEmpty)) put("finishedReplies", JsonArray(it.finishedReplies.map(::JsonPrimitive)))
                if (it.groups.isNotEmpty()) put("groups", JsonArray(it.groups.map(::JsonPrimitive)))
                it.keptPage?.let { h -> put("keptPage", h) }; it.keptPageClosed?.let { r -> put("keptPageClosed", r) }
                if (it.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(it.skillNotes.map { n ->
                    buildJsonObject { put("text", n.text); n.draftId?.let { d -> put("draftId", d) }; n.suspended?.let { x -> put("suspended", x) } }
                }))
                if (fs.isNotEmpty()) put("files", JsonArray(fs.map { f ->
                    buildJsonObject { put("name", f.name); put("size", f.size); put("savedAs", f.savedAs); put("uri", f.uri) }
                }))
            }
        })
        // Write the temporary file first and then rename it. If it is killed in the middle of writing,
        // half of the file will not be left.
        val tmp = File(dir, "${file(conversationId).name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(conversationId))) {
            file(conversationId).writeText(json.toString())
            tmp.delete()
        }
        _changes.tryEmit(conversationId)
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** The share shared by the App process (reading and writing are serialized in its lock). */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.meenai.mina.HarnessApp).conversations

        /** `current` The value in the file that indicates "newly opened conversation that has not yet been spoken" (the conversation number will only be alphanumeric, `-`, `_`, and will not collide). */
        private const val CURRENT_FRESH = "fresh"
    }

    // The id comes from newConversation or is passed in from outside. Only safe characters are left to
    // avoid spelling out the path outside the directory.
    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun pageFile(id: String) = File(dir, "${safe(id)}.page")

    /** Which conversation the user is currently in, see [CurrentChat]; if not `.json`, do not enter [list]. */
    private fun currentFile() = File(dir, "current")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long, val pinnedAt: Long? = null, val page: PageOrigin? = null)

/**
 * Which conversation the user is in at the moment: The one that is newly opened in the App and has not
 * spoken yet is [Fresh], and the one currently being viewed is [Open]; there is no record (never entered
 * Mina, old version), which is null. Based on this, the floating capsule picks up the one it should pick
 * up, instead of guessing based on "which conversation file was last written" - the newly opened
 * conversation does not have a file yet, and the guessed one will be the previous one.
 */
sealed interface CurrentChat {
    data object Fresh : CurrentChat

    data class Open(val id: String) : CurrentChat
}

/** Which page the conversation starts from: URL, host name, page title (empty string if not). */
data class PageOrigin(val url: String, val host: String, val title: String = "")
