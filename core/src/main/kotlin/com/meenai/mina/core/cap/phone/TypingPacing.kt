package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.Pace
import com.meenai.mina.core.uistate.TextField
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The rhythm of typing into the input box (pure logic, without touching the input box): People type one
 * word at a time. The Chinese, Japanese and Korean characters are displayed on the screen one word at a
 * time through the input method. The speed varies between words, and there is an occasional pause after
 * punctuation and spaces. [plan] Arranges a paragraph of words into a series of increasingly longer
 * prefixes and how long to wait before each paragraph; the last paragraph is always the full text.
 *
 * - - - Latin letters, numbers, punctuation, etc. Each character is randomly selected from
 * [Limits.latinMs]; each Chinese, Japanese and Korean character is [Limits.cjkMs],
 * Several consecutive ones ([Limits.cjkGroup]) appear on the screen together;
 * - - - There is a chance after each paragraph ([Limits.pauseChance], doubled after punctuation and
 * spaces) to stop more [Limits.pauseMs];
 * - - - When there are many words, several words can be combined into one paragraph, up to
 * [Limits.maxSteps] paragraphs; when the total duration exceeds [Limits.maxTotalMs], each paragraph will
 * be shortened proportionally, and long messages will not be typed for several minutes.
 * - - - Do not split a single word: surrogate pairs, combined additional symbols, variant selectors, and
 * zero-width concatenated expression sequences are counted as one with the previous words.
 *
 * [Instant] writes the entire text at once without waiting (use it for "fast", test, and callers who
 * don't need rhythm).
 */
class TypingPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** The duration unit is milliseconds. */
    data class Limits(
        val latinMs: LongRange = 60L..170L,
        val cjkMs: LongRange = 160L..380L,
        val cjkGroup: IntRange = 1..3,
        val pauseChance: Double = 0.05,
        val pauseMs: LongRange = 300L..900L,
        val maxSteps: Int = 40,
        val maxTotalMs: Long = 6_000L,
    ) {
        companion object {
            /** Apps that limit browsing speed (and all apps when "like people"): type more slowly, stop more, and keep long messages up to 12 seconds. */
            val CAREFUL = Limits(
                latinMs = 90L..230L, cjkMs = 220L..520L, pauseChance = 0.1, pauseMs = 500L..1_400L, maxSteps = 60, maxTotalMs = 12_000L,
            )
        }
    }

    /** One paragraph: After waiting for [delayMs], the input box contains the first [end] UTF-16 units of the full text. */
    data class Step(val end: Int, val delayMs: Long)

    /** Rhythmic (not [Instant]). */
    val paced: Boolean get() = random != null

    /** Arrange [text] into paragraphs; [Instant] and empty string only have one paragraph (full text, varying). */
    fun plan(text: String): List<Step> {
        val r = random ?: return listOf(Step(text.length, 0))
        val units = units(text)
        if (units.size <= 1) return listOf(Step(text.length, Pace.pick(r, limits.latinMs)))
        val per = ceil(units.size.toDouble() / limits.maxSteps).toInt().coerceAtLeast(1)
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < units.size) {
            // Chinese, Japanese and Korean characters are displayed on the screen one word at a time
            // according to the input method (several consecutive words, not spanning to other words);
            // when there are many words, at least one word per paragraph
            val group = if (isCjk(text, units[i])) {
                val want = Pace.pick(r, limits.cjkGroup)
                var k = 1
                while (k < want && i + k < units.size && isCjk(text, units[i + k])) k++
                k
            } else 1
            val upto = minOf(i + maxOf(group, per), units.size)
            var wait = 0L
            for (j in i until upto) wait += Pace.pick(r, if (isCjk(text, units[j])) limits.cjkMs else limits.latinMs)
            val end = if (upto == units.size) text.length else units[upto]
            val last = text.codePointBefore(end)
            val boundary = Character.isWhitespace(last) || isPunctuation(last)
            if (r.nextDouble() < limits.pauseChance * (if (boundary) 2 else 1)) wait += Pace.pick(r, limits.pauseMs)
            steps += Step(end, wait)
            i = upto
        }
        val total = steps.sumOf { it.delayMs }
        if (total <= limits.maxTotalMs) return steps
        val scale = limits.maxTotalMs.toDouble() / total
        return steps.map { it.copy(delayMs = (it.delayMs * scale).roundToLong()) }
    }

    /**
     * The rhythm used for this input box: the password box ([secret]) is not segmented (the original
     * text cannot be read, and half the words cannot be checked); the web page box ([web]) that requires
     * pasting to receive the input is not segmented (the App cannot receive the words written in). These
     * two types are still written once ([Instant]), and the rest follow this rhythm.
     */
    fun forField(web: Boolean, secret: Boolean): TypingPacing = if (web || secret) Instant else this

    /**
     * Press [plan] to write the prefix of [text] into [field] section by section, leaving the full
     * section to the caller (write the entire section and read it back for verification as usual), and
     * wait until the last section is paused before returning. Read back and check after writing the
     * first paragraph: the box does not accept half a word (the readback does not match or cannot be
     * read out) or stops immediately when writing cannot be entered and returns false. The caller still
     * writes the full text at once. Returns true when all prefixes are written; returns false when
     * nothing is written when not segmented ([Instant], only one word).
     */
    suspend fun leadIn(field: TextField, text: String, sleep: suspend (Long) -> Unit = { delay(it) }): Boolean {
        val steps = plan(text)
        if (steps.size < 2) return false
        for ((k, s) in steps.dropLast(1).withIndex()) {
            sleep(s.delayMs)
            val prefix = text.substring(0, s.end)
            if (!field.write(prefix)) return false
            if (k == 0) {
                val back = field.read() ?: return false
                if (back.filterNot { it.isWhitespace() } != prefix.filterNot { it.isWhitespace() }) return false
            }
        }
        sleep(steps.last().delayMs)
        return true
    }

    companion object {
        /** Write the entire text at once. */
        val Instant = TypingPacing(null)

        /** Like the rhythm of human typing; [random] can be replaced with a fixed seed (test). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = TypingPacing(random, limits)

        /** The starting point (UTF-16 subscript) of each word in [text]: the sequence of surrogate pairs, additional symbols, variant selectors, zero-width concatenation and the previous word count as one. */
        internal fun units(text: String): List<Int> {
            val out = mutableListOf<Int>()
            var i = 0
            var joinNext = false
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val attach = out.isNotEmpty() && (joinNext || attaches(cp))
                if (!attach) out += i
                joinNext = cp == ZWJ
                i += Character.charCount(cp)
            }
            return out
        }

        private const val ZWJ = 0x200D

        private fun attaches(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> cp == ZWJ || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF
        }

        private fun isCjk(text: String, at: Int): Boolean = when (Character.UnicodeScript.of(text.codePointAt(at))) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }

        private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
