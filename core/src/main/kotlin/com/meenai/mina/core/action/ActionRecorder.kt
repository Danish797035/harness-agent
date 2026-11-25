package com.meenai.mina.core.action

import com.meenai.mina.core.gate.Irreversible
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.meenai.mina.core.tool.Verb

/**
 * Action running records: tool results, confirmation levels, and screens. The screenshot numbers are
 * posted here (the screenshots you save while looping are also numbered here). Failure to record does
 * not affect the task. When [saveScreen] is null, the sub-step screen will not be saved (the number will
 * not be sent).
 */
class ActionRecorder(
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val saveScreen: ((Int, ByteArray) -> Unit)? = null,
) {
    private var shots = 0

    val savesScreens: Boolean get() = saveScreen != null

    fun now(): Long = clock()

    fun nextShot(): Int = ++shots

    fun event(e: RunEvent) {
        try {
            sink.event(e)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            // Recording failure does not affect the task
        }
    }

    fun confirmation(hit: Irreversible, decision: String) =
        event(RunEvent.Confirmation(clock(), hit.action.name, RunEvent.clip(Redactor.text(hit.matched)), null, null, decision))

    /**
     * The location of the evidence ledger before the last action that changed the status (changing
     * things on the interface, external, irreversible, or writing operations in the sandbox; reporting
     * failure and not saying the interface has changed will not be counted); it has not been re-read
     * since, please check again before submitting the answer (see AnswerReview). There is no such action
     * yet for null.
     */
    var lastChangeMark: Int? = null
        private set

    fun noteChange(ledgerMark: Int) { lastChangeMark = ledgerMark }

    /** This task allows several external or irreversible actions (sending, submitting, placing an order, deleting...); you can decide whether to check the model based on this before submitting the answer. */
    var outwardActs = 0
        private set

    fun approval(origin: Origin, tool: String, hit: Irreversible, risk: Risk, decision: String) {
        if (decision == "allowed" && risk.level.outward) outwardActs++
        event(RunEvent.Approval(clock(), origin.step, origin.policy.source, tool, hit.action.name, RunEvent.clip(Redactor.text(hit.matched)),
            risk.level.name, risk.payment, decision))
    }

    /** The result of a call; [content] is the one handed to the model (or the caller), [extra] is the field that only enters the running record. */
    fun result(
        step: Int, tool: String, verb: Verb?, content: JsonObject, extra: JsonObject, changed: Boolean?, shotId: Int?, durationMs: Long,
        source: String, callId: String, waitMs: Long = 0,
    ) {
        // The user's answer may be a verification code, which will not be entered into the running
        // record.
        val shown = if (verb == Verb.AskUser && content.containsKey("answer")) JsonObject(content + ("answer" to JsonPrimitive(HIDDEN_ANSWER))) else content
        val recorded = Redactor.result(verb, shown).let { if (extra.isEmpty()) it else JsonObject(it + extra) }
        event(RunEvent.ToolResult(clock(), step, tool, RunEvent.clipResult(Redactor.text(recorded.toString())), changed, shotId, durationMs,
            waitMs = waitMs, source = source, callId = callId))
    }

    /** Save a sub-step picture on the spot and return the screenshot number; it is null when no picture is saved. */
    fun saveFrame(png: ByteArray): Int? {
        val save = saveScreen ?: return null
        val id = nextShot()
        try {
            save(id, png)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            // Same as above
        }
        return id
    }

    private companion object {
        const val HIDDEN_ANSWER = "(用户的回答,不记录)"
    }
}
