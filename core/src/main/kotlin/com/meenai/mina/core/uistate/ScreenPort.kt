package com.meenai.mina.core.uistate

import com.meenai.mina.core.visual.FrameGrid
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DOUBLE_TAP_UNSUPPORTED = "double_tap_unsupported"

/** The interval between two clicks: short enough to be recognized as a double click (Android's double-click time limit is 300 milliseconds). */
const val DOUBLE_TAP_GAP_MS = 120L

/** The default duration of the swipe gesture. */
const val DEFAULT_SWIPE_MS = 400L

/** The conversion to tap is reciprocal: n = round(p*999/(size-1)), sandwiched between 0–999. */
fun normalize(p: Int, size: Int): Int {
    if (size <= 1) return 0
    return min(999, max(0, (p * 999.0 / (size - 1)).roundToInt()))
}

/**
 * An element on the screen. ref is in the shape of "@e3"; center is a pixel (used internally in the
 * port, and is changed to 0-999 before being handed to the model); bounds is [l,t,r,b], which has been
 * normalized to 0-999. [hint]: What is displayed in the input box is the prompt/preset word (name is
 * it), not the content that has been entered. [focused]: There is input focus (carriage return and space
 * act on it). [secret]: Password box. [placeholder]: The prompt of the input box itself (it is still
 * there after typing, and has nothing to do with the [name] displayed at the moment); if not, it is
 * null. [searchField]: The control is a search box according to its own semantics (the type=search,
 * role=searchbox, and enter key of the web page are marked as search; the search box control of
 * Android). [states]: The control is in the true state at the moment (the name in [UiStates]: selected,
 * hooked, expanded, pressed, unavailable); when judging the action effect, you can see it by flipping
 * the switch.
 */
data class UiElement(
    val ref: String,
    val role: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: List<Int>,
    val resourceId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val hint: Boolean = false,
    val focused: Boolean = false,
    /** The shortcut keys declared for this control on the web page (aria-keyshortcuts, accesskey are replaced by Alt+keys) are written in the same way as the key of web_interact. */
    val keyShortcuts: List<String> = emptyList(),
    val secret: Boolean = false,
    val placeholder: String? = null,
    val searchField: Boolean = false,
    val states: Set<String> = emptySet(),
) {
    /** Unnamed and non-interactive nodes have no information about the model. */
    val meaningful: Boolean get() = name.isNotBlank() || clickable || editable || scrollable
}

/** The name of the control state, shared by mobile phones and web pages. */
object UiStates {
    const val CHECKED = "checked"
    const val SELECTED = "selected"
    const val EXPANDED = "expanded"
    const val PRESSED = "pressed"
    const val DISABLED = "disabled"
    val ALL = setOf(CHECKED, SELECTED, EXPANDED, PRESSED, DISABLED)
}

/**
 * [changedDuringRead]: The interface before and after reading this list is inconsistent (the page is
 * moving by itself), and the list may not match the screen. [protected]: The current page is protected
 * by the system against screenshots and the screen cannot be seen. [hasWindow]: There is an App window
 * on the screen (it doesn’t matter whether the root node is readable or not); when it is false and there
 * are no elements, the screen is truly empty. [web]: Page information when reading the web page; empty
 * when reading the screen on the mobile phone. [unreadableApp]: The window content (security window,
 * startup screen, displayed elsewhere) cannot be read, but when the port knows which App is in the
 * foreground according to the focus window or the top task, its package name; it is null when it can be
 * read or is not known. With it, "no element" does not mean "the app is not open".
 */
data class ScreenSnapshot(
    val snapshotId: String,
    val packageName: String?,
    val elements: List<UiElement>,
    val width: Int,
    val height: Int,
    val changedDuringRead: Boolean = false,
    val protected: Boolean = false,
    val hasWindow: Boolean = true,
    val web: WebInfo? = null,
    val unreadableApp: String? = null,
    /** The full visibility level of mobile accessibility windows; web and test ports may not be provided. */
    val windows: List<UiWindowTree> = emptyList(),
    /** The App that was opened to the user's home screen after the previous action and moved back just before reading this screen (see [OpenedElsewhere]); if not, it is null. */
    val opened: OpenedElsewhere? = null,
)

/**
 * The action causes an App to be opened outside the Mina screen and onto the user's home screen (the
 * system does not open the App from its own page on the Mina screen, so the system puts it on the home
 * screen). [packageName] That App (when the system asks which one to open, it asks about the package
 * where the window is located), [app] its display name. [pulledBack]: It has been moved to Mina's screen
 * and is at the top; when it is false, it remains on the user's home screen (the App the user is using,
 * or the system is asking the user). [inProfile]: When two copies of this app are installed (avatar,
 * work information), which one is opened? [MAIN_PROFILE] The original copy, [OTHER_PROFILE] the other
 * one; if only one copy is installed or cannot be seen, it is null. [picker]: What opens is a window
 * where the system asks "Which one to use to open it?" (the same app is installed twice, several apps
 * can be opened, etc.). It never moves: it leaves the user to make a selection on their own screen, and
 * the task waits until the selection is made; [app] is the name of the App in question (when
 * recognized). [closed]: The user closed that question and nothing was opened. [byUser]: The selected
 * App requires users to process it manually. After processing it on their own screen, the user clicks to
 * continue. [needsUser]: The pop-up windows that appear on the home screen require users to handle them
 * personally (such as login verification, account confirmation, payment confirmation, etc.), and leave
 * them on the home screen for them to click.
 */
data class OpenedElsewhere(
    val packageName: String, val app: String?, val pulledBack: Boolean, val inProfile: String? = null, val picker: Boolean = false,
    val closed: Boolean = false, val byUser: Boolean = false, val needsUser: Boolean = false,
) {
    /** It is still on the user's home screen at this moment (the user is using it, or the system is asking the user). */
    val onUserScreen: Boolean get() = !pulledBack && !closed && !byUser

    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        app?.let { put("app", it) }
        put("pulledBack", pulledBack)
        if (onUserScreen) put("onUserScreen", true)
        if (needsUser) put("needsUser", true)
        inProfile?.let { put("inProfile", it) }
        if (picker) put("picker", true)
        if (closed) put("closed", true)
        if (byUser) put("byUser", true)
    }

    companion object {
        const val MAIN_PROFILE = "main"
        const val OTHER_PROFILE = "other"
    }
}

/** Interface events received from the virtual screen of the current task; notification events can come from the entire device. */
data class ScreenEvent(val type: String, val packageName: String?, val text: String?)

/** Lines of text read by native OCR from the current screen image, bounded by the same 0–999 coordinates as accessible elements; [confidence] is the 0–1 confidence given by the recognition, or null. */
data class VisibleTextLine(val text: String, val bounds: List<Int>, val confidence: Float? = null)

/** The result of a built-in helper executing a command as the Android shell. */
data class SystemCommandResult(val exitCode: Int, val output: String)

/** Read back the verification result of the input box after input; if it cannot be read back (password box, node cannot be read), it will be UNKNOWN. */
enum class Verification(val wire: String) { MATCH("match"), MISMATCH("mismatch"), UNKNOWN("unknown") }

sealed interface ActionResult {
    /**
     * The action has been issued. The remaining fields are the facts verified by the port afterwards,
     * null/false means no verification or not applicable: [changed] Whether the interface before and
     * after the action has changed; [appSwitched] The current package name when the frontend is no
     * longer the target App; [relocated] The number has expired and is retrieved according to the
     * original element characteristics and executed in the new interface. [relocatedTo] is the current
     * name of the retrieved element; [switchedByTarget] The new frontend is the target App Opened in its
     * own task (such as sharing to another App), it is not an external interruption; [verification]
     * inputs the readback result; [hint] port's supplementary explanation of the result (for example,
     * why it cannot be verified) is transferred to the model as it is; [secret] input target is the
     * password box (the original text is not recorded in the running record accordingly); [settled]
     * waits until the network is idle and the page is quiet after the action when the webpage has
     * DevTools. true, if it has not settled down to the upper limit, it is false, and the rest is null;
     * [coveredBy] The opened App is already on the top, but the window of another package covers it
     * (permission pop-up window, account selector), that package; [chooser] After opening the App, the
     * system first asks "Which one to open" (the same App is installed twice, etc.), and the App has not
     * come to the foreground before selecting it; [opened] This action allows an App to be opened on the
     * user's home screen (see [OpenedElsewhere]); [inProfile] Which one was opened when two copies of
     * the App were installed at the front desk (see [OpenedElsewhere.inProfile]): after the system asks
     * which one to open, and when the target App lists another copy, or wants another copy;
     * [profileUser] is the user number of the one on the screen at this moment, [requestedUser] It is
     * the user's copy requested when opening (the original copy requested is null).
     */
    data class Ok(
        val changed: Boolean? = null,
        val appSwitched: String? = null,
        val relocated: Boolean = false,
        val relocatedTo: String? = null,
        val switchedByTarget: Boolean = false,
        val verification: Verification? = null,
        val hint: String? = null,
        val secret: Boolean = false,
        val settled: Boolean? = null,
        val coveredBy: String? = null,
        val chooser: LaunchChoice? = null,
        val opened: OpenedElsewhere? = null,
        val inProfile: String? = null,
        val profileUser: Int? = null,
        val requestedUser: Int? = null,
    ) : ActionResult

    /** Reason is "ref_expired" when the reference has expired. [hint] is a supplementary description of the port (such as what is covering the target), which is appended to the error description as it is. */
    data class Failed(val reason: String, val hint: String? = null) : ActionResult
}

/** When the app is opened, the app is being used by another task (the same app can only be on one screen at the same time). */
const val APP_IN_USE_BY_OTHER_TASK = "app_in_use_by_other_task"

/** The result of [ScreenPort.awaitAppFree]. */
enum class AppWait {
    /** Let go, you can open it. */
    Free,
    /** The time limit is still occupied. */
    TimedOut,
    /** The task occupying it is waiting for the App in the hand of this task: no one can wait if you wait, there is no waiting. */
    WouldDeadlock,
    /** This port does not compete with other tasks for the App, so there is nothing to wait for. */
    Unsupported,
}

/** The only entrance for Agent to observe and operate the mobile phone screen is implemented by the Android module. */
interface ScreenPort {
    val appAccess: com.meenai.mina.core.access.AppAccessPolicy get() = com.meenai.mina.core.access.AppAccessPolicy.ALLOW

    /** Only checks the window ownership and does not read the screen content. Available if the screen is empty; rejected if there is a window but its ownership is unknown. */
    suspend fun checkAppAccess() {}

    suspend fun snapshot(): ScreenSnapshot

    /** Read one screen but do not register the number (the ref in the model's hand is not affected); used for irreversible determination. */
    suspend fun peek(): ScreenSnapshot = snapshot()
    suspend fun screenshotPng(): ByteArray?
    /** Optional native text recognition; null means the current screen is unreadable or the port does not have OCR. */
    suspend fun readVisibleText(): List<VisibleTextLine>? = null
    suspend fun tapRef(ref: String, snapshotId: String): ActionResult
    suspend fun tapXY(x: Int, y: Int): ActionResult
    /** Double-click (the interval between two clicks is within the time limit for double-click recognition by the system); unsupported ports report double_tap_unsupported. */
    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult
    suspend fun clearText(ref: String?, snapshotId: String?): ActionResult

    /**
     * Type into the input box at this point ([x], [y] is pixels): click it first, click on the input box
     * with focus, and then type as usual. If the input box is not visible in the tree, paste it with the
     * clipboard and check the words on the screen before and after typing; after pasting, the point
     * before and after does not become point_not_typed (that point is probably not an input box, or does
     * not have the focus). By default, you click first and then press the focus to type, without
     * checking that point.
     */
    suspend fun typeAt(x: Int, y: Int, text: String): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) typeText(null, null, text) else it }

    /** Clear the input box at this point, the method is the same as [typeAt]. */
    suspend fun clearAt(x: Int, y: Int): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) clearText(null, null) else it }
    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult

    /**
     * When staying, scroll gently ([fraction] screen or viewport height, right down) without clicking
     * anything. The web page uses the same native input as scrolling down, without moving the pointer;
     * the phone slowly draws a small section on the scrollable content that takes up more than half of
     * the screen. If it is inconvenient to scroll at this moment (there is a pop-up box, the input
     * method is open, the input box has the focus, and no scrollable content is visible), it will not
     * scroll and will return false; if it scrolls, it will be true. The default is not to roll.
     */
    suspend fun idleScroll(fraction: Double): Boolean = false
    /** Multi-point gestures; each path is a sequence of pixels arranged in time, with all fingers moving simultaneously. */
    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = ActionResult.Failed("unsupported")
    suspend fun back(): ActionResult
    suspend fun pressKey(keyCode: Int): ActionResult
    suspend fun openApp(packageName: String, cold: Boolean = false): ActionResult

    /**
     * Open the copy of [packageName](avatar, work information, see [appCopies]) installed in another user's copy of
     * [userId]; the rest are the same as [openApp]. The copy_not_found failed when the user cannot use
     * it or the App is not installed there. Unsupported ports return unsupported.
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean = false): ActionResult = ActionResult.Failed("unsupported")

    /**
     * When [packageName] (the one in another user's [userId]; null is the original one) is being used by
     * another task ([APP_IN_USE_BY_OTHER_TASK]), it can wait up to [timeoutMs] until it is released.
     * Just wait, don't open: wait until the caller arrives and open it again. The task occupying it is
     * waiting for the App in the hand of this task (waiting for each other, no one can wait), and
     * returns [AppWait.WouldDeadlock] on the spot. The App's port does not compete with other tasks and
     * returns [AppWait.Unsupported].
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = AppWait.Unsupported

    /**
     * Open a link or Android settings page on the Agent's screen (press VIEW to open [uri] when [action]
     * is null); [packageName] limits which app opens it. Failure reasons: link_unresolved (no App can be
     * opened), invalid_link, app_in_use_by_user. Unsupported ports return unsupported.
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = ActionResult.Failed("unsupported")

    /**
     * Share a piece of text to [packageName] (ACTION_SEND text/plain) and open its sharing page on the
     * Agent's screen; it is only opened but not sent. This App fails cannot_share when there is no text
     * sharing page; the rest is the same as [openApp] (screen creation, the user is using it, and the
     * system asks which one to open). Unsupported ports return unsupported.
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = ActionResult.Failed("unsupported")

    /**
     * Perform a system action through the Android standard interface (see [SystemAction]): first check
     * if there is an App connected, if not, it will report not_supported; if there is, hand it over to
     * the Agent's screen (the screen is created when you use it for the first time, and it will not be
     * opened when the user is using that App on the home screen). Unsupported ports return unsupported.
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = SystemActionResult(ActionResult.Failed("unsupported"))

    /**
     * Turn the scroll wheel (or an item in the scroll wheel) with the number [ref] to display [value]:
     * Complete it once in the port (enter it if you can, scroll one grid if you can, or drag it one grid
     * higher if it doesn't work), and read it back every few times. Unsupported ports are reported as
     * unsupported.
     */
    suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        WheelOutcome(false, null, null, 0, "none", error = "unsupported")
    suspend fun waitStable(timeoutMs: Long): Boolean
    /**
     * Wait for the next interface change of this screen, up to [timeoutMs]; wait until it returns true.
     * Event-driven ports wake up when there are interface events (accessibility events, page changes),
     * unsupported ports return false when the timeout expires, and are reread by the caller at
     * intervals.
     */
    suspend fun awaitChange(timeoutMs: Long): Boolean { delay(timeoutMs.coerceAtLeast(0)); return false }
    /** Wait for event-driven interface conditions; condition: appear/disappear/activity/stable. */
    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = false
    /** Listen to interface events of the virtual screen, or global notifications; subscribe only during the call. */
    suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = null
    /** Collect events continuously over a period of time; a single subscription will not miss changes between events. */
    suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> =
        listOfNotNull(nextEvent(kind, packageName, durationMs))
    /** Execute commands directly via the task-holding shell helper. */
    suspend fun systemCommand(command: String): SystemCommandResult? = null
    suspend fun listApps(): List<Pair<String, String>> // package name, display name

    /** Apps that are also installed in another user of this phone (avatar, work profile, etc.): package name → the names of those users. Empty if not found or not supported. */
    suspend fun otherProfiles(): Map<String, List<String>> = emptyMap()

    /** Another App that can be opened (installed in another user such as a clone or work profile, see [AppCopies] for rules); empty if it cannot be found or is not supported. */
    suspend fun appCopies(): List<AppCopy> = emptyList()

    /**
     * What to do with this app at the end of the mission (see [AfterTaskMarks]): Just write it down,
     * nothing is moving at this moment. Unsupported ports (no destination at the end of the task) are
     * null.
     */
    val afterTask: AfterTaskMarks? get() = null

    /** Screen diagnosis; if the port is not supported, it will be null, and it will be roughly judged by the caller based on the element list. */
    suspend fun checkScreen(): ScreenCheck? = null

    /** Whether the current page is protected by the system's anti-screenshot protection. */
    suspend fun isProtected(): Boolean = false

    /** The brightness grid that the screen is reduced to at this moment (used to determine the action effect, see [FrameGrid.sample]); it is null when there is no screen or the port does not support it. Be cheaper: don't encode images. */
    suspend fun frameGrid(): FrameGrid? = null
}
