package com.meenai.mina.task

import com.meenai.mina.DeviceTaskState
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.verify.Verifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Script model for real machine testing: complete is reported first (how many times, the history of receipt), and then waits for the test to feed the next tool call. */
class ScriptedModel : ChatModel {
    class Call(val number: Int, val messages: List<Message>)

    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val replies = Channel<List<Part>>(Channel.UNLIMITED)
    private val n = AtomicInteger()
    /** The number of pre-completion verification requests received. */
    val verifierRequests = AtomicInteger()
    /** The number of requests for writing instructions after stopping; the response is [stopSummary] (default is blank, instructions are spelled directly according to the record), which does not account for the script and does not count. */
    val summaryRequests = AtomicInteger()
    @Volatile var stopSummary: String = ""
    /** Fixed answer for verification; default passed. */
    @Volatile var verdict: String = PASS
    /**
     * When a task initiated from the interface proposes the first tool call, it will ask another model
     * "whether the user is discussing, reading, or operating"; this is the model that is asked when
     * there is no lightweight model. If it is set, this question will be answered like this (ACT / READ
     * / DISCUSS), which does not occupy the script and will not be counted; when it is null, this
     * question needs to be tested like other calls.
     */
    @Volatile var intent: String? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        // Verification before submitting the answer: Fixed answer, no script account and no counting
        if (system == Verifier.PROMPT) {
            verifierRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(verdict)), StopReason.EndTurn, null)
        }
        if (system == SUMMARY_PROMPT) {
            summaryRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(stopSummary)), StopReason.EndTurn, null)
        }
        // Give the new conversation a title: fixed answer, does not occupy the script and does not count
        if (system == TITLE_PROMPT) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
        intent?.let { if (system == INTENT_PROMPT) return ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }
        calls.send(Call(n.incrementAndGet(), messages))
        val parts = replies.receive()
        return ModelReply(parts, if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, null)
    }

    /** [say] is what the model said incidentally at this step (displayed on the interface below the step). */
    fun feed(name: String, args: String = "{}", say: String? = null) =
        replies.trySend(listOfNotNull(say?.let { Part.Text(it) }, Part.ToolCall("c${n.get()}", name, args)))

    /** Reply without call: The final answer is handed over to the user, and the task ends (it will be returned once if the check is not correct). */
    fun answer(text: String) = replies.trySend(listOf(Part.Text(text)))

    companion object {
        private val INTENT_PROMPT by lazy { Prompts.role("intent_gate") }
        private val SUMMARY_PROMPT by lazy { Prompts.role("stop_summary") }
        private val TITLE_PROMPT by lazy { Prompts.role("conversation_title") }
        const val PASS = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "met", "quote": null}], "verdict": "pass", "reason": "脚本"}"""
        const val CANNOT_VERIFY = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "unknown", "quote": null}], "verdict": "cannot_verify", "reason": "脚本:看不到结果"}"""
    }

    fun awaitCall(): Call = runBlocking {
        withTimeoutOrNull(30_000) { calls.receive() } ?: error("model not called; task state ${DeviceTaskState.state.value}")
    }
}
