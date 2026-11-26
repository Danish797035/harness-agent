package com.meenai.mina.core.router

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks daily request counts per model and provider.
 * Daily counters reset at IST midnight (Asia/Kolkata).
 */
object RouterUsageTracker {

    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    // modelKey -> Map<dayKey, count>
    private val modelCalls = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    // providerKey -> Map<dayKey, count>
    private val providerCalls = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    private val _updates = MutableStateFlow(0L)
    /** Monotonic version incremented whenever usage counts change so UI quota % stays live. */
    val updates: StateFlow<Long> = _updates.asStateFlow()

    fun todayKey(nowMs: Long = System.currentTimeMillis()): String {
        return LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), IST_ZONE).toString()
    }

    fun recordCall(modelId: String, provider: String, nowMs: Long = System.currentTimeMillis()) {
        val day = todayKey(nowMs)
        val mKey = modelId.lowercase()
        val pKey = provider.lowercase()

        modelCalls.computeIfAbsent(mKey) { ConcurrentHashMap() }.merge(day, 1, Int::plus)
        providerCalls.computeIfAbsent(pKey) { ConcurrentHashMap() }.merge(day, 1, Int::plus)
        _updates.update { it + 1 }
    }

    fun getTodayCalls(modelId: String, nowMs: Long = System.currentTimeMillis()): Int {
        val day = todayKey(nowMs)
        return modelCalls[modelId.lowercase()]?.get(day) ?: 0
    }

    fun getTodayProviderCalls(provider: String, nowMs: Long = System.currentTimeMillis()): Int {
        val day = todayKey(nowMs)
        return providerCalls[provider.lowercase()]?.get(day) ?: 0
    }

    fun clear() {
        modelCalls.clear()
        providerCalls.clear()
        _updates.update { it + 1 }
    }
}
