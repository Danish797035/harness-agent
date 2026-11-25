package com.meenai.mina.core.uistate

import com.meenai.mina.core.access.AppAccessDenied
import com.meenai.mina.core.access.AppAccessPolicy
import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.tool.FakeScreen
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppAccessScreenPortTest {
    @Test fun emptyBlocklistAllowsUnknownPackageWithoutProbe() = runTest {
        assertNull(AppAccessState().denial(null))
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW,
            packages = { error("empty blocklist must not query windows") })
        screen.snapshot()
    }

    @Test fun unknownPackageIsReadOnceMore() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = {
            if (++probes == 1) listOf(null) else listOf("allowed")
        })
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun stillUnknownPackageIsRefusedAfterOneRetry() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { probes++; listOf(null) })
        denied("app_access_unverified") { screen.checkAppAccess() }
        assertEquals(2, probes)
    }

    @Test fun retryCanIdentifyBlockedPackage() = runTest {
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = {
            if (++probes == 1) listOf(null) else listOf("bank")
        })
        denied("app_blocked") { screen.checkAppAccess() }
        assertEquals(2, probes)
    }

    @Test fun unavailableSettingsDenyEvenWhenListIsEmpty() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { AppAccessState(available = false) },
            packages = { error("must reject before probing") })
        denied("app_access_unavailable") { screen.checkAppAccess() }
    }

    @Test fun uncreatedDisplayDoesNotNeedAnApp() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { emptyList() })
        screen.checkAppAccess()
    }

    @Test fun sensitiveOwnUiIsProtectedEvenWithEmptyList() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW, packages = { listOf("mina") },
            protectedUi = { true }, ownPackage = "mina")
        denied("app_access_protected") { screen.snapshot() }
    }

    @Test fun blocklistIsRereadAfterWaiting() = runTest {
        var state = AppAccessState(blocked = setOf("bank"))
        var probes = 0
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { state }, packages = {
            probes++
            if (probes == 2) state = AppAccessState()
            listOf(null)
        })
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun runtimeBlockStopsNextReadAndAllowRestoresIt() = runTest {
        var state = AppAccessState()
        var reads = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray { reads++; return byteArrayOf(1) }
        }
        val screen = AppAccessScreenPort(inner, AppAccessPolicy { state }, packages = { listOf("bank") })
        assertArrayEquals(byteArrayOf(1), screen.screenshotPng())
        state = AppAccessState(blocked = setOf("bank"), revision = 1)
        denied("app_blocked") { screen.screenshotPng() }
        assertEquals(1, reads)
        state = AppAccessState(revision = 2)
        assertArrayEquals(byteArrayOf(1), screen.screenshotPng())
    }

    @Test fun readThatCrossesIntoBlockedAppDoesNotReturnContent() = runTest {
        var pkg = "allowed"
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray { pkg = "bank"; return byteArrayOf(1) }
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { listOf(pkg) })
        denied("app_blocked") { screen.screenshotPng() }
    }

    @Test fun canLeaveBlockedAppButCannotLaunchBlockedTarget() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), blocked(), packages = { listOf("bank") })
        screen.openApp("allowed")
        denied("app_blocked") { screen.openApp("bank") }
        denied("app_blocked") { screen.openAppCopy("bank", 10) }
        denied("app_blocked") { screen.shareText("bank", "text") }
        denied("app_blocked") { screen.awaitAppFree("bank", null, 1_000) }
    }

    // ---------- Every port member crosses this boundary ----------

    /** Each member of the port is explicitly transferred to the port inside the boundary: omissions will fall on the default implementation of the interface and cannot reach inside. */
    @Test fun everyPortMemberIsForwardedExplicitly() {
        val reached = mutableSetOf<String>()
        val screen = AppAccessScreenPort(recordingPort(reached), AppAccessPolicy.ALLOW, packages = { listOf("bank") })
        for (m in portMembers()) {
            reached.clear()
            invoke(screen, m)
            assertTrue("${m.name} is not forwarded explicitly by AppAccessScreenPort", m.name in reached)
        }
    }

    /** When a disabled app is in the foreground, every member of the port is rejected except for those that explicitly say not to touch the app interface; newly added members are included in the list to be checked by default. */
    @Test fun everyPortMemberExceptPassThroughIsChecked() {
        val screen = AppAccessScreenPort(recordingPort(mutableSetOf()), blocked(), packages = { listOf("bank") })
        for (m in portMembers()) {
            val error = runCatching { invoke(screen, m) }.exceptionOrNull()
            if (m.name in PASS_THROUGH) assertNull("${m.name} should pass through", error)
            else assertTrue("${m.name} is not checked by AppAccessScreenPort (got $error)", error is AppAccessDenied)
        }
    }

    private fun portMembers(): List<Method> = ScreenPort::class.java.methods.filter { it.name !in OWN }

    /** Just write down the name of the port; read the screen to the front desk of the bank. */
    private fun recordingPort(reached: MutableSet<String>): ScreenPort =
        Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, _ ->
            reached += m.name
            if (m.name == "snapshot" || m.name == "peek") ScreenSnapshot("s", "bank", emptyList(), 1, 1) else null
        } as ScreenPort

    /** Adjust the sample value once according to the parameter type (the last parameter of the hanging function is the continuation, and the call here will not really hang); the thrown exception will be thrown as it is. */
    private fun invoke(screen: ScreenPort, m: Method) {
        val args = m.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Double::class.javaPrimitiveType -> 0.0
                Boolean::class.javaPrimitiveType -> false
                String::class.java -> ""
                Int::class.javaObjectType -> null
                List::class.java -> emptyList<Any>()
                SystemAction::class.java -> SystemAction.ShowAlarms
                Continuation::class.java -> Continuation<Any?>(EmptyCoroutineContext) {}
                else -> error("no sample for ${t.name} in ${m.name}: add one")
            }
        }
        try {
            m.invoke(screen, *args.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // ---------- Ask less window ----------

    @Test fun snapshotIsCheckedAgainstItsOwnWindowsWithoutProbing() = runTest {
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("allowed")
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { error("snapshot carries its windows") })
        assertEquals("allowed", screen.snapshot().packageName)
        val bank = AppAccessScreenPort(object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("bank")
        }, blocked(), packages = { error("snapshot carries its windows") })
        denied("app_blocked") { bank.snapshot() }
    }

    @Test fun snapshotWithUnreadableWindowStillRetriesOnce() = runTest {
        var probes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows(null)
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { probes++; listOf("bank") })
        denied("app_blocked") { screen.snapshot() }
        assertEquals(1, probes)
    }

    @Test fun readsRightAfterASnapshotReuseItsCheckWhileWindowsStay() = runTest {
        var token = 0L
        var now = 0L
        var probes = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = withWindows("allowed")
        }
        val screen = AppAccessScreenPort(inner, blocked(), packages = { probes++; listOf("allowed") },
            windowToken = { token }, clock = { now })
        screen.snapshot()
        screen.screenshotPng()
        screen.frameGrid()
        repeat(10) { screen.checkAppAccess() }
        assertEquals(0, probes)
        // The window has changed: ask again and use it later
        token++
        screen.screenshotPng()
        screen.screenshotPng()
        assertEquals(1, probes)
        // The time limit for inheritance has passed: Ask again
        now += AppAccessScreenPort.CHECK_TTL_MS + 1
        screen.checkAppAccess()
        assertEquals(2, probes)
    }

    @Test fun reusedCheckIsDroppedWhenTheListChanges() = runTest {
        var state = AppAccessState(blocked = setOf("other"), revision = 1)
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy { state }, packages = { listOf("bank") },
            windowToken = { 0L }, clock = { 0L })
        screen.checkAppAccess()
        state = AppAccessState(blocked = setOf("bank"), revision = 2)
        denied("app_blocked") { screen.checkAppAccess() }
    }

    @Test fun emptyListDoesNoExtraWorkOnAnyRead() = runTest {
        val screen = AppAccessScreenPort(FakeScreen(), AppAccessPolicy.ALLOW,
            packages = { error("empty list must not query windows") }, windowToken = { error("empty list must not read the window count") })
        screen.snapshot()
        screen.peek()
        screen.screenshotPng()
        screen.tapXY(1, 1)
        screen.checkAppAccess()
    }

    private fun withWindows(vararg packages: String?) = ScreenSnapshot("s", packages.firstOrNull(), emptyList(), 1, 1,
        windows = packages.mapIndexed { i, p -> UiWindowTree(i, "application", i, null, p, i == 0, i == 0, emptyList()) })

    private fun blocked() = AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }

    private suspend fun denied(code: String, body: suspend () -> Unit) {
        try {
            body()
            fail("expected $code")
        } catch (e: AppAccessDenied) {
            assertEquals(code, e.code)
        }
    }

    private companion object {
        /** Boundary own members. */
        val OWN = setOf("getAppAccess", "checkAppAccess")

        /** Do not touch any App interface and transfer the members as they are; select the system actions of the App according to the target and let the port view the list by itself. Add it in to figure out why. */
        val PASS_THROUGH = setOf("systemAction", "systemCommand", "listApps", "otherProfiles", "appCopies", "getAfterTask")
    }
}
