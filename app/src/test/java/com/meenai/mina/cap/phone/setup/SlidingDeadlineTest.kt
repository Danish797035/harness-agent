package com.meenai.mina.cap.phone.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidingDeadlineTest {
    private fun TestScope.deadline() = SlidingDeadline(10_000L) { testScheduler.currentTime }

    @Test
    fun endsAfterTheLengthWhenNotExtended() = runTest {
        val d = deadline()
        val never = CompletableDeferred<Unit>()
        d.extend()
        val result = async { d.await(never) }
        testScheduler.advanceTimeBy(9_999L)
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertFalse(result.await())
    }

    /** The boot page was left open for more than nine minutes before I went to the system settings: From this point on, I looked at it for another 10 minutes and didn't press the button to call it a day. */
    @Test
    fun extendingWhileWaitingCountsFromThen() = runTest {
        val d = deadline()
        val never = CompletableDeferred<Unit>()
        d.extend()
        val result = async { d.await(never) }
        testScheduler.advanceTimeBy(9_000L)
        d.extend()
        testScheduler.advanceTimeBy(9_000L)
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertFalse(result.await())
        assertEquals(19_000L, testScheduler.currentTime)
    }

    @Test
    fun finishingInTimeIsTrue() = runTest {
        val d = deadline()
        d.extend()
        val job = launch { delay(3_000L) }
        assertTrue(d.await(job))
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun neverExtendedEndsAtOnce() = runTest {
        assertFalse(deadline().await(CompletableDeferred<Unit>()))
    }
}
