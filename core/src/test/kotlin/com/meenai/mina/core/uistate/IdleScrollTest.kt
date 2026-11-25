package com.meenai.mina.core.uistate

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The arrangement for rolling a few times when staying: roll only when it is long enough, the number of rolls, amplitude, and net movement are all bounded, and always stay within the pause. */
class IdleScrollTest {
    private val l = IdleScroll.Limits()

    @Test
    fun `short dwells get no scrolls`() {
        val s = IdleScroll(Random(1))
        assertTrue(s.plan(0).isEmpty())
        assertTrue(s.plan(l.minWaitMs - 1).isEmpty())
    }

    @Test
    fun `plans are bounded small and mostly downward`() {
        var ups = 0
        var steps = 0
        for (seed in 0 until 500) {
            val wait = 3_000L + seed * 53 % 27_000
            val plan = IdleScroll(Random(seed)).plan(wait)
            assertTrue("$seed $plan", plan.size in 1..l.count.last)
            assertTrue("$seed $plan", plan.sumOf { abs(it.fraction) } <= l.maxTotal + 1e-9)
            val net = plan.sumOf { it.fraction }
            assertTrue("$seed $plan", net >= -1e-9 && net <= l.maxNet + 1e-9)
            // The first scroll always goes down; scroll back no further than the previous scroll.
            assertTrue("$seed $plan", plan.first().fraction > 0)
            var pos = 0.0
            plan.forEach { pos += it.fraction; assertTrue("$seed $plan", pos >= -1e-9) }
            plan.forEach { assertTrue("$seed $plan", abs(it.fraction) <= l.stepFraction.endInclusive + 1e-9) }
            // Always stay in the pause, leaving room before and after, and space between two strokes
            plan.forEach { assertTrue("$seed $plan", it.atMs >= l.leadMs && it.atMs <= wait - l.tailMs) }
            plan.zipWithNext().forEach { (a, b) -> assertTrue("$seed $plan", b.atMs - a.atMs >= l.gapMs) }
            ups += plan.count { it.fraction < 0 }
            steps += plan.size
        }
        assertTrue("$ups/$steps", ups > 0 && ups * 4 < steps)
    }
}
