package com.meenai.mina.core.memory

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelJson
import com.meenai.mina.core.model.ModelJson.field
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Suppress("EnumEntryName")
enum class NoteKind { added, updated, forgot, pending }

/**
 * A row below the results card. Only contains type and number, without memory content: the conversation
 * file is plain text, and the content is fetched from the encrypted memory each time ([text]).
 * [undoToken] is handed over to [MemoryStore.undo]; [pendingId] is pending approval ("OK" = approve,
 * "Don't" = revoke or delete).
 */
data class MemoryNote(val kind: NoteKind, val memoryId: String, val undoToken: String?, val pendingId: String?) {
    /** [item] is the one at the moment of [memoryId]; when it is no longer there (deleted, cleared), write "a deleted memory". */
    fun text(item: MemoryItem?): String {
        val content = item?.content ?: DELETED
        return when (kind) {
            NoteKind.added -> "记住了:$content"
            NoteKind.updated -> "更新了:$content" + (item?.replaces?.let { "(原来:$it)" } ?: "")
            NoteKind.forgot -> if (item == null) "忘掉了一条记忆" else "忘掉了:$content"
            NoteKind.pending -> "要我以后都这样做吗?$content"
        }
    }

    companion object {
        const val DELETED = "一条已删除的记忆"
    }
}

/**
 * A separate call after the task (lightweight model, without tools): only look at what the user said
 * this time and related existing memories, and provide additions, modifications and deletions. Every
 * item given by the model is checked again by the code; if the model makes an error or the response is
 * unqualified, nothing will be done, and the outcome of the task will not be affected.
 */
class MemoryExtractor(private val model: ChatModel) {
    suspend fun extract(utterances: List<String>, related: List<MemoryItem>, store: MemoryStore, source: (String) -> MemorySource): List<Change> {
        if (utterances.none { it.isNotBlank() }) return emptyList()
        val labels = related.take(MAX_RELATED).mapIndexed { i, m -> "#${i + 1}" to m }.toMap()
        val ops = try {
            ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, listOf(Part.Text(request(utterances, labels))), Author.Mina)), ::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val said = UserUtterances().apply { utterances.forEach(::add) }
        return ops.take(MAX_OPS).mapNotNull { apply(it, labels, said, store, source) }
    }

    private fun apply(o: JsonObject, labels: Map<String, MemoryItem>, said: UserUtterances, store: MemoryStore, source: (String) -> MemorySource): Change? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val quote = str("quote")?.takeIf { said.contains(it) } ?: return null
        val keywords = (o["keywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
        val target = str("target")?.let { t -> labels[t] ?: labels["#$t"] }
        fun fromUser(content: String) = (listOf(content) + keywords).all { said.unsupportedSpecifics(it, quote).isEmpty() }
        return when (str("op")) {
            "add" -> {
                val kind = MemoryKind.entries.find { it.name == str("kind") } ?: return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.add(kind, content, keywords, source(quote)).getOrNull()
            }
            "update" -> {
                val t = target ?: return null
                // One-time parameters cannot change long-term preferences
                if (ONE_OFF.containsMatchIn(quote)) return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.update(t.id, content, keywords.takeIf { it.isNotEmpty() }, source(quote)).getOrNull()
            }
            "forget" -> {
                val t = target ?: return null
                if (!FORGET.containsMatchIn(quote)) return null
                store.forget(t.id).getOrNull()
            }
            else -> null
        }
    }

    private fun request(utterances: List<String>, labels: Map<String, MemoryItem>) = buildString {
        appendLine("用户本次说的话:")
        utterances.filter { it.isNotBlank() }.forEachIndexed { i, u -> appendLine("${i + 1}. $u") }
        appendLine()
        if (labels.isEmpty()) append("已有的相关记忆:无")
        else {
            appendLine("已有的相关记忆:")
            labels.forEach { (label, m) -> appendLine("$label [${m.kind.name}] ${m.content}") }
        }
    }.trimEnd()

    companion object {
        const val MAX_OPS = 5
        const val MAX_RELATED = 10
        private val FORGET = Regex("忘|别记|删|不用记|forget", RegexOption.IGNORE_CASE)
        private val ONE_OFF = Regex("这次|这一次|今天先|this time", RegexOption.IGNORE_CASE)

        val PROMPT: String by lazy { Prompts.role("memory_extract") }

        /** Existing memories related to what was said this time (those awaiting approval will also be given to avoid repeating the same approach). */
        fun related(utterances: List<String>, items: List<MemoryItem>): List<MemoryItem> =
            MemoryIndex(items).search(utterances.joinToString("\n"), MAX_RELATED, includePending = true).map { it.first }

        /** Change the prompt line under the result card. */
        fun notes(changes: List<Change>): List<MemoryNote> = changes.map { c ->
            when (c) {
                is Change.Added ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.added, c.item.id, c.undoToken, null)
                is Change.Updated ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.updated, c.item.id, c.undoToken, null)
                is Change.Forgot -> MemoryNote(NoteKind.forgot, c.before.id, c.undoToken, null)
            }
        }

        /** The first readable JSON object in the reply (see [ModelJson]); returns null if unqualified. */
        internal fun parse(text: String): List<JsonObject>? {
            val obj = ModelJson.firstObject(text) ?: return null
            return (obj.field("ops", "operations") as? JsonArray)?.mapNotNull { it as? JsonObject }
        }
    }
}

