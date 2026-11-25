package com.meenai.mina.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The gesture physics of the picture-in-picture window, the unit is pixels and seconds, has nothing to
 * do with the interface for testing. When you let go, project the landing point according to the speed
 * (like a list inertial scrolling to slow down to a stop), and then find the nearest corner; use a
 * critical damping spring for return (damping ratio 1, response 0.4 seconds): no rebound, and can
 * continue walking from the current speed, and can be interrupted at any time.
 */
object PipPhysics {
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /**
     * The area where the small window can be parked: [width]×[height] is the entire screen,
     * [pipWidth]×[pipHeight] is the current size of the small window; [top]/[bottom] is the height of
     * the top bar and input box to be moved away, and [margin] is the distance from the edge.
     */
    data class Bounds(
        val width: Float,
        val height: Float,
        val pipWidth: Float,
        val pipHeight: Float,
        val margin: Float,
        val top: Float,
        val bottom: Float,
    ) {
        /** When there is not enough space (the window is too narrow or too short, such as when the keyboard pops up), it is better to stick to the upper left than to go under the top bar. */
        fun position(c: Corner): Pair<Float, Float> {
            val left = margin
            val right = maxOf(left, width - pipWidth - margin)
            val upper = top + margin
            val lower = maxOf(upper, height - pipHeight - bottom - margin)
            return when (c) {
                Corner.TopStart -> left to upper
                Corner.TopEnd -> right to upper
                Corner.BottomStart -> left to lower
                Corner.BottomEnd -> right to lower
            }
        }
    }

    private const val DECELERATION = 0.998f

    /** After throwing it out with [velocity] (pixels/second), it will decelerate to 0.998 times per millisecond to determine how far it can slide in the end. */
    fun project(velocity: Float): Float = (velocity / 1000f) * DECELERATION / (1f - DECELERATION)

    /** The corner that should snap after letting go from ([x],[y]) at speed ([vx],[vy]). */
    fun snapCorner(x: Float, y: Float, vx: Float, vy: Float, bounds: Bounds): Corner {
        val px = x + project(vx)
        val py = y + project(vy)
        return Corner.entries.minBy { c ->
            val (cx, cy) = bounds.position(c)
            hypot(cx - px, cy - py)
        }
    }

    /** States of a one-dimensional spring: position and velocity. */
    data class Motion(val position: Float, val velocity: Float)

    class Spring(response: Float = 0.4f, dampingRatio: Float = 1f) {
        private val stiffness = (2 * PI / response).pow(2).toFloat()
        private val damping = (4 * PI * dampingRatio / response).toFloat()

        /** Go forward [dt] seconds (semi-implicit Euler; the longest single frame is calculated as 32 milliseconds, and it will not diverge when frames are dropped). */
        fun step(m: Motion, target: Float, dt: Float): Motion {
            val t = dt.coerceIn(0f, MAX_DT)
            val v = m.velocity + (-stiffness * (m.position - target) - damping * m.velocity) * t
            return Motion(m.position + v * t, v)
        }

        fun settled(m: Motion, target: Float) = abs(m.position - target) < REST_DISTANCE && abs(m.velocity) < REST_VELOCITY
    }

    private const val MAX_DT = 0.032f
    private const val REST_DISTANCE = 0.5f
    private const val REST_VELOCITY = 5f
}
