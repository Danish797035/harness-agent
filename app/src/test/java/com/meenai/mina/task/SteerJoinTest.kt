package com.meenai.mina.task

import org.junit.Assert.assertEquals
import org.junit.Test

class SteerJoinTest {
    private val notes = listOf(
        SteerNote("m1", "改成查明天", SteerState.Received),
        SteerNote("m2", "顺便看看后天", SteerState.Received),
    )

    // A batch of two-sentence interruptions are given to the model together: the interrupted paragraph
    // is only attached to the first sentence, but not to the second sentence.
    @Test fun interruptedReplyGoesToTheFirstSteerOfTheBatch() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf(SteerState.Joined, SteerState.Joined), after.map { it.state })
        assertEquals(listOf("今天晴", null), after.map { it.interrupted })
    }

    // Just change direction when not interrupting anything
    @Test fun steerWithoutInterruptionOnlyChangesState() {
        val after = SteerJoin().joined(notes, "m2")
        assertEquals(listOf(SteerState.Received, SteerState.Joined), after.map { it.state })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    // Clear once used: The next batch of comments will not contain the last paragraph.
    @Test fun interruptedReplyIsUsedOnce() {
        val join = SteerJoin()
        join.interrupted("今天晴")
        val first = join.joined(notes, "m1")
        val second = join.joined(first + SteerNote("m3", "算了", SteerState.Received), "m3")
        assertEquals(listOf("今天晴", null, null), second.map { it.interrupted })
    }

    // The replies that have been finished before interrupting are also linked to the first sentence of
    // this batch, and are recorded separately from the interrupted ones.
    @Test fun finishedReplyGoesToTheFirstSteerAndIsUsedOnce() {
        val join = SteerJoin()
        join.finished("光合作用是……的过程。")
        val after = join.joined(join.joined(notes, "m1"), "m2")
        assertEquals(listOf("光合作用是……的过程。", null), after.map { it.finished })
        assertEquals(listOf(null, null), after.map { it.interrupted })
    }

    @Test fun remoteStatusExcludesLocalMessagesAndDistinguishesReceiptConsumptionAndDiscard() {
        val local = SteerNote("local", "phone-only text", SteerState.Joined)
        val remote = SteerNote("remote", "only today", SteerState.Received, remote = true)
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "received")), remoteSteers(listOf(local, remote)))
        val joined = SteerJoin().joined(listOf(local, remote), "remote")
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "joined")), remoteSteers(joined))
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(remote.copy(state = SteerState.Cancelled))))
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "joined")),
            remoteSteers(joined, finished = true))
    }

    // Remote interruption cannot be withdrawn originally; if it is withdrawn, it will only be reported
    // and useless, and there will be no more external status; the withdrawn on the mobile phone will not
    // be reported to the computer
    @Test fun withdrawnMapsToNotAppliedAndLocalWithdrawalsStayHidden() {
        val remote = SteerNote("remote", "only today", SteerState.Withdrawn, remote = true)
        val local = SteerNote("local", "phone-only text", SteerState.Withdrawn)
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "not_applied")), remoteSteers(listOf(local, remote)))
        assertEquals(listOf(com.meenai.mina.core.remote.RemoteSteer("remote", "not_applied")),
            remoteSteers(listOf(local, remote), finished = true))
    }
}
