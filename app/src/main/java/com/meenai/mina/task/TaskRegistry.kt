package com.meenai.mina.task

import com.meenai.mina.core.run.MemoryRunStore
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunPhase
import com.meenai.mina.core.run.RunStore
import com.meenai.mina.core.run.StoredRun
import com.meenai.mina.core.uistate.AppWait

sealed interface StartResult<out R> {
    data class Started<R>(val run: R) : StartResult<R>
    /** Exceeded the parallel limit, ranked [position] in the queue. */
    data class Queued<R>(val run: R, val position: Int) : StartResult<R>
    /** This conversation already has a task (running or queuing): it will not be accepted according to M1 rules, and the conversation will queue itself. */
    data object AlreadyRunningInConversation : StartResult<Nothing>
}

/**
 * Running tasks by conversation index, concurrency caps, and global locks across tasks. When the upper
 * limit is 1 (switch is off), it is M1 one at a time. [create] creates a task object during
 * registration; R is [TaskRun] in the product, and is replaced with a normal object in the test.
 */
class TaskCenter<R : Any>(
    private val limit: () -> ParallelLimit,
    private val conversationOf: (R) -> String,
    /** The unique number of the task (the notification button carries it); the numbers of the two tasks before and after the same conversation are different. */
    private val keyOf: (R) -> String,
    val store: RunStore = MemoryRunStore(),
    val resources: ResourceScheduler = ResourceScheduler(),
    private val create: (conversationId: String, goal: String) -> R,
) {
    private val running = mutableListOf<R>()
    private val queued = mutableListOf<R>()
    private val deferred = ArrayDeque<Pair<String, String>>()
    /** The caller during migration continues to take resources from here, and the actual ownership belongs to [resources]. */
    val clipLock get() = resources.clipboard
    val takeoverQueue get() = resources.takeoverQueue

    @Synchronized
    fun tryStart(conversationId: String, goal: String, queueInConversation: Boolean = false): StartResult<R> {
        val sameConversation = byConversation(conversationId) != null
        if (sameConversation && !queueInConversation) return StartResult.AlreadyRunningInConversation
        val run = create(conversationId, goal)
        return if (!sameConversation && running.size < limit().max) {
            store.save(StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Preparing))
            running += run
            StartResult.Started(run)
        } else {
            store.save(StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Queued))
            queued += run
            StartResult.Queued(run, queued.size)
        }
    }

    @Synchronized fun runs(): List<R> = running + queued
    @Synchronized fun running(): List<R> = running.toList()
    @Synchronized fun queued(): List<R> = queued.toList()
    @Synchronized fun isQueued(run: R): Boolean = queued.any { it === run }
    @Synchronized fun byConversation(id: String): R? = runs().firstOrNull { conversationOf(it) == id }
    @Synchronized fun byKey(key: String): R? = runs().firstOrNull { keyOf(it) == key }

    /**
     * Task [owner] wants to open [pkg] on its own screen: it is occupied when no one is occupying it, it
     * is occupied by itself, or the occupied task is no longer using it ([stillUsed] is false, for
     * example, the app is closed by itself) and returns true; otherwise, if another task is using it,
     * return false. Checking and accounting are in the same lock. Only one of them can succeed in
     * opening the same app at the same time.
     */
    @Synchronized
    fun reserve(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean {
        return resources.reserveApp(pkg, owner, { byKey(it) != null }, stillUsed)
    }

    /** Can task [owner] occupy [pkg](only check but not occupy, the rules are the same as [reserve]) at this moment? */
    @Synchronized
    fun appFree(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean =
        resources.appFree(pkg, owner, { byKey(it) != null }, stillUsed)

    /**
     * The task [owner] and other tasks are released from [pkg], up to [timeoutMs] (see
     * [ResourceScheduler.awaitApp]: Not equal to each other). Hang and wait, do not take the lock here;
     * press Run or not to start waiting for the task list at that moment.
     */
    suspend fun awaitApp(pkg: String, owner: String, timeoutMs: Long, free: suspend () -> Boolean): AppWait {
        val active = synchronized(this) { runs().map(keyOf).toSet() }
        return resources.awaitApp(pkg, owner, timeoutMs, { it in active }, free)
    }

    /** If it is still in the queue, it will be removed from the queue and return true (the caller records it as canceled); if it has started running, it will return false and the task will be canceled by itself. */
    @Synchronized
    fun cancelIfQueued(run: R): Boolean {
        if (queued.none { it === run }) return false
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended)) }
        return queued.removeAll { it === run }
    }

    /** The task ends (or is canceled while queuing); returns the task that was released from the queue and should start running. */
    @Synchronized
    fun onEnded(run: R): List<R> {
        if (queued.removeAll { it === run }) return emptyList()
        running.removeAll { it === run }
        resources.releaseOwner(keyOf(run))
        val started = mutableListOf<R>()
        while (queued.isNotEmpty() && running.size < limit().max) {
            val index = queued.indexOfFirst { candidate ->
                running.none { conversationOf(it) == conversationOf(candidate) } &&
                    queued.takeWhile { it !== candidate }.none { conversationOf(it) == conversationOf(candidate) }
            }
            if (index < 0) break
            val next = queued.removeAt(index)
            running += next
            store.get(RunId(keyOf(next)))?.let { store.save(it.copy(phase = RunPhase.Preparing)) }
            started += next
        }
        return started
    }

    @Synchronized fun markRunning(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Running, roundInProgress = true)) }
    }

    @Synchronized fun markResult(run: R, reply: String?, deliveryId: String? = null, successful: Boolean? = null) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Aftercare, reply = reply,
            deliveryId = deliveryId, runSucceeded = successful)) }
    }

    @Synchronized fun markDelivered(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
    }

    /**
     * The closing boundary received the original words of the user who can no longer inject the original
     * Run: If this conversation has no tasks at the moment, it will return true (the caller starts on
     * the spot); if it is left, it will return false, and it will be handed over by [takeReady] after it
     * is completed. Check and stay in the same lock, and [onEnded] will not be staggered.
     */
    @Synchronized fun deferOrStart(threadId: String, text: String): Boolean {
        if (byConversation(threadId) == null) return true
        deferred += threadId to text
        return false
    }

    /** Among the words left behind, take away those that no longer have tasks in the conversation (in the order you clicked). */
    @Synchronized fun takeReady(): List<Pair<String, String>> {
        val ready = deferred.filter { (threadId, _) -> byConversation(threadId) == null }
        deferred.removeAll { (threadId, _) -> byConversation(threadId) == null }
        return ready
    }
}

/** Existing callers and tests can still be gradually moved to TaskCenter under the old name during the migration period. */
typealias TaskRegistry<R> = TaskCenter<R>
