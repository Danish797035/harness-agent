package com.meenai.mina.core.action

import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Generate activities from real execution results and avoid guessing what was done from the model's progress text. */
internal object ActivityRecord {
    private val controls = setOf(
        Verb.Wait, Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser,
        Verb.HandleObstacle, Verb.EndNotDone,
    )
    // The element number (ref) is only meaningful to the model and is not an object for people to see.
    private val targetKeys = listOf("package", "url", "path", "query")

    fun of(
        tools: ToolDispatcher, tool: String, args: JsonObject, exec: ToolExecution,
        step: Int, source: String, callId: String, atMs: Long, outward: Boolean = false,
    ): RunEvent.Activity? {
        val verb = tools.verb(tool)
        if (verb in controls) return null
        val target = RunEvent.placeLabel(exec.place)
            ?: targetKeys.firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        val content = exec.content
        val failed = content["ok"] == JsonPrimitive(false)
        val effect = if (failed) {
            (content["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
        } else {
            ((content["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
                ?: when (exec.changed) { true -> "changed"; false -> "unchanged"; null -> "completed" }
        }
        return RunEvent.Activity(
            atMs, step, if (tools.declaredRisk(tool) == Risk.Read) "observe" else "act",
            tool, target?.let { RunEvent.clip(Redactor.text(it)) }, effect,
            exec.observations.map { it.id }.filter { it > 0 }.distinct(), source, callId,
            exec.observations.lastOrNull { !it.title.isNullOrBlank() }?.title?.let { RunEvent.clip(Redactor.text(it)) },
            failed, outward,
        )
    }
}
