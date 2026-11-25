package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Entry
import com.meenai.mina.core.tool.EvidenceLog
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.bool
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.str
import com.meenai.mina.core.tool.strArray
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.SystemAction
import com.meenai.mina.core.uistate.SystemActionResult
import com.meenai.mina.core.uistate.action
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Alarm clocks, timers, and schedules are handed over to the clock and calendar apps on your phone
 * through Android’s standard interfaces without having to operate their interfaces step by step. If
 * there is no App connected, it will report not_supported, and the model will return to the operation
 * interface. Just change things on your phone, don’t spend money: the risk is the same as opening the
 * app.
 */

/** The seven days of the week are written in the parameters → java.util.Calendar day of the week (Sunday = 1). */
private val WEEKDAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

/** The local time written to the model is written in the result. */
private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal object SetAlarm : PhoneTool {
    override val verb = Verb.SetAlarm
    override val label = "Set alarm"
    override val risk = Risk.Write
    override val entry = Entry.Alone
    override val logged = true
    override val def = ToolDef("phone_set_alarm", Prompts.tool("phone_set_alarm"), mapOf(
        "hour" to int("Hour of day, 0-23 (24-hour clock).", 0, 23),
        "minute" to int("Minute, 0-59.", 0, 59),
        "label" to str("Optional label shown with the alarm."),
        "days" to strArray("Optional repeat days: MON, TUE, WED, THU, FRI, SAT, SUN. Omit for a one-time alarm.", 7),
        "vibrate" to bool("Optional: vibrate when it rings."),
    ), listOf("hour", "minute"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val hour = a.int("hour", 0, 23)!!
        val minute = a.int("minute", 0, 59)!!
        val days = a.strList("days", 1, 7)?.map { d ->
            WEEKDAYS.indexOf(d.trim().uppercase().take(3)).takeIf { it >= 0 }?.plus(1)
                ?: throw ArgError("days", "items must be MON, TUE, WED, THU, FRI, SAT or SUN")
        }?.distinct()?.sorted().orEmpty()
        val label = a.str("label", max = 100)
        val vibrate = a.o["vibrate"]?.let { a.bool("vibrate", false) }
        val res = ctx.phone.screen.systemAction(SystemAction.SetAlarm(hour, minute, label, days, vibrate))
        return systemResult(ctx, res) {
            put("requested", buildJsonObject {
                put("time", "%02d:%02d".format(hour, minute))
                label?.let { put("label", it) }
                if (days.isNotEmpty()) put("days", JsonArray(days.map { JsonPrimitive(WEEKDAYS[it - 1]) }))
            })
            val check = AlarmCheck.of(hour, minute, res)
            put("check", check.wire)
            res.nextAlarmAfter?.let { put("nextAlarm", local(it)) }
            hint(this, when (check) {
                AlarmCheck.Status.VERIFIED -> null
                AlarmCheck.Status.EARLIER_FIRST -> Feedback.format("alarm.earlier_first", "next" to local(res.nextAlarmAfter!!))
                AlarmCheck.Status.NOT_SEEN, AlarmCheck.Status.UNKNOWN -> Feedback["alarm.unconfirmed"]
            }, res, silent = true)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = time(r)

    private fun time(r: ToolExecution): String? =
        ((r.content["requested"] as? JsonObject)?.get("time") as? JsonPrimitive)?.contentOrNull

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val time = time(r) ?: return emptyList()
        val check = (r.content["check"] as? JsonPrimitive)?.contentOrNull
        val by = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull
        val tail = if (check == AlarmCheck.Status.VERIFIED.wire) ",系统报的下一个闹钟就是它" else ",系统没报出它(未核实)"
        return listOf(Observation(ObsKind.Fact, "$name 把 $time 的闹钟交给了 ${by ?: "时钟 App"}$tail", source = PHONE))
    }
}

internal object SetTimer : PhoneTool {
    override val verb = Verb.SetTimer
    override val label = "Set timer"
    override val risk = Risk.Write
    override val entry = Entry.Alone
    override val logged = true
    override val def = ToolDef("phone_set_timer", Prompts.tool("phone_set_timer"), mapOf(
        "seconds" to int("Length of the timer in seconds, 1-86400.", 1, 86400),
        "label" to str("Optional label shown with the timer."),
    ), listOf("seconds"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val seconds = a.int("seconds", 1, 86400)!!
        val label = a.str("label", max = 100)
        val res = ctx.phone.screen.systemAction(SystemAction.SetTimer(seconds, label))
        return systemResult(ctx, res) {
            put("requested", buildJsonObject { put("seconds", seconds); label?.let { put("label", it) } })
            hint(this, null, res, silent = true)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = seconds(o)

    private fun seconds(o: JsonObject): String? = (o["seconds"] as? JsonPrimitive)?.contentOrNull?.let { "$it s" }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val by = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull
        return listOf(Observation(ObsKind.Fact, "$name 把 ${seconds(o)} 的计时器交给了 ${by ?: "时钟 App"}", source = PHONE))
    }
}

/** Open the alarm list of the clock on Mina's screen: check, delete, and change alarms are all operated on that page. */
internal object ShowAlarms : PhoneTool {
    override val verb = Verb.ShowAlarms
    override val label = "Show alarms"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_show_alarms", Prompts.tool("phone_show_alarms"), emptyMap(), emptyList())

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        ctx.refusedPay = null
        val res = ctx.phone.screen.systemAction(SystemAction.ShowAlarms)
        if (res.result is ActionResult.Ok) ctx.appOpened = true
        return systemResult(ctx, res) { hint(this, Feedback["alarms.opened"], res, silent = false) }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull
}

/** Open a new schedule page pre-populated by the calendar: click Save by Model on that page, nothing will be saved here; no calendar permissions are required. */
internal object AddCalendarEvent : PhoneTool {
    override val verb = Verb.AddCalendarEvent
    override val label = "Add calendar event"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_add_calendar_event", Prompts.tool("phone_add_calendar_event"), mapOf(
        "title" to str("Event title."),
        "start" to str("Start as local date-time yyyy-MM-ddTHH:mm (e.g. 2026-11-02T14:30), or a date yyyy-MM-dd with allDay=true."),
        "end" to str("Optional end, same format as start. Defaults to one hour after start (one day for all-day events)."),
        "allDay" to bool("All-day event (default false)."),
        "location" to str("Optional location."),
        "description" to str("Optional notes."),
    ), listOf("title", "start"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val title = a.str("title", max = 200)!!
        val allDay = a.bool("allDay", false)
        val zone = ZoneId.systemDefault()
        val begin = CalendarTime.parse(a.str("start")!!, allDay, zone) ?: throw ArgError("start", "must look like 2026-11-02T14:30 (or 2026-11-02 with allDay=true)")
        val end = a.str("end")?.let { CalendarTime.parse(it, allDay, zone) ?: throw ArgError("end", "must use the same format as start") }
        if (end != null && end < begin) throw ArgError("end", "must not be before start")
        ctx.refusedPay = null
        val res = ctx.phone.screen.systemAction(SystemAction.AddCalendarEvent(
            title, begin, end ?: CalendarTime.defaultEnd(begin, allDay), allDay, a.str("location", max = 200), a.str("description", max = 2000),
        ))
        if (res.result is ActionResult.Ok) ctx.appOpened = true
        return systemResult(ctx, res) {
            put("requested", buildJsonObject {
                put("title", title); put("start", local(begin)); end?.let { put("end", local(it)) }
                if (allDay) put("allDay", true)
            })
            hint(this, Feedback["calendar.opened"], res, silent = false)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = (o["title"] as? JsonPrimitive)?.contentOrNull

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val by = (r.content["handledBy"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return listOf(Observation(ObsKind.Fact, "$name 打开了 $by 的新日程页(还没保存)", source = PHONE))
    }
}

/** Result: The action result of the port is transferred as usual (failure will be explained according to the error code), and the taken over App and [more] will be added when successful. */
private fun systemResult(ctx: ToolContext, res: SystemActionResult, more: JsonObjectBuilder.() -> Unit): ToolExecution {
    val r = ctx.action(res.result)
    if (r.content["ok"] != JsonPrimitive(true)) return r
    val extra = buildJsonObject {
        res.handledBy?.let { put("handledBy", it) }
        if (res.showing) put("screenOpened", true)
        more()
    }
    // The prompts given by the port (cut away, opened to the home screen...) are listed first, and the
    // instructions here follow.
    val hints = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, (extra["hint"] as? JsonPrimitive)?.content)
    val merged = r.content + extra + (if (hints.isEmpty()) emptyMap() else mapOf("hint" to JsonPrimitive(hints.joinToString(" "))))
    return ToolExecution(JsonObject(merged), changed = r.changed)
}

/** Explanation of this type of result: ([silent]) should have been set quietly but it says this when the interface is opened, otherwise [normal] is used. */
private fun hint(b: JsonObjectBuilder, normal: String?, res: SystemActionResult, silent: Boolean) {
    (if (silent && res.showing) Feedback["system_action.screen_opened"] else normal)?.let { b.put("hint", it) }
}

private fun local(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(LOCAL_TIME)

/** Does the next alarm clock reported by the system match the one to be set? */
internal object AlarmCheck {
    enum class Status(val wire: String) {
        /** The next alarm clock reported by the system is at this time. */
        VERIFIED("verified"),
        /** The next one is another alarm clock earlier, this one cannot be seen. */
        EARLIER_FIRST("earlier_alarm_first"),
        /** The system did not report it (there is no next alarm, or the next one is later than it). */
        NOT_SEEN("not_seen"),
        /** The system's next alarm cannot be read. */
        UNKNOWN("unknown"),
    }

    fun of(hour: Int, minute: Int, res: SystemActionResult, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Status {
        if (!res.alarmReadable) return Status.UNKNOWN
        val next = res.nextAlarmAfter ?: return Status.NOT_SEEN
        val at = Instant.ofEpochMilli(next).atZone(zone)
        if (at.hour == hour && at.minute == minute) return Status.VERIFIED
        // When is the next time at this moment: The next alarm clock is earlier than it, that is, it was
        // blocked earlier.
        val mine = nextOccurrence(hour, minute, Instant.ofEpochMilli(now).atZone(zone))
        return if (next < mine) Status.EARLIER_FIRST else Status.NOT_SEEN
    }

    private fun nextOccurrence(hour: Int, minute: Int, now: ZonedDateTime): Long {
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }
}

/** Schedule time: local date and time (or whole day with only date), with time zone offset also recognized. */
internal object CalendarTime {
    fun parse(s: String, allDay: Boolean, zone: ZoneId): Long? {
        val t = s.trim()
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse("${t}:00").atZone(zone).toInstant().toEpochMilli() }
        if (allDay) runCatching { return LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    fun defaultEnd(begin: Long, allDay: Boolean): Long = begin + if (allDay) 24 * 3_600_000L else 3_600_000L
}
