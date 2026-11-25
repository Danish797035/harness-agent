package com.meenai.mina.cap.phone.a11y

import android.accounts.AccountManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.meenai.mina.core.cap.phone.GesturePacing
import com.meenai.mina.core.cap.phone.TypingPacing
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.meenai.mina.core.uistate.AppWait
import com.meenai.mina.core.uistate.AppCopy
import com.meenai.mina.core.uistate.AppProfiles
import com.meenai.mina.core.uistate.LaunchChoice
import com.meenai.mina.core.uistate.LaunchChooser
import com.meenai.mina.core.uistate.OpenedElsewhere
import com.meenai.mina.core.uistate.UnseenField
import com.meenai.mina.core.uistate.normalize
import com.meenai.mina.core.uistate.Relocation
import com.meenai.mina.core.uistate.ScreenCheck
import com.meenai.mina.core.uistate.ScreenDiagnosis
import com.meenai.mina.core.uistate.ScreenFacts
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenEvent
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.VisibleTextLine
import com.meenai.mina.core.uistate.SystemCommandResult
import com.meenai.mina.core.uistate.SystemAction
import com.meenai.mina.core.uistate.SystemActionResult
import com.meenai.mina.core.uistate.WheelOutcome
import com.meenai.mina.cap.phone.shell.SystemIntents
import com.meenai.mina.core.visual.FrameGrid
import com.meenai.mina.core.uistate.Verification
import com.meenai.mina.core.uistate.TextField
import com.meenai.mina.core.uistate.typeAndVerify
import com.meenai.mina.core.uistate.relocate
import com.meenai.mina.core.uistate.RELOCATE_MAX_SHIFT
import com.meenai.mina.cap.phone.display.FrameMath
import com.meenai.mina.cap.phone.display.LazyAgentDisplay
import com.meenai.mina.shell.IHarnessShell
import com.meenai.mina.cap.phone.shell.MainScreenWatch
import com.meenai.mina.cap.phone.shell.ShellService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android implementation of [ScreenPort]: read the barrier-free window of the Agent virtual screen and
 * operate on this screen. After each action, wait for the interface to quiet down, use the control tree
 * fingerprint (supplemented by the screen) to verify whether it has taken effect, and check whether the
 * front desk has been cut away by other apps. The virtual screen is only built when it is used (the
 * first launch_app, or system command or event tool): before it is built, the screen reading is an empty
 * screen, and other actions fail [NO_APP_OPEN]. These actions will not build the screen by themselves.
 * Just because the screen is created does not mean that the app has been opened: the screen will be
 * empty for tasks that only run system commands.
 */
class AndroidScreen(
    private val a11y: ScreenAccessibility,
    private val shell: IHarnessShell,
    private val display: LazyAgentDisplay,
    /** It is true when the clipboard is temporarily changed to Mina's text, and false after restoration; the task service records this and starts the restoration next time after the process ends unexpectedly. */
    private val clipHeld: (Boolean) -> Unit = {},
    /** The clipboard is global: when several tasks are running at the same time, they share the same lock. */
    private val clipLock: Lock = ReentrantLock(),
    /** True for parallel tasks: Before opening the app, check whether it is being used for other tasks on another screen of Mina. */
    private val checkOtherTasks: Boolean = false,
    /** When a parallel task occurs, this package is occupied for this task; false is returned when other tasks already occupy it (and are still using it). */
    private val reserve: (String) -> Boolean = { true },
    /** Same as [reserve], it occupies the share installed in another user (user number): it is occupied separately from the original share, and one share can be used for each of the two tasks. */
    private val reserveCopy: (String, Int) -> Boolean = { _, _ -> true },
    /** After successfully changing the clipboard, hand over the user's original text and the text put in by Mina for encryption and retention (restore accordingly after the process is killed). */
    private val clipSaved: (original: String, ours: String) -> Unit = { _, _ -> },
    /** The App is currently in the hands of the user (under control): What is opened on the home screen at this time is the user's business and cannot be moved. */
    private val handedOver: () -> Boolean = { false },
    /** The rhythm of the gesture: the pause before the finger is dropped, the holding time, the deviation of the landing point, the arc and speed of the sliding (see [GesturePacing]). */
    private val pacing: GesturePacing = GesturePacing.human(),
    /** The rhythm of typing in the App of this package name (see [TypingPacing]); the default is to write the entire text at once. */
    private val typing: (String?) -> TypingPacing = { TypingPacing.Instant },
    /** Before issuing the start: [packageName](装在 [user] 那份用户里的;null 为本来那份) is to be opened. Accordingly, the task service starts quietly and is silent at the beginning. */
    private val beforeOpen: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    /** The startup was rejected and did not open: withdraw [beforeOpen] made in advance. */
    private val notOpened: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    override val appAccess: com.meenai.mina.core.access.AppAccessPolicy = com.meenai.mina.core.access.AppAccessPolicy.ALLOW,
    /** During parallel tasks: According to the occupation registration, whether this task can occupy this package at this time ([user] share; null is the original share). Only checking but not accounting. */
    private val appFree: (packageName: String, user: Int?) -> Boolean = { _, _ -> true },
    /**
     * During parallel tasks: wait for other tasks to release this package, up to that long; [free] is a
     * complete judgment (it is not on other screens, and no one is occupying it in the registration).
     * The task service registers "who is waiting for whom" and recognizes each other. When waiting, the
     * interface writes "Waiting for another task to finish an App".
     */
    private val awaitApp: suspend (packageName: String, user: Int?, timeoutMs: Long, free: suspend () -> Boolean) -> AppWait =
        { _, _, _, _ -> AppWait.Unsupported },
) : ScreenPort {
    /** -1 when the screen has not been built; only used after confirming that the screen has been built. */
    private val displayId get() = display.current?.displayId ?: -1
    /** The screen has been created (which does not mean the app has been opened). */
    private val hasDisplay get() = display.current != null
    private val ids = NodeIds()

    /** The latest package successfully opened by launch_app is used to determine whether it has been removed. */
    @Volatile private var targetPackage: String? = null

    /** The last time you opened the app, the system asked "Which one to open?" */
    @Volatile private var chooserPackage: String? = null

    /**
     * The App that the Agent is using and its task number (-1 if it is not at the top when opened); the
     * task service is muted accordingly and monitors whether it has been moved. [userId] is the user to
     * whom the task belongs (when two copies are installed, you can find out which copy it is); if it
     * cannot be read, it is -1.
     */
    data class TargetApp(val packageName: String, val taskId: Int, val userId: Int = -1)

    private val _target = MutableStateFlow<TargetApp?>(null)
    val target: StateFlow<TargetApp?> = _target.asStateFlow()

    private val systemPickers: Set<String> by lazy { SystemPickers.resolve(a11y.packageManager) }

    /** The package where the system settings are located (parsed according to Settings.ACTION_SETTINGS): The settings page opened by the target App in its own task is not considered to be cut away. */
    private val settingsPackage: String? by lazy { SystemPickers.settingsPackage(a11y.packageManager) }

    private fun windows() = a11y.windowsOn(displayId)
    /**
     * Window probe for application access: the ownership of each App window on the screen, only the
     * package name of the root node can be read, and the content cannot be read. The item that cannot
     * read the root node is null (unknown ownership); if there is no App window, it is the App with the
     * top task, and if there is no App window, it is a blank screen (empty list). The rules are
     * consistent with the window ownership in the screen reading results.
     */
    suspend fun accessPackages(): List<String?> = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext emptyList()
        val packages = windows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { it.root?.packageName?.toString() }
        packages.ifEmpty { listOfNotNull(foregroundTaskPackage()) }
    }

    private fun read() = ScreenReader.read(windows(), display.width, display.height)
    private fun treeHash() = ScreenReader.hash(windows())

    /** Create screens on demand when explicitly using system commands or event tools; common tasks wait until the first time you open the app. */
    suspend fun ensureDisplay() { display.get() }

    // ---------- Screen Reading ----------

    override suspend fun snapshot(): ScreenSnapshot = capture(register = true)

    // Not entering the number registration list: the latest ref in the model's hands is still valid
    override suspend fun peek(): ScreenSnapshot = capture(register = false)

    private suspend fun capture(register: Boolean): ScreenSnapshot = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext ScreenSnapshot(snapshotId(), null, emptyList(), display.width, display.height, hasWindow = false)
        // The previous action took a while to open on the user's home screen: move it back first and
        // then read it (only do it on the screen where the model is to be read, not on the one where the
        // effect is judged)
        val late = if (register) lateSpill() else null
        val r = read()
        val after = treeHash()
        val snap = NodeSnapshot(snapshotId(), r.entries)
        if (register) ids.put(snap)
        ScreenSnapshot(
            snapshotId = snap.id,
            packageName = r.topPackage,
            elements = r.entries.map { it.element },
            width = display.width,
            height = display.height,
            changedDuringRead = after != r.hash,
            protected = r.hasAppWindow && frameBlack() == true,
            hasWindow = r.anyAppWindow,
            unreadableApp = if (r.topPackage == null) foregroundTaskPackage() else null,
            windows = r.windows,
            // Still waiting for the user to make a selection on the home screen: Just say it anyway
            opened = late?.opened ?: _userPick.value?.let { OpenedElsewhere(it.packageName, it.app, pulledBack = false, picker = true) },
        )
    }

    /**
     * When the window content cannot be read (safe window, startup screen, displayed elsewhere), press
     * the top task on the Agent screen to identify which App is in the foreground; if the screen has not
     * been created, there is no task, or the task cannot be asked, it will be null.
     */
    private fun foregroundTaskPackage(): String? {
        if (!hasDisplay) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        return runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The app at the top of Mina's screen (package name, task, and user) will be given once every time
     * you change it: give it once when subscribing, and then reread it when the window changes (a new
     * page opens, another app is clicked from another app, another one comes up). It will not be given
     * if the screen has not been built yet and there are no tasks. Ask the built-in helper on the
     * caller's thread.
     */
    fun foregroundApps(): Flow<TargetApp> = flow {
        emit(Unit)
        emitAll(a11y.windowChanges())
    }.conflate().mapNotNull {
        if (!hasDisplay) return@mapNotNull null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return@mapNotNull null
        val pkg = runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TargetApp(pkg, task, userOf(task))
    }.distinctUntilChanged { a, b -> a.packageName == b.packageName && a.userId == b.userId }

    override suspend fun screenshotPng(): ByteArray? = withContext(Dispatchers.IO) { display.current?.latestFramePng() }

    /**
     * Recognize the text on the screen. There are almost no accessibility issues in the self-drawn
     * interface. The screen may still be transitioning when the event is quiet: wait for the screen to
     * stop before recognizing it; if the entire page is changed during the recognition process, click on
     * the new page to recognize it again (see [StillFrameRead] for rules). Partially moving parts
     * (animated avatars, cursors) will not affect the position of the words, so you can use them as they
     * are and no longer throw them away entirely.
     */
    override suspend fun readVisibleText(): List<VisibleTextLine>? = withContext(Dispatchers.IO) {
        if (!hasDisplay || frameBlack() == true) return@withContext null
        StillFrameRead.read(SystemClock::uptimeMillis, { delay(it) }, ::frameSignature) {
            display.current?.latestFrameBitmap()?.let { PhoneOcr.read(it) }
        }
    }

    override suspend fun frameGrid(): FrameGrid? = withContext(Dispatchers.IO) {
        val bmp = display.current?.frames?.value ?: return@withContext null
        runCatching { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    override suspend fun isProtected(): Boolean = withContext(Dispatchers.IO) {
        hasDisplay && read().hasAppWindow && frameBlack() == true
    }

    override suspend fun checkScreen(): ScreenCheck = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        // First check whether the interface will become quieter: there are always changes but it is
        // still loading.
        val quiet = withEventClock { clock -> clock.awaitQuiet(QUIET_MS, CHECK_CAP_MS) }
        // Take the picture twice when it is quiet. If it is black both times, it may be anti-screenshot.
        val black = mutableListOf<Boolean>()
        frameBlack()?.let(black::add)
        if (quiet) {
            withEventClock { clock -> clock.awaitQuiet(SECOND_LOOK_QUIET_MS, SECOND_LOOK_CAP_MS) }
            frameBlack()?.let(black::add)
        }
        val r = read()
        ScreenDiagnosis.classify(
            ScreenFacts(
                currentApp = r.topPackage,
                hasWindow = r.hasAppWindow,
                anyWindow = r.anyAppWindow,
                readableElements = r.entries.count { it.element.meaningful },
                changing = !quiet,
                blackFrames = black,
                texts = r.texts,
                passwordField = r.passwordField,
                foregroundApp = if (r.topPackage == null) foregroundTaskPackage() else null,
            ),
        )
    }

    override suspend fun waitStable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext true
        withEventClock { clock -> clock.awaitQuiet(QUIET_MS, timeoutMs) }
    }

    /** The next interface event of this virtual screen (hovering does not count); wait for the time limit to expire before the screen is opened. */
    override suspend fun awaitChange(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) { delay(timeoutMs.coerceAtLeast(0)); return@withContext false }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { a11y.eventsFor(displayId).first { it.eventType and IGNORED_EVENTS == 0 } } != null
    }

    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        if (condition == "stable") return@withContext waitStable(timeoutMs)
        if (condition !in setOf("appear", "disappear", "activity") || value.isNullOrBlank()) return@withContext false
        coroutineScope {
            val changed = Channel<Boolean>(Channel.CONFLATED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(displayId).collect { event ->
                    if (condition == "activity" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.className?.toString()?.contains(value, true) == true) changed.trySend(true)
                    else if (condition != "activity") changed.trySend(false)
                }
            }
            try {
                fun matched(): Boolean = when (condition) {
                    "activity" -> runCatching { shell.topActivity(displayId)?.contains(value, true) == true }.getOrDefault(false)
                    else -> {
                        val r = read()
                        val exists = r.windows.any { w ->
                            w.title?.contains(value, true) == true || w.nodes.any { n -> containsText(n, value) }
                        } || r.texts.any { it.contains(value, true) }
                        exists == (condition == "appear")
                    }
                }
                if (matched()) return@coroutineScope true
                withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
                    while (true) {
                        val activityEvent = changed.receive()
                        if (condition == "activity") {
                            if (activityEvent) return@withTimeoutOrNull true
                        } else if (matched()) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
            } finally { job.cancel(); changed.close() }
        }
    }

    private fun containsText(node: com.meenai.mina.core.uistate.UiTreeNode, text: String): Boolean =
        (!node.secret && (node.text?.contains(text, true) == true || node.description?.contains(text, true) == true)) ||
            node.children.any { containsText(it, text) }

    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = withContext(Dispatchers.IO) {
        if (kind == "notification") return@withContext withTimeoutOrNull(timeoutMs) {
            a11y.notificationEvents().first { packageName == null || it.packageName == packageName }
        }
        if (!hasDisplay) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            val event = a11y.eventsFor(displayId).first { e ->
                (packageName == null || e.packageName?.toString() == packageName) && when (kind) {
                    "window" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(), event.text.joinToString(" ").take(2_000))
        }
    }

    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = withContext(Dispatchers.IO) {
        if (kind != "notification" && !hasDisplay) return@withContext emptyList()
        val collected = mutableListOf<ScreenEvent>()
        val stream = if (kind == "notification") a11y.notificationEvents().filter { packageName == null || it.packageName == packageName }
        else a11y.eventsFor(displayId)
            .filter { event ->
                (packageName == null || event.packageName?.toString() == packageName) && when (kind) {
                    "window" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            .map { event -> ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(),
                event.text.joinToString(" ").ifBlank { event.className?.toString().orEmpty() }.take(2_000)) }
        withTimeoutOrNull(durationMs) { stream.take(maxEvents).collect { collected += it } }
        collected
    }

    override suspend fun systemCommand(command: String): SystemCommandResult? = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext null
        val result = shell.runSystemCommand(displayId, command)
        SystemCommandResult(result.getInt("exitCode", -1), result.getString("output").orEmpty())
    }

    // ---------- Action ----------

    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        when (val target = resolve(ref, snapshotId)) {
            null -> ActionResult.Failed("ref_expired")
            else -> verified(relocatedTo = target.second) { click(target.first) }
        }
    }

    override suspend fun tapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { tapAt(x, y) }
    }

    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val target = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
        val rect = android.graphics.Rect().also { target.first.getBoundsInScreen(it) }
        verified(relocatedTo = target.second) { doubleTapAt(pacing.within(rect.left, rect.top, rect.right, rect.bottom)) }
    }

    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { doubleTapAt(pacing.near(x, y)) }
    }

    // Double-click can only be two strokes in one gesture: it is sent in two times, and the callback
    // delay in the middle may exceed the time limit for the system to recognize a double-click.
    private suspend fun doubleTapAt(at: Pair<Int, Int>): Boolean {
        delay(pacing.approach())
        return a11y.taps(displayId, at.first.toFloat(), at.second.toFloat(), 2, pacing.doublePress(), pacing.doubleGap())
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { drag(listOf(x1 to y1, x2 to y2), durationMs, bow = true) }
    }

    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        if (paths.isEmpty() || paths.size > 2 || paths.any { it.size < 2 || it.size > 32 }) return@withContext ActionResult.Failed("invalid_gesture")
        verified {
            // Follow the drawn path with one finger and arrange the speed according to the rhythm;
            // moving several fingers together will only make the duration fluctuate a little.
            if (paths.size == 1) return@verified drag(paths.single(), durationMs, bow = false)
            delay(pacing.approach())
            a11y.gesture(displayId, paths.map(::pathOf), pacing.duration(durationMs))
        }
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        var relocatedTo: String? = null
        val start = if (ref != null && snapshotId != null) {
            val target = resolve(ref, snapshotId) ?: return@withContext ActionResult.Failed("ref_expired")
            relocatedTo = target.second
            target.first
        } else {
            read().entries.firstOrNull { it.element.scrollable }?.node
        }
        val node = start?.let { ancestorOrSelf(it) { n -> n.isScrollable } }
        if (node == null) {
            // Use a swipe in the middle of the screen when there are no scrollable controls
            val cx = display.width / 2
            val (from, to) = if (down) display.height * 3 / 4 to display.height / 4 else display.height / 4 to display.height * 3 / 4
            val r = swipe(cx, from, cx, to)
            return@withContext if (r is ActionResult.Ok && relocatedTo != null) r.copy(relocated = true, relocatedTo = relocatedTo) else r
        }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        verified(relocatedTo = relocatedTo) { node.performAction(action) }
    }

    /**
     * When staying, scroll gently: slowly draw [fraction] screen heights in the middle of the scrollable
     * content (information flow, text) that takes up more than half of the screen, stop, then raise your
     * hand without shaking. It does not scroll when the input method is playing, the input box holds the
     * focus, is covered by other windows (pop-up boxes, floating layers), and does not have scrollable
     * content large enough.
     */
    override suspend fun idleScroll(fraction: Double): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay || imeBox() != null || focusedEditable() != null) return@withContext false
        if (ScreenReader.contentWindows(windows()).count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } > 1) return@withContext false
        val area = read().entries.filter { it.element.scrollable }
            .map { e -> android.graphics.Rect().also { e.node.getBoundsInScreen(it) } }
            .maxByOrNull { it.width().toLong() * it.height() } ?: return@withContext false
        if (area.width().toLong() * area.height() < display.width.toLong() * display.height * IDLE_MIN_AREA) return@withContext false
        val dist = (display.height * kotlin.math.abs(fraction)).roundToInt().coerceAtMost(area.height() / 2)
        if (dist < IDLE_MIN_PX) return@withContext false
        val cx = area.centerX()
        val mid = area.centerY()
        // Look down (fraction is positive) and move your finger upwards
        val (from, to) = if (fraction > 0) mid + dist / 2 to mid - dist / 2 else mid - dist / 2 to mid + dist / 2
        drag(listOf(cx to from, cx to to), IDLE_SCROLL_MS, bow = true)
    }

    override suspend fun back(): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }
    }

    override suspend fun pressKey(keyCode: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        verified { shell.injectKey(displayId, keyCode) }
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val found = when (val f = findField(ref, snapshotId)) {
            is Field.Found -> f
            is Field.Missing -> return@withContext when {
                f.reason != NO_FOCUS -> ActionResult.Failed(f.reason)
                // There is no input focus on anything on the screen, and no input method pops up: there
                // is no frame to hold the paste, and there is no way to paste it (if you move it
                // elsewhere on the interface, it will be regarded as "possibly entered")
                !somethingHoldsInputFocus() -> ActionResult.Failed(NO_FOCUS)
                else -> typeUnseen(text)
            }
        }
        var field = found.node
        val original = FieldShape.of(field)
        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val web = inWebView(field)
        // The webpage editing area requires pasting to receive input; the password box does not go
        // through the clipboard.
        var pasteUnavailable = web && field.isPassword
        val placeholder = ScreenReader.placeholderOf(field)
        val io = object : TextField {
            override fun read(): String? {
                if (field.refresh()) return ScreenReader.fieldText(field, placeholder, text)
                // Some apps replace input controls as soon as the content of the app changes: read it
                // only when the one currently holding the input focus matches the original type,
                // resource ID, and location. Otherwise, it will not be read back and it will not be
                // written to another box.
                val now = focusedEditable()?.takeIf { sameField(original, it) } ?: return null
                field = now
                return ScreenReader.fieldText(now, placeholder, text)
            }
            override fun write(text: String): Boolean {
                if (!web || pasteUnavailable) return setText(field, text)
                return when (val r = inputText(field, text)) {
                    null -> { pasteUnavailable = true; setText(field, text) }
                    else -> r
                }
            }
        }
        val textsBefore = read().texts.toSet()
        // Type it out paragraph by paragraph like a human being (see TypingPacing); the password box and
        // the web page box that need to be pasted to be received are still written at once. Stop when
        // writing fails and the box does not accept half a word. Next, write the entire paragraph and
        // read it back for verification.
        typing(field.packageName?.toString()).forField(web, field.isPassword).leadIn(io, text)
        var frozen = false
        val verification = typeAndVerify(
            io, text, TYPE_ATTEMPTS,
            settle = { withEventClock { clock -> clock.awaitQuiet(READBACK_QUIET_MS, READBACK_CAP_MS) } },
            // When the readback remains unchanged, check whether there are new words (associative words,
            // etc.) containing the entered words popping up on the interface.
            reacted = {
                val typed = text.filterNot { it.isWhitespace() }
                (read().texts.toSet() - textsBefore).any { typed in it.filterNot { c -> c.isWhitespace() } }.also { frozen = it }
            },
            secret = field.isPassword,
        )
        val hint = listOfNotNull(
            HINT_TAPPED_TO_FOCUS.takeIf { found.tapped },
            HINT_READBACK_FROZEN.takeIf { frozen },
            HINT_PASTE_UNAVAILABLE.takeIf { pasteUnavailable },
        ).joinToString(" ").ifEmpty { null }
        // The words may only be displayed but not received by the App: "Consistent" cannot be reported.
        val reported = if (pasteUnavailable && verification != Verification.MISMATCH) Verification.UNKNOWN else verification
        ok(verification = reported, hint = hint, secret = field.isPassword, relocatedTo = found.relocatedTo)
    }

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val found = when (val f = findField(ref, snapshotId, tapToFocus = false)) {
            is Field.Found -> f
            is Field.Missing -> return@withContext when {
                f.reason != NO_FOCUS -> ActionResult.Failed(f.reason)
                !somethingHoldsInputFocus() -> ActionResult.Failed(NO_FOCUS)
                else -> clearUnseen()
            }
        }
        val field = found.node
        verified(relocatedTo = found.relocatedTo) { if (inWebView(field)) inputText(field, "") == true else setText(field, "") }
    }

    // ---------- Input box not visible in the tree ----------

    /**
     * To type into the input box at this point: click it first and wait for the interface to be quiet;
     * click on the input box that is visible in the tree and have the focus, then type on it as usual,
     * otherwise press [typeUnseen] to paste and check. The text on the screen before pasting will be
     * read after clicking (the clicked cursor and keyboard are not counted as the words typed). No
     * typing when switching to other apps (clicking on links or buttons).
     */
    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val tapped = verified { tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // Clicking on it opens another app (the same applies to opening it on the user's home screen and
        // then moving it back): Don't type, report the result of this click as it is.
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        if (focusedEditable() != null) return@withContext typeText(null, null, text)
        typeUnseen(text, atPoint = true)
    }

    /** Clear the input box at this point, the method is the same as [typeAt]. */
    override suspend fun clearAt(x: Int, y: Int): ActionResult = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext NO_APP_OPEN
        val tapped = verified { tapAt(x, y) }
        if (tapped !is ActionResult.Ok) return@withContext tapped
        // Clicked and opened another app (the same applies to the one that was opened on the user's home
        // screen and then moved back): Do not clear it. Report the result of this click as it is.
        if (tapped.opened != null) return@withContext tapped
        tapped.appSwitched?.takeIf { !tapped.switchedByTarget }?.let { return@withContext UnseenField.tapSwitched(it) }
        if (focusedEditable() != null) return@withContext clearText(null, null)
        clearUnseen(atPoint = true)
    }

    /**
     * There is no input box with focus in the tree: the input box may be in the interface drawn by the
     * App itself, and the tree cannot read it, and the focus is on it. First select all (replace the
     * original words, the same as setting words for nodes), then paste through the clipboard, and the
     * keystrokes will only be sent to Mina's screen; then compare the words read on the screen before
     * and after typing ([UnseenField.typed]). The input method is not required to be played: the keys
     * are sent to the window with the focus on this screen, and the frame can be received without going
     * through the input method; if the input method is not played and nothing has changed, please ask
     * the model to click on the input box first. When playing the input method, the words on the
     * keyboard are not counted as the words on the screen.
     */
    private suspend fun typeUnseen(text: String, atPoint: Boolean = false): ActionResult {
        val ime = imeBox()
        val before = readVisibleText()?.let { UnseenField.outside(it, ime) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        var pasted: Boolean? = null
        val changed = withEventClock { clock ->
            clock.mark()
            pasted = pasteUnseen(text, beforeFrame)
            pasted == true && awaitChange(clock, beforeTree, beforeFrame)
        }
        when (pasted) {
            null -> return ActionResult.Failed("paste_unavailable", HINT_PASTE_BLOCKED)
            false -> return ActionResult.Failed("action_failed")
            else -> Unit
        }
        val after = readVisibleText()?.let { UnseenField.outside(it, imeBox() ?: ime) }
        return when (val r = UnseenField.typed(text, before, after, changed, keyboardUp = ime != null, atPoint = atPoint)) {
            is ActionResult.Ok -> ok(changed = r.changed, verification = r.verification, hint = r.hint)
            else -> r
        }
    }

    /**
     * Change the clipboard to [text], select all, Ctrl+V, wait for the app to read (the screen has
     * changed) or timeout before restoring. Return null: The clipboard cannot be used safely (the user's
     * original content cannot be read), and nothing is moved; false: The paste key cannot be sent.
     * Clipboard saving, restoration, and encryption are the same as [inputText].
     */
    private fun pasteUnseen(text: String, beforeFrame: IntArray?): Boolean? {
        clipLock.lock()
        clipHeld(true)
        try {
            if (runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED) != ShellService.CLIP_SET) return null
            runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
            ctrlKey(KeyEvent.KEYCODE_A)
            if (!ctrlKey(KeyEvent.KEYCODE_V)) return false
            // The app reads the clipboard when processing the paste key: the screen has changed and then
            // restored, it will take a while at most.
            val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline && !FrameMath.differs(beforeFrame, frameSignature())) Thread.sleep(POLL_MS)
            return true
        } finally {
            runCatching { shell.restoreClip() }
            clipHeld(false)
            clipLock.unlock()
        }
    }

    /** The same premise as [typeUnseen]; after selecting all, press the delete key. If the input method does not pop up and the interface does not change at all, please click the input box ([UnseenField.cleared]) on the model first. */
    private suspend fun clearUnseen(atPoint: Boolean = false): ActionResult {
        val keyboardUp = imeBox() != null
        val r = verified { ctrlKey(KeyEvent.KEYCODE_A) && shell.injectKey(displayId, KeyEvent.KEYCODE_DEL) }
        return UnseenField.cleared(r, keyboardUp, atPoint)
    }

    private fun ctrlKey(keyCode: Int): Boolean = runCatching { shell.injectCtrlKey(displayId, keyCode) }.getOrDefault(false)

    /** There is an input method popping up on this screen (press return at this time, Android will put away the input method first). */
    fun keyboardUp(): Boolean = hasDisplay && imeBox() != null

    /** The range of the input method window on this screen ([l,t,r,b] from 0–999); the input method is null if there is no input method. The words on it when playing are not counted as the words on the screen. */
    private fun imeBox(): List<Int>? {
        val w = windows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val r = android.graphics.Rect().also(w::getBoundsInScreen)
        if (r.isEmpty) return null
        return listOf(normalize(r.left, display.width), normalize(r.top, display.height), normalize(r.right, display.width), normalize(r.bottom, display.height))
    }

    /**
     * Start to the virtual screen; when [cold], end the process first and return to the initial page of
     * the App. The user does not start or end the app when using it on the home screen. If the user
     * waits and tries again, if it still doesn't work, the agent is asked to pause or ask the user to
     * take over. The virtual screen was built when it was first used here.
     */
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = open(packageName, null, cold)

    /**
     * Open the copy installed in another user [userId] (the caller first checked it by pressing
     * [appCopies]): before starting the built-in assistant, check again according to the same rule
     * whether the user copy can be used, and it cannot be used as copy_not_found; only this copy is
     * considered for occupation and cold start. [userId] is equal to [openApp] when it is Mina's own
     * user.
     */
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        open(packageName, userId.takeIf { it != myUser }, cold)

    /**
     * Wait for other tasks to be released [packageName] (模型打开它时要了等): Release means that there is no
     * longer it on other screens, and there are no other tasks occupying it in the occupation
     * registration. It only makes sense when running parallel tasks, otherwise there will be no other
     * tasks competing with it.
     */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        if (!checkOtherTasks) return AppWait.Unsupported
        val user = userId?.takeIf { it != myUser }
        return awaitApp(packageName, user, timeoutMs) {
            withContext(Dispatchers.IO) { !onOtherDisplay(packageName, user) && appFree(packageName, user) }
        }
    }

    /** This package (the [user] one) is currently open on another screen of Mina (in the hands of another task). */
    private fun onOtherDisplay(packageName: String, user: Int?): Boolean {
        val other = runCatching { if (user == null) shell.displayOfPackage(packageName) else shell.displayOfPackageInUser(packageName, user) }.getOrDefault(-1)
        return other >= 0 && other != display.current?.displayId
    }

    /** The common part of [openApp] / [openAppCopy]; if [user] is empty, it is the original share. */
    private suspend fun open(packageName: String, user: Int?, cold: Boolean): ActionResult = withContext(Dispatchers.IO) {
        // The same app cannot be on two screens at the same time: the system will move the entire task
        // over, which is equivalent to taking it away from the other task. If it contains two portions,
        // each of the two portions will be counted as its own.
        if (checkOtherTasks) {
            if (onOtherDisplay(packageName, user)) return@withContext IN_USE_BY_OTHER
            if (!(if (user == null) reserve(packageName) else reserveCopy(packageName, user))) return@withContext IN_USE_BY_OTHER
        }
        // The top window before opening: the selection box that pops up later may be the selection box
        // caused by this opening (it was not selected last time, but it is still the same selection box
        // when it is opened again)
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        beforeOpen(packageName, user)
        fun launch() = when {
            user != null -> shell.startAppInUser(id, packageName, user, cold)
            cold -> shell.restartApp(id, packageName)
            else -> shell.startApp(id, packageName)
        }
        var code = launch()
        val retryUntil = SystemClock.uptimeMillis() + IN_USE_RETRY_MS
        while (code == ShellService.START_IN_USE_ON_MAIN && SystemClock.uptimeMillis() < retryUntil) {
            delay(IN_USE_POLL_MS)
            code = launch()
        }
        if (code != ShellService.START_OK && code != ShellService.START_OK_COLD_IGNORED) notOpened(packageName, user)
        when (code) {
            ShellService.START_OK, ShellService.START_OK_COLD_IGNORED -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_NOT_FOUND -> return@withContext ActionResult.Failed("app_not_found")
            ShellService.START_USER_NOT_ALLOWED -> return@withContext ActionResult.Failed(COPY_NOT_FOUND)
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = if (code == ShellService.START_OK_COLD_IGNORED) HINT_COLD_IGNORED else null, user = user)
    }

    /**
     * Share text to [packageName]: Just like [openApp], the screen is created for the first time. It is
     * not opened when the user is using it on the home screen. After opening it, it also waits for it to
     * come to the front desk, and the recognition system asks which one to open. This App does not
     * accept text sharing as cannot_share. Only the sharing page is opened, and the model clicked on
     * that page is not posted.
     */
    override suspend fun shareText(packageName: String, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (checkOtherTasks) {
            val other = runCatching { shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != display.current?.displayId) return@withContext IN_USE_BY_OTHER
            if (!reserve(packageName)) return@withContext IN_USE_BY_OTHER
        }
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        beforeOpen(packageName, null)
        val code = runCatching { shell.startShare(id, packageName, text) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true)
    }

    /**
     * Open Mina's compatibility self-test page on Mina's screen ([intent] specifies the component; only
     * for compatibility self-test). That page is only open to callers at the shell level, leaving it to
     * the built-in assistant to boot directly to this screen as a shell (the assistant only recognizes
     * this component); shell startup is not restricted by the background startup page, and it does not
     * matter whether Mina is in the foreground. Then wait for it to reach the top of the Mina screen
     * like [openApp]; build the screen when using it for the first time.
     */
    suspend fun openOwnPage(intent: Intent): ActionResult = withContext(Dispatchers.IO) {
        val before = if (hasDisplay) topWindowPackage().takeIf { it != chooserPackage } else null
        val id = display.get().displayId
        when (runCatching { shell.startSystemAction(id, intent, null) }.getOrDefault(ShellService.START_ERROR)) {
            ShellService.START_OK -> Unit
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("own_page_not_allowed")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(a11y.packageName, before, hint = null)
    }

    /**
     * After startup is issued: wait[packageName]Go to the top of the Mina screen, or the system will ask
     * which one to open first ([before]is the package where the top window is located before
     * startup).[sharing]: This time it’s about sharing (the system asks which one to use when there are
     * several sharing pages in the App, and the App name may not be included in the options): The top
     * window is replaced by the system package window, which counts as asking.
     */
    private suspend fun awaitOpened(packageName: String, before: String?, hint: String?, sharing: Boolean = false, user: Int? = null): ActionResult {
        targetPackage = packageName
        chooserPackage = null
        requestedUser = user
        // Opened: Mina is the top task on the screen (if you cannot ask for a task, return to the top
        // window for accessibility); when you specify which task it is, it must be the user's task.
        fun launched(): Boolean = (foregroundTaskPackage()?.let { it == packageName } ?: (ScreenReader.topPackage(windows()) == packageName)) &&
            (user == null || userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).let { it < 0 || it == user })
        fun picker(): String? = if (sharing) systemPickerOf(packageName, before) else null
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        // When the system first asks which one to open, the app will not go to the foreground by itself:
        // it will stop waiting when it sees the selection box.
        while (!launched() && chooserOf(packageName, before) == null && picker() == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val onTop = launched()
        val task = if (onTop) shell.topTaskId(displayId) else -1
        _target.value = TargetApp(packageName, task, if (task >= 0) userOf(task) else user ?: -1)
        if (!onTop && user != null) {
            // What I want is another copy, but the system first asks which one to open: I click on the
            // options one by one and identify which copy is opened, without leaving it to the model to
            // guess.
            if (chooserTargets(packageName, before) != null) return resolveCopyChooser(packageName, user, before, hint)
            // What is directly opened is the other copy of this App: it fails as it is and is not the
            // desired copy.
            if (foregroundTaskPackage() == packageName) return copyNotOpened(user, userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)))
        }
        // The system is asking which one to open (there are two copies of the App installed, etc.): it
        // is considered open and waits for the model to be selected; after selecting the App, it comes
        // to the foreground, and subsequent actions will recognize it as the target. The system window
        // (permission box) covered when the App is on top does not count
        if (!onTop) chooserOf(packageName, before)?.let { first ->
            // The first time I saw it, I asked how many copies were packed so I could mark which one was
            // the other.
            if (profiles == null) otherProfiles()
            val choice = chooserOf(packageName, before) ?: first
            chooserPackage = choice.packageName
            return ok().copy(chooser = choice, appSwitched = null, switchedByTarget = false)
        }
        // When sharing, the system asks which sharing page of this app to use: it is also considered
        // open, and waits for the model to be selected.
        if (!onTop) picker()?.let { pkg ->
            chooserPackage = pkg
            return ok(hint = HINT_SHARE_PICKER).copy(appSwitched = null, switchedByTarget = false)
        }
        // If you haven’t opened it, tell the truth and tell the truth about what is on top at the
        // moment. If you don’t open it, you will not be reported as successful.
        if (!onTop) {
            val now = foregroundTaskPackage() ?: ScreenReader.topPackage(windows())
            // I want another copy, but the other copy of this app is on top.
            val copy = if (user != null && now == packageName) " (a different copy of the app)" else ""
            return ActionResult.Failed("app_not_in_front", hint = now?.let { "In the foreground now: $it$copy." } ?: "Nothing came to the foreground.")
        }
        // It's open, but another package's window is covering it (permission pop-up window, account
        // selector): tell the truth who it is, it's still open
        val cover = ScreenReader.topPackage(windows())?.takeIf { it != packageName }
        return ok(hint = hint).let { if (cover != null) it.copy(coveredBy = cover, appSwitched = null, switchedByTarget = false) else it }
    }

    /**
     * What you want is the copy in another user [user], but the system asks which one to open on the
     * Mina screen (the options may have the same names, and you can’t tell which one is which): Click on
     * the options one by one, wait for them to open, and read the user to whom the top task belongs; if
     * it is the one you want, open it. Instead of just pressing return once to let it go to the
     * background (that may be the task on hand of the user and not ending it), open it again and click
     * on the next one. I've tried it all, but it still doesn't work. If it fails copy_not_opened, tell
     * me which copy was opened; don't treat the wrong copy as a success.
     */
    private suspend fun resolveCopyChooser(packageName: String, user: Int, before: String?, hint: String?): ActionResult {
        fun topTask() = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        suspend fun opened(): ActionResult? {
            if (foregroundTaskPackage() != packageName) return null
            val task = topTask()
            if (userOf(task) != user) return null
            _target.value = TargetApp(packageName, task, user)
            return ok(hint = hint)
        }
        var tried = 0
        var shown: Int? = null
        while (true) {
            val targets = chooserTargets(packageName, before) ?: break
            if (tried >= targets.size) break
            val (x, y) = targets[tried++]
            if (!runCatching { shell.injectTap(displayId, x, y) }.getOrDefault(false)) break
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (foregroundTaskPackage() != packageName && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            waitStable(SETTLE_CAP_MS)
            if (foregroundTaskPackage() != packageName) break
            opened()?.let { return it }
            shown = userOf(topTask())
            Log.i(TAG, "chooser option $tried opened user $shown, not the requested $user")
            runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }
            waitStable(SETTLE_CAP_MS)
            if (tried >= targets.size) break
            if (runCatching { shell.startAppInUser(displayId, packageName, user, false) }.getOrDefault(ShellService.START_ERROR) != ShellService.START_OK) break
            val until = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (chooserTargets(packageName, before) == null && SystemClock.uptimeMillis() < until) {
                // This time I directly issued the requested portion.
                opened()?.let { return it }
                delay(POLL_MS)
            }
        }
        return copyNotOpened(user, shown)
    }

    /** The requested copy [user] is not opened; [shown] is the user to whom the opened copy belongs (null/negative number if it is not opened or cannot be read). */
    private fun copyNotOpened(user: Int, shown: Int?): ActionResult.Failed {
        val what = when {
            shown == null || shown < 0 -> "Nothing from the app came to the front."
            shown == myUser -> "The main app opened instead (user $shown)."
            else -> "The copy in user $shown opened instead."
        }
        return ActionResult.Failed(COPY_NOT_OPENED, "$what Requested: the copy in user $user.")
    }

    /** When the system asks which one to open on the Mina screen, the center point of those options (pixels on the screen, the same options one after another); if it is not asking, it is null. The recognition method is the same as [chooserOf]. */
    private fun chooserTargets(packageName: String, before: String?): List<Pair<Int, Int>>? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val entries = ScreenReader.read(listOf(w), display.width, display.height).entries
        val picked = LaunchChooser.optionIndices(label, entries.map { it.element }) ?: return null
        return picked.map { i -> android.graphics.Rect().also(entries[i].node::getBoundsInScreen).let { it.centerX() to it.centerY() } }
    }

    /**
     * Open a link or settings page. Like [openApp], the screen is created for the first time; the user
     * does not open the app when using it on the home screen. The settings page is expected to fall in
     * the package where the system settings are located, and the specified App falls in that App;
     * falling elsewhere (system selector, jump page) will still be successful, with prompts.
     */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = withContext(Dispatchers.IO) {
        // If the App is specified, it will be viewed by the application access boundary; if it is not
        // specified, if the list is not empty, pick one that is not disabled to take over.
        val pinned = packageName ?: allowedLinkHandler(action, uri)
        val id = display.get().displayId
        val expected = pinned ?: settingsPackage.takeIf { action != null }
        if (checkOtherTasks && expected != null && !reserve(expected)) return@withContext IN_USE_BY_OTHER
        val before = ScreenReader.topPackage(windows())
        val beforeTree = treeHash()
        // Know who opened it in advance (specified App, settings page, and only one link to the App):
        // Quit before starting
        val early = expected ?: linkHandler(action, uri)
        early?.let { beforeOpen(it, null) }
        val code = shell.startView(id, action, uri, pinned)
        if (code != ShellService.START_OK) early?.let { notOpened(it, null) }
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("link_unresolved")
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("invalid_link")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        fun landed(): Boolean {
            val top = ScreenReader.topPackage(windows())
            return if (expected != null) top == expected else top != null && (top != before || treeHash() != beforeTree)
        }
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (!landed() && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val top = ScreenReader.topPackage(windows())
        // Only target the app that is opening the link: do not target it if it falls in the system
        // selector or elsewhere
        val opened = top?.takeIf { if (expected != null) it == expected else it !in systemPickers }
        if (opened != null) {
            // If you don’t know who opened the link in advance, you will occupy it after landing; if you
            // cannot occupy it, you will not withdraw it (I have checked other screens on the shell)
            if (checkOtherTasks) reserve(opened)
            targetPackage = opened
            requestedUser = null
            val task = shell.topTaskId(displayId)
            _target.value = TargetApp(opened, task, userOf(task))
        }
        ok(hint = if (expected != null && top != expected) hintLinkElsewhere(top, expected) else null)
    }

    /**
     * When the disabled list is not empty and the link does not specify an App, who should open it: use
     * the system default one that is not disabled, otherwise use the first one that is not disabled;
     * reject all available connections if they are disabled. If the list is empty or there is no App
     * that can be connected, it will be null (leave it to the system as usual, without checking it
     * again).
     */
    private fun allowedLinkHandler(action: String?, uri: String?): String? {
        val access = appAccess.current()
        if (access.blocked.isEmpty()) return null
        val intent = Intent(action ?: Intent.ACTION_VIEW).apply {
            uri?.let { data = android.net.Uri.parse(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = a11y.packageManager
        val candidates = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val allowed = candidates.filter { access.denial(it) == null }
        if (allowed.isEmpty()) access.denial(candidates.first())?.let { throw it }
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return default?.takeIf { it in allowed } ?: allowed.first()
    }

    /** It is not specified who opens the link of the App: the one directly determined by the system; if it needs to be asked by the user to choose (falling in the system selector) or if it cannot be read, it will be null. */
    private fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in systemPickers }

    private fun hintLinkElsewhere(top: String?, expected: String) =
        "The link opened, but the screen shows ${top ?: "no app yet"} instead of $expected: a system chooser or a redirect page may have taken it. Observe before acting."

    /**
     * After a task is moved back to this screen, wait until its window appears in the accessible window
     * list before letting the Agent read the screen; otherwise, the subsequent observe may read an empty
     * screen. It will return if it cannot wait, and it will be judged by the Agent himself by reading
     * the screen.
     */
    suspend fun awaitContent(): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (ScreenReader.topPackage(windows()) == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        ScreenReader.topPackage(windows()) != null
    }

    override suspend fun listApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) { launchableApps(a11y.packageManager, a11y.packageName) }

    // ---------- System Action ----------

    /**
     * Perform system actions via Android standard intent (see [SystemIntents]). First check who will
     * answer Mina (capability test, no need to build a screen): if not, it is not_supported; if there
     * are several, press [SystemIntents.choose] to directly specify one. Then let the built-in assistant
     * boot to Mina's screen (set up the screen when you use it for the first time), and stare at the
     * home screen as usual before and after the action. Alarm clocks and timers should be set quietly
     * and then read the next alarm clock of the system to check; however, the App reports accordingly
     * when the interface is opened. The list of alarm clocks and new schedules should wait for that app
     * to be at the top.
     */
    override suspend fun systemAction(action: SystemAction): SystemActionResult = withContext(Dispatchers.IO) {
        val intent = SystemIntents.toIntent(SystemIntents.spec(action))
        val pm = a11y.packageManager
        val handlers = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
                SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
        }.getOrDefault(emptyList())
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        // The only way to know who will pick up is here: skip the ones that are disabled; reject all the
        // ones that can be picked up if they are disabled.
        val access = appAccess.current()
        val allowedHandlers = if (access.blocked.isEmpty()) handlers else handlers.filter { access.denial(it.packageName) == null }
        if (handlers.isNotEmpty() && allowedHandlers.isEmpty()) access.denial(handlers.first().packageName)?.let { throw it }
        val handler = SystemIntents.choose(allowedHandlers, default)
            ?: return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
        val pkg = handler.packageName
        intent.component = android.content.ComponentName(pkg, handler.className)
        if (checkOtherTasks && !action.silent && !reserve(pkg)) return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
        val alarms = a11y.getSystemService(AlarmManager::class.java)
        val alarmBefore = runCatching { alarms?.nextAlarmClock?.triggerTime }
        val id = display.get().displayId
        val topBefore = ScreenReader.topPackage(windows())
        val w = armSpillWatch()
        // The page you took over may not have the permissions required by the shell (setting the alarm
        // clock): prepare one started as Mina.
        val asMina = PendingIntent.getActivity(a11y, SYSTEM_ACTION_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Quietly set (alarm clock, timer) without opening the interface or silencing it: the clock will
        // ring at one o'clock
        if (!action.silent) beforeOpen(pkg, null)
        val code = runCatching { shell.startSystemAction(id, intent, asMina) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK && !action.silent) notOpened(pkg, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext SystemActionResult(ActionResult.Failed("app_in_use_by_user"), pkg)
            ShellService.START_IN_USE_BY_OTHER -> return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
            ShellService.START_UNRESOLVED -> return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
            else -> return@withContext SystemActionResult(ActionResult.Failed("launch_failed"), pkg)
        }
        fun top() = ScreenReader.topPackage(windows())
        var alarmAfter = alarmBefore.getOrNull()
        if (action is SystemAction.SetAlarm && alarmBefore.isSuccess) {
            // Most clock apps are written in the background and then registered: wait for the next alarm
            // clock of the system to change, and wait for a while at most.
            val deadline = SystemClock.uptimeMillis() + ALARM_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                alarmAfter = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
                if (alarmAfter != alarmBefore.getOrNull()) break
                delay(ALARM_POLL_MS)
            }
        } else if (!action.silent) {
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (top() != pkg && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        }
        waitStable(SETTLE_CAP_MS)
        val landed = top() == pkg
        // It was supposed to be set up quietly, but there was an interface for that app on the screen.
        val showing = action.silent && landed && topBefore != pkg
        if (landed && (!action.silent || showing)) {
            targetPackage = pkg
            requestedUser = null
            val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
            _target.value = TargetApp(pkg, task, userOf(task))
        }
        val hint = if (!action.silent && !landed) hintNotInFront(top(), pkg) else null
        SystemActionResult(
            ok(hint = hint, spill = takeSpill(w)), handledBy = pkg,
            nextAlarmBefore = alarmBefore.getOrNull(), nextAlarmAfter = alarmAfter, alarmReadable = alarmBefore.isSuccess, showing = showing,
        )
    }

    private fun hintNotInFront(top: String?, expected: String) =
        "$expected took the action, but the screen shows ${top ?: "no app yet"}. Observe before acting."

    // ---------- Roller ----------

    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext WheelOutcome(false, null, null, 0, "none", error = "no_app_open")
        val (node, _) = resolve(ref, snapshotId) ?: return@withContext WheelOutcome(false, null, null, 0, "none", error = "ref_expired")
        val driver = WheelDriver(
            display.width, display.height,
            quiet = { q, cap -> withEventClock { it.awaitQuiet(q, cap) } },
            // Slow dragging: When walking according to the rhythm, slow down to a stop at the end and
            // let go, so as not to turn your head; when slow dragging cannot be moved, switch to a quick
            // stroke.
            drag = { points, slow -> drag(points, if (slow) WHEEL_DRAG_MS else WHEEL_SWIPE_MS, bow = false) },
            ocr = { readVisibleText() },
            imeShown = { imeBox() != null },
            back = { runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }.getOrDefault(false) },
        )
        driver.set(node, value)
    }

    // ---------- Which one to open ----------

    /** The package where the top readable window (regardless of type: some system selection boxes are application windows, some are system windows) is located. */
    private fun topWindowPackage(): String? =
        ScreenReader.contentWindows(windows()).firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /**
     * After opening [packageName], the system asks which one to open: the top one is replaced by the
     * window of another system package ([before] was the top one before opening), and the options in the
     * window mention the name of this App. Not null. Only recognize system packages: mentioning this
     * name in other App windows does not count.
     */
    private fun chooserOf(packageName: String, before: String?): LaunchChoice? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val elements = ScreenReader.read(listOf(w), display.width, display.height).entries.map { it.element }
        val options = LaunchChooser.options(label, elements, profiles?.get(packageName).orEmpty()) ?: return null
        return LaunchChoice(pkg, options)
    }

    /** The top window that can be read is replaced by the system package (not [packageName], nor [before] before startup): that package; is not null. */
    private fun systemPickerOf(packageName: String, before: String?): String? {
        val pkg = topWindowPackage() ?: return null
        return pkg.takeIf { it != packageName && it != before && isSystemPackage(it) }
    }

    private val systemPackages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** For packages that come with the system; for packages that Mina cannot see (package visibility starting from Android 11), ask the built-in assistant. */
    private fun isSystemPackage(pkg: String): Boolean = pkg in systemPickers || systemPackages.getOrPut(pkg) {
        val flags = runCatching { a11y.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull()
        if (flags != null) flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        else runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false)
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = a11y.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Apps that are also installed in other users (package name → names of those users); ask the built-in assistant for the first time, and then use this one. Don't remember when you can't ask, and ask again next time. */
    @Volatile private var profiles: Map<String, List<String>>? = null

    override suspend fun otherProfiles(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        profiles ?: readProfiles()?.also { profiles = it }.orEmpty()
    }

    private fun readProfiles(): Map<String, List<String>>? {
        val me = myUser
        val users = AppProfiles.parseUsers(runCatching { shell.listUsers() }.getOrNull() ?: return null).filter { it.id != me && it.running }
        return AppProfiles.alsoIn(users.associateWith { u ->
            AppProfiles.parsePackages(runCatching { shell.listPackages(u.id) }.getOrNull() ?: return null)
        })
    }

    /** Another App that can be opened (for rules, see [AppCopies], the built-in assistant determines which apps can be used by users); if asked every time, it will be empty if not asked. */
    override suspend fun appCopies(): List<AppCopy> = withContext(Dispatchers.IO) {
        runCatching { AppCopyReader.read(shell, listApps()) }.getOrNull().orEmpty()
            .also { copies -> copiedPackages = copiedPackages + copies.map { it.packageName } }
    }

    /** Packages that have been listed in another copy: only ask them if they have been changed to another copy ([switchedTo]). */
    @Volatile private var copiedPackages: Set<String> = emptySet()

    /** The user to whom the task belongs; -1 if the task does not exist or cannot be requested. */
    private fun userOf(task: Int): Int = if (task < 0) -1 else runCatching { shell.userOfTask(task) }.getOrDefault(-1)

    // ---------- Verification ----------

    /**
     * Execute the action, wait for the interface to be quiet, and then compare the front and rear
     * fingerprints; then check to see if the front desk has been cut away. Staring at the home screen
     * before and after the action: The action causes the app to open on the user's home screen and then
     * move it back, and the result will be reported as opened (see [takeSpill]). It took a while for the
     * previous action to open the home screen. If you don't do this action, let's talk about the screen
     * changing first.
     */
    private suspend fun verified(relocatedTo: String? = null, action: suspend () -> Boolean): ActionResult {
        lateSpill()?.let { return ActionResult.Failed("opened_elsewhere", hintLateSpill(it.opened)) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        val w = armSpillWatch()
        val changed = withEventClock { clock ->
            clock.mark()
            if (action()) awaitChange(clock, beforeTree, beforeFrame) else null
        } ?: return ActionResult.Failed("action_failed")
        // Another one was opened from the moved tasks, and they are included together.
        val profile = adoptFromAdopted(w)
        val spill = takeSpill(w)
        return ok(changed = changed || spill?.opened?.pulledBack == true, relocatedTo = relocatedTo, spill = spill, inProfile = profile)
    }

    // ---------- Open to the user's home screen ----------

    /** Tracking of an action: [since] is the starting time, [topBefore] is the top task on the main screen at that time, [agentTopBefore] is the top task on the Mina screen at that time. */
    private class Watch(val since: Long, val topBefore: Int, val agentTopBefore: Int)

    /** The apps you moved back and the options you recognize (when asked which one to open). */
    private class Spill(val opened: OpenedElsewhere, val chooser: LaunchChoice?)

    /** Tracking of the latest action; cleared after [SPILL_WINDOW_MS]. */
    @Volatile private var watch: Watch? = null

    /** To what time will the judgment be recorded? The same sentence will not be judged twice. */
    @Volatile private var consumed = 0L

    /** Tasks moved back to the Mina screen from the user's home screen, and tasks opened from them: they are counted as being opened by the target app at the top, and are not counted as being removed. */
    private val adopted: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The system asks which one to open on the user's home screen and waits for the user to select it:
     * [taskId] asks about the task in that window, [packageName] asks about its package, [app] asks
     * about which app, [topBefore] asks about the top task on the home screen before it appears,
     * [agentTop] asks about the top task on the Mina screen at that time, [since] when it appears. The
     * selection box is never moved: if the screen is moved, it will be rebuilt on the new screen, and
     * some will be closed when rebuilt; Mina will not reopen it for other apps (the one that opens it
     * must be the original app).
     */
    class UserPick(val taskId: Int, val packageName: String, val app: String?, val topBefore: Int, val agentTop: Int, val since: Long)

    private val _userPick = MutableStateFlow<UserPick?>(null)
    /** It is waiting for the user to select it on the home screen; the task is stopped accordingly, and the user is asked to select it on the capsule. */
    val userPick: StateFlow<UserPick?> = _userPick.asStateFlow()

    /** After the user has finished selecting: the selected App needs to be processed by the user personally ([NeedsUser], stay on the home screen), and the rest has been done (the results will be reported in the next screen reading or action). */
    sealed interface PickResult {
        data object Settled : PickResult
        class NeedsUser(val taskId: Int, val packageName: String, val app: String?, val userId: Int) : PickResult
    }

    /** After the user completes the tasks, the model must be informed, and the next time the user reads the screen or performs an action, it will be reported. */
    @Volatile private var pendingReport: Spill? = null

    /** The time and context when the user clicked something on the home screen (only recorded in the short period before and after the action). */
    private val userTaps = java.util.concurrent.ConcurrentLinkedDeque<MainScreenSpill.Tap>()
    private val spillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var tapWatch: Job? = null
    @Volatile private var tapWatchUntil = 0L

    /** Not counting those opened on the home screen: Mina itself, desktop App (press HOME to analyze, do not hardcode). */
    private val notSpills: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching { a11y.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
            .toSet() + a11y.packageName
    }

    /** Before taking action: Please use the built-in assistant to stare at the home screen and write down the task on the top of the two screens at the moment; it will be null (not staring) when there is no screen, it is being taken over, or the assistant does not support it. */
    private fun armSpillWatch(): Watch? {
        if (!hasDisplay || handedOver()) return null
        val since = SystemClock.uptimeMillis()
        watchUserTaps(since + SPILL_WINDOW_MS)
        val top = runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }.getOrNull() ?: return null
        val agentTop = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        return Watch(since, top, agentTop).also { watch = it }
    }

    /** During the tracking period, record the user's clicks on the home screen (Mina's own floating layer does not count); it will stop when it expires. */
    private fun watchUserTaps(until: Long) {
        tapWatchUntil = maxOf(tapWatchUntil, until)
        if (tapWatch?.isActive == true) return
        tapWatch = spillScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                    if ((e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) &&
                        e.packageName?.toString() != a11y.packageName
                    ) {
                        userTaps.addLast(MainScreenSpill.Tap(SystemClock.uptimeMillis(), e.packageName?.toString()))
                        while (userTaps.size > MAX_USER_TAPS) userTaps.pollFirst()
                    }
                }
            }
            while (true) {
                val left = tapWatchUntil - SystemClock.uptimeMillis()
                if (left <= 0) break
                delay(left)
            }
            collecting.cancel()
        }
    }

    /**
     * After the action: Ask the built-in assistant about the tasks that will appear on the home screen
     * after the action starts. Press [MainScreenSpill] to confirm; if it is opened on the home screen,
     * move it back to Mina's screen. If it is opened in the app that the user is using, it will only be
     * reported. None is null.
     */
    private suspend fun takeSpill(w: Watch?): Spill? {
        if (w == null || handedOver()) return null
        val b = runCatching { shell.mainArrivals(maxOf(w.since, consumed + 1)) }.getOrNull() ?: return null
        val arrivals = parseArrivals(b)
        arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
        var last: Spill? = null
        for (v in MainScreenSpill.decide(arrivals, w.since, SPILL_WINDOW_MS, w.topBefore, notSpills, userTaps.toList())) {
            val a = v.arrival
            val pkg = a.packageName ?: continue
            // Open to the part of the user's home screen that is not visible within the application
            // access boundary: disabled items cannot be moved or read.
            appAccess.current().denial(pkg)?.let { throw it }
            last = when (v) {
                // If the system asks which one to open, press the selection box first; pop-up windows
                // that require the user to handle by themselves (such as login verification and account
                // confirmation) remain on the home screen and are not moved to Mina's screen.
                is MainScreenSpill.PullBack -> leaveForUser(a, pkg, w) ?: leavePopup(a, pkg) ?: pullBack(a, pkg) ?: last
                is MainScreenSpill.InUse -> Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId)), null)
            }
        }
        if (last != null) Log.i(TAG, "opened on the main screen after an action: ${last.opened}")
        return last
    }

    /**
     * Ask again when the tracking of the last action has not expired (that action was launched after a
     * while); clear it when it expires. What the user wants to say after selecting and processing on the
     * home screen, let’s talk about this first.
     */
    private suspend fun lateSpill(): Spill? {
        pendingReport?.let { pendingReport = null; return it }
        val w = watch ?: return null
        val spill = takeSpill(w)
        if (SystemClock.uptimeMillis() > w.since + SPILL_WINDOW_MS) watch = null
        return spill
    }

    /**
     * What pops up on the home screen are pop-ups that require users to handle by themselves (login
     * verification, account confirmation, payment confirmation, etc.): they do not move and stay on the
     * user's screen. Criteria: On the page of the system account login service (a package with an
     * account authenticator registered and no desktop icon), the top window is not the entire screen
     * (dialog box, bottom pop-up layer), there is a password box in the window, or the page itself does
     * not enter recent tasks (excludeFromRecents). This kind of window disappears from the user's eyes
     * when it is moved to Mina's screen, and the user cannot click OK on the home screen. Not null.
     * noHistory does not count: many apps have it on their opening pages, and ordinary apps are treated
     * as pop-up windows when included.
     */
    private suspend fun leavePopup(a: MainScreenSpill.Arrival, pkg: String): Spill? =
        if (needsUserWindow(a, pkg)) popup(a, pkg) else null

    /** Is the top-level window for this task on the home screen a pop-up window that requires the user to handle manually (see [leavePopup]). Wait up to two more [POPUP_SETTLE_MS]. */
    private suspend fun needsUserWindow(a: MainScreenSpill.Arrival, pkg: String): Boolean {
        if (isAuthenticatorUi(pkg)) return true
        val activity = a.topActivity?.let {
            runCatching { a11y.packageManager.getActivityInfo(ComponentName(pkg, it), 0) }.getOrNull()
        }
        if (activity != null && activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0) return true
        val metrics = a11y.resources.displayMetrics
        // The window that just popped up may still be in the opening animation (drawn smaller than the
        // entire screen). Wait until it stops and look at it again to avoid treating the ordinary app as
        // a pop-up window.
        var window = topWindowOf(pkg)
        if (window == null) {
            delay(POPUP_SETTLE_MS)
            window = topWindowOf(pkg) ?: return false
        }
        if (isNarrow(window, metrics)) {
            delay(POPUP_SETTLE_MS)
            val settled = topWindowOf(pkg) ?: return false
            if (isNarrow(settled, metrics)) return true
            window = settled
        }
        return ScreenReader.read(listOf(window), metrics.widthPixels, metrics.heightPixels).passwordField
    }

    private fun topWindowOf(pkg: String): AccessibilityWindowInfo? =
        ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg }

    /**
     * Is this package the system's account login service? The account authenticator
     * (AccountManager.authenticatorTypes, platform data) is registered, but there is no desktop icon (no
     * LAUNCHER entry). This kind of package does not have its own homepage. The only pages that pop up
     * on the home screen are pages such as adding an account, logging in, verifying, and verifying
     * codes. They are the user's business. The entire screen cannot be moved to Mina's screen without a
     * password box. Authenticator packages with desktop icons (chat apps, email apps, etc. that
     * synchronize contacts) are not counted, and their conversation, sharing, and writing pages will be
     * moved back as usual. The authenticator table reads every time: The newly installed authenticator
     * can also be recognized. This step is only taken when a page pops up on the home screen.
     */
    private fun isAuthenticatorUi(pkg: String): Boolean {
        val authenticators = runCatching { AccountManager.get(a11y).authenticatorTypes.mapNotNull { it.packageName }.toSet() }
            .getOrDefault(emptySet())
        if (pkg !in authenticators) return false
        // Packages with desktop icons must be visible to Mina (check LAUNCHER in the list); if you can't
        // find it, it's not there.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val hasLauncher = runCatching { a11y.packageManager.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(true)
        return MainScreenSpill.authenticatorUi(pkg, authenticators, hasLauncher)
    }

    /** The window does not cover the entire screen: dialog boxes, bottom pop-ups, floating windows, etc. */
    private fun isNarrow(window: AccessibilityWindowInfo, metrics: android.util.DisplayMetrics): Boolean {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return bounds.width() < metrics.widthPixels * FULL_SCREEN_SHARE || bounds.height() < metrics.heightPixels * FULL_SCREEN_SHARE
    }

    private suspend fun popup(a: MainScreenSpill.Arrival, pkg: String): Spill =
        Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId), needsUser = true), null)

    /**
     * What opens on the home screen is a window where the system asks "Which one to open with?" (The
     * options in the system selector or system package window correspond to the two installed apps; the
     * selection box of each system is not necessarily the original one): Do not move it, leave it to the
     * user to choose on their own screen, write it down and let the task wait. Not null.
     */
    private suspend fun leaveForUser(a: MainScreenSpill.Arrival, pkg: String, w: Watch): Spill? {
        val system = isSystemPackage(pkg)
        val choice = if (system) mainChooser(pkg) else null
        if (MainScreenSpill.handling(system, pkg in systemPickers, choice != null) != MainScreenSpill.Handling.LeaveForUser) return null
        val app = choice?.options?.firstOrNull()?.label ?: appLabel(pkg)
        _userPick.value = UserPick(a.taskId, pkg, app, w.topBefore, w.agentTopBefore, a.at)
        return Spill(OpenedElsewhere(pkg, app, pulledBack = false, picker = true), choice)
    }

    /** Move the task opened on the home screen to Mina's screen (only move it, do not reopen it), and wait for it to reach the top; if it cannot be moved, it is null. */
    private suspend fun pullBack(a: MainScreenSpill.Arrival, pkg: String, byUser: Boolean = false): Spill? {
        if (!runCatching { shell.moveTask(a.taskId, displayId) }.getOrDefault(false)) {
            Log.w(TAG, "could not move task ${a.taskId} ($pkg) from the main screen")
            return null
        }
        adopted += a.taskId
        // You don’t know in advance which app will be released: you can move it back and then occupy it;
        // if you can’t occupy it, you won’t withdraw it.
        if (checkOtherTasks) reserve(pkg)
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (runCatching { shell.topTaskId(displayId) }.getOrDefault(-1) != a.taskId && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        val profile = if (isSystemPackage(pkg)) null else inProfileOf(pkg, a.userId)
        return Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = true, inProfile = profile, byUser = byUser), null)
    }

    /**
     * Is the system package window on the home screen asking which one to open: I don’t know in advance
     * which app it is asking for, so use the name of the two installed apps to compare the options in
     * the window (this is the case when the same app is installed twice); if you only read this system
     * window, it will be null if it does not match.
     */
    private suspend fun mainChooser(pkg: String): LaunchChoice? {
        val w = ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg } ?: return null
        val elements = ScreenReader.read(listOf(w), display.width, display.height).entries.map { it.element }
        for ((app, names) in otherProfiles()) {
            val label = appLabel(app) ?: continue
            LaunchChooser.options(label, elements, names)?.let { return LaunchChoice(pkg, it) }
        }
        return null
    }

    /**
     * The task is adjusted before the next action of changing the screen: wait for the user to finish
     * selecting on the home screen (keep staring at the home screen before selecting). After selecting,
     * open the normal page on the home screen and move it back to Mina's screen (only move it, the
     * original app that opens it is still the same); those that require the user to handle manually
     * (requiring a password, the window cannot be read, such as payment, fingerprints) remain on the
     * home screen and return to [PickResult.NeedsUser]; those that are open on the Mina screen are also
     * included; if the user closes the problem, tell the truth. It is null when not waiting.
     */
    suspend fun awaitUserPick(): PickResult? = withContext(Dispatchers.IO) {
        val p = _userPick.value ?: return@withContext null
        try {
            while (runCatching { shell.displayOfTask(p.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY) {
                runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }
                delay(PICK_POLL_MS)
            }
            val gone = SystemClock.uptimeMillis()
            runCatching { shell.watchMainScreen(SPILL_WINDOW_MS.toInt()) }
            val deadline = gone + PICK_WAIT_MS
            while (true) {
                val arrivals = runCatching { shell.mainArrivals(p.since) }.getOrNull()?.let(::parseArrivals).orEmpty()
                val opened = MainScreenSpill.picked(arrivals, p.taskId, gone, PICK_SLACK_MS, PICK_WAIT_MS, p.topBefore, notSpills)
                arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
                if (opened != null) {
                    val pkg = opened.packageName!!
                    if (needsUser(pkg) || needsUserWindow(opened, pkg)) {
                        return@withContext PickResult.NeedsUser(opened.taskId, pkg, appLabel(pkg) ?: p.app, opened.userId)
                    }
                    pendingReport = pullBack(opened, pkg)
                    return@withContext PickResult.Settled
                }
                // The selected app is opened on Mina's screen (opened from there)
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                if (top >= 0 && top != p.agentTop && top !in adopted) {
                    adopted += top
                    waitStable(SETTLE_CAP_MS)
                    val pkg = runCatching { shell.packageOfTask(top) }.getOrNull() ?: p.packageName
                    val profile = inProfileOf(pkg, runCatching { shell.userOfTask(top) }.getOrDefault(-1))
                    pendingReport = Spill(OpenedElsewhere(pkg, appLabel(pkg), pulledBack = true, inProfile = profile), null)
                    return@withContext PickResult.Settled
                }
                if (SystemClock.uptimeMillis() >= deadline) break
                delay(POLL_MS)
            }
            pendingReport = Spill(OpenedElsewhere(p.packageName, p.app, pulledBack = false, picker = true, closed = true), null)
            PickResult.Settled
        } finally {
            _userPick.value = null
        }
    }

    /** The user clicks Continue after processing ([PickResult.NeedsUser]): if it is still on the home screen, move it back to Mina's screen and continue, if it is already closed, just tell the truth. */
    suspend fun afterUserStep(r: PickResult.NeedsUser) = withContext(Dispatchers.IO) {
        val still = runCatching { shell.displayOfTask(r.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY
        val moved = if (still) pullBack(MainScreenSpill.Arrival(r.taskId, r.packageName, r.userId, 0, Display.DEFAULT_DISPLAY, false, true, Display.DEFAULT_DISPLAY), r.packageName, byUser = true) else null
        pendingReport = moved ?: Spill(OpenedElsewhere(r.packageName, r.app, pulledBack = false, byUser = true), null)
    }

    /**
     * The window of this App on the home screen requires the user to handle it manually: there is a
     * password box, or when the home screen cannot be read and the system takes a screenshot of the home
     * screen, the system reports a screenshot window (the captured screen is not retained and is
     * immediately thrown away). Just look to see if it is there, not what is written in it.
     */
    private suspend fun needsUser(pkg: String): Boolean {
        // What is read is the window on the user's home screen, and the application access boundary is
        // not visible: disabled ones are not read.
        appAccess.current().denial(pkg)?.let { throw it }
        // It will take a while to read the window that just came up: draw a conclusion when you can read
        // the content (or see the password box). If you still can't read it by the time limit, check
        // whether it is anti-screenshot.
        val deadline = SystemClock.uptimeMillis() + PICK_READ_MS
        while (true) {
            val mine = ScreenReader.contentWindows(a11y.windowsOn(Display.DEFAULT_DISPLAY)).filter { it.root?.packageName?.toString() == pkg }
            val r = mine.takeIf { it.isNotEmpty() }?.let { ScreenReader.read(it, display.width, display.height) }
            val readable = r?.entries?.any { it.element.meaningful } == true
            if (r?.passwordField == true || readable) return MainScreenSpill.needsUser(r?.passwordField == true, readable, secureWindow = false)
            if (SystemClock.uptimeMillis() >= deadline) {
                val shot = runCatching { a11y.takeMainScreenshot() }.getOrNull()
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = shot is ScreenAccessibility.MainShot.Secure)
            }
            delay(POLL_MS * 2)
        }
    }

    /**
     * Before the action, the topmost task on Mina's screen was the task that was moved back (or opened
     * from it). After the action, the topmost task was changed to another task: this one was also opened
     * on that road, and is included in the calculation. If there are two copies of the app installed,
     * which one should be opened?
     */
    private suspend fun adoptFromAdopted(w: Watch?): String? {
        if (w == null || w.agentTopBefore !in adopted) return null
        val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (top < 0 || top == w.agentTopBefore || top in adopted || top == _target.value?.taskId) return null
        adopted += top
        val pkg = runCatching { shell.packageOfTask(top) }.getOrNull() ?: return null
        return inProfileOf(pkg, runCatching { shell.userOfTask(top) }.getOrDefault(-1))
    }

    /** Which version of the app is opened when two copies are installed: the one used by the same user as Mina is the original one; if only one copy is installed or cannot be seen, it is null. */
    private suspend fun inProfileOf(pkg: String, userId: Int): String? {
        if (userId < 0 || pkg !in otherProfiles()) return null
        return if (userId == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE
    }

    private fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
        val tasks = b.getIntArray(MainScreenWatch.KEY_TASK) ?: return emptyList()
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val users = b.getIntArray(MainScreenWatch.KEY_USER)
        val at = b.getLongArray(MainScreenWatch.KEY_AT)
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)
        val activity = b.getStringArray(MainScreenWatch.KEY_ACTIVITY)
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)
        if (packages == null || users == null || at == null || from == null || same == null || standard == null || now == null) return emptyList()
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i], activity?.getOrNull(i))
        }
    }

    private fun hintLateSpill(o: OpenedElsewhere): String {
        val name = "${o.app ?: o.packageName} (${o.packageName})"
        val copy = when (o.inProfile) {
            OpenedElsewhere.OTHER_PROFILE -> " It is the other copy of the app (clone or other profile): if the user did not ask for that copy, go back and pick the other option."
            OpenedElsewhere.MAIN_PROFILE -> " It is the main copy of the app."
            else -> ""
        }
        return when {
            o.closed -> "The user closed the system's question on their screen without picking, so nothing was opened. Decide what to do next; do not simply repeat the action."
            o.byUser && !o.pulledBack -> "$name needed the user; they handled it on their own screen and tapped continue, and it has closed. Observe before acting."
            o.byUser -> "$name needed the user; they handled it on their own screen and tapped continue. It is now in front on Harness Agent's screen. Observe before acting."
            o.needsUser && o.onUserScreen -> "It was $name: a popup on the user's screen that needs them (a sign-in, verification or confirmation step). " +
                "It was left there on purpose; do not repeat the action. Call hand_to_user so they can finish it, then observe before acting."
            o.pulledBack -> "It was $name, now in front on Harness Agent's screen.$copy Observe before acting."
            else -> "It was $name, which the user is using on their own screen, so it was left there. " +
                "Do not repeat the previous action: call hand_to_user with reason other so the user can do that step there, or wait and try again later."
        }
    }

    /** Compare fingerprints after the interface is stable (see [ChangeWait] for rules); return true immediately if confirmed. */
    private suspend fun awaitChange(clock: EventClock, beforeTree: Long, beforeFrame: IntArray?): Boolean =
        ChangeWait.awaitStableChange(
            now = SystemClock::uptimeMillis,
            sleep = { delay(it) },
            frame = ::frameSignature,
            quietForMs = clock::quietForMs,
            treeChanged = { treeHash() != beforeTree },
            beforeFrame = beforeFrame,
        )

    /** When the foreground is not the target App, nor the system page or system selector it calls up, the current foreground package name and whether it is opened by the target App are returned. */
    private fun switchedTo(): AppSwitch.Switch? {
        val target = _target.value
        return AppSwitch.switchedTo(
            targetPackage,
            ScreenReader.topPackage(windows()),
            // What is moved back from the user's home screen (and what is opened from them) is also the
            // target app that is opened this way.
            sameTask = {
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                top >= 0 && (target != null && target.taskId == top || top in adopted)
            },
            partOfTask = chooserPackage?.let { systemPickers + it } ?: systemPickers,
            settingsPage = { it == settingsPackage },
            // The same App is installed twice: the top one is replaced by the one from another user (not
            // the target task, nor the one that was moved back). It is also considered to be cut away.
            // The task is only asked when the target is another copy, or the App has listed another copy
            // (an App with only one copy installed is not asked more than twice for each action)
            otherCopy = {
                target != null && target.userId >= 0 && (target.userId != myUser || target.packageName in copiedPackages) && run {
                    val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                    top >= 0 && top != target.taskId && top !in adopted && userOf(top).let { it >= 0 && it != target.userId }
                }
            },
        )
    }

    /**
     * When opening, the target App has not yet reached the foreground (the system first asks which one
     * to open, and the startup is slow), and then it reaches the top: write down its task number, so
     * that the task service can mute it accordingly, and keep an eye on whether it has been moved.
     */
    private suspend fun adoptTargetTask(): String? {
        val t = _target.value ?: return null
        if (t.taskId >= 0 || foregroundTaskPackage() != t.packageName) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        val user = userOf(task)
        _target.value = TargetApp(t.packageName, task, user)
        // The system asked which one to open (two copies were installed): which one was reported to be
        // opened?
        return inProfileOf(t.packageName, user)
    }

    /** Bring the action result of "whether it was cut away"; [spill] is the App that this action opens on the user's home screen, [inProfile] see [ActionResult.Ok.inProfile]. */
    private suspend fun ok(
        changed: Boolean? = null, relocatedTo: String? = null, verification: Verification? = null,
        hint: String? = null, secret: Boolean = false, spill: Spill? = null, inProfile: String? = null,
    ): ActionResult.Ok {
        val adopted = adoptTargetTask()
        val shown = shownCopy()
        val profile = adopted ?: inProfile ?: shown?.first
        val sw = switchedTo()
        return ActionResult.Ok(
            changed = changed, appSwitched = sw?.packageName, switchedByTarget = sw?.byTarget == true,
            relocated = relocatedTo != null, relocatedTo = relocatedTo, verification = verification, hint = hint, secret = secret,
            chooser = spill?.chooser, opened = spill?.opened, inProfile = profile,
            profileUser = shown?.second, requestedUser = requestedUser.takeIf { shown != null },
        )
    }

    /** The user wants the copy when opening the App (another copy); the user wants the original copy, or it is null if the App is not opened this time. */
    @Volatile private var requestedUser: Int? = null

    /**
     * When the target App is installed in two copies (the other one is listed, the other one is
     * requested, or the other one is opened), which one is on the Mina screen at this moment:
     * (inProfile, owned by the user). If only one copy is installed, if it is not at the top or cannot
     * be read, it will be null; if only one copy of the App is installed, no further questions will be
     * asked.
     */
    private fun shownCopy(): Pair<String, Int>? {
        val t = _target.value ?: return null
        if (requestedUser == null && t.packageName !in copiedPackages && (t.userId < 0 || t.userId == myUser)) return null
        if (foregroundTaskPackage() != t.packageName) return null
        val user = userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).takeIf { it >= 0 } ?: return null
        return (if (user == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE) to user
    }

    // ---------- Number analysis ----------

    /**
     * Parse the number into an operable node; the second value is the current name of the retrieved
     * element after relocation, otherwise it is null. If the node in the latest snapshot is still there
     * and has not changed, use it directly; if the snapshot expires or the node changes, find the unique
     * corresponding one in the current interface based on the characteristics of the original element.
     */
    private fun resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
        val (entry, snapshot) = when (val l = ids.lookup(ref, snapshotId)) {
            is NodeIds.Lookup.Unknown -> return null
            is NodeIds.Lookup.Current -> {
                if (unchanged(l.entry)) return l.entry.node to null
                l.entry to l.snapshot
            }
            is NodeIds.Lookup.Old -> l.entry to l.snapshot
        }
        val now = read()
        return when (val r = relocate(entry.locator, snapshot.locators, now.entries.map { it.locator }, min(display.width, display.height))) {
            is Relocation.Found -> now.entries[r.index].let { it.node to it.element.name }
            else -> null
        }
    }

    /** The node is still there, and the type, name, resource ID, and location are the same as in the snapshot (the same node may have changed content after the list is reused). */
    private fun unchanged(entry: NodeEntry): Boolean {
        val n = entry.node
        if (!n.refresh()) return false
        val now = ScreenReader.locatorOf(n, display.width, display.height) ?: return false
        return now == entry.locator.copy(context = null)
    }

    private sealed interface Field {
        /** [tapped]: The given element itself is not an input box. The real input box appears (or is focused) only after clicking it. */
        class Found(val node: AccessibilityNodeInfo, val tapped: Boolean = false, val relocatedTo: String? = null) : Field
        class Missing(val reason: String) : Field
    }

    /**
     * Find the box to enter. Given the number: neither the element itself nor the input box inside it,
     * but it looks like an input entrance (the "fake search box" on the homepage: clickable, not a
     * button, with a prompt or a name like search), click it first, and only use that box if the click
     * succeeds and an input box with focus appears that was not there before. Not numbered: The box
     * currently holding input focus.
     */
    private suspend fun findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
        if (ref != null && snapshotId != null) {
            val (node, relocatedTo) = resolve(ref, snapshotId) ?: return Field.Missing("ref_expired")
            val editable = if (node.isEditable) node else descendant(node) { it.isEditable }
            if (editable != null) return Field.Found(editable, relocatedTo = relocatedTo)
            if (!tapToFocus || !looksLikeInputEntry(node)) return Field.Missing("not_editable")
            val focusedBefore = focusedEditable()
            val clicked = withEventClock { clock ->
                clock.mark()
                click(node).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
            }
            val now = focusedEditable()
            return if (clicked && now != null && now != focusedBefore) Field.Found(now, tapped = true, relocatedTo = relocatedTo) else Field.Missing("not_editable")
        }
        return focusedEditable()?.let { Field.Found(it) } ?: Field.Missing(NO_FOCUS)
    }

    /** Characteristics used to identify "the same input box": type, resource ID, location size. */
    private data class FieldShape(val cls: String?, val rid: String?, val rect: android.graphics.Rect) {
        companion object {
            fun of(n: AccessibilityNodeInfo) =
                FieldShape(n.className?.toString(), n.viewIdResourceName, android.graphics.Rect().also { n.getBoundsInScreen(it) })
        }
    }

    private fun sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
        val b = FieldShape.of(n)
        if (a.cls != b.cls || a.rid != b.rid) return false
        // The sizes should be almost the same; the position allows the entire page to move a little (the
        // page often moves up when typing), and the same upper limit is used for number retrieval.
        val short = min(display.width, display.height)
        val sizeTol = short * SAME_FIELD_SIZE
        val moveTol = short * RELOCATE_MAX_SHIFT
        return kotlin.math.abs(a.rect.width() - b.rect.width()) <= sizeTol && kotlin.math.abs(a.rect.height() - b.rect.height()) <= sizeTol &&
            kotlin.math.hypot((a.rect.centerX() - b.rect.centerX()).toDouble(), (a.rect.centerY() - b.rect.centerY()).toDouble()) <= moveTol
    }

    /** Image input entrance: clickable, not button type, and with prompts or names like search. */
    private fun looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(n) { it.isClickable } ?: return false
        val cls = target.className?.toString().orEmpty()
        if (cls.endsWith("Button")) return false
        if (ScreenReader.placeholderOf(n) != null || !n.hintText.isNullOrBlank()) return true
        val name = ScreenReader.ownLabel(n) ?: ScreenReader.ownLabel(target) ?: return false
        return SEARCH_WORDS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * There is something on this screen holding the input focus (even if it is not the input box in the
     * tree, such as the entire interface drawn by the App itself holding the focus), or the input method
     * is playing. When there are none, there is no box to press the button: the input box that cannot be
     * seen in the tree can only be received and pasted with the focus.
     */
    private fun somethingHoldsInputFocus(): Boolean =
        imeBox() != null || ScreenReader.contentWindows(windows()).any { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null }

    /** This screen holds the editable box with input focus; when the focus falls on a container (such as a web page), look for the focused editable node inside it. */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        for (w in ScreenReader.contentWindows(windows())) {
            val focused = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (focused.isEditable) return focused
            descendant(focused) { it.isFocused && it.isEditable }?.let { return it }
        }
        return null
    }

    // ---------- Low-level operations ----------

    /** Click on the node (or the nearest pointable ancestor) first; if not, gesture at its center, and finally inject touch. */
    private suspend fun click(node: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(node) { it.isClickable && it.isEnabled }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

    /** Click at this point: pause for a moment, then lower your finger and hold for a random moment. Built-in assistant injects touch when accessibility gestures fail. */
    private suspend fun touch(at: Pair<Int, Int>): Boolean {
        val (x, y) = at
        delay(pacing.approach())
        val press = pacing.press()
        if (a11y.gesture(displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, press)) return true
        if (pacing.paced && injectStroke(listOf(GesturePacing.Point(x.toDouble(), y.toDouble(), 0), GesturePacing.Point(x.toDouble(), y.toDouble(), press)))) return true
        return shell.injectTap(displayId, x, y)
    }

    /**
     * Slide a finger along [points]. When there is a rhythm, press [GesturePacing.stroke] to arrange the
     * time of each point, and the built-in assistant will inject it point by point (the App will see the
     * real speed of first fast and then slow); when the assistant cannot notice, return to the
     * barrier-free gesture and walk along the same arc (the system will follow the path at a constant
     * speed, only the speed is missing). Without rhythm, it is the same as before: the barrier-free
     * gesture moves at a constant speed for a given duration.
     */
    private suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
        if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
        delay(pacing.approach())
        val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, display.width to display.height)
        if (injectStroke(stroke.points)) return true
        return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
    }

    private fun injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
        shell.injectStroke(
            displayId,
            IntArray(points.size) { points[it].x.roundToInt() },
            IntArray(points.size) { points[it].y.roundToInt() },
            LongArray(points.size) { points[it].t },
        )
    }.getOrDefault(false)

    private fun pathOf(points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(points.first().first.toFloat(), points.first().second.toFloat())
        points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
    }

    /**
     * The editing area (contenteditable) in the web page only accepts actual input: SET_TEXT only
     * changes the words on the web page. The editor cannot receive the input event, and the App will
     * treat it as no input ("Complete" will not light up, exit without saving). For this type of input
     * box, select the original content and then paste it or press the delete key.
     */
    private fun inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

    /**
     * Select the original content and paste it (or press the delete key to clear it). Returning null
     * means that the clipboard cannot be used safely (the user's original content cannot be read, etc.)
     * and nothing has been changed. The clipboard is Mina's only during this pasting period: the app
     * will restore it after reading (the words have changed) or timeout.
     */
    private fun inputText(node: AccessibilityNodeInfo, text: String): Boolean? {
        node.refresh()
        val before = node.text?.toString().orEmpty()
        fun selectAll() {
            if (before.isEmpty()) return
            val range = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
        }
        if (text.isEmpty()) {
            selectAll()
            return before.isEmpty() || shell.injectKey(displayId, KeyEvent.KEYCODE_DEL)
        }
        clipLock.lock()
        clipHeld(true)
        try {
            if (runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED) != ShellService.CLIP_SET) return null
            runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
            selectAll()
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
            // The app reads the clipboard asynchronously: wait for the word to change and then restore
            // it. Wait a while at most.
            val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (node.refresh() && node.text?.toString().orEmpty() != before) break
                Thread.sleep(POLL_MS)
            }
            return true
        } finally {
            runCatching { shell.restoreClip() }
            clipHeld(false)
            clipLock.unlock()
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private inline fun ancestorOrSelf(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= MAX_ANCESTORS) {
            if (test(n)) return n
            n = n.parent
            depth++
        }
        return null
    }

    private fun descendant(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val n = queue.removeFirst()
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    // ---------- Screen ----------

    /** The coarse-grained brightness grid of the preview frame; null if there is no frame. */
    private fun frameSignature(): IntArray? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.lumaGrid(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    /** Whether the screen is completely black (the anti-screenshot window is drawn black on the virtual screen); if there is no screen, it is null. */
    private fun frameBlack(): Boolean? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.isBlack(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    // ---------- Event clock ----------

    /** Record the time of the latest interface event on this screen. */
    private class EventClock {
        private val last = AtomicLong(SystemClock.uptimeMillis())
        fun mark() = last.set(SystemClock.uptimeMillis())
        fun quietForMs() = SystemClock.uptimeMillis() - last.get()

        /** Wait until there is no interface event in consecutive [quietMs]; wait until it returns true in [capMs]. */
        suspend fun awaitQuiet(quietMs: Long, capMs: Long): Boolean {
            val deadline = SystemClock.uptimeMillis() + capMs.coerceAtLeast(0)
            while (true) {
                val now = SystemClock.uptimeMillis()
                val quietFor = now - last.get()
                if (quietFor >= quietMs) return true
                if (now >= deadline) return false
                delay(min(quietMs - quietFor, deadline - now).coerceIn(10, 100))
            }
        }
    }

    /** Execute [block] during the subscription to the screen accessibility events; the subscription is established before the block, so the first batch of events caused by the action will not be missed. */
    private suspend fun <T> withEventClock(block: suspend (EventClock) -> T): T = coroutineScope {
        val clock = EventClock()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            a11y.eventsFor(displayId).collect { if (it.eventType and IGNORED_EVENTS == 0) clock.mark() }
        }
        try {
            block(clock)
        } finally {
            job.cancel()
        }
    }

    private companion object {
        const val TAG = "MinaScreen"
        /** The tasks that appear on the home screen within a certain period of time after the action starts are considered to be initiated by this action. */
        const val SPILL_WINDOW_MS = 6_000L
        /**
         * The width and height of the window must be at least as much as the main screen to be
         * considered a full-screen page; smaller than this are dialog boxes and bottom pop-ups. Keep it
         * wider: the status bar, navigation bar, and hole-digging will make the full-screen App window
         * smaller than the screen. The navigation bar is still on the side when the screen is
         * horizontal.
         */
        const val FULL_SCREEN_SHARE = 0.8f
        /** The window that just popped up may still be in the opening animation: wait this long and look at it again to confirm whether it is not the entire screen. */
        const val POPUP_SETTLE_MS = 300L
        const val MAX_USER_TAPS = 32
        /** After the user makes a selection in the selection box on the home screen, how long should the user wait for the selected app to come up? How often should the user wait for the selected app to be viewed. */
        const val PICK_WAIT_MS = 3_000L
        const val PICK_POLL_MS = 300L
        /** The items that pop up before the selection box is closed are also considered selected (you need to check whether the selection box is closed or not after a while). */
        const val PICK_SLACK_MS = 1_000L
        /** After the selected App is uploaded, you can wait at least this long to read its window and see if the user needs to handle it personally. */
        const val PICK_READ_MS = 2_000L
        val NO_APP_OPEN = ActionResult.Failed("no_app_open")
        const val NO_FOCUS = "no_focus"
        const val PER_USER_RANGE = 100_000
        /** User where Mina is: The original app is here. */
        val myUser: Int get() = AppCopyReader.myUser
        const val COPY_NOT_FOUND = "copy_not_found"
        const val COPY_NOT_OPENED = "copy_not_opened"
        const val HINT_PASTE_BLOCKED = "The text could not be entered: the field is not in the element list, so it can only be pasted, " +
            "and the clipboard could not be used safely right now. Call hand_to_user so the user can type it."
        val IN_USE_BY_OTHER = ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK)

        const val QUIET_MS = 500L

        /** The piece of scrollable content gently scrolling in Stay takes up at least this much of the screen. */
        const val IDLE_MIN_AREA = 0.5

        /** Stay and roll gently to at least this many pixels (any less you won’t be able to see it). */
        const val IDLE_MIN_PX = 8

        /** The duration of rolling gently while staying: slow enough, drag slowly, stop and then raise your hand. */
        const val IDLE_SCROLL_MS = 900L
        const val CHECK_CAP_MS = 1_500L
        const val SECOND_LOOK_QUIET_MS = 300L
        const val SECOND_LOOK_CAP_MS = 800L
        const val SETTLE_CAP_MS = 2_000L
        const val READBACK_QUIET_MS = 200L
        const val READBACK_CAP_MS = 800L
        const val LAUNCH_WAIT_MS = 5_000L
        const val IN_USE_RETRY_MS = 3_000L
        const val IN_USE_POLL_MS = 500L
        const val POLL_MS = 100L
        /** After setting the alarm clock, wait for the next alarm clock of the system to change, and you can only watch it once in this long time. */
        const val ALARM_WAIT_MS = 3_000L
        const val ALARM_POLL_MS = 150L
        const val SYSTEM_ACTION_REQUEST = 0x5A
        /** The duration of dragging the roller: no shorter than the slow drag duration in the rhythm, and it has stopped when you let go. */
        const val WHEEL_DRAG_MS = 1_000L
        /** The duration of a quick swipe when slow dragging stops (like a human hand swiping a scroll wheel). */
        const val WHEEL_SWIPE_MS = 400L
        const val TYPE_ATTEMPTS = 2
        const val WEB_VIEW = "android.webkit.WebView"
        const val PASTE_WAIT_MS = 1_500L
        const val HINT_PASTE_UNAVAILABLE = "This field only receives real input (it needs a paste), but the clipboard could not be used safely, " +
            "so the text may only be displayed and the app may not have received it. Check that the app reacted (e.g. its save button became enabled) " +
            "before relying on it; if it did not, call hand_to_user so the user can type it."
        const val HINT_TAPPED_TO_FOCUS = "The element you gave is not a text field itself, so it was tapped first and the text went into the field that got focus after the tap (often a separate search page). Check which field holds your text before submitting."
        const val FOCUS_WAIT_MS = 2_000L
        const val SAME_FIELD_SIZE = 0.03
        val SEARCH_WORDS = listOf("搜索", "搜一搜", "查找", "search")
        const val HINT_READBACK_FROZEN = "The field keeps reporting the same text, so what was typed could not be read back; " +
            "but new text containing it appeared on the screen after typing (e.g. suggestions), so the app seems to have received it. Observe to check before submitting."
        const val HINT_SHARE_PICKER = "The system is asking which of the app's share screens to use (the screen lists them). " +
            "Tap the one the task needs (for a message, the one that sends to a contact or chat); do not share again."
        const val HINT_COLD_IGNORED = "coldIgnored: app has a task outside Harness Agent's screen, so it was not restarted (that would wipe the user's state there). It was opened as-is; navigate back to its start screen yourself if needed."
        const val MAX_ANCESTORS = 8

        // Hover, touch exploration, gesture recognition, and accessibility focus are not changes in
        // interface content.
        const val IGNORED_EVENTS = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
    }
}

/** Apps that can be opened on the desktop (package name, display name), excluding Mina itself; only use the package manager, without built-in assistants and accessibility. */
fun launchableApps(pm: android.content.pm.PackageManager, ownPackage: String): List<Pair<String, String>> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ownPackage }
        .distinctBy { it.first }
        .sortedBy { it.second }
}

