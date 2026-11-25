package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMeterTest {
    private val task = Message(Role.User, listOf(Part.Text("帮我查天气")), Author.User)
    private var seq = 0
    private fun call() = Message(Role.Assistant, listOf(Part.ToolCall("c${++seq}", "phone_tap", """{"target":"按钮"}""")), Author.Mina)
    private fun result(pad: Int = 400) = Message(Role.Tool, listOf(Part.ToolResult("c$seq", """{"ok":true,"pad":"${"x".repeat(pad)}"}""", false)), Author.Mina)

    @Test fun noUsageYetEstimatesMessagesAndOverheadOnce() {
        val h = listOf(task, call(), result())
        // Step 1: There is no anchor point. The message + fixed overhead are estimated together and
        // multiplied by the same ratio.
        assertEquals(UsageMeter.estimate(TokenEstimate.of(h) + 11_000, 1.2), UsageMeter.of(h, 11_000, 1.2))
    }

    @Test fun anchorsOnTheLatestReplyWithUsage() {
        val first = call()
        val r1 = result()
        val second = call()
        val r2 = result()
        val h = listOf(task, first, r1, second, r2)
        val usage = mapOf(first to UsageMeter.Anchor(Usage(10_000, 50), 9_000), second to UsageMeter.Anchor(Usage(12_000, 40), 9_000))
        // Real input (including prompt words and tools at the time) + output of reply + estimate of
        // subsequent messages; the fixed overhead has not changed and will not be added.
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)), 1.0), UsageMeter.of(h, 9_000, 1.0, usage::get))
        // Then the fixed overhead changed (a set of tools was expanded): only the changed part was added
        assertEquals(12_000 + 40 + UsageMeter.estimate(TokenEstimate.of(listOf(r2)) + 500, 1.0), UsageMeter.of(h, 9_500, 1.0, usage::get))
    }

    @Test fun thinkingTokensAreNotContextGrowth() {
        val reply = call()
        val h = listOf(task, reply)
        // Output 3000, 2900 is reported separately by the service provider, only 100 will be sent as the
        // next input
        val anchor = UsageMeter.Anchor(Usage(inputTokens = 8_000, outputTokens = 3_000, reasoningTokens = 2_900), 0)
        assertEquals(8_100, UsageMeter.of(h, 0, 1.0) { if (it === reply) anchor else null })
        assertEquals(0, UsageMeter.resent(Usage(1, 10, reasoningTokens = 50)))
    }

    @Test fun anchoredOverheadIsNotCountedTwiceInTheTrigger() {
        // 32K local models, prompt words and tools 11K: The actual usage already includes these 11K, the
        // trigger point will no longer be deducted once, and will not be pressed every step
        val window = 32_000
        val overhead = 11_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var input = 12_000
        var compactions = 0
        repeat(10) {
            val reply = call()
            h += reply
            h += result(pad = 200)
            val anchor = UsageMeter.Anchor(Usage(input, 30), overhead)
            val size = UsageMeter.of(h, overhead, c.scale) { if (it === reply) anchor else null }
            if (c.compress(h, window, size, fixedOverhead = overhead).foldedSteps > 0) compactions++
            input += 100
        }
        assertEquals(0, compactions)
        // Press after crossing the trigger point (18K)
        val reply = call()
        h += reply
        h += result()
        val over = UsageMeter.of(h, overhead, c.scale) { if (it === reply) UsageMeter.Anchor(Usage(18_500, 30), overhead) else null }
        assertTrue(over > ContextLimits.of(window).trigger)
        assertTrue(c.compress(h, window, over, fixedOverhead = overhead).messages != h)
    }

    @Test fun firstStepWithHeavyOverheadOnSmallWindowDoesNotCompactEveryStep() {
        // There is no real usage, and the fixed overhead has passed the trigger point: there will be a
        // backlash after pressing once, and it will not be re-pressed at every step.
        val window = 32_000
        val overhead = 16_000
        val c = ContextCompressor(TOOLS)
        val h = mutableListOf(task)
        var rewrites = 0
        repeat(30) {
            h += call()
            h += result()
            if (c.compress(h, window, fixedOverhead = overhead).rewritten) rewrites++
        }
        assertTrue("$rewrites", rewrites in 1..3)
    }
}
