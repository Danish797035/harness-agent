package com.meenai.mina.core.router.picker

import com.meenai.mina.core.router.MagicubeLedger

/**
 * Result of a model probe execution.
 */
data class ProbeResult(
    val modelId: String,
    val provider: String,
    val passed: Boolean,
    val firstTokenMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val errorMessage: String? = null,
    val dayKey: String = "",
)

/**
 * Persistent storage for daily probe results.
 */
interface ModelProbeStore {
    fun load(): Map<String, ProbeResult>
    fun save(results: Map<String, ProbeResult>)
}

/**
 * In-memory storage for unit tests and default fallback.
 */
class InMemoryModelProbeStore(
    initial: Map<String, ProbeResult> = emptyMap(),
) : ModelProbeStore {
    private val memory = mutableMapOf<String, ProbeResult>().apply { putAll(initial) }

    override fun load(): Map<String, ProbeResult> = synchronized(memory) { memory.toMap() }

    override fun save(results: Map<String, ProbeResult>) {
        synchronized(memory) {
            memory.clear()
            memory.putAll(results)
        }
    }
}

/**
 * Functional interface to execute an actual HTTP or simulated probe call against a candidate model.
 */
fun interface ModelProbeExecutor {
    suspend fun executeProbe(candidate: ModelCandidate, isVision: Boolean): ProbeResult
}

/**
 * Probe Service for testing model candidates:
 * - Runs once per day (or when candidate list changes) on top 5 models per role.
 * - Test for standard roles: 1-line question + dummy get_time tool call.
 * - Test for Vision role: 32x32 colored image.
 * - Measures first-token time and total duration.
 * - Failure handling: HTTP 400, "no provider supported", or invalid format marks model failed ("us din bahar").
 * - Daily budgets: OpenRouter max 6/day; ModelScope max 3/day and ONLY when Magicube balance >= 20.0.
 * - Spend is recorded in [MagicubeLedger].
 */
class ModelProbeService(
    private val probeStore: ModelProbeStore = InMemoryModelProbeStore(),
    private val executor: ModelProbeExecutor? = null,
    private val clock: Clock = Clock.SYSTEM,
) {
    companion object {
        const val MAX_OPENROUTER_PROBES_PER_DAY: Int = 6
        const val MAX_MODELSCOPE_PROBES_PER_DAY: Int = 3
        const val MIN_MAGICUBE_BALANCE_FOR_PROBE: Double = 20.0

        /**
         * 32x32 solid red PNG in base64 format for lightweight vision probe without downloading external assets.
         */
        const val SOLID_RED_32X32_PNG_BASE64: String =
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAF0lEQVR42u3BAQEAAACAkP6v7ggKAAAA4AcL6AAB3510zQAAAABJRU5ErkJggg=="
    }

    private val results = mutableMapOf<String, ProbeResult>().apply {
        putAll(probeStore.load())
    }

    private var openRouterProbeCount: Int = 0
    private var modelScopeProbeCount: Int = 0
    private var budgetDayKey: String = ""
    private var lastProbedListHash: Int = 0

    private fun todayKey(): String = MagicubeLedger.todayKey(clock.currentTimeMillis())

    @Synchronized
    private fun checkResetDailyBudget() {
        val today = todayKey()
        if (budgetDayKey != today) {
            budgetDayKey = today
            openRouterProbeCount = 0
            modelScopeProbeCount = 0
        }
    }

    @Synchronized
    fun getProbeResult(modelId: String): ProbeResult? {
        val today = todayKey()
        val result = results[modelId] ?: return null
        return if (result.dayKey == today) result else null
    }

    @Synchronized
    fun isFailedToday(modelId: String): Boolean {
        val today = todayKey()
        val result = results[modelId] ?: return false
        return result.dayKey == today && !result.passed
    }

    @Synchronized
    fun recordProbeResult(result: ProbeResult) {
        val today = todayKey()
        val record = result.copy(dayKey = today)
        results[result.modelId] = record
        probeStore.save(results)
    }

    @Synchronized
    fun getOpenRouterProbeCount(): Int {
        checkResetDailyBudget()
        return openRouterProbeCount
    }

    @Synchronized
    fun getModelScopeProbeCount(): Int {
        checkResetDailyBudget()
        return modelScopeProbeCount
    }

    /**
     * Determines whether a daily probe cycle should trigger:
     * - Triggered if day key changed since last probe OR candidate list changed.
     */
    @Synchronized
    fun shouldTriggerProbe(candidateIds: List<String>): Boolean {
        checkResetDailyBudget()
        val currentHash = candidateIds.sorted().hashCode()
        if (currentHash != lastProbedListHash) return true
        val today = todayKey()
        return results.values.none { it.dayKey == today }
    }

    /**
     * Probes top 5 candidates for each role within daily budgets.
     * Skips ModelScope entirely if Magicube balance is < 20.0.
     */
    suspend fun probeCandidates(
        roleTopCandidates: Map<ModelRole, List<ModelCandidate>>,
    ): Map<String, ProbeResult> {
        checkResetDailyBudget()
        val today = todayKey()
        val candidateMap = mutableMapOf<String, Pair<ModelCandidate, Boolean>>()

        // Collect top 5 for each role
        for ((role, list) in roleTopCandidates) {
            val top5 = list.take(5)
            val isVision = (role == ModelRole.Vision)
            for (c in top5) {
                if (!candidateMap.containsKey(c.id) || isVision) {
                    candidateMap[c.id] = c to isVision
                }
            }
        }

        lastProbedListHash = candidateMap.keys.sorted().hashCode()

        for ((modelId, pair) in candidateMap) {
            val (candidate, isVision) = pair
            val existing = results[modelId]
            if (existing != null && existing.dayKey == today) {
                continue // Already probed today
            }

            // Budget check
            val provider = candidate.provider.lowercase()
            when {
                provider.contains("openrouter") -> {
                    if (openRouterProbeCount >= MAX_OPENROUTER_PROBES_PER_DAY) {
                        continue // Budget exhausted
                    }
                }
                provider.contains("modelscope") -> {
                    if (modelScopeProbeCount >= MAX_MODELSCOPE_PROBES_PER_DAY) {
                        continue // Budget exhausted
                    }
                    if (MagicubeLedger.totalBalance(clock.currentTimeMillis()) < MIN_MAGICUBE_BALANCE_FOR_PROBE) {
                        continue // Magicube < 20: Skip ModelScope probe
                    }
                }
            }

            // Execute probe
            val probeResult = if (executor != null) {
                try {
                    executor.executeProbe(candidate, isVision)
                } catch (e: Exception) {
                    ProbeResult(
                        modelId = candidate.id,
                        provider = candidate.provider,
                        passed = false,
                        errorMessage = e.message ?: "Probe execution failed",
                        dayKey = today,
                    )
                }
            } else {
                // Default probe simulation
                ProbeResult(
                    modelId = candidate.id,
                    provider = candidate.provider,
                    passed = true,
                    firstTokenMs = 1200L,
                    totalDurationMs = 2500L,
                    dayKey = today,
                )
            }

            // Track budget usage
            synchronized(this) {
                if (provider.contains("openrouter")) {
                    openRouterProbeCount++
                } else if (provider.contains("modelscope")) {
                    modelScopeProbeCount++
                    // Deduct ledger spend for probe if applicable
                    val cost = MagicubeLedger.costForModel(candidate.id)
                    MagicubeLedger.deduct(cost, clock.currentTimeMillis())
                }
                recordProbeResult(probeResult)
            }
        }

        return synchronized(this) { results.toMap() }
    }

    /**
     * Checks if error text indicates an immediate disqualification for the day:
     * - HTTP 400
     * - "no provider supported" or "No available provider"
     */
    fun isFatalProbeFailure(errorMessage: String?): Boolean {
        if (errorMessage.isNullOrBlank()) return false
        val lower = errorMessage.lowercase()
        return lower.contains("400") ||
            lower.contains("no provider supported") ||
            lower.contains("no available provider") ||
            lower.contains("provider returned error")
    }
}
