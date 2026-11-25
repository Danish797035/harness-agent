package com.meenai.mina.ui

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.agent.RunProgress
import com.meenai.mina.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.meenai.mina.settings.Problem
import org.junit.Test

class StatusCopyTest {
    @get:Rule val texts = EnglishTexts()

    // When the channel settings page cannot be opened, return to the first one that can be opened.
    @Test
    fun firstThatStartsFallsBack() {
        val tried = mutableListOf<String>()
        assertTrue(firstThatStarts(listOf("channel", "app")) { tried += it; it == "app" })
        assertEquals(listOf("channel", "app"), tried)
        tried.clear()
        assertTrue(firstThatStarts(listOf("channel", "app")) { tried += it; true })
        assertEquals(listOf("channel"), tried)
        assertFalse(firstThatStarts(listOf("channel", "app")) { false })
    }

    @Test
    fun paymentLinesOnePerField() {
        assertEquals("Item: 拿铁 大杯\nAmount: ¥18\nAddress: 公司前台", StatusCopy.paymentLines("拿铁 大杯", "¥18", "公司前台"))
        assertEquals("Amount: ¥18", StatusCopy.paymentLines(null, "¥18", " "))
        assertNull(StatusCopy.paymentLines(null, null, null))
    }

    // Follow the memorized route and write down what you are doing on the card when doing a few steps at
    // a time.
    @Test
    fun replayAndBatchCopy() {
        assertEquals("Replaying a known route (step 2/5)", StatusCopy.progress(RunProgress.Replay(2, 5)))
        assertEquals("Running 3 steps at once", StatusCopy.progress(RunProgress.Batch(3)))

        val ex = Exchange("查电量", emptyList(), ExchangeStatus.Working, live = true)
        val replaying = ChatViewModel.reduce(ex, TaskState.Running(0, "按以往的做法操作", progress = RunProgress.Replay(2, 5)))
        assertEquals("Replaying a known route (step 2/5)", liveLine(replaying))
        val batching = ChatViewModel.reduce(replaying, TaskState.Running(1, "一起做", "do_steps", RunProgress.Batch(3)))
        assertEquals("Running 3 steps at once", liveLine(batching))
        // The step list only lists what was actually done
        assertEquals(emptyList<StepRow>(), batching.steps)
    }

    @Test
    fun headsUpCopyForEachCombination() {
        val both = Problem.AttentionLowered(attention = true, result = true)
        val b = StatusCopy.banner(both)
        assertEquals("“Needs you” notifications won’t pop up", b.title)
        assertEquals(
            "When you need to take over or approve, these notifications now land quietly in the shade and are easy to miss. The same goes for “Task results”. Tap “Turn on” and turn on “Pop on screen”.\n" +
                "Settings › Apps › Harness Agent › Notifications › Needs you › Pop on screen (some phones call it “Banner” or “Floating notification”)",
            b.detail,
        )
        assertEquals("Turn on", b.link)
        assertEquals(Fix.OpenAttentionChannel, StatusCopy.directFix(both))
        assertEquals(Fix.OpenAttentionChannel, StatusCopy.explain(both, "14").fix)

        val attentionOnly = StatusCopy.banner(Problem.AttentionLowered(true, false))
        assertFalse(attentionOnly.detail.contains("The same goes for"))

        val resultOnly = Problem.AttentionLowered(attention = false, result = true)
        assertEquals("“Task results” notifications won’t pop up", StatusCopy.banner(resultOnly).title)
        assertTrue(StatusCopy.banner(resultOnly).detail.contains("Notifications › Task results › Pop on screen"))
        assertEquals(Fix.OpenResultChannel, StatusCopy.directFix(resultOnly))
        val e = StatusCopy.explain(resultOnly, "14")
        assertEquals("Turn on", e.action)
        assertEquals(Fix.OpenResultChannel, e.fix)
    }
}
