package com.meenai.mina.cap.web

/**
 * The upper limit of web page tags shared by all tasks in the process (up to [max] globally): each task
 * has a tag pool, and each pool is added as [Member]. "Recently used" Using the same clock here, you can
 * also compare across tasks to see who has not used it for the longest time. All member methods are
 * called on the main thread.
 */
class TabBudget(val max: Int = DEFAULT_MAX) {
    interface Member {
        fun tabCount(): Int
        /** The tag that has not been used for the longest time and can be turned off (number, last used time); locked (under takeover) and [except] are not counted. None is null. */
        fun oldestSpare(except: String?): Pair<String, Long>?
        /** Turn it off because of the global upper limit (send "limit" event). */
        fun evictForBudget(tabId: String)
    }

    private val members = mutableListOf<Member>()
    private var clock = 0L

    @Synchronized fun tick(): Long = ++clock

    @Synchronized fun join(m: Member) {
        if (members.none { it === m }) members += m
    }

    @Synchronized fun leave(m: Member) {
        members.removeAll { it === m }
    }

    fun total(): Int = snapshot().sumOf { it.tabCount() }

    fun full(): Boolean = total() >= max

    /** Close the tab that has been unused for the longest time among all tasks and can be closed; if there is no one that can be closed, false is returned. */
    fun evictOldest(except: String?): Boolean {
        val victim = snapshot().mapNotNull { m -> m.oldestSpare(except)?.let { m to it } }.minByOrNull { it.second.second } ?: return false
        victim.first.evictForBudget(victim.second.first)
        return true
    }

    @Synchronized private fun snapshot() = members.toList()

    companion object {
        const val DEFAULT_MAX = 8
    }
}
