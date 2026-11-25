package com.meenai.mina.core.agent

/**
 * How to write when a user's words quote a previous message: The first line of the words indicates whose
 * words are quoted (fixed English, the model can understand it at a glance; the dialogue page recognizes
 * this line), and then each line starting with ">" is the content of the quote, and after a blank line
 * is what the user said this time. For example
 * ```
 * > [Quoting your earlier reply]
 * > There will be light rain tomorrow morning...
 *
 * Do you need to bring an umbrella?
 * ```
 * This sentence is saved as it is in the dialogue and handed over to the task (new tasks and
 * interruptions are the same). This is what the model sees; the dialogue page draws the quoted content
 * as a small piece of light color above the bubble. The quoted paragraph: language prompts and language
 * checks of answers do not count; citations of memory and skills only count when they quote what the
 * user said themselves (see [UserWords]).
 */
object QuoteMessage {
    /** Whose words are quoted: Harness’s answer, or what the user himself said. */
    enum class From(val header: String) {
        Mina("> [Quoting your earlier reply]"),
        You("> [Quoting my earlier message]");

        companion object {
            val Harness = Mina
        }
    }

    /** The quoted content should be given to the model in at most this many words, and any more should be cut off and "..." added at the end. */
    const val MAX_CHARS = 1000

    data class Quote(val from: From, val text: String)

    /** The split sentence: [quote] is the quoted one (null if not quoted), [words] is what the user said this time (the original words if not quoted). */
    data class Parsed(val quote: Quote?, val words: String)

    /** Quoted content: remove leading and trailing blanks, truncate and add "..." if it exceeds [MAX_CHARS]. */
    fun clip(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_CHARS) t else t.take(MAX_CHARS).trimEnd() + "…"
    }

    /** The quotation comes first and the words follow; when there is no quotation or the quoted content is empty, it is the original words. */
    fun compose(quote: Quote?, words: String): String {
        val body = quote?.let { clip(it.text) }?.takeIf { it.isNotEmpty() } ?: return words
        val block = (listOf(quote.from.header) + body.lines().map { if (it.isBlank()) ">" else "> $it" }).joinToString("\n")
        return if (words.isEmpty()) block else "$block\n\n$words"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        val from = From.entries.firstOrNull { it.header == lines.firstOrNull()?.trim() } ?: return Parsed(null, text)
        var i = 1
        val quoted = mutableListOf<String>()
        while (i < lines.size && lines[i].startsWith(">")) {
            quoted += lines[i].removePrefix(">").removePrefix(" ")
            i++
        }
        return Parsed(Quote(from, quoted.joinToString("\n").trim()), lines.drop(i).joinToString("\n").trim())
    }

    /** If the reference is removed, the unquoted value will be returned as is. */
    fun words(text: String): String = parse(text).words
}
