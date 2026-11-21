package com.meenai.mina.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今天和最近 7 天的请求数与 token,按 [zone] 的日历日算(含今天)。
 * [dailyTokens] 只算今天,[weeklyTokens] 算这 7 天。
 */
data class LimitUsage(
    val dailyCalls: Long = 0,
    val dailyTokens: Long = 0,
    val weeklyCalls: Long = 0,
    val weeklyTokens: Long = 0,
)

/** 从用量账本里取「今天 / 最近 7 天」的请求数,给用量上限页用。 */
object UsageLimitsQuery {
    private const val HOUR_MS = 3_600_000L

    /**
     * [providerId] 为 null 时算全部服务商,否则只算这一家。
     * 每个整点桶按它开始的时刻落在 [zone] 的哪一天,和 [UsageQuery] 一致。
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
