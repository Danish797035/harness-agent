package com.meenai.mina.core.agent

import com.meenai.mina.core.prompt.Feedback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「再发一次」这类回答不是答案:用户的话已经在对话里。只认明确的重复请求,不误伤「稍后再试」。 */
class RepeatRequestTest {
    @Test fun asksToSendTheSameRequestAgain() {
        assertTrue(RepeatRequest.matches("I wasn't able to run the search yet — send it again and I'll search right away."))
        assertTrue(RepeatRequest.matches("Please send your request again."))
        assertTrue(RepeatRequest.matches("Please repeat the request and I will do it."))
        assertTrue(RepeatRequest.matches("Resend that message."))
        assertTrue(RepeatRequest.matches("Phir se bhejo, main abhi search karta hoon."))
        assertTrue(RepeatRequest.matches("Dobara likh do."))
        assertTrue(RepeatRequest.matches("फिर से भेजो।"))
    }

    @Test fun claimsOfAnActionLimitAreNotAnAnswer() {
        assertTrue(RepeatRequest.matches("However, I reached my action limit before I could complete the search for \"Motu Patlu\"."))
        assertTrue(RepeatRequest.matches("You can now type Motu Patlu directly into the YouTube search bar."))
        assertTrue(RepeatRequest.matches("I wasn't able to complete the search."))
        assertTrue(RepeatRequest.matches("Aap khud search kar lijiye."))
        assertTrue(RepeatRequest.matches("Action limit aa gayi."))
    }

    @Test fun ordinaryAnswersDoNotMatch() {
        assertFalse(RepeatRequest.matches("YouTube is open on the web (youtube.com)."))
        assertFalse(RepeatRequest.matches("The page failed to load; please try again later."))
        assertFalse(RepeatRequest.matches("I searched and found the song."))
        assertFalse(RepeatRequest.matches(""))
    }

    @Test fun theNudgeTextExists() {
        assertTrue(Feedback.has("repeat_request_nudge"))
    }
}
