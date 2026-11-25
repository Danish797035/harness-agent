package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.FAILURE_MESSAGES
import com.meenai.mina.core.tool.REF
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.SNAP
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.str
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.WheelOutcome
import com.meenai.mina.core.uistate.action
import com.meenai.mina.core.uistate.screen
import com.meenai.mina.core.uistate.withPortRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turn a scroll wheel selector (time, date, region...) to a value: one call to turn the position in the
 * port (enter if you can, scroll if you can scroll without obstacles, press and drag one grid higher if
 * it doesn't work), there is no need to look at each grid of the model. If it cannot be transferred, the
 * value displayed at this moment will be reported accordingly.
 */
internal object SetWheel : PhoneTool {
    override val verb = Verb.SetWheel
    override val batchable = true
    override val label = "Turn picker wheel"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def = ToolDef("phone_set_wheel", Prompts.tool("phone_set_wheel"), mapOf(
        "ref" to str("$REF The wheel itself, or the item it currently shows."),
        "snapshotId" to str(SNAP),
        "value" to str("The value the wheel should show, as written on it, e.g. 9, 40, 2026, PM, Beijing."),
    ), listOf("ref", "snapshotId", "value"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref() ?: throw ArgError("ref", "required")
        val value = a.str("value", max = 60)!!.trim()
        return ctx.withPortRef(ref) { r ->
            val o = ctx.screen.setWheel(r!!.first, r.second, value)
            if (o.error == "ref_expired" || o.error == "no_app_open") return@withPortRef ctx.action(ActionResult.Failed(o.error))
            result(o, value)
        }
    }

    private fun result(o: WheelOutcome, value: String): ToolExecution {
        val body = buildJsonObject {
            put("ok", o.reached)
            if (!o.reached) {
                val code = when (o.error) {
                    "not_a_wheel" -> "not_a_wheel"
                    "at_end" -> "wheel_at_end"
                    "unknown_value" -> "wheel_unreadable"
                    "not_moving" -> "wheel_not_moving"
                    else -> "wheel_not_reached"
                }
                put("error", code)
                put("message", listOfNotNull(FAILURE_MESSAGES.getValue(code), o.hint).joinToString(" "))
            }
            put("target", value)
            o.value?.let { put("value", it) }
            o.from?.let { put("from", it) }
            put("attempts", o.attempts)
            put("method", o.method)
            if (o.reached) o.hint?.let { put("hint", it) }
        }
        return ToolExecution(body, changed = o.attempts > 0 && o.value != o.from)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = o.argStr("value")
}
