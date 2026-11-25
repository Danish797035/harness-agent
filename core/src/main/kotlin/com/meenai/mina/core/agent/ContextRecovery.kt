package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role

/**
 * The certainty after compression is: the user’s original words (including interjections) and the fixed
 * knowledge/skill rules cannot disappear. The runtime status is not looked at when comparing (folding
 * will remove it, the same message is still counted); if it is really missing, it will be put back to
 * its position in the complete history, not connected to the end.
 */
internal object ContextRecovery {
    fun ensure(full: List<Message>, compressed: List<Message>): List<Message> {
        val userCounts = compressed.filter { it.author == Author.User }.groupingBy(::userKey).eachCount().toMutableMap()
        val ruleCounts = compressed.mapNotNull(::ruleKey).groupingBy { it }.eachCount().toMutableMap()
        fun <K> MutableMap<K, Int>.take(key: K): Boolean {
            val left = this[key] ?: 0
            if (left > 0) this[key] = left - 1
            return left > 0
        }
        val missing = full.indices.filter { i ->
            val m = full[i]
            if (m.author == Author.User) !userCounts.take(userKey(m)) else ruleKey(m)?.let { !ruleCounts.take(it) } ?: false
        }
        if (missing.isEmpty()) return compressed
        val origins = origins(full, compressed)
        val out = compressed.toMutableList()
        val at = origins.toMutableList<Int?>()
        for (i in missing) {
            // Placed before the first message that comes after it; not sandwiched between the call and
            // the result
            var pos = at.indexOfFirst { it != null && it > i }.let { if (it < 0) out.size else it }
            while (pos < out.size && out[pos].role == Role.Tool) pos++
            out.add(pos, ContextBuilder.withoutRuntimeState(full[i]))
            at.add(pos, i)
        }
        return out
    }

    /** Which piece of the complete history does each compressed message come from (identify by call number or as is; newly written ones such as summary are null). */
    private fun origins(full: List<Message>, compressed: List<Message>): List<Int?> {
        val byId = HashMap<String, Int>()
        val byKey = HashMap<Message, ArrayDeque<Int>>()
        full.forEachIndexed { i, m ->
            ids(m).forEach { byId.putIfAbsent(it, i) }
            byKey.getOrPut(userKey(m)) { ArrayDeque() }.addLast(i)
        }
        return compressed.map { m -> ids(m).firstNotNullOfOrNull(byId::get) ?: byKey[userKey(m)]?.removeFirstOrNull() }
    }

    private fun ids(m: Message): List<String> = m.parts.mapNotNull {
        when (it) {
            is Part.ToolCall -> it.id
            is Part.ToolResult -> it.callId
            else -> null
        }
    }

    private fun userKey(m: Message): Message = ContextBuilder.withoutRuntimeState(m)

    private fun ruleKey(message: Message): Pair<ContextKind, String?>? =
        ContextBuilder.kindOf(message)?.takeIf { it.pinned }?.let { it to ContextBuilder.sourceOf(message) }
}
