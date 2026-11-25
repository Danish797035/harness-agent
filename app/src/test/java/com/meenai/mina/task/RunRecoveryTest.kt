package com.meenai.mina.task

import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.run.Channel
import com.meenai.mina.core.run.ChannelInputSink
import com.meenai.mina.core.run.ChannelPeer
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.run.MemoryRunStore
import com.meenai.mina.core.run.Outbox
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunPhase
import com.meenai.mina.core.run.SendOutcome
import com.meenai.mina.core.run.StoredRun
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunRecoveryTest {
    @get:Rule val texts = EnglishTexts()

    @get:Rule val tmp = TemporaryFolder()

    private class RecordingChannel : Channel {
        val sent = mutableListOf<Delivery>()
        override val id = LocalChannel.ID
        override val confirms = true
        override fun start(sink: ChannelInputSink) = AutoCloseable { }
        override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome { sent += delivery; return SendOutcome.Delivered }
    }

    private object NoTriggers : TriggerOutcomes {
        override fun interrupted(key: String, reason: String) {}
        override fun delivered(key: String) {}
        override fun actionFailed(key: String, reason: String) {}
    }

    private inner class Rig(val scope: TestScope) {
        val store = MemoryRunStore()
        val channel = RecordingChannel()
        val outbox = Outbox()
        val conversations = ConversationStore(tmp.newFolder())
        val recovery = RunRecovery(store, outbox, channel, NoTriggers, conversations, scope)
    }

    private fun run(id: String, phase: RunPhase = RunPhase.Running) = StoredRun(RunId(id), "thread-$id", "目标 $id", phase)

    // After the process is killed, as soon as the app is started (without any service), it will be
    // marked as interrupted and informed
    @Test fun appStartMarksUnfinishedRunsInterruptedAndTellsUser() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))
        rig.store.save(run("done", RunPhase.Ended))

        val interrupted = rig.recovery.recover()

        assertEquals(listOf(RunId("a")), interrupted.map { it.id })
        assertEquals(RunPhase.Interrupted, rig.store.get(RunId("a"))?.phase)
        assertEquals(RunPhase.Ended, rig.store.get(RunId("done"))?.phase)
        assertEquals(listOf("interrupted:a"), rig.channel.sent.map { it.id })
    }

    // Both App startup and task service startup are triggered: only informed once, and the service does
    // nothing that time.
    @Test fun secondTriggerDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))

        rig.recovery.recover()
        // Then a new run was started, and the service was started and adjusted again.
        rig.store.save(run("b", RunPhase.Preparing))
        val second = rig.recovery.recover()

        assertTrue(second.isEmpty())
        assertEquals(RunPhase.Preparing, rig.store.get(RunId("b"))?.phase)
        assertEquals(listOf("interrupted:a"), rig.channel.sent.map { it.id })
    }

    // The reply has been saved but not delivered: it will be sent once, and it will not be sent again if
    // it is triggered again.
    @Test fun redeliversSavedReplyOnlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a", RunPhase.Finishing).copy(reply = "结果", deliveryId = "d1"))

        rig.recovery.recover()
        rig.recovery.recover()

        assertEquals(listOf("d1"), rig.channel.sent.map { it.id })
        assertEquals(RunPhase.Ended, rig.store.get(RunId("a"))?.phase)
    }

    // The run running at the moment cannot be treated as an interrupt.
    @Test fun liveRunsAreNotInterrupted() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("live"))
        rig.store.save(run("dead"))

        val interrupted = rig.recovery.recover(live = setOf(RunId("live")))

        assertEquals(listOf(RunId("dead")), interrupted.map { it.id })
        assertEquals(RunPhase.Running, rig.store.get(RunId("live"))?.phase)
        assertEquals(listOf("interrupted:dead"), rig.channel.sent.map { it.id })
    }

    // When the task service is destroyed, the interrupt is marked (the process is still there): inform
    // it once on the spot, and it will not be repeated again (next time it is started).
    @Test fun runInterruptedByServiceDestroyIsToldExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))
        rig.store.interruptUnfinished()

        rig.recovery.settleInterrupted()
        rig.recovery.settleInterrupted()
        rig.recovery.recover()

        assertEquals(listOf("interrupted:a"), rig.channel.sent.map { it.id })
        assertEquals(RunPhase.Interrupted, rig.store.get(RunId("a"))?.phase)
    }

    // The interruption operation that has been notified long ago will no longer be prompted.
    @Test fun alreadyToldRunIsNotToldAgain() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("old", RunPhase.Interrupted))
        rig.outbox.deliver(rig.channel, ChannelPeer(LocalChannel.ID, "phone-owner", "thread-old"),
            Delivery.Notice("interrupted:old", "thread-old", "旧提示"))
        rig.channel.sent.clear()

        rig.recovery.settleInterrupted()

        assertTrue(rig.channel.sent.isEmpty())
        assertTrue(rig.conversations.turns("thread-old").isEmpty())
    }

    // Interruptions are written into the conversation record and have the same status as the failed
    // round.
    @Test fun interruptionIsWrittenIntoConversation() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.store.save(run("a"))
        rig.store.interruptUnfinished()

        rig.recovery.settleInterrupted()
        rig.recovery.settleInterrupted()

        val turns = rig.conversations.turns("thread-a")
        assertEquals(1, turns.size)
        assertEquals("failed", turns.single().status)
        assertEquals("目标 a", turns.single().userText)
        assertEquals("The task was interrupted. You can start it again.", turns.single().outcome)
    }
}
