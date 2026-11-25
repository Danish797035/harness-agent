package com.meenai.mina.core.uistate.tools

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.Verification
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.uistate.DESCRIBED_ELEMENT
import com.meenai.mina.core.uistate.DESCRIBED_ROLE
import com.meenai.mina.core.tool.COORD
import com.meenai.mina.core.tool.LOCATED_REF
import com.meenai.mina.core.tool.REF
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.SNAP
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.bool
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.okObj
import com.meenai.mina.core.tool.str
import com.meenai.mina.core.tool.switchHint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.meenai.mina.core.uistate.action
import com.meenai.mina.core.uistate.lastOrSnapshot
import com.meenai.mina.core.uistate.locatedInfo
import com.meenai.mina.core.uistate.toPx
import com.meenai.mina.core.uistate.lastSnapshot
import com.meenai.mina.core.uistate.screen
import com.meenai.mina.core.uistate.withPortRef

private const val KEYCODE_ENTER = 66

/** Enter and clear the additional parameters: the coordinates of a point (ref can also be @vN found by looking at the picture). When the tree cannot read the input box, type directly at that point. */
private val POINT_ARGS = mapOf(
    "x" to int("Instead of a ref: the input box's point (e.g. a visibleText center), tapped before typing. $COORD", 0, 999),
    "y" to int(COORD, 0, 999),
)

/** Where to type: an element (number) in the tree, or a point (pixel; [located] is found by looking at the picture @vN, not null). */
private sealed interface FieldAim {
    data class Ref(val ref: Pair<String, String>?) : FieldAim
    data class Point(val x: Int, val y: Int, val located: String?) : FieldAim
}

/**
 * Determine where to type according to the parameters: when no point is given, it is the number (or the
 * frame where the focus is); when coordinates or @vN are given, it is the point, and @vN must match the
 * picture when found. Unusable @vN Failure results returned to the model.
 */
private suspend fun aim(a: Args, ctx: ToolContext): Pair<FieldAim?, ToolExecution?> {
    val ref = a.ref(located = true)
    val x = a.coord("x")
    val y = a.coord("y")
    if (ref != null && (x != null || y != null)) throw ArgError("x", "give either ref+snapshotId or x+y, not both")
    if ((x == null) != (y == null)) throw ArgError(if (x == null) "x" else "y", "x and y go together")
    if (ref != null && LOCATED_REF.matches(ref.first)) {
        val p = locatedPixel(ctx, ref.first, ref.second) ?: return null to fail("ref_expired", Feedback["located_stale"])
        return FieldAim.Point(p.first, p.second, ref.first) to null
    }
    if (x != null && y != null) {
        val s = ctx.lastOrSnapshot()
        return FieldAim.Point(toPx(x, s.width), toPx(y, s.height), null) to null
    }
    return FieldAim.Ref(ref) to null
}

/** Type on the points you find when looking at the picture. When clearing, write down what you are looking for in the results; mark the points that are not supported. */
private fun located(ctx: ToolContext, aim: FieldAim?, r: ToolExecution): ToolExecution {
    val ref = (aim as? FieldAim.Point)?.located ?: return r
    val info = ctx.locatedInfo[ref] ?: return r
    val extra = listOfNotNull(info.target?.let { "located" to JsonPrimitive(it) }, ("unverifiedPoint" to JsonPrimitive(true)).takeIf { info.unverified })
    return if (extra.isEmpty()) r else r.with(content = JsonObject(r.content + extra))
}

/** Input (it may be sent when you bring it back to the carriage, it must be determined by the target to determine whether it is irreversible). */
internal class TypeText(f: ScreenFamily) : ScreenTool(f, "type_text") {
    override val verb = Verb.TypeText
    override val batchable = true
    override val routable = f.routable
    override val label = "Type text"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Enter
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "text" to buildJsonObject { put("type", "string"); put("description", "Text to enter, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "pressEnter" to bool("Press Enter after typing (default false)."),
            "ref" to str(REF), "snapshotId" to str(SNAP),
            DESCRIBED_ELEMENT to str("Instead of ref+snapshotId: the input field's label, accessible name or placeholder, as shown on the screen. It must match exactly one input field on the screen right now."),
            DESCRIBED_ROLE to str(DESCRIBE_ROLE),
        ) + POINT_ARGS, listOf("text")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an input field here; use ref+snapshotId")
        val text = a.str("text", max = 10000)!!
        val enter = a.bool("pressEnter", false)
        val (target, failed) = aim(a, ctx)
        failed?.let { return it }
        return when (target) {
            is FieldAim.Point -> located(ctx, target, typeInto(ctx, text, enter) { ctx.screen.typeAt(target.x, target.y, text) })
            else -> ctx.withPortRef((target as FieldAim.Ref).ref) { ref -> typeInto(ctx, text, enter) { ctx.screen.typeText(ref?.first, ref?.second, text) } }
        }
    }

    private suspend fun typeInto(ctx: ToolContext, text: String, enter: Boolean, type: suspend () -> ActionResult): ToolExecution {
        val screen = ctx.screen
        val typed = type()
        if (typed !is ActionResult.Ok) return ctx.action(typed)
        val v = typed.verification
        if (v == Verification.MISMATCH) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_not_applied"); put("verification", v.wire)
            put("message", Feedback["type.not_applied"])
            put("recoverable", true)
        })
        val switched = typed.appSwitched
        // If you can't read it back, don't press Enter: the content that hasn't been typed may be
        // submitted; when the front desk is switched away, pressing Enter will be typed in other apps.
        val pressEnter = enter && v != Verification.UNKNOWN && switched == null
        if (pressEnter) {
            val k = screen.pressKey(KEYCODE_ENTER)
            if (k !is ActionResult.Ok) return ctx.action(k)
        }
        return ToolExecution(okObj {
            put("typed", text.length); put("pressedEnter", pressEnter)
            put("secret", typed.secret)
            if (typed.relocated) put("relocated", true)
            typed.relocatedTo?.takeIf { typed.relocated }?.let { put("relocatedTo", it) }
            v?.let { put("verification", it.wire) }
            if (enter && !pressEnter) put("enterSkipped", true)
            switched?.let { put("app_switched", it) }
            val hints = listOfNotNull(
                typed.hint,
                (if (enter) Feedback.format("type.unverified_enter", "unverified" to Feedback["type.unverified"]) else Feedback["type.unverified"])
                    .takeIf { v == Verification.UNKNOWN },
                switched?.let { switchHint(it, typed.switchedByTarget).let { h -> if (enter) Feedback.format("type.switched_enter", "switch" to h) else h } },
            )
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = typed.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = refTarget(o, r, ctx.lastSnapshot) ?: pointTarget(o)

    // Only when the port clearly states that it is not a password box will the entered words be written.
    override fun logTyped(o: JsonObject, r: ToolExecution) =
        o.argStr("text")?.takeIf { r.content["secret"] == JsonPrimitive(false) }?.let { "「${it.take(40)}」" }
}

internal class ClearText(f: ScreenFamily) : ScreenTool(f, "clear_text") {
    override val verb = Verb.ClearText
    override val batchable = true
    override val routable = f.routable
    override val label = "Clear field"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe(),
        mapOf("ref" to str(REF), "snapshotId" to str(SNAP)) + POINT_ARGS) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val (target, failed) = aim(a, ctx)
        failed?.let { return it }
        return when (target) {
            is FieldAim.Point -> located(ctx, target, ctx.action(ctx.screen.clearAt(target.x, target.y)))
            else -> ctx.withPortRef((target as FieldAim.Ref).ref) { r -> ctx.action(ctx.screen.clearText(r?.first, r?.second)) }
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = refTarget(o, r, ctx.lastSnapshot) ?: pointTarget(o)
}

/** The object written in the action list when typing on the coordinate point and clearing it. */
private fun pointTarget(o: JsonObject): String? = o["x"]?.let { x -> "${x},${o["y"]}" }
