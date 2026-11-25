package com.meenai.mina.core.agent

import com.meenai.mina.core.net.ProxyFailure
import com.meenai.mina.core.model.ModelError
import kotlin.random.Random

/**
 * If the model call encounters a temporary failure, should we try again and how long should we wait?
 * Temporary faults: current limit (429), server error (5xx), failure to connect/connection
 * disconnected/timeout, streaming reply is disconnected after the end of the connection (at this time,
 * the reply has not been written into the history, and no tools have been executed, so it is safe to
 * resend). Other errors (Key, quota, model name, content rejected, other 4xx, cannot be parsed) will be
 * the same if you try again, and there will be no retry; if the context is too long, it will be
 * compressed. If the current limit is set with Retry-After, just wait as it is (there is an upper
 * limit); otherwise, the exponential backoff and jitter will be used, and each call will be retried up
 * to [maxRetries] times. There is an upper limit on the total time spent waiting for retries in a task.
 * [taskWaitBudgetMs] (default 5 minutes, just a safety net): If you make an error after using it, just
 * hand it over without waiting. Pure function: The jitter is given by [jitter] (0..1), and is changed to
 * a fixed value in the test.
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    private val baseMs: Long = 1_000,
    private val maxBackoffMs: Long = 8_000,
    private val retryAfterCapMs: Long = 30_000,
    /** The upper limit of the sum of all retry waits in a task. */
    val taskWaitBudgetMs: Long = 5 * 60_000,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    init {
        require(maxRetries >= 0 && baseMs > 0 && maxBackoffMs >= baseMs && retryAfterCapMs >= 0 && taskWaitBudgetMs >= 0)
    }

    /**
     * This call has been retried [retriesSoFar] times and then encountered [e]. The task has been
     * waiting for retry. [waitedInTaskMs]: Returns the number of milliseconds to wait before trying
     * again; it should not try again (it is not a temporary failure, the number of times is up, waiting
     * this time will exceed the waiting limit of the task) is null.
     */
    fun delayFor(e: Throwable, retriesSoFar: Int, waitedInTaskMs: Long = 0): Long? {
        if (retriesSoFar >= maxRetries || !transient(e)) return null
        val wait = (e as? ModelError.RateLimited)?.retryAfterMs?.coerceIn(0, retryAfterCapMs) ?: run {
            val ceiling = minOf(maxBackoffMs, baseMs shl minOf(retriesSoFar, 20))
            // Half fixed, half random: several tasks that go wrong at the same time will not collide
            // again at the same moment.
            ceiling / 2 + (ceiling / 2 * jitter().coerceIn(0.0, 1.0)).toLong()
        }
        return wait.takeIf { waitedInTaskMs + it <= taskWaitBudgetMs }
    }

    companion object {
        /** No retry: hand it over if there is an error (when the lightweight model makes an error, this step directly changes to the main model). */
        val NONE = RetryPolicy(maxRetries = 0)

        /** A temporary failure may be resolved by sending the same request again after a while. */
        fun transient(e: Throwable): Boolean = when (e) {
            is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
            is ModelError.Server -> e.status in 500..599
            // The agent did not wake up for a while and just restarted: it may be connected after a
            // while; the account and password are incorrect and it is useless to try again.
            is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
            else -> false
        }

        /** The name of this fault in the operation record. */
        fun label(e: Throwable): String = when (e) {
            is ModelError.RateLimited -> "rate_limited"
            is ModelError.Network -> "network"
            is ModelError.StreamCut -> "stream_cut"
            is ModelError.Server -> "server_${e.status}"
            is ModelError.Proxy -> if (e.failure == ProxyFailure.Unreachable) "proxy_unreachable" else "proxy_auth"
            else -> e::class.simpleName.orEmpty()
        }
    }
}
