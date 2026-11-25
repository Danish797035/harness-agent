package com.meenai.mina.core.replay

import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Record a completed run as a route. Only look at [RunEvent.ActionTarget] and events such as takeover,
 * questioning, confirmation, obstacles, and endings; if any item indicates that it should not be
 * recorded this time (requiring user action, payment, password box, anti-screenshot, browser tool or
 * sandbox tool used), null will be returned.
 */
object RouteRecorder {
    /** Having used it means that this method cannot be separated from the user or other channels, and it is not safe to play it back. */
    private val NO_ROUTE_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
    private val WAITING_STATES = setOf("waiting_input", "waiting_takeover", "no_answer")
    private val PROTECTED = Regex("\"protected\"\\s*:\\s*true|\"state\"\\s*:\\s*\"protected\"")
    private val NOT_SECRET = Regex("\"secret\"\\s*:\\s*false")

    /** The slot value is at least this long: words that are too short tend to appear accidentally in the original words. */
    private const val MIN_SLOT = 2

    /**
     * The entered words can only be fragments that appear exactly once in the original conversation
     * (recorded as slots); otherwise (fixed words, follow-up questions, copied from elsewhere) will not
     * be recorded in their entirety. Words that the user has not said in the original conversation will
     * never be stored in the route. The name of the target element must also be tied into a slot when it
     * appears in the original episode, and will be recognized as a new original episode during playback.
     * Whether it is done or not will be recorded by the caller after pressing
     * [com.meenai.mina.core.agent.LearnGate]. Here we only look at the route itself and whether to
     * remember it or not.
     */
    fun record(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex): Route? {
        if (events.lastOrNull { it is RunEvent.TaskEnded }?.let { (it as RunEvent.TaskEnded).outcome } != "succeeded") return null
        if (events.any { spoils(it, tools) }) return null
        if (events.any { it is RunEvent.ActionTarget && it.incomplete }) return null

        val acts = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective && tools[it.tool]?.routable == true }
        if (acts.isEmpty() || acts.size > MAX_ROUTE_STEPS || acts.any { it.protected }) return null
        val results = events.filterIsInstance<RunEvent.ToolResult>()

        // Slot values are numbered in order of first use.
        val slots = mutableListOf<String>()
        fun bind(v: String): Boolean {
            if (v in slots) return true
            if (v.trim().length < MIN_SLOT || occurrences(goal, v) != 1) return false
            slots += v
            return true
        }
        val args = mutableListOf<JsonObject>()
        val targets = mutableListOf<Selector?>()
        for (a in acts) {
            val o = parse(a.argsJson) ?: return null
            var cleaned = o.filterKeys { it != "ref" && it != "snapshotId" && it != "target" }
            if (tools.verb(a.tool) == Verb.TypeText) {
                val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                // If the port is not clearly stated and it is not entered into the password box, it will
                // not be remembered.
                if (text == Redactor.MASK) return null
                val r = a.callId?.let { id -> results.lastOrNull { it.callId == id } } ?: return null
                if (!NOT_SECRET.containsMatchIn(r.resultJson)) return null
                if (!bind(text)) return null
                cleaned = cleaned + ("text" to JsonPrimitive("{${slots.indexOf(text) + 1}}"))
            }
            args += JsonObject(cleaned)
            targets += a.target?.let { t ->
                val n = t.name
                when {
                    n.isEmpty() -> t
                    n in goal -> if (bind(n)) t.copy(name = "{${slots.indexOf(n) + 1}}") else return null
                    // The name contains this time's parameters (such as "Send to Zhang San"): do not
                    // identify by name
                    slots.any { it in n } -> t.copy(name = "", uniqueAtRecord = false)
                    else -> t
                }
            }
        }
        // The positions of the slots in the original story cannot overlap each other (if the short one
        // is swallowed by the long one, it cannot be changed separately)
        val ranges = slots.map { v -> goal.indexOf(v).let { it until it + v.length } }
        if (ranges.indices.any { i -> ranges.indices.any { j -> i < j && ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last } }) return null
        var template = goal
        ranges.withIndex().sortedByDescending { it.value.first }.forEach { (i, r) -> template = template.replaceRange(r, "{${i + 1}}") }

        fun anchors(a: Anchors, target: Selector?) = Anchors(a.packageName, a.texts.map(String::trim).filter { s ->
            RouteAnchors.stable(s) && s != target?.name?.trim() && slots.none { it.trim() in s }
        }.distinct().take(RouteAnchors.MAX))

        val steps = acts.mapIndexed { i, a -> RouteStep(a.tool, args[i].toString(), targets[i], anchors(a.before, a.target), anchors(a.after, null)) }
        val packages = (acts.mapNotNull { a -> if (tools.verb(a.tool) == Verb.LaunchApp) parse(a.argsJson)?.get("package")?.let { (it as? JsonPrimitive)?.contentOrNull } else null } +
            acts.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }).distinct()
        val route = Route(UUID.randomUUID().toString(), template, slots.size, packages, emptyMap(), steps, lastUsedMs = nowMs)
        return route.takeIf { RouteCodec.encode(it).toByteArray().size <= MAX_ROUTE_BYTES }
    }

    private fun occurrences(s: String, v: String): Int {
        var n = 0; var i = s.indexOf(v)
        while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
        return n
    }

    private fun spoils(e: RunEvent, tools: ToolIndex): Boolean = when (e) {
        is RunEvent.Takeover, is RunEvent.Confirmation -> true
        is RunEvent.State -> e.name in WAITING_STATES
        is RunEvent.Barrier -> e.kind == "payment"
        is RunEvent.ModelReply -> e.tool?.let { barred(it, tools) } == true
        is RunEvent.ToolResult -> barred(e.tool, tools) || PROTECTED.containsMatchIn(e.resultJson)
        is RunEvent.ActionTarget -> barred(e.tool, tools)
        else -> false
    }

    /** The route only remembers the methods on the interface that can be played back: if you have used tools with other capabilities (web pages, sandboxes), the playback will be incomplete. */
    private fun barred(tool: String, tools: ToolIndex): Boolean {
        val t = tools[tool] ?: return false
        if (t.verb in NO_ROUTE_VERBS) return true
        val routed = tools.names.mapNotNull { tools[it]?.takeIf { i -> i.routable }?.capability }.toSet()
        return t.capability != null && t.capability !in routed
    }

    private fun parse(s: String): JsonObject? = try { Json.parseToJsonElement(s) as? JsonObject } catch (e: Exception) { null }
}
