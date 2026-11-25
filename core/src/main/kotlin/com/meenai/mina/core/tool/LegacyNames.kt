package com.meenai.mina.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool name before renaming → Current name, generated from registered tools according to rules (no
 * handwritten comparison table): The tool name of the ability is `<能力>_<其余>`; the verb that exists on
 * several screens used to have only one name without a prefix (`tap`), and now it is one for each
 * screen; the verb that has only one screen, the previous name is [formerPrefix] The old prefix of that
 * ability in it plus the rest (the mobile phone has no prefix, `launch_app`; the web page is
 * `browser_`); the rest is also changed and written in [renamed] (current name → previous name);
 * [retired] is the old name that has been removed and has no replacement tool (old name → what to do
 * now), rewritten into that statement. [moved] did not follow the above rules and completely changed its
 * name (old name → current name; one is divided into several).
 *
 * Only used to read old things: saved experience routes, display of running records, personal skills and
 * practices of loading context, and prompts when the model calls the old name. Tools called with the old
 * name will not be quietly executed under the new name.
 */
class LegacyNames(
    private val index: ToolIndex,
    formerPrefix: Map<String, String>,
    renamed: Map<String, String> = emptyMap(),
    private val retired: Map<String, String> = emptyMap(),
    moved: Map<String, List<String>> = emptyMap(),
) {
    private val table: Map<String, List<String>>

    init {
        val infos = index.names.mapNotNull { index[it] }
        val out = LinkedHashMap<String, MutableList<String>>()
        for (t in infos) {
            // The new mobile phone commands do not have unprefixed historical names.
            if (t.verb in setOf(Verb.Query, Verb.Collect, Verb.PhoneRun, Verb.PhoneFind, Verb.PhoneExtract,
                    Verb.PhoneDialog, Verb.PhoneEvent, Verb.PhoneGesture, Verb.PhoneCommand)) continue
            val cap = t.capability ?: continue
            val prefix = formerPrefix[cap] ?: continue
            if (!t.name.startsWith("${cap}_")) continue
            val rest = t.name.removePrefix("${cap}_")
            // Other screens have the same verb, the same rest: what used to be a common name without a
            // prefix
            val shared = infos.any { o -> o !== t && o.capability != cap && o.capability in formerPrefix && o.verb == t.verb && o.name == "${o.capability}_$rest" }
            val old = renamed[t.name] ?: if (shared) rest else prefix + rest
            if (old != t.name && old !in index.names) out.getOrPut(old) { mutableListOf() } += t.name
        }
        for ((old, news) in moved) news.filter { it in index.names }.takeIf { it.isNotEmpty() }?.let { out.getOrPut(old) { mutableListOf() } += it }
        table = out
    }

    /** The old name corresponds to the current name (how many verbs are available on several screens); the old name is not empty. */
    fun replacements(old: String): List<String> =
        table[old].orEmpty()

    /**
     * The new name that should be prompted when the model calls the old name [old]: one is split into
     * knowledge and skills. Select the corresponding one according to the kind in the parameter (or the
     * `kind:` prefix of the name). If you can't tell, give both.
     */
    fun replacements(old: String, args: JsonObject): List<String> {
        val all = replacements(old)
        if (all.size < 2) return all
        val kind = args.text("kind") ?: args.text("name")?.substringBefore(':', "")
        val verbs = when (kind) {
            "app", "site" -> setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead)
            "skill", "personal" -> setOf(Verb.SkillSearch, Verb.SkillRead)
            else -> return all
        }
        return all.filter { index.verb(it) in verbs }.ifEmpty { all }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** What to do now with the old name that has been removed and has no replacement tool; it is not the case that the old name is null. */
    fun retiredAs(old: String): String? = retired[old]

    /** Old names registered one by one. */
    val names: Set<String> get() = table.keys

    /** The new name of the old name in [accept] (for example, only the one that can be recorded in the route); if it is not the old name, or there is no suitable one, it is null. */
    fun resolve(old: String, accept: (String) -> Boolean = { true }): String? = replacements(old).firstOrNull(accept)

    /**
     * The old tool names mentioned in the text are replaced with the current ones: the old underlined
     * names (`launch_app`, `browser_navigate`) are replaced everywhere; those that are English words
     * (`tap`, `back`) are only replaced with backticks. Some screens have "`phone_tap` / `web_tap`"
     * written on them.
     */
    fun rewrite(text: String): String = renamedIn(retiredIn(text))

    /**
     * The old name that has been removed is replaced with the sentence of what to do now, together with
     * the previous action of "call / call": replace everything in the backtick; the word without
     * backtick (it may be an English word) is only replaced where it is the tool name: immediately after
     * "call / call", or sandwiched in Chinese.
     */
    private fun retiredIn(text: String): String {
        var out = text
        for ((old, instead) in retired) {
            val name = Regex.escape(old)
            out = Regex("(?:(?i:call)\\s+|调用\\s*)?`$name`").replace(out, instead)
            out = Regex("(?:(?i:call)\\s+|调用\\s*)(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])").replace(out, instead)
            out = Regex("(?<=[\\u4e00-\\u9fff])\\s*$name(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])$name\\s*(?=[\\u4e00-\\u9fff])").replace(out) { " $instead " }
        }
        return out
    }

    private fun renamedIn(text: String): String {
        if (table.isEmpty()) return text
        fun joined(news: List<String>, tick: Boolean): String {
            fun t(s: String) = if (tick) "`$s`" else s
            return news.joinToString(" / ") { t(it) }
        }
        val ticked = TICKED.replace(text) { m -> replacements(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { joined(it, true) } ?: m.value }
        return WORD.replace(ticked) { m ->
            val w = m.value
            if ('_' !in w) w else replacements(w).takeIf { it.isNotEmpty() }?.let { joined(it, false) } ?: w
        }
    }

    private companion object {
        val TICKED = Regex("`([a-z][a-z0-9_]*)`")
        val WORD = Regex("(?<![A-Za-z0-9_`])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_`])")
    }
}

