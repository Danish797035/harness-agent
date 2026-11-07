package com.meenai.mina.core.router.preset

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Wraps a [ChatModel] to constrain concurrent requests (e.g., Z.ai concurrency 1).
 */
class ConcurrencyLimitedChatModel(
    val delegate: ChatModel,
    val semaphore: Semaphore = Semaphore(1),
) : StreamingChatModel {

    override val name: String get() = delegate.name
    override val capabilities: Capabilities get() = delegate.capabilities

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
    ): ModelReply = semaphore.withPermit {
        delegate.complete(system, messages, tools)
    }

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply = semaphore.withPermit {
        if (delegate is StreamingChatModel) {
            delegate.completeStreaming(system, messages, tools, onText)
        } else {
            val reply = delegate.complete(system, messages, tools)
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
            reply
        }
    }
}
