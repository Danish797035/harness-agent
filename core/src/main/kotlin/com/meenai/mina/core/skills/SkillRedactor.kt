package com.meenai.mina.core.skills

import com.meenai.mina.core.memory.NumberText

/**
 * When precipitating skills, remove the specific values this time: the content within quotation marks
 * in the target and answer, the numerical string with more than 2 digits, and the amount; when drafting,
 * the original screen text quoted by the verification and the text entered this time are also regarded
 * as specific values. Chinese names are not recognized—relying on quotation marks to identify users. All
 * characters should be written in a unified way (NFKC; Chinese numerals, separated numerals combined
 * into a string of Arabic numerals), and comparisons and substitutions should be made in the same way.
 */
object SkillRedactor {
    const val PLACEHOLDER = "<参数>"

    private val QUOTED = listOf("「", "」", "『", "』", "“", "”", "\"", "\"", "‘", "’", "《", "》")
        .chunked(2).map { (open, close) -> Regex(Regex.escape(open) + "([^" + Regex.escape(close) + "\\n]+)" + Regex.escape(close)) }
    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_\-]{30,}""")
    private val GROQ_KEY = Regex("""\bgsk_[A-Za-z0-9_\-]{16,}""")
    private val PAN = Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b""")
    private val UPI = Regex("""\b[a-zA-Z0-9._\-]+@[a-zA-Z0-9_\-]+(?!\.[a-zA-Z])\b""")
    private val SECRETS = listOf(SK_KEY, GOOGLE_KEY, GROQ_KEY, PAN, UPI)

    private val AMOUNT = Regex(
        """(?:[¥$₹]|(?i:\b(?:Rs\b\.?|INR|rupees?)))\s*\d+(?:\.\d+)?""" +
            """|\d+(?:\.\d+)?\s*(?:元|块钱|块|万元|万|美元|(?i:rupees?|Rs\.?|INR)|[¥$₹])(?!\w)"""
    )
    private val DIGITS = Regex("\\d{2,}")
    private val ONLY_DIGITS = Regex("\\d+")
    private val PIECE_SPLIT = Regex("[\\s,.:;!?、。·]+")
    /** These lines in the draft header are identifiers, numbers (run numbers, package names) are not scanned; they are only exempt within the header. */
    private val KEEP_LINES = Regex("^(?:name|apps|packages|domains|learned_from|source):")

    fun norm(s: String): String = NumberText.collapse(s)

    fun literals(goal: String, criteria: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        fun quoted(t: String) = QUOTED.forEach { re -> re.findAll(t).forEach { out += it.groupValues[1].trim() } }
        val g = norm(goal)
        quoted(g)
        out += numbers(g)
        criteria.map(::norm).forEach(::quoted)
        return keep(out)
    }

    /**
     * The entire paragraph of text (check the original screen text quoted and the entered text) together
     * with the numbers, amounts, and paragraphs cut by blanks and punctuation are counted as specific
     * values (the names of people in "Wang Xiaoming: See You Tomorrow" alone must also be replaced).
     */
    fun textLiterals(texts: List<String>): Set<String> =
        keep(texts.map(::norm).flatMap { t -> listOf(t.trim()) + numbers(t) + t.split(PIECE_SPLIT) })

    /** The longer ones are replaced first to prevent the short values from splitting the long values; for pure numbers, only the entire number is replaced ("12" does not move "12306"). */
    fun redact(md: String, literals: Set<String>): String {
        var out = norm(md)
        for (lit in keep(literals.map(::norm)).sortedByDescending { it.length }) {
            out = if (ONLY_DIGITS.matches(lit)) Regex("(?<!\\d)" + lit + "(?!\\d)").replace(out, PLACEHOLDER)
            else out.replace(lit, PLACEHOLDER)
        }
        return out
    }

    /**
     * Scan the full text of the draft again: Replace the remaining amount and numeric strings with more
     * than 2 digits (the order number, phone number, and price are mostly the values this time);
     * [keepDigits] is the number that comes with the App name (appears in the name and alias of the
     * built-in skill), keep it.
     */
    fun scrubNumbers(md: String, keepDigits: Set<String>): String {
        val lines = norm(md).lines()
        val frontEnd = if (lines.firstOrNull()?.trimEnd() == "---") (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: -1 else -1
        return lines.mapIndexed { i, line ->
            if (i in 1 until frontEnd && KEEP_LINES.containsMatchIn(line)) line
            else {
                var l = line
                for (re in SECRETS) l = re.replace(l, PLACEHOLDER)
                DIGITS.replace(AMOUNT.replace(l) { m -> if (m.value.filter(Char::isDigit) in keepDigits) m.value else PLACEHOLDER }) { m ->
                    if (m.value in keepDigits) m.value else PLACEHOLDER
                }
            }
        }.joinToString("\n")
    }

    private fun numbers(t: String) =
        SECRETS.flatMap { re -> re.findAll(t).map { it.value.trim() } } +
            AMOUNT.findAll(t).map { it.value.trim() } +
            DIGITS.findAll(t).map { it.value }

    // Replacing the value of a word will mess up the text ("mother" and "number"); pure numbers are not
    // subject to this restriction
    private fun keep(values: Iterable<String>) =
        values.map { it.trim() }.filter { it.isNotEmpty() && (it.length >= 2 || ONLY_DIGITS.matches(it)) }.toSet()
}
