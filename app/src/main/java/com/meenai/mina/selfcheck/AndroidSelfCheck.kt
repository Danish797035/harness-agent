package com.meenai.mina.selfcheck

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.meenai.mina.BuildConfig
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.a11y.AndroidScreen
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.cap.phone.a11y.SystemPickers
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.cap.phone.display.FrameMath
import com.meenai.mina.cap.phone.display.LazyAgentDisplay
import com.meenai.mina.cap.phone.setup.DeveloperSettings
import com.meenai.mina.cap.phone.shell.ShellBinding
import com.meenai.mina.cap.phone.shell.ShellService
import com.meenai.mina.cap.sandbox.AndroidSandbox
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.cap.web.WebHost
import com.meenai.mina.cap.web.WebScreen
import com.meenai.mina.cap.web.WebTabPool
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.selfcheck.Advice
import com.meenai.mina.core.selfcheck.CheckOutcome
import com.meenai.mina.core.selfcheck.DeviceFacts
import com.meenai.mina.core.selfcheck.SelfCheck
import com.meenai.mina.core.selfcheck.SelfCheckPort
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.PhoneSetupFlow
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.shell.IHarnessShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android implementation of compatibility self-test. The mobile phone uses the same set of tasks: the
 * shared assistant connection ([PhoneServices.sharedShell]) in the process, [AgentDisplay] to create a
 * screen, [AndroidScreen] to open the page, read the screen, click, type, scroll, and return; the web
 * page uses the built-in browser [WebScreen]; the sandbox uses [AndroidSandbox]. Only touch Mina's own
 * self-test page and system settings (only open, do not click), do not change settings, and do not
 * connect to the Internet. The facts in the report only include status and numbers, and do not include
 * package names, account numbers, or user information of other apps. [status] is the current system
 * status on the interface (accessibility, notifications, built-in assistants).
 */
class AndroidSelfCheck(context: Context, private val status: () -> SystemStatus) : SelfCheckPort {
    private val app = context.applicationContext
    private val phone = PhoneServices.of(app)

    @Volatile private var connection: ShellBinding? = null
    @Volatile private var shell: IHarnessShell? = null
    @Volatile private var display: AgentDisplay? = null
    @Volatile private var screen: AndroidScreen? = null
    @Volatile private var web: Web? = null

    /** Look at the top task on the main screen when you start the run; look at it again every time you reach a node, and note down the changed nodes. */
    private var mainBefore: Int? = null
    private val mainChanged = mutableListOf<String>()

    private class Web(val host: WebHost, val pool: WebTabPool, val screen: WebScreen) {
        fun close() {
            runCatching { pool.closeAll(TASK) }
            runCatching { pool.close() }
            runCatching { host.close() }
        }
    }

    override suspend fun facts(): DeviceFacts = withContext(Dispatchers.IO) {
        val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val m = metrics()
        val st = status()
        val devPageMissing = DeveloperSettings.developerOptionsPageMissing(app.packageManager)
        val wirelessMissing = DeveloperSettings.wirelessDebuggingMissing(app.contentResolver)
        DeviceFacts(
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
            mem.totalMem, m.widthPixels, m.heightPixels,
            wirelessDebugging = !wirelessMissing,
            appVersion = BuildConfig.VERSION_NAME, appBuild = BuildConfig.GIT_HASH,
            developerOptionsPage = !devPageMissing,
            phoneControlUnavailable = PhoneSetupFlow.unavailable(devPageMissing, wirelessMissing, st.builtinShell, st.helperRestart),
        )
    }

    override suspend fun check(check: SelfCheck): CheckOutcome = when (check) {
        SelfCheck.DEVICE -> error("judged from facts()")
        SelfCheck.PERMISSIONS -> permissions()
        SelfCheck.HELPER -> helper()
        SelfCheck.VIRTUAL_DISPLAY -> virtualDisplay()
        SelfCheck.APP_LAUNCH -> appLaunch()
        SelfCheck.SCREEN_READING -> screenReading()
        SelfCheck.ACTIONS -> actions()
        SelfCheck.STAYS_ALIVE -> staysAlive()
        SelfCheck.MAIN_SCREEN -> mainScreen()
        SelfCheck.BROWSER -> browser()
        SelfCheck.SANDBOX -> sandbox()
        SelfCheck.APP_COPIES -> appCopies()
    }

    // ---------- 2 Permissions ----------

    private fun permissions(): CheckOutcome {
        val s = status()
        val battery = runCatching { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) }.getOrNull()
        val detail = "accessibility " + when (s.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        } + ", notifications " + (if (s.notificationsAllowed) "allowed" else "blocked") +
            ", battery " + when (battery) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" }
        return when {
            s.accessibility == AccessibilityState.Off -> CheckOutcome.fail(detail, Advice.TurnOnAccessibility)
            s.accessibility == AccessibilityState.EnabledNotRunning -> CheckOutcome.fail(detail, Advice.RestartAccessibility)
            !s.notificationsAllowed -> CheckOutcome.partial(detail, Advice.AllowNotifications)
            battery == false -> CheckOutcome.partial(detail, Advice.AllowBattery)
            else -> CheckOutcome.pass(detail)
        }
    }

    // ---------- 3 Built-in Assistant ----------

    private suspend fun helper(): CheckOutcome {
        val st = status().builtinShell
        // If it has not been paired, do not connect (you will be prompted to set it up when connecting);
        // if it has been paired, the watchdog may be picking it up, please wait for a while.
        if (st == BuiltinShellStatus.NotPaired && !phone.shellControl.paired) return CheckOutcome.fail("not set up ($st)", Advice.SetUpPhoneControl)
        val conn = connection ?: phone.sharedShell.acquire().also { connection = it }
        val sh = withTimeoutOrNull(HELPER_WAIT_MS) { conn.shell.filterNotNull().first() }
            ?: return CheckOutcome.fail("not running (${phone.shellControl.status.value})", Advice.SetUpPhoneControl)
        shell = sh
        return withContext(Dispatchers.IO) {
            val uid = sh.identity().getOrNull(0) ?: -1
            val path = when {
                uid == ROOT_UID -> "root"
                phone.shellControl.paired -> "paired wireless debugging"
                else -> "started from a computer (USB)"
            }
            val who = when (uid) { SHELL_UID -> "shell"; ROOT_UID -> "root"; else -> "uid $uid" }
            val times = List(LATENCY_CALLS) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                sh.identity()
                (SystemClock.elapsedRealtimeNanos() - t0) / 1_000
            }.sorted()
            mainBefore = mainTop(sh)
            CheckOutcome.pass("running as $who, $path, round trip ${format(times[times.size / 2] / 1000.0)} ms")
        }
    }

    // ---------- 4 Virtual Screen ----------

    private suspend fun virtualDisplay(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val m = metrics()
        // Do not interrupt the screen building process: be sure to write down the completed screen and
        // release it at the end.
        val made = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { AgentDisplay(sh, m.widthPixels, m.heightPixels, m.densityDpi) }.onSuccess { display = it }
        }
        val d = made.getOrElse { return CheckOutcome.fail("could not be created (${it.javaClass.simpleName})", Advice.DisplayFailed) }
        val flags = runCatching { app.getSystemService(DisplayManager::class.java).getDisplay(d.displayId)?.flags }.getOrNull()
        val own = flags?.let { it and ownFocusFlag() != 0 }
        val detail = "created ${d.width}x${d.height}, own focus " + when (own) { true -> "yes"; false -> "no"; null -> "unknown" }
        return when {
            Build.VERSION.SDK_INT < DeviceFacts.OWN_FOCUS_SDK -> CheckOutcome.partial("$detail (own focus needs Android 14)", Advice.FocusShared)
            own == false -> CheckOutcome.partial(detail, Advice.FocusShared)
            else -> CheckOutcome.pass(detail)
        }
    }

    /** The "own focus" sign displayed since Android 14; the constant is not public, so you can first get it by name, and you can't get the original value. */
    private fun ownFocusFlag(): Int = runCatching { Display::class.java.getField("FLAG_OWN_FOCUS").getInt(null) }.getOrDefault(1 shl 11)

    // ---------- 5 Open the page ----------

    private suspend fun appLaunch(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        val a11y = ScreenAccessibility.instance.value ?: return CheckOutcome.fail("accessibility service not connected", Advice.RestartAccessibility)
        val s = screen ?: AndroidScreen(a11y, sh, LazyAgentDisplay(d.width, d.height) { d }.also { it.get() }).also { screen = it }
        val own = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (own !is ActionResult.Ok || topPackage(sh, d.displayId) != app.packageName) {
            return CheckOutcome.fail("Mina's test page did not open on Mina's display (${reason(own)})", Advice.LaunchFailed)
        }
        sampleMain(sh, "test page")
        // First close the self-test page and then open the system settings: then open a page from the
        // beginning instead of turning the old page back to the front.
        withContext(Dispatchers.Main) { SelfCheckTargetActivity.finishOpen() }
        val settings = SystemPickers.settingsPackage(app.packageManager)
        val settingsNote = if (settings == null) "no system Settings app found" else when (val r = s.openApp(settings, cold = false)) {
            is ActionResult.Failed -> if (r.reason == "app_in_use_by_user") SETTINGS_IN_USE else "Settings did not open (${r.reason})"
            is ActionResult.Ok -> {
                val onMina = withContext(Dispatchers.IO) { topPackage(sh, d.displayId) } == settings
                val onMain = withContext(Dispatchers.IO) { mainTop(sh)?.let { runCatching { sh.packageOfTask(it) }.getOrNull() } } == settings
                sampleMain(sh, "Settings")
                // Just watch it: go back once and leave it
                s.back()
                when {
                    onMain -> SETTINGS_ON_MAIN
                    !onMina -> "Settings did not come to the front of Mina's display"
                    else -> null
                }
            }
        }
        // The next few steps are to be done on the self-test page: reopen it once and start from the
        // beginning.
        val again = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (again !is ActionResult.Ok || !poll { withContext(Dispatchers.IO) { isTestPageOnTop(sh, d.displayId) } }) {
            return CheckOutcome.fail("Mina's test page did not reopen after Settings (${reason(again)})", Advice.LaunchFailed)
        }
        return when (settingsNote) {
            null -> CheckOutcome.pass("Mina's test page and Settings opened on Mina's display")
            SETTINGS_ON_MAIN -> CheckOutcome.fail("test page ok; $settingsNote", Advice.OpenedOnMainScreen)
            SETTINGS_IN_USE -> CheckOutcome.partial("test page ok; $settingsNote", Advice.SettingsInUse)
            else -> CheckOutcome.partial("test page ok; $settingsNote", Advice.LaunchFailed)
        }
    }

    // ---------- 6 Screen Reading ----------

    private suspend fun screenReading(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        s.waitStable(SETTLE_MS)
        // When the page is first opened, the tree may not be complete yet: wait until Mina is at the top
        // and all the required controls are there. Wait a while at most.
        var snap = s.snapshot()
        poll(PAGE_WAIT_MS) {
            snap = s.snapshot()
            snap.packageName == app.packageName && EXPECTED.all { snap.find(it) != null }
        }
        val missing = EXPECTED.filter { snap.find(it) == null }
        if (snap.packageName != app.packageName || missing.isNotEmpty()) {
            return CheckOutcome.fail("missing ${missing.joinToString().ifEmpty { "none" }}; ${seen(snap)}", Advice.ReadFailed)
        }
        val frame = awaitFrame(d) ?: return CheckOutcome.fail("tree ok; no screenshot from the display", Advice.BlankFrames)
        val blank = try {
            val grid = FrameMath.lumaGrid(frame.width, frame.height, frame::getPixel)
            FrameMath.isBlack(frame.width, frame.height, frame::getPixel) || grid.max() - grid.min() < BLANK_SPREAD
        } finally {
            frame.recycle()
        }
        if (blank) return CheckOutcome.fail("tree ok; screenshot is blank or black", Advice.BlankFrames)
        return CheckOutcome.pass("${snap.elements.size} elements, all expected found; screenshot not blank")
    }

    // ---------- 7 Actions ----------

    private suspend fun actions(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        fun mark(name: String, passed: Boolean) { if (passed) ok += name else bad += name }

        // Click: count plus one
        var snap = s.snapshot()
        val before = snap.find(COUNT)?.number()
        val button = snap.find(BUTTON)
        if (button != null && before != null) {
            s.tapRef(button.ref, snap.snapshotId)
            mark("tap", poll { s.peek().find(COUNT)?.number() == before + 1 })
        } else mark("tap", false)

        // Typing: Type once each in English and Chinese, read back the input box
        snap = s.snapshot()
        mark("type ASCII", snap.find(INPUT)?.let { typed(s, it, snap, ASCII) } == true)
        snap = s.snapshot()
        mark("type Chinese", snap.find(INPUT)?.let { typed(s, it, snap, CHINESE) } == true)

        // Scrolling: Visible rows changed
        snap = s.snapshot()
        val rowsBefore = snap.rows()
        val list = snap.find(LIST)
        if (list != null && rowsBefore.isNotEmpty()) {
            s.scroll(list.ref, snap.snapshotId, down = true)
            mark("scroll", poll { s.peek().rows().let { it.isNotEmpty() && it.first() != rowsBefore.first() } })
        } else mark("scroll", false)

        // Return: The self-test page leaves the top of the Mina screen. Most of the time after typing,
        // the input method is still playing. Those few clicks are to close the input method (this is the
        // original case of Android) and are not counted in the number of times.
        val gone = BackPresses.until(
            tries = BACK_TRIES,
            maxPresses = BACK_MAX_PRESSES,
            keyboardUp = { withContext(Dispatchers.IO) { s.keyboardUp() } },
            press = { s.back() },
            left = { poll { withContext(Dispatchers.IO) { !isTestPageOnTop(sh, d.displayId) } } },
        )
        mark("back", gone)
        sampleMain(sh, "actions")

        val detail = (ok.map { "$it ok" } + bad.map { "$it failed" }).joinToString(", ")
        return when {
            bad.isEmpty() -> CheckOutcome.pass(detail)
            bad == listOf("type Chinese") -> CheckOutcome.partial(detail, Advice.ActionFailed)
            else -> CheckOutcome.fail(detail, Advice.ActionFailed)
        }
    }

    private suspend fun typed(s: AndroidScreen, field: UiElement, snap: ScreenSnapshot, text: String): Boolean {
        s.clearText(field.ref, snap.snapshotId)
        val fresh = s.snapshot()
        val f = fresh.find(INPUT) ?: return false
        s.typeText(f.ref, fresh.snapshotId, text)
        return poll { s.peek().find(INPUT)?.name?.let { SelfCheckText.contains(it, text) } == true }
    }

    // ---------- 8 Keep moving ----------

    private suspend fun staysAlive(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        if (s.openOwnPage(SelfCheckTargetActivity.intent(app)) !is ActionResult.Ok) {
            return CheckOutcome.fail("Mina's test page did not reopen", Advice.LaunchFailed)
        }
        val start = SystemClock.uptimeMillis()
        var lastChange = start
        var prev = frameHash(d)
        var lastTick = s.peek().find(TICK)?.number()
        var treeLostAt: Long? = null
        while (SystemClock.uptimeMillis() - start < ALIVE_MS) {
            delay(ALIVE_STEP_MS)
            val now = SystemClock.uptimeMillis()
            val h = frameHash(d)
            if (h != null && h != prev) lastChange = now
            prev = h
            if (now - lastChange > STALL_MS) {
                return CheckOutcome.fail("frames stopped after ${(lastChange - start + 500) / 1000} s", Advice.FramesStopped)
            }
            val tick = s.peek().find(TICK)?.number()
            if (tick == null || tick == lastTick) {
                if (treeLostAt == null) treeLostAt = now
            } else treeLostAt = null
            lastTick = tick
            treeLostAt?.let { lost ->
                if (now - lost > STALL_MS) return CheckOutcome.fail("tree stopped updating after ${(lost - start + 500) / 1000} s", Advice.FramesStopped)
            }
        }
        return CheckOutcome.pass("frames and tree kept changing for ${ALIVE_MS / 1000} s")
    }

    // ---------- 9 Home screen ----------

    private suspend fun mainScreen(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val before = mainBefore ?: return CheckOutcome.fail("main screen was not readable at the start", Advice.Error)
        sampleMain(sh, "end")
        val who = if (withContext(Dispatchers.IO) { runCatching { sh.packageOfTask(before) }.getOrNull() } == app.packageName) "Mina" else "another app"
        return if (mainChanged.isEmpty()) CheckOutcome.pass("top task unchanged ($who stayed on top)")
        else CheckOutcome.fail("top task changed during: ${mainChanged.joinToString()}", Advice.MainScreenChanged)
    }

    // ---------- 10 Built-in browser ----------

    private suspend fun browser(): CheckOutcome {
        if (runCatching { WebViewCompat.getCurrentWebViewPackage(app) == null }.getOrDefault(true)) return CheckOutcome.fail("no WebView on this device", Advice.NoWebView)
        val m = metrics()
        // It is built in the main thread just like the task; be sure to write it down when it is built
        // and close it at the end. Visits are not recorded in the website list (not given to the
        // registry)
        val built = withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                val host = WebHost(app, m.widthPixels, m.heightPixels, m.densityDpi)
                try {
                    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
                    val pool = WebTabPool(app, host, assetLoaders = listOf(loader))
                    Web(host, pool, WebScreen(pool, TASK, host, SystemClock::uptimeMillis)).also { web = it }
                } catch (e: Exception) {
                    host.close()
                    throw e
                }
            }
        }
        val w = built.getOrElse { return CheckOutcome.fail("browser could not start (${it.javaClass.simpleName})", Advice.WebFailed) }
        val nav = w.screen.navigate(WEB_PAGE, newTab = false)
        if (!nav.loaded) return CheckOutcome.fail("local page did not load (${nav.netError ?: "not loaded"})", Advice.WebFailed)
        val read = w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text
        if (!SelfCheckText.contains(read, WEB_MARKER)) return CheckOutcome.fail("page loaded but its text was not read", Advice.WebFailed)
        val snap = w.screen.snapshot()
        val button = snap.elements.firstOrNull { it.resourceId == WEB_BUTTON }
            ?: return CheckOutcome.fail("text read; button not found", Advice.WebFailed)
        w.screen.tapRef(button.ref, snap.snapshotId)
        val clicked = poll { SelfCheckText.contains(w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text, WEB_CLICKED) }
        return if (clicked) CheckOutcome.pass("local page loaded, text read, button tap verified")
        else CheckOutcome.fail("text read; button tap did not change the page", Advice.WebFailed)
    }

    // ---------- 11 Sandbox ----------

    private suspend fun sandbox(): CheckOutcome {
        val state = SandboxState.get(app)
        when (val st = state.status.value) {
            is SandboxStatus.Unsupported -> return CheckOutcome.skipped("not supported on ${Build.SUPPORTED_ABIS.firstOrNull() ?: "this device"}", Advice.SandboxUnsupported)
            is SandboxStatus.Ready -> Unit
            // Do not install the running environment for self-test (takes several minutes)
            else -> return CheckOutcome.partial("runtime not prepared (${st.javaClass.simpleName})", Advice.SandboxNotPrepared)
        }
        val box = AndroidSandbox(app, state)
        // If there is an error in the command (for example, if the network filter cannot be installed
        // and is rejected), record it as it is; if it is cancelled, it will be discarded as usual.
        suspend fun run(argv: List<String>): Result<CommandResult> = try {
            Result.success(box.run(SANDBOX_WORKSPACE, argv, null, COMMAND_MS) {})
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val echo = run(listOf("sh", "-c", "echo ok"))
        val echoOk = echo.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "ok" } == true
        val py = run(listOf("python3", "-c", "print(1+1)"))
        val pyOk = py.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "2" } == true
        fun what(r: Result<CommandResult>) = r.fold(
            { if (it.timedOut) "timed out" else "exit ${it.exitCode}, ${it.durationMs} ms" }, { "error ${it.javaClass.simpleName}" },
        )
        val detail = "echo ${if (echoOk) "ok" else "failed"} (${what(echo)}), python3 ${if (pyOk) "ok" else "failed"} (${what(py)})"
        return when {
            echoOk && pyOk -> CheckOutcome.pass(detail)
            echoOk -> CheckOutcome.partial(detail, Advice.SandboxFailed)
            else -> CheckOutcome.fail(detail, Advice.SandboxFailed)
        }
    }

    // ---------- 12 Another App ----------

    private suspend fun appCopies(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val users = withContext(Dispatchers.IO) { runCatching { sh.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) }.getOrNull() }
            ?: return CheckOutcome.partial("other users could not be read", Advice.Error)
        return CheckOutcome.pass("${users.size} usable other profile(s)")
    }

    // ---------- Ending ----------

    override suspend fun release() {
        withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                runCatching { SelfCheckTargetActivity.finishOpen() }
                web?.close()
                web = null
            }
            withContext(Dispatchers.IO) {
                display?.release()
                display = null
            }
            screen = null
            shell = null
            connection?.let {
                connection = null
                phone.sharedShell.release()
            }
        }
    }

    // ---------- Gadgets ----------

    private fun metrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** The topmost task on the home screen; null if not readable. */
    private fun mainTop(sh: IHarnessShell): Int? = runCatching { sh.topTaskId(Display.DEFAULT_DISPLAY) }.getOrNull()?.takeIf { it >= 0 }

    private suspend fun sampleMain(sh: IHarnessShell, stage: String) {
        val before = mainBefore ?: return
        val now = withContext(Dispatchers.IO) { mainTop(sh) }
        if (now != before && stage !in mainChanged) mainChanged += stage
    }

    private fun topPackage(sh: IHarnessShell, displayId: Int): String? = runCatching {
        sh.topTaskId(displayId).takeIf { it >= 0 }?.let(sh::packageOfTask)
    }.getOrNull()

    private fun isTestPageOnTop(sh: IHarnessShell, displayId: Int): Boolean =
        runCatching { sh.topActivity(displayId) }.getOrNull()?.contains(SelfCheckTargetActivity::class.java.simpleName) == true

    private suspend fun awaitFrame(d: AgentDisplay): Bitmap? {
        repeat(FRAME_TRIES) {
            d.latestFrameBitmap()?.let { return it }
            delay(POLL_MS)
        }
        return null
    }

    /** The fingerprint of the real-time screen (the reduced one); if there is no screen yet, it is null. */
    private fun frameHash(d: AgentDisplay): Int? {
        val bmp = d.frames.value ?: return null
        return runCatching {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            px.contentHashCode()
        }.getOrNull()
    }

    private suspend fun poll(timeoutMs: Long = VERIFY_MS, block: suspend () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (block()) return true
            delay(POLL_MS)
        }
        return block()
    }

    /**
     * A control on the self-test page: first press the resource id (the screen reader gives a short
     * name, and also recognizes the end of the complete writing), then press the text on the page
     * (controls with words do not have the resource id when reading the screen). The text and the
     * self-test page are taken according to the App language; the comparison is not case-sensitive and
     * does not take into account white space (see [SelfCheckText]).
     */
    private fun ScreenSnapshot.find(key: String): UiElement? =
        elements.firstOrNull { e -> e.resourceId?.let { it == key || it.endsWith("/$key") } == true }
            ?: elements.firstOrNull { e ->
                when (key) {
                    TITLE -> SelfCheckText.same(e.name, tr(R.string.selfcheck_target_title))
                    COUNT -> tapsText.matches(e.name)
                    BUTTON -> e.clickable && SelfCheckText.same(e.name, tr(R.string.selfcheck_target_button))
                    INPUT -> e.editable
                    TICK -> tickText.matches(e.name)
                    LIST -> e.scrollable
                    else -> false
                }
            }

    /** Visible lines on the self-test page (press from top to bottom). */
    private fun ScreenSnapshot.rows(): List<String> =
        elements.filter { rowText.matches(it.name) }.sortedBy { it.centerY }.map { it.name }

    /**
     * When reading the screen and not finding the self-test page, write "what you saw" in the report: if
     * it is a self-test page, just write the package name and the first few controls; if it is the
     * system interface, just write the package name, number, and type or ID of the control (the system
     * settings may have the user's name at the top, without writing text); other apps don't even write
     * the package name.
     */
    private fun seen(snap: ScreenSnapshot): String {
        val pkg = snap.packageName
        val n = snap.elements.size
        val first = snap.elements.take(SEEN_ELEMENTS)
        return when {
            pkg == app.packageName -> "saw Mina, $n elements: " + first.joinToString { (it.resourceId ?: it.name).take(SEEN_CHARS) }
            pkg == null -> "saw no readable window, $n elements"
            systemPackage(pkg) -> "saw $pkg, $n elements: " + first.joinToString { (it.resourceId ?: it.role).take(SEEN_CHARS) }
            else -> "saw another app, $n elements"
        }
    }

    private fun systemPackage(pkg: String): Boolean = pkg == SystemPickers.settingsPackage(app.packageManager) || runCatching {
        app.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** Text with a number ("clicked %d times", "timing %d", "line %d"): taken according to the App language, used to identify controls. */
    private fun numbered(res: Int) = SelfCheckText.Numbered(tr(res, SENTINEL), SENTINEL.toString())

    private val tapsText by lazy { numbered(R.string.selfcheck_target_taps) }
    private val tickText by lazy { numbered(R.string.selfcheck_target_tick) }
    private val rowText by lazy { numbered(R.string.selfcheck_target_row) }

    private fun UiElement.number(): Int? = Regex("\\d+").findAll(name).lastOrNull()?.value?.toIntOrNull()

    private fun reason(r: ActionResult): String = (r as? ActionResult.Failed)?.reason ?: "not on top"

    private fun format(ms: Double) = String.format(java.util.Locale.ROOT, "%.1f", ms)

    private companion object {
        const val TASK = "self-check"
        const val SANDBOX_WORKSPACE = "self-check"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val HELPER_WAIT_MS = 10_000L
        const val LATENCY_CALLS = 5
        const val SETTLE_MS = 2_000L
        const val VERIFY_MS = 2_000L
        const val POLL_MS = 100L
        const val FRAME_TRIES = 20
        const val BLANK_SPREAD = 8
        const val BACK_TRIES = 2
        /** You can press this many times at most including closing the input method. */
        const val BACK_MAX_PRESSES = 5
        const val ALIVE_MS = 15_000L
        const val ALIVE_STEP_MS = 500L
        /** It stops if the picture doesn't change at all for so long (the numbers on the page jump every 250 milliseconds). */
        const val STALL_MS = 3_000L
        const val COMMAND_MS = 8_000L
        const val MAX_CHARS = 4_000
        const val SETTINGS_IN_USE = "Settings is open on the main screen, so it was not opened"
        const val SETTINGS_ON_MAIN = "Settings opened on the main screen"

        const val COUNT = "selfcheck_count"
        const val BUTTON = "selfcheck_button"
        const val INPUT = "selfcheck_input"
        const val TICK = "selfcheck_tick"
        const val LIST = "selfcheck_list"
        const val TITLE = "selfcheck_title"
        val EXPECTED = listOf(TITLE, COUNT, BUTTON, INPUT, TICK, LIST)
        const val ASCII = "Mina 123"
        const val CHINESE = "Hello Mina"
        const val SENTINEL = 987_654_321
        /** The screen reading item can only take this long to wait for the self-test page to appear. */
        const val PAGE_WAIT_MS = 5_000L
        const val SEEN_ELEMENTS = 5
        const val SEEN_CHARS = 24

        const val WEB_PAGE = "https://appassets.androidplatform.net/assets/selfcheck/page.html"
        const val WEB_MARKER = "SELFCHECK-READ-OK"
        const val WEB_BUTTON = "#selfcheck-button"
        const val WEB_CLICKED = "Clicked 1"
    }
}
