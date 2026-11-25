package com.meenai.mina.core.run

/** Minimal persistent record of running status. After the operation is interrupted, you will only be notified and allowed to rerun, and will not resume running from the screen. */
data class StoredRun(
    val id: RunId,
    val threadId: String,
    val goal: String,
    val phase: RunPhase,
    val reply: String? = null,
    val deliveryId: String? = null,
    val roundInProgress: Boolean = false,
    val triggerWindowKey: String? = null,
    val runSucceeded: Boolean? = null,
)

interface RunStore {
    fun save(record: StoredRun)
    fun get(id: RunId): StoredRun?
    fun all(): List<StoredRun>

    /** When restarting, interrupts are marked one by one, and the tool call cannot be run silently again. [live] contains the operations that are still running at the moment, so don’t move them. */
    fun interruptUnfinished(live: Set<RunId> = emptySet()): List<StoredRun> {
        val interrupted = all().filter { it.phase != RunPhase.Ended && it.phase != RunPhase.Interrupted && it.id !in live }
        interrupted.forEach { save(it.copy(phase = RunPhase.Interrupted)) }
        return interrupted
    }
}

class MemoryRunStore : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()
    @Synchronized override fun save(record: StoredRun) { records[record.id] = record }
    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()
}
