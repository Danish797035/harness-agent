package com.meenai.mina.cap.phone.shell

/**
 * The record of tasks that "popped to the top" on the home screen (screen 0) is fed by the built-in
 * assistant every time it views the task list ([scan]). Only record the facts, without judging whether
 * it is caused by the Agent's action (on the Mina side, see MainScreenSpill): If a task is changed at
 * the top of the main screen, an entry will be recorded; it is still the same task, but if the top page
 * of the main screen is changed, an entry will be recorded ([Arrival.sameTask]). On each strip, which
 * screen the task was last seen on ([Arrival.from], [NEW] if it has not been seen before) is recognized
 * based on the screen it was moved from other screens (takeover). Pure logic, the clock is given by the
 * caller.
 */
internal class MainScreenLog(private val keepMs: Long = KEEP_MS) {
    /** I saw a task: [topActivity] is the complete class name of its top page, [standard] is the page of an ordinary App (not the desktop, recent tasks, assistants, etc.). */
    data class Task(
        val taskId: Int,
        val displayId: Int,
        val packageName: String?,
        val userId: Int,
        val topActivity: String?,
        val standard: Boolean = true,
    )

    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val topActivity: String? = null,
    )

    private var top: Task? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()

    /**
     * Feed the task list once ([tasks]: all tasks on the screen, sorted by recent use, in the same order
     * as the system getTasks). When [record] is false, only the current appearance is recorded, and the
     * appearance is not recorded (it was changed during the period when I was not staring at it, I can’t
     * tell when).
     */
    fun scan(tasks: List<Task>, now: Long, record: Boolean) {
        val mainTop = tasks.firstOrNull { it.displayId == MAIN }
        val last = top
        if (record && mainTop != null && last != null) {
            when {
                mainTop.taskId != last.taskId -> add(mainTop, now, displays[mainTop.taskId] ?: NEW, sameTask = false)
                mainTop.topActivity != last.topActivity -> add(mainTop, now, MAIN, sameTask = true)
            }
        }
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun add(t: Task, now: Long, from: Int, sameTask: Boolean) {
        arrivals.addLast(Arrival(t.taskId, t.packageName, t.userId, now, from, sameTask, t.standard, t.topActivity))
    }

    /** The topmost task on the home screen at the moment; it is -1 if it has not been viewed or there is no task on the home screen. */
    val topTaskId: Int get() = top?.taskId ?: -1

    /** Records after [since] (inclusive) are arranged in sequence. */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** The screen on which this task was last seen; -1 when it is no longer visible. */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        const val MAIN = 0
        const val NEW = -1
        const val KEEP_MS = 60_000L
    }
}
