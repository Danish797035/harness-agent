package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.DOUBLE_TAP_GAP_MS
import com.meenai.mina.core.uistate.Pace
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The rhythm of mobile phone gestures: pause before placing your finger down, tap and hold for varying
 * lengths of time, the landing point is slightly deviated near the center of the element (always within
 * the element), sliding along a small arc, first accelerating and then decelerating, and the duration
 * floating around a given value. All durations and amplitudes have upper and lower limits ([Limits]), so
 * the total duration of an action is bounded. [Instant] does not add any pauses, deviations and arcs,
 * and the duration is the same as before (click [TAP_MS], double-click the interval [DOUBLE_TAP_GAP_MS],
 * slide to walk in a straight line at a constant speed for the given duration).
 */
class GesturePacing(private val random: Random?, val limits: Limits = Limits()) {
    /**
     * Lower and upper bounds. Durations are in milliseconds, distances in pixels.
     * [approachMs]: pause before the finger lands; [pressMs]: press-and-hold for a tap;
     * [doublePressMs] / [doubleGapMs]: hold time of each tap and the gap between them (the system
     * recognises a double tap when the gap is 40-300 ms); [jitterFraction] / [maxJitterPx] /
     * [minJitterSpanPx]: when tapping an element, the landing point stays off-centre by at most this
     * fraction of the element's width (height) and this many pixels, and is not offset in the direction
     * that is narrower (shorter) than [minJitterSpanPx]; [pointJitterPx]: deviation when pressing a
     * coordinate; [durationJitter]: fraction by which the swipe duration floats up and down;
     * [holdStretch]: a press held in place (start and end almost coincide, such as a long press) can only
     * lengthen, never shorten, by at most this fraction; [stillPx]: start and end closer than this count
     * as holding in place; [bowFraction] / [maxBowPx]: maximum deviation of the arc from a straight line;
     * [sampleMs]: time between adjacent points while the finger moves; [maxPoints]: maximum points in
     * one stroke; [dragMs]: a swipe at least this long is a slow drag, decelerating to a stop and not
     * flinging on release; shorter swipes are fast flicks released at [flingEndSpeed] times the average
     * speed (comparable to the old constant-speed release, so scroll distance stays the same).
     */
    data class Limits(
        val approachMs: LongRange = 30L..120L,
        val pressMs: LongRange = 50L..120L,
        val doublePressMs: LongRange = 40L..90L,
        val doubleGapMs: LongRange = 80L..140L,
        val jitterFraction: Double = 0.2,
        val maxJitterPx: Int = 12,
        val minJitterSpanPx: Int = 24,
        val pointJitterPx: Int = 3,
        val durationJitter: Double = 0.08,
        val holdStretch: Double = 0.12,
        val stillPx: Double = 3.0,
        val bowFraction: Double = 0.06,
        val maxBowPx: Double = 30.0,
        val sampleMs: LongRange = 8L..16L,
        val maxPoints: Int = 600,
        val dragMs: Long = 800L,
        val flingEndSpeed: ClosedFloatingPointRange<Double> = 0.9..1.0,
    )

    /** A point in a stroke: relative falling time [t] milliseconds when the finger is at ([x], [y]). */
    data class Point(val x: Double, val y: Double, val t: Long)

    /** One stroke: the point from pressing to lifting (the first t is 0, the last one is the moment and position of lifting). */
    data class Stroke(val points: List<Point>) {
        val durationMs: Long get() = points.last().t
    }

    /** Rhythmic (not [Instant]). */
    val paced: Boolean get() = random != null

    fun approach(): Long = pick(limits.approachMs, 0L)
    fun press(): Long = pick(limits.pressMs, TAP_MS)
    fun doublePress(): Long = pick(limits.doublePressMs, TAP_MS)
    fun doubleGap(): Long = pick(limits.doubleGapMs, DOUBLE_TAP_GAP_MS)

    private fun pick(r: LongRange, instant: Long): Long = random?.let { Pace.pick(it, r) } ?: instant

    /**
     * The landing point when the point element ([left], [top]) – ([right], [bottom]) (right and bottom
     * are not included): near the center (rounded like Rect.centerX/centerY), the deviation does not
     * exceed the width (height) of [Limits.jitterFraction] and [Limits.maxJitterPx], more towards the
     * center; the narrow direction is not biased. The placement point is always in the element.
     */
    fun within(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        val cx = (left + right) shr 1
        val cy = (top + bottom) shr 1
        val r = random ?: return cx to cy
        fun reach(span: Int) = if (span < limits.minJitterSpanPx) 0 else minOf((span * limits.jitterFraction).toInt(), limits.maxJitterPx)
        return (cx + offset(r, reach(right - left))).coerceIn(left, maxOf(left, right - 1)) to
            (cy + offset(r, reach(bottom - top))).coerceIn(top, maxOf(top, bottom - 1))
    }

    /** The landing point when pressing the coordinate point: within [Limits.pointJitterPx] near the given point (less than 1dp, it will not deflect to other controls); the coordinate is not less than 0. */
    fun near(x: Int, y: Int): Pair<Int, Int> {
        val r = random ?: return x to y
        return (x + offset(r, limits.pointJitterPx)).coerceAtLeast(0) to (y + offset(r, limits.pointJitterPx)).coerceAtLeast(0)
    }

    // The sum of two uniform numbers (triangular distribution): most of them fall near the center, no
    // more than ±reach
    private fun offset(r: Random, reach: Int): Int =
        if (reach <= 0) 0 else ((r.nextDouble() + r.nextDouble() - 1.0) * reach).roundToInt().coerceIn(-reach, reach)

    /** The duration of a gesture in which several fingers move together (without [stroke] points): the given value fluctuates up and down [Limits.durationJitter]. */
    fun duration(durationMs: Long): Long {
        val r = random ?: return durationMs
        return (durationMs * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
    }

    /**
     * Use a finger to move around [durationMs] along [path](at least two points). The starting point and end point
     * remain unchanged; when [bow] is true, a short arc is walked between the two points (only for the
     * path of two points, the multi-point path is drawn by the caller, follow it). [bounds] When given
     * (width, height), the points are all sandwiched within the screen. When the start and end points
     * almost overlap, press and hold in place: there is only the starting point and the end point, and
     * the duration is only lengthened but not shortened (long pressing is still long enough). [Instant]:
     * The point is [path], the time is distributed uniformly according to the length of the path, and
     * the duration is [durationMs].
     */
    fun stroke(path: List<Pair<Double, Double>>, durationMs: Long, bow: Boolean = false, bounds: Pair<Int, Int>? = null): Stroke {
        require(path.size >= 2) { "a stroke needs at least two points" }
        val total = durationMs.coerceAtLeast(1)
        val r = random ?: return Stroke(uniform(path, total))
        val start = path.first()
        val end = path.last()
        val length = lengthOf(path)
        if (length < limits.stillPx) {
            val hold = (total * (1 + r.nextDouble(0.0, limits.holdStretch))).roundToLong()
            return Stroke(listOf(Point(start.first, start.second, 0), Point(end.first, end.second, hold)))
        }
        val t = (total * (1 + r.nextDouble(-limits.durationJitter, limits.durationJitter))).roundToLong().coerceAtLeast(1)
        val shape = if (bow && path.size == 2) {
            val c = Pace.control(start, end, Pace.bow(r, length, limits.bowFraction, limits.maxBowPx))
            (0..CURVE_SEGMENTS).map { Pace.bezier(start, c, end, it.toDouble() / CURVE_SEGMENTS) }
        } else path
        val endSpeed = if (t >= limits.dragMs) 0.0 else r.nextDouble(limits.flingEndSpeed.start, limits.flingEndSpeed.endInclusive)
        val step = Pace.pick(r, limits.sampleMs).coerceAtLeast(1)
        val n = ceil(t.toDouble() / step).toInt().coerceIn(1, limits.maxPoints - 1)
        val cum = cumulative(shape)
        val points = (0..n).map { i ->
            val tm = if (i == n) t else (t * i.toDouble() / n).roundToLong()
            val (x, y) = when (i) {
                0 -> start
                n -> end
                else -> at(shape, cum, cum.last() * progress(i.toDouble() / n, endSpeed))
            }
            Point(x, y, tm)
        }
        return Stroke(if (bounds == null) points else points.map { it.copy(x = it.x.coerceIn(0.0, bounds.first - 1.0), y = it.y.coerceIn(0.0, bounds.second - 1.0)) })
    }

    companion object {
        /** How long to hold the tap when not adding a rhythm. */
        const val TAP_MS = 50L

        /** The arc is broken into so many small segments and then points are taken according to time. */
        private const val CURVE_SEGMENTS = 32

        /** No pauses, deviations or arcs. */
        val Instant = GesturePacing(null)

        /** Like the rhythm of a human hand; [random] can be replaced with a fixed seed (test). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = GesturePacing(random, limits)

        /**
         * The proportion of the distance traveled when time reaches [u] (0..1): three Hermites, starting
         * speed 0, and ending speed [endSpeed] times the average speed (monotonic between 0..3). When
         * [endSpeed] is 0, the two ends are slow and the middle is fast, stop and then raise your hand;
         * when it is 1, let go at the same speed as a constant speed.
         */
        internal fun progress(u: Double, endSpeed: Double): Double {
            val u2 = u * u
            val u3 = u2 * u
            return (-2 * u3 + 3 * u2) + endSpeed * (u3 - u2)
        }

        private fun lengthOf(path: List<Pair<Double, Double>>): Double = cumulative(path).last()

        private fun cumulative(path: List<Pair<Double, Double>>): DoubleArray {
            val out = DoubleArray(path.size)
            for (i in 1 until path.size) out[i] = out[i - 1] + hypot(path[i].first - path[i - 1].first, path[i].second - path[i - 1].second)
            return out
        }

        /** The position when walking through [s] along the polyline. */
        private fun at(path: List<Pair<Double, Double>>, cum: DoubleArray, s: Double): Pair<Double, Double> {
            var i = 1
            while (i < path.size - 1 && cum[i] < s) i++
            val seg = cum[i] - cum[i - 1]
            val f = if (seg <= 0) 1.0 else ((s - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            val (x0, y0) = path[i - 1]
            val (x1, y1) = path[i]
            return x0 + (x1 - x0) * f to y0 + (y1 - y0) * f
        }

        /** A stroke without rhythm: at a given point, time is distributed at a uniform speed according to the distance (the same as the system walking at a uniform speed along a path). */
        private fun uniform(path: List<Pair<Double, Double>>, total: Long): List<Point> {
            val cum = cumulative(path)
            val length = cum.last()
            return path.mapIndexed { i, (x, y) ->
                val t = when {
                    i == path.lastIndex -> total
                    length <= 0 -> 0L
                    else -> (total * cum[i] / length).roundToLong()
                }
                Point(x, y, t)
            }
        }
    }
}
