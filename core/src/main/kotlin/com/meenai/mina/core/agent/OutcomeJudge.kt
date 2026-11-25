package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Is it possible to calculate the result that the user wants in one run? A reply without a call is an
 * answer, and the answer itself may truthfully say that it was not completed or only partially
 * completed; learning things (route, skills, skill results), timed task results and running review all
 * follow this ending. If you do not press "Submit Answer".
 */
enum class RunOutcome(val wire: String) {
    Done("done"), Partial("partial"), NotPossible("not_possible"),
    /** Not successful (model error, timeout, unreadable reply), or old record; it should not be completed or failed. */
    Unknown("unknown");

    companion object {
        fun ofWire(s: String?): RunOutcome? = entries.firstOrNull { it.wire == s }

        /** The ending judged in the running record; if there is no such event (old record, failure, cancellation), it is null. */
        fun recorded(events: List<RunEvent>): RunOutcome? =
            events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.let { ofWire(it.outcome) ?: Unknown }
    }
}

data class JudgedOutcome(val outcome: RunOutcome, val reason: String = "", val model: String? = null) {
    fun event(atMs: Long) = RunEvent.OutcomeJudged(atMs, outcome.wire, RunEvent.clip(Redactor.text(reason)), model)
}

/**
 * After the answer is given to the user, a model is requested in the background (lightweight model is
 * preferred, the main model is not used) to give the answer and determine the outcome according to the
 * user's requirements. Do not change the answer, do not delay delivery; errors or timeouts will be
 * [RunOutcome.Unknown], never thrown (will be thrown out as usual if cancelled).
 */
class OutcomeJudge(
    private val judge: ChatModel,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun judge(goal: String, answer: String, priorTurns: List<Turn> = emptyList()): JudgedOutcome {
        val prior = Conversation.context(priorTurns, maxTurns = 2, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            // The request and reply are materials to be judged and are included in the label; the words
            // written inside are not instructions to the judge.
            append("<request>\n").append(untag(goal)).append("\n</request>\n\n<reply>\n").append(untag(answer)).append("\n</reply>")
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback?.takeUnless { it === judge })) {
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = candidate.complete(Prompts.role("outcome_judge"), messages, emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result.copy(model = candidate.name.ifBlank { null })
        }
        return JudgedOutcome(RunOutcome.Unknown)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        private val TAG = Regex("</?\\s*(request|reply)\\s*>", RegexOption.IGNORE_CASE)

        /** Remove the request/reply tags that come with the material to prevent it from closing in advance and pretending that the following words are the input of the judge. */
        private fun untag(s: String) = s.replace(TAG, "")

        /**
         * The last readable JSON object in the reply: {"outcome": "done|partial|not_possible", "reason":
         * "..."}; if not readable, it is null. Take the last one: the object brought out by the judge
         * when retelling the material is at the front, and the conclusion is at the end.
         */
        fun parse(raw: String): JudgedOutcome? {
            var end = raw.lastIndexOf('}')
            while (end >= 0) {
                var start = raw.lastIndexOf('{', end)
                while (start >= 0) {
                    of(raw.substring(start, end + 1))?.let { return it }
                    start = if (start == 0) -1 else raw.lastIndexOf('{', start - 1)
                }
                end = if (end == 0) -1 else raw.lastIndexOf('}', end - 1)
            }
            return null
        }

        private fun of(json: String): JudgedOutcome? {
            val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val outcome = RunOutcome.ofWire((o["outcome"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase())
                ?.takeIf { it != RunOutcome.Unknown } ?: return null
            return JudgedOutcome(outcome, (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
        }
    }
}
