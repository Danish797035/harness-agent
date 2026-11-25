package com.meenai.mina.settings

import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.HelperRestart
import com.meenai.mina.core.shell.PairingChannelHealth

enum class AccessibilityState {
    Off,
    /** It is turned on in the system settings, but the service is not running (common after the app is forcibly stopped) and needs to be turned off and on again. */
    EnabledNotRunning,
    On,
}

data class SystemStatus(
    val sdkInt: Int,
    /** The status of the built-in assistant. */
    val builtinShell: BuiltinShellStatus,
    val accessibility: AccessibilityState,
    val notificationsAllowed: Boolean,
    /** The "Need You to Handle" notification channel has been adjusted below high priority by the user or the system (does not pop up and may not sound). */
    val attentionLowered: Boolean = false,
    /** The same applies to the "Task Result" notification channel. */
    val resultLowered: Boolean = false,
    /** "Pairing" notification channel: Fill in the pairing code here, and the guide page will say it when it is turned off or adjusted not to pop up. */
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    /** The three tasks notify the current importance of the channel; if it is empty, it means that the channel has not been established yet. The notification page is displayed accordingly. */
    val attentionImportance: Int? = null,
    val resultImportance: Int? = null,
    val progressImportance: Int? = null,
    /** The system "Developer Options" is turned on. */
    val developerOptions: Boolean = false,
    /** The system "wireless debugging" is turned on. */
    val wirelessDebugging: Boolean = false,
    /** There is no "wireless debugging" item in the system (it does not have this function): the boot page accordingly says "Start with computer instead". */
    val wirelessDebuggingMissing: Boolean = false,
    /** The "Developer Options" page cannot be opened in the system: When established together with [wirelessDebuggingMissing], this environment cannot be set up for mobile operation. */
    val developerSettingsMissing: Boolean = false,
    /** The system "USB debugging" is turned on: the assistant will not stop even if the Wi-Fi is disconnected. */
    val usbDebugging: Boolean = true,
    /** The system may prevent accessibility from opening (starting from Android 13, apps that are not installed from the App Store must first allow restricted settings). */
    val restrictedSettings: Boolean = false,
    /** What can I do to get the assistant up again after it is stopped: only the USB boot command is used, and wireless debugging is not booted anywhere. Please tell me clearly that you want to run the command again. */
    val helperRestart: HelperRestart = HelperRestart.None,
    /** The user has not unlocked it once after booting ([android.os.UserManager.isUserUnlocked] is false): Accessibility service cannot be bound at this time. */
    val userLocked: Boolean = false,
)

fun SystemStatus.notificationOverview() = NotificationOverview.of(
    notificationsAllowed, attentionImportance, resultImportance, progressImportance,
)

sealed interface Problem {
    /** Mina's mobile operation capabilities are not enabled below Android 12. */
    data object SystemTooOld : Problem
    /** The built-in assistant is not running (no settings, wireless debugging is off, connecting, error). */
    data class PhoneHelper(val status: BuiltinShellStatus) : Problem
    /** [userLocked] is not running when it is true because it has not been unlocked after booting. It will recover by itself after unlocking. The user should not be allowed to turn it off and on again. */
    data class Accessibility(val state: AccessibilityState, val userLocked: Boolean = false) : Problem
    data object NoModel : Problem
    /** The task is not blocked, but the progress and "need you" reminder are not visible. */
    data object NotificationsOff : Problem
    /** The notification "needing your processing" (taking over, asking questions, confirming) or "task result" has been turned down, does not pop up, and may not be visible; [attention] and [result] indicate which one it is. */
    data class AttentionLowered(val attention: Boolean, val result: Boolean) : Problem
    /** Do not block tasks: The virtual screen of Android 12 and 13 does not have independent focus, and background operations may cause the input focus of the main screen to be temporarily lost. */
    data object FocusMayLeak : Problem
}

/** Check before starting the task; the interface prompts accordingly and the task entrance is blocked accordingly. */
object Prerequisites {
    /** Minimum version for mobile app operation; chat, web, and sandbox do not require this capability. */
    const val MIN_SDK = 31
    /** Starting from Android 14, the virtual screen can have its own focus and no longer affects the main screen. */
    const val OWN_FOCUS_SDK = 34
    private fun isWarning(p: Problem) = p == Problem.NotificationsOff || p == Problem.FocusMayLeak || p is Problem.AttentionLowered

    data class Check(val problems: List<Problem>) {
        /** Problems blocking tasks. The system version, built-in assistant, and accessibility that the mobile phone operates are only required when operating the mobile app. */
        val blockers: List<Problem> get() = problems.filter { !isWarning(it) && !phoneOnly(it) }
        val canStartTask: Boolean get() = blockers.isEmpty()
        /** Able to operate mobile apps. */
        val phoneReady: Boolean get() = problems.none(::phoneOnly)
    }

    private fun phoneOnly(p: Problem) = p is Problem.SystemTooOld || p is Problem.PhoneHelper || p is Problem.Accessibility

    fun check(status: SystemStatus, hasMainModel: Boolean): Check = Check(
        buildList {
            if (status.sdkInt < MIN_SDK) add(Problem.SystemTooOld)
            // The functions of mobile phones below Android 12 are not available, which has been
            // explained by SystemTooOld
            if (status.sdkInt >= MIN_SDK && status.builtinShell != BuiltinShellStatus.Running) {
                add(Problem.PhoneHelper(status.builtinShell))
            }
            if (status.sdkInt >= MIN_SDK && status.accessibility != AccessibilityState.On) {
                add(Problem.Accessibility(status.accessibility, status.userLocked))
            }
            if (!hasMainModel) add(Problem.NoModel)
            if (!status.notificationsAllowed) add(Problem.NotificationsOff)
            else if (status.attentionLowered || status.resultLowered) add(Problem.AttentionLowered(status.attentionLowered, status.resultLowered))
            if (status.sdkInt in MIN_SDK until OWN_FOCUS_SDK) add(Problem.FocusMayLeak)
        },
    )
}
