package com.meenai.mina.core.agent

import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.assembly.toolDispatcher

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("i${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
private const val DONE = "好了"
private fun finish() = com.meenai.mina.core.testing.answerReply("好了")

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopInputTest {
    private class ListSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { events += e }
    }

    private fun ask(options: String = ""","options":["同事张伟","同学张伟"]""") = call("ask_user", """{"prompt":"发给哪个张伟?"$options}""")
    private fun loop(m: FakeModel, timeoutMs: Long = 15 * 60_000L, sink: RunSink = RunSink.NONE) =
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", sink = sink, inputTimeoutMs = timeoutMs)

    /** The tool result of [c] is matched in the [i] request, and it is asserted that there is no subsequent user message. */
    private fun FakeModel.resultFor(c: Part.ToolCall, i: Int = 1): JsonObject {
        val h = requests[i].withoutRuntime()
        val at = h.indexOfFirst { m -> m.parts.any { it is Part.ToolCall && it.id == c.id } }
        val r = h[at + 1].parts.filterIsInstance<Part.ToolResult>().single()
        assertEquals(c.id, r.callId)
        assertEquals("answer is the tool result, no extra user message", at + 2, h.size)
        return Json.parseToJsonElement(r.content).jsonObject
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun answerIsToolResult() = runTest {
        val c = ask()
        val m = FakeModel(tools(c), finish(), finish())
        val l = loop(m)
        val run = async { l.run("t") }
        runCurrent() // advanceUntilIdle will push the virtual time past the query time limit
        assertEquals(TaskState.WaitingInput("发给哪个张伟?", listOf("同事张伟", "同学张伟")), l.state.value)
        assertTrue(l.answerInput("就第一个吧"))
        com.meenai.mina.core.testing.assertAnswered(DONE, run.await())
        // If the user answers and the model does nothing, then the answer must be handed in: prompt once
        // to do it (or explain why not to do it), and accept it as usual the second time.
        val nudge = com.meenai.mina.core.agent.ContextBuilder.bodyOf(m.requests[2].last()).orEmpty()
        assertTrue(nudge, "发给哪个张伟?" in nudge && "Carry it out" in nudge)
        assertEquals(3, m.requests.size)
        val r = m.resultFor(c)
        assertEquals("true", r.s("ok")); assertEquals("就第一个吧", r.s("answer"))
        assertEquals(Role.Tool, m.requests[1].withoutRuntime().last().role)
    }

    @Test fun optionTextReturnedVerbatim() = runTest {
        val c = ask()
        val m = FakeModel(tools(c), finish())
        val l = loop(m)
        val run = async { l.run("t") }
        runCurrent() // advanceUntilIdle will push the virtual time past the query time limit
        l.answerInput("同学张伟")
        run.await()
        assertEquals("同学张伟", m.resultFor(c).s("answer"))
    }

    @Test fun timeoutIsErrorNotEmptyAnswer() = runTest {
        val c = ask()
        val m = FakeModel(tools(c), finish())
        val l = loop(m, timeoutMs = 60_000)
        val run = async { l.run("t") }
        runCurrent()
        advanceTimeBy(59_000); runCurrent()
        assertTrue(l.state.value is TaskState.WaitingInput)
        advanceTimeBy(2_000); runCurrent()
        com.meenai.mina.core.testing.assertAnswered(DONE, run.await())
        val r = m.resultFor(c)
        assertEquals("false", r.s("ok")); assertEquals("no_answer", r.s("error"))
        assertEquals("No answer came within the time limit.", r.s("message")); assertEquals("true", r.s("recoverable"))
        assertEquals("false", r.s("counts_as_failure"))
        assertNull(r["answer"])
        // Answers that arrive after the timeout will not count, and the interface will accordingly tell
        // the user that they are late.
        assertFalse(l.answerInput("晚了"))
    }

    @Test fun defaultTimeoutIsFifteenMinutes() = runTest {
        val c = ask()
        val m = FakeModel(tools(c), finish())
        val l = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE")
        val run = async { l.run("t") }
        runCurrent()
        advanceTimeBy(15 * 60_000L - 1_000); runCurrent()
        assertTrue(l.state.value is TaskState.WaitingInput)
        advanceTimeBy(2_000)
        run.await()
        assertEquals("no_answer", m.resultFor(c).s("error"))
    }

    @Test fun cancelDuringWaitEndsCancelled() = runTest {
        val m = FakeModel(tools(ask()))
        val l = loop(m)
        val run = async { l.run("t") }
        advanceTimeBy(1_000); runCurrent()
        assertTrue(l.state.value is TaskState.WaitingInput)
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(TaskState.Cancelled, l.state.value)
        assertEquals(1, m.requests.size)
    }

    @Test fun answerNotWrittenToRunRecord() = runTest {
        val sink = ListSink()
        val m = FakeModel(tools(ask(options = "")), finish())
        val l = loop(m, sink = sink)
        val run = async { l.run("t") }
        runCurrent() // advanceUntilIdle will push the virtual time past the query time limit
        l.answerInput("验证码 834921")
        run.await()
        assertFalse(sink.events.toString(), "834921" in sink.events.toString())
        assertTrue(sink.events.any { it is RunEvent.ToolResult && it.tool == "ask_user" })
    }

}
