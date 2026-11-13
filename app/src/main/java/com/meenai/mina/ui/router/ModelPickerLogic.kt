package com.meenai.mina.ui.router

import kotlin.math.ceil

enum class ModelPickerFilter {
    All,
    Free,
    Vision,
    Tools,
    LongContext;
}

object ModelPickerLogic {
    const val LONG_CONTEXT_THRESHOLD = 64_000

    fun filterModels(
        models: List<ModelQuotaLine>,
        searchQuery: String,
        activeFilter: ModelPickerFilter,
    ): List<ModelQuotaLine> {
        val query = searchQuery.trim().lowercase()
        return models.filter { model ->
            // Match search
            val matchesQuery = query.isEmpty() ||
                model.modelName.lowercase().contains(query) ||
                model.modelId.lowercase().contains(query) ||
                model.provider.lowercase().contains(query)

            if (!matchesQuery) return@filter false

            // Match capability filter
            when (activeFilter) {
                ModelPickerFilter.All -> true
                ModelPickerFilter.Free -> model.isFree
                ModelPickerFilter.Vision -> model.supportsVision
                ModelPickerFilter.Tools -> model.supportsTools
                ModelPickerFilter.LongContext -> model.contextTokens >= LONG_CONTEXT_THRESHOLD
            }
        }
    }

    fun formatContextTokens(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
            tokens >= 1_000 -> "${tokens / 1_000}k"
            else -> "$tokens"
        }
    }

    fun computeQuotaState(
        isBadKey: Boolean,
        isUnsupported: Boolean,
        cooldownMs: Long,
        limit: Int?,
        todayRequests: Int,
        isModelScope: Boolean = false,
        magicubeBalance: Double? = null,
        costPerCall: Double? = null,
    ): Pair<QuotaState, String?> {
        val cooldownMin = if (cooldownMs > 0L) ceil(cooldownMs / 60000.0).toInt().coerceAtLeast(1) else 0

        val quotaState = when {
            isBadKey -> QuotaState.Exhausted
            isUnsupported -> QuotaState.Exhausted
            cooldownMs > 0L -> QuotaState.Exhausted
            limit != null && todayRequests >= limit -> QuotaState.Exhausted
            isModelScope && magicubeBalance != null && costPerCall != null && magicubeBalance < costPerCall -> QuotaState.Exhausted
            limit != null && (limit - todayRequests) in 1..5 -> QuotaState.Low
            isModelScope && magicubeBalance != null && costPerCall != null && magicubeBalance < costPerCall * 3 -> QuotaState.Low
            else -> QuotaState.Ready
        }

        val exhaustedReason = when {
            isBadKey -> "API Key is invalid or expired"
            isUnsupported -> "Model is unsupported"
            cooldownMs > 0L -> "Cooldown active ($cooldownMin min remaining)"
            limit != null && todayRequests >= limit -> "Daily limit reached ($todayRequests/$limit requests)"
            isModelScope && magicubeBalance != null && costPerCall != null && magicubeBalance < costPerCall -> {
                val bal = if (magicubeBalance % 1.0 == 0.0) magicubeBalance.toInt().toString() else String.format(java.util.Locale.US, "%.1f", magicubeBalance)
                val cost = if (costPerCall % 1.0 == 0.0) costPerCall.toInt().toString() else String.format(java.util.Locale.US, "%.1f", costPerCall)
                "Insufficient Magicube balance ($bal available, ~$cost needed)"
            }
            else -> null
        }

        return quotaState to exhaustedReason
    }
}
