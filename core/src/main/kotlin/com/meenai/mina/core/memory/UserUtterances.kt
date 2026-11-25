package com.meenai.mina.core.memory

import java.text.Normalizer

/**
 * What the user himself said in this task: his own words in the opening, interjection, and follow-up
 * answers (the accompanying screen, the page description of the browser page, the quoted Mina’s answer,
 * and the attachment line are not counted, the quoted words of himself are counted), and the remarks
 * when taking over and returning. The memorized `quote` must be a substring of one of the sentences (all
 * half-widths are unified, and the leading and trailing blanks are removed before comparison) - words on
 * the screen, web pages, and tool results are not counted.
 */
class UserUtterances {
    private val texts = mutableListOf<String>()

    @Synchronized fun add(text: String) {
        if (text.isNotBlank()) texts += text
    }

    val all: List<String> @Synchronized get() = texts.toList()

    /** It will be cleared every time the task starts: the words said in the previous task are not counted this time. */
    @Synchronized fun clear() = texts.clear()

    /**
     * The specific values in [content] cannot be found in [quote] or what the user said this time: the
     * values copied from the screen when the model was paraphrasing. Specific value = content within
     * quotation marks, numbers (Chinese numbers, separated numbers are first unified into a string of
     * Arabic numerals), English letters/numbers of more than 3 letters, address fragments (place name
     * signs together with the first two words, such as "Zhangjiang Road" and "Yipinyuan"; only take two
     * words, the model will add an extra district name to avoid mistaken rejection). In the first two
     * terms of comparison, remove "user(s)" and "I(s)" to avoid misrejection caused by the third-person
     * narration itself.
     *
     * Limitations: Chinese names without quotation marks and place names without logo characters cannot
     * be recognized ("Send to Wang Xiaoming"); this type of recognition depends on the user's review of
     * the prompt words and result cards.
     */
    @Synchronized fun unsupportedSpecifics(content: String, quote: String): List<String> {
        val c = comparable(content)
        val said = (texts + quote).map(::comparable)
        val specifics = QUOTED.findAll(c).map { it.groupValues[1] } + DIGITS.findAll(c).map { it.value } +
            LATIN.findAll(c).map { it.value } + PLACE.findAll(c).map { it.value }
        return specifics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .filter { v -> said.none { it.contains(v, ignoreCase = true) } }.toList()
    }

    private fun comparable(s: String) = PERSON.replace(NumberText.collapse(s), "")

    /** Quotations of less than 2 words do not count: one or two words can be found in any sentence. */
    @Synchronized fun contains(quote: String): Boolean {
        val q = norm(quote)
        if (q.length < MIN_QUOTE) return false
        return texts.any { norm(it).contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()

    private companion object {
        const val MIN_QUOTE = 2
        val QUOTED = Regex("[「『“\"]([^「」『』“”\"\n]+)[」』”\"]")
        val DIGITS = Regex("\\d+")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
        val PLACE = Regex("\\p{IsHan}{2}(?:小区|大厦|广场|公寓|花园|单元|路|街|巷|弄|镇|村|号|楼|栋|室|区|县|市|省|园|苑|城|道|里|湾)")
        val PERSON = Regex("用户的|用户|我的|我")
    }
}

