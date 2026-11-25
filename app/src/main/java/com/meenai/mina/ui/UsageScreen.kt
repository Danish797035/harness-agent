package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.core.usage.ModelUsage
import com.meenai.mina.core.usage.ProviderUsage
import com.meenai.mina.core.usage.UsageBucket
import com.meenai.mina.core.usage.UsageLedger
import com.meenai.mina.core.usage.UsageQuery
import com.meenai.mina.core.usage.UsageRange
import com.meenai.mina.core.usage.UsageReport
import com.meenai.mina.core.usage.UsageStep
import com.meenai.mina.core.usage.UsageTotals
import com.meenai.mina.settings.Provider
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.IconTile
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.SegmentedControl
import com.meenai.mina.ui.components.cardSurface
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.meenai.mina.i18n.uiLocale
import kotlin.math.roundToInt

/** The five segments at the top of the usage page: the first four segments are fixed ranges, [Custom] opens date selection. */
enum class UsagePreset(private val labelRes: Int) {
    Day(R.string.usage_24h), Week(R.string.usage_7d), Month(R.string.usage_30d), Quarter(R.string.usage_90d), Custom(R.string.usage_custom);

    val label: String get() = tr(labelRes)

    /** The corresponding range of this section; [Custom] should give [from]..[to](local date). */
    fun range(from: LocalDate?, to: LocalDate?): UsageRange = when (this) {
        Day -> UsageRange.Last24Hours
        Week -> UsageRange.LastDays(7)
        Month -> UsageRange.LastDays(30)
        Quarter -> UsageRange.LastDays(90)
        Custom -> if (from != null && to != null) UsageRange.Custom(from, to) else UsageRange.LastDays(7)
    }
}

/** A row in the model ranking: [share] is the proportion of this row (all service providers, or the selected one). */
data class ModelRank(val provider: ProviderUsage, val model: ModelUsage, val share: Double)

/** Small rules for writing token usage in words, numbers, and charts. */
object UsageCopy {
    val TITLE: String get() = tr(R.string.usage_title)
    val NONE: String get() = tr(R.string.usage_none)
    val EMPTY_TITLE: String get() = tr(R.string.usage_empty_title)
    val EMPTY_BODY: String get() = tr(R.string.usage_empty_body)
    val NOTE: String get() = tr(R.string.usage_note)
    val TOTAL: String get() = tr(R.string.usage_total)
    val TREND: String get() = tr(R.string.usage_trend)
    val BY_PROVIDER: String get() = tr(R.string.usage_by_provider)
    val BY_PROVIDER_HINT: String get() = tr(R.string.usage_by_provider_hint)
    val BY_MODEL: String get() = tr(R.string.usage_by_model)
    val ALL: String get() = tr(R.string.usage_all)
    val ALL_PROVIDERS: String get() = tr(R.string.usage_all_providers)
    val OTHERS: String get() = tr(R.string.usage_others)
    val DELETED: String get() = tr(R.string.usage_deleted)
    val CHANGE_DATES: String get() = tr(R.string.usage_change_dates)
    const val UNIT = "Token"

    /** The maximum number of service providers with their own colors in the chart (the number of [MinaColors.series] in the theme), and the rest are classified as "others" in the trend chart. */
    const val COLORED = 3

    /**
     * Number of tokens: Write as below 10,000; press K, M, B upwards and keep three significant digits
     * (round off and remove the 0 at the end), such as 8765, 10K, 12.3K, 123K, 1.23M, 1.5B.
     */
    fun tokens(n: Long): String {
        if (n < 10_000) return n.toString()
        for ((unitRes, size) in listOf(R.string.usage_unit_k to 1_000L, R.string.usage_unit_m to 1_000_000L, R.string.usage_unit_b to 1_000_000_000L)) {
            val v = round3(BigDecimal(n).divide(BigDecimal(size)))
            if (v < BigDecimal(1_000) || unitRes == R.string.usage_unit_b) return "${v.stripTrailingZeros().toPlainString()}${tr(unitRes)}"
        }
        return n.toString()
    }

    private fun round3(v: BigDecimal): BigDecimal = v.setScale(
        when {
            v < BigDecimal.TEN -> 2
            v < BigDecimal(100) -> 1
            else -> 0
        },
        RoundingMode.HALF_UP,
    )

    /** Set the value of the row on the home page: the total of the past 7 days. If not, write "not yet". */
    fun settingsValue(weekTokens: Long): String = if (weekTokens <= 0) NONE else tr(R.string.usage_last_7d, tokens(weekTokens))

    fun calls(n: Long) = trPlural(R.plurals.times, n.toInt())

    /** Proportion: less than 1% but not 0, write "<1%". */
    fun percent(share: Double): String {
        val p = (share * 100).roundToInt()
        return if (p == 0 && share > 0) "<1%" else "$p%"
    }

    fun missing(n: Long) = trPlural(R.plurals.usage_missing, n.toInt())

    fun providerName(p: ProviderUsage) = if (p.deleted) p.name + DELETED else p.name

    fun stepLabel(step: UsageStep) = when (step) {
        UsageStep.Hour -> tr(R.string.usage_hourly)
        UsageStep.Day -> tr(R.string.usage_daily)
        UsageStep.Week -> tr(R.string.usage_weekly)
    }

    /** Local date: Do not write the year of this year. */
    fun date(d: LocalDate, today: LocalDate): String =
        if (d.year == today.year) tr(R.string.date_month_day, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)
        else tr(R.string.date_year_month_day, d.year, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)

    /** Select the line below for the range and write down the actual days. */
    fun rangeText(range: UsageRange, today: LocalDate): String = when (range) {
        UsageRange.Last24Hours -> tr(R.string.usage_past_24h)
        is UsageRange.LastDays -> tr(R.string.usage_until_today, date(UsageQuery.days(range, today).first, today))
        is UsageRange.Custom -> UsageQuery.days(range, today).let { (from, to) ->
            if (from == to) date(from, today) else "${date(from, today)} – ${date(to, today)}"
        }
    }

    /** The scale under the trend chart: write "18:00" by hour (write "now" in the box at this moment), write "10/27" by day and week (write "today" by day). */
    fun axis(b: UsageBucket, step: UsageStep, today: LocalDate, last: Boolean = false): String = when {
        step == UsageStep.Hour && last -> tr(R.string.usage_now)
        step == UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        b.start.toLocalDate() == today -> tr(R.string.usage_today)
        else -> "${b.start.monthValue}/${b.start.dayOfMonth}"
    }

    /** Which period of time is indicated when you click on a pillar. */
    fun period(b: UsageBucket, step: UsageStep, today: LocalDate): String = when (step) {
        UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        UsageStep.Day -> date(b.start.toLocalDate(), today)
        UsageStep.Week -> {
            val first = b.start.toLocalDate()
            val last = b.lastDay ?: first
            if (first == last) date(first, today) else "${date(first, today)} – ${date(last, today)}"
        }
    }

    /** What color does this service provider use in the chart? The one after [COLORED] is null (gray, and it is classified as "other" in the trend chart). */
    fun colorSlot(p: ProviderUsage): Int? = p.order.takeIf { it < COLORED }

    /** The top of the grid line: not less than the power of 1, 2, 4, 6, 8 times 10 of [max], and the scale at half of it is also an integer. */
    fun niceMax(max: Long): Long {
        if (max <= 0) return 1
        var scale = 1L
        while (scale * 10 < max) scale *= 10
        return listOf(1L, 2L, 4L, 6L, 8L, 10L).map { it * scale }.first { it >= max }
    }

    /** Ranking by model: If you select a service provider, only this one will be listed, not all service providers; by token, from most to least, the proportion will be based on the total of this row. */
    fun modelRanking(report: UsageReport, providerId: String?): List<ModelRank> {
        val from = providerId?.let { id -> listOfNotNull(report.provider(id)) } ?: report.providers
        val rows = from.flatMap { p -> p.models.map { p to it } }
            .sortedWith(compareByDescending<Pair<ProviderUsage, ModelUsage>> { it.second.totals.tokens }.thenByDescending { it.second.totals.calls })
        val total = rows.sumOf { it.second.totals.tokens }
        return rows.map { (p, m) -> ModelRank(p, m, if (total <= 0) 0.0 else m.totals.tokens.toDouble() / total) }
    }
}

/**
 * Settings › Token Usage: One page. Select the time at the top and the total usage during this period,
 * and there are three pictures below: Usage trend (stacked by service provider), ranking by service
 * provider (click one to select, then click cancel), and ranking by model (can switch service
 * providers). The selected service provider only affects which one is highlighted in the picture and
 * which one is listed in the model. The total usage is always all. Every time a record
 * ([UsageLedger.version]) is recorded in the ledger, the statistics are recalculated; the local time
 * zone is converted during statistics.
 */
@Composable
fun UsageScreen(ledger: UsageLedger, providers: StateFlow<List<Provider>>, onBack: () -> Unit) {
    var preset by rememberSaveable { mutableStateOf(UsagePreset.Week) }
    // Both ends of the custom range are saved as the epoch day (rememberSaveable type can be saved)
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val version by ledger.version.collectAsState()
    val live by providers.collectAsState()
    val range = preset.range(customFrom?.let(LocalDate::ofEpochDay), customTo?.let(LocalDate::ofEpochDay))
    val zone = ZoneId.systemDefault()
    val report by produceState<UsageReport?>(null, version, range, live) {
        value = withContext(Dispatchers.Default) {
            // The connected service providers are passed in according to the connection order, and the
            // color matching is fixed according to this order.
            UsageQuery.report(ledger.snapshot(), range, Instant.now(), zone, live.associate { it.id to it.name })
        }
    }
    // If the service provider selected after changing the time has no usage during this period, all will
    // be refunded.
    LaunchedEffect(report) {
        val r = report ?: return@LaunchedEffect
        if (selected?.let(r::provider) == null) selected = null
    }

    UsageContent(
        report = report, preset = preset, today = LocalDate.now(zone), selected = selected,
        onPreset = { p -> if (p == UsagePreset.Custom) picking = true else preset = p },
        onPickCustom = { picking = true },
        onSelect = { selected = it },
        onBack = onBack,
    )

    if (picking) {
        UsageRangePicker(
            today = LocalDate.now(zone),
            from = customFrom?.let(LocalDate::ofEpochDay), to = customTo?.let(LocalDate::ofEpochDay),
            onPicked = { from, to ->
                customFrom = from.toEpochDay(); customTo = to.toEpochDay()
                preset = UsagePreset.Custom
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Usage page without status for easy testing. [report] is null, indicating that statistics are still
 * being collected and only range selection is displayed. [onPreset] When [UsagePreset.Custom] is
 * received, the caller opens the date selection; "Change date" is also opened at the custom time point
 * ([onPickCustom]). [selected] is the selected service provider (null means all), click the ranking or
 * capsule to change it with [onSelect].
 */
@Composable
fun UsageContent(
    report: UsageReport?,
    preset: UsagePreset,
    today: LocalDate,
    selected: String?,
    onPreset: (UsagePreset) -> Unit,
    onPickCustom: () -> Unit,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val c = MinaTheme.colors
    PageScaffold(UsageCopy.TITLE, onBack) {
        SegmentedControl(
            UsagePreset.entries.map { it.label }, preset.ordinal, { onPreset(UsagePreset.entries[it]) },
            tags = UsagePreset.entries.map { "usage-range-${it.name}" },
        )
        if (report == null) return@PageScaffold
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            Text(UsageCopy.rangeText(report.range, today), style = MinaType.Hint, color = c.ink3)
            if (preset == UsagePreset.Custom) {
                Text(" · ", style = MinaType.Hint, color = c.ink3)
                Text(
                    UsageCopy.CHANGE_DATES, Modifier.clickable(role = Role.Button, onClick = onPickCustom).testTag("usage-change-dates"),
                    style = MinaType.Hint, color = c.accent, fontWeight = FontWeight.Medium,
                )
            }
        }
        if (report.providers.isEmpty()) {
            EmptyCard()
            return@PageScaffold
        }
        val pick = selected?.takeIf { report.provider(it) != null }
        TotalsCard(report.totals)

        ChartCard(UsageCopy.TREND, UsageCopy.stepLabel(report.step)) {
            TrendChart(report, pick, today)
        }

        ChartCard(UsageCopy.BY_PROVIDER, UsageCopy.BY_PROVIDER_HINT) {
            val max = report.providers.maxOf { it.totals.tokens }.coerceAtLeast(1)
            Column(Modifier.padding(top = 6.dp)) {
                report.providers.forEach { p ->
                    RankRow(
                        UsageCopy.providerName(p), mono = false, muted = p.deleted, color = seriesColor(c, p),
                        fraction = p.totals.tokens.toFloat() / max, tokens = p.totals.tokens, share = p.share,
                        dim = pick != null && pick != p.id, bold = pick == p.id,
                        onClick = { onSelect(if (pick == p.id) null else p.id) }, tag = "usage-rank-provider-${p.id}",
                    )
                }
            }
        }

        ChartCard(UsageCopy.BY_MODEL, pick?.let(report::provider)?.let(UsageCopy::providerName) ?: UsageCopy.ALL_PROVIDERS) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsageChip(UsageCopy.ALL, pick == null, { onSelect(null) }, tag = "usage-chip-all")
                report.providers.sortedBy { it.order }.forEach { p ->
                    UsageChip(p.name, pick == p.id, { onSelect(p.id) }, deleted = p.deleted, tag = "usage-chip-${p.id}")
                }
            }
            val models = UsageCopy.modelRanking(report, pick)
            val max = models.maxOfOrNull { it.model.totals.tokens }?.coerceAtLeast(1) ?: 1
            models.forEach { m ->
                RankRow(
                    m.model.model, mono = true, muted = false, color = seriesColor(c, m.provider),
                    fraction = m.model.totals.tokens.toFloat() / max, tokens = m.model.totals.tokens, share = m.share,
                    tag = "usage-rank-model-${m.provider.id}-${m.model.model}",
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        if (report.totals.callsWithoutUsage > 0) {
            FooterNote(UsageCopy.missing(report.totals.callsWithoutUsage), Modifier.testTag("usage-missing"))
        }
        FooterNote(UsageCopy.NOTE)
    }
}

private fun seriesColor(c: MinaColors, p: ProviderUsage): Color = UsageCopy.colorSlot(p)?.let { c.series.getOrNull(it) } ?: c.seriesOther

/** Total usage card: The big words are input plus output, and the bottom row is input, output, cache hits, and call times. */
@Composable
private fun TotalsCard(t: UsageTotals) {
    val c = MinaTheme.colors
    MinaCard {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            Text(UsageCopy.TOTAL, style = MinaType.Caption, color = c.ink3)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                Text(UsageCopy.tokens(t.tokens), Modifier.testTag("usage-total"), style = TotalStyle, color = c.ink)
                Text(
                    UsageCopy.UNIT, Modifier.padding(start = 4.dp, bottom = 6.dp),
                    style = MinaType.Value, fontWeight = FontWeight.Medium, color = c.ink2,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(tr(R.string.usage_input), UsageCopy.tokens(t.input), Modifier.weight(1f))
                Stat(tr(R.string.usage_output), UsageCopy.tokens(t.output), Modifier.weight(1f))
                Stat(tr(R.string.usage_cached), UsageCopy.tokens(t.cached), Modifier.weight(1f))
                Stat(tr(R.string.usage_calls), UsageCopy.calls(t.calls), Modifier.weight(1f))
            }
        }
    }
}

private val TotalStyle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MinaType.Hint, color = MinaTheme.colors.ink3, maxLines = 1)
        Text(
            value, Modifier.padding(top = 2.dp), style = MinaType.Value, fontWeight = FontWeight.SemiBold,
            color = MinaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A card with a picture: a title on the left and a small text on the right. */
@Composable
private fun ChartCard(title: String, hint: String, content: @Composable () -> Unit) {
    val c = MinaTheme.colors
    MinaCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = CardTitle, color = c.ink)
                Text(hint, style = MinaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

private val CardTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

/** Without dosage: a centered instruction card. */
@Composable
private fun EmptyCard() {
    val c = MinaTheme.colors
    MinaCard(Modifier.testTag("usage-empty")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(MinaIcons.Chart)
            Text(UsageCopy.EMPTY_TITLE, Modifier.padding(top = 12.dp), style = MinaType.BodyStrong, color = c.ink)
            Text(UsageCopy.EMPTY_BODY, Modifier.padding(top = 6.dp), style = MinaType.Caption, color = c.ink3, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The ranking row: name, horizontal bar (the color is that of the service provider), usage and
 * proportion. When [dim], the whole line becomes lighter (another service provider is selected), when
 * [bold], the name is bolded (selected); when [muted], the name uses the auxiliary text color (the
 * service provider has been deleted).
 */
@Composable
private fun RankRow(
    name: String, mono: Boolean, muted: Boolean, color: Color, fraction: Float, tokens: Long, share: Double,
    dim: Boolean = false, bold: Boolean = false, onClick: (() -> Unit)? = null, tag: String,
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RowShape).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (dim) 0.4f else 1f)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            name, Modifier.width(118.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = (if (mono) RankMono else RankText).let { if (bold) it.copy(fontWeight = FontWeight.SemiBold) else it },
            color = if (muted) c.ink3 else c.ink,
        )
        Box(Modifier.weight(1f).height(14.dp)) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val w = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
                val r = 4.dp.toPx().coerceAtMost(w / 2)
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, size.height, topRightCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r)))
                }
                drawPath(path, color)
            }
        }
        Column(Modifier.width(74.dp), horizontalAlignment = Alignment.End) {
            Text(UsageCopy.tokens(tokens), style = RankValue, color = c.ink, maxLines = 1)
            Text(UsageCopy.percent(share), style = RankShare, color = c.ink3, maxLines = 1)
        }
    }
}

private val RowShape = RoundedCornerShape(8.dp)
private val RankText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp)
private val RankMono = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
private val RankValue = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
private val RankShare = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)

/** Capsules for switching service providers: When selected, the color is emphasized and the solid background is highlighted; the name of [deleted] becomes lighter and "(deleted)" is written after it. */
@Composable
private fun UsageChip(text: String, selected: Boolean, onClick: () -> Unit, deleted: Boolean = false, tag: String) {
    val c = MinaTheme.colors
    val fg = when {
        selected -> c.onAccent
        deleted -> c.ink3
        else -> c.ink
    }
    Box(
        Modifier.height(34.dp)
            .then(if (selected) Modifier.background(c.accent, MinaShapes.Pill) else Modifier.cardSurface(c, MinaShapes.Pill))
            .clip(MinaShapes.Pill)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (deleted) text + UsageCopy.DELETED else text, maxLines = 1, color = fg, style = ChipText)
    }
}

private val ChipText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

/** A segment in the trend chart: each service provider has its own color ([slot] is the color number), and the rest are combined into "others" ([slot] is null). */
private data class StackPart(val slot: Int?, val tokens: Long)

/**
 * Usage trend: One pillar per grid, stacked according to the service provider (the colored one is at the
 * bottom, "other" is at the top), leaving 2dp between segments and columns, and 4dp rounded corners on
 * the top of each column. Leave a column on the right to write the scale of the two dotted grids (the
 * top and half of the integer). Click one to write the total of this box and the usage of each company
 * at the top, and click again to close it; when you choose a service provider, the other companies'
 * segments will fade to a quarter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendChart(report: UsageReport, selected: String?, today: LocalDate) {
    val c = MinaTheme.colors
    val series = report.series
    if (series.isEmpty()) return
    val byId = report.providers.associateBy { it.id }
    val colored = report.providers.filter { UsageCopy.colorSlot(it) != null }.sortedBy { it.order }
    val hasOthers = report.providers.any { UsageCopy.colorSlot(it) == null && it.totals.tokens > 0 }
    val selectedSlot = selected?.let(byId::get)?.let { UsageCopy.colorSlot(it) ?: -1 }
    // Bottom-up: colored items in order, "others" on top
    val slots: List<Int?> = colored.map(UsageCopy::colorSlot) + if (hasOthers) listOf(null) else emptyList()
    val stacks = series.map { b ->
        val bySlot = b.byProvider.entries.groupBy { byId[it.key]?.let(UsageCopy::colorSlot) }
        slots.map { s -> StackPart(s, bySlot[s].orEmpty().sumOf { it.value }) }.filter { it.tokens > 0 }
    }
    val nice = UsageCopy.niceMax(series.maxOf { it.tokens })
    var tapped by remember(series) { mutableStateOf<Int?>(null) }
    val gutter = 38.dp

    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colored.forEach { p -> LegendItem(c.series[UsageCopy.colorSlot(p)!!], UsageCopy.providerName(p)) }
        if (hasOthers) LegendItem(c.seriesOther, UsageCopy.OTHERS)
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(132.dp)) {
        Canvas(
            Modifier.fillMaxSize().testTag("usage-chart")
                .semantics { contentDescription = "${UsageCopy.TREND}，${UsageCopy.stepLabel(report.step)}" }
                .pointerInput(series) {
                    detectTapGestures { o ->
                        val plot = size.width - gutter.toPx()
                        if (o.x > plot) return@detectTapGestures
                        val i = (o.x / (plot / series.size)).toInt().coerceIn(0, series.lastIndex)
                        tapped = if (tapped == i) null else i
                    }
                },
        ) {
            val plot = size.width - gutter.toPx()
            val h = size.height
            val gap = 2.dp.toPx()
            val n = series.size
            val colW = ((plot - gap * (n - 1)) / n).coerceAtLeast(1f)
            val step = if (n > 1) (plot - colW) / (n - 1) else 0f
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            listOf(0f, 0.5f).forEach { y -> drawLine(c.grid, Offset(0f, h * y), Offset(plot, h * y), 1.dp.toPx(), pathEffect = dash) }
            drawLine(c.grid, Offset(0f, h), Offset(plot, h), 1.dp.toPx())
            stacks.forEachIndexed { i, segs ->
                val x = i * step
                var bottom = h
                segs.forEachIndexed { k, s ->
                    val top = k == segs.lastIndex
                    val segH = h * s.tokens / nice
                    val drawn = if (top) segH else (segH - gap).coerceAtLeast(0.5f)
                    val base = if (s.slot != null) c.series.getOrNull(s.slot) ?: c.seriesOther else c.seriesOther
                    val color = if (selectedSlot != null && selectedSlot != (s.slot ?: -1)) base.copy(alpha = base.alpha * 0.25f) else base
                    if (top) {
                        val r = 4.dp.toPx().coerceAtMost(colW / 2).coerceAtMost(drawn)
                        drawPath(
                            Path().apply {
                                addRoundRect(RoundRect(x, bottom - drawn, x + colW, bottom, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
                            },
                            color,
                        )
                    } else {
                        drawRect(color, Offset(x, bottom - drawn), Size(colW, drawn))
                    }
                    bottom -= segH
                }
            }
        }
        // The grid scale is written in the right column
        GridLabel(UsageCopy.tokens(nice), Modifier.align(Alignment.TopEnd).offset(y = (-7).dp))
        GridLabel(UsageCopy.tokens(nice / 2), Modifier.align(Alignment.CenterEnd).offset(y = (-7).dp))
        tapped?.let { i -> TrendTip(series[i], report, i, gutter, today) }
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, end = gutter)) {
        val style = MinaType.Hint
        Text(UsageCopy.axis(series.first(), report.step, today), Modifier.align(Alignment.CenterStart), style = style, color = c.ink3)
        if (series.size > 2) {
            Text(UsageCopy.axis(series[series.size / 2], report.step, today), Modifier.align(Alignment.Center), style = style, color = c.ink3)
        }
        if (series.size > 1) {
            Text(UsageCopy.axis(series.last(), report.step, today, last = true), Modifier.align(Alignment.CenterEnd), style = style, color = c.ink3)
        }
    }
}

@Composable
private fun LegendItem(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, LegendShape))
        Text(name, style = MinaType.Hint, color = MinaTheme.colors.ink2, maxLines = 1)
    }
}

private val LegendShape = RoundedCornerShape(3.dp)

@Composable
private fun GridLabel(text: String, modifier: Modifier) {
    Text(text, modifier.width(34.dp), style = GridText, color = MinaTheme.colors.ink3, textAlign = TextAlign.End, maxLines = 1)
}

private val GridText = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp)

/** Instructions when clicking on a pillar: Which period of time, total, each service provider below has one line (from most to least usage); face that pillar horizontally, and do not show the left and right sides of the picture. */
@Composable
private fun TrendTip(b: UsageBucket, report: UsageReport, index: Int, gutter: Dp, today: LocalDate) {
    val c = MinaTheme.colors
    val rows = b.byProvider.entries.sortedByDescending { it.value }
    Column(
        Modifier.layout { m, cons ->
            val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
            layout(cons.maxWidth, p.height) {
                val plot = cons.maxWidth - gutter.roundToPx()
                val center = plot / report.series.size.toFloat() * (index + 0.5f)
                p.place((center - p.width / 2f).toInt().coerceIn(0, (cons.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        }.background(c.ink, TipShape).padding(horizontal = 9.dp, vertical = 6.dp).testTag("usage-chart-tip"),
    ) {
        Text(
            "${UsageCopy.period(b, report.step, today)} · ${UsageCopy.tokens(b.tokens)}",
            style = TipText, fontWeight = FontWeight.SemiBold, color = c.bg, maxLines = 1,
        )
        rows.forEach { (id, v) ->
            val name = report.provider(id)?.let(UsageCopy::providerName) ?: id
            Text("$name　${UsageCopy.tokens(v)}", style = TipText, color = c.bg, maxLines = 1)
        }
    }
}

private val TipShape = RoundedCornerShape(8.dp)
private val TipText = TextStyle(fontSize = 11.5.sp, lineHeight = 18.sp)

/** Custom range date selection: You can only select the days from which the ledger is still retained (the most recent [UsageLedger.KEEP_DAYS] days) to today. You can view both ends by selecting both. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageRangePicker(today: LocalDate, from: LocalDate?, to: LocalDate?, onPicked: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The date picker date is the number of milliseconds from UTC zero
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val earliest = today.minusDays(UsageLedger.KEEP_DAYS.toLong())
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (from ?: today.minusDays(6)).let(::millis),
        initialSelectedEndDateMillis = (to ?: today).let(::millis),
        yearRange = earliest.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = date(utcTimeMillis).let { it >= earliest && it <= today }
            override fun isSelectableYear(year: Int) = year in earliest.year..today.year
        },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (start != null && end != null) onPicked(date(start), date(end)) }, enabled = start != null && end != null) {
                Text(tr(R.string.view))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    ) {
        DateRangePicker(state, Modifier.weight(1f), showModeToggle = false)
    }
}
