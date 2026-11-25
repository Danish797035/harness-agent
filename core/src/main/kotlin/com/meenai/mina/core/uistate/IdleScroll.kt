package com.meenai.mina.core.uistate

import kotlin.math.abs
import kotlin.random.Random

/**
 * A handy little move when staying (pure logic, without touching the page): When people are waiting to
 * turn the next page, they often slide the page down a little, and occasionally slide it back a little.
 * Do it once when the stay is long enough ([Limits.minWaitMs]); do 1–3 times ([Limits.count]), each time
 * scrolling down a small section of the viewport height ([Limits.stepFraction]), mostly downwards,
 * occasionally ([Limits.upChance]) going back a little, but not more than the previous scroll; the total
 * number of times does not exceed [Limits.maxTotal] viewports, and the net movement does not exceed
 * [Limits.maxNet] viewport. The time falls in the pause, leaving a section before and after
 * ([Limits.leadMs], [Limits.tailMs]). Only scroll the page, no pointer movement, no hovering, no
 * clicking.
 */
class IdleScroll(private val random: Random, val limits: Limits = Limits()) {
    /** The duration unit is milliseconds, and the amplitude unit is viewport height. */
    data class Limits(
        val minWaitMs: Long = 3_000L,
        val count: IntRange = 1..3,
        val stepFraction: ClosedFloatingPointRange<Double> = 0.06..0.16,
        val upChance: Double = 0.25,
        val maxTotal: Double = 0.45,
        val maxNet: Double = 0.3,
        val leadMs: Long = 800L,
        val tailMs: Long = 1_200L,
        /** At least that much time passed between the two strokes. */
        val gapMs: Long = 700L,
    )

    /** Once: After the pause starts, scroll [fraction] viewports (positively downward) at [atMs] time. */
    data class Step(val atMs: Long, val fraction: Double)

    /** How many times should be done in one [waitMs] stay? If it is too short, it will be empty. */
    fun plan(waitMs: Long): List<Step> {
        val l = limits
        if (waitMs < l.minWaitMs) return emptyList()
        val room = waitMs - l.leadMs - l.tailMs
        if (room < 0) return emptyList()
        // How many times can you put it down during the stay (at least gapMs should be left between two
        // times)
        val fits = (room / l.gapMs + 1).toInt()
        val n = random.nextInt(l.count.first, l.count.last + 1).coerceAtMost(fits).coerceAtLeast(1)
        var total = 0.0
        var net = 0.0
        val fractions = mutableListOf<Double>()
        repeat(n) { i ->
            val size = random.nextDouble(l.stepFraction.start, l.stepFraction.endInclusive)
            val up = i > 0 && net > 0 && random.nextDouble() < l.upChance
            val f = if (up) -minOf(size, net) else minOf(size, l.maxNet - net)
            if (abs(f) < MIN_STEP || total + abs(f) > l.maxTotal) return@repeat
            fractions += f
            total += abs(f)
            net += f
        }
        // Time: spread evenly among the available sections, and then sort them, so that the gapMs
        // between the two is not close to
        val times = MutableList(fractions.size) { l.leadMs + random.nextLong(0, room + 1) }.sorted().toMutableList()
        for (i in 1 until times.size) times[i] = maxOf(times[i], times[i - 1] + l.gapMs)
        return fractions.indices.mapNotNull { i -> times[i].takeIf { it <= waitMs - l.tailMs }?.let { Step(it, fractions[i]) } }
    }

    private companion object {
        /** Anything smaller than this will not be done at once (you can’t tell even if you roll it). */
        const val MIN_STEP = 0.02
    }
}
