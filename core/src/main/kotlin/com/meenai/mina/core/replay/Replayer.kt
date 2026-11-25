package com.meenai.mina.core.replay

import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionOutcome
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.uistate.interfaces
import com.meenai.mina.core.tool.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.hypot
import com.meenai.mina.core.tool.Verb

sealed interface ReplayOutcome {
    /** The number of steps completed (or checked). */
    val done: Int

    data class Completed(override val done: Int) : ReplayOutcome
    /**
     * [executed]: Step done+1 is really executed (the interface may have been moved); those that were
     * rejected before execution (parameters, links, expired numbers, App occupied, etc.) are false.
     * [error] is the error code of the tool result at that step, which will be told to the model when it
     * is returned.
     */
    data class Diverged(
        override val done: Int, val expected: String, val seen: String, val executed: Boolean = false, val error: String? = null,
    ) : ReplayOutcome
    /** The next step is an irreversible operation (or it is unclear), stop before it and return the model to go through the normal confirmation process. */
    data class StoppedForConfirmation(override val done: Int) : ReplayOutcome
}

/**
 * Follow the route step by step without adjusting the model. Each step: Lock screen level → Read screen
 * → Light verification (front package name, anchor point before action, unique hit of selector) →
 * Executed by [ActionExecutor] (stop as soon as the guard prompts; stop if it is irreversible or
 * unclear, playback never considers the user to have agreed) → Check the changes after the action. If
 * any part does not match, stop and return the model. The words entered only come from the fixed text in
 * the route and the slot value in the original story.
 */
class Replayer(
    private val tools: ToolDispatcher,
    private val executor: ActionExecutor,
    private val gate: suspend () -> Unit,
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var seq = 0

    suspend fun run(choice: RouteChoice): ReplayOutcome {
        val steps = choice.route.steps
        val total = steps.size
        fun event(i: Int, matched: Boolean, detail: String) =
            emit(RunEvent.Replay(clock(), choice.route.id, i + 1, total, matched, Redactor.text(detail)))

        for ((i, step) in steps.withIndex()) {
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null): ReplayOutcome {
                event(i, false, why)
                return ReplayOutcome.Diverged(i, describe(step, choice.slots) + " ($why)", seen, executed, error)
            }
            val verb = tools.index[step.tool]?.takeIf { it.routable }?.verb ?: return diverged("not a replayable action", "")
            // Wait first when the screen is locked or taken away; read the screen after waiting, check
            // and execute on the screen after waiting (no need to wait for the second time for execution
            // entry)
            gate()
            val snap = read() ?: return diverged("the screen could not be read", "")
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN)
            val seen = summary(snap)
            step.before.packageName?.let { if (snap.packageName != it) return diverged("the foreground app is not $it", seen) }
            val names = snap.elements.map { it.name.trim() }.toSet()
            val need = minOf(2, step.before.texts.size)
            if (step.before.texts.count { it in names } < need) return diverged("the page before the action does not match", seen)

            val args = build(step, choice.slots) ?: return diverged("the slots in the arguments cannot be filled", seen)
            val target = step.target?.let { sel ->
                find(snap, sel, choice.slots) ?: return diverged("the target element is not unique or not found", seen)
            }
            if (verb == Verb.Tap && target == null) return diverged("the tap has no target to check", seen)
            // Only the links that are still in the reference materials will be opened: if the
            // information is changed, the links recorded in the route will no longer be released.
            if (verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!tools.linkAllowed(arg("action"), arg("uri"), arg("package"))) return diverged("the link is no longer in the reference material", seen)
            }
            val call = target?.let { JsonObject(args + mapOf("ref" to JsonPrimitive(it.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))) } ?: args

            val id = "replay-${++seq}"
            val out = when (val o = executor.run(Part.ToolCall(id, step.tool, call.toString()), Origin.Replay)) {
                is ActionOutcome.Ran -> o
                is ActionOutcome.Refused -> when (o.code) {
                    "guard_stopped", "guard_warning" -> return diverged("the action guard raised a warning", seen)
                    "confirmation_required", "confirmation_mismatch" -> {
                        event(i, false, "irreversible action, handed back to the model for confirmation")
                        return ReplayOutcome.StoppedForConfirmation(i)
                    }
                    "target_unresolved" -> return diverged("cannot tell which element the tap would hit", seen)
                    "own_app" -> return diverged("it acts on Mina itself", seen)
                    else -> error("unexpected refusal ${o.code}")
                }
                is ActionOutcome.Cancelled -> return diverged("cancelled", seen)
            }
            val exec = out.exec
            // Only one screen is read after the action: it is used for route anchor points and "whether
            // the expected text appears"
            val fresh = out.seen ?: tools.interfaces.peek()
            val effective = effectiveWrite(verb, exec.content)
            val now = fresh?.let { if (it.protected) PROTECTED_SEEN else summary(it) } ?: seen
            if (exec.outcome != null || exec.content["ok"] != JsonPrimitive(true)) {
                val error = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                return diverged("the action failed" + error?.let { ": $it" }.orEmpty(), now, executed = exec.outcome != null || error !in NOT_EXECUTED, error = error)
            }
            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = exec.content["changed"] == JsonPrimitive(true) || exec.content["verification"] == JsonPrimitive("match") ||
                (effective && verb in OPENS) ||
                step.after.texts.any { it in shown }
            if (!landed) return diverged("no expected change after the action", now, executed = true)
            event(i, true, describe(step, choice.slots))
        }
        return ReplayOutcome.Completed(total)
    }

    private suspend fun read(): ScreenSnapshot? = try {
        // Read the screen where the route is at the moment (the screen with the most recent action)
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)!!
        val r = tools.executeRead(Part.ToolCall("replay-read-${++seq}", dump, "{}"), 0)
        (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Remove the number; `{n}` in each string parameter is replaced with the slot value. The slot number is out of range, or the non-string parameter contains a slot, so it is null. */
    private fun build(step: RouteStep, slots: List<String>): JsonObject? {
        val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in o) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(fill(v.content, slots) ?: return null)
                SLOT.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    /**
     * Press the selector to find the only target in the current screen. If the name is empty, it will
     * not be replaced by the name; `{n}` will be replaced with the slot value first. There was only one
     * at the time of recording, but now there are more than one: the page is no longer what it was then
     * (for example, there are more entries with the same name in the list), so it is not recognized. It
     * is not unique during recording: the one with the same serial number will be recognized only if its
     * position from the time of recording does not exceed 15% of the screen width.
     */
    private fun find(s: ScreenSnapshot, sel: Selector, slots: List<String>): UiElement? {
        val name = fill(sel.name, slots) ?: return null
        val same = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && (name.isEmpty() || it.name.trim() == name) }
        if (same.size == 1) return same.single()
        if (same.isEmpty() || sel.uniqueAtRecord || sel.bounds.size != 4) return null
        fun near(e: UiElement) = e.bounds.size == 4 && hypot(cx(e.bounds) - cx(sel.bounds), cy(e.bounds) - cy(sel.bounds)) <= NEAR
        val pick = same.getOrNull(sel.ordinal)?.takeIf(::near) ?: return null
        return pick.takeIf { same.count(::near) == 1 }
    }

    private fun cx(b: List<Int>) = (b[0] + b[2]) / 2.0
    private fun cy(b: List<Int>) = (b[1] + b[3]) / 2.0

    private fun summary(s: ScreenSnapshot): String =
        "${s.packageName ?: "unknown app"}: " + s.elements.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(", ")

    private fun emit(e: RunEvent) {
        try { sink.event(e) } catch (x: CancellationException) { throw x } catch (x: Exception) { /* 记录失败不影响回放 */ }
    }

    companion object {
        /** These errors are rejected before the action is executed and the interface is untouched. */
        private val NOT_EXECUTED = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired", "app_in_use_by_other_task",
            "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
        )

        /** Screen width 15% (0–999 coordinates). */
        private const val NEAR = 150.0
        private const val PROTECTED_SEEN = "a protected page"
        private val SLOT = Regex("\\{(\\d+)\\}")

        /** Replace `{n}` with the nth slot value; if the slot number does not exist, it is null. */
        internal fun fill(s: String, slots: List<String>): String? {
            var bad = false
            val out = SLOT.replace(s) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { bad = true; "" } }
            return out.takeUnless { bad }
        }

        /** Step-by-step instructions for viewing the model. */
        fun describe(step: RouteStep, slots: List<String>): String {
            val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null }
            val what = listOfNotNull(
                step.target?.name?.let { fill(it, slots) }?.takeIf { it.isNotEmpty() }?.let { "「$it」" },
                (o?.get("text") as? JsonPrimitive)?.contentOrNull?.let { fill(it, slots) }?.let { "type 「$it」" },
                (o?.get("package") as? JsonPrimitive)?.contentOrNull,
                (o?.get("direction") as? JsonPrimitive)?.contentOrNull,
                (o?.get("key") as? JsonPrimitive)?.contentOrNull,
            ).joinToString(" ")
            return if (what.isEmpty()) step.tool else "${step.tool} $what"
        }
    }
}
