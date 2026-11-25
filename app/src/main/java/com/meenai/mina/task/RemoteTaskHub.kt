package com.meenai.mina.task

import android.content.Context
import android.util.Log
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.mcp.McpTaskHost
import com.meenai.mina.core.mcp.McpTaskLink
import com.meenai.mina.core.remote.RemoteTaskService
import com.meenai.mina.core.remote.RemoteTask
import com.meenai.mina.core.remote.RemoteSteer
import com.meenai.mina.core.remote.RemoteTasks
import com.meenai.mina.core.remote.StartOutcome
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.RunEventBus
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.RunSpec
import java.util.UUID

/**
 * The entrance and exit of the task handed over by the computer: starting from [TaskService] (the same
 * path as the dialogue interface, the source follows the existing remote MCP permission policy; CLI and
 * MCP are accessed from here), the status is read from the task itself ([TaskRun.state]), here only the
 * number given by the computer corresponds to which dialogue, original words, and the final state at the
 * end of the task (the task object is gone after the end). Only the numbers registered here are
 * recognized. Tasks initiated on the mobile phone cannot be seen or moved.
 */
class RemoteTaskHub(
    private val context: Context,
    private val runs: () -> List<TaskRun> = { (context.applicationContext as com.meenai.mina.HarnessApp).taskState.runs.value },
    private val clock: () -> Long = System::currentTimeMillis,
) : McpTaskHost, RemoteTasks {
    private class Record(val id: String, val conversation: String, val goal: String, val createdAtMs: Long) {
        @Volatile var final: TaskState? = null
        @Volatile var steers: List<RemoteSteer> = emptyList()
    }

    private val buses = HashMap<RunId, RunEventBus>()
    private val records = LinkedHashMap<String, Record>()

    @Synchronized override fun submit(spec: RunSpec): McpTaskLink {
        require(spec.provenance.origin == RunOrigin.Mcp)
        val bus = buses.getOrPut(spec.id) { RunEventBus() }
        val conversation = spec.threadId.takeIf(RemoteTaskService::isConversation) ?: RemoteTaskService.newConversation()
        try { launch(spec.id.value, spec.goal, conversation, spec.provenance.attendance) }
        catch (e: Exception) { buses.remove(spec.id); throw e }
        return McpTaskLink(spec.id, bus) { cancel(spec.id.value) }
    }

    override fun start(goal: String, conversation: String?): StartOutcome {
        val id = UUID.randomUUID().toString()
        val record = try {
            // The other end of the computer can continue to answer follow-up questions, but it is not
            // always guarded: follow-up questions will be waited as usual, and when the time limit is
            // reached, it will be treated as if there is no answer.
            launch(id, goal, conversation ?: RemoteTaskService.newConversation(), Attendance.Reachable)
        } catch (e: Exception) {
            Log.w(TAG, "could not start MCP task", e)
            return StartOutcome.Refused("Harness Agent could not start the task on the phone (${e.message ?: e::class.simpleName}). Open Mina on the phone and try again.")
        }
        return StartOutcome.Started(remote(record))
    }

    private fun launch(id: String, goal: String, conversation: String, attendance: Attendance): Record {
        val record = Record(id, conversation, goal, clock())
        synchronized(this) {
            records[id] = record
            while (records.size > KEEP) records.remove(records.keys.first())
        }
        try { TaskService.startMcp(context, goal, conversation, id, attendance) }
        catch (e: Exception) { synchronized(this) { records.remove(id) }; throw e }
        return record
    }

    override fun get(id: String): RemoteTask? = synchronized(this) { records[id] }?.let(::remote)

    override fun list(limit: Int): List<RemoteTask> = synchronized(this) { records.values.toList() }.asReversed().take(limit).map(::remote)

    override fun answer(id: String, text: String): Boolean {
        val run = live(id) ?: return false
        if (run.state.value !is TaskState.WaitingInput) return false
        run.answer(text) {}
        return true
    }

    override fun cancel(id: String): Boolean {
        if (synchronized(this) { records[id] }?.final != null || live(id) == null) return false
        TaskService.cancelMcp(context, id)
        return true
    }

    override fun steer(id: String, text: String): String? {
        val record = synchronized(this) { records[id] } ?: return null
        if (record.final != null) return null
        return live(id)?.steerRemote(text)
    }

    private fun live(id: String): TaskRun? = runs().firstOrNull { it.mcpExternalId == id }

    /** Status: I have finished using the final status I wrote down, and I am still running (or queuing) to read my own tasks; the ones that have been handed in for a long time and have not been registered have not been started yet. */
    private fun remote(r: Record): RemoteTask {
        val run = live(r.id)
        val end = r.final
        val state = end ?: run?.state?.value
            ?: if (clock() - r.createdAtMs > LOST_MS) TaskState.Failed("The task never started on the phone.", FailKind.Environment) else null
        return RemoteTask(r.id, r.conversation, r.goal, r.createdAtMs, state,
            if (end != null) r.steers else run?.let { remoteSteers(it.steers.value) }.orEmpty())
    }

    @Synchronized fun attach(id: String, source: RunEventBus): AutoCloseable? =
        buses[RunId(id)]?.let { sink -> source.subscribe(sink) }

    /** Task ending (including being canceled while queuing, and the service being stopped by the system): Write down the final state, and read all subsequent states from here. */
    @Synchronized fun ended(id: String, end: TaskState, steers: List<SteerNote>) {
        records[id]?.let { record ->
            // The snapshot is transmitted by the end party: the queue cancellation may have been moved
            // out of the center, the service may be interrupted, and it may be too late to change the
            // message display status.
            record.steers = remoteSteers(steers, finished = true)
            record.final = end
        }
        buses.remove(RunId(id))?.event(RunEvent.State(clock(), "mcp_task_ended", end::class.simpleName.orEmpty()))
    }

    private companion object {
        const val TAG = "MinaMcp"
        /** Only keep the tasks handed in by so many computers recently. */
        const val KEEP = 100
        const val LOST_MS = 2 * 60_000L
    }
}
