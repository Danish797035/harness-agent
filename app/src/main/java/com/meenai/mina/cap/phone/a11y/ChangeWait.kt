package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.cap.phone.display.FrameMath

/**
 * After the action, wait for the interface to stabilize before judging whether there are any changes.
 * Just watching the event quiet is not enough: a slow-responsive page may not start drawing when it is
 * quiet, and it will be misjudged as unchanged, and the model will repeat points.
 *
 * - - Accessibility event is quiet [QUIET_MS] and the control tree has changed: it is immediately judged
 * as changed, without waiting for the screen to stop (video and live broadcast screens are always
 * moving).
 * - - Only the picture has changed (self-drawn interface): it must be similar between the two frames
 * before it is judged to have changed, so as not to regard the transition in the middle of the painting
 * as the result.
 * - - To judge "no change", the event must be quiet and the picture stable. Wait until [CAP_MS].
 * - - When reaching [CAP_MS], no matter whether the event is still coming or the screen is still moving,
 * the conclusion will be drawn based on the current state:
 * If the control tree or screen is different from before the action, it has changed. Pages with non-stop
 * events (progress bar, timer) will not cause the action to get stuck exceeding the upper limit.
 *
 * The only difference is that when the event is quiet, sleep according to the remaining quiet time. The
 * normal page can be returned about [QUIET_MS] after the last event. Clock, wait, and frame fetching are
 * all injected by the caller to facilitate testing on the JVM.
 */
internal object ChangeWait {
    const val POLL_MS = 150L
    const val QUIET_MS = 400L
    const val CAP_MS = 2_500L
    private const val MIN_SLEEP_MS = 10L

    /**
     * @param frame The brightness grid of the current preview frame, which is null when there is no
     * picture (at this time, only the control tree and events are quiet)
     * @param quietForMs How long has passed since the last interface event
     * @param treeChanged Is the control tree fingerprint different from before the action?
     * @param beforeFrame Brightness grid before action
     */
    suspend fun awaitStableChange(
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        frame: () -> IntArray?,
        quietForMs: () -> Long,
        treeChanged: () -> Boolean,
        beforeFrame: IntArray?,
    ): Boolean {
        val deadline = now() + CAP_MS
        var prev = frame()
        var wait = POLL_MS
        while (true) {
            sleep(minOf(wait, (deadline - now()).coerceAtLeast(0)))
            val cur = frame()
            val quietFor = quietForMs()
            val quiet = quietFor >= QUIET_MS
            if (quiet && treeChanged()) return true
            val stable = if (prev == null || cur == null) prev == null && cur == null else !FrameMath.differs(prev, cur)
            val frameChanged = FrameMath.differs(beforeFrame, cur)
            if (quiet && stable && frameChanged) return true
            if (now() >= deadline) return frameChanged || treeChanged()
            wait = if (quiet) POLL_MS else minOf(POLL_MS, QUIET_MS - quietFor).coerceAtLeast(MIN_SLEEP_MS)
            prev = cur
        }
    }
}
