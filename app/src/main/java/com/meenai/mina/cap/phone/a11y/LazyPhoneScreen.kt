package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.AfterTaskMarks
import com.meenai.mina.core.uistate.AppCopy
import com.meenai.mina.core.uistate.AppWait
import com.meenai.mina.core.uistate.ScreenCheck
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.ScreenEvent
import com.meenai.mina.core.uistate.VisibleTextLine
import com.meenai.mina.core.uistate.SystemCommandResult
import com.meenai.mina.core.uistate.SystemAction
import com.meenai.mina.core.uistate.SystemActionResult
import com.meenai.mina.core.uistate.WheelOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What is needed to operate the mobile app (built-in assistant, accessibility) is not available at the moment; [hint] is a sentence that tells the model and the user how to do it. */
class PhoneUnavailable(val hint: String) : Exception(hint)

/**
 * The mobile port that is connected only when used: [acquire] is called only when launching_app /
 * open_link for the first time, or system command or event tool; tasks that only use web pages or
 * sandboxes do not require built-in assistants and accessibility from beginning to end. Before
 * connecting, the reading screen was a blank screen, and other reported actions no_app_open, just like
 * when the app was not opened after being connected. When unable to connect, this action reports
 * phone_unavailable (attached is how to fix it). Please try again next time. Which apps are installed?
 * Just ask the package manager ([listApps]), no need to connect; if the App in another user is not
 * connected, ask [appCopies] (the built-in assistant is only available if it is connected, do not
 * connect it for it).
 */
class LazyPhoneScreen(
    private val width: Int,
    private val height: Int,
    private val listApps: suspend () -> List<Pair<String, String>>,
    private val appCopies: suspend () -> List<AppCopy> = { emptyList() },
    private val acquire: suspend () -> ScreenPort,
) : ScreenPort {
    private val lock = Mutex()
    @Volatile private var port: ScreenPort? = null

    /** When the task under the model is completed, each App will be left or gone; at the end, the task will be handed over to the built-in assistant for execution (if the phone is not connected, there will be nothing to process). */
    override val afterTask = AfterTaskMarks()

    /** A mobile phone was used in this task (already connected). */
    val acquired: Boolean get() = port != null

    private suspend fun built(): ScreenPort? = lock.withLock {
        port ?: try {
            acquire().also { port = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            lastProblem = e.hint
            null
        }
    }

    @Volatile private var lastProblem: String? = null

    private fun unavailable() = ActionResult.Failed(PHONE_UNAVAILABLE, lastProblem)

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = built()?.openApp(packageName, cold) ?: unavailable()
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        built()?.openAppCopy(packageName, userId, cold) ?: unavailable()
    // You will only wait when you are occupied by other tasks. By then your phone has already been
    // connected; if it is not connected, there is nothing to wait for.
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait =
        port?.awaitAppFree(packageName, userId, timeoutMs) ?: AppWait.Unsupported
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult =
        built()?.openLink(action, uri, packageName) ?: unavailable()
    override suspend fun shareText(packageName: String, text: String): ActionResult = built()?.shareText(packageName, text) ?: unavailable()
    override suspend fun systemAction(action: SystemAction): SystemActionResult = built()?.systemAction(action) ?: SystemActionResult(unavailable())
    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        port?.setWheel(ref, snapshotId, value) ?: WheelOutcome(false, null, null, 0, "none", error = "no_app_open")

    // ---------- Before connecting: App that has not been opened ----------

    override suspend fun snapshot(): ScreenSnapshot = port?.snapshot() ?: empty()
    override suspend fun peek(): ScreenSnapshot = port?.peek() ?: empty()
    override suspend fun screenshotPng(): ByteArray? = port?.screenshotPng()
    override suspend fun readVisibleText(): List<VisibleTextLine>? = port?.readVisibleText()
    override suspend fun tapRef(ref: String, snapshotId: String) = port?.tapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun tapXY(x: Int, y: Int) = port?.tapXY(x, y) ?: NO_APP_OPEN
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = port?.doubleTapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun doubleTapXY(x: Int, y: Int) = port?.doubleTapXY(x, y) ?: NO_APP_OPEN
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = port?.typeText(ref, snapshotId, text) ?: NO_APP_OPEN
    override suspend fun clearText(ref: String?, snapshotId: String?) = port?.clearText(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun typeAt(x: Int, y: Int, text: String) = port?.typeAt(x, y, text) ?: NO_APP_OPEN
    override suspend fun clearAt(x: Int, y: Int) = port?.clearAt(x, y) ?: NO_APP_OPEN
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = port?.scroll(ref, snapshotId, down) ?: NO_APP_OPEN
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = port?.swipe(x1, y1, x2, y2, durationMs) ?: NO_APP_OPEN
    override suspend fun idleScroll(fraction: Double): Boolean = port?.idleScroll(fraction) ?: false
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = port?.gesture(paths, durationMs) ?: NO_APP_OPEN
    override suspend fun back() = port?.back() ?: NO_APP_OPEN
    override suspend fun pressKey(keyCode: Int) = port?.pressKey(keyCode) ?: NO_APP_OPEN
    override suspend fun waitStable(timeoutMs: Long) = port?.waitStable(timeoutMs) ?: true
    override suspend fun awaitChange(timeoutMs: Long): Boolean = port?.awaitChange(timeoutMs) ?: super.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = port?.waitFor(condition, value, timeoutMs) ?: false
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        val screen = built() ?: return null
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.nextEvent(kind, packageName, timeoutMs)
    }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        val screen = built() ?: return emptyList()
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.watchEvents(kind, packageName, durationMs, maxEvents)
    }
    override suspend fun systemCommand(command: String): SystemCommandResult? {
        val screen = built() as? AndroidScreen ?: return null
        screen.ensureDisplay()
        return screen.systemCommand(command)
    }
    override suspend fun listApps(): List<Pair<String, String>> = port?.listApps() ?: listApps.invoke()
    // Ask the built-in assistant: don’t connect it before you connect it (if you can’t connect, you will
    // ask the user to set it up), only after opening the app
    override suspend fun otherProfiles(): Map<String, List<String>> = port?.otherProfiles() ?: emptyMap()
    override suspend fun appCopies(): List<AppCopy> = port?.appCopies() ?: appCopies.invoke()
    override suspend fun checkScreen(): ScreenCheck? = port?.checkScreen()
    override suspend fun isProtected() = port?.isProtected() ?: false
    override suspend fun frameGrid() = port?.frameGrid()

    private fun empty() = ScreenSnapshot(snapshotId(), null, emptyList(), width, height, hasWindow = false)

    companion object {
        const val PHONE_UNAVAILABLE = "phone_unavailable"
        private val NO_APP_OPEN = ActionResult.Failed("no_app_open")
    }
}
