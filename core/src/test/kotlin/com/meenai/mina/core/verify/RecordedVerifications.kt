package com.meenai.mina.core.verify

import com.meenai.mina.core.eval.RunFacts
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.testing.RECORDED_TOOLS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * In the recorded run, each verification (verifier event) is paired with the final answer it verified
 * (the new record is the reply without the call, the old record is the finish call, see
 * [RunFacts.isFinalAnswer]), and does not depend on the order of the two: the usual step is to verify
 * first, then record the reply after verification, and in the last round, record the reply first and
 * then verify.
 *
 * - - Events with step: with answers of the same step.
 * - - The old record does not have a step: the first model reply after it is the answer that has not
 * been matched yet, that is it (check first and then write down the reply); otherwise, the most recent
 * answer before it that has not been matched yet is matched.
 *
 * Return (verifier subscript, answer subscript); unworthy verifications are not included.
 */
internal object RecordedVerifications {
    fun pairs(events: List<RunEvent>): List<Pair<Int, Int>> {
        val used = mutableSetOf<Int>()
        fun finishAt(i: Int) = RunFacts.isFinalAnswer(events[i]) && i !in used
        val out = mutableListOf<Pair<Int, Int>>()
        events.forEachIndexed { v, e ->
            if (e !is RunEvent.Verifier) return@forEachIndexed
            val f = if (e.step != null) {
                events.indices.lastOrNull { finishAt(it) && (events[it] as RunEvent.ModelReply).step == e.step }
            } else {
                val next = (v + 1 until events.size).firstOrNull { events[it] is RunEvent.ModelReply }
                next?.takeIf(::finishAt) ?: (v - 1 downTo 0).firstOrNull(::finishAt)
            } ?: return@forEachIndexed
            used += f
            out += v to f
        }
        return out
    }

    /** Verify the materials available for [v] at that time: its previous events, excluding the time when it was verified, finish your own reply and results (the answer cannot be used to prove yourself). */
    fun before(events: List<RunEvent>, v: Int, f: Int): List<RunEvent> {
        val step = (events[f] as RunEvent.ModelReply).step
        return events.subList(0, v).filterIndexed { i, e ->
            i != f && !(e is RunEvent.ToolResult && e.tool == "finish" && e.step == step)
        }
    }

    /** Check the text that can be compared at that time: all previous screen reading text and all strings in the tool results (see [before]). */
    fun sources(events: List<RunEvent>, v: Int, f: Int): List<String> = before(events, v, f).mapNotNull { b ->
        when (b) {
            is RunEvent.Observed -> b.text.takeIf { b.kind == "read" }
            is RunEvent.ToolResult -> runCatching { strings(Json.parseToJsonElement(b.resultJson)).joinToString("\n") }.getOrDefault(b.resultJson)
            else -> null
        }
    }.filter { it.isNotBlank() }

    /**
     * Reconstruct the materials obtained by the runtime checker according to the evidence ledger: the
     * screen reread after finish is not recorded, and is replaced by the last screen read; add the
     * entries selected by the answer in the ledger ([RunFacts.ledger]) - use the same selection method
     * as [Verifier] at runtime.
     */
    fun ledgerSources(events: List<RunEvent>, v: Int, f: Int, goal: String, answer: String): List<String> {
        val seen = before(events, v, f)
        val fresh = seen.filterIsInstance<RunEvent.Observed>().lastOrNull { it.kind == "read" }?.text?.takeIf { it.isNotBlank() }
        return listOfNotNull(fresh) + RunFacts.ledger(seen, RECORDED_TOOLS).forAnswer(goal, answer).map { it.text }
    }

    /** The answer that was checked: When the answer is the ending of the task, the full text in task_ended is taken (the answer in the reply may be truncated). */
    fun answer(events: List<RunEvent>, f: Int): String? {
        val r = events[f] as RunEvent.ModelReply
        val last = events.indexOfLast(RunFacts::isFinalAnswer) == f
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.takeIf { last && r.tool == null && it.outcome == "succeeded" }
        return ended?.answerOrReason ?: RunFacts.answerOf(r)
    }

    fun arg(json: String?, key: String): String? = runCatching {
        ((Json.parseToJsonElement(json.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }
}
