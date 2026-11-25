package com.meenai.mina.core.agent

import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.verify.AnswerSource
import com.meenai.mina.core.verify.AnswerSources
import com.meenai.mina.core.verify.Criteria
import com.meenai.mina.core.verify.Grounding
import com.meenai.mina.core.verify.Verdict
import com.meenai.mina.core.verify.Verification
import com.meenai.mina.core.verify.Verifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Check the status of the task before answering and handing it over. */
internal data class ReviewFacts(
    /** The tools for this task have been adjusted (the ones that have not been adjusted are chat and common sense, and there is no material to check). */
    val acted: Boolean,
    /** Have done something external or irreversible (permitted such an action, or handed over payment to the user). */
    val outward: Boolean,
    /** I haven’t read it again since the last action that changed the status: The answer is that no one has read it. */
    val unreadChange: Boolean,
)

/**
 * Verification before final answer is handed over. Come according to risk, no return:
 * - - This task has not been checked with a single tool (you can answer it in chat, common sense, and
 * dialogue): not checked, not checked.
 * - - Literal check ([Grounding.check]): The numbers, numbers, dates, citations and stated calculations
 * (a operator b = c) in the answer must have the original text in the materials read for this task.
 * If there are such facts in the answer, they are all correct, and it is considered verified; if there
 * are no such facts in the answer, and there is literally nothing to verify, it is not considered
 * verified.
 * - - If you have done something external or irreversible, you have not re-read it after the last action
 * to change the status, or if the literal verification does not match, please also check the model
 * ([Verifier]); it will be considered verified only if it passes.
 * If there are unmatched conclusions ([Review.gaps], [Review.objections]), the loop will be returned to
 * the model; if not, these points will be marked as unconfirmed and handed over (see AgentLoop). The
 * source ([Review.sources]) is also found here by the code ([AnswerSources]).
 */
internal class AnswerReview(
    private val tools: ToolDispatcher,
    private val verifier: Verifier?,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    /** The product path will only sort out the sources and will not initiate another round of verification for the final reply. */
    suspend fun sourcesOnly(goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> =
        AnswerSources.of(tools.evidence(fresh = false).ledger, goal, answer, media)

    /**
     * [gaps] is the answer that cannot be found in the materials I read, [objections] is the reason why
     * the verification model was not completed; the two together are [unconfirmed]. [reviewed] is false,
     * which means nothing has been checked (nothing can be checked, and no model is requested to be
     * checked); [verified] is true only when it has been checked and everything is correct. [note] is
     * the reason why the verifier failed to verify; [verdict] is the conclusion of the verification
     * model (it is not null).
     */
    class Review(
        val gaps: List<String> = emptyList(), val objections: List<String> = emptyList(), val sources: List<AnswerSource> = emptyList(),
        val reviewed: Boolean = true, val verified: Boolean = true, val note: String? = null, val verdict: Verdict? = null,
    ) {
        val unconfirmed: List<String> get() = (gaps + objections).distinct()
    }

    /** Check the answer [answer] given in step [step]. [media]: The screen number saved in each step; [verifying]: Please adjust it first when you want to verify the model ("Verifying" is displayed on the interface). */
    suspend fun review(
        goal: String, answer: String, step: Int, facts: ReviewFacts, earlierGoals: List<String>, media: Map<Int, List<Int>>, verifying: () -> Unit,
    ): Review {
        if (!facts.acted) return Review(reviewed = false, verified = false)
        val v = verifier
        // Literal verification only looks at the ledger (the latest read is already in it), and does not
        // re-read it; only re-read the current interface when you want to verify the model.
        val read = tools.evidence(fresh = false)
        val sources = AnswerSources.of(read.ledger, goal, answer, media)
        val g = Grounding.check(goal, answer, read.sources(read.ledger.forAnswer(goal, answer)), read.today)
        val gaps = g.unmatched.map { it.text }.distinct()
        val risky = facts.outward || facts.unreadChange
        emit(RunEvent.AnswerCheck(now(), step, g.claims.size, gaps.map(::detail), risky))
        val literalOk = gaps.isEmpty() && g.claims.isNotEmpty()
        if (!risky && gaps.isEmpty()) return Review(sources = sources, reviewed = literalOk, verified = literalOk)
        if (v == null) return Review(gaps = gaps, sources = sources, reviewed = literalOk || gaps.isNotEmpty(), verified = literalOk)
        verifying()
        val ev = tools.evidence(withImage = v.wantsImage)
        val r = v.verify(goal, Criteria.of(goal, facts.outward), answer, ev, earlierGoals)
        emit(RunEvent.Verifier(now(), r.verdict.wire, RunEvent.clip(Redactor.text(detailOf(r).toString())), step))
        return when (r.verdict) {
            Verdict.Pass -> Review(sources = sources, verdict = r.verdict)
            // The standard is just the user's original words, and the "standard" that is not met is the
            // original words themselves: what is clear is the reason given by the checker
            Verdict.Fail -> Review(gaps = gaps, objections = listOf(r.reason).filter(String::isNotBlank), sources = sources, verified = false, verdict = r.verdict)
            Verdict.CannotVerify -> (gaps + r.notFound).distinct().let { missing ->
                Review(gaps = missing, sources = sources, verified = false, note = r.reason, verdict = r.verdict)
            }
        }
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private fun detailOf(v: Verification) = buildJsonObject {
        put("reason", v.reason)
        v.groundedBy?.let { put("groundedBy", it) }
        if (v.notFound.isNotEmpty()) put("notFound", JsonArray(v.notFound.map(::JsonPrimitive)))
        put("checks", JsonArray(v.checks.map { c ->
            buildJsonObject { put("criterion", c.criterion); put("status", c.status); c.quote?.let { put("quote", it) } }
        }))
    }
}
