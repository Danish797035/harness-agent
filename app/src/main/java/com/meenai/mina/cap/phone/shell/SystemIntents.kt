package com.meenai.mina.cap.phone.shell

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.meenai.mina.core.uistate.SystemAction

/**
 * Android standard intent for system actions: set alarm clock, timer, open alarm clock list
 * (AlarmClock), open prefilled new schedule (CalendarContract insertion page). First write it as [Spec]
 * which has nothing to do with Android, and then change it to [Intent]. The built-in assistant only
 * allows these types ([allowed]) and does not start any intent for Mina.
 */
object SystemIntents {
    /** The schedule insertion page is matched by type: the type is specified directly and does not depend on the calendar provider to check (checking it requires the visibility of the calendar). */
    const val EVENTS_TYPE = "vnd.android.cursor.dir/event"
    const val EVENTS_URI = "content://${CalendarContract.AUTHORITY}/events"

    /** Mina's compatibility self-check page: The built-in assistant recognizes this component and opens it directly to Mina's screen as a shell without pressing [allowed]. */
    const val OWN_PAGE_CLASS = "com.meenai.mina.selfcheck.SelfCheckTargetActivity"

    /** A page that can receive this intent; [system] is the app that comes with the system. */
    data class Handler(val packageName: String, val className: String, val system: Boolean)

    /**
     * Which one to give: there is only one and it is it; if the user has set the default one
     * ([defaultPackage], the package parsed by the system by default, not the selector), use the default
     * one; otherwise, choose the one that comes with the system first, and then follow the order.
     * Directly specify that page: quietly set actions should not leave a "which one to use" selection
     * box on Mina's screen. If not, it is null (not supported, the model returns to the operation
     * interface).
     */
    fun choose(handlers: List<Handler>, defaultPackage: String?): Handler? =
        handlers.singleOrNull()
            ?: handlers.firstOrNull { it.packageName == defaultPackage }
            ?: handlers.firstOrNull { it.system }
            ?: handlers.firstOrNull()

    /** Contents of an intent: actions, data and types, additional values (only Int, Long, Boolean, String, list of integers). */
    data class Spec(val action: String, val data: String? = null, val type: String? = null, val extras: Map<String, Any> = emptyMap())

    fun spec(a: SystemAction): Spec = when (a) {
        is SystemAction.SetAlarm -> Spec(AlarmClock.ACTION_SET_ALARM, extras = buildMap {
            put(AlarmClock.EXTRA_HOUR, a.hour)
            put(AlarmClock.EXTRA_MINUTES, a.minute)
            // Interface without clock: set it directly
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
            if (a.days.isNotEmpty()) put(AlarmClock.EXTRA_DAYS, ArrayList(a.days))
            a.vibrate?.let { put(AlarmClock.EXTRA_VIBRATE, it) }
        })
        is SystemAction.SetTimer -> Spec(AlarmClock.ACTION_SET_TIMER, extras = buildMap {
            put(AlarmClock.EXTRA_LENGTH, a.seconds)
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
        })
        SystemAction.ShowAlarms -> Spec(AlarmClock.ACTION_SHOW_ALARMS)
        is SystemAction.AddCalendarEvent -> Spec(Intent.ACTION_INSERT, EVENTS_URI, EVENTS_TYPE, buildMap {
            put(CalendarContract.Events.TITLE, a.title)
            put(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.beginMs)
            a.endMs?.let { put(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (a.allDay) put(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            a.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        })
    }

    /** The built-in assistant only activates these types of actions for Mina: actions are in the table, and only schedules can be inserted. */
    fun allowed(action: String?, data: String?): Boolean = when (action) {
        AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER, AlarmClock.ACTION_SHOW_ALARMS -> data == null
        Intent.ACTION_INSERT -> data == EVENTS_URI
        else -> false
    }

    fun toIntent(s: Spec): Intent {
        val i = Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            s.data != null && s.type != null -> i.setDataAndType(Uri.parse(s.data), s.type)
            s.data != null -> i.data = Uri.parse(s.data)
            s.type != null -> i.type = s.type
        }
        for ((k, v) in s.extras) when (v) {
            is Int -> i.putExtra(k, v)
            is Long -> i.putExtra(k, v)
            is Boolean -> i.putExtra(k, v)
            is String -> i.putExtra(k, v)
            is ArrayList<*> -> i.putIntegerArrayListExtra(k, ArrayList(v.filterIsInstance<Int>()))
            else -> error("unsupported extra $k")
        }
        return i
    }
}
