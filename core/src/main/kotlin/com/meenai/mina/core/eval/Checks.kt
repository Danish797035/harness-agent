package com.meenai.mina.core.eval

import com.meenai.mina.core.verify.Grounding

/** After a task is completed, the evaluator collects the facts, and only looks at it when scoring. */
data class Observed(
    /** succeeded|unverified|failed|cancelled|confirmation|takeover|timeout|skipped */
    val outcome: String,
    val answer: String,
    /** The materials selected according to the answers in the evidence ledger are read one place at a time (the same set given to the verifier at runtime, see [RunFacts.evidence]), and the echo of the Agent's own input has been removed. */
    val evidence: List<String>,
    /** Confirm card fields: action/target/content/amount. */
    val confirmation: Map<String, String>?,
    /** The key is [Checks.screenKey]. */
    val screenHas: Map<String, Boolean>,
    /** Probe executed after task: name → output. */
    val probes: Map<String, String>,
    val steps: Int,
    val wallMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val failReason: String?,
    /** Apps that the Agent has touched but are not in the question apps are only recorded and are not counted as failures. */
    val leftScope: List<String> = emptyList(),
    /** Original text of the question: Reciting the words in the question (App name, calculation formula) in the answer does not count as the value to be read from the screen. */
    val goal: String = "",
    /** The conclusion of the verification before completion (pass / fail / cannot_verify); if it has not been verified, it is null. */
    val verdict: String? = null,
    /** The number of model calls in the main loop (number of ModelReply events; playback and batch execution steps are not counted). */
    val modelCalls: Int = 0,
    /** The total waiting time of the model (sum of each ModelReply.latencyMs). */
    val modelMs: Long = 0,
    /** Total tool execution time (sum of each ToolResult.durationMs). */
    val toolMs: Long = 0,
    /** The part where the intermediate interface is stable (the sum of each ToolResult.waitMs). */
    val waitMs: Long = 0,
    /** The number of routes completed during playback / the number of routes returned to the model midway. */
    val replayHits: Int = 0,
    val replayHandBacks: Int = 0,
    /** The number of memories at the beginning of the task (memory_used event). */
    val memoryUsed: Int = 0,
    /** The name of the personal skill used this time (skill_used event, deduplication). */
    val skillsUsed: List<String> = emptyList(),
    /** The key is [Checks.webKey]. */
    val webHas: Map<String, Boolean> = emptyMap(),
    /** Obstacles reported immediately before takeover that are visible on that screen (see [RunFacts.takeoverBarrier]); not null. */
    val takeoverBarrier: String? = null,
    /** The number of times Agent asked the user (ask_user). */
    val askedUser: Int = 0,
    /** The day of the task (the date of the running clock): If you write "tomorrow" as a specific date in the answer, click it to check (see [Grounding.check]); if you don't know, it is null. */
    val today: java.time.LocalDate? = null,
    /** The outcome of the judgment after handing in the answer (outcome event in the running record: done / partial / not_possible / unknown); if not judged, it is null. Click it to score, see [Scoring.recorded]. */
    val judged: String? = null,
    /** The summary of this run (time destination, first feedback, number of round trips, etc., see [com.meenai.mina.core.record.RunMetrics]); no run record is null. */
    val metrics: com.meenai.mina.core.record.RunMetrics? = null,
)

object Checks {
    fun screenKey(c: Check.ScreenHas): String = "${c.packageName}|${c.deepLink.orEmpty()}|${c.text}"

    fun webKey(c: Check.WebHas): String = "${c.url}|${c.text}"

    fun passes(c: Check, o: Observed): Boolean = when (c) {
        is Check.AnswerContains -> c.all.all { it in o.answer }
        is Check.AnswerRegex -> Regex(c.pattern).containsMatchIn(o.answer)
        is Check.AnswerGrounded -> grounded(o.answer, o.evidence, c.minItems, o.goal, o.today)
        is Check.ScreenHas -> o.screenHas[screenKey(c)] == true
        is Check.MemoryUsed -> o.memoryUsed >= c.min
        is Check.AskedUser -> (o.askedUser > 0) == c.expected
        is Check.WebHas -> o.webHas[webKey(c)] == true
        is Check.ProbeEquals -> o.probes[c.probe]?.trim()?.let { actual ->
            if (c.contains) c.expected.trim() in actual else actual == c.expected.trim()
        } ?: false
        is Check.ConfirmationShows -> o.confirmation?.let { card ->
            listOf("action" to c.action, "target" to c.target, "content" to c.content, "amount" to c.amount)
                .all { (k, want) -> want == null || want in card[k].orEmpty() }
        } ?: false
    }

    /**
     * The answer is well-documented: the same literal rule as checking the bottom line
     * ([Grounding.check]: citations, numbers with units, numbers, and dates can all be found in the
     * materials and can be located based on the citations in the materials), and the well-documented
     * entries ([Grounding.Result.items]) are no less than [minItems]. The numbers and words in the
     * question [goal] are considered known. Whether the value belongs to what is mentioned in the answer
     * (which line, which page) is not determined by the code, it is determined by the verification model
     * (see whether the ending succeeded in the evaluation).
     */
    internal fun grounded(answer: String, screens: List<String>, minItems: Int, goal: String = "", today: java.time.LocalDate? = null): Boolean =
        Grounding.check(goal, answer, screens, today).let { it.found != false && it.items >= minItems }
}
