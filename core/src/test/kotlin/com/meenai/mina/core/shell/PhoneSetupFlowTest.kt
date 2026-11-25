package com.meenai.mina.core.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupFlowTest {
    private val fresh = SetupInputs(developerOptions = false, wirelessDebugging = false, status = BuiltinShellStatus.NotPaired, usbDebugging = false, accessibility = false)
    private val switchesOn = fresh.copy(developerOptions = true, usbDebugging = true, accessibility = true, wirelessDebugging = true)

    private fun SetupView.markList() = SetupStep.entries.map { marks.getValue(it) }

    @Test
    fun walksTheStepsInOrderAsSettingsChange() {
        val v1 = PhoneSetupFlow.view(fresh)
        assertEquals(SetupPhase.Guide, v1.phase)
        assertEquals(SetupStep.DeveloperOptions, v1.current)
        assertEquals(listOf(StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo), v1.markList())

        val v2 = PhoneSetupFlow.view(fresh.copy(developerOptions = true))
        assertEquals(SetupStep.UsbDebugging, v2.current)
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo), v2.markList())

        val v2a = PhoneSetupFlow.view(fresh.copy(developerOptions = true, usbDebugging = true))
        assertEquals(SetupStep.Accessibility, v2a.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo), v2a.markList())

        val v2b = PhoneSetupFlow.view(fresh.copy(developerOptions = true, usbDebugging = true, accessibility = true))
        assertEquals(SetupStep.WirelessDebugging, v2b.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo), v2b.markList())

        val v3 = PhoneSetupFlow.view(switchesOn)
        assertEquals(SetupStep.PairingCode, v3.current)
        assertTrue(v3.autoRead)
        assertFalse(v3.portFound)
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(discovery = PairingDiscovery.Found(37000))).portFound)

        val v4 = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Connecting))
        assertEquals(SetupPhase.Working, v4.phase)
        assertNull(v4.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Current), v4.markList())

        val v5 = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Running))
        assertEquals(SetupPhase.Success, v5.phase)
        assertTrue(v5.markList().all { it == StepMark.Done })
    }

    @Test
    fun everyStepCanBePassedByHandWhenDetectionReadsOff() {
        // Everything on this phone reads "off": the user clicks "I have turned it on, next step" step by
        // step, and still goes to pairing.
        var i = fresh
        assertEquals(SetupStep.DeveloperOptions, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.DeveloperOptions)
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.UsbDebugging)
        assertEquals(SetupStep.Accessibility, PhoneSetupFlow.view(i).current)
        i = i.copy(accessibilitySkipped = true)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.WirelessDebugging)
        val pairing = PhoneSetupFlow.view(i)
        assertEquals(SetupStep.PairingCode, pairing.current)
        assertEquals(SetupPhase.Guide, pairing.phase)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Skipped, StepMark.Done, StepMark.Current, StepMark.Todo), pairing.markList())
        // Once connected, it counts as successful, no matter what you read before
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(i.copy(status = BuiltinShellStatus.Running)).phase)
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(i.copy(status = BuiltinShellStatus.Connecting)).phase)
    }

    @Test
    fun confirmedStepStaysPassedAndDetectionStillTicksTheRest() {
        // Only confirmed the developer options: USB debugging will be automatically checked when it is
        // turned on, no need to click again.
        val v = PhoneSetupFlow.view(switchesOn.copy(developerOptions = false, confirmed = setOf(SetupStep.DeveloperOptions)))
        assertEquals(SetupStep.PairingCode, v.current)
        assertEquals(StepMark.Done, v.marks[SetupStep.DeveloperOptions])
        assertEquals(StepMark.Done, v.marks[SetupStep.UsbDebugging])
        // The unconfirmed step reads and is turned off, and returns as usual.
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, confirmed = setOf(SetupStep.DeveloperOptions))).current)
        assertEquals(true, PhoneSetupFlow.detected(SetupStep.UsbDebugging, switchesOn))
        assertNull(PhoneSetupFlow.detected(SetupStep.WirelessDebugging, switchesOn.copy(wirelessDebugging = null)))
        assertNull(PhoneSetupFlow.detected(SetupStep.PairingCode, switchesOn))
    }

    @Test
    fun confirmedWirelessButHelperReadsItOffPointsBackInsteadOfSpinning() {
        val i = SetupInputs(true, false, BuiltinShellStatus.WirelessDebuggingOff, confirmed = setOf(SetupStep.WirelessDebugging))
        val v = PhoneSetupFlow.view(i)
        assertEquals(SetupPhase.Failed, v.phase)
        assertEquals(SetupStep.Connect, v.current)
        assertEquals(StepMark.Failed, v.marks[SetupStep.Connect])
        assertNull(v.failure)
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = false), v.suspect)
        // It is read that it is open but the status has not caught up: it is still connecting.
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(i.copy(wirelessDebugging = true)).phase)
        // The mobile phone that cannot read it also honestly says "cannot read"
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true), PhoneSetupFlow.view(i.copy(wirelessDebugging = null)).suspect)
    }

    @Test
    fun connectionFailurePointsAtTheMostLikelyEarlierStep() {
        val connect = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)
        // Developer options are read off (the user only comes here after confirming)
        val dev = PhoneSetupFlow.view(switchesOn.copy(developerOptions = false, confirmed = setOf(SetupStep.DeveloperOptions), status = connect))
        assertEquals(SetupPhase.Failed, dev.phase)
        assertEquals(SetupSuspect(SetupStep.DeveloperOptions, unreadable = false), dev.suspect)
        // Wireless debugging reads off
        val off = PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = false, confirmed = setOf(SetupStep.WirelessDebugging), status = connect))
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = false), off.suspect)
        // Wireless debugging cannot read
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true), PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = connect)).suspect)
        // The closing of the pairing window may also be caused by the switch.
        val closed = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = closed)).suspect?.step)
        // It all reads open, or the pairing code is wrong, and the assistant can’t get up: don’t point
        // randomly.
        assertNull(PhoneSetupFlow.view(switchesOn.copy(status = connect)).suspect)
        assertNull(PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))).suspect)
        assertNull(PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed))).suspect)
    }

    @Test
    fun anyEarlierStepCanBeRevisited() {
        val atPairing = switchesOn
        val back = PhoneSetupFlow.view(atPairing.copy(revisit = SetupStep.UsbDebugging))
        assertEquals(SetupPhase.Guide, back.phase)
        assertEquals(SetupStep.UsbDebugging, back.current)
        assertTrue(back.revisiting)
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Done, StepMark.Done, StepMark.Todo, StepMark.Todo), back.markList())
        assertFalse(PhoneSetupFlow.view(atPairing).revisiting)
        // You can go back even after failure, the failed step is still marked
        val failed = PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), revisit = SetupStep.WirelessDebugging))
        assertEquals(SetupStep.WirelessDebugging, failed.current)
        assertNull(failed.failure)
        assertEquals(StepMark.Failed, failed.marks[SetupStep.Connect])
        // You can also go back and pair again.
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), revisit = SetupStep.PairingCode)).current)
        // The step of automatic connection cannot be clicked; when the connection is in progress and has
        // been successful, please refer to the assistant.
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(atPairing.copy(revisit = SetupStep.Connect)).current)
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Connecting, revisit = SetupStep.DeveloperOptions)).phase)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Running, revisit = SetupStep.DeveloperOptions)).phase)
        // Reading the previous step was turned off: go back to that step first
        assertEquals(SetupStep.DeveloperOptions, PhoneSetupFlow.view(atPairing.copy(developerOptions = false, revisit = SetupStep.WirelessDebugging)).current)
    }

    @Test
    fun accessibilityCanBeSkippedAndPairingFallsBackToTheNotification() {
        val off = switchesOn.copy(accessibility = false)
        assertEquals(SetupStep.Accessibility, PhoneSetupFlow.view(off).current)
        val skipped = PhoneSetupFlow.view(off.copy(accessibilitySkipped = true))
        assertEquals(SetupStep.PairingCode, skipped.current)
        assertFalse(skipped.autoRead)
        assertEquals(StepMark.Skipped, skipped.marks[SetupStep.Accessibility])
        // After skipping it, it was opened in the system again: it was still automatically read.
        val laterOn = PhoneSetupFlow.view(switchesOn.copy(accessibilitySkipped = true))
        assertTrue(laterOn.autoRead)
        assertEquals(StepMark.Done, laterOn.marks[SetupStep.Accessibility])
        // Even if the code is incorrect and you refill it, it will still appear to be accessible.
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))).autoRead)
    }

    @Test
    fun restrictedSettingsHintOnlyAtTheAccessibilityStep() {
        val off = switchesOn.copy(accessibility = false, restrictedSettings = true)
        assertTrue(PhoneSetupFlow.view(off).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(restrictedSettings = false)).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(accessibility = true)).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(developerOptions = false)).showAppInfo)
    }

    @Test
    fun usbDebuggingOffBlocksSetupButNotARunningHelper() {
        // USB debugging is off: After pairing and wireless debugging on, it still stops at this step.
        val v = PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, status = BuiltinShellStatus.Connecting))
        assertEquals(SetupStep.UsbDebugging, v.current)
        assertEquals(SetupPhase.Guide, v.phase)
        // Even if it is already running, it will be set up (there will be another prompt on the settings
        // page)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, status = BuiltinShellStatus.Running)).phase)
    }

    @Test
    fun turningASwitchOffGoesBackToThatStep() {
        // After pairing, wireless debugging is turned off: go back to that step and do not require
        // re-pairing.
        val v = PhoneSetupFlow.view(SetupInputs(true, false, BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals(SetupStep.WirelessDebugging, v.current)
        // Developer options are turned off: Even if you have failed before, go back to the first step.
        val f = PhoneSetupFlow.view(SetupInputs(false, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        assertEquals(SetupStep.DeveloperOptions, f.current)
        assertEquals(SetupPhase.Guide, f.phase)
    }

    @Test
    fun pairedAndSwitchOnMeansConnectingEvenIfStatusLags() {
        val v = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals(SetupPhase.Working, v.phase)
    }

    @Test
    fun unreadableWirelessSwitchDoesNotBlock() {
        // Mobile phones that cannot read the switch: Don’t get stuck in the wireless debugging step,
        // follow the assistant’s status
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.NotPaired)).current)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.WirelessDebuggingOff)).current)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.Running)).phase)
    }

    @Test
    fun failuresLandOnTheStepTheUserMustRedo() {
        val wrong = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
        assertEquals(SetupPhase.Failed, wrong.phase)
        assertEquals(SetupStep.PairingCode, wrong.current)
        assertEquals(StepMark.Failed, wrong.marks[SetupStep.PairingCode])
        assertEquals(BuiltinShellFix.RePair, wrong.failure?.fix)

        val revoked = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)))
        assertEquals(SetupStep.PairingCode, revoked.current)

        val helper = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)))
        assertEquals(SetupStep.Connect, helper.current)
        assertEquals(StepMark.Done, helper.marks[SetupStep.PairingCode])
        assertEquals(BuiltinShellFix.Retry, helper.failure?.fix)

        assertEquals(BuiltinShellFix.OpenWifi, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi).fix)
    }

    @Test
    fun repairWhileRunningStaysOnPairingStepAndFinishesAfterNewPairing() {
        val running = SetupInputs(true, true, BuiltinShellStatus.Running, repair = true, discovery = PairingDiscovery.Found(4000))
        val waiting = PhoneSetupFlow.view(running)
        assertEquals(SetupPhase.Guide, waiting.phase)
        assertEquals(SetupStep.PairingCode, waiting.current)
        assertTrue(waiting.portFound)
        assertTrue(waiting.autoRead)
        assertEquals(BuiltinShellStatus.NotPaired, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Running, repair = true))
        assertEquals(BuiltinShellStatus.Connecting, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Connecting, repair = true))
        // After the new pairing starts, the re-pairing ends and the assistant continues to run
        // successfully.
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(running.copy(status = BuiltinShellStatus.Connecting)).phase)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(running.copy(repair = false)).phase)
        assertEquals(BuiltinShellStatus.Running, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Running, repair = false))
    }

    @Test
    fun asksForNotificationsOnlyAtThePairingStep() {
        val off = fresh.copy(notificationsAllowed = false)
        assertFalse(PhoneSetupFlow.view(off).needsNotifications)
        assertTrue(PhoneSetupFlow.view(off.copy(developerOptions = true, usbDebugging = true, accessibility = true, wirelessDebugging = true)).needsNotifications)
    }

    @Test
    fun pairingStepSaysWhatIsWrongWithTheNotification() {
        val pairing = fresh.copy(developerOptions = true, usbDebugging = true, accessibility = false, accessibilitySkipped = true, wirelessDebugging = true)
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(pairing).current)
        assertNull(PhoneSetupFlow.view(pairing).notifyIssue)
        // The most important thing is that notifications are not allowed; secondly, the "matching"
        // notification is turned off; it is adjusted to not pop up and only reminds, not not sending.
        val off = PhoneSetupFlow.view(pairing.copy(notificationsAllowed = false, pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.NotificationsOff, off.notifyIssue)
        val channelOff = PhoneSetupFlow.view(pairing.copy(pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.ChannelOff, channelOff.notifyIssue)
        assertTrue(channelOff.needsNotifications)
        val lowered = PhoneSetupFlow.view(pairing.copy(pairingChannel = PairingChannelHealth.Lowered))
        assertEquals(PairingNotifyIssue.NotHeadsUp, lowered.notifyIssue)
        assertFalse(lowered.needsNotifications)
        // Ignore notifications for other steps
        assertNull(PhoneSetupFlow.view(fresh.copy(pairingChannel = PairingChannelHealth.Off)).notifyIssue)
        // If pairing fails, it will still remind you when the pairing step stops.
        val failed = PhoneSetupFlow.view(pairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.ChannelOff, failed.notifyIssue)
    }

    @Test
    fun pairingCodeNormalization() {
        assertEquals("123456", PairingCode.normalize("123456"))
        assertEquals("123456", PairingCode.normalize(" 123 456 "))
        assertEquals("123456", PairingCode.normalize("123-456"))
        assertEquals("123456", PairingCode.normalize("１２３４５６"))
        // Full-width spaces, full-width horizontal lines, long and short horizontal lines in input
        // methods
        assertEquals("123456", PairingCode.normalize("１２３\u3000４５６"))
        assertEquals("123456", PairingCode.normalize("123－456"))
        assertEquals("123456", PairingCode.normalize("12–34—56"))
        assertNull(PairingCode.normalize(""))
        assertNull(PairingCode.normalize("123 45"))
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("12a456"))
        assertEquals(37123, PairingCode.port(" 37123 "))
        assertNull(PairingCode.port("0"))
        assertNull(PairingCode.port("70000"))
    }

    @Test
    fun fakeControlDrivesStatus() = runTest {
        val fake = FakeBuiltinShellControl(pairingResult = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))
        fake.startDiscovery()
        assertEquals(PairingDiscovery.Searching, fake.pairingDiscovery.value)
        fake.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), fake.status.value)
        fake.retry()
        assertEquals(BuiltinShellStatus.Running, fake.status.value)
        fake.unpair()
        assertEquals(BuiltinShellStatus.NotPaired, fake.status.value)
        assertEquals(listOf("discover", "pair 123456 40000", "retry", "unpair"), fake.calls)
    }

    // ---------- "Switch to computer startup" is only given when needed ----------

    private val atWireless = switchesOn.copy(wirelessDebugging = false)

    @Test
    fun usbFallbackIsHiddenInTheNormalFlow() {
        // Normally going all the way: every step, connection, success, no give
        listOf(
            fresh, fresh.copy(developerOptions = true), atWireless, switchesOn,
            switchesOn.copy(discovery = PairingDiscovery.Found(37000)),
            switchesOn.copy(status = BuiltinShellStatus.Connecting),
            switchesOn.copy(status = BuiltinShellStatus.Running),
            switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)),
            switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi)),
        ).forEach { assertFalse(it.toString(), PhoneSetupFlow.view(it).usbFallback) }
        // I stopped for a while but didn’t give it until the time limit was reached.
        assertFalse(PhoneSetupFlow.view(atWireless.copy(stalledMs = PhoneSetupFlow.STALL_MS - 1)).usbFallback)
    }

    @Test
    fun usbFallbackWhenTheSystemHasNoWirelessDebugging() {
        assertTrue(PhoneSetupFlow.view(atWireless.copy(wirelessMissing = true)).usbFallback)
        // The first two steps (developer options, USB debugging) are also required when starting the
        // computer, and will not be given until they are completed.
        assertFalse(PhoneSetupFlow.view(fresh.copy(wirelessMissing = true)).usbFallback)
        // I won’t give it if I succeed.
        assertFalse(PhoneSetupFlow.view(switchesOn.copy(wirelessMissing = true, status = BuiltinShellStatus.Running)).usbFallback)
    }

    @Test
    fun usbFallbackWhenTheUserCannotFindIt() {
        assertTrue(PhoneSetupFlow.view(atWireless.copy(usbAsked = true)).usbFallback)
        // Once it appears, keep it: it will still be there after you start connecting.
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(usbAsked = true, status = BuiltinShellStatus.Connecting)).usbFallback)
        // It will not be given when you click back to the first step and redo it.
        assertFalse(PhoneSetupFlow.view(atWireless.copy(usbAsked = true, revisit = SetupStep.DeveloperOptions)).usbFallback)
    }

    @Test
    fun usbFallbackAfterStallingWhereTheSystemShouldAnswer() {
        val stalled = PhoneSetupFlow.STALL_MS
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(atWireless)))
        assertTrue(PhoneSetupFlow.view(atWireless.copy(stalledMs = stalled)).usbFallback)
        // The pairing window cannot be found during the pairing step (the system has no pairing service)
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(switchesOn)))
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(stalledMs = stalled)).usbFallback)
        // After finding the pairing window, it is waiting for the user to fill in the code, which is not
        // considered stuck.
        val found = switchesOn.copy(discovery = PairingDiscovery.Found(37000))
        assertNull(PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(found)))
        assertFalse(PhoneSetupFlow.view(found.copy(stalledMs = stalled)).usbFallback)
        // Always connecting automatically
        val connecting = switchesOn.copy(status = BuiltinShellStatus.Connecting)
        assertEquals(SetupStep.Connect, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(connecting)))
        assertTrue(PhoneSetupFlow.view(connecting.copy(stalledMs = stalled)).usbFallback)
        // Users have to find the first few steps by themselves, it doesn’t matter how long it takes.
        assertNull(PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(fresh)))
        assertFalse(PhoneSetupFlow.view(fresh.copy(stalledMs = stalled * 10)).usbFallback)
    }

    @Test
    fun usbFallbackAfterFailuresThatMeanWirelessDoesNotWork() {
        listOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped).forEach { f ->
            val v = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Failed(f)))
            assertEquals(SetupPhase.Failed, v.phase)
            assertTrue(f.name, v.usbFallback)
        }
        // The user said that wireless debugging was turned on, but the assistant read that it was turned
        // off and did not connect.
        val claimed = atWireless.copy(confirmed = setOf(SetupStep.WirelessDebugging), wirelessDebugging = null, status = BuiltinShellStatus.WirelessDebuggingOff)
        val v = PhoneSetupFlow.view(claimed)
        assertEquals(SetupStep.WirelessDebugging, v.suspect?.step)
        assertTrue(v.usbFallback)
    }

    /** For assistants that have only been started using USB commands (this phone may not have wireless debugging available at all), wireless debugging is turned off. */
    private val usbOnly = switchesOn.copy(wirelessDebugging = false, restart = HelperRestart.UsbCommand)

    /** The assistant is running: successful, the wireless debugging and pairing steps are recorded as skipped, and wireless debugging is not mentioned anywhere. */
    @Test
    fun usbStartedHelperRunningIsSuccessWithWirelessStepsSkipped() {
        val v = PhoneSetupFlow.view(usbOnly.copy(status = BuiltinShellStatus.Running))
        assertEquals(SetupPhase.Success, v.phase)
        assertEquals(StepMark.Skipped, v.marks[SetupStep.WirelessDebugging])
        assertEquals(StepMark.Skipped, v.marks[SetupStep.PairingCode])
        assertEquals(StepMark.Done, v.marks[SetupStep.Connect])
        assertEquals(HelperRestart.UsbCommand, v.restart)
        assertFalse(v.usbFallback)
    }

    /** Handling over (assistant changing hands, App just getting up and waiting for it to come): It shows that it is connecting, and does not go back to that step because wireless debugging is turned off. */
    @Test
    fun handoverDoesNotFlipBackToWirelessStep() {
        val v = PhoneSetupFlow.view(usbOnly.copy(status = BuiltinShellStatus.Connecting))
        assertEquals(SetupPhase.Working, v.phase)
        assertNull(v.current)
        // The paired mobile phone is turning on wireless debugging for the user and the same is true
        // when connecting.
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(atWireless.copy(status = BuiltinShellStatus.Connecting)).phase)
        // When the previous switch is really turned off, you still have to go back to that step.
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(usbOnly.copy(usbDebugging = false, status = BuiltinShellStatus.Connecting)).current)
    }

    /** The assistant has stopped: It stops at the connection step and says it has stopped, giving USB commands and not leading to wireless debugging; people who want to pair can still click back to the pairing step. */
    @Test
    fun usbHelperStoppedShowsTheCommandNotWirelessDebugging() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        val v = PhoneSetupFlow.view(usbOnly.copy(status = stopped))
        assertEquals(SetupPhase.Failed, v.phase)
        assertEquals(SetupStep.Connect, v.current)
        assertEquals(stopped, v.failure)
        assertEquals(BuiltinShellFix.RunUsbCommand, v.failure?.fix)
        assertTrue(v.usbFallback)
        assertNull(v.suspect)
        assertEquals(StepMark.Skipped, v.marks[SetupStep.WirelessDebugging])
        assertEquals(StepMark.Skipped, v.marks[SetupStep.PairingCode])
        // The same is true when there is no wireless debugging item in the system.
        assertEquals(SetupStep.Connect, PhoneSetupFlow.view(usbOnly.copy(wirelessDebugging = null, wirelessMissing = true, status = stopped)).current)
        // Click back to the pairing step: pair as usual
        val back = PhoneSetupFlow.view(usbOnly.copy(wirelessDebugging = true, status = stopped, revisit = SetupStep.PairingCode))
        assertEquals(SetupStep.PairingCode, back.current)
        assertTrue(back.revisiting)
    }

    /** Mobile phones that have been paired and can use wireless debugging are not affected: when wireless debugging is turned off and the assistant is not available, you can still return to that step (it will automatically connect after turning it on). */
    @Test
    fun pairedPhoneStillGoesBackToWirelessStep() {
        val v = PhoneSetupFlow.view(atWireless.copy(status = BuiltinShellStatus.WirelessDebuggingOff, restart = HelperRestart.Wireless))
        assertEquals(SetupStep.WirelessDebugging, v.current)
        assertEquals(SetupPhase.Guide, v.phase)
        val ok = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Running, restart = HelperRestart.Wireless))
        assertTrue(ok.markList().all { it == StepMark.Done })
    }

    private val noWay = fresh.copy(developerSettingsMissing = true, wirelessMissing = true)

    @Test
    fun environmentWithoutDeveloperOptionsOrWirelessDebuggingSaysSoUpFront() {
        val v = PhoneSetupFlow.view(noWay)
        assertEquals(SetupPhase.Unavailable, v.phase)
        assertNull(v.current)
        assertFalse(v.usbFallback)
        assertTrue(v.markList().all { it == StepMark.Todo })
        // Pairing failed, and the same thing happened when I clicked "Re-pair".
        assertEquals(SetupPhase.Unavailable, PhoneSetupFlow.view(noWay.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed))).phase)
        assertEquals(SetupPhase.Unavailable, PhoneSetupFlow.view(noWay.copy(repair = true)).phase)
    }

    @Test
    fun onlyOneOfTheTwoMissingStillGuides() {
        assertEquals(SetupPhase.Guide, PhoneSetupFlow.view(fresh.copy(developerSettingsMissing = true)).phase)
        assertEquals(SetupPhase.Guide, PhoneSetupFlow.view(fresh.copy(wirelessMissing = true)).phase)
    }

    @Test
    fun aHelperThatRunsOrRanBeforeMeansThereIsAWay() {
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(noWay.copy(status = BuiltinShellStatus.Running)).phase)
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(status = BuiltinShellStatus.Connecting)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.UsbCommand)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.Wireless)))
        assertTrue(PhoneSetupFlow.unavailable(true, true, BuiltinShellStatus.NotPaired, HelperRestart.None))
    }
}
