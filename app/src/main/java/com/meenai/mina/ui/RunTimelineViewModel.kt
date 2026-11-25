package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunMetrics
import com.meenai.mina.record.RunStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import com.meenai.mina.core.assembly.knownTools

enum class TagTone { Ok, Warn, Bad }

/** Tags on the timeline: Guard, Verification, Confirmation, Takeover, Obstacle Conclusion. */
data class Tag(val text: String, val tone: TagTone)

/** Actual execution of the activities reported by the portal; consecutive reads can be combined into one line, and the actions remain visible one by one. */
data class TimelineActivity(
    val kind: String,
    val label: String,
    val target: String?,
    val effect: String?,
    val count: Int = 1,
    val obsIds: List<Int> = emptyList(),
)

/** Recorded elapsed time. Batch/script sub-actions are included in the outer tool time consumption and are only counted once. */
data class StepTiming(
    val modelMs: Long? = null,
    val toolMs: Long? = null,
    val retryMs: Long = 0,
) {
    val totalMs: Long? get() = if (modelMs != null && toolMs != null) modelMs + toolMs else null
}

/**
 * A line of the timeline. [said] is what is said in this step of the model (cut to 80 words), [tool] is
 * the Chinese term for the tool, [detail] is the parameter that best describes the object (the record
 * has been desensitized and displayed as is), [screenshotId] is the picture saved in this step.
 */
data class TimelineStep(
    val step: Int,
    val said: String,
    val tool: String?,
    val detail: String?,
    val result: String?,
    val tags: List<Tag>,
    val screenshotId: Int?,
    val activities: List<TimelineActivity> = emptyList(),
    val timing: StepTiming = StepTiming(),
)

data class TimelineHeader(
    val goal: String,
    val criteria: List<String>,
    val outcome: String,
    /** This run is not over yet: the ending, verification, and statistics have not been written yet, and the page continues to be read every second (see [RunTimelineScreen]). */
    val running: Boolean = false,
    val verdict: String,
    val duration: String?,
    val modelCalls: Int,
    val usedMemory: List<String> = emptyList(),
    /** Summary of this run; if there is no start event in the record, it is null (only the time is displayed). */
    val metrics: RunMetrics? = null,
    /** Which model is executed or switching instructions (see Free Key Router). */
    val routerNote: String? = null,
)

sealed interface TimelineUi {
    data object Loading : TimelineUi
    /** The record has been cleared (only the last 30 records are retained) or the number is incorrect. */
    data object Expired : TimelineUi
    data class Ready(val header: TimelineHeader, val steps: List<TimelineStep>) : TimelineUi

    companion object {
        val EXPIRED_TEXT: String get() = tr(R.string.timeline_expired)
    }
}

/** Read-only timeline for one run. [load] disk reading needs to be adjusted in the background thread. */
class RunTimelineViewModel(
    private val store: RunStore, val runId: String,
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    private val resolveMemory: (List<String>) -> List<String> = { emptyList() },
    /**
     * The task run this time is still running at the moment. The running calculation that has not ended
     * the record and the task is no longer there (the process was killed and the record was written
     * halfway) has ended, and the page is not reread every second for it.
     */
    private val alive: (runId: String) -> Boolean = { false },
) {
    fun load(): TimelineUi {
        val summary = store.list().firstOrNull { it.id == runId }
        val events = store.events(runId)
        if (summary == null && events.isEmpty()) return TimelineUi.Expired
        val started = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val startMs = summary?.startedAtMs ?: events.first().atMs
        val endMs = summary?.endedAtMs ?: ended?.atMs
        val models = mutableListOf<String>()
        started?.model?.takeIf(String::isNotBlank)?.let { models.add(it) }
        events.filterIsInstance<RunEvent.ModelReply>().forEach { reply ->
            reply.model?.takeIf(String::isNotBlank)?.let { if (!models.contains(it)) models.add(it) }
        }
        val isRunning = ended == null && summary?.endedAtMs == null && alive(runId)
        val routerNote = when {
            isRunning && models.isNotEmpty() -> tr(R.string.router_model_running_note, models.last())
            isRunning && !summary?.model.isNullOrBlank() -> tr(R.string.router_model_running_note, summary!!.model)
            models.size >= 2 -> tr(R.string.router_switched_note, models[0], models[1])
            models.size == 1 -> tr(R.string.router_model_used_note, models[0])
            else -> summary?.model?.takeIf(String::isNotBlank)?.let { tr(R.string.router_model_used_note, it) }
        }
        val header = TimelineHeader(
            goal = summary?.goal ?: started?.goal.orEmpty(),
            criteria = summary?.criteria?.takeIf { it.isNotEmpty() }
                ?: events.filterIsInstance<RunEvent.Criteria>().lastOrNull()?.criteria.orEmpty(),
            outcome = outcomeLabel(summary?.outcome ?: ended?.outcome,
                summary?.judged ?: events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome),
            // As long as there is one of the end event or the end time, the run is completed; if there
            // is neither, the task is still running, and the interface decides whether to reread by the
            // second based on this.
            running = isRunning,
            verdict = verdictLabel(summary?.verdict ?: events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict),
            duration = endMs?.let { duration(it - startMs) },
            modelCalls = events.count { it is RunEvent.ModelReply },
            usedMemory = resolveMemory(events.filterIsInstance<RunEvent.MemoryUsed>().lastOrNull()?.ids.orEmpty()),
            metrics = RunMetrics.of(events, runId),
            routerNote = routerNote,
        )
        return TimelineUi.Ready(header, steps(events, toolLabels::label))
    }

    fun screen(screenshotId: Int): File? = store.screen(runId, screenshotId)

    fun usageLog(): String = store.usageLog(runId)

    companion object {
        const val SAID_MAX = 80

        /** Grouped by step number; events without a step number (verification, confirmation, takeover, obstacle) are hung on the most recent step before it. */
        internal fun steps(events: List<RunEvent>, toolLabel: (String) -> String = { it }): List<TimelineStep> {
            val rows = LinkedHashMap<Int, TimelineStep>()
            val modelTimes = mutableMapOf<Int, Long>()
            val retryTimes = mutableMapOf<Int, Long>()
            val toolTimes = mutableMapOf<Int, MutableList<RunEvent.ToolResult>>()
            var current: Int? = null
            fun at(step: Int) = rows.getOrPut(step) { TimelineStep(step, "", null, null, null, emptyList(), null) }.also { current = step }
            fun tag(t: Tag) { current?.let { s -> rows[s] = rows.getValue(s).let { it.copy(tags = it.tags + t) } } }
            for (e in events) when (e) {
                // The response to the dispatched lightweight model was not executed and no steps were
                // entered; the number of calls will still be counted.
                is RunEvent.ModelReply -> {
                    val elapsed = e.latencyMs.coerceAtLeast(0) + e.retryWaitMs.coerceAtLeast(0)
                    if (elapsed > 0) modelTimes[e.step] = modelTimes.getOrDefault(e.step, 0) + elapsed
                    if (e.retryWaitMs > 0) retryTimes[e.step] = retryTimes.getOrDefault(e.step, 0) + e.retryWaitMs
                    if (e.acted) {
                        val row = at(e.step)
                        val said = listOf(row.said, e.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                        rows[e.step] = row.copy(
                            said = said,
                            tool = e.tool?.let(toolLabel) ?: row.tool,
                            detail = e.argsJson?.let(::detailOf) ?: row.detail,
                        )
                    }
                }
                is RunEvent.ToolResult -> {
                    toolTimes.getOrPut(e.step) { mutableListOf() } += e
                    val row = at(e.step)
                    rows[e.step] = row.copy(
                        result = resultLabel(e.resultJson, e.changed),
                        tags = row.tags + guardTags(e.resultJson),
                        screenshotId = row.screenshotId ?: e.screenshotId,
                    )
                }
                is RunEvent.Media -> at(e.step).let { rows[e.step] = it.copy(screenshotId = it.screenshotId ?: e.mediaId) }
                is RunEvent.Activity -> {
                    val row = at(e.step)
                    val next = TimelineActivity(e.kind, toolLabel(e.label), e.target, e.effect, obsIds = e.obsIds)
                    val last = row.activities.lastOrNull()
                    val activities = if (e.kind == "observe" && last?.kind == "observe" && last.target == e.target) {
                        row.activities.dropLast(1) + last.copy(count = last.count + 1, obsIds = (last.obsIds + e.obsIds).distinct())
                    } else row.activities + next
                    rows[e.step] = row.copy(activities = activities)
                }
                is RunEvent.Verifier -> tag(verifierTag(e.verdict))
                is RunEvent.Confirmation -> tag(
                    when (e.decision) {
                        "approved" -> Tag(tr(R.string.tag_approved), TagTone.Ok)
                        "declined", "declined_before" -> Tag(tr(R.string.tag_declined), TagTone.Bad)
                        else -> Tag(tr(R.string.tag_needs_ok), TagTone.Warn)
                    },
                )
                is RunEvent.Takeover -> tag(Tag(tr(R.string.tag_takeover), TagTone.Warn))
                is RunEvent.Barrier -> tag(Tag(tr(R.string.tag_barrier), TagTone.Warn))
                else -> Unit
            }
            return rows.values.map { row ->
                val results = toolTimes[row.step].orEmpty()
                // do_steps and compound tools also remember sub-actions; the outer durationMs already
                // contains them.
                val outer = results.filter { it.source == "model" && it.durationMs > 0 }
                val measured = (outer.ifEmpty { results.filter { it.durationMs > 0 } }).sumOf { it.durationMs }
                row.copy(
                    said = clipSaid(row.said),
                    timing = StepTiming(modelTimes[row.step], measured.takeIf { it > 0 }, retryTimes[row.step] ?: 0),
                )
            }
        }

        internal fun timingLabel(t: StepTiming): String {
            if (t.modelMs == null && t.toolMs == null) return tr(R.string.timing_unrecorded)
            return buildList {
                t.totalMs?.let { add(tr(R.string.timing_total, shortDuration(it))) }
                t.modelMs?.let { add(tr(R.string.timing_model, shortDuration(it))) }
                t.toolMs?.let { add(tr(R.string.timing_tool, shortDuration(it))) }
                if (t.retryMs > 0) add(tr(R.string.timing_retry, shortDuration(t.retryMs)))
            }.joinToString(" · ")
        }

        private fun shortDuration(ms: Long): String = when {
            ms < 1_000 -> tr(R.string.duration_ms, ms)
            ms < 60_000 -> tr(R.string.duration_seconds_decimal, "%.1f".format(java.util.Locale.ROOT, ms / 1_000.0))
            else -> duration(ms)
        }

        private fun clipSaid(s: String) = if (s.length <= SAID_MAX) s else s.take(SAID_MAX) + "…"

        private val DETAIL_KEYS = listOf("text", "package", "query", "key", "direction", "ref", "answer", "reason")

        private fun detailOf(argsJson: String): String? {
            val o = parse(argsJson) ?: return null
            return DETAIL_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }?.let(::clipSaid)
        }

        // The result may be truncated into illegal JSON: ok / error is only found according to the
        // writing method of the top-level key (the quotes in the element text are escaped and will not
        // be missed)
        private val NOT_OK = Regex(""""ok"\s*:\s*false""")
        private val ERROR = Regex(""""error"\s*:\s*"([a-z_]+)"""")

        private fun resultLabel(json: String, changed: Boolean?): String = when {
            NOT_OK.containsMatchIn(json) -> ERROR.find(json)?.groupValues?.get(1)?.let { ERROR_LABELS[it] }?.let { tr(R.string.result_error_detail, it) } ?: tr(R.string.result_error)
            changed == true -> tr(R.string.result_effective)
            changed == false -> tr(R.string.result_unchanged)
            else -> tr(R.string.result_completed)
        }

        private fun guardTags(json: String): List<Tag> = guardObjects(json).mapNotNull { o ->
            val severity = (o["severity"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val what = (o["code"] as? JsonPrimitive)?.contentOrNull?.let { GUARD_LABELS[it] }
            val head = if (severity == "stop") tr(R.string.guard_stop) else tr(R.string.guard_warn)
            Tag(if (what == null) head else tr(R.string.guard_with_what, head, what), if (severity == "stop") TagTone.Bad else TagTone.Warn)
        }

        /**
         * Each item in the guard array in the result. The complete JSON is parsed directly; when it is
         * truncated, the complete objects are taken out one by one from `"guard":[` onwards, and the
         * incomplete ones at the truncation point are discarded.
         */
        private fun guardObjects(json: String): List<JsonObject> {
            parse(json)?.let { o -> return (o["guard"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() }
            val start = GUARD_START.findAll(json).lastOrNull()?.range?.last?.plus(1) ?: return emptyList()
            val out = mutableListOf<JsonObject>()
            var depth = 0
            var inString = false
            var escaped = false
            var from = -1
            for (i in start until json.length) {
                val c = json[i]
                when {
                    escaped -> escaped = false
                    inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' -> { if (depth == 0) from = i; depth++ }
                    c == '}' -> { depth--; if (depth == 0) parse(json.substring(from, i + 1))?.let(out::add) }
                    c == ']' && depth == 0 -> break
                }
            }
            return out
        }

        private val GUARD_START = Regex(""""guard"\s*:\s*\[""")

        private fun parse(s: String): JsonObject? = try {
            Json.parseToJsonElement(s) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

        private val GUARD_LABELS get() = mapOf(
            "repeating_action" to tr(R.string.guard_repeating),
            "no_progress" to tr(R.string.guard_no_progress),
            "too_many_failures" to tr(R.string.guard_failures),
        )

        private val ERROR_LABELS get() = mapOf(
            "ref_expired" to tr(R.string.err_ref_expired),
            "no_app_open" to tr(R.string.err_no_app),
            "invalid_arguments" to tr(R.string.err_invalid_args),
            "operation_failed" to tr(R.string.err_op_failed),
            "unknown_tool" to tr(R.string.err_unknown_tool),
            "protected_content" to tr(R.string.err_protected),
            "confirmation_required" to tr(R.string.err_confirm_required),
            "confirmation_mismatch" to tr(R.string.err_confirm_mismatch),
            "confirmation_declined" to tr(R.string.err_confirm_declined),
            "action_not_applied" to tr(R.string.err_not_applied),
            "verification_failed" to tr(R.string.verify_failed),
            "parallel_tool_call_skipped" to tr(R.string.err_parallel_skipped),
            "screenshot_unavailable" to tr(R.string.err_no_screenshot),
            "vision_unavailable" to tr(R.string.err_vision),
            "payment_requires_takeover" to tr(R.string.err_payment),
            "finish_missing_evidence" to tr(R.string.err_no_evidence),
            "double_click_unsupported" to tr(R.string.err_double_click),
            "app_blocked" to tr(R.string.err_app_blocked),
            "app_access_unverified" to tr(R.string.err_app_unverified),
            "app_access_unavailable" to tr(R.string.err_app_unavailable),
            "app_access_protected" to tr(R.string.err_app_protected),
        )

        private fun verifierTag(verdict: String) = when (verdict) {
            "pass" -> Tag(tr(R.string.verify_passed), TagTone.Ok)
            "fail" -> Tag(tr(R.string.verify_failed), TagTone.Bad)
            else -> Tag(tr(R.string.verify_unverified), TagTone.Warn)
        }

        private fun verdictLabel(verdict: String?) = if (verdict == null) tr(R.string.verify_none) else verifierTag(verdict).text

        /** [judged]: the outcome judged after the answer was handed over; an answer that truthfully says it failed or only did part is labelled by it. */
        private fun outcomeLabel(outcome: String?, judged: String?) = when {
            outcome != "succeeded" && outcome != "unverified" -> outcomeLabel(outcome)
            judged == "partial" -> tr(R.string.outcome_partial)
            judged == "not_possible" -> tr(R.string.outcome_not_possible)
            else -> outcomeLabel(outcome)
        }

        private fun outcomeLabel(outcome: String?) = when (outcome) {
            "succeeded" -> tr(R.string.outcome_succeeded)
            "unverified" -> tr(R.string.outcome_unverified)
            "failed" -> tr(R.string.outcome_failed)
            "cancelled" -> tr(R.string.steer_cancelled)
            "error" -> tr(R.string.result_error)
            null -> tr(R.string.outcome_unfinished)
            else -> outcome
        }

        internal fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s < 60) tr(R.string.duration_s, s) else tr(R.string.duration_m_s, s / 60, s % 60)
        }
    }
}
