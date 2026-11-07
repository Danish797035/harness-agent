package com.meenai.mina.core.router

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks recent success rate and response latency per model.
 * Models with recent failures drop in ranking to avoid repeated failovers.
 */
class ModelHealthTracker {
    private data class HealthStat(
        var consecutiveFailures: Int = 0,
        var totalSuccesses: Int = 0,
        var totalFailures: Int = 0,
        var lastLatencyMs: Long = 0L,
        var avgLatencyMs: Long = 0L,
    )

    private val stats = ConcurrentHashMap<String, HealthStat>()

    fun recordSuccess(modelId: String, latencyMs: Long) {
        val stat = stats.getOrPut(modelId) { HealthStat() }
        synchronized(stat) {
            stat.consecutiveFailures = 0
            stat.totalSuccesses++
            stat.lastLatencyMs = latencyMs
            stat.avgLatencyMs = if (stat.avgLatencyMs == 0L) latencyMs else (stat.avgLatencyMs * 0.7 + latencyMs * 0.3).toLong()
        }
    }

    fun recordFailure(modelId: String) {
        val stat = stats.getOrPut(modelId) { HealthStat() }
        synchronized(stat) {
            stat.consecutiveFailures++
            stat.totalFailures++
        }
    }

    /**
     * Calculates health score from 0.0 (unhealthy) to 100.0 (optimal).
     */
    fun score(modelId: String): Double {
        val stat = stats[modelId] ?: return 100.0
        synchronized(stat) {
            var score = 100.0
            // Heavy penalty for consecutive failures
            score -= stat.consecutiveFailures * 25.0

            // Latency bonus/penalty
            if (stat.avgLatencyMs > 10_000L) {
                score -= 15.0
            } else if (stat.avgLatencyMs > 5_000L) {
                score -= 5.0
            } else if (stat.avgLatencyMs in 1L..1_500L) {
                score += 5.0
            }

            return min(100.0, max(0.0, score))
        }
    }

    fun reset(modelId: String? = null) {
        if (modelId != null) {
            stats.remove(modelId)
        } else {
            stats.clear()
        }
    }
}
