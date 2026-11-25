package com.meenai.mina.core.run

/** Questions are sent from AgentLoop and delivered by the current channel; answers are still only returned from RunInbox. */
interface Interaction {
    suspend fun publish(ask: Delivery.Ask): DeliveryState
    fun resolved(askId: String)
}

/** Today's interface and notifications are displayed by the App status subscription and are considered visible when published. */
object LocalInteraction : Interaction {
    override suspend fun publish(ask: Delivery.Ask) = DeliveryState.Delivered
    override fun resolved(askId: String) = Unit
}

class ChannelInteraction(
    private val outbox: Outbox,
    private val channel: Channel,
    private val peer: ChannelPeer,
) : Interaction {
    override suspend fun publish(ask: Delivery.Ask): DeliveryState = outbox.deliver(channel, peer, ask).state
    override fun resolved(askId: String) = Unit
}
