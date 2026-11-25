package com.meenai.mina.core.agent

import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.tag
import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.replay.ReplayOutcome
import com.meenai.mina.core.replay.Replayer
import com.meenai.mina.core.replay.Route
import com.meenai.mina.core.replay.RouteMatcher
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val REPLAY_SOURCE: String get() = Feedback["replay_source"]
private val REPLAY_SCREEN_HEADER: String get() = Feedback["replay_screen_header"]
private val REPLAY_NO_SCREEN: String get() = Feedback["replay_no_screen"]

/**
 * Experience playback: Before calling the model for the first time, if there is a suitable route, follow
 * it first, and return the model if it is completed or if it does not match; after the task is
 * completed, the route count is updated according to the ending. Problems with the route (matching,
 * reading and writing routes) will not affect the task. Each mission starts with [reset].
 */
internal class RouteReplay(
    private val routes: RouteMatcher,
    private val tools: ToolDispatcher,
    private val gate: suspend () -> Unit,
    /** The playback progress is displayed externally (also the state restored after the level is finished). */
    private val show: (TaskState.Running) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val clock: () -> Long,
) {
    /** The replayed route and results of this mission. */
    private var replayed: Pair<Route, ReplayOutcome>? = null

    fun reset() { replayed = null }

    /** When returning the screen, use the read element tool of the most recently operated screen to read the screen (read whichever screen the playback route falls on). */
    private val dumpTool: String get() = tools.index.nameOf(Verb.ListElements, tools.surface)!!

    /**
     * Find the route and play it back; after the playback, hand over the process (how many steps you
     * took and where it doesn’t match) and a copy of the current full screen reading to the model, and
     * return to the front-end app in the screen reading.
     */
    suspend fun run(task: String, history: MutableList<Message>, executor: ActionExecutor, ctx: ContextBuilder, track: StepTrack): Place? {
        val choice = try {
            withContext(Dispatchers.IO) { routes.choose(task) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val total = choice.route.steps.size
        var running = TaskState.Running(0, REPLAY_NOTE, progress = RunProgress.Replay(1, total))
        show(running)
        val replayer = Replayer(
            tools, executor, gate = gate,
            sink = object : RunSink {
                override fun event(e: RunEvent) {
                    emit(e)
                    // If one step is correct, the next step will be displayed.
                    if (e is RunEvent.Replay && e.matched && e.step < e.total) {
                        running = running.copy(progress = RunProgress.Replay(e.step + 1, e.total))
                        show(running)
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            },
            clock = clock,
        )
        val out = replayer.run(choice)
        replayed = choice.route to out
        update(choice.route.id) { r ->
            when (out) {
                is ReplayOutcome.Diverged -> {
                    val misses = if (r.missStep == out.done) r.consecutiveMisses + 1 else 1
                    r.copy(consecutiveMisses = misses, missStep = out.done, disabled = r.disabled || misses >= ROUTE_MISSES_TO_DISABLE, lastUsedMs = clock())
                }
                else -> r.copy(lastUsedMs = clock())
            }
        }
        val steps = choice.route.steps
        val doneLines = steps.take(out.done).mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }.joinToString("\n")
        val head = when (out) {
            is ReplayOutcome.Completed -> Feedback.format("replay.completed", "total" to steps.size, "steps" to doneLines)
            is ReplayOutcome.Diverged -> {
                val error = out.error?.let { Feedback.format("replay.error_code", "code" to it) }.orEmpty()
                val next = if (out.executed) Feedback.format("replay.diverged_executed", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                else Feedback.format("replay.diverged_skipped", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] }, "next" to next)
            }
            is ReplayOutcome.StoppedForConfirmation -> Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] },
                "next" to Feedback.format("replay.stopped", "step" to (out.done + 1), "action" to Replayer.describe(steps[out.done], choice.slots)))
        }
        // Currently reading the screen in full: the model takes the number from here and continues; if
        // it cannot be read, just hand in the instructions, and the task continues as usual.
        val looked = try {
            tools.executeRead(Part.ToolCall("replay_handback", dumpTool, "{}"), 0).takeIf { it.content["ok"] == JsonPrimitive(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val read = looked?.content
        if (read != null) {
            // Screen reading creates a pair of tool calls and results: Cropping and "How many screens
            // can the model see?" Press the screen reading tool to identify it. Each interface also
            // meets the matching requirements for calls and results.
            val id = "replay_handback"
            history += Message(Role.Assistant, listOf(Part.Text(Feedback.format("replay.handback", "head" to head, "note" to REPLAY_SCREEN_HEADER)), Part.ToolCall(id, dumpTool, "{}")), Author.Mina)
            history += Message(Role.Tool, listOf(Part.ToolResult(id, read.toString(), isError = false, looked?.observations.orEmpty().map { it.tag })), Author.Mina)
            // Main model that can see pictures: you can see the scene at the moment when returning it
            // and after the action.
            tools.imageNow()?.let { (png, shotId) -> history += Message(Role.User, listOf(Part.Text(Feedback["action_frame_note"]), tools.modelImage(png, shotId)), Author.Mina) }
        } else {
            history += ctx.notice(REPLAY_SOURCE, Feedback.format("replay.handback", "head" to head, "note" to REPLAY_NO_SCREEN))
        }
        // The step after handing over is counted as a recovery step (handled to the main model when the
        // large and small models are divided)
        track.markRecovery()
        return looked?.place
    }

    /** After the playback is completed and this run is completed ([LearnGate], [outcome] are the endings judged after handing in the answer): the success count +1, and the consecutive errors are cleared. */
    suspend fun noteResult(end: TaskState, outcome: RunOutcome) {
        val (route, out) = replayed ?: return
        if (out !is ReplayOutcome.Completed || !LearnGate.done(end, outcome)) return
        update(route.id) { it.copy(successes = it.successes + 1, consecutiveMisses = 0, missStep = null) }
    }

    private suspend fun update(id: String, change: (Route) -> Route) {
        val store = routes.store
        try {
            // Reading and rewriting are completed in one go: the count will not be lost when another
            // parallel task changes this route at the same time.
            withContext(Dispatchers.IO) { store.update(id, change) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // If the route count cannot be written, it will not affect the task.
        }
    }

    companion object {
        /** 回放期间界面上这一步的说明。 */
        const val REPLAY_NOTE = "按以往的做法操作"
        /** 同一步连续对不上这么多次就停用路线。 */
        const val ROUTE_MISSES_TO_DISABLE = 2
    }
}
