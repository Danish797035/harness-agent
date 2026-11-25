package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role

/**
 * A conversation: what the user said and what the outcome was. [outcome] is the answer when successful
 * or the reason for failure; [status] is succeeded / unverified (it was completed but could not be
 * verified, [note] is the reason, [unconfirmed] is several places marked as unconfirmed) / failed /
 * canceled. [runId] is the running record of this round; if there is no record or earlier rounds, it is
 * null.
 */
/**
 * [memoryNotes]/[usedMemory]/[skillNotes]: The memory and skill tips under the result card will be added
 * after the task is completed; the upper part of the model will not be fed. [result]: The ending that is
 * judged after handing in the answer ([RunOutcome.wire]) is added in the background; if it is not
 * judged, it is null.
 */
data class Turn(
    val userText: String, val outcome: String, val status: String, val note: String? = null, val runId: String? = null,
    val memoryNotes: List<com.meenai.mina.core.memory.MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.meenai.mina.core.skills.SkillNote> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val result: String? = null,
    /** If the user inserts the task while the task is in progress and has handed over the task (the interface will be displayed under that round). */
    val steers: List<String> = emptyList(),
    /** One-to-one correspondence with [steers]: the words in the reply that are interrupted by each interruption are the words that the user has already seen, and the uninterrupted ones are empty strings. Only the interface is displayed, and the upper part of the model is not fed. */
    val interruptedReplies: List<String> = emptyList(),
    /** One-to-one correspondence with [steers]: before each interjection is handed over to the model, the reply that has been finished and displayed is an empty string. Only displayed on the interface. */
    val finishedReplies: List<String> = emptyList(),
    /** The ability groups that have been really used in this round (mobile phone, web page, sandbox...); the next round will be started in advance, and the upper part of the model will not be fed. */
    val groups: List<String> = emptyList(),
    /** The page of the site that will be left in the background to continue running after the task is completed (see web_keep_open); if not left, it will be null. */
    val keptPage: String? = null,
    /** The reason why the page left was closed later ([com.meenai.mina.core.cap.web.KeptPageClose.wire]); it is still open or not left as null. */
    val keptPageClosed: String? = null,
)

object Conversation {
    const val HEADER = "Earlier turns of this conversation (for reference; what you read this time shows the current state):"

    /** What I wrote down when there was no explanation for the round that ended (or there was nothing to explain). */
    const val CANCELLED_OUTCOME = "已取消"

    /** The account to be filled in the round that ended; null if there is none. */
    fun stopSummary(t: Turn): String? =
        t.outcome.trim().takeIf { t.status == "cancelled" && it.isNotEmpty() && it != CANCELLED_OUTCOME }

    /**
     * Make the previous rounds into a reference message for the user role, with the latest one at the
     * end; if the entire message exceeds [maxTokens] (by [TokenEstimate]), discard the earlier rounds.
     * If there is only one round left and it is too long, each field will be truncated separately (one
     * paragraph will be left for the user's original words and the ending). If it is still too long, the
     * entire paragraph will be truncated. Returns null if there are no rounds.
     */
    fun context(turns: List<Turn>, maxTurns: Int = 5, maxTokens: Int = 2_500): Message? {
        var recent = turns.takeLast(maxTurns.coerceAtLeast(0))
        if (recent.isEmpty()) return null
        val header = "$HEADER\n"
        fun render(ts: List<Turn>, clip: Int) = ts.mapIndexed { i, t -> line(i + 1, t, clip) }.joinToString("\n")
        while (recent.size > 1 && TokenEstimate.of(header + render(recent, Int.MAX_VALUE)) > maxTokens) recent = recent.drop(1)
        var body = render(recent, Int.MAX_VALUE)
        val room = (maxTokens - TokenEstimate.of(header)).coerceAtLeast(20)
        if (TokenEstimate.of(header + body) > maxTokens) {
            // There is only one round left and it is too long: each field is truncated, leaving one
            // paragraph each for the original story and the ending.
            body = render(recent, room / 3)
            if (TokenEstimate.of(header + body) > maxTokens) body = TokenEstimate.clip(body, room, "…")
        }
        return Message(Role.User, listOf(Part.Text("$HEADER\n$body")), Author.Mina)
    }

    private fun line(n: Int, t: Turn, clip: Int): String {
        // The answer is to tell the truth that it was not done or only partially done: According to the
        // outcome of handing over the judgment, don’t treat it as done in the next round.
        val answered = t.status == "succeeded" || t.status == "unverified"
        val result = when {
            answered && t.result == RunOutcome.Partial.wire -> "only partly done: ${t.outcome.clip(clip)}"
            answered && t.result == RunOutcome.NotPossible.wire -> "not done: ${t.outcome.clip(clip)}"
            t.status == "succeeded" -> "result: ${t.outcome.clip(clip)}"
            t.status == "unverified" -> "result (could not be verified): ${t.outcome.clip(clip)}"
            // Bring along the explanations you made up after you stopped (what you have done and what
            // you have not done). Only when you say "Continue" will you know where to start; the old
            // record only has "Cancelled"
            t.status == "cancelled" -> stopSummary(t)?.let { "the user stopped this task partway; before the stop: ${it.clip(clip)}" } ?: "the user cancelled this task"
            else -> "not finished, reason: ${t.outcome.clip(clip)}"
        }
        // The page left after the mission is over: When you are asked "Is it still playing" in the next
        // round, you will know whether it is still on or off.
        val kept = t.keptPage?.let { host ->
            if (t.keptPageClosed == null) "; after it Harness Agent kept the page on $host running in the background"
            else "; Harness Agent kept the page on $host running after it, and that page has since been closed"
        }.orEmpty()
        return "$n. The user said: ${t.userText.clip(clip)}; $result$kept"
    }

    /** Up to [max] tokens (including the "..." at the end). */
    private fun String.clip(max: Int) = if (max == Int.MAX_VALUE || TokenEstimate.of(this) <= max) this else TokenEstimate.clip(this, max, "…")
}
