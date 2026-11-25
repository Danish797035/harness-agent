package com.meenai.mina.core.agent

import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionOutcome
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Until
import com.meenai.mina.core.tool.UntilOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

const val DO_STEPS_TOOL = "do_steps"
private const val MIN_STEPS = 1
/** Only limit the resource consumption of a single call, and do not write the reasonable length of continuous operations as task rules. */
private const val MAX_STEPS = 20
private const val EXPECT_MAX = 200
/** From the second step, determine and execute the upper limit of the stability of the interface (same as the screen that comes with the action). */
private const val SETTLE_MS = 1500L
/** One-step waiting condition (you don’t have to give it); expect is an old way of writing, and the meaning has changed from "take a look after it is done" to "wait until it appears". */
private const val EXPECT = "expect"
private const val EXPECT_GONE = "expect_gone"
private const val EXPECT_APP = "expect_app"
private const val WAIT_STABLE = "wait_stable"
private const val TIMEOUT_S = "timeout_s"
private val STEP_FIELDS = setOf("tool", "args", EXPECT, EXPECT_GONE, EXPECT_APP, WAIT_STABLE, TIMEOUT_S)

/** Application access is blocked: The batch should be stopped and the instructions of the execution entry should be brought to the model as it is. */
private fun appAccessDenied(code: String) = com.meenai.mina.core.access.AppAccessDenied.isAppAccessCode(code)

/** A step in a batch; [until] is what you want to wait for after finishing and before continuing. If not, it is null. */
data class BatchStep(val tool: String, val argsJson: String, val until: Until? = null)

/**
 * Perform multiple consecutive actions at one time, check them step by step, and stop if something
 * unexpected happens. Each step (from the second step until the interface is stable) is executed by
 * [ActionExecutor], in the same sequence as the single step; it is not used in the batch, and no
 * agreement is assumed. The irreversible step hit stops before it and is not executed. Failure to pass
 * the verification: the tool failed, was switched to another app, and the input readback was
 * inconsistent. When a step with a waiting condition ([Until]) is completed, wait for it to be
 * established ([ToolDispatcher.waitUntil]: screen reading is better than literal, regardless of the
 * model), and stop when the time limit is reached, the pop-up box is seen, or the app is changed; how
 * long to wait is written into the result of this step. Each step is an action for the guard and the run
 * record (source = "batch" in the run record).
 */
class BatchRunner(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    private val executor: ActionExecutor,
    /** The guard conclusions of each substep (once before execution and once after execution) are returned to the loop, and stuck reminders and repeated inputs are included; suggestions to be attached to the model are returned. */
    private val noteVerdicts: (step: BatchStep, exec: ToolExecution?, verdicts: List<GuardVerdict>) -> String? = { _, _, _ -> null },
    /** Is the user waiting for new comments at the moment (just look at the inbox in the memory); if so, it will stop between two sub-steps and leave it to the loop to send. */
    private val steerWaiting: () -> Boolean = { false },
) {
    private class Stop(
        val index: Int, val reason: String, val entry: JsonObject, val executed: Boolean, val extra: JsonObject = JsonObject(emptyMap()),
        /** Those sub-steps that are not done after stopping are also given a result (when a new interruption is made), and the sub-steps and results are paired one by one. */
        val rest: List<JsonElement> = emptyList(),
    )

    /** The guard conclusions that appear in all sub-steps; the loop will judge whether there is a guard reminder in this step based on this. */
    val verdicts = mutableListOf<GuardVerdict>()
    private var advice: String? = null

    /** The sum of the time spent on each step waiting for the interface according to conditions; the running record counts it as waiting, not as an operation. */
    var waitedMs = 0L
        private set

    suspend fun run(steps: List<BatchStep>, callId: String, step: Int = 0): ToolExecution {
        val entries = mutableListOf<JsonElement>()
        var last: ToolExecution? = null
        // The observations read in each sub-step (already recorded in the ledger); the results of the
        // entire batch are carried with them, and they are shortened according to their shape when
        // cutting.
        val seen = mutableListOf<Observation>()
        var stop: Stop? = null
        fun skipFrom(i: Int): Stop {
            val skipped = steps.drop(i).map { entry(it.tool) { put("ok", false); put("error", NEW_MESSAGE); put("executed", false) } }
            return Stop(i, NEW_MESSAGE, skipped.first(), executed = false, rest = skipped.drop(1))
        }
        for ((i, s) in steps.withIndex()) {
            if (i > 0 && steerWaiting()) {
                stop = skipFrom(i)
                break
            }
            // When the interface in the previous step is still changing, it will be judged that the
            // transition screen will be read, and expect will also be misjudged.
            if (i > 0) tools.settle(SETTLE_MS)
            val out = executor.run(Part.ToolCall("$callId.${i + 1}", s.tool, s.argsJson), Origin.Batch(step))
            val before = out.before
            note(s, null, before)
            fun notRun(reason: String, extra: JsonObject = JsonObject(emptyMap()), message: String? = null) = Stop(i, reason, entry(s.tool) {
                put("ok", false); put("error", reason); put("executed", false)
                message?.let { put("message", it) }
                if (before.isNotEmpty()) put("guard", JsonArray(before.map { it.toJson() }))
            }, executed = false, extra = extra)
            val exec = when (out) {
                is ActionOutcome.Ran -> out.exec
                is ActionOutcome.Refused -> {
                    val hit = out.hit
                    // Application permission denied: Give the next step instructions given by the
                    // execution entry to the model, don’t let it only see the error code
                    val message = (out.content["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { appAccessDenied(out.code) }
                    stop = if (hit == null) notRun(out.code, message = message)
                    else notRun(out.code, buildJsonObject { put("action", hit.action.name); put("matched", hit.matched) }, message)
                    break
                }
                is ActionOutcome.Cancelled -> { stop = notRun("cancelled"); break }
            }
            // The stay, interval or wait before this step was cut off by the user's intervention: this
            // step did nothing, and was skipped with the following ones, leaving it to the loop to send
            // the sentence.
            if (exec.content["error"] == JsonPrimitive(NEW_MESSAGE)) {
                waitedMs += exec.waitMs
                stop = skipFrom(i)
                break
            }
            seen += exec.observations
            // The pauses made up for the rhythm in sub-steps are also counted as waiting for this batch.
            waitedMs += exec.waitMs
            val afterVerdicts = out.after
            note(s, exec, afterVerdicts)
            val all = before + afterVerdicts
            last = exec
            val c = exec.content
            val screen = c["screen"] as? JsonObject
            val failed = c["ok"] != JsonPrimitive(true)
            val hard = when {
                failed -> (c["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
                c["verification"] == JsonPrimitive("mismatch") -> "action_not_applied"
                c["app_switched"] != null -> "app_switched"
                // Open it on the user's home screen (move it back and change the location): the
                // subsequent steps are arranged according to the original screen
                c["opened"] != null -> "opened_elsewhere"
                // When it comes to websites and apps, we have reached the upper limit: we won’t do the
                // next steps anymore.
                c["limitReached"] != null -> "limit_reached"
                else -> null
            }
            val review = i < steps.lastIndex && screen != null &&
                (screen["imageReason"] != null || screen["protected"] == JsonPrimitive(true) ||
                    screen["readable"] == JsonPrimitive(false) || screen["note"] != null)
            // The screen that comes with the action may still look like it is loading: when the words to
            // appear appear, there is no need to look at the picture again.
            val until = s.until?.takeIf { hard == null && (!review || it.appears != null) }
            val waited = until?.let { tools.waitUntil(it, executor.recorder::now, steerWaiting) }
            waited?.let { waitedMs += it.waitedMs }
            val reason = hard ?: when {
                waited != null && !waited.met -> when (waited.stopped) {
                    UntilOutcome.TIMEOUT -> "expectation_unmet"
                    UntilOutcome.NEW_MESSAGE -> null
                    else -> waited.stopped
                }
                review && waited?.met != true -> "screen_needs_review"
                else -> null
            }
            val e = entry(s.tool) {
                put("ok", reason == null)
                exec.changed?.let { put("changed", it) }
                effectKind(c)?.let { put("effect", it) }
                (c["verification"] as? JsonPrimitive)?.let { put("verification", it) }
                reason?.let { put("error", it) }
                if (waited != null && !waited.met) s.until?.let { untilFields(it).forEach { (k, v) -> put(k, v) } }
                waited?.let { w ->
                    put("wait", buildJsonObject { put("met", w.met); w.stopped?.let { put("stopped", it) }; put("waited_ms", w.waitedMs) })
                }
                (c["app_switched"] as? JsonPrimitive)?.let { put("app_switched", it) }
                (c["opened"] as? JsonObject)?.let { put("opened", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                (c["chooser"] as? JsonObject)?.let { put("chooser", it) }
                (c["limitReached"] as? JsonPrimitive)?.let { put("limitReached", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                if (all.isNotEmpty()) put("guard", JsonArray(all.map { it.toJson() }))
            }
            if (reason != null) { stop = Stop(i, reason, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break }
            // While waiting, the user interrupted: the following sub-steps will be skipped at the
            // beginning of the next round; when this is the last step, it will stop here.
            if (waited?.stopped == UntilOutcome.NEW_MESSAGE && i == steps.lastIndex) {
                stop = Stop(i, NEW_MESSAGE, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break
            }
            entries += e
            if (guards.stop != null && i < steps.size - 1) {
                stop = Stop(i + 1, "guard_stopped", entry(steps[i + 1].tool) { put("ok", false); put("error", "guard_stopped"); put("executed", false) }, executed = false)
                break
            }
        }
        return finish(steps, entries, stop, last, callId, step, seen)
    }

    private fun finish(
        steps: List<BatchStep>, done: List<JsonElement>, stop: Stop?, last: ToolExecution?, callId: String, step: Int, seen: List<Observation>,
    ): ToolExecution {
        val completed = stop?.index ?: steps.size
        executor.recorder.event(RunEvent.Batch(executor.recorder.now(), completed, steps.size, stop != null, stop?.reason))
        val screen = last?.content?.get("screen")
        val content = buildJsonObject {
            put("ok", stop == null)
            when (stop?.reason) {
                null -> Unit
                "confirmation_required" -> {
                    put("error", "confirmation_required")
                    stop.extra.forEach { (k, v) -> put(k, v) }
                    val what = stop.extra["matched"]?.let { (it as JsonPrimitive).content }
                    put("message", "Step ${stop.index + 1} of ${steps.size} spends money ($what). " +
                        "It was not run, and the steps after it were not run either. Payment steps never go in do_steps: " +
                        if (executor.confirms) "follow the step's message (request_confirmation, or hand_to_user for a payment), then do that step on its own as a single tool call."
                        else "do it on its own as a single tool call, not as part of a batch.")
                    put("counts_as_failure", false)
                }
                NEW_MESSAGE -> {
                    put("error", "batch_stopped")
                    put("reason", NEW_MESSAGE)
                    put("message", (if (stop.executed) "Step ${stop.index + 1} of ${steps.size} was run, but waiting for its condition was cut short"
                        else "Steps ${stop.index + 1}-${steps.size} of ${steps.size} were skipped") + " to handle a new message from the user. " +
                        "Read the message first, then decide what to do next.")
                    put("counts_as_failure", false)
                }
                else -> {
                    put("error", "batch_stopped")
                    put("reason", stop.reason)
                    val n = "Step ${stop.index + 1} of ${steps.size}"
                    if (appAccessDenied(stop.reason)) {
                        // Permission denial is not an ordinary operation failure: do not suggest
                        // "continue with another batch", clearly explain what to do next.
                        val detail = (stop.entry["message"] as? JsonPrimitive)?.contentOrNull
                            ?: "Step ${stop.index + 1} of ${steps.size} was not run (${stop.reason})."
                        put("message", "$detail The remaining steps in this batch were not run.")
                        put("retryable", false)
                    } else {
                        put("message", (if (stop.executed) "$n was run but did not pass its check (${stop.reason})" else "$n was not run (${stop.reason})") +
                            "; the steps after it were not run. Check the screen, then continue one step at a time or with a new batch.")
                    }
                }
            }
            advice?.let { put("advice", it) }
            put("completed", completed); put("total", steps.size)
            put("steps", JsonArray(done + listOfNotNull(stop?.entry) + stop?.rest.orEmpty()))
            screen?.let { put("screen", it) }
            if (stop != null) put("recoverable", true)
        }
        // The pictures of the sub-steps have been saved one by one. The picture of the last sub-step
        // sent to the model (only available for the main model that can see pictures) is left in the
        // loop, followed by the entire batch of results.
        return ToolExecution(content, changed = last?.changed, frame = if (!executor.recorder.savesScreens) last?.frame ?: last?.image else null,
            screenElements = last?.screenElements, images = last?.images.orEmpty(), imageIds = last?.imageIds.orEmpty(), place = last?.place,
            observations = seen)
    }

    private fun note(s: BatchStep, exec: ToolExecution?, v: List<GuardVerdict>) {
        verdicts += v
        noteVerdicts(s, exec, v)?.let { advice = it }
    }

    private fun entry(tool: String, b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("tool", tool); b() }

    companion object {
        /** The reason why the sub-step is stopped due to the user's new interruption. */
        const val NEW_MESSAGE = SKIPPED_FOR_NEW_MESSAGE

        /**
         * Verify the parameters of do_steps; [available] is the tool name provided to the model at this
         * time, and [batchable] is a tool that can be put into a batch (according to the tool
         * declaration, only screen actions and waiting; observation, questioning, taking over, and
         * confirming are not allowed). Return to the step, or name the error description at which step.
         */
        fun parse(argsJson: String, available: Set<String>, batchable: List<String>): Pair<List<BatchStep>?, String?> {
            val o = try { Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject } catch (e: SerializationException) { null }
                ?: return null to "arguments: must be a JSON object"
            o.keys.firstOrNull { it != "steps" && it != "reason" }?.let { return null to "$it: unknown argument for do_steps" }
            val arr = o["steps"] as? JsonArray ?: return null to "steps: required, an array of $MIN_STEPS-$MAX_STEPS steps"
            if (arr.size !in MIN_STEPS..MAX_STEPS) return null to "steps: must have $MIN_STEPS-$MAX_STEPS items"
            val out = arr.mapIndexed { i, e ->
                val n = i + 1
                val s = e as? JsonObject ?: return null to "steps[$n]: must be an object with tool, args and optional expect"
                s.keys.firstOrNull { it !in STEP_FIELDS }?.let { return null to "steps[$n].$it: unknown field" }
                val tool = (s["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null to "steps[$n].tool: required"
                if (tool !in batchable || tool !in available) {
                    return null to "steps[$n].tool: $tool cannot be batched; only ${batchable.filter { it in available }.joinToString()}"
                }
                val args = when (val a = s["args"]) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> a
                    else -> return null to "steps[$n].args: must be an object"
                }
                val texts = listOf(EXPECT, EXPECT_GONE, EXPECT_APP).associateWith { k ->
                    when (val x = s[k]) {
                        null -> null
                        is JsonPrimitive -> x.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                            ?: return null to "steps[$n].$k: must be a non-blank string"
                        else -> return null to "steps[$n].$k: must be a string"
                    }?.also { if (it.length > EXPECT_MAX) return null to "steps[$n].$k: must be at most $EXPECT_MAX characters" }
                }
                val stable = when (val x = s[WAIT_STABLE]) {
                    null -> false
                    is JsonPrimitive -> x.takeIf { !it.isString }?.booleanOrNull ?: return null to "steps[$n].$WAIT_STABLE: must be true or false"
                    else -> return null to "steps[$n].$WAIT_STABLE: must be true or false"
                }
                val max = Until.MAX_MS / 1000
                val seconds = when (val x = s[TIMEOUT_S]) {
                    null -> null
                    is JsonPrimitive -> x.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in 1.0..max.toDouble() }
                        ?: return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                    else -> return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                }
                val waits = texts.values.any { it != null } || stable
                if (seconds != null && !waits) return null to "steps[$n].$TIMEOUT_S: only goes with $EXPECT, $EXPECT_GONE, $EXPECT_APP or $WAIT_STABLE"
                val until = if (!waits) null else Until(texts[EXPECT], texts[EXPECT_GONE], texts[EXPECT_APP], stable,
                    seconds?.let { (it * 1000).toLong() } ?: Until.DEFAULT_MS)
                BatchStep(tool, args.toString(), until)
            }
            return out to null
        }

        /** The waiting conditions are returned according to the way the model is written (if you don't wait, it is attached to the result of that step). */
        private fun untilFields(u: Until): JsonObject = buildJsonObject {
            u.appears?.let { put(EXPECT, it) }
            u.gone?.let { put(EXPECT_GONE, it) }
            u.place?.let { put(EXPECT_APP, it) }
            if (u.stable) put(WAIT_STABLE, true)
            put(TIMEOUT_S, u.timeoutMs / 1000.0)
        }

        /**
         * The description given to the model when the steps in a batch are applied to more than two
         * screens ([surfaceOf] is the screen on which the tool is applied, and the wait for a non-moving
         * screen is null); if they are all on the same screen, it is null.
         */
        fun mixedSurface(steps: List<BatchStep>, surfaceOf: (String) -> String?): String? {
            val surfaces = steps.mapNotNull { surfaceOf(it.tool) }.distinct()
            if (surfaces.size < 2) return null
            return Feedback.format("batch.mixed_surface", "surfaces" to surfaces.joinToString(", "))
        }

        /** For running records: all text entered in the batch will be coded (the port has not yet said whether it is a password box). */
        fun redactArgs(argsJson: String): String {
            val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return Redactor.text(argsJson)
            val steps = o["steps"] as? JsonArray ?: return Redactor.text(argsJson)
            val masked = JsonArray(steps.map { s ->
                val so = s as? JsonObject ?: return@map s
                val a = so["args"] as? JsonObject
                if (a == null || "text" !in a) s else JsonObject(so + ("args" to JsonObject(a + ("text" to JsonPrimitive("***")))))
            })
            return Redactor.text(JsonObject(o + ("steps" to masked)).toString())
        }

        /** The definition of do_steps; [batchable] is the name of the tool that can be put into a batch. */
        fun spec(batchable: List<String>) = ToolSpec(
            DO_STEPS_TOOL,
            Prompts.tool("do_steps"),
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("steps", buildJsonObject {
                        put("type", "array"); put("minItems", MIN_STEPS); put("maxItems", MAX_STEPS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("tool", buildJsonObject { put("type", "string"); put("enum", JsonArray(batchable.map(::JsonPrimitive))) })
                                put("args", buildJsonObject { put("type", "object"); put("description", "The arguments that tool takes on its own.") })
                                put(EXPECT, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: after this step, wait until an element's name contains this text. Text already on screen before the step also matches, so pick text the step brings up.")
                                })
                                put(EXPECT_GONE, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until no element's name contains this text (a loading or progress label).")
                                })
                                put(EXPECT_APP, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until the app in front (package name) or the page address contains this.")
                                })
                                put(WAIT_STABLE, buildJsonObject { put("type", "boolean"); put("description", "Optional: wait until the screen stops changing.") })
                                put(TIMEOUT_S, buildJsonObject {
                                    put("type", "number"); put("minimum", 1); put("maximum", Until.MAX_MS / 1000)
                                    put("description", "Optional: longest wait for this step's conditions, in seconds (default ${Until.DEFAULT_MS / 1000}).")
                                })
                            })
                            put("required", JsonArray(listOf(JsonPrimitive("tool"), JsonPrimitive("args"))))
                            put("additionalProperties", false)
                        })
                    })
                    put("reason", buildJsonObject { put("type", "string"); put("description", com.meenai.mina.core.tool.REASON_NOTE) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("steps"))))
                put("additionalProperties", false)
            }.toString(),
        )
    }
}

/** The type of effect in the result; if the action does not report the effect, the effect is null. */
/** The type of actual effect reported by the action (the ability is written in the effect of the result); if not reported, it is null. */
private fun effectKind(c: JsonObject): String? = ((c["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
