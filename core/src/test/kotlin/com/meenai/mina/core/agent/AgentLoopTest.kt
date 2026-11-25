package com.meenai.mina.core.agent

import com.meenai.mina.core.guard.DIAGNOSTIC_VERBS
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.testing.ScriptedScreen
import com.meenai.mina.core.testing.Screen
import com.meenai.mina.core.testing.button
import com.meenai.mina.core.testing.label
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
import com.meenai.mina.core.testing.verb
import com.meenai.mina.core.assembly.toolDispatcher

/** Reply by script; the script item is the reply or exception to be thrown. */
/** When viewing the model, the positioning accuracy will be verified by default ([groundingVerified]); if it is not verified, it will be measured separately. */
class FakeModel(vararg script: Any, private val repeatLast: Boolean = false, contextTokens: Int = 128_000, groundingVerified: Boolean = true) : ChatModel {
    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = contextTokens, reasoning = false, groundingVerified = groundingVerified)
    private val script = script.toMutableList()
    val requests = mutableListOf<List<Message>>()
    val offeredTools = mutableListOf<List<String>>()
    var system: String? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        this.system = system
        requests += messages
        offeredTools += tools.map { it.name }
        val next = if (repeatLast && script.size == 1) script[0] else script.removeAt(0)
        if (next is Throwable) throw next
        return next as ModelReply
    }
}

private var callSeq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("call${++callSeq}", name, args)
private fun reply(vararg parts: Part) = ModelReply(parts.toList(), StopReason.ToolUse, null)
private fun tools(vararg calls: Part.ToolCall) = reply(*calls)
private const val DONE = "done"
private fun finish(answer: String = DONE) = com.meenai.mina.core.testing.answerReply(answer)

private fun Message.results() = parts.filterIsInstance<Part.ToolResult>()
private fun Part.ToolResult.json() = Json.parseToJsonElement(content).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
/** Retries without jitter: The wait is exactly the upper limit of the backoff, and the virtual time can be asserted. */
private val FIXED_RETRY = RetryPolicy(jitter = { 1.0 })

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {
    /** There is only a mobile phone, and the system prompts after opening a mobile phone group: the entrance of several groups, the rules given to the user, and a mobile phone section (remove the lines of tools that are not provided). */
    private fun phoneOpenedPrompt(base: String) = listOf(
        base, com.meenai.mina.core.capability.PromptEnv(setOf("phone_launch_app", "phone_set_alarm"), false).gate(SystemPrompt.read("groups")), SystemPrompt.read("handover"),
        SystemPrompt.read("phone").lines().filterNot { "<!-- needs: phone_open_link -->" in it || "<!-- needs: phone_find_visually -->" in it }.joinToString("\n"),
    ).joinToString("\n\n")

    private fun loop(
        model: ChatModel, screen: FakeScreen = FakeScreen(), maxSteps: Int = 60, vision: Boolean = false, sink: RunSink = RunSink.NONE,
        gate: suspend (setWaiting: (TaskState?) -> Unit) -> Unit = {},
    ) = AgentLoop(model, toolDispatcher(screen, vision), "BASE", maxSteps, gate, sink = sink, retry = FIXED_RETRY)

    @Test fun discussionToolProposalIsReplacedByDirectAnswer() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish("可以，先讨论具体流程。"))
        val judge = FakeModel(finish("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("以后可以帮我把内容同步到公众号并发送，对吧？")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(2, main.requests.size)
        assertTrue(main.offeredTools.first().contains("phone_list_apps"))
        assertTrue(main.offeredTools.last().isEmpty())
        assertTrue(events.none { it is RunEvent.ToolResult })
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "intent_gate" && it.detail == "Discuss" })
    }

    // If the intention cannot be determined (neither the judgment model nor the master model gives one
    // of the three words), follow the original decision of the master model and do not change it to a
    // written answer.
    @Test fun unclearIntentKeepsProposedTool() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish("我觉得是要操作"), finish())
        val judge = FakeModel(finish("maybe"))
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("帮我看看装了哪些 App")
        assertTrue(end is TaskState.Succeeded)
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "intent_gate" && it.detail == "unavailable" })
        assertTrue(events.any { it is RunEvent.ToolResult })
        assertEquals(1, judge.requests.size)
    }

    @Test fun currentLookupStillUsesTool() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish())
        val judge = FakeModel(finish("READ"))
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("现在看看装了哪些 App")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(2, main.requests.size)
        assertTrue(events.any { it is RunEvent.ToolResult && it.tool == "phone_list_apps" })
    }

    @Test fun discussionSkipsMatchingReplayBeforeAnyAction() = runTest {
        val goal = "以后打开备忘录看看，对吧？"
        val route = com.meenai.mina.core.replay.Route("discussion", goal, 0, emptyList(), emptyMap(), emptyList(), lastUsedMs = 0)
        val store = object : com.meenai.mina.core.replay.RouteStore {
            override fun all() = listOf(route)
            override fun put(r: com.meenai.mina.core.replay.Route) = Unit
            override fun remove(id: String) = Unit
            override fun clear() = Unit
            override fun count() = 1
        }
        val picker = FakeModel(finish("{\"route\":1,\"slots\":[]}"))
        val main = FakeModel(finish("可以先讨论。"))
        val judge = FakeModel(finish("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            routes = com.meenai.mina.core.replay.RouteMatcher(picker, store, null),
            flags = SpeedFlags(replay = true),
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run(goal)
        assertTrue(picker.requests.isEmpty())
        assertTrue(events.none { it is RunEvent.Replay || it is RunEvent.ToolResult })
        assertEquals(1, main.requests.size)
    }

    @Test fun phoneRunRequeriesAcrossScreensAndRecordsEachAction() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen("com.example.app", listOf(button("@e1", "群聊"))),
            Screen("com.example.app", listOf(button("@e1", "消息"))),
            Screen("com.example.app", listOf(label("@e1", "完成"))),
        ))
        var screenshots = 0
        val port = object : ScreenPort by screen {
            override suspend fun screenshotPng(): ByteArray? { screenshots++; return byteArrayOf(1) }
        }
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(reply(call("phone_run", """{"commands":[{"op":"tap","targetText":"群聊"},{"op":"tap","targetText":"消息"}]}""")), finish())
        val end = AgentLoop(model, toolDispatcher(port, inlineScreen = true, recordFrames = true), "BASE", sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run("打开消息")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(2, model.requests.size)
        assertEquals(2, screen.calls.size)
        assertEquals(0, screenshots)
        assertEquals(2, events.filterIsInstance<RunEvent.ToolResult>().count { it.source == "script" && it.tool == "phone_tap" })
        assertEquals("true", model.requests[1].withoutRuntime().last().results().single().json().s("ok"))
    }

    @Test fun lightStepsOffAlwaysMain() = runTest {
        val main = FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish())
        val light = FakeModel()
        val r = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", router = ModelRouter(main, light)).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(0, light.requests.size)
        assertEquals(4, main.requests.size)
    }

    @Test fun inlineScreenPromptOnlyWhenFlagOn() = runTest {
        val off = FakeModel(reply(call("phone_observe")), finish())
        AgentLoop(off, toolDispatcher(FakeScreen()), SystemPrompt.identity()).run("t")
        assertEquals(phoneOpenedPrompt(SystemPrompt.identity()), off.system)
        assertTrue("You do not need to observe after every" !in off.system!!)
        // There is no screen when the mobile phone set is still stored, so there is no instructions for
        // the action to come with the screen.
        val collapsed = FakeModel(finish())
        AgentLoop(collapsed, toolDispatcher(FakeScreen(), inlineScreen = true), SystemPrompt.identity(), flags = SpeedFlags(inlineScreen = true)).run("t")
        assertTrue("You do not need to observe after every" !in collapsed.system!!)
        val on = FakeModel(reply(call("phone_observe")), finish())
        AgentLoop(on, toolDispatcher(FakeScreen(), inlineScreen = true), SystemPrompt.identity(),
            flags = SpeedFlags(inlineScreen = true)).run("t")
        assertTrue(on.system!!.startsWith(SystemPrompt.identity()))
        assertTrue("already includes the screen after the action\n  (`screen`: only elements that appeared, disappeared or changed" in on.system!!)
        assertTrue("You do not need to observe after every\n  action" in on.system!!)
    }

    @Test fun stuckGuardSeesInlineScreens() = runTest {
        // Without observing, if you click the same place repeatedly, the screen will remain unchanged:
        // you can also recognize that there is no progress by relying on the screen displayed by the
        // action results.
        val m = FakeModel(*Array(8) { reply(call("phone_back")) }, finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), inlineScreen = true), "BASE", flags = SpeedFlags(inlineScreen = true)).run("t")
        val seen = m.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(seen.any { "no_progress" in it })
        // When the switch is turned off and the observe: action reports its own effect (no_effect), you
        // can also recognize whether there is any progress.
        val off = FakeModel(*Array(8) { reply(call("phone_back")) }, finish())
        AgentLoop(off, toolDispatcher(FakeScreen()), "BASE").run("t")
        val offSeen = off.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(offSeen.any { "no_progress" in it })
        assertTrue(offSeen.all { "\"kind\":\"no_effect\"" in it || "ok\":true" !in it })
    }

    @Test fun visibleScreensListsUnprunedReads() {
        val calls = listOf(Part.ToolCall("a", "phone_observe", "{}"), Part.ToolCall("b", "phone_tap", "{}"), Part.ToolCall("c", "phone_back", "{}"), Part.ToolCall("d", "phone_observe", "{}"))
        val results = listOf(
            Part.ToolResult("a", """{"ok":true,"snapshotId":"s1","elements":"${HistoryPruner.OMITTED}"}""", false),
            Part.ToolResult("b", """{"ok":true,"screen":{"snapshotId":"s2","full":false,"added":[]}}""", false),
            Part.ToolResult("c", """{"ok":true,"screen":"${HistoryPruner.OMITTED}"}""", false),
            Part.ToolResult("d", """{"ok":true,"snapshotId":"s4","elements":[]}""", false),
        )
        val h = calls.zip(results).flatMap { (c, r) -> listOf(Message(Role.Assistant, listOf(c), Author.Mina), Message(Role.Tool, listOf(r), Author.Mina)) }
        assertEquals(setOf("s2", "s4"), visibleReads(h))
    }

    @Test fun batchCountsAsOneStepButEachActionGuarded() = runTest {
        val backs = { n: Int -> call("do_steps", """{"steps":[${List(n) { """{"tool":"phone_back","args":{}}""" }.joinToString(",")}]}""") }
        val m = FakeModel(reply(backs(3)), finish())
        val r = AgentLoop(m, toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true) }), "BASE", maxSteps = 2, flags = SpeedFlags(batchSteps = true)).run("t")
        // The entire batch only counts one step towards the upper limit of steps.
        assertTrue(r is TaskState.Succeeded)
        // Follow the group with the screen multiple steps at a time: mobile phones will not be given
        // when the group is closed, only after the mobile phone movements in the batch are unfolded
        assertTrue("do_steps" !in m.offeredTools.first())
        assertTrue("do_steps" in m.offeredTools[1])
        val result = m.requests[1].withoutRuntime().last().results().single().json()
        assertEquals("true", result.s("ok"))
        // The same actions can be continued to be executed even if they are accumulated across batches.
        val m2 = FakeModel(reply(backs(5)), reply(backs(5)), reply(backs(5)), finish())
        val r2 = AgentLoop(m2, toolDispatcher(FakeScreen().also { it.result = ActionResult.Ok(changed = true) }), "BASE", flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue(r2 is TaskState.Succeeded)
        assertEquals(4, m2.requests.size)
    }

    @Test fun batchSubStepsNoticeRepeatedInputWithoutUiProgress() = runTest {
        val typing = call("do_steps", """{"steps":[{"tool":"phone_type_text","args":{"text":"咖啡"}},{"tool":"phone_back","args":{}}]}""")
        val again = call("do_steps", """{"steps":[{"tool":"phone_type_text","args":{"text":"咖啡"}},{"tool":"phone_back","args":{}}]}""")
        val m = FakeModel(reply(typing), reply(again), finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue(m.requests[1].withoutRuntime().last().results().single().json()["advice"] == null)
        assertTrue(m.requests[2].withoutRuntime().last().results().single().json()["advice"] != null)
    }

    @Test fun doStepsAbsentWhenFlagOff() = runTest {
        val m = FakeModel(reply(call("do_steps", """{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back","args":{}}]}""")), finish())
        val s = FakeScreen()
        AgentLoop(m, toolDispatcher(s), SystemPrompt.identity()).run("t")
        assertTrue("do_steps" !in m.offeredTools.first())
        assertTrue("do_steps" !in m.system!!)
        assertEquals("unknown_tool", m.requests[1].withoutRuntime().last().results().single().json().s("error"))
        assertTrue(s.calls.isEmpty())
        val on = FakeModel(reply(call("phone_observe")), finish())
        AgentLoop(on, toolDispatcher(FakeScreen()), SystemPrompt.identity(), flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue("give them in one do_steps call" in on.system!!)
    }

    @Test fun completesWithTextAnswer() = runTest {
        val m = FakeModel(reply(Part.Thinking("t"), Part.Text("看一下"), call("phone_observe")), finish("找到了"))
        val l = loop(m)
        val r = l.run("帮我查")
        com.meenai.mina.core.testing.assertAnswered("找到了", r)
        assertEquals(r, l.state.value)
        assertEquals(phoneOpenedPrompt("BASE"), m.system)
        val second = m.requests[1].withoutRuntime()
        assertEquals(Role.User, second[0].role)
        assertEquals("帮我查", (second[0].parts.single() as Part.Text).text)
        // The assistant replies to the history (including Thinking and ToolCall) as it is, followed by a
        // Tool message.
        assertEquals(Role.Assistant, second[1].role)
        assertTrue(second[1].parts.any { it is Part.Thinking })
        assertEquals(Role.Tool, second[2].role)
        assertEquals("true", second[2].results().single().json().s("ok"))
    }

    @Test fun opaqueToolCallIsReplayed() = runTest {
        val c = Part.ToolCall("x1", "phone_observe", "{}", opaque = "sig")
        val m = FakeModel(reply(c), finish())
        loop(m).run("t")
        assertEquals(c, m.requests[1][1].parts.single())
        assertEquals("x1", m.requests[1][2].results().single().callId)
    }

    @Test fun stopsAtMaxSteps() = runTest {
        val m = FakeModel(tools(call("wait", """{"seconds":0.2}""")), repeatLast = true)
        val r = loop(m, maxSteps = 3).run("t")
        assertTrue(r is TaskState.Failed && "3" in r.reason && r.kind == FailKind.StepLimit)
        // 3 steps + final round of closing (the model still wants to wait, so it doesn’t count)
        assertEquals(4, m.requests.size)
    }

    private fun waits(n: Int) = (1..n).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray()

    @Test fun finalTurnOffersOnlyReportFailure() = runTest {
        val m = FakeModel(*waits(3), tools(call("end_not_done", """{"message":"查过搜索和主菜单,没有这个菜单"}""")))
        val r = loop(m, maxSteps = 3).run("t")
        assertEquals(TaskState.Failed("查过搜索和主菜单,没有这个菜单", FailKind.GaveUp), r)
        assertEquals(listOf("end_not_done"), m.offeredTools.last())
        assertTrue(m.offeredTools.first().size > 2)
        val note = (m.requests.last().withoutRuntime().last().parts.single() as Part.Text).text
        assertTrue(note, "out of steps" in note && "in text" in note)
    }

    @Test fun finalTurnTextIsTheAnswer() = runTest {
        com.meenai.mina.core.testing.assertAnswered("查过了,没找到", loop(FakeModel(*waits(3), finish("查过了,没找到")), maxSteps = 3).run("t"))
        // The last round is empty talk or you want to adjust other tools: the number of steps is
        // exhausted.
        val empty = loop(FakeModel(*waits(3), reply(Part.Text(""))), maxSteps = 3).run("t")
        assertTrue("$empty", empty is TaskState.Failed && empty.kind == FailKind.StepLimit)
    }

    @Test fun finalTurnUnreadValueIsLabelledNotRetried() = runTest {
        // There are no steps to return in the last round: unread values are directly marked as
        // unconfirmed
        val m = FakeModel(*waits(3), finish("结果是 3.6"))
        val r = loop(m, maxSteps = 3).run("t")
        assertEquals(listOf("3.6"), (r as TaskState.Succeeded).unconfirmed)
        assertEquals(4, m.requests.size)
    }

    // A reply without a call is the answer, and the task is over; no need to remind again.
    @Test fun plainTextEndsTheTask() = runTest {
        val m = FakeModel(reply(Part.Text("我想想")), finish())
        com.meenai.mina.core.testing.assertAnswered("我想想", loop(m).run("t"))
        assertEquals(1, m.requests.size)
    }

    @Test fun emptyTextIsNudgedOnceThenFails() = runTest {
        val m = FakeModel(reply(Part.Text("")), reply(Part.Text("  ")))
        val r = loop(m).run("t")
        assertEquals(StopRules.noAnswer(), r)
        val nudge = m.requests[1].withoutRuntime().last()
        assertEquals(Role.User, nudge.role)
        assertTrue("empty" in (nudge.parts.single() as Part.Text).text)
    }

    @Test fun toolTurnResetsEmptyNudge() = runTest {
        val m = FakeModel(reply(Part.Text("")), tools(call("phone_observe")), reply(Part.Text("")), finish())
        com.meenai.mina.core.testing.assertAnswered(DONE, loop(m).run("t"))
    }

    @Test fun onlyFirstToolCallExecuted() = runTest {
        val s = FakeScreen()
        val m = FakeModel(tools(call("phone_tap", """{"x":500,"y":500}"""), call("phone_back")), finish())
        loop(m, s).run("t")
        assertEquals(listOf("tapXY 540 1201"), s.calls)
        val results = m.requests[1].withoutRuntime().last().results()
        assertEquals(2, results.size)
        assertEquals("parallel_tool_call_skipped", results[1].json().s("error"))
        assertTrue(results[1].isError)
        assertFalse(results[0].isError)
    }

    @Test fun unknownToolReturnsErrorToModel() = runTest {
        val m = FakeModel(tools(call("fly")), finish())
        com.meenai.mina.core.testing.assertAnswered(DONE, loop(m).run("t"))
        val r = m.requests[1].withoutRuntime().last().results().single()
        assertTrue(r.isError)
        assertEquals("unknown_tool", r.json().s("error"))
    }

    @Test fun authErrorStopsWithReason() = runTest {
        val r = loop(FakeModel(ModelError.Auth())).run("t")
        assertTrue(r is TaskState.Failed && "API key" in r.reason && r.kind == FailKind.Model)
    }

    @Test fun otherFatalModelErrorsFailWithoutRetry() = runTest {
        for (e in listOf(ModelError.QuotaExceeded(), ModelError.ModelNotFound(), ModelError.ContentRefused())) {
            val m = FakeModel(e, finish())
            val r = loop(m).run("t")
            assertTrue("$e", r is TaskState.Failed && (r as TaskState.Failed).reason.isNotBlank())
            assertEquals(1, m.requests.size)
        }
    }

    @Test fun rateLimitedFollowsRetryAfter() = runTest {
        val m = FakeModel(ModelError.RateLimited(5000), finish())
        com.meenai.mina.core.testing.assertAnswered(DONE, loop(m).run("t"))
        assertEquals(5000, currentTime)
    }

    // Temporary failures (current limiting, network, 5xx) share the same number of retries, with
    // exponential backoff, etc.: 1 s, 2 s, 4 s
    @Test fun transientErrorsShareBoundedBackoff() = runTest {
        val m = FakeModel(ModelError.RateLimited(null), ModelError.Network(), ModelError.Server(503), finish())
        com.meenai.mina.core.testing.assertAnswered(DONE, loop(m).run("t"))
        assertEquals(4, m.requests.size)
        assertEquals(7000, currentTime)

        val always = FakeModel(ModelError.Network(), ModelError.Network(), ModelError.Network(), ModelError.Network(), finish())
        val r = loop(always).run("t")
        assertTrue("$r", r is TaskState.Failed && r.kind == FailKind.Model)
        assertEquals(4, always.requests.size)
    }

    @Test fun serverErrorsRetryOnly5xx() = runTest {
        val down = FakeModel(ModelError.Server(500), finish())
        com.meenai.mina.core.testing.assertAnswered(DONE, loop(down).run("t"))
        assertEquals(2, down.requests.size)

        val bad = FakeModel(ModelError.Server(400), finish())
        val r = loop(bad).run("t")
        assertTrue("$r", r is TaskState.Failed && "400" in r.reason)
        assertEquals(1, bad.requests.size)
    }

    // Every time you retry, the running record is entered: the fault name, the number of times, and how
    // long you waited.
    @Test fun retriesAreRecorded() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
            override fun media(mediaId: Int, png: ByteArray) = Unit
        }
        loop(FakeModel(ModelError.Server(502), ModelError.RateLimited(1500), finish()), sink = sink).run("t")
        val retries = events.filterIsInstance<RunEvent.State>().filter { it.name == "model_retry" }.map { it.detail }
        assertEquals(listOf("main server_502 #1 1000ms", "main rate_limited #2 1500ms"), retries)
    }

    // Canceling the task while waiting: no more requests will be sent, and the task will end with Cancel
    @Test fun cancelDuringBackoff() = runTest {
        val m = FakeModel(ModelError.Server(503), finish())
        val l = loop(m)
        val run = async { l.run("t") }
        runCurrent()
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(1, m.requests.size)
    }

    @Test fun unexpectedModelExceptionFails() = runTest {
        val l = loop(FakeModel(IllegalStateException("boom")))
        assertEquals(TaskState.Failed("Model call error: boom", FailKind.Model), l.run("t"))
        assertEquals(TaskState.Failed("Model call error: boom", FailKind.Model), l.state.value)
    }

    @Test fun rateLimitWaitIsCapped() = runTest {
        loop(FakeModel(ModelError.RateLimited(600_000), finish())).run("t")
        assertEquals(30_000, currentTime)
    }

    // The streaming reply connection is disconnected before the end: the reply has not been entered into
    // the history and the tool has not been executed, so it is resent as it is; the half-word on the
    // interface is cleared before retrying.
    @Test fun streamCutIsRetriedWithoutKeepingPartialText() = runTest {
        lateinit var l: AgentLoop
        val previews = mutableListOf<String?>()
        val requests = mutableListOf<List<Message>>()
        fun preview() = (l.state.value as? TaskState.Running)?.preview
        val model = object : StreamingChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("应走流式")
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                requests += messages
                if (requests.size == 1) {
                    onText("半句")
                    previews += preview()
                    throw ModelError.StreamCut()
                }
                previews += preview()
                onText(DONE)
                return finish()
            }
        }
        l = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", retry = FIXED_RETRY, streamReplies = true)
        com.meenai.mina.core.testing.assertAnswered(DONE, l.run("t"))
        assertEquals(listOf("半句", null), previews)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals(1000, currentTime)
    }

    @Test fun networkErrorRetriesAfterOneSecond() = runTest {
        val m = FakeModel(ModelError.Network(), finish())
        com.meenai.mina.core.testing.assertAnswered(DONE, loop(m).run("t"))
        assertEquals(1000, currentTime)
    }

    @Test fun reportFailureEndsFailedWithMessage() = runTest {
        val r = loop(FakeModel(tools(call("end_not_done", """{"message":"没有库存"}""")))).run("t")
        assertEquals(TaskState.Failed("没有库存", FailKind.GaveUp), r)
    }

    @Test fun cancelEndsWithCancelled() = runTest {
        val l = loop(FakeModel(tools(call("hand_to_user", """{"reason":"other","message":"m"}"""))))
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(TaskState.Cancelled, l.state.value)
    }

    @Test fun cancelDuringModelCall() = runTest {
        val hang = object : ChatModel {
            override val capabilities = Capabilities(true, false, 1000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = awaitCancellation()
        }
        val l = loop(hang)
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
    }

    @Test fun gateIsCalledBeforeWritingTools() = runTest {
        val s = FakeScreen()
        val open = CompletableDeferred<Unit>()
        var gates = 0
        val m = FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"x":1,"y":1}""")), finish())
        val l = loop(m, s) { gates++; open.await() }
        val run = async { l.run("t") }
        advanceUntilIdle()
        // observe does not pass through the gate; the gate hangs before tap, and tap has not yet been
        // executed.
        assertEquals(1, gates)
        assertTrue(s.calls.isEmpty())
        open.complete(Unit)
        com.meenai.mina.core.testing.assertAnswered(DONE, run.await())
        assertEquals(1, s.calls.size)
        assertEquals(1, gates) // finish is not a write operation
    }

    @Test fun gateReportsWaitingUnlockThenRunning() = gateReports(TaskState.Held(GateReason("waiting_unlock")), clearWithNull = true)

    @Test fun gateReportsPausedAndRunningIsRestoredWithoutNull() = gateReports(TaskState.Held(GateReason("paused", "手机被拿走", resumable = true)), clearWithNull = false)

    /** During the suspension period, the external status is reported by the gate; after release (regardless of whether null is passed or not), Running has been resumed when executing the tool. */
    private fun gateReports(waiting: TaskState, clearWithNull: Boolean) = runTest {
        val open = CompletableDeferred<Unit>()
        var stateDuringTap: TaskState? = null
        lateinit var l: AgentLoop
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                stateDuringTap = l.state.value
                return ActionResult.Ok()
            }
        }
        val m = FakeModel(reply(Part.Text("点一下"), call("phone_tap", """{"x":1,"y":1}""")), finish())
        l = AgentLoop(m, toolDispatcher(screen), "B", gate = { setWaiting ->
            setWaiting(waiting)
            open.await()
            if (clearWithNull) setWaiting(null)
        })
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertEquals(waiting, l.state.value)
        assertNull(stateDuringTap)
        open.complete(Unit)
        com.meenai.mina.core.testing.assertAnswered(DONE, run.await())
        assertEquals(TaskState.Running(1, "点一下", "phone_tap", narration = "点一下"), stateDuringTap)
    }

    @Test fun repeatedTapWithProgressCanExceedOldStepLimit() = runTest {
        // When the screen continues to change, the same action can exceed the old limit of 60 steps
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true) }
        val m = FakeModel(*(1..65).map { tools(call("phone_tap", """{"x":105,"y":106}""")) }.toTypedArray(), finish())
        loop(m, s, maxSteps = Int.MAX_VALUE).run("t")
        assertEquals(65, s.calls.size)
    }

    @Test fun typingTheSameQueryAgainSuggestsReportFailure() = runTest {
        val m = FakeModel(
            tools(call("phone_type_text", """{"text":"回溯"}""")), tools(call("phone_type_text", """{"text":"时光"}""")),
            tools(call("phone_type_text", """{"text":" 回溯 ","ref":"@e2","snapshotId":"s9"}""")), finish(),
        )
        loop(m).run("t")
        assertNull(m.requests[1].withoutRuntime().last().results().single().json()["advice"])
        assertNull(m.requests[2].withoutRuntime().last().results().single().json()["advice"])
        val again = m.requests[3].withoutRuntime().last().results().single().json()
        assertTrue(again.s("advice"), "回溯" in again.s("advice") && "end_not_done" in again.s("advice"))
    }

    @Test fun sameQueryInAnotherAppIsNotARepeat() = runTest {
        val pkgs = ArrayDeque(listOf("com.a", "com.b"))
        val s = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s${pkgs.size}", pkgs.removeFirst(), emptyList(), 1080, 2400)
        }
        val m = FakeModel(
            tools(call("phone_observe")), tools(call("phone_type_text", """{"text":"天气"}""")),
            tools(call("phone_observe")), tools(call("phone_type_text", """{"text":"天气"}""")), finish(),
        )
        AgentLoop(m, toolDispatcher(s), "BASE").run("t")
        assertNull(m.requests[4].withoutRuntime().last().results().single().json()["advice"])
    }

    @Test fun repeatedPasswordIsNeverEchoedInAdvice() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(secret = true) }
        val m = FakeModel(tools(call("phone_type_text", """{"text":"pw123456"}""")), tools(call("phone_type_text", """{"text":"pw123456"}""")), finish())
        loop(m, s).run("t")
        assertNull(m.requests[2].withoutRuntime().last().results().single().json()["advice"])
    }

    @Test fun progressCheckSuggestsGivingUpHonestlyHalfwayThrough() = runTest {
        // Different waits for each step will not trigger duplicate protection
        val m = FakeModel(*(1..20).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray())
        loop(m, maxSteps = 20).run("t")
        fun notes(i: Int) = m.requests[i].filter { it.role == Role.User }.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        // requests[i] is a request issued at step i+1: there is no reminder before step 10 is used, and
        // it appears once when steps 10 and 15 are used up.
        assertTrue(notes(9).none { "end_not_done" in it })
        val half = notes(10).filter { "end_not_done" in it }
        assertEquals(1, half.size)
        assertTrue(half.single(), "10 of 20" in half.single())
        assertEquals(1, notes(14).count { "end_not_done" in it })
        assertEquals(2, notes(15).count { "end_not_done" in it })
    }

    @Test fun giveUpRemindersTellAnAnswerFromAFailure() = runTest {
        // The desired result cannot be obtained without the function, item or value to be used; the only
        // question is "Is it available?", and the App's search clearly says that "No" is the answer.
        // Answer directly and quote the original text you read.
        fun separates(s: String) = "feature" in s && "end_not_done" in s && "yes/no question about whether something exists" in s && "definitive" in s
        val taps = mutableListOf<Any>()
        repeat(6) { taps += tools(call("phone_tap", """{"x":105,"y":106}""")) }
        taps += finish()
        val m = FakeModel(*taps.toTypedArray())
        loop(m, FakeScreen().apply { result = ActionResult.Ok(changed = true) }).run("t")
        assertNull(m.requests[6].withoutRuntime().last().results().single().json()["advice"])

        val typed = FakeModel(tools(call("phone_type_text", """{"text":"张三"}""")), tools(call("phone_type_text", """{"text":"张三"}""")), finish())
        loop(typed).run("t")
        assertNull(typed.requests[2].withoutRuntime().last().results().single().json()["advice"])

        val w = FakeModel(*(1..11).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray(), finish())
        loop(w, maxSteps = 20).run("t")
        val check = w.requests[10].filter { it.role == Role.User }.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
            .single { "Progress check" in it }
        assertTrue(check, separates(check))
    }

    @Test fun unchangedElementListTripsNoProgress() = runTest {
        // FakeScreen returns the same element every time: after the action, the screen remains unchanged
        // and there is no progress.
        var i = 0
        val script = mutableListOf<Any>(tools(call("phone_observe")))
        repeat(12) {
            script += tools(call("phone_tap", """{"x":${(i++) * 80},"y":10}"""))
            script += tools(call("phone_observe"))
        }
        val r = loop(FakeModel(*script.toTypedArray())).run("t")
        assertTrue("$r", r is TaskState.Failed && "no_progress" in r.reason)
    }

    @Test fun screenshotsFollowToolResultsWhenVisionOn() = runTest {
        val m = FakeModel(tools(call("phone_observe")), finish())
        loop(m, vision = true).run("t")
        val msgs = m.requests[1]
        assertEquals(Role.Tool, msgs[2].role)
        assertEquals(Role.User, msgs[3].role)
        assertTrue(msgs[3].parts.any { it is Part.Image })

        val noVision = FakeModel(tools(call("phone_observe")), finish())
        loop(noVision).run("t")
        assertEquals(3, noVision.requests[1].withoutRuntime().size)
    }

    @Test fun recalledScreenshotsAreSentAsImagesAndCountAsNew() = runTest {
        val recall = call("view_past_screens", """{"ids":[1]}""")
        val m = FakeModel(*Array(4) { tools(call("phone_observe")) }, tools(recall), finish())
        loop(m, vision = true).run("t")
        val msgs = m.requests[5].withoutRuntime()
        val at = msgs.indexOfFirst { it.parts.any { p -> p is Part.ToolResult && p.callId == recall.id } }
        val after = msgs[at + 1]
        assertEquals(Role.User, after.role)
        assertEquals(1, after.parts.count { it is Part.Image })
        assertTrue(after.parts.any { it is Part.Text && "earlier" in it.text })
        // Looking back, the reissued picture is the latest picture, so keep it; the crop line has not
        // moved forward (5 round trips), and the previous screen reading pictures are also there.
        assertEquals(4, msgs.take(at).sumOf { msg -> msg.parts.count { it is Part.Image } })

        val text = FakeModel(tools(call("phone_observe")), tools(call("view_past_screens", """{"ids":[1]}""")), finish())
        loop(text).run("t")
        val last = text.requests[2]
        assertTrue(last.none { msg -> msg.parts.any { it is Part.Image } })
        assertTrue("elementsText" in last.withoutRuntime().last().results().single().content)
        // Review is a read-only diagnostic tool and does not count as an action.
        assertTrue(verb("view_past_screens") in DIAGNOSTIC_VERBS)
    }

    @Test fun runningStateCarriesStepAndNote() = runTest {
        val seen = mutableListOf<TaskState>()
        lateinit var l: AgentLoop
        val inner = FakeModel(reply(Part.Text("打开设置"), call("phone_observe")), finish())
        val spy = object : ChatModel by inner {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                seen += l.state.value
                return inner.complete(system, messages, tools)
            }
        }
        l = AgentLoop(spy, toolDispatcher(FakeScreen()), "B")
        l.run("t")
        assertEquals(TaskState.Running(1, ""), seen[0])
        assertEquals(TaskState.Running(2, "打开设置"), seen[1])
    }

    private fun distinctWaits(n: Int, from: Int = 1) = (from until from + n).map { tools(call("wait", """{"seconds":${it / 10.0}}""")) }

    @Test fun contextTooLongRetriesOnceCompressed() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val script = distinctWaits(10) + ModelError.ContextTooLong() + distinctWaits(1, from = 11) + finish()
        val m = FakeModel(*script.toTypedArray())
        val r = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", 60, sink = sink).run("t")
        com.meenai.mina.core.testing.assertAnswered(DONE, r)
        // The 11th call reported an overlength error. Try again after pressing harder: keep the last 6
        // round trips, and fold the previous 4 times into a summary.
        assertEquals(13, m.requests.size)
        assertEquals(21, m.requests[10].withoutRuntime().size)
        val retried = m.requests[11].withoutRuntime()
        assertEquals(1 + 1 + 6 * 2, retried.size)
        val summary = retried[1].plainText()
        assertTrue(summary, summary.startsWith(ContextCompressor.HEADER) && "Step 4 wait(0.4) → applied" in summary && "Step 5 " !in summary)
        // Only append after that: the compressed result is at the front as it is, and one more round
        // trip is at the back.
        assertEquals(1 + 1 + 7 * 2, m.requests[12].withoutRuntime().size)
        assertEquals(retried, m.requests[12].withoutRuntime().take(retried.size))
        assertEquals(listOf(4), events.filterIsInstance<RunEvent.Compressed>().map { it.foldedSteps })

        // If it exceeds the pressure and is too long, it will fail and will not be retried.
        val again = FakeModel(*distinctWaits(10).toTypedArray(), ModelError.ContextTooLong(), ModelError.ContextTooLong())
        val failed = loop(again).run("t")
        assertTrue(failed is TaskState.Failed && failed.kind == FailKind.Model)
        assertEquals(12, again.requests.size)
    }

    @Test fun secondContextTooLongAfterAggressiveCompactionRecompacts() = runTest {
        // After compressing it hard, I ran a few steps and it became too long again: I compressed it
        // hard again from the complete history and sent it again, and the task was completed as usual.
        val script = distinctWaits(10) + ModelError.ContextTooLong() + distinctWaits(4, from = 11) + ModelError.ContextTooLong() +
            distinctWaits(1, from = 15) + finish()
        val m = FakeModel(*script.toTypedArray())
        val r = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", 60).run("t")
        com.meenai.mina.core.testing.assertAnswered(DONE, r)
        val overflowed = m.requests[15].withoutRuntime()
        val retried = m.requests[16].withoutRuntime()
        assertTrue("${retried.size} < ${overflowed.size}", retried.size < overflowed.size)
        val summary = retried[1].plainText()
        assertTrue(summary, summary.startsWith(ContextCompressor.HEADER) && "Step 8 " in summary && "Step 9 " !in summary)
    }

    @Test fun contextTooLongWithNothingToFoldFailsWithoutResending() = runTest {
        val m = FakeModel(*distinctWaits(3).toTypedArray(), ModelError.ContextTooLong(), finish())
        val r = loop(m).run("t")
        assertTrue(r is TaskState.Failed && r.kind == FailKind.Model)
        assertEquals(4, m.requests.size)
    }

    @Test fun compressedEventOnlyWhenFoldingChanges() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        // The context is very small: the basic prompt words and tool definitions exceed the trigger
        // point, and even the slightest pressure cannot return below the trigger point. Every additional
        // round trip requires one more step; after that, there are empty words (reminded once), the
        // number of folds remains unchanged, and no events are recorded.
        val m = FakeModel(*distinctWaits(13).toTypedArray(), reply(Part.Text("")), finish(), contextTokens = 100)
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", 60, sink = sink).run("t")
        assertEquals(15, m.requests.size)
        assertEquals((1..12).toList(), events.filterIsInstance<RunEvent.Compressed>().map { it.foldedSteps })
    }

    @Test fun longTaskStaysUnderBudget() = runTest {
        // Each screen reading is about 6000 words; 60 steps, context 32000 tokens: the estimated message
        // sent each time (multiplied by the ratio before calibration) does not exceed the available
        // window
        var n = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                n++
                val els = (1..40).map { UiElement("@e$it", "text", "第${n}次读屏的第${it}项" + "字".repeat(140), 10, 10, listOf(0, 0, 9, 9), null, false, false, false) }
                return ScreenSnapshot("snap$n", "com.x", els, 1080, 2400)
            }
        }
        // Two kinds of screen reading alternate to avoid triggering repeated operation protection
        val script = (1..59).map { tools(call(if (it % 2 == 1) "phone_observe" else "phone_list_elements")) } + finish()
        val m = FakeModel(*script.toTypedArray(), contextTokens = 32_000)
        val l = AgentLoop(m, toolDispatcher(screen), "BASE", 60)
        com.meenai.mina.core.testing.assertAnswered(DONE, l.run("长任务"))
        assertEquals(60, m.requests.size)
        val observation = m.requests[1][2].results().single().content
        assertTrue("observation should be about 6000+ chars, was ${observation.length}", observation.length >= 6000)
        val worst = m.requests.maxOf { TokenEstimate.of(it) }
        assertTrue("sent about $worst tokens", worst * ContextCompressor.UNCALIBRATED_SCALE <= ContextLimits.of(32_000).usable)
        // Only crop the old reading screen if it has exceeded the budget (the compressor will only fold
        // when it exceeds the limit), and then return to the budget after folding
        assertTrue(m.requests.last().any { msg -> msg.plainText().startsWith(ContextCompressor.HEADER) })
    }

    @Test fun priorTurnsPrecedeTask() = runTest {
        val m = FakeModel(finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE").run("再查一次", listOf(Turn("查天气", "晴", "succeeded")))
        val first = m.requests[0].withoutRuntime()
        assertEquals(2, first.size)
        assertEquals(Role.User, first[0].role)
        assertTrue("查天气" in (first[0].parts.single() as Part.Text).text)
        assertEquals("再查一次", (first[1].parts.single() as Part.Text).text)

        val none = FakeModel(finish())
        AgentLoop(none, toolDispatcher(FakeScreen()), "BASE").run("单独的任务")
        assertEquals(1, none.requests[0].withoutRuntime().size)
    }

    @Test fun loopIsAppendOnlyBelowTheThreshold() = runTest {
        val m = FakeModel(*Array(12) { tools(call("phone_observe")) }, finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE").run("t")
        fun omitted(ms: List<Message>) = ms.flatMap { it.results() }.count { HistoryPruner.OMITTED in it.content }
        // The compression trigger point has not been crossed: the old screen reading is not cut once,
        // and each request is the prefix for the next time
        assertTrue(m.requests.all { omitted(it) == 0 })
        m.requests.zipWithNext().forEach { (a, b) -> assertEquals(a, b.subList(0, a.size)) }
    }

    @Test fun activeModelNameTracksExecutingModelAndClearsOnFinish() = runTest {
        val observed = mutableListOf<String?>()
        val inner = FakeModel(finish())
        lateinit var loop: AgentLoop
        val spy = object : ChatModel {
            override val name = "gemini-2.0-flash"
            override val capabilities = inner.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                observed += loop.activeModelName.value
                return inner.complete(system, messages, tools)
            }
        }
        loop = AgentLoop(spy, toolDispatcher(FakeScreen()), "BASE")
        assertNull(loop.activeModelName.value)
        loop.run("t")
        assertEquals(listOf<String?>("gemini-2.0-flash"), observed)
        assertNull(loop.activeModelName.value)
    }
}
