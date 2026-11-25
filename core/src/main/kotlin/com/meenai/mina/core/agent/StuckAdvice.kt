package com.meenai.mina.core.agent

import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.guard.Severity
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Accounting for stuck reminders: how many times the guard has reported "stuck" reminders; how many times each paragraph has been successfully entered in each place (App or website). */
internal data class StuckState(val warnings: Int = 0, val typed: Map<String, Int> = emptyMap())

/**
 * The fact that an action (single step, or a sub-step in a batch) is handed over to the stuck reminder:
 * the guard's conclusion (the action + target is repeated, the screen does not change after the move,
 * read repeatedly when the page does not change), the plain text successfully entered this time
 * ([StuckAdvice.typedText], not input or the password box is null), and where it was at the time.
 */
internal data class StuckInput(val verdicts: List<GuardVerdict>, val typed: String?, val place: String?, val progressed: Boolean = false)

/**
 * Suggestions given to the model when stuck (put into the advice field of the tool result): stuck class
 * reminders give up after searching for the suggestions for the second time; when the same paragraph is
 * entered again in the same place (repeatedly searching for the same things with different words, the
 * guard window cannot see it), it is said that it has been entered several times. The single step and
 * the sub-steps in the batch are performed in the same set. Pure function.
 */
internal object StuckAdvice {
    private val STUCK_CODES = setOf("no_progress", "no_new_state", "read_loop")

    /** Accounting and suggestions to be given after return (null if not). */
    fun next(s: StuckState, input: StuckInput): Pair<StuckState, String?> {
        val warnings = (if (input.progressed) 0 else s.warnings) + input.verdicts.count { it.severity == Severity.Warn && it.code in STUCK_CODES }
        var typed = if (input.progressed) emptyMap() else s.typed
        // Count by "current location + text": searching for the same word in other apps does not count
        // as a duplicate
        val again = input.typed?.let { t ->
            val key = "${input.place}\u0000$t"
            val n = (typed[key] ?: 0) + 1
            typed = typed + (key to n)
            if (n >= 2 && input.verdicts.any { it.code in STUCK_CODES })
                Feedback.format("repeated_input_advice", "text" to t, "times" to n, "answer_or_failure" to ANSWER_OR_FAILURE) else null
        }
        val advice = if (warnings >= 2 && input.verdicts.any { it.code in STUCK_CODES })
            Feedback.format("give_up_advice", "answer_or_failure" to ANSWER_OR_FAILURE) else again
        return StuckState(warnings, typed) to advice
    }

    /**
     * The text successfully entered this time (remove the leading and trailing blanks, empty ones are
     * not counted); if it is not input, it is not successful, or the port does not clearly say "not a
     * password box", it will be null: the original text will be included in the prompt, and the tool
     * results will be entered into the running record.
     */
    fun typedText(verb: Verb?, args: JsonObject, content: JsonObject?): String? {
        if (verb != Verb.TypeText || content?.get("ok") != JsonPrimitive(true) || content["secret"] != JsonPrimitive(false)) return null
        return (args["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
