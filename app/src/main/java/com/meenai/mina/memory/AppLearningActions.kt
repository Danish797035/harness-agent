package com.meenai.mina.memory

import android.content.Context
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.ui.LearningActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The memory and skill prompt buttons on the result card act on the same memory and personal skill ([memory]) in the process; both reading and writing are in the background thread. */
class AppLearningActions(private val context: Context, private val memory: MemoryRuntime) : LearningActions {
    private val store get() = memory.store()
    private val skills get() = memory.skills()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override suspend fun undo(token: String) = io { store.undo(token) }
    override suspend fun approve(pendingId: String) = io { store.approve(pendingId) }
    override suspend fun forget(memoryId: String) = io { store.forget(memoryId).isSuccess }
    override fun viewSkill(draftId: String) = memory.requestOpen(MemoryOpen(draftId))
    // Clear old results when restoring to avoid being immediately deactivated next time something goes
    // wrong.
    override suspend fun restoreSkill(name: String) {
        io { skills.update(name) {
            it.copy(status = it.restoredStatus(), outcomes = emptyList())
        } }
    }
    override suspend fun deleteSkill(name: String) = io { skills.remove(name) }
    override suspend fun save(notes: RunNotes) {
        memory.publish(notes)
        io {
            runCatching {
                ConversationStore.shared(context).updateTurn(notes.conversationId, notes.runId) {
                    it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes)
                }
            }
        }
    }
}
