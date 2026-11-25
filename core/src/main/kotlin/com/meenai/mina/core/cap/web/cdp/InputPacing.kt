package com.meenai.mina.core.cap.web.cdp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.meenai.mina.core.uistate.Pace
import kotlin.random.Random

/**
 * The rhythm of DevTools mouse and keyboard: move the mouse along a short arc and then press it, hold it
 * down for a while and then lift it up, there are intervals of varying lengths between keys, and the
 * scroll wheel rotates one frame after another. All durations and amplitudes have upper and lower limits
 * ([Limits]), so the total duration of an action is bounded. [Instant] does not add any pauses and
 * intermediate points (the event sequence is the same as without rhythm, used for testing and callers
 * that do not need rhythm).
 */
class InputPacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * Upper and lower limits. Duration unit is milliseconds, distance unit is CSS pixels.
     * [approachPx]: how far from the target to move when pointer position is unknown;
     * [bowFraction] / [maxBowPx]: maximum curve deviation from a straight line (relative / absolute);
     * [maxKeyedChars]: text longer than this is passed to the input box as a whole (like paste)
     * instead of typing key by key.
     */
    data class Limits(
        val moveSteps: IntRange = 4..9,
        val moveStepMs: LongRange = 8L..22L,
        val approachPx: IntRange = 60..180,
        val bowFraction: Double = 0.12,
        val maxBowPx: Double = 40.0,
        val dwellMs: LongRange = 40L..120L,
        val pressMs: LongRange = 50L..120L,
        val keyHoldMs: LongRange = 25L..70L,
        val keyGapMs: LongRange = 35L..120L,
        val modifierGapMs: LongRange = 15L..45L,
        val wheelNotchPx: Double = 53.0,
        val maxNotches: Int = 12,
        val wheelGapMs: LongRange = 25L..70L,
        val maxKeyedChars: Int = 30,
    )

    /** Rhythmic (not [Instant]). */
    val paced: Boolean get() = random != null

    fun dwell(): Long = pick(limits.dwellMs)
    fun press(): Long = pick(limits.pressMs)
    fun keyHold(): Long = pick(limits.keyHoldMs)
    fun keyGap(): Long = pick(limits.keyGapMs)
    fun modifierGap(): Long = pick(limits.modifierGapMs)
    fun moveStep(): Long = pick(limits.moveStepMs)
    fun wheelGap(): Long = pick(limits.wheelGapMs)

    private fun pick(r: LongRange): Long = random?.let { Pace.pick(it, r) } ?: 0L

    /** The starting point when the pointer is unknown: somewhere [Limits.approachPx] far around the target (coordinates are not less than 0). [Instant] is the target itself. */
    fun origin(x: Double, y: Double): Pair<Double, Double> {
        val r = random ?: return x to y
        val dist = Pace.pick(r, limits.approachPx).toDouble()
        val angle = r.nextDouble(0.0, 2 * PI)
        return (x + dist * cos(angle)).coerceAtLeast(0.0) to (y + dist * sin(angle)).coerceAtLeast(0.0)
    }

    /**
     * The middle point from [from] to [to] (excluding the starting point, the last one is exactly the
     * end point): quadratic Bezier arc, slow at both ends and fast in the middle. [Instant] or only end
     * point when less than 3 pixels away.
     */
    fun path(from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>> {
        val r = random ?: return listOf(to)
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 3.0) return listOf(to)
        val n = Pace.pick(r, limits.moveSteps)
        val c = Pace.control(from, to, Pace.bow(r, dist, limits.bowFraction, limits.maxBowPx))
        return (1..n).map { i -> if (i == n) to else Pace.bezier(from, c, to, Pace.ease(i.toDouble() / n)) }
    }

    /**
     * Divide the scrolling distance [distance] (positive means downward) into wheel increments one by
     * one. The total is exactly [distance]: each grid is [Limits.wheelNotchPx], and the last grid is the
     * remaining fraction; when the number of grids exceeds [Limits.maxNotches], it is changed to
     * [Limits.maxNotches] equally (each grid is larger, so the total time is bounded). [Instant] is
     * rolled in one go. 0 is an empty table.
     */
    fun notches(distance: Double): List<Double> {
        if (distance == 0.0) return emptyList()
        if (random == null) return listOf(distance)
        val sign = if (distance < 0) -1.0 else 1.0
        val total = abs(distance)
        val count = ceil(total / limits.wheelNotchPx - 1e-9).toInt().coerceAtLeast(1)
        if (count > limits.maxNotches) return List(limits.maxNotches) { sign * total / limits.maxNotches }
        return List(count) { i -> sign * if (i < count - 1) limits.wheelNotchPx else total - limits.wheelNotchPx * (count - 1) }
    }

    companion object {
        /** No pauses, no midpoints. */
        val Instant = InputPacing(null)

        /** Like the rhythm of a human hand; [random] can be replaced with a fixed seed (test). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = InputPacing(random, limits)
    }
}
