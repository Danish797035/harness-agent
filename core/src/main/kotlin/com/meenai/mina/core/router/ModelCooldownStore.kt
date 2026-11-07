package com.meenai.mina.core.router

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CooldownState(
    val cooldowns: Map<String, Long> = emptyMap(),
    val badKeys: Set<String> = emptySet(),
    val knownQuotaRemaining: Map<String, Int> = emptyMap(),
)

/**
 * Manages rate limit backoffs, daily quota resets (Gemini Midnight PT, OpenRouter 00:00 UTC),
 * invalid keys, and proactive low-quota avoidance. Supports persistence across app restarts.
 */
class ModelCooldownStore(
    private val persistCallback: ((String) -> Unit)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val badKeys = ConcurrentHashMap.newKeySet<String>()
    private val knownQuotaRemaining = ConcurrentHashMap<String, Int>()

    fun markCooldown(modelId: String, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val expiry = nowMs + durationMs.coerceAtLeast(1_000L)
        cooldowns[modelId] = maxOf(cooldowns[modelId] ?: 0L, expiry)
        notifyChanged()
    }

    fun markDailyQuotaExhausted(modelId: String, provider: String, nowMs: Long = System.currentTimeMillis()) {
        val isGemini = provider.equals("gemini", ignoreCase = true) || modelId.contains("gemini", ignoreCase = true)
        val resetMs = if (isGemini) nextPacificMidnight(nowMs) else nextUtcMidnight(nowMs)
        cooldowns[modelId] = resetMs
        knownQuotaRemaining[modelId] = 0
        notifyChanged()
    }

    fun markBadKey(provider: String) {
        badKeys.add(provider.lowercase())
        notifyChanged()
    }

    fun clearBadKey(provider: String) {
        badKeys.remove(provider.lowercase())
        notifyChanged()
    }

    fun isKeyBad(provider: String): Boolean = badKeys.contains(provider.lowercase())

    fun updateQuotaRemaining(modelId: String, remaining: Int) {
        knownQuotaRemaining[modelId] = remaining
        notifyChanged()
    }

    fun isAvailable(
        modelId: String,
        provider: String,
        nowMs: Long = System.currentTimeMillis(),
        isNewTask: Boolean = false,
    ): Boolean {
        if (isKeyBad(provider)) return false
        val expiry = cooldowns[modelId]
        if (expiry != null && nowMs < expiry) return false
        // Proactive avoidance: if starting a new task and fewer than 2 requests remain, avoid this model
        if (isNewTask) {
            val remaining = knownQuotaRemaining[modelId]
            if (remaining != null && remaining < 2) return false
        }
        return true
    }

    fun getRemainingCooldownMs(modelId: String, nowMs: Long = System.currentTimeMillis()): Long {
        val expiry = cooldowns[modelId] ?: return 0L
        return (expiry - nowMs).coerceAtLeast(0L)
    }

    fun toJson(): String {
        val state = CooldownState(
            cooldowns = cooldowns.toMap(),
            badKeys = badKeys.toSet(),
            knownQuotaRemaining = knownQuotaRemaining.toMap(),
        )
        return json.encodeToString(state)
    }

    fun loadFromJson(jsonStr: String) {
        try {
            val state = json.decodeFromString<CooldownState>(jsonStr)
            cooldowns.clear()
            cooldowns.putAll(state.cooldowns)
            badKeys.clear()
            badKeys.addAll(state.badKeys)
            knownQuotaRemaining.clear()
            knownQuotaRemaining.putAll(state.knownQuotaRemaining)
        } catch (_: Exception) {}
    }

    private fun notifyChanged() {
        persistCallback?.invoke(toJson())
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L

        fun nextPacificMidnight(nowMs: Long): Long {
            val zone = ZoneId.of("America/Los_Angeles")
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val next = zdt.toLocalDate().plusDays(1).atStartOfDay(zone)
            return next.toInstant().toEpochMilli()
        }

        fun nextUtcMidnight(nowMs: Long): Long {
            val zone = ZoneOffset.UTC
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val next = zdt.toLocalDate().plusDays(1).atStartOfDay(zone)
            return next.toInstant().toEpochMilli()
        }
    }
}
