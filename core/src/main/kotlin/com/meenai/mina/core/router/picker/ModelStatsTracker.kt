package com.meenai.mina.core.router.picker

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Individual usage event recorded within the 7-day rolling window.
 */
@Serializable
data class UsageEvent(
    val timestampMs: Long,
    val isSuccess: Boolean,
    val firstTokenMs: Long? = null,
    val hasValidToolCall: Boolean? = null,
    val isToolCallFailure: Boolean = false,
)

/**
 * 7-day usage statistics for a specific model candidate.
 * Tracks real execution success, correct tool calling, and first-token latency.
 */
@Serializable
data class ModelUsageRecord(
    val modelId: String,
    val events: List<UsageEvent> = emptyList(),
    val successCount: Int = events.count { it.isSuccess },
    val failureCount: Int = events.count { !it.isSuccess },
    val validToolCalls: Int = events.count { it.hasValidToolCall == true },
    val totalToolCalls: Int = events.count { it.hasValidToolCall != null || it.isToolCallFailure },
    val totalFirstTokenMs: Long = events.mapNotNull { it.firstTokenMs }.filter { it > 0 }.sum(),
    val firstTokenSamples: Int = events.count { it.firstTokenMs != null && it.firstTokenMs > 0 },
    val lastUsedEpochMs: Long = events.maxOfOrNull { it.timestampMs } ?: 0L,
    val timestamps: List<Long> = events.map { it.timestampMs },
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
 * File-backed persistent storage for 7-day model usage statistics.
 */
class FileModelStatsStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelStatsStore {
    override fun load(): Map<String, ModelUsageRecord> {
        return runCatching {
            if (!file.exists() || !file.isFile) return emptyMap()
            json.decodeFromString<Map<String, ModelUsageRecord>>(file.readText())
        }.getOrDefault(emptyMap())
    }

    override fun save(records: Map<String, ModelUsageRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(records))
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

        private fun migrateLegacyRecord(record: ModelUsageRecord): ModelUsageRecord {
            if (record.events.isNotEmpty() || record.timestamps.isEmpty()) return record
            val events = record.timestamps.mapIndexed { idx, ts ->
                UsageEvent(
                    timestampMs = ts,
                    isSuccess = idx < record.successCount,
                    firstTokenMs = if (record.firstTokenSamples > 0) record.totalFirstTokenMs / record.firstTokenSamples else null,
                    hasValidToolCall = if (idx < record.validToolCalls) true else (if (idx < record.totalToolCalls) false else null),
                )
            }
            return record.copy(events = events)
        }
    }

    private val records = mutableMapOf<String, ModelUsageRecord>().apply {
        store.load().forEach { (k, v) -> put(k, migrateLegacyRecord(v)) }
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
        val event = UsageEvent(
            timestampMs = now,
            isSuccess = true,
            firstTokenMs = firstTokenMs,
            hasValidToolCall = hasValidToolCall,
        )
        val validEvents = (current.events + event).filter { now - it.timestampMs <= SEVEN_DAYS_MS }
        val updated = current.copy(
            events = validEvents,
            successCount = validEvents.count { it.isSuccess },
            failureCount = validEvents.count { !it.isSuccess },
            validToolCalls = validEvents.count { it.hasValidToolCall == true },
            totalToolCalls = validEvents.count { it.hasValidToolCall != null || it.isToolCallFailure },
            totalFirstTokenMs = validEvents.mapNotNull { it.firstTokenMs }.filter { it > 0 }.sum(),
            firstTokenSamples = validEvents.count { it.firstTokenMs != null && it.firstTokenMs > 0 },
            lastUsedEpochMs = now,
            timestamps = validEvents.map { it.timestampMs },
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
        val event = UsageEvent(
            timestampMs = now,
            isSuccess = false,
            isToolCallFailure = isToolCallFailure,
            hasValidToolCall = if (isToolCallFailure) false else null,
        )
        val validEvents = (current.events + event).filter { now - it.timestampMs <= SEVEN_DAYS_MS }
        val updated = current.copy(
            events = validEvents,
            successCount = validEvents.count { it.isSuccess },
            failureCount = validEvents.count { !it.isSuccess },
            validToolCalls = validEvents.count { it.hasValidToolCall == true },
            totalToolCalls = validEvents.count { it.hasValidToolCall != null || it.isToolCallFailure },
            totalFirstTokenMs = validEvents.mapNotNull { it.firstTokenMs }.filter { it > 0 }.sum(),
            firstTokenSamples = validEvents.count { it.firstTokenMs != null && it.firstTokenMs > 0 },
            lastUsedEpochMs = now,
            timestamps = validEvents.map { it.timestampMs },
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
            val validEvents = record.events.filter { now - it.timestampMs <= SEVEN_DAYS_MS }
            if (validEvents.size != record.events.size) {
                if (validEvents.isEmpty()) {
                    records.remove(k)
                } else {
                    records[k] = record.copy(
                        events = validEvents,
                        successCount = validEvents.count { it.isSuccess },
                        failureCount = validEvents.count { !it.isSuccess },
                        validToolCalls = validEvents.count { it.hasValidToolCall == true },
                        totalToolCalls = validEvents.count { it.hasValidToolCall != null || it.isToolCallFailure },
                        totalFirstTokenMs = validEvents.mapNotNull { it.firstTokenMs }.filter { it > 0 }.sum(),
                        firstTokenSamples = validEvents.count { it.firstTokenMs != null && it.firstTokenMs > 0 },
                        lastUsedEpochMs = validEvents.maxOfOrNull { it.timestampMs } ?: 0L,
                        timestamps = validEvents.map { it.timestampMs },
                    )
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
