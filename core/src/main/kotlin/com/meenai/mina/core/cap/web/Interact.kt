package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.CdpKeys
import com.meenai.mina.core.uistate.KeyModifier
import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.Resolved
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.PAGE_REF_RE
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.failCode
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.str
import com.meenai.mina.core.uistate.toPx
import kotlinx.serialization.json.JsonObject
import com.meenai.mina.core.uistate.action
import com.meenai.mina.core.uistate.lastOrSnapshot
import com.meenai.mina.core.uistate.lastSnapshot
import com.meenai.mina.core.uistate.session
import com.meenai.mina.core.uistate.withPortRef
import com.meenai.mina.core.uistate.DwellStop
import com.meenai.mina.core.uistate.dwellStopped

/** Copy, cut, paste. */
private val CLIPBOARD_KEYS = setOf("KeyC", "KeyX", "KeyV")

/**
 * web_interact: Verify parameters by action (extra parameters will report an error and will not be
 * ignored silently), coordinates will be replaced by view pixels based on the latest screen, and when
 * stable numbering is on, the numbers at both ends will be translated into port numbers first. Key
 * combinations, dragging, and drop-down selections may trigger submission, which must be determined by
 * the target to determine whether it is irreversible.
 */
internal object Interact : WebTool {
    override val verb = Verb.Interact
    override val risk = Risk.External
    override val gateTarget = GateTarget.Interact
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def = ToolDef("web_interact",
        Prompts.tool("web_interact"),
        mapOf(
            "action" to str("What to do.", listOf("hover", "drag", "select", "key", "dialog")),
            "ref" to str("Element from the latest web_observe/web_list_elements: hover target, drag start, or the drop-down for select."),
            "snapshotId" to str("snapshotId of that web_observe/web_list_elements result (also used for toRef)."),
            "x" to int("Instead of ref for hover/drag: point 0-999 across the screen.", 0, 999),
            "y" to int("Instead of ref for hover/drag: point 0-999 down the screen.", 0, 999),
            "toRef" to str("drag: element to release over."),
            "toX" to int("drag: release point 0-999 across, instead of toRef.", 0, 999),
            "toY" to int("drag: release point 0-999 down, instead of toRef.", 0, 999),
            "steps" to int("drag: mouse moves between press and release, 1-$DRAG_STEPS_MAX (default $DRAG_STEPS_DEFAULT).", 1, DRAG_STEPS_MAX),
            "option" to str("select: the option's shown text or its value."),
            "answer" to str("dialog: accept (OK) or dismiss (Cancel) the page's open dialog.", listOf("accept", "dismiss")),
            "text" to str("dialog: text to enter into a prompt dialog before accepting (default: its prefilled text)."),
            "key" to str("key: one key (Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp/Down/Left/Right, Home, End, PageUp, PageDown, a letter or digit), optionally after Control+, Shift+, Alt+ or Meta+."),
        ), listOf("action"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action")!!
        val allowed = when (action) {
            "hover" -> setOf("ref", "snapshotId", "x", "y")
            "drag" -> setOf("ref", "snapshotId", "x", "y", "toRef", "toX", "toY", "steps")
            "select" -> setOf("ref", "snapshotId", "option")
            "key" -> setOf("key")
            "dialog" -> setOf("answer", "text")
            else -> throw ArgError("action", "must be hover, drag, select, key or dialog")
        }
        a.o.keys.firstOrNull { it != "action" && it != "reason" && it !in allowed }?.let { throw ArgError(it, "not used by action $action") }
        val w = ctx.web!!
        return when (action) {
            "dialog" -> {
                val answer = a.str("answer") ?: throw ArgError("answer", "required for action dialog: accept or dismiss")
                if (answer != "accept" && answer != "dismiss") throw ArgError("answer", "must be accept or dismiss")
                ctx.action(w.answerDialog(answer == "accept", a.str("text", max = 2000)))
            }
            "key" -> {
                val spec = a.str("key", max = 60) ?: throw ArgError("key", "required for action key")
                val combo = CdpKeys.parseCombo(spec) ?: throw ArgError("key", "not a supported key or combination: '$spec'. Use names like Enter, Escape, Tab, ArrowDown, Control+A, Shift+Tab")
                // Clipboard shortcut keys will transfer the contents of the user's clipboard to the web
                // page (or change the clipboard): Don't do it
                if (combo.modifiers.any { it == KeyModifier.Control || it == KeyModifier.Meta } && combo.key.code in CLIPBOARD_KEYS) {
                    throw ArgError("key", "clipboard shortcuts (copy, cut, paste) are not allowed; use web_type_text to enter text")
                }
                // Keys such as Enter can submit and page change: just like clicking, first make up for
                // the stay on this page.
                try { ctx.dwell?.before(port = w) } catch (e: DwellStop) { return dwellStopped(e) }
                ctx.action(w.pressCombo(combo))
            }
            "select" -> {
                val ref = a.ref() ?: throw ArgError("ref", "required for action select")
                val option = a.str("option", max = 200) ?: throw ArgError("option", "required for action select")
                ctx.withPortRef(ref) { r -> ctx.action(w.selectOption(r!!.first, r.second, option)) }
            }
            "hover" -> {
                val at = point(a, ctx, "ref", "x", "y") ?: throw ArgError("ref", "give ref+snapshotId or x+y")
                when (val p = portPoint(at, ctx)) { null -> ctx.action(ActionResult.Failed("ref_expired")); else -> ctx.action(w.hover(p)) }
            }
            else -> {
                val from = point(a, ctx, "ref", "x", "y") ?: throw ArgError("ref", "give ref+snapshotId or x+y for where the drag starts")
                val to = point(a, ctx, "toRef", "toX", "toY") ?: throw ArgError("toRef", "give toRef (with snapshotId) or toX+toY for where the drag ends")
                val steps = a.int("steps", 1, DRAG_STEPS_MAX) ?: DRAG_STEPS_DEFAULT
                val pf = portPoint(from, ctx); val pt = portPoint(to, ctx)
                if (pf == null || pt == null) ctx.action(ActionResult.Failed("ref_expired")) else ctx.action(w.drag(pf, pt, steps))
            }
        }
    }

    /** A landing point: number ([refKey], shared by snapshotId) or 0-999 coordinates; if both are given, an error will be reported if only half of the coordinates are given, and if neither is given, it will be null. */
    private suspend fun point(a: Args, ctx: ToolContext, refKey: String, xKey: String, yKey: String): WebPoint? {
        val ref = a.str(refKey)
        val x = a.coord(xKey); val y = a.coord(yKey)
        if (ref != null) {
            if (x != null || y != null) throw ArgError(xKey, "give either $refKey or $xKey+$yKey, not both")
            if (!PAGE_REF_RE.matches(ref)) throw ArgError(refKey, "must look like @e3")
            val sid = a.str("snapshotId") ?: throw ArgError("snapshotId", "required when $refKey is given")
            return WebPoint.Ref(ref, sid)
        }
        if ((x == null) != (y == null)) throw ArgError(if (x == null) xKey else yKey, "give both $xKey and $yKey")
        if (x == null) return null
        val s = ctx.lastOrSnapshot()
        return WebPoint.Xy(toPx(x, s.width), toPx(y!!, s.height))
    }

    /** When stable numbering is on, translate the model number into a port number; retired to null (ref_expired). */
    private fun portPoint(p: WebPoint, ctx: ToolContext): WebPoint? {
        val st = ctx.session.stabilizer
        if (p !is WebPoint.Ref || st == null) return p
        val r = st.resolve(p.ref, p.snapshotId) as? Resolved.Current ?: return null
        return WebPoint.Ref(r.portRef, r.portSnapshotId)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("action"), o.argStr("answer"), o.argStr("key") ?: o.argStr("option")?.let { "「${it.take(40)}」" },
            o.argStr("ref")?.let { ref -> ctx.lastSnapshot?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref },
            o.argStr("toRef")?.let { "→ $it" }).joinToString(" ")
}
