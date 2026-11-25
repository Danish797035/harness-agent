package com.meenai.mina.core.uistate

import com.meenai.mina.core.prompt.Feedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnseenFieldTest {
    private fun line(text: String, top: Int, left: Int = 100) = VisibleTextLine(text, listOf(left, top, left + 400, top + 40))

    private val chat = listOf(line("张三", 50), line("明天见", 200))

    @Test fun typedTextNewlyOnScreenMatches() {
        val after = chat + line("好的, 我 7 点到", 850)
        assertEquals(Verification.MATCH, UnseenField.verify("好的,我7点到", chat, after, changed = true))
        // Even if you recognize punctuation and spaces differently, it will count
        assertEquals(Verification.MATCH, UnseenField.verify("好的，我 7 点到。", chat, after, changed = false))
    }

    @Test fun textAlreadyOnScreenMustAppearOnceMore() {
        // There is "See you tomorrow" in the chat history before typing: it only appears once and is not
        // counted as a call.
        assertEquals(Verification.UNKNOWN, UnseenField.verify("明天见", chat, chat + line("其他", 850), changed = true))
        assertEquals(Verification.MATCH, UnseenField.verify("明天见", chat, chat + line("明天见", 850), changed = true))
    }

    @Test fun longTextIsCheckedByItsEnds() {
        val typed = "今天下午三点在公司楼下的咖啡店见面可以吗"
        // OCR recognized a wrong word in the middle and split it into two lines.
        val after = chat + line("今天下午三点在公司楼下", 800) + line("的咖非店见面可以吗", 850)
        assertEquals(Verification.MATCH, UnseenField.verify(typed, chat, after, changed = true))
        assertEquals(Verification.UNKNOWN, UnseenField.verify(typed, chat, chat + line("今天下午三点", 800), changed = true))
    }

    @Test fun nothingChangedIsMismatchOnlyWhenBothReadsAgree() {
        assertEquals(Verification.MISMATCH, UnseenField.verify("你好", chat, chat, changed = false))
        // Don’t guess when you can’t read the word
        assertEquals(Verification.UNKNOWN, UnseenField.verify("你好", null, chat, changed = false))
        assertEquals(Verification.UNKNOWN, UnseenField.verify("你好", chat, null, changed = false))
        assertEquals(Verification.UNKNOWN, UnseenField.verify("你好", chat, chat, changed = true))
        // It’s all about expressions and punctuation: there’s no comparable word
        assertEquals(Verification.UNKNOWN, UnseenField.verify("👍!!", chat, chat + line("👍", 850), changed = true))
    }

    @Test fun keyboardLinesAreIgnored() {
        val ime = listOf(0, 600, 999, 999)
        val keyboard = line("你好", 700)
        assertEquals(chat, UnseenField.outside(chat + keyboard, ime))
        assertEquals(chat + keyboard, UnseenField.outside(chat + keyboard, null))
        // The words in the candidate entries cannot be regarded as the words in the box
        assertEquals(Verification.UNKNOWN, UnseenField.verify("你好", chat, UnseenField.outside(chat + keyboard, ime), changed = true))
    }

    @Test fun pastedWithoutKeyboardIsReportedLikeWithIt() {
        val after = chat + line("好的", 850)
        for (keyboardUp in listOf(false, true)) {
            val r = UnseenField.typed("好的", chat, after, changed = true, keyboardUp = keyboardUp) as ActionResult.Ok
            assertEquals(Verification.MATCH, r.verification)
            assertEquals(Feedback["type.unseen_pasted"], r.hint)
        }
        // It has changed but I can’t find this word: Don’t guess, just hand it over anyway
        val unknown = UnseenField.typed("好的", chat, chat + line("别的", 850), changed = true, keyboardUp = false) as ActionResult.Ok
        assertEquals(Verification.UNKNOWN, unknown.verification)
    }

    @Test fun nothingChangedWithoutKeyboardAsksToTapTheBoxFirst() {
        val r = UnseenField.typed("好的", chat, chat, changed = false, keyboardUp = false) as ActionResult.Failed
        assertEquals("no_focus", r.reason)
        assertEquals(Feedback["type.unseen_not_focused"], r.hint)
        // When playing the input method, the focus is indeed on the box: the report still fails to
        // enter, the input tool says
        val up = UnseenField.typed("好的", chat, chat, changed = false, keyboardUp = true) as ActionResult.Ok
        assertEquals(Verification.MISMATCH, up.verification)
    }

    @Test fun clearingWithoutKeyboardThatChangedNothingAsksToTapFirst() {
        val failed = UnseenField.cleared(ActionResult.Ok(changed = false), keyboardUp = false) as ActionResult.Failed
        assertEquals("no_focus", failed.reason)
        assertEquals(Feedback["clear.unseen_not_focused"], failed.hint)
        for ((changed, keyboardUp) in listOf(true to false, false to true, true to true)) {
            val r = UnseenField.cleared(ActionResult.Ok(changed = changed), keyboardUp) as ActionResult.Ok
            assertEquals(Feedback["clear.unseen_cleared"], r.hint)
        }
        val error = ActionResult.Failed("action_failed")
        assertTrue(UnseenField.cleared(error, keyboardUp = false) === error)
    }

    @Test fun atAPointNothingChangedMeansThePointDidNotTakeTheText() {
        for (keyboardUp in listOf(false, true)) {
            val r = UnseenField.typed("好的", chat, chat, changed = false, keyboardUp = keyboardUp, atPoint = true) as ActionResult.Failed
            assertEquals(UnseenField.POINT_NOT_TYPED, r.reason)
            assertEquals(Feedback["type.point_not_typed"], r.hint)
        }
        val ok = UnseenField.typed("好的", chat, chat + line("好的", 850), changed = true, keyboardUp = false, atPoint = true) as ActionResult.Ok
        assertEquals(Verification.MATCH, ok.verification)
        val cleared = UnseenField.cleared(ActionResult.Ok(changed = false), keyboardUp = true, atPoint = true) as ActionResult.Failed
        assertEquals(UnseenField.POINT_NOT_TYPED, cleared.reason)
        assertEquals(UnseenField.POINT_NOT_TYPED, (UnseenField.tapSwitched("com.example.web") as ActionResult.Failed).reason)
    }
}
