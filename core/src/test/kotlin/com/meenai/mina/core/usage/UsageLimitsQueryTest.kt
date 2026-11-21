package com.meenai.mina.core.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class UsageLimitsQueryTest {
    private val utc = ZoneOffset.UTC
    private val ist = ZoneId.of("Asia/Kolkata")
    private val hour = UsageLedger.HOUR_MS

    private fun hourOf(t: Instant) = Math.floorDiv(t.toEpochMilli(), hour)

    private fun row(t: Instant, provider: String = "p1", input: Long = 0, output: Long = 0, calls: Long = 1) =
        UsageRow(hourOf(t), UsageKey(provider, "m1", UsagePurpose.Main), UsageCounts(input = input, output = output, calls = calls))

    private fun snap(vararg rows: UsageRow) = UsageSnapshot(rows.toList(), emptyMap())

    @Test
    fun todayAndWeekCountTheRightDays() {
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val s = snap(
            row(Instant.parse("2026-10-27T04:00:00Z"), input = 10, output = 5),
            row(Instant.parse("2026-10-26T04:00:00Z"), input = 20, output = 1),
            row(Instant.parse("2026-10-21T04:00:00Z"), input = 100), // 7 天窗口的第一天
            row(Instant.parse("2026-10-20T04:00:00Z"), input = 1000), // 窗口外
        )
        val u = UsageLimitsQuery.report(s, null, now, utc)
        assertEquals(1, u.dailyCalls)
        assertEquals(15, u.dailyTokens)
        assertEquals(3, u.weeklyCalls)
        assertEquals(136, u.weeklyTokens)
    }

    @Test
    fun providerFilterKeepsOnlyThatProvider() {
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val s = snap(
            row(Instant.parse("2026-10-27T04:00:00Z"), provider = "gemini_web"),
            row(Instant.parse("2026-10-27T05:00:00Z"), provider = "openrouter"),
        )
        val u = UsageLimitsQuery.report(s, "gemini_web", now, utc)
        assertEquals(1, u.dailyCalls)
        assertEquals(1, u.weeklyCalls)
    }

    @Test
    fun dayBoundaryFollowsTheZone() {
        // UTC 10-26 20:00 是 IST 10-27 01:30
        val now = Instant.parse("2026-10-27T03:00:00Z")
        val s = snap(row(Instant.parse("2026-10-26T20:00:00Z"), input = 7))
        val istUsage = UsageLimitsQuery.report(s, null, now, ist)
        assertEquals("IST 里这笔已经是今天", 1, istUsage.dailyCalls)
        assertEquals(7, istUsage.dailyTokens)
        val utcUsage = UsageLimitsQuery.report(s, null, now, utc)
        assertEquals("UTC 里这笔还是昨天", 0, utcUsage.dailyCalls)
        assertEquals(1, utcUsage.weeklyCalls)
    }
}
