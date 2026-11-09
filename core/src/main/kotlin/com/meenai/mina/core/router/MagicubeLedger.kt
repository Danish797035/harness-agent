package com.meenai.mina.core.router

import com.meenai.mina.core.router.picker.CuratedTiers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * ModelScope Magicube Quota Ledger.
 * - Daily login grant: 200 short-term Magicube, +50 if Alibaba linked (250 total).
 * - Daily reset occurs at 00:00 UTC+8 (China Standard Time), grant credited on reset.
 * - Optional long-term balance with optional expiry date.
 * - Deductions consume short-term first (earliest expiring).
 * - Failed calls or HTTP 400 are not charged.
 * - Router avoids ModelScope when estimated balance is known and < 5.0.
 * - If balance is unknown, ModelScope is not skipped.
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
    val isBalanceKnown: Boolean = false,
)

interface MagicubeStorage {
    fun load(): MagicubeState?
    fun save(state: MagicubeState)
}

object MagicubeLedger {

    val RESET_ZONE: ZoneId = ZoneOffset.ofHours(8) // 00:00 UTC+8
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
    var isBalanceKnown: Boolean = false
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

    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        val copy = synchronized(this) { listeners.toList() }
        copy.forEach { it.invoke() }
    }

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
        isAlibabaLinked = state.isAlibabaLinked
        isBalanceKnown = state.isBalanceKnown

        if (state.shortTermDayKey == today) {
            shortTermDayKey = state.shortTermDayKey
            shortTermBalance = state.shortTermBalance
            isLoginMarkedDayKey = state.isLoginMarkedDayKey
            isAlibabaLinkedDayKey = state.isAlibabaLinkedDayKey
        } else if (state.shortTermDayKey != null || state.isLoginMarkedDayKey != null) {
            // Crossed UTC+8 midnight: grant new day's login + Alibaba bonus
            shortTermDayKey = today
            isLoginMarkedDayKey = today
            val grant = if (isAlibabaLinked) (DAILY_LOGIN_GRANT + ALIBABA_LINKED_BONUS) else DAILY_LOGIN_GRANT
            shortTermBalance = grant
            isAlibabaLinkedDayKey = if (isAlibabaLinked) today else null
            isBalanceKnown = true
            persist()
        } else {
            shortTermDayKey = null
            shortTermBalance = 0.0
            isLoginMarkedDayKey = null
            isAlibabaLinkedDayKey = null
        }

        val expiry = state.longTermExpiryEpochMs
        if (expiry != null && nowMs >= expiry) {
            longTermBalance = 0.0
            longTermExpiryEpochMs = null
        } else {
            longTermBalance = state.longTermBalance
            longTermExpiryEpochMs = expiry
        }
        notifyListeners()
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
            isBalanceKnown = isBalanceKnown,
        )
    }

    private fun persist() {
        storage?.save(toState())
    }

    fun todayKey(nowMs: Long = System.currentTimeMillis()): String {
        return LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), RESET_ZONE).toString()
    }

    private fun checkDailyReset(nowMs: Long) {
        val today = todayKey(nowMs)
        if (shortTermDayKey != null && shortTermDayKey != today) {
            // New day in UTC+8: grant daily login + Alibaba bonus
            shortTermDayKey = today
            isLoginMarkedDayKey = today
            val grant = if (isAlibabaLinked) (DAILY_LOGIN_GRANT + ALIBABA_LINKED_BONUS) else DAILY_LOGIN_GRANT
            shortTermBalance = grant
            isAlibabaLinkedDayKey = if (isAlibabaLinked) today else null
            isBalanceKnown = true
            persist()
            notifyListeners()
        }
        val expiry = longTermExpiryEpochMs
        if (expiry != null && nowMs >= expiry) {
            longTermBalance = 0.0
            longTermExpiryEpochMs = null
            persist()
            notifyListeners()
        }
    }

    @Synchronized
    fun recordDailyLogin(isAlibabaLinked: Boolean = this.isAlibabaLinked, nowMs: Long = System.currentTimeMillis()) {
        this.isAlibabaLinked = isAlibabaLinked
        val today = todayKey(nowMs)
        isBalanceKnown = true
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
        notifyListeners()
    }

    @Synchronized
    fun setAlibabaLinked(linked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        this.isAlibabaLinked = linked
        val today = todayKey(nowMs)
        if (linked && isAlibabaLinkedDayKey != today) {
            isAlibabaLinkedDayKey = today
            shortTermBalance += ALIBABA_LINKED_BONUS
            isBalanceKnown = true
        } else if (!linked && isAlibabaLinkedDayKey == today) {
            isAlibabaLinkedDayKey = null
            shortTermBalance = (shortTermBalance - ALIBABA_LINKED_BONUS).coerceAtLeast(0.0)
        }
        persist()
        notifyListeners()
    }

    @Synchronized
    fun isLoginMarkedToday(nowMs: Long = System.currentTimeMillis()): Boolean {
        checkDailyReset(nowMs)
        return isLoginMarkedDayKey == todayKey(nowMs)
    }

    @Synchronized
    fun getShortTermBalance(nowMs: Long = System.currentTimeMillis()): Double {
        checkDailyReset(nowMs)
        val today = todayKey(nowMs)
        if (shortTermDayKey != today) {
            return 0.0
        }
        return shortTermBalance
    }

    @Synchronized
    fun getLongTermBalance(nowMs: Long = System.currentTimeMillis()): Double {
        checkDailyReset(nowMs)
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
        isBalanceKnown = true
        persist()
        notifyListeners()
    }

    @Synchronized
    fun totalBalance(nowMs: Long = System.currentTimeMillis()): Double {
        return getShortTermBalance(nowMs) + getLongTermBalance(nowMs)
    }

    fun canAffordModelScope(nowMs: Long = System.currentTimeMillis()): Boolean {
        // If balance is unknown, do not skip ModelScope!
        if (!isBalanceKnown) return true
        return totalBalance(nowMs) >= MINIMUM_BALANCE_FOR_MODELSCOPE
    }

    @Synchronized
    fun deduct(cost: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (cost <= 0.0) return true
        checkDailyReset(nowMs)

        val total = shortTermBalance + longTermBalance
        if (total < cost) {
            val fromShort = minOf(shortTermBalance, cost)
            shortTermBalance -= fromShort
            val rem = cost - fromShort
            longTermBalance = (longTermBalance - rem).coerceAtLeast(0.0)
            isBalanceKnown = true
            persist()
            notifyListeners()
            return false
        }

        // Deduct from short-term first (which expires earliest at UTC+8 midnight today)
        if (shortTermBalance >= cost) {
            shortTermBalance -= cost
        } else {
            val rem = cost - shortTermBalance
            shortTermBalance = 0.0
            longTermBalance -= rem
        }
        isBalanceKnown = true
        persist()
        notifyListeners()
        return true
    }

    @Synchronized
    fun syncBalance(totalAmount: Double, nowMs: Long = System.currentTimeMillis()) {
        val today = todayKey(nowMs)
        isLoginMarkedDayKey = today
        shortTermDayKey = today
        isBalanceKnown = true
        val target = totalAmount.coerceAtLeast(0.0)
        val dailyGrant = if (isAlibabaLinked) (DAILY_LOGIN_GRANT + ALIBABA_LINKED_BONUS) else DAILY_LOGIN_GRANT
        if (target <= dailyGrant) {
            shortTermBalance = target
            longTermBalance = 0.0
        } else {
            shortTermBalance = dailyGrant
            longTermBalance = target - dailyGrant
        }
        persist()
        notifyListeners()
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
        isBalanceKnown = false
        persist()
        notifyListeners()
    }
}
