package com.meenai.mina.core.uistate

import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Rhythm elements shared by web mouse ([com.meenai.mina.core.cap.web.cdp.InputPacing]) and mobile phone
 * gestures ([com.meenai.mina.core.cap.phone.GesturePacing]): bounded random values in the interval,
 * quadratic Bezier with a slight arc, and slow motion at both ends and fast in the middle.
 */
object Pace {
    /** Randomly pick one (including both ends) from [r]; when the interval has only one value, it is it. */
    fun pick(random: Random, r: LongRange): Long = if (r.first >= r.last) r.first else random.nextLong(r.first, r.last + 1)

    /** Randomly pick an integer (including both ends) from [r]. */
    fun pick(random: Random, r: IntRange): Int = if (r.first >= r.last) r.first else random.nextInt(r.first, r.last + 1)

    /** The amplitude of the arc's deviation from the straight line: no more than the distance [fraction], no more than [maxPx]; which side is random (with sign). */
    fun bow(random: Random, dist: Double, fraction: Double, maxPx: Double): Double =
        min(dist * fraction, maxPx) * random.nextDouble(-1.0, 1.0)

    /** The control points of quadratic Bezier: [from], [to], the midpoint of the connecting line is offset from [bow] along the normal line. When two points coincide, it is the midpoint. */
    fun control(from: Pair<Double, Double>, to: Pair<Double, Double>, bow: Double): Pair<Double, Double> {
        val (x0, y0) = from
        val (x1, y1) = to
        val dist = hypot(x1 - x0, y1 - y0)
        if (dist == 0.0) return (x0 + x1) / 2 to (y0 + y1) / 2
        return (x0 + x1) / 2 - (y1 - y0) / dist * bow to (y0 + y1) / 2 + (x1 - x0) / dist * bow
    }

    /** Quadratic Bezier point at [t] (0..1). */
    fun bezier(from: Pair<Double, Double>, control: Pair<Double, Double>, to: Pair<Double, Double>, t: Double): Pair<Double, Double> {
        val u = 1 - t
        return (u * u * from.first + 2 * u * t * control.first + t * t * to.first) to
            (u * u * from.second + 2 * u * t * control.second + t * t * to.second)
    }

    /** Slow at both ends and fast in the middle (smoothstep): 0→0,1→1, the speed at both ends is 0. */
    fun ease(t: Double) = t * t * (3 - 2 * t)
}
