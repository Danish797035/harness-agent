package com.meenai.mina.voice

/**
 * When the recognition service ends for a period of time but no words are heard (no matching, no
 * speaking, empty results) during the press and hold period, should you restart it and how long should
 * it take? A normal empty segment (the user pauses for a few seconds) will be restarted immediately;
 * some services will be empty again as soon as they are restarted. If the service returns to empty soon,
 * it will be restarted after a while. If it is too many times, it will not be restarted and the end will
 * be pressed according to what has been heard, so as to avoid the main thread being started, wrongly,
 * and started again. Pure logic, the time is given by the caller.
 */
class Restarts {
    sealed interface Decision {
        data class Restart(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    private var quick = 0
    private var last = Long.MIN_VALUE

    /** Another empty section, [now] is the moment (milliseconds, monotonic clock). */
    fun onEmpty(now: Long): Decision {
        quick = if (last != Long.MIN_VALUE && now - last <= WINDOW_MS) quick + 1 else 1
        last = now
        return when {
            quick > MAX_QUICK -> Decision.GiveUp
            quick > QUICK -> Decision.Restart(BACKOFF_MS)
            else -> Decision.Restart(0)
        }
    }

    /** The word was heard: recount. */
    fun onSpeech() {
        quick = 0
        last = Long.MIN_VALUE
    }

    companion object {
        /** If it is within such a long time since the last empty period, it is considered as "it will be empty again soon". */
        const val WINDOW_MS = 2_000L
        /** After so many times it quickly returned to empty space, it started to open again after a while. */
        const val QUICK = 3
        /** If it closes short more than once this many times in a row, it will not be reopened again. */
        const val MAX_QUICK = 8
        const val BACKOFF_MS = 250L
    }
}
