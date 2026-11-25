package com.meenai.mina.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** What will happen if you delete a conversation: are there any running or queued tasks (which will be stopped), and how many scheduled tasks are tied to it (which will be deleted together). */
data class DeleteImpact(val runningTask: Boolean, val schedules: Int)

/**
 * To delete a conversation: first delete the scheduled tasks bound to it (preventing them from starting
 * new tasks), then stop the running and queued tasks and wait for them to finish (the round saved at the
 * end is also within the scope of deletion), then delete its workspace (attachments, screens sent by
 * users, files written by tasks) and conversation records. Running records (for running statistics) are
 * global and will not be deleted.
 */
class ConversationDeleter(
    private val store: ConversationStore,
    /** Dialogue with tasks running or queued. */
    private val active: StateFlow<List<String>>,
    /** Stop all running and queued tasks for this conversation. */
    private val cancelTasks: (conversationId: String) -> Unit,
    /** The number of the scheduled task tied to this conversation. */
    private val schedulesOf: (conversationId: String) -> List<String>,
    private val removeSchedule: (id: String) -> Unit,
    /** Delete this conversation's workspace. */
    private val deleteWorkspace: suspend (conversationId: String) -> Unit,
    /** After stopping a task, you can wait this long for it to finish at most; delete it after that. */
    private val stopWaitMs: Long = 15_000,
    /** Where to read and write timed tasks and conversation records. */
    private val io: CoroutineContext = Dispatchers.IO,
) {
    fun impact(conversationId: String): DeleteImpact =
        DeleteImpact(conversationId in active.value, runCatching { schedulesOf(conversationId).size }.getOrDefault(0))

    suspend fun delete(conversationId: String) {
        withContext(io) {
            runCatching { schedulesOf(conversationId) }.getOrDefault(emptyList()).forEach { runCatching { removeSchedule(it) } }
        }
        if (conversationId in active.value) {
            cancelTasks(conversationId)
            withTimeoutOrNull(stopWaitMs) { active.first { conversationId !in it } }
        }
        // Even if the workspace cannot be deleted (the operating environment does not support it, etc.),
        // the conversation records will still be deleted.
        try { deleteWorkspace(conversationId) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        withContext(io) { store.delete(conversationId) }
    }
}
