package com.meenai.mina.task

import com.meenai.mina.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockStateTest {
    private var interactive = false
    private var keyguardLocked = true
    private var reads = 0

    private fun TestScope.lockState() = LockState({ reads++; LockState.Reading(interactive, keyguardLocked) }, backgroundScope)

    private fun TestScope.tick() {
        advanceTimeBy(DeviceStateWatcher.POLL_MS)
        runCurrent()
    }

    // The unlocking broadcast comes earlier than the lock screen exits: it is still locked when read,
    // and there is no broadcast after that, so you have to see the unlocking yourself.
    @Test fun unlockSeenWithoutAnotherBroadcast() = runTest {
        val lock = lockState()
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        // No reading when the screen is off, wait for SCREEN_ON
        val dark = reads
        repeat(5) { tick() }
        assertEquals(dark, reads)

        interactive = true
        lock.refresh() // SCREEN_ON or USER_PRESENT: On, the lock screen is still there
        tick()
        assertFalse(passed)
        keyguardLocked = false // Exit the lock screen interface, no broadcast
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())

        // Read and no longer read after unlocking
        val unlocked = reads
        repeat(10) { tick() }
        assertEquals(unlocked, reads)
    }

    // No one is reading this status
    @Test fun noReadsWithoutSubscribers() = runTest {
        interactive = true
        val lock = lockState()
        runCurrent()
        val start = reads
        repeat(10) { tick() }
        assertEquals(start, reads)
        assertTrue(lock.locked.value)
    }
}
