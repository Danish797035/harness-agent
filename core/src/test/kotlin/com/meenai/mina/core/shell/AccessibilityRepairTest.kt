package com.meenai.mina.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRepairTest {
    private val stuck = A11yObservation(enabled = true, connected = false)
    private val on = A11yObservation(enabled = true, connected = true)
    private val off = A11yObservation(enabled = false, connected = false)

    /** Fake environment: When [comesBack] is true, the [connectAfterMs] service will be connected after restarting. */
    private inner class Rig(
        initial: A11yObservation = stuck,
        helperStatus: BuiltinShellStatus = BuiltinShellStatus.Running,
        var consent: RecoveryConsent = RecoveryConsent(),
        var comesBack: Boolean = true,
        val connectAfterMs: Long = 500,
        val result: PermissionResult = PermissionResult.Done,
        var direct: Boolean = false,
        unlocked: Boolean = true,
    ) {
        val userUnlocked = MutableStateFlow(unlocked)
        val service = MutableStateFlow(initial)
        val helper = MutableStateFlow(helperStatus)
        val restarts = mutableListOf<Long>()
        lateinit var scope: TestScope

        val repair = AccessibilityRepair(
            restart = { gap ->
                restarts += scope.currentTime
                if (comesBack && result == PermissionResult.Done) {
                    scope.launch { kotlinx.coroutines.delay(gap + connectAfterMs); service.value = on }
                }
                result
            },
            consent = { consent },
            direct = { direct },
            now = { scope.currentTime },
        )

        fun start(s: TestScope) {
            scope = s
            s.backgroundScope.launch { repair.watch(service, helper, userUnlocked) }
            s.runCurrent()
        }
    }

    @Test
    fun waitsForTheFirstUnlockThenRepairs() = runTest {
        // It has not been unlocked after booting: the service cannot be bound to the original, and the
        // accessibility list in the system cannot be moved.
        val rig = Rig(unlocked = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS * 2)
        assertEquals(emptyList<Long>(), rig.restarts)
        assertNull(rig.repair.last.value)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        // Unlocked but still not connected: Wait for a while before repairing as usual.
        val unlockedAt = currentTime
        rig.userUnlocked.value = true
        advanceTimeBy(10_000)
        assertEquals(listOf(unlockedAt + AccessibilityRepair.GRACE_MS), rig.restarts)
        assertEquals(on, rig.service.value)
        assertEquals(true, rig.repair.last.value?.restarted)
    }

    @Test
    fun repairsStuckServiceWhileHelperRuns() = runTest {
        val rig = Rig().also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.GRACE_MS - 1)
        assertEquals(emptyList<Long>(), rig.restarts)
        advanceTimeBy(10_000)
        assertEquals(listOf(AccessibilityRepair.GRACE_MS), rig.restarts)
        val last = rig.repair.last.value!!
        assertTrue(last.restarted)
        assertEquals(1, last.attempts)
    }

    @Test
    fun noRepairWhenItConnectsDuringGrace() = runTest {
        val rig = Rig().also { it.start(this) }
        advanceTimeBy(1_000)
        rig.service.value = on
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
        assertNull(rig.repair.last.value)
    }

    @Test
    fun turnsItBackOnWhenRemovedFromTheSystemList() = runTest {
        // When forced to stop, the system will remove Mina from the accessibility list: it will open
        // automatically as well.
        val rig = Rig(initial = off).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun noRepairWhenDeclinedInMina() = runTest {
        val rig = Rig(consent = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
    }

    @Test
    fun repairsWithoutHelperWhenAppCanWriteSettingsItself() = runTest {
        // Wireless debugging is turned off and the assistant is not available, but the app itself has
        // the authorization to modify system settings: still repair it
        val rig = Rig(initial = off, helperStatus = BuiltinShellStatus.NotPaired, direct = true).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun waitsForHelperThenRepairs() = runTest {
        val rig = Rig(helperStatus = BuiltinShellStatus.Connecting).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
        rig.helper.value = BuiltinShellStatus.Running
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(1, rig.restarts.size)
    }

    @Test
    fun givesUpAfterLimitWithBackoff() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
        // The second time after waiting for the connection to time out and backing off again.
        val gap = rig.restarts[1] - rig.restarts[0]
        assertTrue("gap $gap", gap >= AccessibilityRepair.CONNECT_TIMEOUT_MS + AccessibilityRepair.BACKOFF_MS)
        assertFalse(rig.repair.last.value!!.restarted)
        // After exhaustion, the status changes again (the assistant goes offline and comes back) and
        // does not try again.
        rig.helper.value = BuiltinShellStatus.Connecting
        runCurrent()
        rig.helper.value = BuiltinShellStatus.Running
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
    }

    @Test
    fun newIncidentAfterItCameBackCountsAgain() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(2, rig.restarts.size)
        // The user manually turned it off and on and then connected; then it got stuck again: count
        // again.
        rig.service.value = on
        runCurrent()
        rig.comesBack = true
        rig.service.value = stuck
        advanceTimeBy(60_000)
        assertEquals(3, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun failedCommandCountsAsAttempt() = runTest {
        val rig = Rig(result = PermissionResult.Failed("boom")).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
        assertFalse(rig.repair.last.value!!.restarted)
    }

    @Test
    fun stopsRetryingWhenDeclinedInMinaBetweenAttempts() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(1, rig.restarts.size)
        rig.consent = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))
        advanceTimeBy(120_000)
        assertEquals(1, rig.restarts.size)
    }

    @Test
    fun slowConnectWithinTimeoutIsSuccess() = runTest {
        val rig = Rig(connectAfterMs = AccessibilityRepair.CONNECT_TIMEOUT_MS - 2_000).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun phaseFollowsTheRepair() = runTest {
        val rig = Rig(connectAfterMs = 2_000).also { it.start(this) }
        assertEquals(RepairPhase.Pending, rig.repair.phase.value)
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(RepairPhase.Repairing, rig.repair.phase.value)
        advanceTimeBy(10_000)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        // Even if it is removed, it still needs to be repaired; it will only remain idle if it is turned
        // off and turned on for me in Mina.
        assertEquals(RepairPhase.Pending, Rig(initial = off).also { it.start(this) }.repair.phase.value)
        val declined = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))
        assertEquals(RepairPhase.Idle, Rig(initial = off, consent = declined).also { it.start(this) }.repair.phase.value)
    }

    @Test
    fun phaseIdleAfterGivingUp() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
    }

    @Test
    fun awaitKeepsTheShortWaitWhenNothingIsBeingRepaired() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Idle)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(5_001)
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
        // Connected from the beginning: Get it immediately
        assertEquals("svc", AccessibilityRepair.awaitService(MutableStateFlow<String?>("svc"), phase, baseMs = 5_000))
    }

    @Test
    fun awaitWaitsThroughARepairThatSucceeds() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Repairing)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(12_000)
        assertFalse(wait.isCompleted)
        service.value = "svc"
        phase.value = RepairPhase.Idle
        runCurrent()
        assertEquals("svc", wait.await())
    }

    @Test
    fun awaitStopsWhenTheRepairGivesUp() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Pending)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(9_000)
        assertFalse(wait.isCompleted)
        phase.value = RepairPhase.Idle
        runCurrent()
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
    }

    @Test
    fun awaitIsCapped() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Repairing)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000, capMs = 25_000) }
        advanceTimeBy(25_001)
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
    }

    @Test
    fun awaitWithTheRealRepairEndToEnd() = runTest {
        val rig = Rig(connectAfterMs = 3_000).also { it.start(this) }
        // Service instance: connection is null
        val instance = MutableStateFlow<String?>(null)
        backgroundScope.launch { rig.service.collect { instance.value = if (it.connected) "svc" else null } }
        val wait = async { AccessibilityRepair.awaitService(instance, rig.repair.phase, baseMs = 5_000) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS)
        assertEquals("svc", wait.await())
    }
}
