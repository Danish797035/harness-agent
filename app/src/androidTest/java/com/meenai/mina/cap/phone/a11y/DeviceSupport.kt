package com.meenai.mina.cap.phone.a11y

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.cap.phone.display.LazyAgentDisplay
import com.meenai.mina.shell.IHarnessShell
import com.meenai.mina.cap.phone.shell.ShellBinding
import com.meenai.mina.cap.phone.PhoneServices
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.meenai.mina.a11y.HarnessAccessibilityService

/** Commonly used for real machine testing: shell connection, accessibility service, and AndroidScreen on the virtual screen. Requires built-in assistant running (paired, wireless debugging on) and Mina accessibility service turned on. */
object DeviceSupport {
    const val SETTINGS = "com.android.settings"
    const val TEST_PACKAGE = "com.meenai.mina.test"
    private var connection: ShellBinding? = null
    private val phone get() = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * The shell connection in the shared process with the App is always occupied: binding and unbinding
     * each other will cause the party that unbinds first to end the shell service, and the binder in the
     * other party's hand will become invalid.
     */
    @Synchronized
    fun shell(): IHarnessShell {
        val c = connection ?: phone.sharedShell.acquire().also {
            connection = it
            rebindAccessibility()
        }
        return awaitShell(c)
    }

    /** Wait for the connection to get the live assistant; if it cannot wait, it will fail with the current status and prompt to pair first. It takes a while for the watchdog to restart after the assistant is killed, and the dead binder may still be held before the disconnect callback arrives. */
    fun awaitShell(c: ShellBinding, timeoutMs: Long = 30_000): IHarnessShell = runBlocking {
        withTimeoutOrNull(timeoutMs) { c.shell.filterNotNull().first { it.asBinder().pingBinder() } }
    } ?: error(
        "Mina's phone helper is not running (status=${phone.shellControl.status.value}): " +
            "pair it once in Mina (设置手机操作) with Wireless debugging on, keep Wireless debugging on, then rerun",
    )

    /** When the service goes offline (the previous test ended the binding of this process, etc.), first prompt the system to rebind, and then wait for it to connect. */
    fun service(): ScreenAccessibility {
        if (ScreenAccessibility.instance.value == null) rebindAccessibility()
        return runBlocking {
            withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
        } ?: error("Mina accessibility service is not enabled")
    }

    class Agent(val display: AgentDisplay, val screen: AndroidScreen) {
        fun release() = display.release()
    }

    fun newAgent(): Agent {
        val shell = shell()
        val d = AgentDisplay(shell, 720, 1600, 320)
        return Agent(d, AndroidScreen(service(), shell, ready(d)))
    }

    /** Already built screen: To test, build the screen yourself first (you may also put an app on it first), and you don’t have to wait for phone_launch_app to read the screen. */
    fun ready(d: AgentDisplay): LazyAgentDisplay = LazyAgentDisplay(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     * When starting the test, the system will forcefully stop the app, and the accessibility service
     * will be recorded as crashed and will no longer be automatically reconnected. When it is enabled,
     * take it off the open list and then put it back to prompt the system to rebind; if it is not
     * enabled, it will not move.
     */
    private fun rebindAccessibility() {
        if (ScreenAccessibility.instance.value != null) return
        val key = "enabled_accessibility_services"
        val mina = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/${HarnessAccessibilityService::class.java.name}"
        val enabled = sh("settings get secure $key").trim().split(':')
        if (mina !in enabled) return
        try {
            sh("settings put secure $key ${enabled.filter { it != mina }.joinToString(":").ifEmpty { "\"\"" }}")
            Thread.sleep(500)
        } finally {
            sh("settings put secure $key ${enabled.joinToString(":")}")
        }
    }

    fun sh(cmd: String): String {
        // Do not suppress other accessibility services, otherwise Mina's services will be suspended
        // during testing
        val ua = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** Open the page in the test package on the virtual screen (given by category, the category name is consistent with the one registered in the list). */
    fun startTestActivity(displayId: Int, activity: Class<out android.app.Activity>) {
        sh("am start --display $displayId -n $TEST_PACKAGE/${activity.name}")
    }

    /**
     * Home screen status: the top focus screen of the system, the focus window and focus activity of
     * screen 0, and the top task of the home screen. Whatever the Agent does on the virtual screen,
     * these should not change.
     */
    fun mainScreenState(shell: IHarnessShell): String {
        val topDisplay = sh("dumpsys window").lines().map { it.trim() }
            .firstOrNull { it.startsWith("mTopFocusedDisplayId=") }
        checkNotNull(topDisplay) { "mTopFocusedDisplayId not found in dumpsys window" }
        val section = sh("dumpsys window displays").substringAfter("Display: mDisplayId=0", "")
            .substringBefore("Display: mDisplayId=").lines().map { it.trim() }
        val focus = section.firstOrNull { it.startsWith("mCurrentFocus=") }
        checkNotNull(focus) { "mCurrentFocus not found for display 0" }
        val focusedApp = section.firstOrNull { it.startsWith("mFocusedApp=") }
            ?.let { Regex("u0 (\\S+)").find(it)?.groupValues?.get(1) }
        return "$topDisplay $focus focusedApp=$focusedApp topTask=${shell.topTaskId(android.view.Display.DEFAULT_DISPLAY)}"
    }

    fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
