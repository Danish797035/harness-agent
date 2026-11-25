package com.meenai.mina.task

import com.meenai.mina.cap.web.TabBudget
import com.meenai.mina.core.uistate.AppWait
import com.meenai.mina.takeover.TakeoverQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock

/** Shared resource entry within the process. TaskCenter manages the run queue, which manages the resources that will compete with each other. */
class ResourceScheduler(
    val tabBudget: TabBudget = TabBudget(),
    val takeoverQueue: TakeoverQueue = TakeoverQueue(),
    val clipboard: ReentrantLock = ReentrantLock(true),
) {
    /** App (package name, another user's written as "package name@user") → occupies its task. */
    private val apps = HashMap<String, String>()
    /** Waiting for another task to release the app's task → the app it is waiting for. */
    private val waiting = HashMap<String, String>()
    /** If a task releases the app it occupies, it will be incremented by one: the waiting task will take a look at it immediately without waiting for the next polling. */
    private val released = MutableStateFlow(0L)

    /** Occupy and check in the same lock; allow takeover when the App has been released or closed by another task. */
    @Synchronized
    fun reserveApp(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        if (!appFree(packageName, owner, holderActive, stillUsed)) return false
        apps[packageName] = owner
        return true
    }

    /** [owner] Can it be occupied at this moment? [packageName]: No one is occupying it, it is occupied by myself, the occupied task is no longer there or it is no longer used ([stillUsed] is false). Only checking but not accounting. */
    @Synchronized
    fun appFree(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        val holder = apps[packageName]
        return holder == null || holder == owner || !holderActive(holder) || !stillUsed()
    }

    @Synchronized fun releaseOwner(owner: String) {
        waiting.remove(owner)
        if (apps.values.removeAll { it == owner }) released.value++
    }

    @Synchronized fun appHolder(packageName: String): String? = apps[packageName]

    /**
     * [owner] etc. [packageName] are released, up to [timeoutMs]. [free] is the complete judgment of the
     * caller (in addition to registration, check whether there is it on other screens). Check it once
     * every [pollMs], and check it again immediately when there is a task to release the app. Just wait
     * and don’t occupy it: wait until the caller arrives and then occupy or open it.
     *
     * Waiting for each other is not equal: the task occupying it (following "Who is waiting for the App
     * in whose hand" to find all the way) finally returns to [AppWait.WouldDeadlock] while waiting for
     * the App in the hand of [owner]; the search and registration are in the same lock, and the two
     * tasks start waiting at the same time. The one that comes later can see the one that came first.
     * [holderActive] The occupied task is still there (the absence is not counted as occupied).
     */
    suspend fun awaitApp(
        packageName: String, owner: String, timeoutMs: Long,
        holderActive: (String) -> Boolean, free: suspend () -> Boolean, pollMs: Long = POLL_MS,
    ): AppWait {
        if (!startWaiting(owner, packageName, holderActive)) return AppWait.WouldDeadlock
        try {
            return withTimeoutOrNull(timeoutMs) {
                while (!free()) {
                    val seen = released.value
                    withTimeoutOrNull(pollMs) { released.first { it != seen } }
                }
                AppWait.Free
            } ?: AppWait.TimedOut
        } finally {
            stopWaiting(owner)
        }
    }

    @Synchronized private fun startWaiting(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        if (wouldDeadlock(owner, packageName, holderActive)) return false
        waiting[owner] = packageName
        return true
    }

    @Synchronized private fun stopWaiting(owner: String) {
        waiting.remove(owner)
    }

    /** Starting from the occupier of [packageName], follow "whose app is it waiting for" and then go back to [owner] to wait for each other. */
    private fun wouldDeadlock(owner: String, packageName: String, holderActive: (String) -> Boolean): Boolean {
        fun holderOf(pkg: String) = apps[pkg]?.takeIf(holderActive)
        var holder = holderOf(packageName)?.takeIf { it != owner } ?: return false
        val seen = HashSet<String>()
        while (seen.add(holder)) {
            val next = holderOf(waiting[holder] ?: return false) ?: return false
            if (next == owner) return true
            holder = next
        }
        return false
    }

    private companion object {
        /** Apps on other screens will not notify you if you close them by yourself: take a look again after such a long time. */
        const val POLL_MS = 1_000L
    }
}
