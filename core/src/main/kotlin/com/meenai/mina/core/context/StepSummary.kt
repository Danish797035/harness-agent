package com.meenai.mina.core.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Structured summary of collapsed steps: Goal/Confirmed Facts/Progress/Next Steps. Each round of
 * discounts will be updated based on the previous one ([next]). The content is all deterministically
 * taken from the original words of the task, the tool results, and the words of the model itself,
 * without adjusting the model: the summary must be recalculated for each compression, and adjusting the
 * model will require an extra round trip, and the same history may result in different summaries, and
 * the request prefix will be unstable. Each paragraph has an upper limit (in fact, [MAX_FACT_VALUES] of
 * each type, [MAX_PROGRESS] lines of progress, [MAX_EARLIER_KINDS] of earlier endings, each cut to
 * [CLIP] words), and the summary will not grow infinitely long no matter how many steps it takes.
 */
data class StepSummary(
    val goal: String,
    /** fact type → value (remove duplicates, new ones last). */
    val facts: Map<String, List<String>> = emptyMap(),
    val progress: List<String> = emptyList(),
    /** Combined into earlier steps of counting: ending → number of steps. */
    val earlier: Map<String, Int> = emptyMap(),
    /** The model’s last stated plan in the folding section. */
    val intent: String? = null,
) {
    /** Fold into one round: [line] is the progress line of this step (ending with "→ ending"), [results] is the successful tool result of this round, and [said] is what the model said this round. */
    fun next(line: String, results: List<JsonObject>, said: String?): StepSummary {
        val f = facts.toMutableMap()
        for (r in results) for ((key, kind) in FACT_KEYS) {
            val v = (r[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            f[kind] = ((f[kind].orEmpty() - clip(v)) + clip(v)).takeLast(MAX_FACT_VALUES)
        }
        var p = progress + line
        var e = earlier
        while (p.size > MAX_PROGRESS) {
            val outcome = p.first().substringAfterLast("→ ").trim().let { if (it in e || e.size < MAX_EARLIER_KINDS) it else OTHER }
            e = e + (outcome to (e[outcome] ?: 0) + 1)
            p = p.drop(1)
        }
        return StepSummary(goal, f, p, e, said?.let(::clip)?.takeIf { it.isNotEmpty() } ?: intent)
    }

    fun render(header: String): String = buildString {
        appendLine(header)
        appendLine("Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only.")
        appendLine("Goal: $goal")
        appendLine("Confirmed facts:")
        if (facts.isEmpty()) appendLine("- (none yet)")
        FACT_KEYS.values.forEach { kind -> facts[kind]?.let { appendLine("- $kind: ${it.joinToString(", ")}") } }
        appendLine("Progress:")
        if (earlier.isNotEmpty()) appendLine("- ${earlier.values.sum()} earlier steps: " + earlier.entries.joinToString(", ") { "${it.key} ${it.value}" })
        progress.forEach { appendLine("- $it") }
        append("Next: continue from the most recent steps kept below")
        intent?.let { append(" (last stated plan before folding: $it)") }
    }

    private companion object {
        /** A short field in the result that can be recorded as a fact → fact type. */
        val FACT_KEYS = linkedMapOf("currentApp" to "apps visited", "url" to "pages opened", "path" to "files read or written")
        const val MAX_FACT_VALUES = 4
        const val MAX_PROGRESS = 15
        const val MAX_EARLIER_KINDS = 5
        const val OTHER = "other"
        const val CLIP = 100

        fun clip(s: String): String {
            val one = s.replace('\n', ' ').trim()
            return if (one.length <= CLIP) one else one.take(CLIP) + "…"
        }
    }
}
