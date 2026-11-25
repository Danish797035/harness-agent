package com.meenai.mina.core.cap.web

/**
 * When the user asks Mina in Mina's browser, facing the page she is looking at, how to write this
 * sentence: the first paragraph indicates which page it is (fixed English, the model can be understood
 * at a glance; dialogue pages and cards recognize this paragraph), and after a blank line is what the
 * user said this time (as is). For example
 * ```
 * [Web page I'm viewing in Harness Agent's browser. It is attached to this task as your current web
 * tab.]
 * Title: Noise Canceling Headphones - Search Results
 * URL: https://shop.example.com/s?k=Noise Canceling Headphones
 * Selected text:
 * | In-ear noise-canceling headphones B2 Pro
 * [End of page]
 *
 * Choose one within 700 on this page
 * ```
 * This sentence is saved as it is in the conversation and handed over to the task. This is what the
 * model sees; it is carried when the task starts from the browser page, and when the newly selected word
 * is included in the interjection. Language prompts, language check of answers and memorized quotations
 * only recognize the words typed by the user after the explanation ([words]). Each line of the selected
 * words starts with "|" and is separated from the remaining lines of the description; the page title and
 * the selected words will be truncated.
 */
object AttachedPage {
    const val HEADER = "[Web page I'm viewing in Harness Agent's browser. It is attached to this task as your current web tab.]"
    const val END = "[End of page]"
    private const val TITLE = "Title: "
    private const val URL = "URL: "
    private const val SELECTED = "Selected text:"
    private const val SELECTED_LINE = "| "

    /** The page title can only have this many words at most. */
    const val MAX_TITLE = 200

    /** The selected words can be given to the model at most this many words, and any more words will be cut off and "..." added at the end. */
    const val MAX_SELECTION = 2000

    /** The URL can be at most this long (extremely long tracking parameters are not included). */
    const val MAX_URL = 1000

    /** The page you are looking at: [selection] is the word selected by the user, and if it is not selected, it is null. */
    data class Page(val url: String, val title: String, val selection: String? = null)

    /** The split sentence: [page] is that page (if not included, it is null), [words] is what the user said this time (if not included, it is the original words). */
    data class Parsed(val page: Page?, val words: String)

    /** The explanation comes first and the words follow; when the words are empty, there is only explanation. */
    fun compose(page: Page, words: String): String {
        val lines = mutableListOf(HEADER)
        oneLine(page.title).take(MAX_TITLE).takeIf(String::isNotEmpty)?.let { lines += TITLE + it }
        lines += URL + oneLine(page.url).take(MAX_URL)
        clip(page.selection)?.let { sel ->
            lines += SELECTED
            sel.lines().forEach { lines += SELECTED_LINE + it }
        }
        lines += END
        val block = lines.joinToString("\n")
        val w = words.trim()
        return if (w.isEmpty()) block else "$block\n\n$w"
    }

    fun parse(text: String): Parsed {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return Parsed(null, text)
        val end = lines.indexOfFirst { it.trim() == END }
        if (end < 0) return Parsed(null, text)
        var title = ""
        var url = ""
        val selected = mutableListOf<String>()
        var inSelection = false
        for (l in lines.subList(1, end)) {
            when {
                inSelection && l.startsWith(SELECTED_LINE.trimEnd()) -> selected += l.removePrefix(SELECTED_LINE.trimEnd()).removePrefix(" ")
                l.startsWith(TITLE) -> title = l.removePrefix(TITLE)
                l.startsWith(URL) -> url = l.removePrefix(URL)
                l.trim() == SELECTED -> inSelection = true
            }
        }
        val selection = selected.joinToString("\n").trim().ifEmpty { null }
        return Parsed(Page(url.trim(), title.trim(), selection), lines.drop(end + 1).joinToString("\n").trim())
    }

    /** After removing the page description; return it as it is without it. */
    fun words(text: String): String = parse(text).words

    /** This sentence carries a page description. */
    fun has(text: String): Boolean = text.lineSequence().firstOrNull()?.trim() == HEADER

    /** Selected words: remove the leading and trailing blanks, remove the trailing blanks of each line, truncate and add "..." if it exceeds [MAX_SELECTION]; the empty ones are null. The end line of the description will not appear inside. */
    fun clip(selection: String?): String? {
        val t = selection?.lines()?.joinToString("\n") { it.trimEnd() }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val cut = if (t.length <= MAX_SELECTION) t else t.take(MAX_SELECTION).trimEnd() + "…"
        return cut.replace(END, "[End]")
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
