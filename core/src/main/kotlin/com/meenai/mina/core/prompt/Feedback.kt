package com.meenai.mina.core.prompt

/**
 * The tool results and the feedback text given to the model in the main loop (prompts, error
 * descriptions, reminders, titles of reference materials) are all in the.md file under
 * prompts/feedback/: Each item starts with a line of "## key", and the following is the original text
 * before the next one (multiple lines are allowed, and blank lines at the beginning and end are not
 * counted); {{name}} is filled in by the caller.
 */
object Feedback {
    /** Feedback file; key is unique among all files. */
    val FILES = listOf("tools", "loop")
    private val HEADING = Regex("^## ([a-z0-9_.]+)$")

    private val entries: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for (f in FILES) parse(Prompts.read("feedback/$f.md")).forEach { (k, v) -> check(out.put(k, v) == null) { "duplicate feedback key $k" } }
        out
    }

    /** The items in a feedback file (key → original text) are arranged in order of appearance; instructions before the first item are not counted. */
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var key: String? = null
        val body = mutableListOf<String>()
        fun flush() { key?.let { out += it to body.joinToString("\n").trim('\n') }; body.clear() }
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line)
            if (m != null) { flush(); key = m.groupValues[1] } else if (key != null) body += line
        }
        flush()
        return out
    }

    operator fun get(key: String): String = Prompts.fill(raw(key), emptyMap())

    fun format(key: String, vararg vars: Pair<String, Any?>): String = Prompts.fill(raw(key), vars.toMap())

    fun has(key: String): Boolean = key in entries

    /** For items starting with [prefix], remove the prefix (for example, follow the instructions given by the error code). */
    fun section(prefix: String): Map<String, String> =
        entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    val keys: Set<String> get() = entries.keys

    private fun raw(key: String): String = checkNotNull(entries[key]) { "missing feedback $key" }
}
