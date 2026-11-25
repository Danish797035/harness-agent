package com.meenai.mina.takeover

import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.tool.HandBackOutcome
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverQueueTest {
    // Only one App is handed over to the main screen at a time: the second task is taken over after the
    // first one
    @Test fun secondWaitsForFirst() = runTest {
        val queue = TakeoverQueue()
        val portA = TakeoverControllerTest.FakePort()
        val portB = TakeoverControllerTest.FakePort()
        val a = Takeover(portA, backgroundScope, queue) { testScheduler.currentTime }
        val b = Takeover(portB, backgroundScope, queue) { testScheduler.currentTime }
        a.onState(TaskState.WaitingTakeover("login", "请登录"))
        b.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertTrue(queue.isFirst(a))
        assertEquals(1, queue.behind(a))

        assertFalse(b.handOver())
        assertEquals(Takeover.Stage.Waiting, b.stage.value)
        assertTrue(portB.log.isEmpty())

        assertTrue(a.handOver())
        assertTrue(a.handBack())
        assertTrue(queue.isFirst(b))
        assertEquals(0, queue.behind(b))
        assertTrue(b.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), portB.log)
    }

    // The takeover at the back of the queue will not be timed: it will not be judged as a timeout as
    // long as the previous user processes it; the timer will not start until it is queued to the end.
    @Test fun queuedTakeoverDoesNotTimeOutBehindFirst() = runTest {
        val queue = TakeoverQueue()
        val portA = TakeoverControllerTest.FakePort()
        val portB = TakeoverControllerTest.FakePort()
        val a = Takeover(portA, backgroundScope, queue) { testScheduler.currentTime }
        val b = Takeover(portB, backgroundScope, queue) { testScheduler.currentTime }
        a.onState(TaskState.WaitingTakeover("login", "请登录"))
        b.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertTrue(a.handOver())
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS + 1_000)
        runCurrent()
        assertEquals(Takeover.Stage.Waiting, b.stage.value)
        assertTrue(portB.results.isEmpty())

        assertTrue(a.handBack())
        runCurrent()
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS - 1_000)
        runCurrent()
        assertTrue(portB.results.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(HandBackOutcome.timeout), portB.results.map { it.outcome })
    }
}
