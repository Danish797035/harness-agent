package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trigger point is just not lower than the minimum window of [trigger] (the trigger point does not decrease monotonically with the window). */
internal fun windowFor(trigger: Int): Int {
    var lo = 1
    var hi = 100_000_000
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (ContextLimits.of(mid).trigger >= trigger) hi = mid else lo = mid + 1
    }
    return lo
}

/** Estimation of the entire request before calibration (message + fixed overhead, multiplied by [ContextCompressor.UNCALIBRATED_SCALE]). */
internal fun uncalibrated(ms: List<Message>, overhead: Int = 0) = UsageMeter.of(ms, overhead, ContextCompressor.UNCALIBRATED_SCALE)

class ContextLimitsTest {
    @Test fun smallLocalWindow32k() {
        val l = ContextLimits.of(32_000)
        // Output reserve 8K (a quarter of the window), margin 6K (a quarter of the available)
        assertEquals(24_000, l.usable)
        assertEquals(6_000, l.margin)
        assertEquals(18_000, l.trigger)
        assertEquals(12_000, l.target)
        // The fixed overhead of 11K is included in the entire request and will not be deducted a second
        // time from the trigger point: the message still has 7K available and will not be overwhelmed as
        // soon as it starts.
        assertTrue(l.trigger - 11_000 >= 7_000)
    }

    @Test fun commonWindow128k() {
        val l = ContextLimits.of(128_000)
        assertEquals(108_000, l.usable)
        assertEquals(95_000, l.trigger)
        assertEquals(54_000, l.target)
    }

    @Test fun hugeWindow1m() {
        val l = ContextLimits.of(1_000_000)
        assertEquals(980_000, l.usable)
        assertEquals(967_000, l.trigger)
        assertEquals(490_000, l.target)
    }

    @Test fun triggerNeverBelowFloorNorAboveUsable() {
        for (w in listOf(100, 1_000, 4_096, 8_192, 32_000, 128_000, 1_000_000)) {
            val l = ContextLimits.of(w)
            assertTrue("$w", l.trigger >= w * ContextLimits.MIN_TRIGGER_SHARE && l.trigger <= l.usable)
            assertTrue("$w", l.target < l.trigger)
        }
        // According to the trigger point given by the model: the limit is between the lower limit and
        // the available window; the target then decreases
        assertEquals(16_000, ContextLimits.of(32_000, triggerOverride = 1_000).trigger)
        assertEquals(24_000, ContextLimits.of(32_000, triggerOverride = 99_999).trigger)
        val low = ContextLimits.of(1_000_000, triggerOverride = 600_000)
        assertEquals(600_000, low.trigger)
        assertEquals(490_000, low.target)
        assertEquals(0, ContextLimits.of(0).trigger)
    }
}
