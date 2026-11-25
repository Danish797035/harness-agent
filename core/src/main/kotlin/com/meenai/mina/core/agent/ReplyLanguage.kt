package com.meenai.mina.core.agent

import java.lang.Character.UnicodeScript

/**
 * The final answer is whether the language follows the user: according to the writing system (Unicode
 * text) to which the letters belong, without looking at the model or vocabulary. Pure function.
 *
 * The writing is classified into the idiom family: Chinese characters and kana are counted together. If
 * there are enough kana (at least [KANA_SHARE] of these two), it is counted as Japanese, otherwise it is
 * counted as Chinese; proverb is Korean; Latin, Cyrillic, Arabic and other other scripts are in their
 * own family. Numbers, punctuation, white space, and markdown symbols are not letters.
 *
 * Cross-texts must be fair: Chinese and Japanese do not use spaces to segment words. One Chinese
 * character (kana) is roughly one word, and the number of words is calculated; other words that use
 * spaces to segment words are based on the number of words (letters of the same character are connected
 * together as one word), not the number of letters. Otherwise, one English word will be divided into
 * several Chinese characters. Words without lowercase letters in Latin and Cyrillic (GB, OS, model
 * abbreviations) are mostly units and abbreviations and do not count.
 *
 * Parts that are not included in the answer: various quotation marks (""""""""""), brackets (() and ()),
 * codes in backticks, URLs, and angle bracket tags. Most of these are copied from the original text or
 * marked-up original text, which should have been preserved. In addition, if there is only a string of
 * words of the same language family in a row (or a cell separated by |,,) and does not exceed
 * [NAME_UNITS] units, it will not be counted as a name or label (an App name in a list, a cell in a
 * table): answers that only list foreign names will not be judged as language changes.
 *
 * To judge whether the language has been changed, the following conditions must be met at the same time:
 * the user's sentence must be at least [MIN_USER_UNITS] units, the remaining text of the answer must be
 * at least [MIN_ANSWER_UNITS] units, and the language family with the most answers must account for at
 * least [MISMATCH_SHARE], and it must not be the language family with the most words in the user's
 * sentence.
 */
internal object ReplyLanguage {
    const val MIN_USER_UNITS = 3
    const val MIN_ANSWER_UNITS = 20
    const val MISMATCH_SHARE = 0.6
    const val NAME_UNITS = 6
    const val KANA_SHARE = 0.1

    /** Language family; [display] is the statement written to the model. */
    data class Family(val display: String)

    val CHINESE = Family("Chinese")
    val JAPANESE = Family("Japanese")
    val KOREAN = Family("Korean")

    /** Answer the language family of [answer] and the user's language family of [user]. */
    data class Mismatch(val answer: Family, val user: Family)

    /** When the text of the answer is obviously not in the language of the user's sentence, the language families on both sides are returned; otherwise (or there are too few words to judge), it is null. */
    fun mismatch(user: String, answer: String): Mismatch? {
        val u = units(user, skipNames = false)
        val a = units(stripQuoted(answer), skipNames = true)
        val uTotal = u.values.sum()
        val aTotal = a.values.sum()
        if (uTotal < MIN_USER_UNITS || aTotal < MIN_ANSWER_UNITS) return null
        val userFamily = u.maxBy { it.value }.key
        val (answerFamily, count) = a.maxBy { it.value }
        if (count < aTotal * MISMATCH_SHARE || answerFamily == userFamily) return null
        return Mismatch(answerFamily, userFamily)
    }

    /** The number of units of each language family in the text [text] (see class description); when [skipNames], it does not count lines or cells with only a string of short words. */
    fun units(text: String, skipNames: Boolean): Map<Family, Int> {
        val cells = text.split(CELL).map { cell -> runsOf(cell).filter { it.units > 0 } }
        val counted = (if (skipNames) cells.filterNot { it.size == 1 && it[0].units <= NAME_UNITS } else cells).flatten()
        val han = counted.filter { it.key == HAN }.sumOf { it.units }
        val kana = counted.filter { it.key == KANA }.sumOf { it.units }
        val cjk = if (kana > 0 && kana >= (han + kana) * KANA_SHARE) JAPANESE else CHINESE
        val out = LinkedHashMap<Family, Int>()
        for (r in counted) {
            val family = when (r.key) {
                HAN, KANA -> cjk
                UnicodeScript.HANGUL.name -> KOREAN
                else -> Family(r.key.lowercase().replaceFirstChar(Char::uppercaseChar) + " script")
            }
            out[family] = (out[family] ?: 0) + r.units
        }
        return out
    }

    /** Remove the content, URL, and tags in the quotes, brackets, and backtick marks in the answer (the brackets can be enclosed, and both inside and outside are removed). */
    fun stripQuoted(text: String): String {
        var s = FENCE.replace(text, " ")
        for (r in listOf(INLINE_CODE, URL, TAG, QUOTES)) s = r.replace(s, " ")
        while (true) {
            val next = PARENS.replace(s, " ")
            if (next == s) return s
            s = next
        }
    }

    /** A series of connected characters of the same character: [key] is the character (kanji and kana are separated before merging), [units] is the unit number. */
    private data class Run(val key: String, var units: Int)

    /**
     * Divide a grid into strings: letters of the same text are counted as the same string if they are
     * separated only by spaces, hyphens, apostrophes, and combining symbols (or letters that do not
     * belong to any type of text). Each word of Kanji and Kana has one unit; each word of other
     * characters has one unit. Latin and Cyrillic words without lowercase letters are not counted.
     */
    private fun runsOf(cell: String): List<Run> {
        val out = mutableListOf<Run>()
        var current: Run? = null
        val word = StringBuilder()
        fun endWord() {
            val r = current
            if (word.isNotEmpty() && r != null && countsAsWord(word)) r.units++
            word.clear()
        }
        var i = 0
        while (i < cell.length) {
            val cp = cell.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) continue
            // Characters with different characters (long note "-", etc.) before and after falling,
            // self-counting
            if (Character.isLetter(cp) && UnicodeScript.of(cp).let { it == UnicodeScript.COMMON || it == UnicodeScript.INHERITED }) continue
            if (Character.isLetter(cp)) {
                val key = keyOf(cp)
                if (current?.key != key) { endWord(); current = Run(key, 0).also { out += it } }
                if (key == HAN || key == KANA) current!!.units++ else word.appendCodePoint(cp)
            } else if (cp == ' '.code || cp == '-'.code || cp == '\''.code || cp == '’'.code) {
                endWord()
            } else {
                endWord()
                current = null
            }
        }
        endWord()
        return out
    }

    private fun countsAsWord(word: CharSequence): Boolean {
        val script = UnicodeScript.of(Character.codePointAt(word, 0))
        if (script != UnicodeScript.LATIN && script != UnicodeScript.CYRILLIC) return true
        return word.codePoints().anyMatch(Character::isLowerCase)
    }

    private fun keyOf(cp: Int): String = when (val s = UnicodeScript.of(cp)) {
        UnicodeScript.HAN -> HAN
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> KANA
        else -> s.name
    }

    private const val HAN = "HAN"
    private const val KANA = "KANA"

    /** Rows, table cells, and parallel items separated by commas each have one cell. */
    private val CELL = Regex("[\\n|、]")
    private val FENCE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`[^`\\n]*`")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val TAG = Regex("<[^<>\\n]*>")
    private val QUOTES = Regex("「[^」\\n]*」|『[^』\\n]*』|“[^”\\n]*”|\"[^\"\\n]*\"|《[^》\\n]*》")
    /** Only match the innermost pair of brackets, and remove them repeatedly until there are no more; half-width and full-width brackets can be mixed. */
    private val PARENS = Regex("[(（][^()（）\\n]*[)）]")
}
