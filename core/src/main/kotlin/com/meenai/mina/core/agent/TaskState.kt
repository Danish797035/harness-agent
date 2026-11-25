package com.meenai.mina.core.agent

import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.verify.AnswerSource

/** The progress of completing several steps at once without going through the model, and task cards and notifications will indicate what is being done accordingly. */
sealed interface RunProgress {
    /** Follow the memorized route, currently doing step [step]/[total]. */
    data class Replay(val step: Int, val total: Int) : RunProgress
    /** Execute the [steps] steps (do_steps) given by the model at a time. */
    data class Batch(val steps: Int) : RunProgress
    /**
     * Another task is using [app] (name on the interface). Stop this step and wait for it to be released
     * (the model will be asked when opening the app). It's just waiting for other tasks and doesn't
     * require the user to do anything: it's still considered work on the interface, not "requiring you
     * to handle it".
     */
    data class WaitingForApp(val app: String) : RunProgress
}

sealed interface TaskState {
    /** It hasn't started running yet. */
    data object Idle : TaskState
    /**
     * [note] is the most recent sentence in the model. If there is no sentence, it is the name of the
     * most recently called tool. [tool] is the tool to be called in this step; if it is empty, it means
     * that this step is still waiting for the model to reply (at this time, [note] is still the previous
     * step). When [progress] is non-empty, this step is performed in several consecutive steps (playback
     * route, batch execution) without going through the model. [reason] is the intention written by the
     * model for this call (the reason in the call parameter); if not written, it is null. [narration] is
     * the model written with this call (progress description, not an answer); use it first in the "What
     * are you doing" on the interface, and then use [reason]. [preview] is the visible text that the
     * current model request has flowed out. It has not yet been confirmed whether it is the final reply
     * or tool progress.
     */
    data class Running(
        val step: Int, val note: String, val tool: String? = null, val progress: RunProgress? = null, val reason: String? = null,
        val narration: String? = null,
        val preview: String? = null,
        /** The model usage up to this moment (number of calls, token, and context remaining) is displayed in real time on the interface. */
        val usage: com.meenai.mina.core.usage.RunUsage? = null,
    ) : TaskState
    /** The preconditions for a capability are not met at this moment, so stop here and wait (see [GateReason]); Running resumes after the conditions are met or the user clicks Continue. */
    data class Held(val reason: GateReason) : TaskState
    /** When paying, [item]/[amount]/[address] are listed separately on the takeover card; return the ending with [AgentLoop.resumeAfterTakeover]. */
    data class WaitingTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : TaskState
    data class WaitingInput(val prompt: String, val options: List<String>? = null) : TaskState
    /**
     * Wait for the user to agree to an operation that costs money (you can only agree this time); answer
     * with [AgentLoop.answerConfirmation], wait for answer, cancel or timeout (press Disagree after
     * timeout).
     */
    data class WaitingConfirmation(val request: ConfirmationRequest) : TaskState
    /**
     * The response of the model without tool call is [answer](show it to the user as it is). There are three results of
     * verification: if [reviewed] is false, nothing has been verified (there is no verifiable value in
     * the chat or answer, and the model has not been verified). At this time, [verified] is also false,
     * and the interface is not marked; if it has been verified and everything matches, [verified] is
     * true; if it has been verified but verified failed to be verified, [verified] is false, and [note]
     * is the reason. [unconfirmed] lists the items that still did not match after one retry (the answer
     * is still returned, with those parts marked unconfirmed). [sources] are the reads the code found in
     * the evidence ledger that support the answer (the model does not write the basis). To learn things
     * (experience routes, personal skills, App knowledge) and see the judged endings, do not check here,
     * see [LearnGate].
     */
    data class Succeeded(
        val answer: String, val verified: Boolean = true, val note: String? = null,
        val sources: List<AnswerSource> = emptyList(), val unconfirmed: List<String> = emptyList(),
        val reviewed: Boolean = true,
    ) : TaskState {
        /** Checked but failed to verify (marked on the interface and recorded as unverified in the review). */
        val doubtful: Boolean get() = reviewed && !verified
    }
    /** [kind] is given by the code that generated the failure itself, not guessed from the text of [reason]. */
    data class Failed(val reason: String, val kind: FailKind) : TaskState
    data object Cancelled : TaskState
}

/**
 * Why stop and wait: It depends on the ability to give this precondition (the core does not know what it
 * is specifically). [code] is the name of the state event in the running record, [why] is the
 * description for the user (can be empty), when [resumable] is true, the user can click "Continue",
 * otherwise it will automatically continue after the conditions are met. When [needsYou] is true, the
 * user is required to move their hands on the phone before continuing (the interface counts as
 * "requiring you to handle it", not just waiting for conditions).
 */
data class GateReason(val code: String, val why: String = "", val resumable: Boolean = false, val needsYou: Boolean = false) {
    /** It has not started yet, but is in the start queue (the parallelism is full, and there are already tasks in the same conversation): it will start automatically when its turn comes, and clicking "Continue" will not allow it to jump in the queue. */
    val queued: Boolean get() = code == QUEUED

    companion object {
        const val QUEUED = "queued"

        /** Queuing to start; [why] is the sentence for the user (for example, there are several tasks ahead). */
        fun queued(why: String) = GateReason(QUEUED, why)
    }
}

/** The source of task failure. */
enum class FailKind {
    /** Agent calls end_not_done, which honestly cannot be done. */
    GaveUp,
    /** An error occurs when the model is called, an exception is returned, or the operation is stopped but no result is given. */
    Model,
    /** Operation protection is triggered and safety stops. */
    Guard,
    StepLimit,
    /** The operating environment is not satisfactory or there are errors (mobile phone operation assistant, accessibility, no model, virtual screen disconnection, etc.). */
    Environment,
}
