package com.meenai.mina.core.usage

import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import kotlinx.coroutines.currentCoroutineContext

/** The purpose for which a call is issued. The storage is recorded as [key]; the unrecognized key (written in a later version) is read as [Other]. */
enum class UsagePurpose(val key: String) {
    Main("main"), Vision("vision"), Light("light"), Other("other");

    companion object {
        fun of(role: ModelRole): UsagePurpose = when (role) {
            ModelRole.Main -> Main
            ModelRole.Vision -> Vision
            ModelRole.Light -> Light
        }

        fun of(key: String): UsagePurpose = entries.firstOrNull { it.key == key } ?: Other
    }
}

/** Whose model is it and for what purpose is it built? Bring it as is when recording the usage. [providerName] is the name of the service provider when building the model (the service provider will be retained even if it is deleted). */
data class UsageSource(val providerId: String, val providerName: String, val model: String, val purpose: UsagePurpose)

/** The result of a call: [usage] is null, which means that the service provider did not report the usage (or the call failed). This time, only the number of times is counted, not the token. */
fun interface UsageSink {
    fun record(source: UsageSource, usage: Usage?)
}

/**
 * Add metering to the model: every time [ChatModel.complete] (and the one that goes by paragraph) ends,
 * a sum will be recorded. If it is successful and has a usage, a token will be recorded. If the usage is
 * not reported or an error is thrown (including cancellation), only the number of times will be
 * recorded. Transparent to the caller: the name and capabilities remain the same, exceptions are thrown
 * as they are, and callbacks are made as usual. The original model supports text by paragraph, and the
 * set one also supports it. Errors in the accounting itself do not affect the call.
 */
fun ChatModel.metered(source: UsageSource, sink: UsageSink): ChatModel =
    if (this is StreamingChatModel) MeteredStreamingModel(this, source, sink) else MeteredModel(this, source, sink)

private open class MeteredModel(private val inner: ChatModel, protected val source: UsageSource, protected val sink: UsageSink) : ChatModel {
    override val capabilities get() = inner.capabilities
    override val name: String get() = inner.name

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        counted { inner.complete(system, messages, tools) }

    protected suspend fun counted(call: suspend () -> ModelReply): ModelReply {
        // Called in a run, it is also recorded in the name of this run (see RunUsageTally)
        val tally = currentCoroutineContext()[RunUsageTally]
        val reply = try {
            call()
        } catch (e: Throwable) {
            note(null, tally)
            throw e
        }
        note(reply.usage, tally)
        return reply
    }

    private fun note(usage: Usage?, tally: RunUsageTally?) {
        runCatching { sink.record(source, usage) }
        tally?.add(usage)
    }
}

private class MeteredStreamingModel(private val inner: StreamingChatModel, source: UsageSource, sink: UsageSink) :
    MeteredModel(inner, source, sink), StreamingChatModel {
    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = counted { inner.completeStreaming(system, messages, tools, onText) }

    override suspend fun completeStreamingWithThinking(
        system: String, messages: List<Message>, tools: List<ToolSpec>,
        onText: (String) -> Unit, onThinking: (String) -> Unit,
    ): ModelReply = counted { inner.completeStreamingWithThinking(system, messages, tools, onText, onThinking) }
}
