package com.meenai.mina.capsule

import com.meenai.mina.chat.CurrentChat

/**
 * Which conversation card will follow when the idle icon is clicked (pure logic): the one where the user
 * is in the App ([current], see [CurrentChat]); if there is no record, then the most recent one read by
 * the caller ([recent], only when the process has just started and the old version has no records); if
 * there is no record of either, it will be a new conversation card.
 *
 * [title] gives the title of the conversation [CurrentChat.Open.id] (given by the user or the first
 * sentence), and returns null if it has not yet been released.
 */
object CapsuleIdle {
    fun chat(current: CurrentChat?, recent: CardView.Chat?, title: (String) -> String? = { null }): CardView.Chat = when (current) {
        null -> recent ?: CardView.Chat(null)
        CurrentChat.Fresh -> CardView.Chat(null, fresh = true)
        // Remember it is "the one the user is in at the moment": do not grab the card when other
        // conversations have ended and are still pending (see [CapsuleCards.of])
        is CurrentChat.Open -> CardView.Chat(current.id, title = title(current.id), current = true)
    }
}
