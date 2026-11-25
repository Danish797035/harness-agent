package com.meenai.mina.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Press and hold state machine to speak: events of pressing, sliding up to cancel, releasing, ending at
 * the point, and closing the conversation, to figure out what the interface should display at the
 * moment. No touching the interface or touching gestures, the dialogue input field and the floating
 * capsule are shared; the session is opened and handed in by the caller ([press]), the event is received
 * and transferred in by the caller ([onEvent]), [tick] is scheduled by the caller when pressing and
 * holding (timing, ending with [maxMs]). The session ends by itself while the hand is still pressed (the
 * mobile phone has built-in recognition that it will not reopen after a sentence has been spoken, or it
 * will make an error in the middle but the word has been heard): keep the result first, wait for the
 * finger to leave, and then process it according to the landing point at that time - stop at Cancel and
 * then throw it away, otherwise it will be handed over; in this way, the word will not be sent out when
 * the user wants to cancel. Only raised in one thread (main thread).
 */
class HoldToTalk(private val clock: () -> Long, private val maxMs: Long = 60_000, private val minMs: Long = 500) {
    sealed interface State {
        data object Idle : State

        /**
         * Press (or just release, the phone's built-in recognition is still finishing): [stable] has
         * been determined, [tail] may have several changed characters at the end. [ended]: The hand is
         * still pressed, but the session has ended, and the results are waiting to be released.
         */
        data class Listening(
            val stable: String,
            val tail: String,
            val level: Float,
            val elapsedMs: Long,
            val cancelArmed: Boolean,
            val live: Boolean,
            val ended: Boolean = false,
        ) : State

        /** Service provider: After releasing, the text will be transferred. */
        data object Converting : State

        /** Use the interface to fill in the input box, then [reset]. */
        data class Done(val text: String) : State

        /** null = Didn't hear clearly (didn't recognize anything, or pressed too short). */
        data class Problem(val why: SpeechFailure?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: SpeechSession? = null
    private var startedAt = 0L
    /** Once it has been released (or reached the point), leave it to the end of the session; then release it again or reach the point without repeating the adjustment. */
    private var released = false
    /** The result of the conversation (Done or Problem(null)) has been given while the hand is still pressed, and then handed over after releasing the hand. */
    private var pending: State? = null

    fun press(session: SpeechSession) {
        // The previous paragraph has not been completed (rarely, for example, the previous paragraph is
        // still being transferred): the user clicks again this time and does not want the previous
        // paragraph.
        this.session?.cancel()
        this.session = session
        startedAt = clock()
        released = false
        pending = null
        _state.value = State.Listening("", "", 0f, 0, cancelArmed = false, live = session.live)
    }

    /** The finger moves up [upPx] from the pressed position; if it exceeds [thresholdPx], it means "release to cancel", and move it back and resume. */
    fun drag(upPx: Float, thresholdPx: Float) = armCancel(upPx > thresholdPx)

    /** The finger is not on "Cancel" at the moment; the landing point of the suspended capsule is not the distance of the upward slide, it is directly told here. */
    fun armCancel(armed: Boolean) {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        if (armed != s.cancelArmed) _state.value = s.copy(cancelArmed = armed)
    }

    fun release() {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val early = pending
        val current = session
        if (current == null && early == null) return
        when {
            s.cancelArmed -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Idle
            }
            // Pressing too short is probably an accidental touch: it will not be transferred (the
            // service provider will also charge a credit), and it will be treated as if you did not hear
            // clearly.
            clock() - startedAt < minMs -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Problem(null)
            }
            // The session has ended while the hand is still pressed: only now will the retained results
            // be handed over.
            early != null -> {
                pending = null
                _state.value = early
            }
            current != null -> {
                released = true
                current.release()
                // From the service provider: it will take a while for the recorder to stop before the
                // Converting event arrives; it will display Converting as soon as you release it,
                // preventing the input box from flashing back to occupy the space first.
                if (!s.live) _state.value = State.Converting
            }
        }
    }

    /** Press and hold to adjust the time: update the time; release it when it reaches [maxMs] - the recording will stop automatically without further notification, and the last word will be lost if it is not finished. */
    fun tick() {
        val s = _state.value as? State.Listening ?: return
        if (released || (session == null && pending == null)) return
        val elapsed = clock() - startedAt
        if (elapsed >= maxMs) {
            _state.value = s.copy(elapsedMs = maxMs)
            release()
        } else if (elapsed != s.elapsedMs) {
            _state.value = s.copy(elapsedMs = elapsed)
        }
    }

    fun onEvent(e: SpeechEvent) {
        if (session == null) return
        val now = _state.value
        when (e) {
            // After converting text, the service provider's engine may be delayed by a certain volume:
            // it will only be recognized when listening.
            is SpeechEvent.Level -> if (now is State.Listening) _state.value = now.copy(level = e.value)
            is SpeechEvent.Partial -> if (now is State.Listening) _state.value = now.copy(stable = e.stable, tail = e.tail)
            SpeechEvent.Converting -> _state.value = State.Converting
            is SpeechEvent.Final -> {
                session = null
                val text = e.text.trim()
                val result = if (text.isEmpty()) State.Problem(null) else State.Done(text)
                if (released || now !is State.Listening) {
                    _state.value = result
                } else {
                    // The hand is still pressed: keep it first, the interface will still display the
                    // word you heard; when you release it, stop on Cancel and throw it away, otherwise
                    // you will hand it over.
                    pending = result
                    _state.value = now.copy(stable = text, tail = "", level = 0f, ended = true)
                }
            }
            is SpeechEvent.Failed -> {
                session = null
                val result = State.Problem(e.why)
                // If you make a mistake, you don’t have to wait until you release it (there will be no
                // words if you press it again); just keep it when your hand stops on Cancel, and no
                // longer prompt when you release it on Cancel.
                if (!released && now is State.Listening && now.cancelArmed) {
                    pending = result
                    _state.value = now.copy(level = 0f, ended = true)
                } else {
                    _state.value = result
                }
            }
        }
    }

    /** Leave the page, turn the screen, and go to the background of the app: discard this paragraph and return to normal. Can be adjusted repeatedly. */
    fun dispose() {
        session?.cancel()
        session = null
        released = false
        pending = null
        _state.value = State.Idle
    }

    /** After the interface is used up, [State.Done]/[State.Problem] returns to normal; it is still listening and spinning. */
    fun reset() {
        val s = _state.value
        if (s is State.Done || s is State.Problem) _state.value = State.Idle
    }

    companion object {
        /**
         * The words you say are followed by the existing words in the input box: if the words on both
         * sides are Chinese, Japanese and Korean characters or full-width punctuation, they are directly
         * connected, otherwise a space is added in the middle (no more when the existing words end with
         * a blank).
         */
        fun join(existing: String, spoken: String): String {
            if (existing.isEmpty()) return spoken
            if (spoken.isEmpty()) return existing
            val left = existing.last()
            if (left.isWhitespace()) return existing + spoken
            return if (isCjk(left) && isCjk(spoken.first())) existing + spoken else "$existing $spoken"
        }

        private fun isCjk(ch: Char): Boolean {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) return true
            return when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }
        }
    }
}
