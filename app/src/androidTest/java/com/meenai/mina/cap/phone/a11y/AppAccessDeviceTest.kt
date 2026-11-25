package com.meenai.mina.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.HarnessApp
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.core.access.AppAccessDenied
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAccessDeviceTest {
    @Test fun blockedLaunchIsDeniedWhileShellRemainsAvailable() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        val script = "/sdcard/Android/data/${app.packageName}/files/usb-start.sh"
        DeviceSupport.sh("sh $script")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320)
        try {
            val native = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            val screen = com.meenai.mina.core.uistate.AppAccessScreenPort(native, app.appAccess,
                packages = native::accessPackages, protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName)
            app.appAccess.setBlocked(pkg, true)
            try { screen.openApp(pkg); fail("Blocked launch must be denied") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            val command = screen.systemCommand("printf app-access-shell-ok")
            assertEquals(0, command?.exitCode)
            assertTrue(command?.output?.contains("app-access-shell-ok") == true)
            app.appAccess.setBlocked(pkg, false)
            assertNull(app.appAccess.current().denial(pkg))
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }

    /**
     * Apps that have never been set up are allowed by default: they can be opened and the screen can be
     * read. If you set it to disabled while the task is in progress, the next screen reading will be
     * immediately rejected; if you allow it again, you can read again - the permissions will read the
     * latest status every time, without restarting the task; when the verification results are
     * inherited, the list will no longer be used.
     */
    @Test fun blockingWhileRunningStopsTheNextRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        DeviceSupport.sh("sh /sdcard/Android/data/${app.packageName}/files/usb-start.sh")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320)
        try {
            val native = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            val screen = com.meenai.mina.core.uistate.AppAccessScreenPort(native, app.appAccess,
                packages = native::accessPackages, protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() })
            app.appAccess.setBlocked(pkg, false)
            app.appAccess.setShareAllowed(pkg, false)
            assertNull("a package nobody configured must be allowed", app.appAccess.current().denial(pkg))
            screen.openApp(pkg)
            screen.checkAppAccess()
            screen.checkScreen()

            app.appAccess.setBlocked(pkg, true)
            try { screen.checkScreen(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            // Read the screen and use the window ownership that comes with this screen to check: also
            // rejected
            try { screen.snapshot(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }

            app.appAccess.setBlocked(pkg, false)
            screen.checkScreen()
            screen.checkAppAccess()
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }
}
