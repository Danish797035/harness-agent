package com.meenai.mina.voice

import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * When you press and hold a talk session, the two engines (mobile phone built-in recognition, service
 * provider transliteration) will have the same interface: the interface only accepts [events], adjusts
 * [release] when releasing, and adjusts [cancel] when canceling, regardless of which engine is behind
 * it.
 */
sealed interface SpeechEvent {
    /** 0..1, draw a waveform. */
    data class Level(val value: Float) : SpeechEvent

    /** The full text I have heard so far: [tail] is a few words at the end that may change, and the rest is [stable]. */
    data class Partial(val stable: String, val tail: String) : SpeechEvent

    /** Service Provider: Release it and then switch. */
    data object Converting : SpeechEvent

    /** Final text; empty string = didn’t hear clearly. */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val why: SpeechFailure) : SpeechEvent
}

sealed interface SpeechFailure {
    data object NoPermission : SpeechFailure
    data object Busy : SpeechFailure
    data object Network : SpeechFailure
    /** The microphone cannot be started (the identification service reports an error on the client, an error is thrown when starting to listen, the recording of the service provider's route cannot be started): It is probably occupied by other applications. */
    data object MicUnavailable : SpeechFailure
    /**
     * The identification service reported a recording error (ERROR_AUDIO): Even if the recording
     * permission is given, the recording may not be possible. For example, the system microphone is
     * turned off using the main switch, or the microphone is turned off separately in the system
     * permission management. The interface tells you where to look.
     */
    data object AudioError : SpeechFailure
    data class Provider(val error: ModelError) : SpeechFailure
    data class Unavailable(val why: SpeechEngineSpec.Why) : SpeechFailure
}

interface SpeechSession {
    /** Only given to one collector; each event is sent only once, and the stream ends after [SpeechEvent.Final]/[SpeechEvent.Failed] or [cancel]. */
    val events: Flow<SpeechEvent>

    /** Can produce words while speaking (true for system and native streaming ASR, false for whole file transcoding). */
    val live: Boolean

    /** Release: wrap up and give Final. */
    fun release()

    /** Throw away; idempotent. */
    fun cancel()
}

/**
 * Event exit of the session: unbounded buffering, the interface will not be lost even if it is started
 * later; it will be closed after the end event is sent. When canceling, throw away the items that have
 * not yet been collected - the user no longer wants this time, and late results should not appear again.
 */
internal class SessionEvents {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    @Volatile
    var cancelled = false
        private set

    @Volatile
    var finished = false
        private set

    val flow: Flow<SpeechEvent> = channel.receiveAsFlow().filter { !cancelled }

    fun send(e: SpeechEvent) {
        if (!finished && !cancelled) channel.trySend(e)
    }

    /** Send the last event and close it; do nothing if it is finished or canceled. Return to see if it was really sent this time. */
    @Synchronized
    fun finish(e: SpeechEvent): Boolean {
        if (finished || cancelled) return false
        finished = true
        channel.trySend(e)
        channel.close()
        return true
    }

    /** Return this time if it is the first time to cancel. */
    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        channel.close()
        return true
    }
}

/** A session that cannot be used from the beginning (no permissions, engine unavailable): only send a Failed. */
internal class FailedSession(why: SpeechFailure) : SpeechSession {
    private val out = SessionEvents().also { it.finish(SpeechEvent.Failed(why)) }
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false
    override fun release() {}
    override fun cancel() { out.cancel() }
}
