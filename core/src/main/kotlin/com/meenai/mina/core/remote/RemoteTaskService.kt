package com.meenai.mina.core.remote

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Business errors common to remote portals; HTTP status and MCP isError are determined by the respective
 * adapters. Each of the several situations where the message needs to be exchanged according to the
 * entrance is a subclass, and the adapter is exchanged according to the type, without comparing the text
 * of the message.
 */
sealed class RemoteTaskError(val code: Code, message: String) : Exception(message) {
    enum class Code { INVALID_ARGUMENT, NOT_FOUND, CONFLICT, UNAVAILABLE }

    /** For other errors: the message is sent to the caller as is. */
    class General(code: Code, message: String) : RemoteTaskError(code, message)
    class NoSuchTask(val id: String) : RemoteTaskError(Code.NOT_FOUND, "No remote task with id $id")
    class BadConversation : RemoteTaskError(Code.INVALID_ARGUMENT,
        "conversation must be one returned by an earlier remote task (looks like mcp-<uuid>)")
    /** This step requires the user to handle it on their mobile phone and cannot be answered remotely. */
    class NeedsPhone : RemoteTaskError(Code.CONFLICT, RemoteStatus.ON_PHONE_NOTE)
    /** When I checked, I was still asking questions, but when I turned in the answers, the questions were gone. */
    class QuestionClosed : RemoteTaskError(Code.CONFLICT, "The question is no longer open; check the task's status.")
}

data class RemoteWait(val task: RemoteTask, val changed: Boolean)
data class RemoteCancellation(val taskId: String, val accepted: Boolean, val status: String? = null)
data class RemoteSteerReceipt(val taskId: String, val messageId: String)

/**
 * Task use cases common to the CLI API and MCP. There are no HTTP, JSON-RPC, tool names, or command line
 * arguments. Only access the original task service on the phone through [RemoteTasks], without creating
 * another executor or task state.
 */
class RemoteTaskService(private val tasks: RemoteTasks, private val pollMs: Long = 250) {
    init { require(pollMs > 0) }

    fun start(goal: String, conversation: String? = null): RemoteTask {
        val clean = required(goal, "goal", MAX_GOAL)
        val thread = conversation?.trim()?.takeIf { it.isNotEmpty() }
        if (thread != null && !isConversation(thread)) throw RemoteTaskError.BadConversation()
        return when (val r = tasks.start(clean, thread)) {
            is StartOutcome.Started -> r.task
            is StartOutcome.Refused -> throw RemoteTaskError.General(RemoteTaskError.Code.UNAVAILABLE, r.message)
        }
    }

    fun get(id: String): RemoteTask {
        val clean = id.trim()
        if (clean.isEmpty()) invalid("task_id is required and must be a string")
        return tasks.get(clean) ?: throw RemoteTaskError.NoSuchTask(clean)
    }

    fun list(limit: Int = DEFAULT_LIST): List<RemoteTask> {
        if (limit !in 1..MAX_LIST) invalid("limit must be between 1 and $MAX_LIST")
        return tasks.list(limit)
    }

    /** A bounded wait will return when the status changes or terminates; the mobile phone task will not be canceled when the wait times out. If the waiting task cannot be found, it will return to the last seen state. */
    suspend fun wait(id: String, timeoutS: Int = DEFAULT_WAIT_S): RemoteWait {
        val first = get(id)
        if (timeoutS !in 1..MAX_WAIT_S) invalid("timeout_s must be between 1 and $MAX_WAIT_S")
        val from = RemoteStatus.of(first.state)
        var now = first
        var waited = 0L
        while (!RemoteStatus.finished(RemoteStatus.of(now.state)) && RemoteStatus.of(now.state) == from && waited < timeoutS * 1000L) {
            val interval = minOf(pollMs, timeoutS * 1000L - waited)
            delay(interval)
            waited += interval
            now = tasks.get(first.id) ?: break
        }
        return RemoteWait(now, RemoteStatus.of(now.state) != from)
    }

    /** You can only answer follow-up questions, and you cannot treat payment confirmation or mobile phone takeover as ordinary answers. */
    fun answer(id: String, text: String): String {
        val task = get(id)
        val clean = required(text, "text", MAX_ANSWER)
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_FOR_ANSWER -> Unit
            RemoteStatus.WAITING_ON_PHONE -> throw RemoteTaskError.NeedsPhone()
            else -> conflict("Task ${task.id} is not waiting for an answer (status ${RemoteStatus.of(task.state)}).")
        }
        if (!tasks.answer(task.id, clean)) throw RemoteTaskError.QuestionClosed()
        return task.id
    }

    /** Only append to running tasks; reply, approval, takeover and recovery still have their own entrances. The host checks status and inbox again. */
    fun steer(id: String, text: String): RemoteSteerReceipt {
        val task = get(id)
        val clean = required(text, "text", MAX_STEER)
        val status = RemoteStatus.of(task.state)
        if (status == RemoteStatus.WAITING_ON_PHONE) throw RemoteTaskError.NeedsPhone()
        if (status != RemoteStatus.RUNNING) conflict("Task ${task.id} is not running (status $status). " +
            "Answer an open question or wait for it to run; steering does not start or resume a task.")
        val messageId = tasks.steer(task.id, clean)
            ?: conflict("The task no longer accepts instructions; check its status. No new task was started.")
        return RemoteSteerReceipt(task.id, messageId)
    }

    /** Accepted only means that the task has been handed over to the service. Whether it reaches the canceled final state is still subject to status query. */
    fun cancel(id: String): RemoteCancellation {
        val task = get(id)
        val status = RemoteStatus.of(task.state)
        return if (RemoteStatus.finished(status)) RemoteCancellation(task.id, false, status)
        else RemoteCancellation(task.id, tasks.cancel(task.id))
    }

    private fun required(value: String, name: String, max: Int): String {
        val clean = value.trim()
        if (clean.isEmpty()) invalid("$name is required and must be a non-empty string")
        if (clean.length > max) invalid("$name is too long (at most $max characters)")
        return clean
    }

    private fun invalid(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.INVALID_ARGUMENT, message)
    private fun conflict(message: String): Nothing = throw RemoteTaskError.General(RemoteTaskError.Code.CONFLICT, message)

    companion object {
        const val DEFAULT_WAIT_S = 30
        const val MAX_WAIT_S = 50
        const val DEFAULT_LIST = 10
        const val MAX_LIST = 50
        const val MAX_GOAL = 4_000
        const val MAX_ANSWER = 2_000
        const val MAX_STEER = 4_000
        // The issued session number is retained so that the new CLI and the original MCP can continue
        // the same remote session.
        private const val CONVERSATION_PREFIX = "mcp-"
        fun newConversation(): String = CONVERSATION_PREFIX + UUID.randomUUID()
        fun isConversation(id: String): Boolean = id.startsWith(CONVERSATION_PREFIX) &&
            id.removePrefix(CONVERSATION_PREFIX).let { raw -> runCatching { UUID.fromString(raw).toString() == raw }.getOrDefault(false) }
    }
}
