package com.meenai.mina.core.action

import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.gate.Irreversible
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.tool.ToolExecution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The ending of the execution entrance is completed in one action. */
sealed interface ActionOutcome {
    /** Guard conclusion before execution. */
    val before: List<GuardVerdict>

    /**
     * Not implemented. [code]: guard_stopped / guard_warning / confirmation_required /
     * confirmation_mismatch / approval_unavailable / target_unresolved / own_app. [content] is the
     * result handed over to the caller this time (script sub-steps and model steps are returned
     * according to it; batch and playback are described by [code]).
     */
    data class Refused(
        val code: String, val content: JsonObject, override val before: List<GuardVerdict>, val hit: Irreversible? = null,
    ) : ActionOutcome

    /**
     * Executed. [exec] is the final result (the model step may have been replaced by waiting for the
     * user's subsequent results or verification and return); [after] is the guard conclusion after
     * execution (the sub-step conclusions of the page script are ranked first); [content] is the content
     * handed over to the model and recorded in the running record. [shotId], [picture]: [Frames.Caller]
     * is a screen left for the caller to save; [seen] is a screen read after the action when recording
     * the route anchor point (nothing is read as null).
     */
    data class Ran(
        val exec: ToolExecution, override val before: List<GuardVerdict>, val after: List<GuardVerdict>, val content: JsonObject,
        val durationMs: Long, val shotId: Int? = null, val picture: ByteArray? = null, val seen: ScreenSnapshot? = null,
    ) : ActionOutcome {
        val verdicts: List<GuardVerdict> get() = before + after
    }

    /** The task was canceled while waiting for the user. */
    data class Cancelled(override val before: List<GuardVerdict>) : ActionOutcome
}

/** The result of this call to the caller: if it is executed, it is the execution result, if it is not executed, it is a rejection statement. */
fun ActionOutcome.execution(): ToolExecution = when (this) {
    is ActionOutcome.Ran -> exec
    is ActionOutcome.Refused -> ToolExecution(content)
    is ActionOutcome.Cancelled -> ToolExecution(buildJsonObject { put("ok", false); put("error", "cancelled"); put("executed", false) })
}

/** Instructions given to the caller when stopped, written as the source says. */
internal object Refusals {
    const val SINGLE_USE = "One approval lets through only the next matching action, and that action uses it up whether it succeeds or not."
    const val OWN_APP = "own_app"
    const val PAYMENT_TAKEOVER = "The user completes payment with their own hands; do not use request_confirmation: after opening the payment page, call hand_to_user(reason=payment), " +
        "with item as the product (variant, quantity), amount as the amount due shown on screen, and address as the delivery address."

    fun content(code: String, style: RefusalStyle, before: List<GuardVerdict>, hit: Irreversible?, reason: String?): JsonObject = when (style) {
        // The task ends when the model is stopped by a guard in a single step, and no explanation is
        // needed.
        RefusalStyle.Model -> if (hit == null) notRun(code, if (code == OWN_APP) Feedback["own_app"] else code) else {
            val blocked = if (reason != null) mismatch(hit, reason) else required(hit)
            if (before.isEmpty()) blocked else JsonObject(blocked + ("guard" to JsonArray(before.map { it.toJson() })))
        }
        RefusalStyle.Script -> {
            val message = when (code) {
                "guard_stopped" -> "Harness Agent's operation guard stopped the task."
                "guard_warning" -> "Harness Agent's operation guard flagged this action; it was not run."
                "confirmation_required" -> "this step spends money (${hit?.matched})"
                "confirmation_mismatch" -> "this payment step (${hit?.matched}) does not match what the user approved ($reason)"
                "approval_unavailable" -> "this step spends money and this run cannot get the user's approval; the action was not run"
                "target_unresolved" -> "that ref is not on the latest snapshot; call mina.snapshot() again"
                OWN_APP -> Feedback["own_app"]
                else -> error("unknown refusal $code")
            }
            notRun(code, message) { hit?.let { put("action", it.action.name); put("matched", it.matched) } }
        }
        RefusalStyle.Code -> notRun(code, code)
    }

    private fun required(hit: Irreversible) = buildJsonObject {
        put("ok", false); put("error", "confirmation_required")
        put("action", hit.action.name); put("matched", hit.matched)
        put("message", if (hit.action == IrrevAction.pay) "This step is a payment, so it was not run. $PAYMENT_TAKEOVER"
            else "This step spends money (${hit.action.name}), so it was not run. First call request_confirmation saying where, what is bought and how much it costs, and do it after the user approves. $SINGLE_USE")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun mismatch(hit: Irreversible, reason: String) = buildJsonObject {
        put("ok", false); put("error", "confirmation_mismatch")
        put("action", hit.action.name); put("matched", hit.matched); put("reason", reason)
        put("message", "This step does not match the action the user approved ($reason). That approval is void, and this step was not run. " +
            "If it still needs doing, call request_confirmation again describing this step truthfully, and do it right after the user approves.")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun notRun(error: String, message: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("executed", false)
        extra()
    }
}
