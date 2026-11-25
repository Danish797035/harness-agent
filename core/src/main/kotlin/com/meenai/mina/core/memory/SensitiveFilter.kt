package com.meenai.mina.core.memory

import com.meenai.mina.core.record.Redactor
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Sensitive information level of memory and skill draft: hit will not be saved. Mobile phone numbers,
 * addresses, birthdays, and family names are not included here—they are common facts for getting things
 * done.
 */
object SensitiveFilter {
    private const val KEYWORDS =
        "支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
            "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc|verification code|security code|security question|one-time code)(?![A-Za-z])"
    // These two words are too short and are common in ordinary English (pinned, hotpot), and they only
    // count when followed by numbers.
    private const val DIGIT_KEYWORDS = "(?<![A-Za-z])(?:pin|otp)(?![A-Za-z])"
    private const val GAP = "[\\s:：=,，是为叫]{0,4}(?:(?:is|was|are)\\s+)?"
    private const val OWNER = "\\s*(?:就是|是|为)(?:我的|我|用户的)?\\s*"

    // Only short connections ("is", ":", "is") are allowed between keywords and more than 4
    // digits/letter strings to avoid misinterpretation of numbers in a Mandarin sentence
    private val KEYWORD_RULES = listOf(
        Regex("(?:$KEYWORDS)$GAP[A-Za-z0-9]{4,}", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{4,}$OWNER(?:$KEYWORDS)", RegexOption.IGNORE_CASE),
        Regex("$DIGIT_KEYWORDS$GAP[0-9]{4,}", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])[0-9]{4,}$OWNER$DIGIT_KEYWORDS", RegexOption.IGNORE_CASE),
    )
    private val ID_NUMBER = Regex("(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])")
    // A single space or hyphen is allowed between numbers (card numbers are always written in groups)
    private val CARD_LIKE = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")
    private val SPACES = Regex("\\s+")
    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECK = "10X98765432"

    /** The hit category name (`api_key`|`id_number`|`card_number`|`password`); the miss is null. */
    fun hit(text: String): String? {
        // Full-width numbers, letters, and compatible characters should be unified first; keyword rules
        // should be checked again with the blank text removed ("Verification Code 1 2 3 4")
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val compact = SPACES.replace(t, "")
        return when {
            Redactor.text(t) != t -> "api_key"
            ID_NUMBER.findAll(t).any { validId(it.value) } -> "id_number"
            CARD_LIKE.findAll(t).any { luhn(it.value.filter(Char::isDigit)) } -> "card_number"
            KEYWORD_RULES.any { it.containsMatchIn(t) || it.containsMatchIn(compact) } -> "password"
            else -> null
        }
    }

    /** The date of birth segment is the real date, and the last digit conforms to ISO 7064 MOD 11-2. */
    private fun validId(id: String): Boolean {
        val birth = try {
            LocalDate.of(id.substring(6, 10).toInt(), id.substring(10, 12).toInt(), id.substring(12, 14).toInt())
        } catch (e: DateTimeException) {
            return false
        }
        if (birth.year < 1900) return false
        val sum = (0 until 17).sumOf { (id[it] - '0') * ID_WEIGHTS[it] }
        return ID_CHECK[sum % 11] == id[17].uppercaseChar()
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}
