package com.meenai.mina.core.selfcheck

/**
 * An item of compatibility self-check. The order of the columns is the order of running; if there is an
 * item in [needs] that has not passed (failed or skipped), skip this item and indicate which item is
 * missing. [key] is the English name in the report, [timeoutMs] is the maximum running time for this
 * item (there is a total time limit for the entire self-test, see [SelfCheckRunner.TOTAL_BUDGET_MS]).
 */
enum class SelfCheck(val key: String, val timeoutMs: Long) {
    /** Model, system version, processor architecture, memory, screen, whether there is wireless debugging, and Mina version. */
    DEVICE("device", 5_000),
    /** Accessibility on and bound, notifications allowed, battery unlimited. */
    PERMISSIONS("permissions", 5_000),
    /** The built-in assistant is running, how it gets started, and how long a simple call lasts. */
    HELPER("helper", 15_000),
    /** Can create Mina's virtual screen; starting from Android 14 it does not have its own focus. */
    VIRTUAL_DISPLAY("virtual display", 10_000),
    /** Open Mina's own self-test page on the virtual screen, then open the system settings (only view points), and confirm that it is on Mina's screen and not on the home screen. */
    APP_LAUNCH("app launch", 20_000),
    /** The control tree on the self-test page can be read, and all the required controls are there; the screenshot of the virtual screen is not black or empty. */
    SCREEN_READING("screen reading", 10_000),
    /** Click on the self-test page, type English and Chinese, scroll, return, and read back and check everything. */
    ACTIONS("actions", 20_000),
    /** During the ten seconds that the self-test page has been moving, the virtual screen has been changing and the control tree has been readable. */
    STAYS_ALIVE("stays alive", 25_000),
    /** The tasks at the top of the home screen remain unchanged from beginning to end. */
    MAIN_SCREEN("main screen", 5_000),
    /** The built-in browser opens a local web page (not connected to the Internet), reads the text, clicks the button and then checks. */
    BROWSER("browser", 15_000),
    /** Run `echo ok` and a line of Python in the sandbox (only on arm64; the running environment is not ready and is partially available, so it is not installed now). */
    SANDBOX("sandbox", 20_000),
    /** How many copies of another user (available avatar, work profile) can open the App inside? This is just a count, not the App, and the private space does not count. */
    APP_COPIES("app copies", 10_000),
    ;

    /** The number in the report starts from 1. */
    val number: Int get() = ordinal + 1

    /** A few things to go through first. */
    val needs: List<SelfCheck>
        get() = when (this) {
            DEVICE, PERMISSIONS, BROWSER, SANDBOX -> emptyList()
            HELPER -> listOf(DEVICE)
            VIRTUAL_DISPLAY -> listOf(HELPER)
            APP_LAUNCH -> listOf(PERMISSIONS, VIRTUAL_DISPLAY)
            SCREEN_READING -> listOf(APP_LAUNCH)
            ACTIONS -> listOf(SCREEN_READING)
            STAYS_ALIVE -> listOf(SCREEN_READING)
            MAIN_SCREEN -> listOf(VIRTUAL_DISPLAY)
            APP_COPIES -> listOf(HELPER)
        }
}

enum class CheckStatus {
    Pending, Running, Pass, Partial, Fail, Skipped;

    /** Passed: usable, or partially usable. Items that depend on it run as expected. */
    val passed: Boolean get() = this == Pass || this == Partial

    /** The conclusion has been reached (no longer changes). */
    val final: Boolean get() = this != Pending && this != Running
}

/**
 * What kind of situation is the sentence given to the user (the interface takes the localized text
 * according to it, and does not write it in the report). They are all general terms: the reason plus the
 * next step that can be done.
 */
enum class Advice {
    /** The system version is lower than Android 12, so the mobile phone operation cannot be used. */
    PhoneTooOld,
    /** There is no wireless debugging in the system: use the computer to boot via USB instead. */
    NoWirelessDebugging,
    /** In this environment, there are no developer options and wireless debugging, and the apps on the phone cannot be operated; chat, web pages, files, and calculations are still available. */
    PhoneControlUnavailable,
    /** Accessibility is not open. */
    TurnOnAccessibility,
    /** Accessibility is on but not running: turn it off and on again. */
    RestartAccessibility,
    /** Notifications not allowed: Can’t see progress and “need you” reminders. */
    AllowNotifications,
    /** Battery is limited: the background may be stopped. */
    AllowBattery,
    /** The built-in assistant is not running: Complete the settings for mobile phone operations first. */
    SetUpPhoneControl,
    /** Cannot create a virtual screen. */
    DisplayFailed,
    /** The virtual screen below Android 14 does not have its own focus, and the input focus of the main screen may be temporarily lost during background operations. */
    FocusShared,
    /** The page did not open on Mina's screen. */
    LaunchFailed,
    /** The system settings are opened on the home screen. */
    OpenedOnMainScreen,
    /** The system settings are being used on your home screen, so there is no need to open it in this step. */
    SettingsInUse,
    /** The control tree cannot read or is missing controls. */
    ReadFailed,
    /** The screenshot is black or empty. */
    BlankFrames,
    /** Clicking, typing, scrolling or going back has no effect. */
    ActionFailed,
    /** The virtual screen image will stop moving after a while. */
    FramesStopped,
    /** The tasks at the top of the home screen have changed. */
    MainScreenChanged,
    /** There is no WebView available on the system. */
    NoWebView,
    /** The built-in browser did not read or clicked. */
    WebFailed,
    /** The sandbox is only available on arm64 phones. */
    SandboxUnsupported,
    /** The operating environment is not ready yet. */
    SandboxNotPrepared,
    /** The command did not produce the expected results. */
    SandboxFailed,
    /** This item has timed out. */
    TimedOut,
    /** This one is wrong. */
    Error,
    /** The time for the entire self-examination has run out. */
    OutOfTime,
    /** The user canceled. */
    Cancelled,
}

/**
 * Outcome of a completed check. [detail] is a one-line fact written to the report (English, no
 * personal data: no accounts, app lists, user content, or serial numbers); [advice] tells the UI to
 * display a localized plain-language message, usually null on pass.
 */
data class CheckOutcome(val status: CheckStatus, val detail: String, val advice: Advice? = null) {
    companion object {
        fun pass(detail: String) = CheckOutcome(CheckStatus.Pass, detail)
        fun partial(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Partial, detail, advice)
        fun fail(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Fail, detail, advice)
        fun skipped(detail: String, advice: Advice? = null) = CheckOutcome(CheckStatus.Skipped, detail, advice)
    }
}

/** The status of an item at the moment: not yet running, running, or conclusion; [blockedBy] is the item that was skipped because it failed. */
data class CheckResult(
    val check: SelfCheck,
    val status: CheckStatus = CheckStatus.Pending,
    val detail: String = "",
    val advice: Advice? = null,
    val blockedBy: SelfCheck? = null,
    val durationMs: Long = 0,
)

/**
 * The device facts in the report header come from the system's public interface; they do not include
 * things like serial numbers and account numbers that can identify people. [wirelessDebugging]: Whether
 * there is wireless debugging in the system, the value cannot be read as null; [developerOptionsPage]:
 * whether the developer options page can be opened in the system, the value is null if it is not known.
 * [phoneControlUnavailable] means that there is no way to activate the assistant in this environment
 * (the same judgment as the boot page).
 */
data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val ramBytes: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val wirelessDebugging: Boolean?,
    val appVersion: String,
    val appBuild: String,
    val developerOptionsPage: Boolean? = null,
    val phoneControlUnavailable: Boolean = false,
) {
    /** Model: Only write the model number when the manufacturer name is included in the model number. */
    val modelName: String get() = if (model.startsWith(manufacturer, ignoreCase = true)) model.trim() else "$manufacturer $model".trim()

    val arm64: Boolean get() = ARM64 in abis

    companion object {
        const val ARM64 = "arm64-v8a"
        /** The minimum system version for mobile phone operation (Android 12). */
        const val MIN_PHONE_SDK = 31
        /** Starting from Android 14, the virtual screen can have its own focus. */
        const val OWN_FOCUS_SDK = 34
    }
}

/**
 * Android capabilities required for self-testing. The implementation of the interface is in the app; the
 * unit test is replaced with a fake one. Each item may be interrupted due to timeout or user
 * cancellation, so you must be able to stop it in time; if you make an error, you can throw it directly,
 * and press failure on the other side of the run.
 */
interface SelfCheckPort {
    /** The first "device" fact (read-only system interface, very fast). */
    suspend fun facts(): DeviceFacts

    /** Run one item (other than [SelfCheck.DEVICE]; that item is judged by [facts]). */
    suspend fun check(check: SelfCheck): CheckOutcome

    /** Let go of everything that occupies the process of running (virtual screen, self-test page, web page tags, assistant connection). It will be adjusted after running, canceling, or making an error. It may be adjusted more than once. */
    suspend fun release()
}
