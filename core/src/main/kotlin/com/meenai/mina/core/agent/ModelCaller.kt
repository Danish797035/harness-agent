package com.meenai.mina.core.agent

import com.meenai.mina.core.trace.RunTrace
import com.meenai.mina.core.action.ConfirmDecision
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One model call: The history is only appended, and it is compressed only once when the trigger point is
 * estimated ([ContextCompressor], the complete history does not move). If the main model fails
 * temporarily, press [retry] to retry. If the context is too long, press [router] to resend. When
 * lightSteps is opened, press [router] to divide the work between large and small models (light model
 * does not retry if there is an error, this step directly changes to the main model). Each mission
 * starts with [reset].
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
    private val onActiveModel: (String) -> Unit = {},
) {
    /** Each model has a copy of the compression state: its own context, its own history, and the compression of the lightweight model does not involve the request prefix of the main model. */
    private val compressors = java.util.IdentityHashMap<ChatModel, ContextCompressor>()
    private fun compressorFor(m: ChatModel) =
        compressors.getOrPut(m) { ContextCompressor(tools.index, structured = flags.structuredCompaction,
            diffScreens = tools.inlineScreen, triggerOverride = m.capabilities.compactionTriggerTokens) }
    private val compactions = java.util.IdentityHashMap<ChatModel, CompactionState>()
    /** The time that has elapsed for retry in this task (facing [RetryPolicy.taskWaitBudgetMs]). */
    private var taskRetryWaitMs = 0L

    /** The system prompt word for this task: Press the same tool list sent to the model when starting work ([SystemPrompt.assemble]), and the task remains unchanged. */
    var systemPrompt: String = ""

    /** The time it took for the most recent call to succeed (failed requests, backoff). */
    var lastRetryWaitMs = 0L
        private set

    /** In this task, the lightweight model gives a reply that has been entered into the history; when changing models, click it to remove the private data of other models. */
    private val lightReplies: MutableSet<Message> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** The reply with actual usage (by message identity) and the model giving it, is the anchor point for the size of the request ([UsageMeter]); cleared after compression rewrite prefix. */
    private val anchors = java.util.IdentityHashMap<Message, Pair<ChatModel, UsageMeter.Anchor>>()
    /** The fixed cost estimate of the latest request for each model; when the reply is entered into the history, it will be recorded in the anchor point together with the usage. */
    private val lastOverhead = java.util.IdentityHashMap<ChatModel, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        taskRetryWaitMs = 0
        compressors.values.forEach { it.forget() }
        lightReplies.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** The reply is entered into the history: if it is given by the lightweight model, it will be recorded; if it has real usage, it will be recorded as the anchor point of the context size. */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        if (router?.isLight(used) == true) lightReplies += m
        val overhead = lastOverhead[used]
        if (usage != null && overhead != null) anchors[m] = used to UsageMeter.Anchor(usage, overhead)
    }

    /** How big is the entire request sent to [using]: The most recent reply it sent with the actual usage is used as the anchor point. If not, the full amount is estimated (see [UsageMeter]). */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int =
        UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first === using }?.second }

    /** Is the division of labor working in this task: the switch is turned on, the lightweight model can accept mobile phone steps, and it is not deactivated due to unrecoverable errors. */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /** Once the division of labor is enabled, record the model and role of each call in the running record (also record it after deactivation, for easy comparison). */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /**
     * Press [router] to select the model. If the lightweight model makes an error, the tool is not
     * adjusted (the reply without calling is the final answer, and is only given by the main model), or
     * if it wants to give up, confirm, take over, question, or do irreversible things, its reply will
     * not be executed (note the usage as [ROLE_LIGHT_REJECTED]). In this step, the main model is changed
     * to the query. Before changing the question, use [restarted] to report the new question time.
     * Returns the response and the actual model used.
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val r = router?.takeIf { routing } ?: return ask(history, specs, step, onText = onText, onThinking = onThinking) to model
        val picked = r.pick(track.context(step, history.any { m -> m.parts.any { it is Part.Image } }))
        if (!r.isLight(picked)) return ask(history, specs, step, onText = onText, onThinking = onThinking) to model
        // The context of a lightweight model may be smaller: it is reported to be too long, and how much
        // it is folded at that time does not affect the main model (the compression status is recorded
        // separately by model)
        val asked = now()
        val light = try {
            complete(viewFor(history, picked), specs, picked, RetryPolicy.NONE, step, onText, onThinking)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The key, quota, model name, and request itself are incorrect, and it is useless to try
            // again: the main model will be used for the remaining steps of this task.
            if (e is ModelError.Auth || e is ModelError.QuotaExceeded || e is ModelError.ModelNotFound ||
                e is ModelError.ModelNotAvailable || e is ModelError.ToolsUnsupported ||
                e is ModelError.ChatGptLimitReached || e is ModelError.ChatGptNotEligible || e is ModelError.SignInExpired || e is ModelError.AccountPlanNotAllowed ||
                (e is ModelError.Server && e.status in 400..499)) r.disable()
            null
        }
        val first = light?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        if (first != null && !needsMain(first)) return light to picked
        r.noteTrouble()
        val text = light?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), first?.name, first?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            light?.usage?.inputTokens, light?.usage?.outputTokens, light?.usage?.cachedInputTokens, now() - asked,
            picked.name, ROLE_LIGHT_REJECTED,
        ))
        emit(RunEvent.State(now(), "light_to_main", first?.name ?: if (light == null) "light_error" else "no_tool"))
        onText?.invoke("")
        onThinking?.invoke("")
        restarted(now())
        return ask(history, specs, step, onText = onText, onThinking = onThinking) to model
    }

    private fun needsMain(call: Part.ToolCall): Boolean =
        ModelRouter.mainOnly(call.name, tools.verb(call.name)) ||
            (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, parseArgs(call.argsJson)))

    /** Question [using] (default master model): What is sent is a compressed copy of the history ([viewFor]) shown to it. */
    suspend fun ask(
        history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model,
        onText: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
    ): ModelReply =
        complete(viewFor(history, using), specs, using, retry, step, onText, onThinking)

    /**
     * History sent to [to]: Replies given by other models remove the thinking content and private data
     * on the call (these can only be returned to the model that generated it as is). Only check whether
     * the field is present, regardless of service provider.
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        val toLight = router?.isLight(to) == true
        if (!toLight && lightReplies.isEmpty()) return history
        return history.map { m ->
            val foreign = m.role == Role.Assistant && (m in lightReplies) != toLight
            if (!foreign || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
            else m.copy(parts = m.parts.mapNotNull { p ->
                when {
                    p is Part.Thinking -> null
                    p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                    else -> p
                }
            })
        }
    }

    private suspend fun complete(history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null): ModelReply {
        var retries = 0
        lastRetryWaitMs = 0
        val began = now()
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val compressor = compressorFor(using)
        var compaction = compactions[using] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            val raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            val restored = ContextRecovery.ensure(history, raw.messages)
            val sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            // The interface displays in real time how much context has been used and how much is left
            // (service providers that do not report usage also have estimates)
            currentCoroutineContext()[com.meenai.mina.core.usage.RunUsageTally]?.noteContext(sent.estimate + overhead, contextTokens)
            val (next, report) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            if (report) emit(RunEvent.Compressed(now(), sent.foldedSteps))
            compaction = next
            compactions[using] = compaction
            try {
                // When the different base is cut or folded, the next write operation must be issued in
                // full
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(sent.messages))
                // Everything spent before this time (failed requests, backoff) is counted as retry
                // waiting.
                lastRetryWaitMs = now() - began
                onActiveModel(using.name)
                suspend fun send(): ModelReply = if (onText == null && onThinking == null) using.complete(systemPrompt, sent.messages, specs)
                    else if (using is StreamingChatModel) using.completeStreamingWithThinking(
                        systemPrompt, sent.messages, specs, onText ?: {}, onThinking ?: {},
                    )
                    else using.complete(systemPrompt, sent.messages, specs).also { result ->
                        onThinking?.let { cb ->
                            result.parts.filterIsInstance<Part.Thinking>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(cb)
                        }
                        onText?.let { cb ->
                            result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(cb)
                        }
                    }
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + raw.foldedSteps
                        raw.rewritten && current != sent.messages -> "history_rewritten folded=" + raw.foldedSteps
                        else -> null
                    }
                    overflowRewrote = false
                    trace.modelCall(step, using, tools::verb, change, sent.messages) { send() }
                }
                return reply.also { r ->
                    lastOverhead[using] = overhead
                    r.usage?.let { compressor.calibrate(sent.estimate + overhead, it.inputTokens) }
                }
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                onThinking?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[using] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                onThinking?.invoke("")
                val wait = policy.delayFor(e, retries, taskRetryWaitMs) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // The task of canceling while waiting is over.
                delay(wait)
            }
        }
    }

    companion object {
        /** The value of [RunEvent.ModelReply.role]; it is null when division of labor does not work. */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** The reply of the lightweight model was not executed, and this step changed the main model; it is still a model call, and the usage is taken into account. */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
    }
}

/** Reads (by number) are also fully visible in the emitted history: read results with element lists and action results with screen objects. */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf { it["elements"] is JsonArray } ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}
