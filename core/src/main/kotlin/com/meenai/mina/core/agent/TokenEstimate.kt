package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import kotlin.math.ceil

/**
 * Rough estimate of the unadjusted word segmenter: non-Chinese characters / 4 + Chinese characters ×
 * 0.75; if the size of the picture is known, press width × height/[PIXELS_PER_TOKEN], if not, press
 * 1000. It is only used to determine whether to compress and truncate by token in the context budget.
 * Chinese, Japanese and Korean punctuation, full-width characters, kana, proverbs and Chinese characters
 * are calculated as 0.75.
 */
object TokenEstimate {
    private const val IMAGE_TOKENS = 1000
    /** The caliber of common service providers (approximately 28×28 pixels per token, each provider has slight differences). */
    const val PIXELS_PER_TOKEN = 750.0

    /** How many tokens is a picture of [width]×[height] approximately? If you don’t know the size, press 1000. */
    fun image(width: Int, height: Int): Int = if (width > 0 && height > 0) ceil(width.toLong() * height / PIXELS_PER_TOKEN).toInt() else IMAGE_TOKENS

    fun of(messages: List<Message>): Int {
        var images = 0
        var text = 0.0
        for (m in messages) for (p in m.parts) when (p) {
            is Part.Image -> images += image(p.width, p.height)
            is Part.Text -> text += chars(p.text)
            is Part.Thinking -> text += chars(p.text)
            is Part.ToolCall -> text += chars(p.name) + chars(p.argsJson)
            is Part.ToolResult -> text += chars(p.content)
        }
        return images + ceil(text).toInt()
    }

    /**
     * Calibrate rough estimate with true usage: Returns the new "true/rough estimate" ratio. When
     * [previous] is null, the current ratio is taken directly, otherwise it is merged with the previous
     * smoothing; the result is limited to [MIN_SCALE]..[MAX_SCALE], and an abnormal consumption will not
     * bias the estimate too much.
     */
    fun calibrated(previous: Double?, estimated: Int, actual: Int): Double {
        if (estimated <= 0 || actual <= 0) return previous ?: 1.0
        val ratio = actual.toDouble() / estimated
        val next = if (previous == null) ratio else previous * (1 - SMOOTHING) + ratio * SMOOTHING
        return next.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 3.0
    private const val SMOOTHING = 0.3

    /** Estimating a piece of text with the same caliber is used for fixed costs that are not in the message (basic prompt words, tool definitions). */
    fun of(text: String): Int = ceil(chars(text)).toInt()

    /** The longest prefix that does not exceed [maxTokens] tokens, followed by [hint] at the end; only used in context budget. */
    fun clip(text: String, maxTokens: Int, hint: String = "…"): String {
        if (maxTokens <= 0 || text.isEmpty()) return ""
        if (of(text) <= maxTokens) return text
        val room = maxTokens - of(hint)
        if (room <= 0) return hint
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (of(text.substring(0, mid)) <= room) lo = mid else hi = mid - 1
        }
        // Not splitting an agent pair in half
        if (lo in 1 until text.length && text[lo - 1].isHighSurrogate() && text[lo].isLowSurrogate()) lo--
        return text.substring(0, lo) + hint
    }

    private val WIDE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )
    private val WIDE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION, Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    )

    private fun wide(cp: Int) = Character.UnicodeScript.of(cp) in WIDE_SCRIPTS || Character.UnicodeBlock.of(cp) in WIDE_BLOCKS

    private fun chars(s: String): Double {
        var han = 0
        var other = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (wide(cp)) han++ else other++
            i += Character.charCount(cp)
        }
        return han * 0.75 + other / 4.0
    }
}
