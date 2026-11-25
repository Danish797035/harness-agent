package com.meenai.mina.task

import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.cap.phone.OperatingPace

/**
 * The hook used to evaluate the running machine. Only set in tests; all default values are used in
 * production code, and the behavior remains unchanged. One copy in the process (see [TaskEnvironment]),
 * shared by several tasks running at the same time (the automatic answer only looks at the question
 * itself, and it is the same for any task).
 */
class EvalHooks {
    /** Automatic answer to ask_user; return null or blank to indicate no answer, and the task will wait until the evaluation times out. */
    @Volatile var autoAnswer: ((prompt: String, options: List<String>?) -> String?)? = null

    /** What should the evaluation runner do when encountering a takeover: cancel (cancel the task, recorded as takeover) or wait (wait for someone to handle it). Read by the running machine. */
    @Volatile var onTakeover: String = "cancel"

    /** The upper limit of steps for a single question; null uses the default value of Agent. */
    @Volatile var maxSteps: Int? = null

    /**
     * Confirm the automatic answer of the level; wait for the user when it is not empty, and use its
     * results directly (it is always false in the evaluation: it will never actually send, delete, or
     * place an order). Level events are recorded as usual, and the content of the cards is left for
     * review and verification.
     */
    @Volatile var autoDecideConfirmation: ((ConfirmationRequest) -> Boolean)? = null

    /** The skill drafts you create are accepted directly (training wheels for evaluation). */
    @Volatile var autoApproveSkillDrafts: Boolean = false

    /** Override the "Learn skills from completed tasks" switch; null Use settings. Evaluation is off by default (EvalRunTest is set to false), and only the training wheels are on. */
    @Volatile var learnSkills: Boolean? = null

    /** Override the "Automatically remember what I said" switch; null Use the one in settings. The assessment is off by default, and only the memory question set is turned on. */
    @Volatile var rememberMemory: Boolean? = null

    /** Override the operation rhythm in the settings; null uses the one in the settings. By default, "fast" is used for evaluation and real machine acceptance, and it can be changed to another one according to the parameters. */
    @Volatile var operatingPace: OperatingPace? = null

    fun reset() {
        operatingPace = null
        autoApproveSkillDrafts = false
        learnSkills = null
        rememberMemory = null
        autoAnswer = null
        onTakeover = "cancel"
        maxSteps = null
        autoDecideConfirmation = null
    }

    internal fun answerFor(state: TaskState): String? {
        val s = state as? TaskState.WaitingInput ?: return null
        return autoAnswer?.invoke(s.prompt, s.options)?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun decisionFor(state: TaskState): Boolean? {
        val s = state as? TaskState.WaitingConfirmation ?: return null
        return autoDecideConfirmation?.invoke(s.request)
    }
}
