package com.meenai.mina.core.record

import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.FakeModel
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.assembly.toolDispatcher

class AgentLoopTimingTest {
    private var now = 0L
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink {
        override fun event(e: RunEvent) { events += e }
        override fun media(mediaId: Int, png: ByteArray) = Unit
    }
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("t${++seq}", name, args)
    private fun reply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)

    /** Each call advances the fake clock by [ms]. */
    private fun slow(inner: ChatModel, ms: Long) = object : ChatModel by inner {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            now += ms
            return inner.complete(system, messages, tools)
        }
    }

    private fun run(model: ChatModel, screen: ScreenPort = FakeScreen()) = runBlocking {
        AgentLoop(model, toolDispatcher(screen, false), "BASE", sink = sink, clock = { now }).run("查一下")
    }

    @Test fun modelLatencyRecorded() {
        run(slow(FakeModel(reply(call("phone_observe")), com.meenai.mina.core.testing.answerReply("x")), 1200))
        val replies = events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(2, replies.size)
        replies.forEach { assertEquals(1200L, it.latencyMs) }
    }

    @Test fun toolDurationRecorded() {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = FakeScreen().snapshot().also { now += 300 }
        }
        run(FakeModel(reply(call("phone_observe")), com.meenai.mina.core.testing.answerReply("x")), screen)
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        val observe = results.first { it.tool == "phone_observe" }
        assertEquals(300L, observe.durationMs)
        assertEquals("model", observe.source)
    }

    // The time spent waiting for the user to answer (ask_user) is not included in the tool's time
    // consumption: that is waiting for someone, not the slowness of Mina
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun durationMsExcludesUserWaits() = runTest {
        val m = FakeModel(reply(call("ask_user", """{"prompt":"发给谁?"}""")), com.meenai.mina.core.testing.answerReply("x"))
        val l = AgentLoop(m, toolDispatcher(FakeScreen(), false), "BASE", sink = sink, clock = { now })
        val run = async { l.run("查一下") }
        runCurrent()
        assertTrue(l.state.value is TaskState.WaitingInput)
        now += 60_000
        assertTrue(l.answerInput("张三"))
        run.await()
        val asked = events.filterIsInstance<RunEvent.ToolResult>().first { it.tool == "ask_user" }
        assertEquals(0L, asked.durationMs)
    }

    // The time spent before retrying (the failed request and backoff) is recorded as retryWaitMs alone,
    // not included in the latencyMs of this reply.
    @Test fun retryWaitSplitFromLatency() {
        var first = true
        val inner = FakeModel(com.meenai.mina.core.testing.answerReply("x"))
        val flaky = object : ChatModel by inner {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (first) {
                    first = false
                    now += 500
                    throw ModelError.RateLimited(1)
                }
                now += 1200
                return inner.complete(system, messages, tools)
            }
        }
        run(flaky)
        val r = events.filterIsInstance<RunEvent.ModelReply>().single()
        assertEquals(1200L, r.latencyMs)
        assertEquals(500L, r.retryWaitMs)
        // The arrival time includes previous retries: the user sees the answer at this time
        assertEquals(1700L, r.repliedAtMs)
    }

    // The reply with the call is not recorded until it is executed; the time when it is received is
    // recorded separately, and the first feedback is counted as it
    @Test fun replyArrivalRecordedBeforeToolTime() {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = FakeScreen().snapshot().also { now += 300 }
        }
        run(slow(FakeModel(reply(call("phone_observe")), com.meenai.mina.core.testing.answerReply("x")), 1000), screen)
        val first = events.filterIsInstance<RunEvent.ModelReply>().first()
        assertEquals(1000L, first.repliedAtMs)
        assertTrue(first.atMs >= 1300)
        assertEquals(1000L, RunMetrics.of(events)!!.firstFeedbackMs)
    }

    // Failed ending with category
    @Test fun failedEndCarriesItsKind() {
        val broken = object : ChatModel by FakeModel(com.meenai.mina.core.testing.answerReply("x")) {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = throw ModelError.Auth()
        }
        run(broken)
        val end = events.filterIsInstance<RunEvent.TaskEnded>().single()
        assertEquals("failed", end.outcome)
        assertEquals("model", end.kind)
    }
}
