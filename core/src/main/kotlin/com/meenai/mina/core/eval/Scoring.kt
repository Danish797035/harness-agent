package com.meenai.mina.core.eval

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState

@Suppress("EnumEntryName")
enum class FailureClass {
    perception, grounding, route, barrier, app_state, input, guard_stop, step_limit, model_error,
    false_success, verifier_reject, gate, harness, unknown,
    /** When handing it in, it was marked "failed to verify" and the check items were not passed: the false successes and false successes were counted separately for easy comparison. */
    unverified_wrong,
    /** Another question that was run at the same time in the parallel evaluation snatched the app needed for this question. */
    interference,
}

data class TaskScore(
    val id: String,
    val passed: Boolean,
    val falseSuccess: Boolean,
    val unverified: Boolean,
    /** Checked items that failed: "outcome_is" or "<序号>:<类型>". */
    val failedChecks: List<String>,
    val failureClass: FailureClass?,
    /** The data marks created by writing questions ({run} has been replaced) are listed in the report for manual cleaning. */
    val marker: String? = null,
    /** See [Observed.leftScope]. */
    val leftScope: List<String> = emptyList(),
    /** Type of failure handed over to the user (see [Scoring.handedOver]): Single column, not considered completed. */
    val handedOver: String? = null,
)

object Scoring {
    private val MODEL_ERROR = Regex("API [Kk]ey|额度|限流|模型|quota|rate limit|ChatGPT|permission")

    /** When the evaluation runner itself makes an error, the failure reason begins with this. */
    const val HARNESS_PREFIX = "评测器:"

    /** The reason for failure caused by interference between the two questions in the parallel assessment starts with this. */
    const val INTERFERENCE_PREFIX = "并行干扰:"

    /** The reason for the failure when the Agent calls end_not_done by itself; only this type is recognized for questions that expect failure. */
    const val GAVE_UP = "Agent 如实报告做不成"

    /** The beginning of the failure reason when the check consistently fails (see AgentLoop). */
    const val VERIFICATION_PREFIX = "核查未通过"

    /**
     * The reason for the failure of the submission for scoring: generated according to the source of
     * failure [FailKind]. It does not need to be said by the Agent himself. Otherwise, when the Agent
     * gives up truthfully, the words "model" and "amount" in his words will be misjudged as a model
     * error.
     */
    fun failReason(f: TaskState.Failed): String = when (f.kind) {
        FailKind.GaveUp -> GAVE_UP
        FailKind.Model -> "模型出错:${f.reason}"
        FailKind.Guard, FailKind.StepLimit -> f.reason
        FailKind.Environment -> "${HARNESS_PREFIX}运行环境:${f.reason}"
    }

    /**
     * Score according to the outcome judged in the running record: if the answer is that it was not
     * completed or was only partially completed ([Observed.judged] is partial / not_possible), it will
     * be counted as failed as if the Agent gave up truthfully, and will not be counted as a success. The
     * ending of the case that has not been judged and failed to be judged will be the same as when it is
     * handed over.
     */
    fun recorded(o: Observed): Observed =
        if ((o.outcome == "succeeded" || o.outcome == "unverified") && (o.judged == "partial" || o.judged == "not_possible"))
            o.copy(outcome = "failed", failReason = GAVE_UP)
        else o

    fun score(t: EvalTask, observed: Observed): TaskScore {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        return TaskScore(
            id = t.id,
            passed = failed.isEmpty(),
            falseSuccess = isFalseSuccess(o, failed),
            unverified = o.outcome == "unverified",
            failedChecks = failed,
            failureClass = initialClass(t, o),
            // It is possible to create data only after taking at least one step; questions that have not
            // been started will not be included in the cleaning list
            marker = t.marker?.takeIf { o.steps > 0 },
            leftScope = o.leftScope,
            handedOver = handedOver(t, o),
        )
    }

    /** The initial judgment failure category; successful questions return null. People can change the judgment after looking at the running records. */
    fun initialClass(t: EvalTask, observed: Observed): FailureClass? {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        if (failed.isEmpty()) return null
        if (isFalseSuccess(o, failed)) return FailureClass.false_success
        if (o.outcome == "unverified") return FailureClass.unverified_wrong
        val reason = o.failReason.orEmpty()
        // The original text of unsatisfied standards may contain words such as "model", please check
        // first.
        if (reason.startsWith(VERIFICATION_PREFIX)) return FailureClass.verifier_reject
        infraClass(o)?.let { return it }
        return when {
            "已安全停止" in reason || "stopped safely" in reason -> FailureClass.guard_stop
            "步上限" in reason || "step limit" in reason -> FailureClass.step_limit
            "核查未通过" in reason -> FailureClass.verifier_reject
            o.outcome == "takeover" && t.category != EvalCategory.Barrier -> FailureClass.barrier
            else -> FailureClass.unknown
        }
    }

    /** Failures caused by infrastructure (models, evaluators) take precedence over guard stops, step limits, etc. */
    private fun infraClass(o: Observed): FailureClass? {
        val reason = o.failReason ?: return null
        return when {
            reason.startsWith(HARNESS_PREFIX) -> FailureClass.harness
            reason.startsWith(INTERFERENCE_PREFIX) -> FailureClass.interference
            MODEL_ERROR.containsMatchIn(reason) -> FailureClass.model_error
            else -> null
        }
    }

    private fun isFalseSuccess(o: Observed, failed: List<String>) = o.outcome == "succeeded" && failed.isNotEmpty()

    /** [EvalTask.acceptTakeoverOn] Default when not written: Reading category questions hit the login wall and are given to the user (only users can log in); other categories are not recognized. */
    fun takeoverKinds(t: EvalTask): List<String> =
        t.acceptTakeoverOn ?: if (t.category == EvalCategory.Read && t.expect == Expect.Succeeded) listOf("login") else emptyList()

    /** It declares the precondition that the user has logged in: Hitting the login wall again at this time cannot be explained by "leaving it to the user". */
    private val SIGNED_IN_KINDS = setOf("signedIn", "login", "site")

    /**
     * If you encounter an obstacle recognized by the question, you can see it on the screen
     * ([Observed.takeoverBarrier]) and hand it over to the user: Return to the obstacle type and record
     * a separate category "Leave it to the user". It does not count as a success (whether it counts or
     * not is decided by the person), nor does it count as any other failure. The question will not be
     * recognized when it states that you have logged in as a prerequisite.
     */
    fun handedOver(t: EvalTask, o: Observed): String? = o.takeoverBarrier?.takeIf { kind ->
        t.expect != Expect.Takeover && o.outcome == "takeover" && kind in takeoverKinds(t) &&
            t.requires.none { it.substringBefore(':') in SIGNED_IN_KINDS }
    }

    private fun failedChecks(t: EvalTask, o: Observed): List<String> {
        val expected = when (t.expect) {
            Expect.Succeeded -> "succeeded" // unverified does not count
            Expect.Failed -> "failed"
            Expect.Confirmation -> "confirmation"
            Expect.Takeover -> "takeover"
        }
        val out = mutableListOf<String>()
        // Questions that expect failure will only count if the Agent gives up truthfully; errors in the
        // model or evaluator, hitting the upper limit of steps, or being stopped by a guard will not
        // count.
        val honest = t.expect != Expect.Failed || o.failReason == GAVE_UP
        if (o.outcome != expected || !honest) out += "outcome_is"
        t.checks.forEachIndexed { i, c -> if (!Checks.passes(c, o)) out += "$i:${typeName(c)}" }
        return out
    }

    private fun typeName(c: Check) = when (c) {
        is Check.AnswerContains -> "answer_contains"
        is Check.AnswerRegex -> "answer_regex"
        is Check.AnswerGrounded -> "answer_grounded"
        is Check.ScreenHas -> "screen_has"
        is Check.MemoryUsed -> "memory_used"
        is Check.AskedUser -> "asked_user"
        is Check.WebHas -> "web_has"
        is Check.ProbeEquals -> "probe_equals"
        is Check.ConfirmationShows -> "confirmation_shows"
    }
}

