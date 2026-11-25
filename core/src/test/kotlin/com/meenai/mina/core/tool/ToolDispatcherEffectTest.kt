package com.meenai.mina.core.tool

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.EffectTuning
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The distributor determines the effect of the action: watch the asynchronous update again, stop waiting after the port has waited, there is an upper limit on the extra time, and change will follow the effect. */
class ToolDispatcherEffectTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.kind() = this["effect"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    private fun page(vararg names: String) = ScreenSnapshot("p", "com.x", names.mapIndexed { i, n ->
        UiElement("@e${i + 1}", "text", n, 500, 100 + i * 100, listOf(0, 40 + i * 40, 999, 70 + i * 40), null, true, false, false)
    }, 1080, 2400)

    /** The interface changes to [later](asynchronous update) after the action [afterMs] milliseconds; [peekMs] is the time it takes to take a look; [result] is the action result of the port. */
    private class LateScreen(
        val now: () -> Long, val first: ScreenSnapshot, val later: ScreenSnapshot, val afterMs: Long,
        val peekMs: Long = 0, val result: ActionResult = ActionResult.Ok(),
    ) : ScreenPort by FakeScreen() {
        var actedAt = Long.MAX_VALUE
        var settles = 0
        private fun current() = if (now() - actedAt >= afterMs) later else first
        override suspend fun snapshot() = current()
        override suspend fun peek(): ScreenSnapshot { if (peekMs > 0) delay(peekMs); return current() }
        override suspend fun waitStable(timeoutMs: Long): Boolean { settles++; return true }
        override suspend fun tapXY(x: Int, y: Int): ActionResult { actedAt = now(); return result }
        override suspend fun back(): ActionResult { actedAt = now(); return result }
    }

    @Test fun asyncUpdateIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表", "一", "二"), page("详情", "价格", "加入购物车", "评价"), afterMs = EffectTuning.RECHECK_MS / 2)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
    }

    // A new prompt "reached the limit" appears after the action: limitReached and stop prompts are
    // written in the result; the description text on the screen before the action is not counted.
    @Test fun limitNoticeAfterTheActionIsReported() = runTest {
        val s = LateScreen({ currentTime }, page("职位详情", "立即沟通"), page("职位详情", "立即沟通", "今日沟通次数已用完，明天再来吧"), afterMs = 0)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("今日沟通次数已用完，明天再来吧", c["limitReached"]!!.jsonPrimitive.content)
        assertTrue(c["hint"]!!.jsonPrimitive.content.contains("stop here"))
        val faq = LateScreen({ currentTime }, page("常见问题", "次数已达上限怎么办"), page("常见问题", "次数已达上限怎么办", "答案"), afterMs = 0)
        assertEquals(null, toolDispatcher(faq).execute(call("phone_tap", """{"x":500,"y":500}""")).content["limitReached"])
    }

    // The reminder bar comes later (it doesn’t appear when you watch it the first time, it only appears
    // when you watch it again).
    @Test fun lateLimitToastIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表", "操作过于频繁，请稍后再试"), afterMs = EffectTuning.RECHECK_MS / 2)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("操作过于频繁，请稍后再试", c["limitReached"]!!.jsonPrimitive.content)
    }

    @Test fun stillNothingAfterTheRecheckIsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("no_effect", r.content.kind())
        assertEquals(false, r.changed)
    }

    @Test fun portThatAlreadyWaitedIsNotWaitedForAgain() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(0, s.settles)
    }

    @Test fun portSaysChangedIsNeverReportedAsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val c = toolDispatcher(s).execute(call("phone_back")).content
        assertEquals("small_change", c.kind())
        assertEquals("true", c["changed"]!!.jsonPrimitive.content)
    }

    @Test fun portSaysUnchangedButTheInterfaceChangedFollowsTheEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = false))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
        assertEquals("true", r.content["changed"]!!.jsonPrimitive.content)
    }

    @Test fun extraTimePerActionIsBounded() = runTest {
        // Take a look at the slow port that takes 2 seconds: to judge the effect, it will cost BUDGET_MS
        // at most. When it comes, make a conclusion based on the facts of the port.
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, peekMs = 2_000, result = ActionResult.Ok(changed = true))
        val d = toolDispatcher(s)
        val start = currentTime
        val c = d.execute(call("phone_back")).content
        val spent = currentTime - start
        // There are upper limits for one glance before action and for judgment after action.
        assertTrue("spent $spent", spent <= EffectTuning.PROBE_MS + EffectTuning.BUDGET_MS)
        assertEquals("small_change", c.kind())
    }
}
