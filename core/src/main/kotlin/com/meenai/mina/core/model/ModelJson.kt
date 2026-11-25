package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Read the JSON returned by the model as required (verification conclusions, route matching, memory
 * changes...). Different models have different styles of submission: a code block, a few sentences
 * before and after, a paragraph of thought before giving, an object followed by a paragraph of
 * description with curly braces, and key names with different capitalization or writing. These are
 * tolerated here; if you really can’t read it, ask again and make sure it only requires JSON
 * ([completeParsed]).
 */
object ModelJson {
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    /** The first bracketed segment of the reply that can be read as a JSON object (parentheses in the string do not count); null if not. */
    fun firstObject(text: String): JsonObject? {
        var from = text.indexOf('{')
        while (from >= 0) {
            val end = balancedEnd(text, from)
            if (end > from) {
                val obj = try { lenient.parseToJsonElement(text.substring(from, end + 1)) as? JsonObject } catch (e: Exception) { null }
                if (obj != null) return obj
            }
            from = text.indexOf('{', from + 1)
        }
        return null
    }

    /** Starting from { in [start], the position of trimmed }; unbalanced is -1. */
    private fun balancedEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** Remove case, underscores, hyphens, and spaces before key name comparison (differences in writing in verdict, Verdict, and final_verdict). */
    private fun key(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    /** Press [names] to search in order (different writing methods will be recognized); if there is no one, it will be null. */
    fun JsonObject.field(vararg names: String): JsonElement? {
        for (n in names) {
            this[n]?.let { return it }
            val want = key(n)
            entries.firstOrNull { key(it.key) == want }?.let { return it.value }
        }
        return null
    }

    /** I made up a sentence when I couldn't read it the first time. */
    const val JSON_ONLY = "上一条回复读不出 JSON。请只输出一个 JSON 对象,不要代码块标记,不要任何其他文字,格式照系统提示。"

    /**
     * Adjust the model once and use [parse] to read the reply; if you can’t read it out, just follow
     * that reply with [JSON_ONLY] and ask again. Neither time is null. Model errors (exceptions) are
     * thrown to the caller as usual.
     */
    suspend fun <T : Any> completeParsed(model: ChatModel, system: String, messages: List<Message>, parse: (String) -> T?): T? {
        val first = model.complete(system, messages, emptyList())
        val firstText = first.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        parse(firstText)?.let { return it }
        val retry = messages + Message(Role.Assistant, listOf(Part.Text(firstText.ifBlank { "(空)" })), Author.Mina) +
            Message(Role.User, listOf(Part.Text(JSON_ONLY)), Author.Mina)
        val second = model.complete(system, retry, emptyList())
        return parse(second.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }
}
