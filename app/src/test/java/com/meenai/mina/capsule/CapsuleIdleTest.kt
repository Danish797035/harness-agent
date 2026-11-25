package com.meenai.mina.capsule

import com.meenai.mina.chat.CurrentChat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the idle icon is clicked, which conversation will be followed by the card: the one where the user
 * is in the App ([CurrentChat]), if there is no record, the most recent one will be followed (the bottom
 * line read by the caller), and a newly opened conversation that has not yet been spoken is a new
 * conversation card. Pure logic, no disk reading.
 */
class CapsuleIdleTest {
    private val recent = CardView.Chat("c-old", title = "订票")

    @Test fun noRecordFallsBackToTheMostRecentConversation() {
        assertEquals(recent, CapsuleIdle.chat(null, recent))
        // Not a single dialogue: new dialogue card
        assertEquals(CardView.Chat(null), CapsuleIdle.chat(null, null))
    }

    @Test fun aBrandNewConversationIsAFreshCard() {
        val card = CapsuleIdle.chat(CurrentChat.Fresh, recent)
        assertEquals(CardView.Chat(null, fresh = true), card)
        // No number: a new one is opened by the caller when speaking in the card
        assertEquals(null, card.target)
    }

    @Test fun theConversationOnScreenWins() {
        val asked = mutableListOf<String>()
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { id -> asked += id; "查快递" }
        assertEquals(CardView.Chat("c-new", title = "查快递", current = true), card)
        // Just check its title: the title of the one at the bottom is not useful
        assertEquals(listOf("c-new"), asked)
        // Remember that it is "the one the user is currently in", and the card will use this to prevent
        // old tasks from being overloaded.
        assertEquals(true, card.current)
    }

    /** Conversations without titles (already numbered but not yet named) continue as usual, but without a title on the top of the card. */
    @Test fun aConversationWithoutATitleIsStillContinued() {
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { null }
        assertEquals(CardView.Chat("c-new", title = null, current = true), card)
        assertEquals("c-new", card.target)
    }
}
