package com.meenai.mina.cap.phone.a11y

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeWaitTest {
    private val before = IntArray(288) { 200 }
    private val after = IntArray(288) { 20 }
    /** The intermediate state where the picture is different every 50 ms (still drawing and playing). */
    private fun moving(t: Long) = IntArray(288) { ((t / 50 * 37 + it * 11) % 256).toInt() }

    /** Fake clock: sleep advances time; frameAt/lastEventAt gives the picture and the latest event time by time. */
    private class Fake(
        val frameAt: (Long) -> IntArray?,
        val lastEventAt: (Long) -> Long = { -10_000 },
        val treeChangedAt: Long = Long.MAX_VALUE,
    ) {
        var now = 0L
        suspend fun run(before: IntArray?) = ChangeWait.awaitStableChange(
            now = { now },
            sleep = { now += it },
            frame = { frameAt(now) },
            quietForMs = { now - lastEventAt(now) },
            treeChanged = { now >= treeChangedAt },
            beforeFrame = before,
        )
    }

    @Test fun slowPageChangeIsReportedAfterFramesSettle() = runTest {
        // It took 1.2 s for the page to start drawing, and it took 1.6 s to finish drawing; the event
        // has been very quiet, and the control tree has not changed (self-drawn interface)
        val f = Fake(frameAt = { t -> when { t < 1_200 -> before; t < 1_600 -> moving(t); else -> after } })
        assertTrue(f.run(before))
        assertTrue("waited for the frame to settle, at ${f.now}", f.now >= 1_600)
        assertTrue(f.now <= ChangeWait.CAP_MS)
    }

    @Test fun fastPathReturnsAsSoonAsChangeIsStable() = runTest {
        val f = Fake(frameAt = { t -> if (t < 100) before else after })
        assertTrue(f.run(before))
        assertTrue("returned early, at ${f.now}", f.now <= 2 * ChangeWait.POLL_MS)
    }

    @Test fun normalPageChangeReturnsSoonAfterEventsQuiet() = runTest {
        // Normal page turning: The event lasts until 100 ms, and the control tree and screen have
        // changed.
        val f = Fake(frameAt = { t -> if (t < 100) before else after }, lastEventAt = { t -> minOf(t, 100) }, treeChangedAt = 50)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now in 100 + ChangeWait.QUIET_MS..100 + ChangeWait.QUIET_MS + ChangeWait.POLL_MS)
    }

    @Test fun neverStillPageWithTreeChangeReturnsFast() = runTest {
        // Video and live broadcast: The picture keeps moving, but the event is quiet and the control
        // tree changes. There is no need to wait for the picture to stop.
        val f = Fake(frameAt = ::moving, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now <= ChangeWait.POLL_MS)
    }

    @Test fun nothingChangesReturnsFalseAtCap() = runTest {
        val f = Fake(frameAt = { before })
        assertFalse(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
        assertTrue(f.now < ChangeWait.CAP_MS + ChangeWait.POLL_MS)
    }

    @Test fun waitsForEventQuietBeforeJudging() = runTest {
        // The control tree changes at the beginning, but the event lasts until 1 s
        val f = Fake(frameAt = { after }, lastEventAt = { t -> minOf(t, 1_000) }, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("judged at ${f.now}", f.now >= 1_000 + ChangeWait.QUIET_MS)
    }

    @Test fun treeChangeWithoutFrameCounts() = runTest {
        // No picture (preview frame is not available): only view the control tree
        val f = Fake(frameAt = { null }, treeChangedAt = 900)
        assertTrue(f.run(null))
        assertTrue(f.now in 900..ChangeWait.CAP_MS)
    }

    @Test fun stillAnimatingAtCapButChangedCountsAsChanged() = runTest {
        val f = Fake(frameAt = ::moving)
        assertTrue(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
    }

    @Test fun eventsNeverQuietDecidesAtCapWithCurrentState() = runTest {
        // The event keeps going (such as a progress bar): when the upper limit is reached, the
        // conclusion is drawn based on the current state.
        val changed = Fake(frameAt = { after }, lastEventAt = { it }, treeChangedAt = 0)
        assertTrue(changed.run(before))
        assertTrue(changed.now >= ChangeWait.CAP_MS)
        assertFalse(Fake(frameAt = { before }, lastEventAt = { it }).run(before))
    }
}
