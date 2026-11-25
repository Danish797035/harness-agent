package com.meenai.mina.ui

import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Quotations on the conversation page: Keep separate when included with attachments, without citations in the title; responses are copied as plain text. See core's QuoteMessageTest for how it is written into words. */
class QuoteMessageTest {
    @get:Rule val texts = ChineseTexts()

    private val mina = QuoteMessage.Quote(QuoteMessage.From.Mina, "明天上午有小雨\n下午转晴")

    // Quote and add attachments: Attachments are added after the words, and they go to their own places
    // when they are separated.
    @Test fun quotesAndAttachmentsTogether() {
        val goal = ChatViewModel.withAttachments(QuoteMessage.compose(mina, "照这个改"), listOf("/workspace/inbox/a.pdf" to 2048L))
        val q = QuoteMessage.parse(goal)
        assertEquals(mina, q.quote)
        val a = AttachmentMessage.parse(q.words)
        assertEquals("照这个改", a.words)
        assertEquals(listOf("a.pdf"), a.files.map { it.name })
        // Only quotes and attachments, no words
        val only = QuoteMessage.parse(ChatViewModel.withAttachments(QuoteMessage.compose(mina, ""), listOf("/workspace/inbox/a.pdf" to 2048L)))
        assertEquals(mina, only.quote)
        assertEquals("a.pdf", AttachmentMessage.plain(only.words))
    }

    // Do not refer to this sentence in titles and notifications.
    @Test fun titlesSkipTheQuote() {
        val goal = QuoteMessage.compose(mina, "那要带伞吗")
        assertEquals("那要带伞吗", ChatCopy.plainTitle(goal))
        assertEquals("那要带伞吗", ScreenCopy.plain(goal))
    }

    @Test fun answersCopyAsPlainText() {
        assertEquals(
            "上海今天晴,详见 天气\n\n要点\n\n• 早上 12 度\n• 下午 20 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\nval x = 1\n\n城市\t天气\n上海\t晴",
            MarkdownModel.toPlainText(
                "上海今天**晴**,详见 [天气](https://example.com)\n\n## 要点\n\n- 早上 12 度\n- 下午 *20* 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\n" +
                    "```kotlin\nval x = 1\n```\n\n---\n\n| 城市 | 天气 |\n| --- | --- |\n| 上海 | 晴 |",
            ),
        )
        assertEquals("", MarkdownModel.toPlainText(""))
    }
}
