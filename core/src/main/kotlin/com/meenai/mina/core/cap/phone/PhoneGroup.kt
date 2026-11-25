package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.uistate.tools.ScreenTool
import com.meenai.mina.core.tool.AgentTool

/** The ID of the mobile phone's capability is also the prefix of the name of the set of tools on the mobile phone. */
const val PHONE = "phone"

/** The snapshotId of the mobile phone screen starts with it (both the random number and the stable number of the port). You can tell it belongs to the mobile phone by looking at the number. */
const val PHONE_SNAPSHOT_PREFIX = "p"

/** The set of screen tools on the mobile phone: the experience route only remembers the methods on the mobile phone. */
internal val PHONE_FAMILY = ScreenFamily(PHONE, snapshotPrefix = PHONE_SNAPSHOT_PREFIX, routable = true, sandboxCallable = true, ownPrompts = setOf("observe", "back", "scroll", "type_text", "clear_text"))

/** Tools specific to mobile phones. */
internal interface PhoneTool : AgentTool {
    override val capability: String get() = PHONE
    override val sandboxCallable: Boolean get() = true
}

/**
 * A set of mobile phones: a set of screen tools (screen reading, image viewing, point, slide, input,
 * keystrokes, diagnostic screen), plus listing, opening apps and links, marking whether the app should
 * be left on or off when the task is over, and setting alarms, timers, schedules, and scroll wheels
 * through the system's standard interface. In the order issued to the model.
 */
internal object PhoneGroup {
    val tools: List<AgentTool> = ScreenTool.of(PHONE_FAMILY) + listOf(
        PhoneQuery, PhoneFind, PhoneExtract, PhoneCollect, PhoneWait, PhoneEventTool,
        PhoneDialog, PhoneGesture, SetWheel, PhoneRun, PhoneCommand, ListApps, LaunchApp, AppAfterTask, ShareText, OpenLink,
        SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent,
    )
}
