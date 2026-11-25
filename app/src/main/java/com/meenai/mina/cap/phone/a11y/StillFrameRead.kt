package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.cap.phone.display.FrameMath

/**
 * Screen requirements before and after on-screen text recognition (OCR). The self-drawing interface
 * almost never sends accessibility events. "Event quiet" does not mean that the drawing is completed:
 * when the page is just entered and the keyboard is raised, the transition frame is recognized, or the
 * screen has been changed after the recognition, so it has to be discarded.
 *
 * - - Wait for the screen to stabilize before recognizing: the two frames before and after are similar
 * ([FrameMath.differs] is false), wait at most [STILL_CAP_MS]; if there are parts that are always moving
 * (animated avatars, cursors), recognize them at that time.
 * - - Recognize that the entire page has changed ([FrameMath.pageChanged]): the position of the
 * characters no longer matches, press a new page to recognize again, up to [ATTEMPTS] times; local
 * changes do not affect the position of the characters, so use them as they are.
 *
 * Clock, wait, and frame fetching are all injected by the caller to facilitate testing on the JVM.
 */
internal object StillFrameRead {
    const val POLL_MS = 120L
    const val STILL_CAP_MS = 1_200L
    const val ATTEMPTS = 2

    /** [recognize] recognizes the current frame; when it returns null (no picture), it returns null directly. Every time the entire page changes, null is returned. */
    suspend fun <T : Any> read(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?, recognize: suspend () -> T?): T? {
        repeat(ATTEMPTS) {
            awaitStill(now, sleep, frame)
            val before = frame()
            val result = recognize() ?: return null
            if (!FrameMath.pageChanged(before, frame())) return result
        }
        return null
    }

    /** Wait until the two frames before and after are similar; return false when the time limit is still moving. It does not wait when there is no picture. */
    suspend fun awaitStill(now: () -> Long, sleep: suspend (Long) -> Unit, frame: () -> IntArray?): Boolean {
        val deadline = now() + STILL_CAP_MS
        var prev = frame() ?: return true
        while (true) {
            sleep(minOf(POLL_MS, (deadline - now()).coerceAtLeast(0)))
            val cur = frame() ?: return true
            if (!FrameMath.differs(prev, cur)) return true
            if (now() >= deadline) return false
            prev = cur
        }
    }
}
