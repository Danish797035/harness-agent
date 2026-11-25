package com.meenai.mina.cap.phone.setup

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The time limit that can be pushed back: [lengthMs] starting from the last [extend].
 * [PairingCoordinator] uses it to determine how long it takes to pair. Every time the user returns to
 * pairing (click "Go to Wireless Debugging" and leave the guide page that stopped at the pairing step),
 * it will be recalculated from that time.
 */
internal class SlidingDeadline(private val lengthMs: Long, private val now: () -> Long) {
    @Volatile private var until = 0L

    fun extend() {
        until = now() + lengthMs
    }

    /** Wait for [job] to finish; the time limit can be pushed back while waiting. It is true if it is completed, and it is false if the time limit comes first. */
    suspend fun await(job: Job): Boolean {
        while (true) {
            val left = until - now()
            if (left <= 0) return false
            if (withTimeoutOrNull(left) { job.join() } != null) return true
        }
    }
}
