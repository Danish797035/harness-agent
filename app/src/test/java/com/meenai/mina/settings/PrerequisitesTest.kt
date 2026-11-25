package com.meenai.mina.settings

import com.meenai.mina.core.shell.BuiltinShellFailure
import com.meenai.mina.core.shell.BuiltinShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerequisitesTest {
    private val ready = SystemStatus(
        sdkInt = 34,
        builtinShell = BuiltinShellStatus.Running,
        accessibility = AccessibilityState.On,
        notificationsAllowed = true,
    )

    @Test
    fun allReadyCanStart() {
        val c = Prerequisites.check(ready, hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.problems.isEmpty())
    }

    @Test
    fun android11CanStartWithoutPhoneControl() {
        val c = Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.blockers.isEmpty())
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.SystemTooOld), c.problems)
    }

    @Test
    fun oldAndroidDoesNotAskToSetUpUnsupportedPhoneFeatures() {
        val c = Prerequisites.check(
            ready.copy(sdkInt = 30, builtinShell = BuiltinShellStatus.NotPaired, accessibility = AccessibilityState.Off),
            hasMainModel = true,
        )
        assertTrue(c.canStartTask)
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.SystemTooOld), c.problems)
    }

    @Test
    fun oldAndroidStillNeedsAMainModel() {
        val c = Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = false)
        assertFalse(c.canStartTask)
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun noMainModelBlocksStart() {
        val c = Prerequisites.check(ready, hasMainModel = false)
        assertFalse(c.canStartTask)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun accessibilityOnlyNeededForPhoneApps() {
        for (a in listOf(AccessibilityState.Off, AccessibilityState.EnabledNotRunning)) {
            val c = Prerequisites.check(ready.copy(accessibility = a), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.Accessibility(a)), c.problems)
        }
    }

    @Test
    fun notificationsOffOnlyWarns() {
        val c = Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertEquals(listOf(Problem.NotificationsOff), c.problems)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun android12And13CanControlAppsWithAFocusWarning() {
        for (sdk in 31..33) {
            val c = Prerequisites.check(ready.copy(sdkInt = sdk), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertTrue(c.phoneReady)
            assertEquals(listOf(Problem.FocusMayLeak), c.problems)
        }
        assertTrue(Prerequisites.check(ready.copy(sdkInt = 34), hasMainModel = true).problems.isEmpty())
        assertEquals(listOf(Problem.SystemTooOld), Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = true).problems)
    }

    @Test
    fun android12StillNeedsHelperAndAccessibilityForPhoneControl() {
        for (sdk in 31..32) {
            val c = Prerequisites.check(
                ready.copy(sdkInt = sdk, builtinShell = BuiltinShellStatus.NotPaired, accessibility = AccessibilityState.Off),
                hasMainModel = true,
            )
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off), Problem.FocusMayLeak), c.problems)
        }
    }

    @Test
    fun problemsOrderedBySeverity() {
        val c = Prerequisites.check(
            SystemStatus(33, BuiltinShellStatus.NotPaired, AccessibilityState.Off, false), hasMainModel = false,
        )
        assertEquals(
            listOf(
                Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off),
                Problem.NoModel, Problem.NotificationsOff, Problem.FocusMayLeak,
            ),
            c.problems,
        )
    }

    // Fix 1 M4: The "Need You" reminder is prompted when it is turned down by the user or the system,
    // but it does not block the task.
    @Test
    fun attentionLoweredIsAWarning() {
        val c = Prerequisites.check(ready.copy(attentionLowered = true), hasMainModel = true)
        assertEquals(listOf<Problem>(Problem.AttentionLowered(attention = true, result = false)), c.problems)
        assertTrue(c.canStartTask)
    }

    // Various combinations of the two channels "Need Your Handling" and "Task Result": judge in one
    // place and bring which one has been lowered
    @Test
    fun loweredChannelCombinations() {
        fun problems(attention: Boolean, result: Boolean) =
            Prerequisites.check(ready.copy(attentionLowered = attention, resultLowered = result), hasMainModel = true).problems
        assertEquals(emptyList<Problem>(), problems(false, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, false)), problems(true, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(false, true)), problems(false, true))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, true)), problems(true, true))
        assertTrue(Prerequisites.check(ready.copy(resultLowered = true), hasMainModel = true).canStartTask)
    }

    // When the main switch is turned off, it will only report "notification not allowed" and will not
    // remind the channel repeatedly.
    @Test
    fun masterSwitchOffHidesChannelProblems() {
        val c = Prerequisites.check(
            ready.copy(notificationsAllowed = false, attentionLowered = true, resultLowered = true), hasMainModel = true,
        )
        assertEquals(listOf<Problem>(Problem.NotificationsOff), c.problems)
    }

    // Web pages and sandbox tasks do not require built-in assistants: they can still be started, and
    // will only report phone_unavailable when using a mobile phone; problems will still be listed and
    // prompted.
    @Test
    fun phoneHelperOnlyNeededForPhoneApps() {
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode),
        ).forEach { s ->
            val c = Prerequisites.check(ready.copy(builtinShell = s), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf<Problem>(Problem.PhoneHelper(s)), c.problems)
        }
        // Android 12 or below only reports that the system is too old
        assertEquals(
            listOf<Problem>(Problem.SystemTooOld),
            Prerequisites.check(ready.copy(sdkInt = 30, builtinShell = BuiltinShellStatus.NotPaired), hasMainModel = true).problems,
        )
    }
}
