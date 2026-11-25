package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.cap.phone.display.FrameMath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillFrameReadTest {
    private val cells = FrameMath.GRID_W * FrameMath.GRID_H
    private val pageA = IntArray(cells) { 200 }
    private val pageB = IntArray(cells) { 20 }
    /** A different transition frame every 50 ms (the page is sliding in, the keyboard is rising). */
    private fun moving(t: Long) = IntArray(cells) { ((t / 50 * 37 + it * 11) % 256).toInt() }
    /** There is a small area on page A that keeps moving (an animated avatar): only moving a few frames. */
    private fun animatedA(t: Long) = pageA.copyOf().also { f -> for (i in 0 until 4) f[i] = if ((t / 100) % 2 == 0L) 0 else 255 }

    private class Clock { var now = 0L }

    @Test fun waitsForTheTransitionToEndBeforeReading() = runTest {
        val c = Clock()
        val frame = { if (c.now < 500) moving(c.now) else pageA }
        var readAt = -1L
        val r = StillFrameRead.read({ c.now }, { c.now += it }, frame) { readAt = c.now; "lines" }
        assertEquals("lines", r)
        assertTrue("read at $readAt", readAt >= 500)
    }

    @Test fun smallAnimationsDoNotDiscardTheText() = runTest {
        val c = Clock()
        // The avatar keeps moving: recognize it when it reaches the upper limit. After recognizing it,
        // only those few frames have changed. Use it as usual.
        val r = StillFrameRead.read({ c.now }, { c.now += it }, { animatedA(c.now) }) { c.now += 300; "lines" }
        assertEquals("lines", r)
        assertTrue(c.now <= StillFrameRead.STILL_CAP_MS + 400)
    }

    @Test fun pageSwitchDuringRecognitionReadsAgainOnTheNewPage() = runTest {
        val c = Clock()
        var reads = 0
        // The page changed when I read it for the first time: throw it away, click a new page and read
        // it again.
        val r = StillFrameRead.read({ c.now }, { c.now += it }, { if (reads == 0 || c.now < 1_000) pageA else pageB }) {
            reads++
            if (reads == 1) c.now = 1_000
            "read $reads"
        }
        assertEquals("read 2", r)
        // It keeps changing on the whole page: I can’t get it right even if I admit it twice, so I won’t
        // give it.
        var n = 0
        assertNull(StillFrameRead.read({ c.now }, { c.now += it }, { if (n % 2 == 0) pageA else pageB }) { n++; "x" })
    }

    @Test fun noFrameMeansNothingToRead() = runTest {
        val c = Clock()
        assertNull(StillFrameRead.read<String>({ c.now }, { c.now += it }, { null }) { null })
        assertTrue(StillFrameRead.awaitStill({ c.now }, { c.now += it }, { null }))
        assertFalse(StillFrameRead.awaitStill({ c.now }, { c.now += it }, { moving(c.now) }))
    }

    @Test fun pageChangedNeedsAQuarterOfTheScreen() {
        val few = pageA.copyOf().also { for (i in 0 until 10) it[i] = 0 }
        assertTrue(FrameMath.differs(pageA, few))
        assertFalse(FrameMath.pageChanged(pageA, few))
        assertTrue(FrameMath.pageChanged(pageA, pageB))
        assertFalse(FrameMath.pageChanged(null, pageB))
    }
}
