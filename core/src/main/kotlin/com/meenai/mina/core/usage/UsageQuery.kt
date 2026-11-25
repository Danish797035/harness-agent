package com.meenai.mina.core.usage

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Depends on which period of time. [Last24Hours] is the rolling last 24 hours (including the hour at
 * this moment), in hours and minutes; [LastDays] is the last few local calendar days including today,
 * [Custom] is the local date [from]..[to] (inclusive), all in days, and hours exceeding
 * [UsageQuery.MAX_DAILY_BARS] are in weeks.
 */
sealed interface UsageRange {
    data object Last24Hours : UsageRange
    data class LastDays(val days: Int) : UsageRange
    data class Custom(val from: LocalDate, val to: LocalDate) : UsageRange
}

/** How long is a bar in a trend chart? */
enum class UsageStep { Hour, Day, Week }

/** Total over time. [tokens] is input plus output (cache hits are counted in the input and are not added separately). */
data class UsageTotals(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    val tokens: Long get() = input + output

    operator fun plus(n: UsageCounts) =
        UsageTotals(input + n.input, output + n.output, cached + n.cached, calls + n.calls, callsWithoutUsage + n.callsWithoutUsage)
}

/**
 * One branch of the trend chart: [start] is the local time at the beginning of this grid (hourly is the
 * hour, day and week is zero o'clock on the first day), [lastDay] is the last day of this grid
 * (inclusive; the last grid may be less than 7 days), and the rest are null. [byProvider] is the token
 * of each service provider in this grid (those without usage are not listed).
 */
data class UsageBucket(
    val start: LocalDateTime,
    val tokens: Long,
    val byProvider: Map<String, Long> = emptyMap(),
    val lastDay: LocalDate? = null,
)

/** The amount of a certain use in a model; [share] is the proportion of this model (0..1). It is only used for one dimension of statistics and is not distinguished on the interface. */
data class PurposeUsage(val purpose: UsagePurpose, val totals: UsageTotals, val share: Double)

/** The usage of the next model of a service provider; [share] is the proportion of this service provider. */
data class ModelUsage(val model: String, val totals: UsageTotals, val share: Double, val purposes: List<PurposeUsage>)

/**
 * Usage of one provider; [share] is the fraction of the total. [name] is the current name; when the
 * provider has been deleted ([deleted]) it is the name recorded when the usage was booked. [order] is
 * this provider's fixed position (connected providers in connection order, deleted ones last by the
 * time they were first booked); colours follow it and do not change with the time range.
 */
data class ProviderUsage(
    val id: String,
    val name: String,
    val deleted: Boolean,
    val totals: UsageTotals,
    val share: Double,
    val models: List<ModelUsage>,
    val order: Int = 0,
)

/** Statistics for a period of time: total, trend chart ([step] is as long as one grid), breakdown by service provider › model › purpose, each layer is ordered from most to least by token. */
data class UsageReport(
    val range: UsageRange,
    val step: UsageStep,
    val totals: UsageTotals,
    val series: List<UsageBucket>,
    val providers: List<ProviderUsage>,
) {
    val hourly: Boolean get() = step == UsageStep.Hour

    fun provider(id: String): ProviderUsage? = providers.firstOrNull { it.id == id }
}

/**
 * Calculate statistics from the contents of the ledger. The ledger is stored on the hour of UTC, and the
 * change to local time is done here: an hour bucket falls on the local day according to the time it
 * starts (the date boundary uses the rule of [LocalDate.atStartOfDay], and the 23 or 25 hours of
 * daylight saving time is also the same); where the time zone is half an hour different from UTC, the
 * hour that crosses local zero is counted to the day it starts.
 */
object UsageQuery {
    private const val HOUR_MS = UsageLedger.HOUR_MS

    /** Divide up to a few sticks by day; then press the length by week (one stick every 7 days from the first day). */
    const val MAX_DAILY_BARS = 45

    /**
     * [live] is the service provider that is still connected (id → name), arranged in order of
     * connection (the iteration order is the order); those that are not included have been deleted, and
     * the names are left in the ledger.
     */
    fun report(snapshot: UsageSnapshot, range: UsageRange, now: Instant, zone: ZoneId, live: Map<String, String>): UsageReport {
        val nowHour = Math.floorDiv(now.toEpochMilli(), HOUR_MS)
        val today = now.atZone(zone).toLocalDate()
        // Which column each row falls on; if not in the range, it is null
        val slot: (Long) -> Int?
        val starts: List<LocalDateTime>
        val lastDays: List<LocalDate?>
        val step: UsageStep
        when (range) {
            UsageRange.Last24Hours -> {
                val first = nowHour - 23
                step = UsageStep.Hour
                starts = (0 until 24).map { i -> LocalDateTime.ofInstant(Instant.ofEpochMilli((first + i) * HOUR_MS), zone) }
                lastDays = starts.map { null }
                slot = { h -> (h - first).takeIf { it in 0..23 }?.toInt() }
            }
            is UsageRange.LastDays, is UsageRange.Custom -> {
                val (from, to) = days(range, today)
                val count = ChronoUnit.DAYS.between(from, to).toInt() + 1
                val days = if (count > MAX_DAILY_BARS) 7 else 1
                step = if (days == 7) UsageStep.Week else UsageStep.Day
                val bars = (count + days - 1) / days
                starts = (0 until bars).map { from.plusDays(it.toLong() * days).atStartOfDay() }
                lastDays = (0 until bars).map { i -> if (days == 1) null else minOf(from.plusDays(i * 7L + 6), to) }
                slot = { h ->
                    val d = Instant.ofEpochMilli(h * HOUR_MS).atZone(zone).toLocalDate()
                    if (d < from || d > to) null else ChronoUnit.DAYS.between(from, d).toInt() / days
                }
            }
        }

        val bars = LongArray(starts.size)
        val perBar = List(starts.size) { HashMap<String, Long>() }
        val byKey = HashMap<UsageKey, UsageTotals>()
        val firstSeen = HashMap<String, Long>()
        var total = UsageTotals()
        for (row in snapshot.rows) {
            firstSeen.merge(row.key.providerId, row.hour, ::minOf)
            val i = slot(row.hour) ?: continue
            val t = row.counts.input + row.counts.output
            bars[i] += t
            if (t > 0) perBar[i].merge(row.key.providerId, t, Long::plus)
            byKey[row.key] = (byKey[row.key] ?: UsageTotals()) + row.counts
            total += row.counts
        }

        // Fixed order: connected ones are arranged in the order of connection, deleted items are
        // arranged later, and the earliest accounting time is used.
        val liveOrder = live.keys.withIndex().associate { (i, id) -> id to i }
        val goneOrder = firstSeen.keys.filter { it !in live }.sortedWith(compareBy<String> { firstSeen.getValue(it) }.thenBy { it })
            .withIndex().associate { (i, id) -> id to live.size + i }

        val providers = byKey.entries.groupBy { it.key.providerId }.map { (pid, entries) ->
            val models = entries.groupBy { it.key.model }.map { (model, es) ->
                val mTotal = es.fold(UsageTotals()) { a, e -> a.add(e.value) }
                val purposes = es.groupBy { it.key.purpose }.map { (p, ps) -> p to ps.fold(UsageTotals()) { a, e -> a.add(e.value) } }
                    .sortedWith(compareByDescending<Pair<UsagePurpose, UsageTotals>> { it.second.tokens }
                        .thenByDescending { it.second.calls }.thenBy { it.first.ordinal })
                    .map { (p, t) -> PurposeUsage(p, t, share(t, mTotal)) }
                Triple(model, mTotal, purposes)
            }
            val pTotal = models.fold(UsageTotals()) { a, m -> a.add(m.second) }
            val sortedModels = models.sortedWith(compareByDescending<Triple<String, UsageTotals, List<PurposeUsage>>> { it.second.tokens }
                .thenByDescending { it.second.calls }.thenBy { it.first })
                .map { (m, t, ps) -> ModelUsage(m, t, share(t, pTotal), ps) }
            val name = live[pid] ?: snapshot.providerNames[pid] ?: pid
            ProviderUsage(
                pid, name, deleted = pid !in live, totals = pTotal, share = share(pTotal, total), models = sortedModels,
                order = liveOrder[pid] ?: goneOrder.getValue(pid),
            )
        }.sortedWith(compareByDescending<ProviderUsage> { it.totals.tokens }.thenByDescending { it.totals.calls }.thenBy { it.order })

        return UsageReport(
            range, step, totals = total,
            series = starts.indices.map { i -> UsageBucket(starts[i], bars[i], perBar[i], lastDays[i]) },
            providers = providers,
        )
    }

    /** Which days fall within the range of days (including both ends); if the custom ends are written backwards, swap them. */
    fun days(range: UsageRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        is UsageRange.LastDays -> today.minusDays(range.days.coerceAtLeast(1) - 1L) to today
        is UsageRange.Custom -> if (range.from <= range.to) range.from to range.to else range.to to range.from
        UsageRange.Last24Hours -> today.minusDays(1) to today
    }

    private fun UsageTotals.add(o: UsageTotals) =
        UsageTotals(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    private fun share(part: UsageTotals, whole: UsageTotals): Double =
        if (whole.tokens <= 0) 0.0 else part.tokens.toDouble() / whole.tokens
}
