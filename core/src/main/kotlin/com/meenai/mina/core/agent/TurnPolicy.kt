package com.meenai.mina.core.agent

import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.Verb

/** A reminder sent to the model in a loop: source tag and text (packaged into context by [ContextBuilder.notice]). */
internal data class Notice(val source: String, val text: String)

/** A snapshot of the round strategy: the number of steps and the upper limit of the number of steps. */
internal data class TurnSnapshot(val step: Int, val maxSteps: Int)

/** The same rule as the basic prompt words: if the desired result is missing the function, item or value to be used, the answer cannot be obtained; only when asking "Is it available?", the App's search will clearly say that it is not available. */
internal val ANSWER_OR_FAILURE: String get() = Feedback["answer_or_failure"]

/**
 * Round strategy: whether to remind at the beginning of each step (self-check midway), whether to remind
 * or end when the reply is empty (or truncated by the output limit), how to remind when the check is not
 * correct, what to say in the last round after the number of steps is used up, and what tools to give.
 * Pure functions only look at what is passed in. The reply without the call itself is the answer and is
 * not ignored here; only the empty reply will be reminded, and only once.
 */
internal object TurnPolicy {
    /** Self-examination midway: remind once when these ratios are used for steps, and give up after searching them all. Tasks with too few steps will not be reminded. */
    private val PROGRESS_CHECKS = listOf(0.5, 0.75)
    private const val PROGRESS_CHECK_MIN_STEPS = 10
    /** In the last round after the number of steps is used up, only this tool is given up, and the answer is written in text. */
    val FINAL_VERBS = setOf(Verb.EndNotDone)

    /** The reminder is added before sending it to the model in this step; if not, it is null. */
    fun beforeStep(s: TurnSnapshot): Notice? {
        if (s.maxSteps < PROGRESS_CHECK_MIN_STEPS) return null
        val used = s.step - 1
        if (PROGRESS_CHECKS.none { (s.maxSteps * it).toInt() == used }) return null
        return Notice(Feedback["progress_source"],
            Feedback.format("progress_check", "used" to used, "max" to s.maxSteps, "answer_or_failure" to ANSWER_OR_FAILURE))
    }

    /**
     * There is no call and no words in the reply ([truncated]: the words are truncated by the output
     * upper limit, not a complete answer). [already] is true, indicating that such a reply has just been
     * reminded once: no more reminders this time, null is returned, and the task ends.
     */
    fun afterEmpty(already: Boolean, truncated: Boolean): Notice? =
        if (already) null else Notice(Feedback["nudge_source"], Feedback[if (truncated) "truncated_reply" else "empty_reply"])

    /**
     * Reminder when the answer is not correct in the verification and is returned once: [gaps] refers to
     * the few places that cannot be found in the materials I read, and [objections] refers to the
     * reasons why the verification model was not completed. Let’s talk about them separately.
     */
    fun unconfirmed(gaps: List<String>, objections: List<String> = emptyList()): Notice {
        val findings = listOfNotNull(
            gaps.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_gaps", "items" to it.joinToString("\n") { g -> "- $g" }) },
            objections.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_objection", "reasons" to it.joinToString("\n") { o -> "- $o" }) },
        ).joinToString("\n")
        return Notice(Feedback["unconfirmed_source"], Feedback.format("unconfirmed_nudge", "findings" to findings))
    }

    /** The text of the answer is not in the language of the user's sentence ([m] is the language family on both sides, see [ReplyLanguage]): return it once and rewrite the entire paragraph into the user's language. */
    fun language(m: ReplyLanguage.Mismatch): Notice =
        Notice(Feedback["language_source"], Feedback.format("language_nudge", "answer" to m.answer.display, "user" to m.user.display))

    /** The call to [tool] (see [LeakedToolCall]) is leaked in the reply text: return it once, call it again through the tool interface, or answer in text. */
    fun leakedToolCall(tool: String): Notice =
        Notice(Feedback["leaked_call_source"], Feedback.format("leaked_call_nudge", "tool" to tool))

    /** The user agrees or answers ([what]), but then does nothing and needs to submit an answer: remind him to do it once, or explain clearly why he did not do it. */
    fun followUp(what: String): Notice = Notice(Feedback["followup_source"], Feedback.format("followup_nudge", "what" to what))

    /** Reminder after running out of steps and before the last round. */
    fun finalTurn(): Notice = Notice(Feedback["final_source"], Feedback["final_turn"])

    /** The tools given to the model in the last round: give up; answer directly in words after finishing. */
    fun finalTools(specs: List<ToolSpec>, verbOf: (String) -> Verb?): List<ToolSpec> = specs.filter { verbOf(it.name) in FINAL_VERBS }
}
