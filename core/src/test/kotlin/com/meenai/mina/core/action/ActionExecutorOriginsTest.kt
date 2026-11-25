package com.meenai.mina.core.action

import com.meenai.mina.core.tool.execute
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.gate.ConfirmationGate
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.meenai.mina.core.assembly.toolDispatcher

/** A screen has only one button (named by the use case) and a normal button; note what was actually clicked. */
private class OneButton(label: String) : ScreenPort by FakeScreen() {
    val taps = mutableListOf<String>()
    private val shown = listOf(
        UiElement("@e1", "button", label, 900, 2300, listOf(800, 940, 999, 999), null, true, false, false),
        UiElement("@e2", "button", "返回", 50, 50, listOf(0, 0, 100, 40), null, true, false, false),
    )
    override suspend fun snapshot() = ScreenSnapshot("s1", "com.chat", shown, 1080, 2400)
    override suspend fun peek() = snapshot()
    override suspend fun tapRef(ref: String, snapshotId: String) = com.meenai.mina.core.uistate.ActionResult.Ok(changed = true).also { taps += ref }
}

/**
 * The same action that costs money, no matter which path it comes from (model single step, multiple
 * steps at a time, page script, experience playback), the execution entrance gives the same conclusion
 * and is not executed, and the confirmation events in the running record are the same; actions that do
 * not cost money (send, delete, submit and ordinary clicks) are executed as usual, and the record shape
 * only differs from the source field.
 */
@RunWith(Parameterized::class)
class ActionExecutorOriginsTest(private val label: String, private val action: IrrevAction, private val origin: Origin) {
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }

    private suspend fun setup(): Pair<OneButton, ActionExecutor> {
        val screen = OneButton(label)
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        return screen to ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink))
    }

    @Test fun paymentIsRefusedTheSameWay() = runTest {
        val (screen, executor) = setup()
        val out = executor.run(Part.ToolCall("c1", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), origin)
        out as ActionOutcome.Refused
        assertEquals("confirmation_required", out.code)
        assertEquals(action, out.hit?.action)
        assertEquals(label, out.hit?.matched)
        assertTrue("没有执行", screen.taps.isEmpty())
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>().map { it.copy(atMs = 0) }
        assertEquals(listOf(RunEvent.Confirmation(0, action.name, label, null, null, "required")), confirmations)
        // Actions that are not executed are not recorded as execution results; the model's single-step
        // interception statement itself is the result given to the model, and is recorded as usual.
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(results.all { it.changed == null && it.callId == "c1" && "confirmation_required" in it.resultJson })
        assertEquals(if (origin.policy.recordsRefusal) 1 else 0, results.size)
    }

    @Test fun ordinaryActionRunsAndIsRecordedTheSameWay() = runTest {
        val (screen, executor) = setup()
        val out = executor.run(Part.ToolCall("c2", "phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""), origin)
        assertTrue("$out", out is ActionOutcome.Ran)
        assertEquals(listOf("@e2"), screen.taps)
        val r = events.filterIsInstance<RunEvent.ToolResult>().single()
        assertEquals(listOf(origin.step, "phone_tap", "c2", origin.policy.source, true), listOf(r.step, r.tool, r.callId, r.source, r.changed))
        assertTrue(events.none { it is RunEvent.Confirmation })
    }

    @Test fun outwardActionsThatSpendNoMoneyRunWithoutAsking() = runTest {
        for (free in listOf("发送", "删除", "提交")) {
            val screen = OneButton(free)
            val tools = toolDispatcher(screen)
            tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
            val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink))
            val out = executor.run(Part.ToolCall("c4", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), origin)
            assertTrue("$free $out", out is ActionOutcome.Ran)
            assertEquals(listOf("@e1"), screen.taps)
        }
        assertTrue(events.none { it is RunEvent.Confirmation })
    }

    @Test fun autonomousActionRunsWithoutApproval() = runTest {
        val screen = OneButton(label)
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(sink), autonomous = true)
        val out = executor.run(Part.ToolCall("c3", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), origin)
        assertTrue("$out", out is ActionOutcome.Ran)
        assertEquals(listOf("@e1"), screen.taps)
        assertTrue(events.none { it is RunEvent.Confirmation || it is RunEvent.Approval })
    }

    companion object {
        private val ORIGINS = listOf(Origin.Model(3), Origin.Batch(3), Origin.Script(3), Origin.Replay)
        private val ACTIONS = listOf("确认支付" to IrrevAction.pay, "提交订单" to IrrevAction.order)

        @JvmStatic
        @Parameterized.Parameters(name = "{0} via {2}")
        fun cases(): List<Array<Any>> = ACTIONS.flatMap { (l, a) -> ORIGINS.map { arrayOf(l, a, it) } }
    }
}
