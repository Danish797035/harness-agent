package com.meenai.mina.cap.phone.a11y

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real machine test: The page on Mina's screen opens another App through a service in another process
 * (the same as opening it from the background), and the system puts it on the home screen; the result of
 * the click will be reported as opened, and it will be moved back to Mina's screen, and the top of the
 * home screen will return to the original one. Which App to open is specified by the instrument
 * parameter spillTarget (default system setting); it will not run when it already has a task (moving it
 * over and then destroying it with the screen will lose that task). Apps installed with two copies will
 * first ask which one to open: the question will not be moved, stay on the home screen, and report
 * picker, onUserScreen, tasks, etc. to the user for selection; when the instrument parameter pickOption
 * is given, it will be tested when the user selects on the home screen (or back to close), and then see
 * which one is moved back to Mina's screen and which one is reported to be opened.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSpillDeviceTest {
    // Just like in the task, the size and density of the screen are the same as the main screen: the
    // moved page does not need to be rebuilt due to the size change (the system selection box will be
    // closed automatically when rebuilt)
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.meenai.mina.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi)
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()
    private val target = InstrumentationRegistry.getArguments().getString("spillTarget") ?: SETTINGS

    @After
    fun tearDown() {
        // When it is not moved back, move the one left on the home screen to Mina's screen and destroy
        // it together with the screen.
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        // If it fails midway and the problem remains on the home screen: press Return to close it.
        screen.userPick.value?.let { p -> if (main >= 0 && shell.packageOfTask(main) == p.packageName) sh("input keyevent 4") }
        if (main >= 0 && shell.packageOfTask(main) == target) shell.moveTask(main, agent.display.displayId)
        agent.release()
    }

    /** It also runs when the instrument parameter allowExisting=true (that task will be moved over and closed with the screen, just like crossing out the recent task). */
    private fun noTaskYet() {
        if (InstrumentationRegistry.getArguments().getString("allowExisting") == "true") return
        assumeFalse("$target already has a task",
            sh("dumpsys activity recents").lines().any { val l = it.trim(); l.startsWith("* Recent") && " A=" in l && l.substringAfter(" A=").substringAfter(':').startsWith(target) })
    }

    private suspend fun spillPage(): com.meenai.mina.core.uistate.ScreenSnapshot {
        screen.ensureDisplay()
        sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${SpillTestActivity::class.java.name} --es ${SpillTestActivity.EXTRA_TARGET} $target")
        val s = pollUntil(5_000) { runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open elsewhere" } } }
        assertNotNull("spill page did not come up", s)
        return s!!
    }

    /** Contrast: Without Mina's actions, directly inject a click onto Mina's screen, and the result will fall on the home screen (this is the phenomenon that needs to be repaired). */
    @Test
    fun withoutTheWatchTheLaunchLandsOnTheMainScreen() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val b = s.elements.first { it.name == "open elsewhere" }
        val vdBefore = shell.topTaskId(vd)
        assertTrue(shell.injectTap(vd, b.centerX, b.centerY))
        val landed = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != mainBefore } }
        println("before: main top $mainBefore -> $landed (${landed?.let { shell.packageOfTask(it) }}), Mina screen top $vdBefore -> ${shell.topTaskId(vd)}")
        assertNotNull("launch did not land on the main screen", landed)
        assertEquals("Mina's screen changed", vdBefore, shell.topTaskId(vd))
    }

    @Test
    fun appOpenedOnTheMainScreenIsPulledBack() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val button = s.elements.first { it.name == "open elsewhere" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        val opened = (r as ActionResult.Ok).opened
        assertNotNull("tap did not report opened: $r", opened)
        val now = screen.snapshot()
        println("spill result: $r; on Mina's screen (${now.packageName}): ${now.elements.filter { it.clickable }.map { it.name }}")
        if (!opened!!.picker) {
            assertTrue("not pulled back: $opened", opened.pulledBack)
            assertEquals(target, shell.packageOfTask(shell.topTaskId(vd)))
            assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
            return@runBlocking
        }
        // The system asks on the user screen which one to open: do not move or reopen, stay on the home
        // screen and wait for the user to select
        assertTrue("question was moved: $opened", !opened.pulledBack && opened.onUserScreen)
        val chooserTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        assertEquals("question is not on the main screen", opened.packageName, shell.packageOfTask(chooserTask))
        assertNotNull("run is not waiting for the user", screen.userPick.value)
        assertEquals("Mina's screen changed", SpillTestActivity::class.java.name, shell.topActivity(vd))
        // Instrument parameters pickOption=<number, starting from 0> or back: When testing as a user, click that
        // option on the home screen (or press return to close the question)
        val pick = InstrumentationRegistry.getArguments().getString("pickOption") ?: return@runBlocking
        if (pick == "back") sh("input keyevent 4")
        else {
            val label = r.chooser?.options?.firstOrNull()?.label
            val nodes = ScreenReader.contentWindows(DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY))
                .filter { it.root?.packageName?.toString() == opened.packageName }
                .flatMap { w -> ScreenReader.read(listOf(w), agent.display.width, agent.display.height).entries.map { it.element } }
                .filter { it.clickable && it.name == label }
            val option = nodes.getOrNull(pick.toInt())
            assertNotNull("no option $pick: ${nodes.map { it.name }}", option)
            sh("input tap ${option!!.centerX} ${option.centerY}")
        }
        val settled = screen.awaitUserPick()
        // The selected ones need to be handled by the user personally: At this time, the test will be
        // done when the user clicks to continue.
        if (settled is AndroidScreen.PickResult.NeedsUser) screen.afterUserStep(settled)
        val after = screen.snapshot()
        val top = shell.topTaskId(vd)
        println("after the user ($pick): $settled; opened=${after.opened}; Mina's screen top ${shell.packageOfTask(top)} user ${shell.userOfTask(top)}; " +
            "main top ${shell.topTaskId(Display.DEFAULT_DISPLAY)} (before $mainBefore)")
        if (pick == "back") assertTrue("not reported closed: ${after.opened}", after.opened?.closed == true)
        else {
            assertTrue("not pulled back: ${after.opened}", after.opened?.pulledBack == true && (after.opened?.inProfile != null || after.opened?.byUser == true))
            assertEquals(target, shell.packageOfTask(top))
        }
        assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
    }
}
