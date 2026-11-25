package com.meenai.mina.core.action

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.execute
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The interval between external actions: the first time, the interval is enough, and other websites are counted separately. The interval has an upper and lower limit and becomes longer with the number of times; the execution entry waits according to it, and the waiting time is recorded as waiting. */
class OutwardPacingTest {
    private val limits = OutwardPacing.Limits()

    @Test fun firstActionOnASiteDoesNotWait() {
        val p = OutwardPacing.human(Random(1))
        assertEquals(0, p.waitBefore("example.com", 0))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("other.com", 10))
        assertEquals(0, OutwardPacing.Off.also { it.noteDone("example.com", 0) }.waitBefore("example.com", 1))
    }

    @Test fun gapIsWithinTheFirstRangeAndCountsElapsedTime() {
        repeat(50) { seed ->
            val p = OutwardPacing.human(Random(seed))
            p.noteDone("example.com", 1_000)
            val gap = p.waitBefore("example.com", 1_000)
            assertTrue("$gap", gap in limits.firstGapMs)
            // The time spent reading pages and thinking about the next step is included in the interval.
            assertEquals(gap - 3_000, p.waitBefore("example.com", 4_000))
            assertEquals(0, p.waitBefore("example.com", 1_000 + gap))
        }
    }

    @Test fun gapGrowsWithCountAndNeverPassesTheCap() {
        val p = OutwardPacing.human(Random(3))
        var t = 0L
        for (n in 1..40) {
            p.noteDone("example.com", t)
            val gap = p.waitBefore("example.com", t)
            val lo = (limits.firstGapMs.first + (n - 1) * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
            assertTrue("action $n: $gap", gap in lo..limits.maxGapMs)
            t += gap
        }
    }

    @Test fun oldActionsFallOutOfTheWindow() {
        val p = OutwardPacing.human(Random(5), OutwardPacing.Limits(windowMs = 60_000))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("example.com", 61_000))
    }

    @Test fun sitesAreRegistrableDomainsOnTheWebAndPackagesOnThePhone() {
        assertEquals("example.com", OutwardPacing.siteOf("www.example.com", web = true))
        assertEquals("example.com", OutwardPacing.siteOf("m.example.com", web = true))
        assertEquals("com.chat.app", OutwardPacing.siteOf("com.chat.app", web = false))
        assertEquals(null, OutwardPacing.siteOf(null, web = false))
    }

    /** One screen: one "Communicate now" and one ordinary button; write down what you clicked. */
    private class GreetPage : ScreenPort by FakeScreen() {
        val taps = mutableListOf<String>()
        private val shown = listOf(
            UiElement("@e1", "button", "立即沟通", 900, 300, listOf(800, 100, 999, 160), null, true, false, false),
            UiElement("@e2", "button", "下一个", 900, 1300, listOf(800, 500, 999, 560), null, true, false, false),
        )
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.jobs", shown, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { taps += ref }
    }

    // When executing autonomously, it is also separated according to the judgment: wait enough time
    // before the second "immediate communication", it will be recorded as the wait for this call, and
    // will not be included in the operation time; ordinary clicks will not wait
    @Test fun executorWaitsBetweenOutwardTapsAndRecordsTheWait() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val screen = GreetPage()
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val pacing = OutwardPacing.human(Random(9))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(sink, { currentTime }), autonomous = true, pacing = pacing)
        suspend fun tap(id: String, ref: String) = executor.run(Part.ToolCall(id, "phone_tap", """{"ref":"$ref","snapshotId":"s1"}"""), Origin.Model(1))
        tap("c1", "@e1")
        val gap = pacing.waitBefore("com.jobs", currentTime)
        assertTrue("$gap", gap in limits.firstGapMs)
        val before = currentTime
        tap("c2", "@e2")
        val spent = currentTime - before
        tap("c3", "@e1")
        assertEquals(listOf("@e1", "@e2", "@e1"), screen.taps)
        val results = events.filterIsInstance<RunEvent.ToolResult>().associateBy { it.callId }
        assertEquals(0L, results.getValue("c1").waitMs)
        assertEquals(0L, results.getValue("c2").waitMs)
        // The time spent on ordinary clicks is included in the interval, and only the insufficient part
        // is made up.
        assertEquals(gap - spent, results.getValue("c3").waitMs)
        assertTrue(results.getValue("c3").durationMs < results.getValue("c3").waitMs)
        // Mark on the activity which times it is an external action: after stopping, press it to count
        // the number of times it was issued when giving an account.
        val outward = events.filterIsInstance<RunEvent.Activity>().associate { it.callId to it.outward }
        assertEquals(mapOf("c1" to true, "c2" to false, "c3" to true), outward)
    }
}
