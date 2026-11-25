package com.meenai.mina.core.agent

import com.meenai.mina.core.tool.execute
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionOutcome
import com.meenai.mina.core.action.ActionRecorder
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.action.execution
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.ScriptWebPort
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.cap.web.FakeWebPort
import com.meenai.mina.core.cap.web.ScriptRun
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.WEB_SCRIPT_TOOL
import com.meenai.mina.core.assembly.toolDispatcher

class ScriptStepsTest {
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
    private var gates = 0

    private fun shop() = FakeWebPort().apply {
        elements = listOf(
            UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "#go", true, false, false),
            UiElement("@e2", "button", "提交订单", 540, 600, listOf(0, 200, 999, 300), "#order", true, false, false),
        )
    }

    private suspend fun onWeb(web: FakeWebPort) = toolDispatcher(FakeScreen(), web = ScriptWebPort(web)).also {
        it.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
    }

    /** Sub-steps of the page script: after execution entry, the source is Script; the guard conclusion is saved for assertion. */
    private class Steps(val executor: ActionExecutor, val step: Int) {
        val verdicts = mutableListOf<GuardVerdict>()
        suspend fun run(call: Part.ToolCall) = executor.run(call, Origin.Script(step)).also { o ->
            verdicts += o.before
            if (o is ActionOutcome.Ran) verdicts += o.after
        }.execution()
    }

    private fun steps(d: ToolDispatcher, guards: OperationGuards = OperationGuards()) =
        Steps(ActionExecutor(d, guards, com.meenai.mina.core.gate.ConfirmationGate(), gate = { gates++ }, recorder = ActionRecorder(sink)), step = 4)

    @Test fun ordinaryActionRunsAndIsRecordedAsScriptStep() = runTest {
        val web = shop()
        val d = onWeb(web)
        val s = steps(d)
        val snap = s.run(Part.ToolCall("c1.1", "web_list_elements", "{}")).content
        val sid = snap["snapshotId"]!!.jsonPrimitive.content
        val tap = s.run(Part.ToolCall("c1.2", "web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content
        assertEquals(JsonPrimitive(true), tap["ok"])
        assertEquals(1, gates) // Only writing operations have passed the lock screen level
        assertTrue(web.calls.any { it.startsWith("tapRef @e1") })
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        assertEquals(listOf("script", "script"), results.map { it.source })
        assertEquals(listOf("c1.1", "c1.2"), results.map { it.callId })
        assertEquals(listOf(4, 4), results.map { it.step })
    }

    @Test fun irreversibleActionIsNotRun() = runTest {
        val web = shop()
        val d = onWeb(web)
        val s = steps(d)
        val sid = s.run(Part.ToolCall("c1.1", "web_list_elements", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val r = s.run(Part.ToolCall("c1.2", "web_tap", """{"ref":"@e2","snapshotId":"$sid"}""")).content
        assertEquals("confirmation_required", r["error"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), r["executed"])
        assertFalse(web.calls.any { it.startsWith("tapRef") })
        assertEquals(1, gates) // Pass the level first and then press the current screen to judge
        assertEquals("required", events.filterIsInstance<RunEvent.Confirmation>().single().decision)
        assertEquals(1, events.filterIsInstance<RunEvent.ToolResult>().size) // Those that are not executed will not be recorded as tool results.
    }

    @Test fun startedActionFinishesAndIsRecordedEvenIfCancelled() = runTest {
        val web = shop()
        val slow = object : com.meenai.mina.core.cap.web.WebPort by web {
            override suspend fun tapRef(ref: String, snapshotId: String): com.meenai.mina.core.uistate.ActionResult {
                kotlinx.coroutines.delay(5_000); return web.tapRef(ref, snapshotId)
            }
        }
        val d = toolDispatcher(FakeScreen(), web = slow)
        d.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
        val s = steps(d)
        val sid = s.run(Part.ToolCall("c1.1", "web_list_elements", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val job = launch { s.run(Part.ToolCall("c1.2", "web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")) }
        kotlinx.coroutines.delay(1_000)
        job.cancel() // Script timed out, task canceled: action has started
        job.join()
        assertTrue(web.calls.any { it.startsWith("tapRef @e1") })
        assertEquals(listOf("c1.1", "c1.2"), events.filterIsInstance<RunEvent.ToolResult>().map { it.callId })
    }

    @Test fun repeatedScriptActionsAreNotStoppedByTheirSignature() = runTest {
        val d = onWeb(shop())
        val g = OperationGuards()
        val s = steps(d, g)
        // Repeated execution of the same action will not be stopped due to the same signature.
        var last: JsonObject? = null
        repeat(14) { last = s.run(Part.ToolCall("c1.$it", "web_tap", """{"x":500,"y":50}""")).content }
        assertTrue(last!!["error"]?.jsonPrimitive?.content != "guard_stopped")
    }

    @Test fun loopRunsScriptActionsThroughTheGate() = runTest {
        val web = shop()
        val port = ScriptWebPort(web) { b ->
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e1") })
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e2") })
            ScriptRun.Done("\"unreachable\"")
        }
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("a", "web_navigate", """{"url":"https://shop.example.com/"}""")), StopReason.ToolUse, null),
            ModelReply(listOf(Part.ToolCall("b", WEB_SCRIPT_TOOL, """{"code":"await mina.tap('@e2')"}""")), StopReason.ToolUse, null),
            com.meenai.mina.core.testing.answerReply("ok"),
        )
        AgentLoop(model, toolDispatcher(FakeScreen(), web = port), "BASE", sink = sink).run("搜一下再发")
        assertEquals(1, web.calls.count { it.startsWith("tapRef") })
        val scripted = events.filterIsInstance<RunEvent.ToolResult>().filter { it.source == "script" }
        assertEquals(listOf("b.1", "b.2", "b.3"), scripted.map { it.callId })
        assertTrue(events.any { it is RunEvent.Confirmation })
        val result = model.requests[2].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        val o = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("confirmation_required", o["error"]!!.jsonPrimitive.content)
        // The entire script call itself is also recorded as usual.
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().any { it.tool == WEB_SCRIPT_TOOL && it.callId == "b" && it.source == "model" })
    }
}
