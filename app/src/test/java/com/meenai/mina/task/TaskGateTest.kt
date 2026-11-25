package com.meenai.mina.task

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import com.meenai.mina.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGateTest {
    @get:Rule val texts = ChineseTexts()

    private val locked = MutableStateFlow(false)
    private val shellUp = MutableStateFlow(true)
    private var moveBackCalls = 0
    private var moveBackResult = true
    private val gate = TaskGate(locked, shellUp) { moveBackCalls++; moveBackResult }
    private val reported = mutableListOf<TaskState?>()

    @Test
    fun passesImmediatelyWhenNothingBlocks() = runTest {
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun nonInteractiveWaitsForUnlockThenResumesByItself() = runTest {
        locked.value = LockState.Reading(interactive = false, keyguardLocked = false).locked
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        locked.value = LockState.Reading(interactive = true, keyguardLocked = true).locked // The screen is on but still on the lock screen
        runCurrent()
        assertFalse(passed)
        locked.value = LockState.Reading(interactive = true, keyguardLocked = false).locked
        runCurrent()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    /** The user presses pause by himself: he stops before the next action of changing the screen and clicks "Continue"; pressing "Continue" previously clicked does not count. */
    @Test
    fun userPauseWaitsForContinue() = runTest {
        gate.resume() // "Continue" before pausing does not count
        gate.pause()
        assertTrue(gate.userPausedPending)
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.USER_PAUSED)), reported.last())

        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertEquals(null, reported.last())
        assertFalse(gate.userPausedPending)
    }

    @Test
    fun movedAwayPausesUntilUserContinuesThenMovesBack() = runTest {
        gate.resume() // "Continue" before pausing does not count
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), reported.last())
        assertEquals(0, moveBackCalls)

        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)

        // No longer pause after moving back
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    /** The user takes action on the borrowed page: no matter what kind of screen, stop, click "Continue" to release on the spot; the previous "Continue" does not count, and nothing is moved. */
    @Test
    fun userOnPagePausesAnyStepUntilContinue() = runTest {
        gate.onUserOnPage()
        assertTrue(gate.userOnPagePending)
        var passed = false
        launch { gate.pass({ false }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE)), reported.last())
        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertFalse(gate.userOnPagePending)
        assertEquals(0, moveBackCalls)
        // Before reaching the level, I clicked Continue: No Stop.
        gate.onUserOnPage()
        gate.resume()
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun failedMoveBackStaysPaused() = runTest {
        moveBackResult = false
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        gate.resume()
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVE_BACK_FAILED)), reported.last())

        moveBackResult = true
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun helperLossPausesUntilBackAndUserContinues() = runTest {
        shellUp.value = false
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())

        gate.resume() // The assistant has not been restored yet: continuing is invalid.
        runCurrent()
        assertFalse(passed)

        shellUp.value = true
        runCurrent()
        assertFalse(passed) // Even after restoring, you have to wait for the user to click continue.
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun movedAwayMeansOnMainAndNotOnAgentDisplay() {
        assertTrue(DeviceStateWatcher.isMovedAway(agentTop = 5, mainTop = 7, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = 7, mainTop = 3, taskId = 7))
        // The Agent closed the App by himself: it is not at the top of any screen and does not count as
        // being taken away.
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = 3, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = -1, taskId = -1))
    }

    @Test
    fun continueTappedRightAsPauseIsShownIsNotLost() = runTest {
        gate.onTaskMovedAway()
        var passed = false
        // As soon as the notification shows "Paused", the user clicks to continue: the click occurs
        // during the setWaiting callback
        launch {
            gate.pass { reported += it; if (it is TaskState.Held && moveBackCalls == 0) gate.resume() }
            passed = true
        }
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)
    }

    @Test
    fun exposesPendingMoveForImmediateNotice() {
        assertFalse(gate.movedAwayPending)
        gate.onTaskMovedAway()
        assertTrue(gate.movedAwayPending)
    }

    @Test
    fun continueTappedAfterMoveDetectedButBeforeGateCounts() = runTest {
        // After discovering that it has been moved, the notification immediately displays pause; the
        // user clicks Continue before the Agent reaches the gate.
        gate.onTaskMovedAway()
        gate.resume()
        gate.pass { reported += it }
        assertEquals(1, moveBackCalls)
        assertEquals(null, reported.last())
    }

    /** Steps on web pages and sandboxes do not require built-in assistants, and will not be moved to the home screen: only the lock screen is viewed. */
    @Test
    fun shellAndMovedAwayOnlyCheckedForPhoneSteps() = runTest {
        shellUp.value = false
        gate.onTaskMovedAway()
        gate.pass({ false }) { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
        var passed = false
        launch { gate.pass({ true }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())
        // Steps to lock screen and still block web pages
        reported.clear()
        locked.value = true
        var webPassed = false
        launch { gate.pass({ false }) { reported += it }; webPassed = true }
        runCurrent()
        assertFalse(webPassed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(webPassed)
        coroutineContext.cancelChildren()
    }
}
