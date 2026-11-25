package com.meenai.mina.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldToTalkTest {
    /** Fake session: only remember how many times release and cancel have been called; the event is directly fed to [HoldToTalk.onEvent] by the test. */
    private class FakeSession(override val live: Boolean = true) : SpeechSession {
        override val events: Flow<SpeechEvent> = emptyFlow()
        var released = 0
        var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++ }
    }

    private var now = 0L
    private val hold = HoldToTalk(clock = { now })

    private fun pressed(live: Boolean = true): FakeSession = FakeSession(live).also { hold.press(it) }

    @Test
    fun partialShowsLive() {
        pressed()
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertEquals("帮我", s.stable)
        assertEquals("订票", s.tail)
        assertTrue(s.live)
    }

    @Test
    fun releaseGivesDone() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun cancelArmedDropsText() {
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(1, session.cancelled)
        assertEquals(0, session.released)
        // After canceling, late results will no longer appear.
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun dragBackDisarms() {
        pressed()
        hold.drag(100f, 80f)
        hold.drag(10f, 80f)
        assertFalse((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
    }

    @Test
    fun tooShortIsNoMatch() {
        val session = pressed()
        now = 300
        hold.release()
        // If it is too short, it will not be transferred to the conversation (the service provider will
        // also need to pay the credit): just throw it away and pretend that you didn’t hear it clearly.
        assertEquals(0, session.released)
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
        // The result of late session does not cover "didn't hear clearly"
        hold.onEvent(SpeechEvent.Final("帮我"))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun blankFinalIsNoMatch() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.onEvent(SpeechEvent.Final("  "))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun minimumIsConfigurable() {
        // When the input field starts listening, the finger has already been pressed for 300ms. Only
        // 200ms are counted: in total, less than half a second is too short.
        val short = HoldToTalk(clock = { now }, minMs = 200)
        val session = FakeSession().also { short.press(it) }
        now = 250
        short.release()
        assertEquals(1, session.released)
        assertEquals(0, session.cancelled)
    }

    @Test
    fun maxDurationReleases() {
        val session = pressed()
        now = 30_000
        hold.tick()
        assertEquals(0, session.released)
        assertEquals(30_000L, (hold.state.value as HoldToTalk.State.Listening).elapsedMs)
        now = 60_000
        hold.tick()
        assertEquals(1, session.released)
        // After reaching the point, tick again without repeating the ending.
        now = 61_000
        hold.tick()
        assertEquals(1, session.released)
    }

    @Test
    fun providerShowsConverting() {
        pressed(live = false)
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Converting)
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        // The service provider's engine may be a volume late: it no longer returns to listening when
        // converting text.
        hold.onEvent(SpeechEvent.Level(0.5f))
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        hold.onEvent(SpeechEvent.Final("你好"))
        assertEquals(HoldToTalk.State.Done("你好"), hold.state.value)
    }

    @Test
    fun providerConvertsRightOnRelease() {
        val session = pressed(live = false)
        now = 2_000
        hold.release()
        // It takes a while for the recorder to stop, and the Converting event arrives late; the moment
        // it is released, the text should be displayed, without flashing back to the placeholder.
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        assertEquals(1, session.released)
        hold.tick()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        hold.onEvent(SpeechEvent.Final(""))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun failureSurfaces() {
        pressed()
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertEquals(HoldToTalk.State.Problem(SpeechFailure.Busy), hold.state.value)
    }

    @Test
    fun disposeCancels() {
        val session = pressed()
        hold.dispose()
        hold.dispose()
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun levelMovesWhileListening() {
        pressed()
        hold.onEvent(SpeechEvent.Level(0.7f))
        assertEquals(0.7f, (hold.state.value as HoldToTalk.State.Listening).level, 0.0001f)
    }

    @Test
    fun resetAfterDoneGoesIdle() {
        pressed()
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Final("好"))
        hold.reset()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun pressAgainDropsTheOldSession() {
        val first = pressed()
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
    }

    @Test
    fun earlyFinalWaitsForReleaseThenAppliesOnce() {
        // The built-in recognition of the mobile phone ends on its own while the hand is still pressed:
        // if you don’t hand it over first, it will count if you leave your finger.
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertTrue(s.ended)
        assertEquals("帮我订票", s.stable)
        now = 3_000
        hold.tick()
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
        // The session has ended, no need to finish it; no more handing over will be done once again.
        assertEquals(0, session.released)
        hold.reset()
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalWithCancelArmedIsDropped() {
        val session = pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(0, session.released)
    }

    @Test
    fun earlyFinalThenDragToCancelIsDropped() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalThenDragBackOffCancelIsApplied() {
        pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(10f, 80f)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun earlyFailureWithCancelArmedIsDropped() {
        pressed()
        now = 2_000
        hold.armCancel(true)
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertTrue((hold.state.value as HoldToTalk.State.Listening).ended)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalAppliedWhenTimeRunsOut() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("好"))
        now = 60_000
        hold.tick()
        assertEquals(HoldToTalk.State.Done("好"), hold.state.value)
    }

    @Test
    fun pressWhileConvertingDropsTheOldSession() {
        // When the service provider's transcription is stuck, the user presses again: the old part is
        // cancelled, the new part is listened to as usual, and the results are produced as usual.
        val first = pressed(live = false)
        now = 2_000
        hold.release()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        now = 4_000
        hold.release()
        assertEquals(1, second.released)
        hold.onEvent(SpeechEvent.Final("新的"))
        assertEquals(HoldToTalk.State.Done("新的"), hold.state.value)
    }

    @Test
    fun joinRules() {
        assertEquals("你好", HoldToTalk.join("", "你好"))
        assertEquals("帮我订票", HoldToTalk.join("帮我", "订票"))
        assertEquals("book a train", HoldToTalk.join("book", "a train"))
        assertEquals("帮我 book", HoldToTalk.join("帮我", "book"))
        assertEquals("好的，订票", HoldToTalk.join("好的，", "订票"))
        assertEquals("book ", HoldToTalk.join("book ", ""))
        assertEquals("book a", HoldToTalk.join("book ", "a"))
    }
}
