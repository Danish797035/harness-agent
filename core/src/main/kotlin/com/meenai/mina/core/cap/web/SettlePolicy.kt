package com.meenai.mina.core.cap.web

import kotlinx.coroutines.delay

/**
 * How long to wait after a web page action. Wait for the page to be quiet and the same as before
 * ([domQuiet] with the old upper limit [domCapMs], the page has been changing and it will wait until the
 * upper limit is reached and not report); the network is another condition: only wait when the request
 * sent by this action is still in progress ([networkBusy]), the automatic action will start to total up
 * to [netCapMs], and after the request is completed, take another look at the page to be quiet in the
 * remaining time. When the network is already idle, the wait is just as long as when there is no network
 * recording. Returns whether the network is quiet within the upper limit (true when not waiting for the
 * network).
 */
object SettlePolicy {
    suspend fun settle(
        clock: () -> Long,
        domQuiet: suspend (capMs: Long) -> Boolean,
        networkBusy: () -> Boolean,
        domCapMs: Long,
        netCapMs: Long,
        pollMs: Long,
    ): Boolean {
        val deadline = clock() + netCapMs
        domQuiet(domCapMs)
        if (!networkBusy()) return true
        while (networkBusy()) {
            if (clock() >= deadline) return false
            delay(pollMs)
        }
        val left = deadline - clock()
        if (left > 0) domQuiet(minOf(left, domCapMs))
        return true
    }

    /**
     * Wait for the text to be stable before reading the page (not just the page is quiet: during lazy
     * loading and batch rendering, the DOM may be quiet for a while and move at other times). The text
     * (length, abstract) is fetched every [intervalMs], and the [stableIntervals] times in a row are
     * considered stable as the last time; when there is still no word, it is [emptyIntervals] times in a
     * row (the page is probably still loading). When [capMs] is not stable, it returns false; if a
     * sample cannot be obtained, it returns false.
     */
    suspend fun contentStable(
        clock: () -> Long,
        sample: suspend () -> Pair<Int, Int>?,
        intervalMs: Long,
        stableIntervals: Int,
        emptyIntervals: Int,
        capMs: Long,
    ): Boolean {
        val deadline = clock() + capMs
        var last = sample() ?: return false
        var same = 0
        while (true) {
            if (clock() + intervalMs > deadline) return false
            delay(intervalMs)
            val now = sample() ?: return false
            same = if (now == last) same + 1 else 0
            last = now
            if (same >= if (now.first == 0) emptyIntervals else stableIntervals) return true
        }
    }
}
