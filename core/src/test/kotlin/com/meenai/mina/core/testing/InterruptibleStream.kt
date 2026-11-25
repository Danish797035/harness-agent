package com.meenai.mina.core.testing

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/**
 * Set up a layer of streaming output for [inner] to test interrupt interruptions: the first request
 * sends [chunks] (each paragraph is the visible full text up to this point, an empty string means
 * restarting or clearing) in sequence, and then stops until it is removed; this request is not recorded
 * in [inner]. Subsequent requests will be responded to according to [inner]'s script, and the entire
 * text will be sent at once.
 */
class InterruptibleStream(private val inner: FakeChatModel, private val chunks: List<String>) : StreamingChatModel {
    override val name: String get() = inner.name
    override val capabilities: Capabilities get() = inner.capabilities
    /** The first request has been sent. [chunks] has stopped. */
    val entered = CompletableDeferred<Unit>()
    /** The first request was withdrawn. */
    var cancelled = false
        private set
    private var first = true

    /** When asking without streaming (the interface does not display real-time text), the first request also stops, but no words are sent. */
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        if (first) {
            first = false
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        if (first) {
            first = false
            chunks.forEach(onText)
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        return inner.complete(system, messages, tools).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
    }
}
