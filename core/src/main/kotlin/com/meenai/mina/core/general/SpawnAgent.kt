package com.meenai.mina.core.general

import com.meenai.mina.core.child.ChildPhase
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Trust
import com.meenai.mina.core.observation.Untrusted
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.RunProvenance
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.str
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SpawnAgent : AgentTool {
    /** The default number of steps and upper limit of steps for subtask. */
    const val DEFAULT_STEPS = 15
    const val MAX_STEPS = 40
    /** The subtask results contain the default number of words, the maximum number of words, and the minimum number of words. */
    const val DEFAULT_RESULT_CHARS = 4_000
    const val MAX_RESULT_CHARS = 8_000
    const val MIN_RESULT_CHARS = 500

    override val verb = Verb.SpawnAgent
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("spawn_agent", Prompts.tool("spawn_agent",
        "steps" to DEFAULT_STEPS, "maxSteps" to MAX_STEPS, "chars" to DEFAULT_RESULT_CHARS, "maxChars" to MAX_RESULT_CHARS), mapOf(
        "goal" to str("A bounded research question for the child agent, using web and sandbox reads only."),
        "format" to str("Optional output format.", listOf("text", "json")),
        "maxSteps" to int("Optional step budget for the child, 1-$MAX_STEPS (default $DEFAULT_STEPS); larger values are lowered to $MAX_STEPS.", 1),
        "maxResultChars" to int("Optional length limit of the returned result in characters, $MIN_RESULT_CHARS-$MAX_RESULT_CHARS (default $DEFAULT_RESULT_CHARS); values outside are moved into range.", 1),
    ), listOf("goal"))

    override fun available(ctx: ToolContext) = ctx.config.childHost != null && ctx.config.runSpec != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val parent = ctx.config.runSpec ?: error("no parent RunSpec")
        val host = ctx.config.childHost ?: error("no child host")
        val goal = a.str("goal", max = 4000)!!
        val format = a.str("format")
        // Only verify that it is an integer; if it exceeds the range, it will be pulled back to the
        // range, and an inappropriate error will occur (subtasks can still be dispatched)
        val steps = (a.int("maxSteps", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)
        val limit = (a.int("maxResultChars", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_RESULT_CHARS).coerceIn(MIN_RESULT_CHARS, MAX_RESULT_CHARS)
        val child = RunSpec(RunId(UUID.randomUUID().toString()), goal, parent.threadId,
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("web", "sandbox"), readOnly = true, parent = parent.id, stepBudget = steps)
        val result = host.spawn(child, format)
        val text = result.text.take(limit)
        return ToolExecution(buildJsonObject {
            put("ok", result.phase == ChildPhase.Completed)
            put("childRunId", result.id.value)
            put("status", result.phase.name.lowercase())
            put("maxSteps", steps)
            put("result", Untrusted.wrap(text))
            if (result.text.length > limit) {
                put("truncated", true)
                put("fullLength", result.text.length)
            }
        }, observations = listOf(Observation(ObsKind.Reply, text, source = "child", trust = Trust.Untrusted)))
    }
}
