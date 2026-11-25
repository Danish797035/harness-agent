package com.meenai.mina.core.agent

import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.core.action.Refusals
import com.meenai.mina.core.gate.ConfirmationGate
import com.meenai.mina.core.gate.DeclareResult
import com.meenai.mina.core.general.RequestConfirmation
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.HandBack
import com.meenai.mina.core.tool.HandBackOutcome
import com.meenai.mina.core.tool.Outcome
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.run.DeliveryState
import com.meenai.mina.core.run.Interaction
import com.meenai.mina.core.run.LocalInteraction
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.RunProvenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Waiting for users: answer questions, hand over for acceptance, answer confirmation. During the waiting
 * period, the waiting status is displayed to the outside world, and after waiting, it is replaced with
 * [running] (given by the caller); the user's decision (or timeout, skipping) makes the tool result of
 * this call. The interface passes in the user's answers via a [answerInput] method.
 */
internal class UserExchange(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    /** The time limit for waiting for the user to confirm the response, then press disagree; if it is null, keep waiting. */
    private val confirmTimeoutMs: Long?,
    private val inputTimeoutMs: Long,
    /** What the user said this time (used for citation verification of memory_save); null when not collected. */
    private val utterances: () -> UserUtterances?,
    private val setState: (TaskState) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    private var threadId = "local"
    private var provenance = RunProvenance()
    private var interaction: Interaction = LocalInteraction
    @Volatile var activeAsk: Delivery.Ask? = null
        private set
    @Volatile private var takeoverReply: CompletableDeferred<HandBack>? = null
    @Volatile private var inputReply: CompletableDeferred<String>? = null
    @Volatile private var confirmationReply: CompletableDeferred<ConfirmChoice>? = null
    /** Change the answer to the one given to the model (change to instructions when using the user screen, see [UserScreenTurns.asText]); the default is as is. */
    var answerText: suspend (String) -> String = { it }

    fun begin(thread: String, run: RunProvenance, channel: Interaction = LocalInteraction) {
        threadId = thread
        provenance = run
        interaction = channel
        activeAsk = null
    }

    private fun ask(kind: Delivery.Ask.Kind, text: String): Delivery.Ask =
        Delivery.Ask(UUID.randomUUID().toString(), threadId, UUID.randomUUID().toString(), kind, text)

    private suspend fun announce(ask: Delivery.Ask): DeliveryState {
        activeAsk = ask
        return interaction.publish(ask)
    }

    private fun resolve(ask: Delivery.Ask) {
        if (activeAsk?.askId == ask.askId) activeAsk = null
        interaction.resolved(ask.askId)
    }

    private fun matches(id: String?): Boolean = id == null || activeAsk?.askId == id

    fun resumeAfterTakeover(result: HandBack, askId: String? = null): Boolean =
        if (matches(askId)) takeoverReply?.complete(result) ?: false else false

    /** Returns false when there are no pending questions (timed out, answered). */
    fun answerInput(text: String, askId: String? = null): Boolean =
        if (matches(askId)) inputReply?.complete(text) ?: false else false

    fun answerConfirmation(choice: ConfirmChoice, askId: String? = null): Boolean =
        if (matches(askId)) confirmationReply?.complete(choice) ?: false else false

    /** Mission ended: No more waiting for any answers. */
    fun clear() {
        takeoverReply = null
        inputReply = null
        confirmationReply = null
        activeAsk = null
    }

    /** Wait for the user to answer the follow-up question, up to [inputTimeoutMs]; return null after timeout. The user's answer may be a verification code or something, so don't remember it. */
    suspend fun awaitAnswer(prompt: String, options: List<String>?): String? {
        if (provenance.attendance == Attendance.Unattended) return null
        val pending = CompletableDeferred<String>().also { inputReply = it }
        val ask = ask(Delivery.Ask.Kind.Input, prompt)
        activeAsk = ask
        setState(TaskState.WaitingInput(prompt, options))
        emit(RunEvent.State(now(), "waiting_input", detail(prompt)))
        return try {
            if (announce(ask) == DeliveryState.Failed) null
            else withTimeoutOrNull(inputTimeoutMs) { pending.await() }
        } finally {
            inputReply = null
            resolve(ask)
        }.also { if (it == null) emit(RunEvent.State(now(), "no_answer", detail(prompt))) else utterances()?.add(UserWords.said(com.meenai.mina.core.userscreen.UserScreenMessage.parse(it).words, tools)) }?.let { answerText(it) }
    }

    suspend fun input(o: Outcome.Input, running: TaskState): ToolExecution {
        val answer = awaitAnswer(o.prompt, o.options)
        // The facts given by the user in the task are also the basis for verification.
        answer?.let { tools.noteUserAnswer(o.prompt, it) }
        setState(running)
        return ToolExecution(buildJsonObject {
            if (answer != null) { put("ok", true); put("answer", answer) }
            else { put("ok", false); put("error", "no_answer"); put("message", INPUT_TIMEOUT_MESSAGE); put("recoverable", true); put("counts_as_failure", false) }
        })
    }

    /** Wait for the user to return it; return null when the task is canceled (aborted). If it is not returned, it will not be said that the transaction has been completed: skipping and timeout are not considered failures, but the model is told truthfully. */
    suspend fun takeover(o: Outcome.Takeover, running: TaskState): ToolExecution? {
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) return null
        val pending = CompletableDeferred<HandBack>().also { takeoverReply = it }
        val ask = ask(Delivery.Ask.Kind.Takeover, o.message)
        activeAsk = ask
        setState(TaskState.WaitingTakeover(o.reason, o.message, o.item, o.amount, o.address))
        emit(RunEvent.State(now(), "waiting_takeover", detail(o.message)))
        val since = now()
        val back = try {
            if (announce(ask) == DeliveryState.Failed) return null
            if (provenance.attendance == Attendance.Reachable) withTimeoutOrNull(inputTimeoutMs) { pending.await() }
            else pending.await()
        } finally { takeoverReply = null; resolve(ask) } ?: return null
        back.note?.let { utterances()?.add(it) }
        tools.noteTakeover(o.reason, back)
        val outcome = if (back.userFinished && back.outcome == HandBackOutcome.complete) "user_finished" else back.outcome.name
        emit(RunEvent.Takeover(now(), o.reason, outcome, back.durationMs ?: (now() - since), back.inputCount))
        if (back.outcome == HandBackOutcome.aborted) return null
        setState(running)
        return ToolExecution(buildJsonObject {
            put("outcome", back.outcome.name)
            if (back.outcome == HandBackOutcome.complete) {
                put("ok", true)
                put("durationMs", back.durationMs ?: (now() - since))
                back.inputCount?.let { put("inputCount", it) }
                back.stoppedAt?.let { put("stoppedAt", it) }
                put("note", HANDBACK_NOTE)
                if (back.userFinished) { put("userFinished", true); put("instruction", USER_FINISHED_PROMPT) }
                back.note?.takeIf { it.isNotBlank() }?.let { put("userNote", it) }
            } else {
                put("ok", false)
                put("reason", if (back.outcome == HandBackOutcome.skipped) "takeover_skipped" else "takeover_timeout")
                put("counts_as_failure", false)
                put("note", NO_HANDBACK_NOTE)
            }
        })
    }

    /** If the application is consistent with the front desk, just wait for the user's answer (press "Disagree" when the time limit is reached to cancel); the tool result is the user's decision. */
    suspend fun confirm(o: Outcome.Confirm, step: Int, running: TaskState): ToolExecution {
        val g = confirmations ?: return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "unknown_tool"); put("tool", RequestConfirmation.name)
            put("message", Feedback["confirmation_unavailable"]); put("recoverable", true)
        })
        val r = o.request
        fun event(decision: String?) = RunEvent.Confirmation(now(), r.action.name, detail(r.target), detail(r.content), r.amount?.let(::detail), decision)
        fun error(code: String, message: String, recoverable: Boolean) = ToolExecution(buildJsonObject {
            put("ok", false); put("error", code); put("message", message)
            put("recoverable", recoverable); put("counts_as_failure", false)
        })
        when (g.declare(r, o.foregroundApp)) {
            DeclareResult.Mismatch -> {
                emit(event("mismatch"))
                return ToolExecution(buildJsonObject {
                    put("ok", false); put("error", "confirmation_mismatch")
                    put("message", Feedback.format("confirm_app_mismatch", "app" to r.app, "foreground" to (o.foregroundApp ?: "unknown")))
                    put("recoverable", true)
                })
            }
            DeclareResult.Declined -> {
                emit(event("declined_before"))
                return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
            }
            DeclareResult.Unsupported -> return error("payment_requires_takeover", Refusals.PAYMENT_TAKEOVER, recoverable = true)
            DeclareResult.Pending -> Unit
        }
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_unattended"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        // Payment is only agreed on the mobile phone: channels, timings, and tasks initiated by MCP are
        // not required.
        if (provenance.origin != RunOrigin.Ui) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_remote"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        val pending = CompletableDeferred<ConfirmChoice>().also { confirmationReply = it }
        val ask = ask(Delivery.Ask.Kind.Confirm, "${r.action.name}: ${r.target} ${r.content}")
        activeAsk = ask
        setState(TaskState.WaitingConfirmation(r))
        emit(event(null))
        // If no one answers (not next to the phone), just click Disagree. It will never automatically
        // agree.
        val timeout = if (provenance.attendance == Attendance.Reachable) confirmTimeoutMs ?: 15 * 60_000L else confirmTimeoutMs
        val answer = try {
            if (announce(ask) == DeliveryState.Failed) null
            else if (timeout == null) pending.await() else withTimeoutOrNull(timeout) { pending.await() }
        } finally { confirmationReply = null; resolve(ask) }
        val choice = answer ?: ConfirmChoice.Deny
        g.decide(choice, step)
        setState(running)
        emit(event(when {
            answer == null -> "timed_out"
            choice.approved -> "approved"
            else -> "declined"
        }))
        return if (choice.approved) ToolExecution(buildJsonObject { put("ok", true); put("approved", true); put("message", CONFIRMATION_APPROVED) })
        else error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    companion object {
        val INPUT_TIMEOUT_MESSAGE: String get() = Feedback["no_answer_message"]
        val HANDBACK_NOTE: String get() = Feedback["handback_note"]
        val NO_HANDBACK_NOTE: String get() = Feedback["no_handback_note"]
        val USER_FINISHED_PROMPT: String get() = Feedback["user_finished_prompt"]
        val CONFIRMATION_DECLINED: String get() = Feedback["confirmation_declined"]
        val CONFIRMATION_APPROVED: String get() = Feedback["confirmation_approved"]
    }
}
