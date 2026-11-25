package com.meenai.mina.core.agent

import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink

/**
 * The running records are written in order of cause and effect: the observations ([observed]) read in a
 * call and recorded in the evidence ledger are stored first, and are written after the tool results of
 * this call; reads without tool results (a screen read when returning, a glance after the batch is
 * completed) are written before the next other event. There is nothing between the model response and
 * the tool results.
 */
internal class RecordOrder(private val sink: RunSink) : RunSink {
    private val pending = mutableListOf<RunEvent.Observed>()

    fun observed(e: RunEvent.Observed) {
        synchronized(pending) { pending += e }
    }

    override fun event(e: RunEvent) {
        if (e !is RunEvent.ModelReply && e !is RunEvent.ToolResult) flush()
        sink.event(e)
        if (e is RunEvent.ToolResult) flush()
    }

    override fun media(mediaId: Int, png: ByteArray) = sink.media(mediaId, png)

    private fun flush() {
        val out = synchronized(pending) { pending.toList().also { pending.clear() } }
        out.forEach(sink::event)
    }
}
