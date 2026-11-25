package com.meenai.mina.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The number of requests and tokens today and the last 7 days are calculated based on the calendar day
 * of [zone] (including today). [dailyTokens] only counts today, [weeklyTokens] counts these 7 days.
 */
data class LimitUsage(
    val dailyCalls: Long = 0,
    val dailyTokens: Long = 0,
    val weeklyCalls: Long = 0,
    val weeklyTokens: Long = 0,
)

/** Get the number of requests "today/last 7 days" from the usage ledger and use it for the usage limit page. */
object UsageLimitsQuery {
    private const val HOUR_MS = 3_600_000L

    /**
     * When [providerId] is null, all service providers are counted, otherwise only this one is counted.
     * Each hourly bucket falls on which day of [zone] according to its starting time, which is
     * consistent with [UsageQuery].
     */
    fun report(snapshot: UsageSnapshot, providerId: String?, now: Instant, zone: ZoneId): LimitUsage {
        val today = now.atZone(zone).toLocalDate()
        val first = today.minusDays(6)
        var dailyCalls = 0L
        var dailyTokens = 0L
        var weeklyCalls = 0L
        var weeklyTokens = 0L
        for (row in snapshot.rows) {
            if (providerId != null && row.key.providerId != providerId) continue
            val day = Instant.ofEpochMilli(row.hour * HOUR_MS).atZone(zone).toLocalDate()
            if (day.isBefore(first) || day.isAfter(today)) continue
            val tokens = row.counts.input + row.counts.output
            weeklyCalls += row.counts.calls
            weeklyTokens += tokens
            if (day == today) {
                dailyCalls += row.counts.calls
                dailyTokens += tokens
            }
        }
        return LimitUsage(dailyCalls, dailyTokens, weeklyCalls, weeklyTokens)
    }
}
