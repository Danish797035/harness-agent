package com.meenai.mina.core.router.picker

/**
 * 7-day usage statistics for a specific model candidate.
 * Tracks real execution success, correct tool calling, and first-token latency.
 */
data class ModelUsageRecord(
    val modelId: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val validToolCalls: Int = 0,
    val totalToolCalls: Int = 0,
    val totalFirstTokenMs: Long = 0L,
    val firstTokenSamples: Int = 0,
    val lastUsedEpochMs: Long = 0L,
    val timestamps: List<Long> = emptyList(),
) {
    val totalCalls: Int get() = successCount + failureCount

    val successRate: Double
        get() = if (totalCalls > 0) successCount.toDouble() / totalCalls else 1.0

    val toolCallAccuracy: Double
        get() = if (totalToolCalls > 0) validToolCalls.toDouble() / totalToolCalls else 1.0

    val avgFirstTokenMs: Double
        get() = if (firstTokenSamples > 0) totalFirstTokenMs.toDouble() / firstTokenSamples else 3000.0
}

/**
 * Storage interface for persistent 7-day model usage statistics.
 */
interface ModelStatsStore {
    fun load(): Map<String, ModelUsageRecord>
    fun save(records: Map<String, ModelUsageRecord>)
}

/**
 * In-memory storage for unit tests and default fallback.
 */
class InMemoryModelStatsStore(
    initial: Map<String, ModelUsageRecord> = emptyMap(),
) : ModelStatsStore {
    private val memory = mutableMapOf<String, ModelUsageRecord>().apply { putAll(initial) }

    override fun load(): Map<String, ModelUsageRecord> = synchronized(memory) { memory.toMap() }

    override fun save(records: Map<String, ModelUsageRecord>) {
        synchronized(memory) {
            memory.clear()
            memory.putAll(records)
        }
    }
}

/**
 * Tracks model performance over a 7-day rolling window and computes real-usage score adjustments.
 * Also enforces the 10%+ hysteresis rule for model switching.
 */
class ModelStatsTracker(
    private val store: ModelStatsStore = InMemoryModelStatsStore(),
    private val clock: Clock = Clock.SYSTEM,
) {
    companion object {
        const val SEVEN_DAYS_MS: Long = 7 * 24 * 60 * 60 * 1000L
        const val DEFAULT_HYSTERESIS_PERCENT: Double = 10.0
    }

    private val records = mutableMapOf<String, ModelUsageRecord>().apply {
        putAll(store.load())
    }

    @Synchronized
    fun getStats(modelId: String): ModelUsageRecord? {
        pruneExpired()
        return records[modelId]
    }

    @Synchronized
    fun getAllStats(): Map<String, ModelUsageRecord> {
        pruneExpired()
        return records.toMap()
    }

    @Synchronized
    fun recordSuccess(
        modelId: String,
        firstTokenMs: Long? = null,
        hasValidToolCall: Boolean? = null,
    ) {
        val now = clock.currentTimeMillis()
        val current = records[modelId] ?: ModelUsageRecord(modelId = modelId)
        val newTimestamps = (current.timestamps + now).filter { now - it <= SEVEN_DAYS_MS }

        val newTotalFirstToken = if (firstTokenMs != null && firstTokenMs > 0) {
            current.totalFirstTokenMs + firstTokenMs
        } else current.totalFirstTokenMs

        val newSamples = if (firstTokenMs != null && firstTokenMs > 0) {
            current.firstTokenSamples + 1
        } else current.firstTokenSamples

        val newValidTools = if (hasValidToolCall == true) current.validToolCalls + 1 else current.validToolCalls
        val newTotalTools = if (hasValidToolCall != null) current.totalToolCalls + 1 else current.totalToolCalls

        val updated = current.copy(
            successCount = current.successCount + 1,
            validToolCalls = newValidTools,
            totalToolCalls = newTotalTools,
            totalFirstTokenMs = newTotalFirstToken,
            firstTokenSamples = newSamples,
            lastUsedEpochMs = now,
            timestamps = newTimestamps,
        )
        records[modelId] = updated
        store.save(records)
    }

    @Synchronized
    fun recordFailure(
        modelId: String,
        isToolCallFailure: Boolean = false,
    ) {
        val now = clock.currentTimeMillis()
        val current = records[modelId] ?: ModelUsageRecord(modelId = modelId)
        val newTimestamps = (current.timestamps + now).filter { now - it <= SEVEN_DAYS_MS }

        val newTotalTools = if (isToolCallFailure) current.totalToolCalls + 1 else current.totalToolCalls

        val updated = current.copy(
            failureCount = current.failureCount + 1,
            totalToolCalls = newTotalTools,
            lastUsedEpochMs = now,
            timestamps = newTimestamps,
        )
        records[modelId] = updated
        store.save(records)
    }

    @Synchronized
    fun pruneExpired() {
        val now = clock.currentTimeMillis()
        var changed = false
        val keys = records.keys.toList()
        for (k in keys) {
            val record = records[k] ?: continue
            val validTimestamps = record.timestamps.filter { now - it <= SEVEN_DAYS_MS }
            if (validTimestamps.size != record.timestamps.size) {
                if (validTimestamps.isEmpty()) {
                    records.remove(k)
                } else {
                    records[k] = record.copy(timestamps = validTimestamps)
                }
                changed = true
            }
        }
        if (changed) {
            store.save(records)
        }
    }

    /**
     * Computes a score bonus/penalty from real-use statistics:
     * - Success rate: boosts score up to +10.0, penalizes down to -15.0 if failure rate is high.
     * - Tool call accuracy: boosts score up to +8.0 if tools work reliably, penalizes if failing.
     * - First-token time: bonus (+5.0) for < 3s, penalty (-20.0) if repeatedly exceeding 15s.
     */
    fun computeRealUseBonus(modelId: String): Double {
        val stats = getStats(modelId) ?: return 0.0
        if (stats.totalCalls == 0) return 0.0

        var bonus = 0.0

        // 1. Success rate component
        val sRate = stats.successRate
        bonus += when {
            sRate >= 0.95 -> 10.0
            sRate >= 0.85 -> 5.0
            sRate >= 0.70 -> 0.0
            sRate >= 0.50 -> -8.0
            else -> -15.0
        }

        // 2. Tool call accuracy component
        if (stats.totalToolCalls > 0) {
            val toolAcc = stats.toolCallAccuracy
            bonus += when {
                toolAcc >= 0.95 -> 8.0
                toolAcc >= 0.80 -> 4.0
                toolAcc >= 0.60 -> 0.0
                else -> -10.0
            }
        }

        // 3. First-token latency component (4G latency optimization)
        if (stats.firstTokenSamples > 0) {
            val avgMs = stats.avgFirstTokenMs
            bonus += when {
                avgMs <= 3000.0 -> 5.0
                avgMs <= 6000.0 -> 2.0
                avgMs <= 10000.0 -> 0.0
                avgMs <= 15000.0 -> -5.0
                else -> -20.0 // Penalize model that repeatedly exceeds 15 seconds
            }
        }

        return bonus
    }

    /**
     * Hysteresis decision rule:
     * A switch from [currentScore] to [candidateScore] is only approved if the candidate is at least
     * [thresholdPercent]% better than the current model.
     *
     * Example:
     * - current: 100.0, candidate: 105.0 -> 5% improvement -> false (stays with current)
     * - current: 100.0, candidate: 115.0 -> 15% improvement -> true (switches)
     */
    fun shouldSwitch(
        currentScore: Double,
        candidateScore: Double,
        thresholdPercent: Double = DEFAULT_HYSTERESIS_PERCENT,
    ): Boolean {
        if (currentScore <= 0.0) return true
        if (candidateScore <= currentScore) return false
        val improvementPercent = ((candidateScore - currentScore) / currentScore) * 100.0
        return improvementPercent >= thresholdPercent
    }
}
