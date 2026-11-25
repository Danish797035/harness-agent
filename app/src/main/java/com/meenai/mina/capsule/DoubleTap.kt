package com.meenai.mina.capsule

/**
 * Click and double-click on the capsule (pure logic, the clock is given by the caller): click to expand
 * the card, double-click to send the current screen. Pressing once ([tap]) does not count as a click:
 * pressing [timeoutMs] again counts as a double-click; then the second press ([settle]) does not count
 * as a click.
 */
class DoubleTap(val timeoutMs: Long) {
    enum class Tap {
        /** The first time: wait for [timeoutMs] and then [settle]. */
        First,
        Double,
    }

    private var firstAt: Long? = null

    fun tap(now: Long): Tap {
        val first = firstAt
        return if (first != null && now - first <= timeoutMs) {
            firstAt = null
            Tap.Double
        } else {
            firstAt = now
            Tap.First
        }
    }

    /** It's time after the first click: still waiting for the second click (returns true, only returns once); returns false when it has been double-clicked or has not yet arrived. */
    fun settle(now: Long): Boolean {
        val first = firstAt ?: return false
        if (now - first < timeoutMs) return false
        firstAt = null
        return true
    }

    /** No more waiting (put the capsule away and change it to something else). */
    fun reset() {
        firstAt = null
    }
}
