package com.meenai.mina.core.trigger

/** Schedule management for native users; host is responsible for persistence and rescheduling alarms. */
interface ScheduleAdmin {
    fun list(threadId: String): List<TriggerDefinition>
    fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean
    fun remove(threadId: String, id: String): Boolean
    fun reschedule(threadId: String, id: String, atMs: Long): Boolean
    fun runNow(threadId: String, id: String): Boolean
}

/**
 * The situation after saving a scheduled task: [onTime] is false, which means that the system does not
 * allow the alarm clock to be on time (it may not be on time or unable to get up); at this time,
 * [userNotified] says whether there is really a prompt to the user to activate it this time.
 */
data class Scheduled(val onTime: Boolean, val userNotified: Boolean = false)
