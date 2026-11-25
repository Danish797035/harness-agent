package com.meenai.mina.core.eval

import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.verify.EvidenceLedger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb

/** Get the facts for evaluation from a run event. */
object RunFacts {
    /**
     * Reconstruct the evidence ledger of this task from the running record: the same runtime checker
     * [EvidenceLedger] is used, and the fields recorded by each tool are the same as the runtime (see
     * [RecordedEvidence]).
     */
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger = RecordedEvidence.ledger(events, tools)

    /** The evidence used by the evaluation to check the answers: the full text of the entries selected according to the answers in the ledger, read one at a time (the same as the selection method given to the verifier at runtime). */
    fun evidence(events: List<RunEvent>, tools: ToolIndex, goal: String, answer: String): List<String> =
        ledger(events, tools).forAnswer(goal, answer).map { it.text }

    /** The name of the App package where the Agent has opened or read the interface, sorted by first occurrence. */
    fun appsSeen(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        when {
            e is RunEvent.Observed && e.kind == READ -> appOf(e)
            e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp -> arg(e, "package")
            else -> null
        }
    }.filter { it.isNotBlank() }.distinct()

    /** Apps opened by Agent using launch_app are sorted by first opening (after running, `{app:<capability>}` determines which App is used). */
    fun appsLaunched(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp) arg(e, "package") else null
    }.filter { it.isNotBlank() }.distinct()

    /**
     * The reason for handing over to the user to take over must have on-screen evidence: the most recent
     * barrier report (barrier event) before entering the takeover for the first time, there was no other
     * action between the two except applying for takeover, and this barrier can be seen on the last
     * screen read before the report ([BarrierSignals]). Only what the model says and cannot be seen on
     * the screen does not count.
     */
    fun takeoverBarrier(events: List<RunEvent>, tools: ToolIndex): String? {
        val t = events.indexOfFirst { it is RunEvent.State && it.name == TAKEOVER_STATE }
        if (t < 0) return null
        val b = (t - 1 downTo 0).firstOrNull { events[it] is RunEvent.Barrier } ?: return null
        if ((b + 1 until t).any { val e = events[it]; e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) != Verb.HandToUser }) return null
        val kind = (events[b] as RunEvent.Barrier).kind
        val read = (b - 1 downTo 0).map { events[it] }.firstOrNull { it is RunEvent.Observed && it.kind == READ } as? RunEvent.Observed ?: return null
        return kind.takeIf { BarrierSignals.shows(it, read.text.lines()) }
    }

    /**
     * The App in the foreground when the Agent is typing (the App where the last interface read before
     * typing is located), sorted by first appearance: in which App the things created by writing type
     * questions are located. A webpage is not an app.
     */
    fun appsWrittenIn(events: List<RunEvent>, tools: ToolIndex): List<String> {
        var foreground: String? = null
        val out = mutableListOf<String>()
        for (e in events) {
            if (e is RunEvent.Observed && e.kind == READ) foreground = appOf(e)
            if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.TypeText) foreground?.takeIf { PACKAGE.matches(it) }?.let { out += it }
        }
        return out.distinct()
    }

    /**
     * This reply is the final answer given to the user: a reply without a call and with words; in the
     * old record, it was a finish call (the answer was through the tool at that time). The recovery of
     * reassigned lightweight models does not count.
     */
    fun isFinalAnswer(e: RunEvent): Boolean =
        e is RunEvent.ModelReply && e.acted && (e.tool == LEGACY_FINISH || (e.tool == null && e.text.isNotBlank()))

    /** The original text of the final answer: The old record takes the answer parameter of finish, and the new record takes the words of the reply (the long ones in the record are truncated; the complete ones are in task_ended). */
    fun answerOf(r: RunEvent.ModelReply): String? = if (r.tool == LEGACY_FINISH) arg(r, "answer") else r.text.takeIf { it.isNotBlank() }

    /**
     * In this task, external or irreversible actions (send, submit, place an order, delete...; according
     * to the risk level in the approval record) have been released, or payment has been handed over to
     * the user: For such tasks, please check the model before answering and handing over.
     */
    fun actedOutward(events: List<RunEvent>): Boolean =
        events.any { it is RunEvent.Approval && it.decision == "allowed" && it.risk in OUTWARD_RISKS } ||
            events.any { it is RunEvent.ModelReply && it.acted && it.tool == "hand_to_user" && arg(it, "reason") == "payment" }

    /** The name of the tool that submitted the result in the old record (the reply without the call is now the answer). */
    const val LEGACY_FINISH = "finish"
    private val OUTWARD_RISKS = com.meenai.mina.core.action.ActionRisk.entries.filter { it.outward }.map { it.name }.toSet()

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val READ = ObsKind.Read.wire

    /** The app in which this observation was read; null for web pages. */
    private fun appOf(e: RunEvent.Observed): String? = e.place?.takeIf { it.kind == PlaceKind.App }?.name

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * Stopped at the confirmation level: the last confirmation event was rejection (automatic rejection
     * in the review), and then the task ended by itself (not canceled). It does not count if you apply
     * again after rejection, if it is approved, or if it is never completed.
     */
    fun stoppedAtGate(events: List<RunEvent>): Boolean {
        val i = events.indexOfLast { it is RunEvent.Confirmation && it.decision != "required" && it.decision != "mismatch" }
        val last = events.getOrNull(i) as? RunEvent.Confirmation ?: return false
        if (last.decision != "declined" && last.decision != "declined_before") return false
        return events.drop(i + 1).any { it is RunEvent.TaskEnded && it.outcome != "cancelled" }
    }


    private fun arg(r: RunEvent.ModelReply, key: String): String? = runCatching {
        ((Json.parseToJsonElement(r.argsJson.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}
