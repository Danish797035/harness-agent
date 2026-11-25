package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionRecorder
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.action.execution
import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PlacePace
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.execute
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The rhythm in the app is connected to the execution entrance: each step in a single step and a batch
 * is paused before the action, and the duration of the pause is recorded as the wait for this call;
 * "fast" does not stop; Apps that limit the browsing speed will not change the screen when it reaches
 * the upper limit, report page_limit, type as usual, and continue after the user answers; scroll lightly
 * in the pause and only enter the running record.
 */
class AppDwellTest {
    /** An App: Each click changes a whole screen (six different pieces of content); jot down points, type, and scroll a few times. */
    private class Pages(private val pkg: String = "com.slow") : ScreenPort by FakeScreen() {
        var page = 0
        val calls = mutableListOf<String>()
        private fun shown() = (1..6).map { i ->
            UiElement("@e$i", "text", "第${page}屏的第${i}条内容", 540, 200 * i, listOf(0, 80 * i, 999, 80 * i + 60), null, true, i == 1, false)
        }
        override suspend fun snapshot() = ScreenSnapshot("s$page", pkg, shown(), 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tap"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type"; return ActionResult.Ok(changed = true) }
        override suspend fun back(): ActionResult { calls += "back"; page++; return ActionResult.Ok(changed = true) }
        override suspend fun idleScroll(fraction: Double): Boolean { calls += "idle"; return true }
    }

    private fun tools(screen: ScreenPort, pacing: AppPacing, idle: IdleScroll? = null): ToolDispatcher =
        toolDispatcher(screen, appPacing = { pacing }, appPace = { if (it == "com.slow") PlacePace.CAREFUL else PlacePace.NORMAL }, appIdleScroll = { idle })

    private fun executor(t: ToolDispatcher) = ActionExecutor(t, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE))

    private suspend fun ActionExecutor.tap(t: ToolDispatcher): ToolExecution {
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        return run(Part.ToolCall("t", "phone_tap", """{"ref":"@e2","snapshotId":"$sid"}"""), Origin.Model(1)).execution()
    }

    private fun ToolExecution.error() = (content["error"] as? JsonPrimitive)?.content

    @Test fun fastAddsNoDwell() = runTest {
        val screen = Pages()
        val t = tools(screen, OperatingPace.FAST.appPacing())
        val ex = executor(t)
        val start = currentTime
        repeat(5) { assertEquals(0L, ex.tap(t).waitMs) }
        // There is no upper limit on the number of screens
        assertEquals(5, screen.page)
        assertEquals(0L, currentTime - start)
    }

    @Test fun eachActionWaitsAfterTheLastOneAndTheWaitIsRecorded() = runTest {
        val t = tools(Pages("com.plain"), OperatingPace.STANDARD.appPacing(Random(3)))
        val ex = executor(t)
        assertEquals(0L, ex.tap(t).waitMs)
        val before = currentTime
        val second = ex.tap(t)
        // Changed the previous screen: the range of the new screen (standard)
        assertTrue("${second.waitMs}", second.waitMs in AppPacing.Limits().newScreenMs.first..AppPacing.Limits().maxDwellMs)
        assertTrue(currentTime - before >= second.waitMs)
        // Each step in a batch is also stopped by the execution entry
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val typed = ex.run(Part.ToolCall("b1", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"你好"}"""), Origin.Batch(2)).execution()
        assertTrue("${typed.waitMs}", typed.waitMs > 0)
    }

    @Test fun carefulAppsStopAtTheScreenBudgetUntilTheUserAnswers() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing(Random(1), screenCap = 3))
        val ex = executor(t)
        repeat(3) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
        // Three screens have been changed: If the screen change action is not performed, explain why it
        // is stopped.
        val stopped = ex.tap(t)
        assertEquals("page_limit", stopped.error())
        assertTrue(stopped.content["message"].toString(), stopped.content["message"].toString().contains("ask_user"))
        assertEquals(3, screen.page)
        // Typing as usual
        val sid = t.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        assertEquals(JsonPrimitive(true), ex.run(Part.ToolCall("y", "phone_type_text", """{"ref":"@e1","snapshotId":"$sid","text":"a"}"""), Origin.Model(1)).execution().content["ok"])
        // The user replied: Go ahead and do it
        t.noteUserAnswer("还要接着看吗?", "继续")
        assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"])
        assertEquals(4, screen.page)
    }

    @Test fun ordinaryAppsHaveNoBudget() = runTest {
        val screen = Pages("com.plain")
        val t = tools(screen, AppPacing(Random(1), screenCap = 2))
        val ex = executor(t)
        repeat(4) { assertEquals(JsonPrimitive(true), ex.tap(t).content["ok"]) }
    }

    @Test fun idleScrollsHappenOnlyBeforeActionsThatDoNotAimAtAPlaceAndAreOnlyRecorded() = runTest {
        val screen = Pages()
        val t = tools(screen, AppPacing.human(Random(5)), IdleScroll(Random(6)))
        val ex = executor(t)
        ex.tap(t)
        // Do not scroll before clicking on coordinates or numbers
        ex.tap(t)
        assertTrue(screen.calls.none { it == "idle" })
        // Return to the position on the screen without pressing: Stop on the new screen long enough,
        // scroll a few times while staying, and only enter the running record
        val back = ex.run(Part.ToolCall("k", "phone_back", "{}"), Origin.Model(1)).execution()
        val idled = screen.calls.count { it == "idle" }
        assertTrue("$idled", idled > 0)
        assertEquals(JsonPrimitive(idled), back.record["idleScrolls"])
        assertTrue(back.content["idleScrolls"] == null)
    }
}
