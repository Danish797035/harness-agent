package com.meenai.mina.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OperatingPaceTest {
    @Test fun fastNeverWaitsOnAWebPage() {
        val pacing = OperatingPace.FAST.webPacing(Random(1))
        assertFalse(pacing.paced)
        pacing.noteLoad("https://example.com", 0)
        assertEquals(0L, pacing.noteLoad("https://example.com/watch", 1_000))
        assertEquals(0L, pacing.waitBefore("example.com", 1_000))
    }

    @Test fun standardWaitsMuchLessThanHuman() {
        val standard = OperatingPace.STANDARD.webPacing(Random(1))
        val human = OperatingPace.HUMAN.webPacing(Random(1))
        standard.noteLoad("https://example.com", 0)
        human.noteLoad("https://example.com", 0)
        val s = standard.noteLoad("https://example.com/watch", 1_000)
        val h = human.noteLoad("https://example.com/watch", 1_000)
        assertTrue("standard dwell $s", s in 1_500L..2_500L)
        assertTrue("human dwell $h", h in 4_000L..7_000L)
        assertTrue(s < h)
    }

    @Test fun carefulSitesStillWaitLongerThanNormalOnes() {
        val pacing = OperatingPace.STANDARD.webPacing(Random(1))
        pacing.noteLoad("https://shop.example.com", 0, com.meenai.mina.core.uistate.PlacePace.CAREFUL)
        val careful = pacing.noteLoad("https://shop.example.com/item", 1_000, com.meenai.mina.core.uistate.PlacePace.CAREFUL)
        assertTrue("careful dwell $careful", careful in 4_000L..7_000L)
    }
}
