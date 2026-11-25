package com.meenai.mina.core.remote

import com.meenai.mina.core.agent.TaskState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A task handed over by the computer: [state] is empty, which means it has been handed over to the task service and has not been registered yet (it will be available soon). */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: TaskState?,
    val steers: List<RemoteSteer> = emptyList(),
)

/** Only reports messages appended by the computer; joined means entering the current task context, not successful execution. */
data class RemoteSteer(val messageId: String, val state: String)

sealed interface StartOutcome {
    data class Started(val task: RemoteTask) : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/**
 * The task portal connected to the computer on the App side: the task is still started through the task
 * service, a record is left in the conversation, and the status reads the task's own, and there is no
 * separate copy here. It only recognizes tasks handed over by the computer (press [RemoteTask.id]).
 * Tasks initiated on the mobile phone cannot be seen or moved.
 */
interface RemoteTasks {
    fun start(goal: String, conversation: String?): StartOutcome
    fun get(id: String): RemoteTask?
    /** New first. */
    fun list(limit: Int): List<RemoteTask>
    /** Only called when the task is waiting to answer a question; returns true when handed over to the task. */
    fun answer(id: String, text: String): Boolean
    /** Atomic confirmation messages go into the current task's inbox; they cannot be transferred to a new task. Returns the message number, rejects as null. */
    fun steer(id: String, text: String): String?
    fun cancel(id: String): Boolean
}

/** What the task status says to the computer; only the status of the task itself is read, and nothing else is recorded. */
object RemoteStatus {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val QUEUED = "queued"
    const val PAUSED = "paused"
    const val WAITING_FOR_ANSWER = "waiting_for_answer"
    const val WAITING_ON_PHONE = "waiting_on_phone"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered remotely. " +
        "Harness Agent has shown its usual notification there; keep waiting or cancel the task."

    fun of(state: TaskState?): String = when (state) {
        null, TaskState.Idle -> STARTING
        is TaskState.Running -> RUNNING
        is TaskState.Held -> when {
            state.reason.needsYou -> WAITING_ON_PHONE
            state.reason.queued -> QUEUED
            else -> PAUSED
        }
        is TaskState.WaitingInput -> WAITING_FOR_ANSWER
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> WAITING_ON_PHONE
        is TaskState.Succeeded -> DONE
        is TaskState.Failed -> FAILED
        TaskState.Cancelled -> CANCELLED
    }

    fun finished(status: String) = status == DONE || status == FAILED || status == CANCELLED

    /** What a task looks like at the moment: status, recent progress notes, what to wait for, and results. */
    fun describe(t: RemoteTask): JsonObject = buildJsonObject {
        val s = t.state
        val status = of(s)
        put("task_id", t.id)
        put("status", status)
        put("conversation", t.conversation)
        put("goal", t.goal)
        put("started_at_ms", t.createdAtMs)
        when (s) {
            is TaskState.Running -> {
                put("step", s.step)
                (s.narration ?: s.reason ?: s.note).takeIf { it.isNotBlank() }?.let { put("progress", it) }
            }
            is TaskState.Held -> {
                put("waiting_for", s.reason.code)
                s.reason.why.takeIf { it.isNotBlank() }?.let { put("progress", it) }
                if (s.reason.needsYou) put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingInput -> {
                put("question", s.prompt)
                s.options?.takeIf { it.isNotEmpty() }?.let { o -> putJsonArray("options") { o.forEach { add(it) } } }
                put("note", "Harness Agent is asking a question. Answer this question remotely or on the phone.")
            }
            is TaskState.WaitingConfirmation -> {
                put("waiting_for", "confirmation")
                put("app", s.request.app)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingTakeover -> {
                put("waiting_for", "hand_to_user")
                put("reason", s.reason)
                put("message", s.message)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.Succeeded -> {
                put("answer", s.answer)
                if (s.reviewed) put("verified", s.verified)
                s.note?.let { put("verification_note", it) }
                if (s.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { s.unconfirmed.forEach { add(it) } }
            }
            is TaskState.Failed -> {
                put("reason", s.reason)
                put("failure_kind", s.kind.name.lowercase())
            }
            null, TaskState.Idle, TaskState.Cancelled -> Unit
        }
        put("answerable", s is TaskState.WaitingInput)
        if (t.steers.isNotEmpty()) putJsonArray("steers") {
            t.steers.forEach { add(buildJsonObject { put("message_id", it.messageId); put("state", it.state) }) }
        }
    }
}
