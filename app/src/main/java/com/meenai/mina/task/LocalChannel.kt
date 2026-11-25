package com.meenai.mina.task

import com.meenai.mina.core.run.Channel
import com.meenai.mina.core.run.ChannelInput
import com.meenai.mina.core.run.ChannelInputSink
import com.meenai.mina.core.run.ChannelPeer
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.run.SendOutcome

/** Task cards and notifications on mobile phones: The task service still draws the existing interface, and this adapter takes over the delivery ledger. */
class LocalChannel(private val deliverLocal: suspend (ChannelPeer, Delivery) -> SendOutcome) : Channel {
    override val id = ID
    override val confirms = true
    private var sink: ChannelInputSink? = null

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome = deliverLocal(peer, delivery)
    suspend fun receive(input: ChannelInput) { sink?.receive(input) }

    companion object { const val ID = "local" }
}
