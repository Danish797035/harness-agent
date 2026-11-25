package com.meenai.mina.core.shell

/** Six steps of guidance, in order. */
enum class SetupStep { DeveloperOptions, UsbDebugging, Accessibility, WirelessDebugging, PairingCode, Connect }

/** [Skipped]: The user skipped this step (only accessible). */
enum class StepMark { Done, Current, Todo, Failed, Skipped }

enum class SetupPhase {
    /** Wait for the user to take a certain step. */
    Guide,
    /** Pairing, connecting, starting: Shows progress. */
    Working,
    Success,
    /** Error: [SetupView.failure] gives the reason and fix, [SetupView.suspect] points out the most likely step to go back and redo. */
    Failed,
    /** There is no way to get an assistant in this environment (see [PhoneSetupFlow.unavailable]): explain it from the beginning and don’t give any steps that won’t work. */
    Unavailable,
}

/** The pairing notification channel looks like: [Off] is turned off and notifications cannot be sent; [Lowered] is adjusted by the user not to pop up at the top of the screen, and the notification bar can be pulled down to fill it. */
enum class PairingChannelHealth { Ready, Lowered, Off }

/** The pairing code must be filled in the notification to notify the unprepared areas. */
enum class PairingNotifyIssue {
    /** Mina is not allowed to send notifications (permission or notification switch for the entire app). */
    NotificationsOff,
    /** "Pairing" notifications have been turned off. */
    ChannelOff,
    /** "Pairing" notification will not pop up: do not block, only remind. */
    NotHeadsUp,
}

/**
 * Input on the boot page: the switches read in the system settings, the user's own confirmation and the
 * status of the assistant. The read switch is only used to automatically tick and give prompts, and does
 * not block it: some mobile phones cannot read it or cannot read it accurately. The user clicks "I have
 * opened it, next step" ([confirmed]) and goes down. [wirelessDebugging] is null, which means that the
 * mobile phone cannot read this switch (do not write the setting item according to the original
 * version): do not block it, and it will be regarded as open and going down. [confirmed] refers to the
 * steps that the user said have been completed; [revisit] refers to the user who clicked on a previous
 * step in the list and wants to go back and redo it. [repair] is caused by the user clicking
 * "re-pairing" on the settings page: even if it is already running, it will return to the pairing step
 * until the new pairing starts. When [usbDebugging] is on, the assistant will not stop even if Wi-Fi is
 * disconnected or wireless debugging is turned off by the system. [accessibility] is Mina's
 * accessibility service running: it reads the pairing code during pairing; users can skip
 * ([accessibilitySkipped]) and return it to fill in the notification. [restrictedSettings] The system
 * may prevent accessibility from being opened (apps that are not installed from the app store must first
 * allow restricted settings in the app information). [notificationsAllowed] and [pairingChannel]
 * determine whether the pairing code can be filled in the notification. The following three decisions
 * are made as to whether to "switch to computer startup" (see [SetupView.usbFallback]):
 * [wirelessMissing] means that there is no wireless debugging in the system at all (this setting is
 * always written when the original version is turned on, and if it cannot be read, it means the system
 * does not have this function); [stalledMs] means that there has been no progress in wireless debugging,
 * pairing (the pairing window has not been found yet), and automatic connection (see
 * [PhoneSetupFlow.stallPoint]); [usbAsked] It is because the user clicked "Cannot find wireless
 * debugging?", or the card has already appeared (it will not be taken back after it appears, so as not
 * to disappear for a while). [restart] is what the assistant relies on to get back up after it stops
 * (see [HelperRestart]): when it is started with only the USB startup command, the wireless debugging
 * and pairing steps are recorded as skipped. When the assistant stops, it stops at the connection step
 * and says it has stopped, giving a command, and does not return to the wireless debugging step.
 * [developerSettingsMissing] cannot open the "Developer Options" page in the system (there is no page
 * connection for the skipped Intent): When it is established together with [wirelessMissing], the user
 * cannot turn on either USB debugging or wireless debugging, see [PhoneSetupFlow.unavailable].
 */
data class SetupInputs(
    val developerOptions: Boolean,
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
    val discovery: PairingDiscovery = PairingDiscovery.Idle,
    val notificationsAllowed: Boolean = true,
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    val repair: Boolean = false,
    val usbDebugging: Boolean = true,
    val accessibility: Boolean = true,
    val accessibilitySkipped: Boolean = false,
    val restrictedSettings: Boolean = false,
    val confirmed: Set<SetupStep> = emptySet(),
    val revisit: SetupStep? = null,
    val wirelessMissing: Boolean = false,
    val stalledMs: Long = 0L,
    val usbAsked: Boolean = false,
    val restart: HelperRestart = HelperRestart.None,
    val developerSettingsMissing: Boolean = false,
)

/** The most likely reason when pairing or connection fails: [step] is turned off when reading this step; [unreadable] is really not readable by this phone at all. */
data class SetupSuspect(val step: SetupStep, val unreadable: Boolean)

data class SetupView(
    val phase: SetupPhase,
    val marks: Map<SetupStep, StepMark>,
    /** The step that should be done at this moment (Guide and Failed); null when Working/Success. */
    val current: SetupStep?,
    val failure: BuiltinShellStatus.Failed? = null,
    /** The pairing window has been found: prompt the user to fill in the code in the notification. */
    val portFound: Boolean = false,
    /** The problem is notified here when stopping at the pairing step; when [needsNotifications] is true, it must be fixed first before filling in the code in the notification. */
    val notifyIssue: PairingNotifyIssue? = null,
    /** Open without hindrance during the first step of pairing: Mina reads the pairing code by herself, no need to fill it in the notification. */
    val autoRead: Boolean = false,
    /** The accessibility step provides the entrance to "Go to application information to allow restricted settings". */
    val showAppInfo: Boolean = false,
    /** When you fail, you are most likely to have to go back and redo the previous step, and the interface will give you a go back button. */
    val suspect: SetupSuspect? = null,
    /** The current step is for the user to click back and redo: the interface gives "return to the original step". */
    val revisiting: Boolean = false,
    /** In the current step, the card for "Switch to computer to start": wireless debugging is probably not available, or it is stuck. Not given when walking normally. */
    val usbFallback: Boolean = false,
    /** What to rely on to get up again after the assistant stops (copied from [SetupInputs.restart]): the reminder on the success page and what the "Start with computer instead" card says. Look at it. */
    val restart: HelperRestart = HelperRestart.None,
) {
    /** The notification cannot be sent (not allowed, or the "matching" notification is turned off): The pairing code needs to be filled in the notification and needs to be fixed first. */
    val needsNotifications: Boolean
        get() = notifyIssue == PairingNotifyIssue.NotificationsOff || notifyIssue == PairingNotifyIssue.ChannelOff
}

/**
 * The state machine of the boot page: every input change (system setting switch, user confirmation,
 * assistant status, progress of finding the matching window) is recalculated, and no intermediate state
 * is saved. Success only depends on the assistant: once it is connected, it is set up, regardless of
 * what was read before. If the previous steps are completed, it will be automatically checked; if it is
 * not completed and the user has not confirmed, it will stop at that step, but the user can click "I
 * have opened it, next step" at any time to go down, so no step will be stuck. When it reads that the
 * switch has been turned off and the time has not been confirmed, no matter what the assistant did
 * before, it will return to that step.
 */
object PhoneSetupFlow {
    /** There are several types of pairing and connection that may fail because the front switch is not turned on (if the pairing code is incorrect, it means that the pairing window is open, so it does not count). */
    private val SWITCH_RELATED = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.Unknown)

    /** There are several steps that the user can click back to redo: there is nothing for the user to do in the automatic connection step. */
    val REVISITABLE: Set<SetupStep> = SetupStep.entries.toSet() - SetupStep.Connect

    /** There are several steps of "I have already turned it on, next step": what is read is the system switch (there is a skip for accessibility). */
    val CONFIRMABLE: Set<SetupStep> = setOf(SetupStep.DeveloperOptions, SetupStep.UsbDebugging, SetupStep.WirelessDebugging)

    /** It stopped at [stallPoint] and there was no progress for so long, so I gave "Start using computer instead". */
    const val STALL_MS = 45_000L

    /** These types of failures indicate that the path of wireless debugging is unavailable, and the problem is "use computer startup instead": the pairing window cannot be found, the connection cannot be made, and the computer startup assistant has stopped. */
    private val USB_FAILURES = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped)

    fun view(i: SetupInputs): SetupView {
        if (unavailable(i)) return SetupView(SetupPhase.Unavailable, SetupStep.entries.associateWith { StepMark.Todo }, null, restart = i.restart)
        return steps(i).let { it.copy(usbFallback = usbFallback(it, i), restart = i.restart) }
    }

    /**
     * Whether this environment can be set up for mobile phone operation: the developer options page
     * cannot be opened in the system, and there is no wireless debugging item, so there is no way to
     * activate the assistant (USB debugging and wireless debugging are both in the developer options,
     * and USB debugging is also required when starting the computer). If the assistant is already
     * running, is getting up, or has been up before, it means there is another way, and it does not
     * count.
     */
    fun unavailable(developerSettingsMissing: Boolean, wirelessMissing: Boolean, status: BuiltinShellStatus, restart: HelperRestart): Boolean =
        developerSettingsMissing && wirelessMissing && restart == HelperRestart.None &&
            status != BuiltinShellStatus.Running && status != BuiltinShellStatus.Connecting

    fun unavailable(i: SetupInputs): Boolean =
        unavailable(i.developerSettingsMissing, i.wirelessMissing, i.status, i.restart)

    /**
     * Where does the stuck timing count: stopped at the wireless debugging step, stopped at the pairing
     * step but has not found the pairing window (the system has no pairing service), and is
     * automatically connecting. In the first few steps, the user has to go to the system settings to
     * find it. It doesn't matter how long it takes. If the location is changed, it will be calculated
     * from the beginning.
     */
    fun stallPoint(v: SetupView): SetupStep? = when {
        v.phase == SetupPhase.Working -> SetupStep.Connect
        v.phase != SetupPhase.Guide -> null
        v.current == SetupStep.WirelessDebugging -> SetupStep.WirelessDebugging
        v.current == SetupStep.PairingCode && !v.portFound -> SetupStep.PairingCode
        else -> null
    }

    /**
     * Do you need to "use computer startup instead": the system does not have wireless debugging, and
     * the user says it cannot be found, it will only be given after reaching the step of wireless
     * debugging (the first two steps must be completed before starting the computer); pairing and
     * connection failed in the above categories, or the user said it was on, but the assistant read it
     * was off; it stopped at [stallPoint] and above [STALL_MS]. Not at other times.
     */
    private fun usbFallback(v: SetupView, i: SetupInputs): Boolean {
        if (v.phase == SetupPhase.Success) return false
        val reachedWireless = v.phase == SetupPhase.Working || (v.current != null && v.current >= SetupStep.WirelessDebugging)
        return (reachedWireless && (i.wirelessMissing || i.usbAsked)) ||
            v.failure?.reason in USB_FAILURES ||
            (v.phase == SetupPhase.Failed && v.suspect?.step == SetupStep.WirelessDebugging) ||
            (stallPoint(v) != null && i.stalledMs >= STALL_MS)
    }

    private fun steps(i: SetupInputs): SetupView {
        val status = effectiveStatus(i.status, i.repair)
        if (status == BuiltinShellStatus.Running) return SetupView(SetupPhase.Success, marks(SetupStep.entries.size, i), null)
        val natural = natural(i, status)
        // When connecting, the progress will prevail; when the previous step is turned off, go back to
        // that step first.
        val back = i.revisit?.takeIf { it in REVISITABLE && status != BuiltinShellStatus.Connecting } ?: return natural
        val earlier = natural.phase == SetupPhase.Guide && natural.current != null && natural.current < back
        if (earlier) return natural
        // Other steps are still checked or marked as failed, and the step that was originally stopped
        // will be changed to to be done first.
        val marks = natural.marks.mapValues { (_, m) -> if (m == StepMark.Current) StepMark.Todo else m } + (back to StepMark.Current)
        return guide(back, i).copy(marks = marks, revisiting = true)
    }

    /**
     * There’s no going back and redoing what it was like. Connecting (including handovers such as when
     * the assistant changes hands and just getting up) is based on the progress. Do not go back to that
     * step just because you read that wireless debugging is turned off: When the assistant on the
     * computer can turn on wireless debugging for the user, the user does not need to turn it on. The
     * assistant started by the USB boot command stopped, and it also did not boot to wireless debugging.
     */
    private fun natural(i: SetupInputs, status: BuiltinShellStatus): SetupView {
        if (blocks(SetupStep.DeveloperOptions, i)) return guide(SetupStep.DeveloperOptions, i)
        if (blocks(SetupStep.UsbDebugging, i)) return guide(SetupStep.UsbDebugging, i)
        if (blocks(SetupStep.Accessibility, i)) return guide(SetupStep.Accessibility, i)
        if (status == BuiltinShellStatus.Connecting) return working(i)
        if (status is BuiltinShellStatus.Failed && status.reason == BuiltinShellFailure.UsbHelperStopped) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                failure = status,
            )
        }
        // When the switch cannot be read, the assistant's statement shall prevail; it will not be
        // blocked after the user has confirmed it.
        val wirelessConfirmed = SetupStep.WirelessDebugging in i.confirmed
        val wirelessOff = !wirelessConfirmed &&
            (i.wirelessDebugging == false || (i.wirelessDebugging == null && status == BuiltinShellStatus.WirelessDebuggingOff))
        if (wirelessOff) return guide(SetupStep.WirelessDebugging, i)
        // The user said it was on, but the assistant read "off", "don't connect": don't keep turning in
        // circles, pointed out which step it was and gave the button to go back.
        if (status == BuiltinShellStatus.WirelessDebuggingOff && wirelessConfirmed && i.wirelessDebugging != true) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                suspect = SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null),
            )
        }
        return when (status) {
            BuiltinShellStatus.Running -> error("handled above")
            BuiltinShellStatus.Connecting -> error("handled above")
            // After pairing, wireless debugging is on but the status has not caught up: it will
            // automatically connect immediately, and "Connecting" will be displayed first.
            BuiltinShellStatus.WirelessDebuggingOff -> working(i)
            BuiltinShellStatus.NotPaired -> guide(SetupStep.PairingCode, i)
            is BuiltinShellStatus.Failed -> {
                val step = if (status.reason.duringPairing || status.fix == BuiltinShellFix.RePair) SetupStep.PairingCode else SetupStep.Connect
                SetupView(
                    SetupPhase.Failed, marks(step.ordinal, i, current = step, mark = StepMark.Failed), step, failure = status,
                    portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
                    notifyIssue = notifyIssue(step, i),
                    autoRead = step == SetupStep.PairingCode && i.accessibility,
                    suspect = if (status.reason in SWITCH_RELATED) suspect(i) else null,
                )
            }
        }
    }

    private fun working(i: SetupInputs) = SetupView(SetupPhase.Working, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect), null)

    /** After reading this step, it was not done well and the user did not confirm it (it can be skipped if there is no obstacle): stop at this step. */
    private fun blocks(step: SetupStep, i: SetupInputs): Boolean =
        detected(step, i) == false && step !in i.confirmed && !(step == SetupStep.Accessibility && i.accessibilitySkipped)

    /** The step read from the system settings has not been done well; it is null when it cannot be read or this step is not a switch. */
    fun detected(step: SetupStep, i: SetupInputs): Boolean? = when (step) {
        SetupStep.DeveloperOptions -> i.developerOptions
        SetupStep.UsbDebugging -> i.usbDebugging
        SetupStep.Accessibility -> i.accessibility
        SetupStep.WirelessDebugging -> i.wirelessDebugging
        SetupStep.PairingCode, SetupStep.Connect -> null
    }

    /** Pairing and connection depend on developer options and wireless debugging: look for the first one in order and read it off (the user has confirmed it before coming here) or cannot read it. */
    private fun suspect(i: SetupInputs): SetupSuspect? = when {
        !i.developerOptions -> SetupSuspect(SetupStep.DeveloperOptions, unreadable = false)
        i.wirelessDebugging != true -> SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null)
        else -> null
    }

    private fun guide(step: SetupStep, i: SetupInputs) = SetupView(
        SetupPhase.Guide, marks(step.ordinal, i, current = step), step,
        portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
        notifyIssue = notifyIssue(step, i),
        autoRead = step == SetupStep.PairingCode && i.accessibility,
        showAppInfo = step == SetupStep.Accessibility && i.restrictedSettings,
    )

    private fun notifyIssue(step: SetupStep, i: SetupInputs): PairingNotifyIssue? = when {
        step != SetupStep.PairingCode -> null
        !i.notificationsAllowed -> PairingNotifyIssue.NotificationsOff
        i.pairingChannel == PairingChannelHealth.Off -> PairingNotifyIssue.ChannelOff
        i.pairingChannel == PairingChannelHealth.Lowered -> PairingNotifyIssue.NotHeadsUp
        else -> null
    }

    /** There are only two steps that were not taken when starting the assistant using the USB boot command. */
    private val WIRELESS_STEPS = setOf(SetupStep.WirelessDebugging, SetupStep.PairingCode)

    /** The first [done] step is completed (the skipped accessibility, wireless debugging and pairing that are not performed during USB startup are recorded as skipped), [current] step is marked as [mark], and the rest needs to be done. */
    private fun marks(done: Int, i: SetupInputs, current: SetupStep? = null, mark: StepMark = StepMark.Current): Map<SetupStep, StepMark> =
        SetupStep.entries.associateWith { s ->
            when {
                s.ordinal < done && s == SetupStep.Accessibility && !i.accessibility && s !in i.confirmed -> StepMark.Skipped
                s.ordinal < done && s in WIRELESS_STEPS && i.restart == HelperRestart.UsbCommand -> StepMark.Skipped
                s.ordinal < done -> StepMark.Done
                s == current -> mark
                else -> StepMark.Todo
            }
        }

    /** When "re-pairing", whether the assistant is running or not before pairing starts ([BuiltinShellStatus.Connecting]), it will be regarded as not yet paired. */
    fun effectiveStatus(status: BuiltinShellStatus, repair: Boolean): BuiltinShellStatus =
        if (repair && status !is BuiltinShellStatus.Connecting) BuiltinShellStatus.NotPaired else status
}
