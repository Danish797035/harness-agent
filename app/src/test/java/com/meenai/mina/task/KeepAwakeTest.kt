package com.meenai.mina.task

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeTest {
    private class FakeLock : WakeLockPort {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }

    @Test
    fun holdsWhileRunningAndReleasesAtEnd() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun waitingKeepsScreenOnForGraceThenReleases() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.WaitingTakeover("login", "请登录"))
        advanceTimeBy(KeepAwake.GRACE_MS - 1)
        runCurrent()
        assertTrue(lock.held)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(lock.held)

        // After the user has finished processing, the task continues to run: turn on again
        awake.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
    }

    @Test
    fun resumingWithinGraceKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(TaskGate.paused("x")))
        advanceTimeBy(KeepAwake.GRACE_MS / 2)
        awake.onState(TaskState.Running(2, ""))
        advanceTimeBy(KeepAwake.GRACE_MS)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Cancelled)
        assertFalse(lock.held)
    }

    @Test
    fun failureAndCancelRelease() = runTest {
        for (end in listOf(TaskState.Failed("x", FailKind.GaveUp), TaskState.Cancelled)) {
            val lock = FakeLock()
            val awake = KeepAwake(lock, backgroundScope)
            awake.onState(TaskState.Running(1, ""))
            awake.onState(end)
            assertFalse(lock.held)
        }
    }

    @Test
    fun noReacquireAfterClose() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.close()
        assertFalse(lock.held)
        // Late status release after the task ends cannot get the lock back.
        awake.onState(TaskState.Running(2, ""))
        assertFalse(lock.held)
    }

    // Each of the two tasks holds a copy: the first one takes the lock at the beginning and releases it
    // at the end of the last one.
    @Test
    fun refCountedAcrossRuns() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        val b = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        b.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        a.onState(TaskState.Succeeded("ok"))
        assertTrue(lock.held)
        a.close()
        assertTrue(lock.held)
        b.onState(TaskState.Cancelled)
        assertFalse(lock.held)
        b.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    /** The screen only remains on, not locked at full brightness: it is dimmed by the system as usual when the user is not operating. */
    @Suppress("DEPRECATION")
    @Test
    fun screenLockLetsTheSystemDim() {
        assertEquals(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, ScreenWakeLock.LEVEL)
    }
}
