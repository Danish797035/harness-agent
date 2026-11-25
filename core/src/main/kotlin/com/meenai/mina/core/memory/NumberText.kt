package com.meenai.mina.core.memory

import java.text.Normalizer

/**
 * The unified writing method of numbers is shared with memory verification and skill desensitization:
 * full-width to half-width, three or more consecutive Chinese numerals are converted into Arabic
 * numerals word by word (remove tens, hundreds and thousands: use the same conversion on both sides, and
 * it will be consistent when comparing), and spaces, hyphens, dots, and commas between numbers are
 * removed ("138 0013 8000", "1,234", "One Three Eight Zero One Three Eight Thousand" are all turned into
 * a string of numbers).
 */
internal object NumberText {
    private val CN_DIGIT = mapOf('零' to '0', '〇' to '0', '一' to '1', '二' to '2', '三' to '3', '四' to '4',
        '五' to '5', '六' to '6', '七' to '7', '八' to '8', '九' to '9')
    val CN_RUN = Regex("[零〇一二三四五六七八九十百千]{3,}")
    private val SEPARATED = Regex("\\d(?:[\\s\\-.,]*\\d)+")
    private const val MIN_SEPARATED = 3

    fun collapse(s: String): String {
        val t = CN_RUN.replace(Normalizer.normalize(s, Normalizer.Form.NFKC)) { m ->
            m.value.mapNotNull { CN_DIGIT[it] }.joinToString("").ifEmpty { m.value }
        }
        return SEPARATED.replace(t) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length >= MIN_SEPARATED) digits else m.value
        }
    }
}
