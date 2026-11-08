package com.meenai.mina.core.router

import com.meenai.mina.core.router.picker.CuratedTiers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * ModelScope Magicube Quota Ledger.
 * - Daily login grant: 200 short-term Magicube, +50 if Alibaba linked (250 total).
 * - Short-term resets at IST midnight (Asia/Kolkata), no rollover.
 * - Optional long-term balance with optional expiry date.
 * - Deductions consume short-term first (earliest expiring).
 * - Failed calls or HTTP 400 are not charged.
 * - Router avoids ModelScope when estimated balance < 5.0.
 * - 9:00 IST daily login reminder, skipped if already marked today.
 */
data class MagicubeState(
    val shortTermBalance: Double = 0.0,
    val shortTermDayKey: String? = null,
    val isLoginMarkedDayKey: String? = null,
    val isAlibabaLinkedDayKey: String? = null,
    val longTermBalance: Double = 0.0,
    val longTermExpiryEpochMs: Long? = null,
    val isAlibabaLinked: Boolean = false,
)

interface MagicubeStorage {
    fun load(): MagicubeState?
    fun save(state: MagicubeState)
}

object MagicubeLedger {

    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    const val DAILY_LOGIN_GRANT: Double = 200.0
    const val ALIBABA_LINKED_BONUS: Double = 50.0
    const val MINIMUM_BALANCE_FOR_MODELSCOPE: Double = 5.0

    @Volatile
    private var storage: MagicubeStorage? = null

    @Volatile
    var isAlibabaLinked: Boolean = false
        private set

    @Volatile
    private var shortTermBalance: Double = 0.0

    @Volatile
    private var shortTermDayKey: String? = null

    @Volatile
    private var isLoginMarkedDayKey: String? = null

    @Volatile
    private var isAlibabaLinkedDayKey: String? = null

    @Volatile
    private var longTermBalance: Double = 0.0

    @Volatile
    private var longTermExpiryEpochMs: Long? = null

    @Synchronized
    fun attachStorage(s: MagicubeStorage, nowMs: Long = System.currentTimeMillis()) {
        storage = s
        val loaded = s.load()
        if (loaded != null) {
            loadState(loaded, nowMs)
        }
    }

    @Synchronized
    fun detachStorage() {
        storage = null
    }

    @Synchronized
    fun loadState(state: MagicubeState, nowMs: Long = System.currentTimeMillis()) {
        val today = todayKey(nowMs)
        shortTermDayKey = state.shortTermDayKey
        shortTermBalance = if (state.shortTermDayKey == today) state.shortTermBalance else 0.0
        isLoginMarkedDayKey = state.isLoginMarkedDayKey
        isAlibabaLinkedDayKey = state.isAlibabaLinkedDayKey
        val expiry = state.longTermExpiryEpochMs
        if (expiry != null && nowMs >= expiry) {
            longTermBalance = 0.0
            longTermExpiryEpochMs = null
        } else {
            longTermBalance = state.longTermBalance
            longTermExpiryEpochMs = expiry
        }
        isAlibabaLinked = state.isAlibabaLinked
    }

    @Synchronized
    fun toState(): MagicubeState {
        return MagicubeState(
            shortTermBalance = shortTermBalance,
            shortTermDayKey = shortTermDayKey,
            isLoginMarkedDayKey = isLoginMarkedDayKey,
            isAlibabaLinkedDayKey = isAlibabaLinkedDayKey,
            longTermBalance = longTermBalance,
            longTermExpiryEpochMs = longTermExpiryEpochMs,
            isAlibabaLinked = isAlibabaLinked,
        )
    }

    private fun persist() {
        storage?.save(toState())
    }

    fun todayKey(nowMs: Long = System.currentTimeMillis()): String {
        return LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), IST_ZONE).toString()
    }

    @Synchronized
    fun recordDailyLogin(isAlibabaLinked: Boolean = this.isAlibabaLinked, nowMs: Long = System.currentTimeMillis()) {
        this.isAlibabaLinked = isAlibabaLinked
        val today = todayKey(nowMs)
        if (isLoginMarkedDayKey != today) {
            isLoginMarkedDayKey = today
            shortTermDayKey = today
            isAlibabaLinkedDayKey = if (isAlibabaLinked) today else null
            shortTermBalance = if (isAlibabaLinked) (DAILY_LOGIN_GRANT + ALIBABA_LINKED_BONUS) else DAILY_LOGIN_GRANT
        } else if (isAlibabaLinked && isAlibabaLinkedDayKey != today) {
            isAlibabaLinkedDayKey = today
            shortTermBalance += ALIBABA_LINKED_BONUS
        }
        persist()
    }

    @Synchronized
    fun setAlibabaLinked(linked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        this.isAlibabaLinked = linked
        val today = todayKey(nowMs)
        if (linked && isLoginMarkedDayKey == today && isAlibabaLinkedDayKey != today) {
            isAlibabaLinkedDayKey = today
            shortTermBalance += ALIBABA_LINKED_BONUS
        }
        persist()
    }

    fun isLoginMarkedToday(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isLoginMarkedDayKey == todayKey(nowMs)
    }

    @Synchronized
    fun getShortTermBalance(nowMs: Long = System.currentTimeMillis()): Double {
        val today = todayKey(nowMs)
        if (shortTermDayKey != today) {
            return 0.0
        }
        return shortTermBalance
    }

    @Synchronized
    fun getLongTermBalance(nowMs: Long = System.currentTimeMillis()): Double {
        val expiry = longTermExpiryEpochMs
        if (expiry != null && nowMs >= expiry) {
            return 0.0
        }
        return longTermBalance
    }

    @Synchronized
    fun setLongTermBalance(balance: Double, expiryEpochMs: Long? = null) {
        longTermBalance = balance.coerceAtLeast(0.0)
        longTermExpiryEpochMs = expiryEpochMs
        persist()
    }

    @Synchronized
    fun totalBalance(nowMs: Long = System.currentTimeMillis()): Double {
        return getShortTermBalance(nowMs) + getLongTermBalance(nowMs)
    }

    fun canAffordModelScope(nowMs: Long = System.currentTimeMillis()): Boolean {
        return totalBalance(nowMs) >= MINIMUM_BALANCE_FOR_MODELSCOPE
    }

    @Synchronized
    fun deduct(cost: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (cost <= 0.0) return true
        val today = todayKey(nowMs)
        if (shortTermDayKey != today) {
            shortTermBalance = 0.0
            shortTermDayKey = null
        }
        val expiry = longTermExpiryEpochMs
        if (expiry != null && nowMs >= expiry) {
            longTermBalance = 0.0
            longTermExpiryEpochMs = null
        }

        val total = shortTermBalance + longTermBalance
        if (total < cost) {
            val fromShort = minOf(shortTermBalance, cost)
            shortTermBalance -= fromShort
            val rem = cost - fromShort
            longTermBalance = (longTermBalance - rem).coerceAtLeast(0.0)
            persist()
            return false
        }

        // Deduct from short-term first (which expires earliest at IST midnight today)
        if (shortTermBalance >= cost) {
            shortTermBalance -= cost
        } else {
            val rem = cost - shortTermBalance
            shortTermBalance = 0.0
            longTermBalance -= rem
        }
        persist()
        return true
    }

    @Synchronized
    fun syncBalance(totalAmount: Double, nowMs: Long = System.currentTimeMillis()) {
        val today = todayKey(nowMs)
        isLoginMarkedDayKey = today
        shortTermDayKey = today
        val target = totalAmount.coerceAtLeast(0.0)
        if (target <= DAILY_LOGIN_GRANT) {
            shortTermBalance = target
            longTermBalance = 0.0
        } else {
            shortTermBalance = DAILY_LOGIN_GRANT
            longTermBalance = target - DAILY_LOGIN_GRANT
        }
        persist()
    }

    fun costForModel(modelId: String): Double {
        val lower = modelId.lowercase()
        if (lower.contains("qwen-image-2.1")) return 1.0
        if (lower.contains("qwen-image-edit")) return 2.0
        if (lower.contains("qwen-image")) return 2.0
        val paramSize = CuratedTiers.extractParameterSizeB(modelId)
        return when {
            paramSize in 0.1..10.0 || lower.contains("7b") || lower.contains("8b") || lower.contains("flash") -> 0.5
            paramSize in 10.1..35.0 || lower.contains("14b") || lower.contains("32b") -> 1.0
            paramSize > 35.0 || lower.contains("72b") || lower.contains("deepseek") || lower.contains("70b") -> 2.0
            else -> 1.0
        }
    }

    fun shouldSendLoginReminder(nowMs: Long = System.currentTimeMillis()): Boolean {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), IST_ZONE)
        if (zdt.hour < 9) return false
        return !isLoginMarkedToday(nowMs)
    }

    fun nextNineAmIst(nowMs: Long = System.currentTimeMillis()): Long {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), IST_ZONE)
        val todayNine = zdt.toLocalDate().atTime(9, 0).atZone(IST_ZONE)
        val target = if (zdt.isBefore(todayNine)) todayNine else todayNine.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    @Synchronized
    fun clear() {
        shortTermBalance = 0.0
        shortTermDayKey = null
        isLoginMarkedDayKey = null
        isAlibabaLinkedDayKey = null
        longTermBalance = 0.0
        longTermExpiryEpochMs = null
        isAlibabaLinked = false
        persist()
    }
}
