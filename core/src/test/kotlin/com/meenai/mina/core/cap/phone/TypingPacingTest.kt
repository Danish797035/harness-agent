package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.TextField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The rhythm of typing: the prefixes are getting longer and longer, and finally the full text; words are not split; Chinese, Japanese and Korean characters are displayed on the screen one word at a time; long articles are segmented and there is an upper limit on the total length; if the segmented writing fails, the whole paragraph will be returned to writing. */
class TypingPacingTest {
    private val limits = TypingPacing.Limits()

    private fun checkShape(text: String, steps: List<TypingPacing.Step>) {
        assertEquals(text.length, steps.last().end)
        steps.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", a.end < b.end) }
        // The segment boundary does not fall in the middle of a word
        val units = TypingPacing.units(text).toSet() + text.length
        steps.forEach { assertTrue("${it.end} in $text", it.end in units) }
        steps.forEach { assertTrue(it.delayMs >= 0) }
    }

    @Test fun instantWritesTheWholeTextAtOnce() {
        assertEquals(listOf(TypingPacing.Step(5, 0)), TypingPacing.Instant.plan("hello"))
        assertFalse(TypingPacing.Instant.paced)
    }

    @Test fun latinTextGoesOutOneCharacterAtATimeWithBoundedDelays() {
        repeat(30) { seed ->
            val text = "hello world"
            val steps = TypingPacing.human(Random(seed)).plan(text)
            checkShape(text, steps)
            assertEquals(text.length, steps.size)
            val most = limits.latinMs.last + limits.pauseMs.last
            steps.forEach { assertTrue("${it.delayMs}", it.delayMs in limits.latinMs.first..most) }
        }
    }

    @Test fun cjkTextGoesOutAWordAtATime() {
        repeat(30) { seed ->
            val text = "今天天气很好，我们去公园散步吧"
            val steps = TypingPacing.human(Random(seed)).plan(text)
            checkShape(text, steps)
            // One paragraph of Chinese characters consists of 1-3 characters, with punctuation forming a
            // paragraph of its own
            var start = 0
            for (s in steps) {
                val chunk = text.substring(start, s.end)
                if (chunk == "，") assertTrue(s.delayMs >= limits.latinMs.first)
                else {
                    assertTrue(chunk, chunk.length in limits.cjkGroup)
                    assertTrue("${s.delayMs}", s.delayMs >= chunk.length * limits.cjkMs.first)
                }
                start = s.end
            }
        }
    }

    @Test fun charactersAreNeverSplit() {
        // a. Smiling face (agent pair), b. Like plus skin color, c. Family (zero-width connection), e
        // plus additional accent, medium
        val text = "a😀b👍🏽c👨‍👩‍👧é中"
        assertEquals(listOf(0, 1, 3, 4, 8, 9, 17, 19), TypingPacing.units(text))
        repeat(20) { seed -> checkShape(text, TypingPacing.human(Random(seed)).plan(text)) }
    }

    @Test fun longTextIsChunkedAndCappedInTotal() {
        val latin = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        val cjk = "我们明天上午十点在公司门口见面，记得带上资料。".repeat(20)
        for (text in listOf(latin, cjk)) {
            for (l in listOf(TypingPacing.Limits(), TypingPacing.Limits.CAREFUL)) {
                val steps = TypingPacing.human(Random(7), l).plan(text)
                checkShape(text, steps)
                assertTrue("${steps.size}", steps.size <= l.maxSteps)
                // When scaling down, round to the nearest 1 millisecond.
                assertTrue("${steps.sumOf { it.delayMs }}", steps.sumOf { it.delayMs } <= l.maxTotalMs + steps.size)
            }
        }
        // Short enough not to shrink
        val short = TypingPacing.human(Random(3)).plan("ok")
        assertTrue(short.sumOf { it.delayMs } >= 2 * limits.latinMs.first)
    }

    @Test fun carefulIsSlower() {
        val text = "我想问一下这个还在吗"
        val normal = (0 until 20).sumOf { TypingPacing.human(Random(it)).plan(text).sumOf { s -> s.delayMs } }
        val careful = (0 until 20).sumOf { TypingPacing.human(Random(it), TypingPacing.Limits.CAREFUL).plan(text).sumOf { s -> s.delayMs } }
        assertTrue("$normal < $careful", normal < careful)
    }

    /** Note the input box that is written; when [accepts] is false, it will be like a box that does not accept half-words (it will always be empty when read back). */
    private class Field(private val accepts: Boolean = true, private val writable: Boolean = true) : TextField {
        val writes = mutableListOf<String>()
        private var text = ""
        override fun read(): String? = if (accepts) text else ""
        override fun write(text: String): Boolean {
            if (!writable) return false
            writes += text
            this.text = text
            return true
        }
    }

    @Test fun leadInWritesGrowingPrefixesAndLeavesTheWholeTextToTheCaller() = runTest {
        val text = "你好，world"
        // The segments emitted from the same seed are the same as when written.
        val plan = TypingPacing.human(Random(1)).plan(text)
        val pacing = TypingPacing.human(Random(1))
        val field = Field()
        val slept = mutableListOf<Long>()
        assertTrue(pacing.leadIn(field, text) { slept += it })
        assertEquals(plan.dropLast(1).map { text.substring(0, it.end) }, field.writes)
        assertEquals(plan.map { it.delayMs }, slept)
    }

    @Test fun leadInStopsWhenTheFieldRejectsPartialText() = runTest {
        val rejecting = Field(accepts = false)
        assertFalse(TypingPacing.human(Random(1)).leadIn(rejecting, "hello") {})
        assertEquals(listOf("h"), rejecting.writes)
        val locked = Field(writable = false)
        assertFalse(TypingPacing.human(Random(1)).leadIn(locked, "hello") {})
        // When not segmented, nothing is written. The caller writes the entire segment as usual.
        val instant = Field()
        assertFalse(TypingPacing.Instant.leadIn(instant, "hello") { error("no wait") })
        assertTrue(instant.writes.isEmpty())
    }

    @Test fun passwordAndPasteOnlyWebFieldsAreWrittenAtOnce() {
        val human = TypingPacing.human(Random(1))
        assertSame(human, human.forField(web = false, secret = false))
        assertSame(TypingPacing.Instant, human.forField(web = false, secret = true))
        assertSame(TypingPacing.Instant, human.forField(web = true, secret = false))
    }
}
