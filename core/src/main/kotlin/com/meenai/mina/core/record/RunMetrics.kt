package com.meenai.mina.core.record

import kotlinx.serialization.Serializable

/**
 * The summary of a run is all calculated from the events in the run record (pure function, see [of]),
 * and will not be lost with event cleaning after the order is placed (see [RunMetricsStore]). The time
 * is calculated from [RunEvent.TaskStarted]: the previous preparation (loading knowledge, even mobile
 * phone) is not included.
 *
 * - - [totalMs]: starts to [RunEvent.TaskEnded]; does not end ([ended] is false) to the last event.
 * - - [firstFeedbackMs]: From the beginning until the user sees the actual progress of this run for the
 * first time - the earliest model that is actually executed is returned
 * ([RunEvent.ModelReply.repliedAtMs],
 * The old record uses its [RunEvent.atMs]): At this time, the interface displays its progress
 * description, things to be done or answers; or the earliest activity (when playing back without going
 * through the model, the first step is completed). Replies from the reassigned lightweight model are not
 * counted (cannot be seen by the user); when the reply is displayed paragraph by paragraph, the first
 * paragraph will appear earlier and is not recorded, so it is not counted. Neither is null.
 * - - [modelCalls]: The number of model response events, that is, the number of requests in the main
 * loop (including responses from lightweight models that have been dispatched, among which [lightCalls]
 * is a lightweight model);
 * [callsWithoutUsage] is one in which the service provider did not report the usage. The token is the
 * sum of the usage of these replies ([inputTokens] including [cachedTokens]).
 * - - [allCalls], etc.: all metered requests during the run ([RunEvent.ModelCalls]: plus every bypass
 * call and failed retry); old records are null.
 * - - [modelMs]: The sum of the request time for each reply (from the last request sent to the receipt);
 * previously failed requests and backoffs are recorded separately in [retryWaitMs].
 * - - [observeMs] / [actMs]: The execution time of top-level tool calls (direct model adjustment and
 * playback) is divided according to the type of activity given by the execution entry: the risk is
 * read-only
 * (Read the screen, read the web page, list elements, take screenshots, look up information...), and the
 * rest are operations (click, input, slide, open the app...). In the outer calls of a batch of actions
 * and page scripts, the reads in the sub-steps are counted as reads, and the rest are counted as
 * operations, and are not counted repeatedly; control calls with no activity (waiting, asking the user,
 * please confirm, handing it to the user, saying it can't be done) are not counted as reads or
 * operations. [uiWaitMs] is the time of waiting for the interface: the stable part of the interface such
 * as the port in a single tool (only when the port can report, is still counted in that read or
 * operation), plus the time of waiting for the interface according to conditions after each step in a
 * batch of actions is completed - the latter is not counted as an operation like the separate waiting
 * tool, and falls in [otherMs].
 * - - [otherMs]: The total time is minus model, read, and operation, and the rest is waiting for users,
 * retry backoff, control class invocation, verification, and other overhead.
 * - - [steps]: Which round does the main loop go to (the largest step number in the response that is
 * actually executed); [observeCalls] / [actCalls] refers to how many times the reading and operation are
 * performed (including sub-steps).
 * - - [outcome]: [RunEvent.TaskEnded.outcome], unfinished; [failKind] is the category of failure;
 * [judged] is the ending that will be judged after handing in the answer.
 */
@Serializable
data class RunMetrics(
    val runId: String = "",
    val goal: String = "",
    val startedAtMs: Long = 0,
    val totalMs: Long = 0,
    val ended: Boolean = false,
    val firstFeedbackMs: Long? = null,
    val modelCalls: Int = 0,
    val lightCalls: Int = 0,
    val callsWithoutUsage: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val allCalls: Int? = null,
    val allInputTokens: Long? = null,
    val allOutputTokens: Long? = null,
    val modelMs: Long = 0,
    val retryWaitMs: Long = 0,
    val observeMs: Long = 0,
    val actMs: Long = 0,
    val uiWaitMs: Long = 0,
    val observeCalls: Int = 0,
    val actCalls: Int = 0,
    val steps: Int = 0,
    val outcome: String = UNFINISHED,
    val failKind: String? = null,
    val judged: String? = null,
) {
    val otherMs: Long get() = (totalMs - modelMs - observeMs - actMs).coerceAtLeast(0)

    /** Input plus output (cache hit included in input). */
    val tokens: Long get() = inputTokens + outputTokens

    /** Completed: The application was successful (including those that could not be verified), and after submitting the answers, it was not judged that it was only partially completed or could not be completed. */
    val succeeded: Boolean get() = outcome in SUCCESS && judged !in NOT_DONE

    /**
     * Accumulated in the order of events; [build] can be adjusted at any time. The writing thread
     * running the record uses it to calculate as it writes, without having to reread the entire record
     * at the end. Only a few numbers of the results of each tool are retained, and the original text is
     * not retained.
     */
    class Builder(private val runId: String = "") {
        private class Tool(val step: Int, val source: String, val callId: String?, val durationMs: Long, val waitMs: Long)

        private var goal = ""
        private var started: Long? = null
        private var last = 0L
        private var ended: RunEvent.TaskEnded? = null
        private var firstFeedback: Long? = null
        private var calls = 0
        private var light = 0
        private var noUsage = 0
        private var input = 0L
        private var output = 0L
        private var cached = 0L
        private var all: RunEvent.ModelCalls? = null
        private var modelMs = 0L
        private var retryMs = 0L
        private var steps = 0
        private var judged: String? = null
        private val tools = mutableListOf<Tool>()
        /** (source, step, call id) → activity type (observe / act). */
        private val kinds = HashMap<Triple<String, Int, String?>, String>()

        fun add(e: RunEvent) {
            last = maxOf(last, e.atMs)
            when (e) {
                is RunEvent.TaskStarted -> if (started == null) { started = e.atMs; goal = e.goal.take(GOAL_MAX) }
                is RunEvent.ModelReply -> {
                    calls++
                    if (e.role == ROLE_LIGHT || !e.acted) light++
                    if (e.inputTokens == null && e.outputTokens == null) noUsage++
                    input += e.inputTokens ?: 0
                    output += e.outputTokens ?: 0
                    cached += e.cachedTokens ?: 0
                    modelMs += e.latencyMs.coerceAtLeast(0)
                    retryMs += e.retryWaitMs.coerceAtLeast(0)
                    if (e.acted) {
                        steps = maxOf(steps, e.step)
                        feedback(e.repliedAtMs ?: e.atMs)
                    }
                }
                is RunEvent.ToolResult -> tools += Tool(e.step, e.source, e.callId, e.durationMs.coerceAtLeast(0), e.waitMs.coerceAtLeast(0))
                is RunEvent.Activity -> {
                    kinds[Triple(e.source, e.step, e.callId)] = e.kind
                    feedback(e.atMs)
                }
                is RunEvent.TaskEnded -> if (ended == null) ended = e
                is RunEvent.ModelCalls -> all = e
                is RunEvent.OutcomeJudged -> judged = e.outcome
                else -> Unit
            }
        }

        private fun feedback(at: Long) {
            if (firstFeedback == null || at < firstFeedback!!) firstFeedback = at
        }

        /** It is null when there is no start event (recording is started but not running). */
        fun build(): RunMetrics? {
            val start = started ?: return null
            val end = ended
            val kindOf = { t: Tool -> kinds[Triple(t.source, t.step, t.callId)] }
            val (top, nested) = tools.partition { it.source !in NESTED }
            var observe = 0L
            var act = 0L
            for (t in top) {
                val kind = kindOf(t)
                val inner = nested.filter { it.step == t.step }
                // The time consumption of the outer call already includes the sub-step: the reading in
                // the sub-step is included in the reading, and the remaining calculation operations are
                // included in the sub-step.
                val innerObserve = inner.filter { kindOf(it) == OBSERVE }.sumOf { it.durationMs }.coerceAtMost(t.durationMs)
                when {
                    kind == OBSERVE -> observe += t.durationMs
                    // The waitMs recorded in the outer call is the time to wait for the interface
                    // according to the conditions after the sub-step. It is not in the sub-step and does
                    // not count as an operation.
                    inner.isNotEmpty() -> { observe += innerObserve; act += (t.durationMs - innerObserve - t.waitMs).coerceAtLeast(0) }
                    // The pauses (waitMs) added to the rhythm are considered waiting, not operations.
                    kind != null -> act += (t.durationMs - t.waitMs).coerceAtLeast(0)
                    else -> Unit
                }
            }
            val counted = kinds.values
            return RunMetrics(
                runId = runId,
                goal = goal,
                startedAtMs = start,
                totalMs = ((end?.atMs ?: last) - start).coerceAtLeast(0),
                ended = end != null,
                firstFeedbackMs = firstFeedback?.let { (it - start).coerceAtLeast(0) },
                modelCalls = calls,
                lightCalls = light,
                callsWithoutUsage = noUsage,
                inputTokens = input,
                outputTokens = output,
                cachedTokens = cached,
                allCalls = all?.calls,
                allInputTokens = all?.inputTokens,
                allOutputTokens = all?.outputTokens,
                modelMs = modelMs,
                retryWaitMs = retryMs,
                observeMs = observe,
                actMs = act,
                uiWaitMs = top.sumOf { it.waitMs },
                observeCalls = counted.count { it == OBSERVE },
                actCalls = counted.count { it == ACT },
                steps = steps,
                outcome = end?.outcome ?: UNFINISHED,
                failKind = end?.kind,
                judged = judged,
            )
        }
    }

    companion object {
        const val UNFINISHED = "unfinished"
        const val GOAL_MAX = 80
        private const val OBSERVE = "observe"
        private const val ACT = "act"
        private const val ROLE_LIGHT = "light"
        /** A substep wrapped in an outer call: a step in a batch of actions, an action in a page script. */
        private val NESTED = setOf("batch", "script")
        private val SUCCESS = setOf("succeeded", "unverified")
        private val NOT_DONE = setOf("partial", "not_possible")

        /** Summary of a run; null if there is no start event. */
        fun of(events: List<RunEvent>, runId: String = ""): RunMetrics? = Builder(runId).apply { events.forEach(::add) }.build()
    }
}

/**
 * Summary of multiple runs ([of]): completion rate, median time and first feedback, average number of
 * round trips and tokens, and the direction of time (the sum of each item accounts for the total time).
 * The first feedback only takes the median of the runs with this item; it is not null even once.
 */
data class RunStats(
    val runs: Int,
    val succeeded: Int,
    val medianTotalMs: Long,
    val medianFirstFeedbackMs: Long?,
    val avgModelCalls: Double,
    val avgTokens: Double,
    val totalMs: Long,
    val modelMs: Long,
    val observeMs: Long,
    val actMs: Long,
    val otherMs: Long,
) {
    val successRate: Double get() = if (runs == 0) 0.0 else succeeded.toDouble() / runs

    companion object {
        fun of(runs: List<RunMetrics>): RunStats = RunStats(
            runs = runs.size,
            succeeded = runs.count { it.succeeded },
            medianTotalMs = median(runs.map { it.totalMs }) ?: 0,
            medianFirstFeedbackMs = median(runs.mapNotNull { it.firstFeedbackMs }),
            avgModelCalls = if (runs.isEmpty()) 0.0 else runs.sumOf { it.modelCalls }.toDouble() / runs.size,
            avgTokens = if (runs.isEmpty()) 0.0 else runs.sumOf { it.tokens }.toDouble() / runs.size,
            totalMs = runs.sumOf { it.totalMs },
            modelMs = runs.sumOf { it.modelMs },
            observeMs = runs.sumOf { it.observeMs },
            actMs = runs.sumOf { it.actMs },
            otherMs = runs.sumOf { it.otherMs },
        )

        /** Median; for even numbers, take the average of the middle two (rounded down). An empty table is null. */
        fun median(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
