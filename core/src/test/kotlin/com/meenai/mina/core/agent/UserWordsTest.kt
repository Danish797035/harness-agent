package com.meenai.mina.core.agent

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.cap.web.FakeWebPort
import com.meenai.mina.core.tool.FakeScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's own words in a sentence: the typed words this time ([UserWords.typed]) and the quotation of memory and skills ([UserWords.said]), according to the spelling on the conversation page and browser page. */
class UserWordsTest {
    private val tools = toolDispatcher(FakeScreen(), web = FakeWebPort())
    private val mina = QuoteMessage.Quote(QuoteMessage.From.Mina, "你常用的收货地址是幸福路 8 号")
    private val own = QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子")
    private val pdf = listOf("/workspace/inbox/a.pdf" to "2 KB")

    private fun words(text: String) = UserWords.typed(text, tools) to UserWords.said(text, tools)

    // Quote Mina's answer: The quoted paragraph does not count; the attachment line does not count
    // either, it is empty when only attachments are included and no typing is done.
    @Test fun aQuotedReplyIsNotTheUserWords() {
        assertEquals("照这个改" to "照这个改", words(AttachmentLines.compose(QuoteMessage.compose(mina, "照这个改"), pdf)))
        assertEquals("" to "", words(AttachmentLines.compose(QuoteMessage.compose(mina, ""), pdf)))
    }

    // I quoted something I said: I only typed it this time. I read the memorized quotation together with
    // the quoted paragraph and put them in front.
    @Test fun anOwnQuoteIsSaidButNotTyped() {
        assertEquals("这个记一下" to "我家猫叫团子\n这个记一下", words(AttachmentLines.compose(QuoteMessage.compose(own, "这个记一下"), pdf)))
        assertEquals("" to "我家猫叫团子", words(AttachmentLines.compose(QuoteMessage.compose(own, ""), pdf)))
    }

    // Question on the browser page: The page description should be removed as usual
    @Test fun thePageDescriptionIsNotTheUserWords() {
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        assertEquals("这一页的地址对不对" to "这一页的地址对不对", words(AttachedPage.compose(page, "这一页的地址对不对")))
    }

    // If there are no citations or attachments, the words will remain unchanged; Mandarin words starting
    // with ">" and words starting with ">" after the quotation will not be regarded as quotations.
    @Test fun plainWordsStayAsTheyAre() {
        assertEquals("查天气" to "查天气", words("查天气"))
        assertEquals("> 这句是我自己写的\n后面" to "> 这句是我自己写的\n后面", words("> 这句是我自己写的\n后面"))
        assertEquals("> 这句什么意思\n解释一下" to "> 这句什么意思\n解释一下", words(QuoteMessage.compose(mina, "> 这句什么意思\n解释一下")))
    }
}
