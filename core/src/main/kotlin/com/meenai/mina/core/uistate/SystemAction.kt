package com.meenai.mina.core.uistate

/**
 * System actions performed through the Android standard interface (intent): setting alarm clocks and
 * timers, opening alarm clock lists, and opening prefilled new schedules. The clock and calendar App
 * installed on the mobile phone takes over; if there is no App, the time port will report
 * [SystemActionResult.NOT_SUPPORTED], and the model will return to the operation interface.
 */
sealed interface SystemAction {
    /** [days] is a day that repeats every week, written as java.util.Calendar (1 = Sunday... 7 = Saturday), empty = only rings once. */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null, val days: List<Int> = emptyList(), val vibrate: Boolean? = null) : SystemAction
    data class SetTimer(val seconds: Int, val label: String? = null) : SystemAction
    data object ShowAlarms : SystemAction
    /** The starting and ending time stamps are milliseconds; when [allDay], only the date is displayed. */
    data class AddCalendarEvent(
        val title: String, val beginMs: Long, val endMs: Long? = null, val allDay: Boolean = false,
        val location: String? = null, val description: String? = null,
    ) : SystemAction

    /** After handing it over to the App, no interface (alarm clock, timer) is left; the rest opens the page of that App on Mina's screen. */
    val silent: Boolean get() = this is SetAlarm || this is SetTimer
}

/**
 * The result of a system action. The reason for [result] failure is [NOT_SUPPORTED] (no App takes this
 * action), app_in_use_by_user, launch_failed, etc.; [handledBy] is the package name of the App taken
 * over; [nextAlarmBefore] / [nextAlarmAfter] is the next alarm clock (millisecond timestamp, null)
 * reported by the system before and after the action, [alarmReadable] is false Unable to read;
 * [showing]: After the action, the page [handledBy] was opened on Mina’s screen (it should have been set
 * quietly, but the App opened the interface).
 */
data class SystemActionResult(
    val result: ActionResult,
    val handledBy: String? = null,
    val nextAlarmBefore: Long? = null,
    val nextAlarmAfter: Long? = null,
    val alarmReadable: Boolean = false,
    val showing: Boolean = false,
) {
    companion object {
        const val NOT_SUPPORTED = "not_supported"
    }
}
