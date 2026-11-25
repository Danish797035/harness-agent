package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quote the previous message: write it into the words to give the model the appearance, truncate, and tear it back, leaving the old words as they are; the saved rounds are with references. */
class QuoteMessageTest {
    private val mina = QuoteMessage.Quote(QuoteMessage.From.Mina, "明天上午有小雨\n下午转晴")

    @Test fun theModelSeesWhoIsQuotedAndWhat() {
        assertEquals(
            "> [Quoting your earlier reply]\n> 明天上午有小雨\n> 下午转晴\n\n那要带伞吗",
            QuoteMessage.compose(mina, "那要带伞吗"),
        )
        assertEquals(
            "> [Quoting my earlier message]\n> 帮我查明天的天气\n\n换成后天",
            QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "帮我查明天的天气"), "换成后天"),
        )
        // The blank line in the quoted content is written as a single ">", and the whole block is still
        // connected together.
        assertEquals(
            "> [Quoting your earlier reply]\n> 第一段\n>\n> 第二段\n\n继续",
            QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, "第一段\n\n第二段"), "继续"),
        )
    }

    @Test fun noQuoteLeavesTheWordsAlone() {
        assertEquals("查天气", QuoteMessage.compose(null, "查天气"))
        assertEquals("查天气", QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, "  "), "查天气"))
    }

    @Test fun longQuotesAreClipped() {
        val long = "字".repeat(QuoteMessage.MAX_CHARS + 50)
        val clipped = QuoteMessage.clip(long)
        assertEquals(QuoteMessage.MAX_CHARS + 1, clipped.length)
        assertTrue(clipped.endsWith("…"))
        assertEquals("短的", QuoteMessage.clip("  短的 \n"))
        val sent = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, long), "这段太长了")
        assertEquals(clipped, QuoteMessage.parse(sent).quote?.text)
    }

    @Test fun parsesBackWhatWasComposed() {
        val p = QuoteMessage.parse(QuoteMessage.compose(mina, "那要带伞吗"))
        assertEquals(mina, p.quote)
        assertEquals("那要带伞吗", p.words)
        val blank = QuoteMessage.parse(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "a\n\nb"), "c"))
        assertEquals("a\n\nb", blank.quote?.text)
        assertEquals("c", blank.words)
    }

    // The words in the old conversation are not quoted in that line: they are displayed as they are;
    // Mandarin words starting with ">" are not treated as quotes.
    @Test fun oldMessagesHaveNoQuote() {
        assertNull(QuoteMessage.parse("查天气").quote)
        assertEquals("查天气", QuoteMessage.parse("查天气").words)
        assertNull(QuoteMessage.parse("> 这句是我自己写的\n后面").quote)
        assertEquals("> 这句是我自己写的\n后面", QuoteMessage.words("> 这句是我自己写的\n后面"))
    }

    // The saved rounds have the references as they are; you can also see what the references are in the
    // text given to the model.
    @Test fun storedTurnsKeepTheQuote() {
        val goal = QuoteMessage.compose(mina, "那要带伞吗")
        val context = (Conversation.context(listOf(Turn(goal, "要带", "succeeded")))!!.parts.single() as Part.Text).text
        assertTrue(context.contains("[Quoting your earlier reply]"))
        assertTrue(context.contains("明天上午有小雨"))
    }
}
