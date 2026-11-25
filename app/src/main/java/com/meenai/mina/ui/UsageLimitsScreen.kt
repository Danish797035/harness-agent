package com.meenai.mina.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.core.gemini.GeminiUsage
import com.meenai.mina.core.gemini.GeminiUsageWindow
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.usage.LimitUsage
import com.meenai.mina.core.usage.UsageLedger
import com.meenai.mina.core.usage.UsageLimitsQuery
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** There are three states of server usage: first read, read, and return to local machine if no read. */
private enum class SyncPhase { Checking, Server, Local }

/**
 * Settings › Usage limits.
 *
 * When connected to Gemini, first read the Gemini account's own usage (the same GraphQL query as the
 * Usage limits page of the web page). Once it is read, it will display the Current usage / Weekly limit
 * and the real reset time as shown on the web page; if it cannot be read (not logged in, the interface
 * has changed, or the network is unavailable), it will be returned to the local account book and
 * honestly write "This is the number of requests for this App", and no longer use the local budget to
 * pretend to be Google's quota.
 *
 * Server-side usage is read every 60 seconds (Google's own page isn't any faster), and local numbers are
 * updated with the ledger every 5 seconds while the page is open.
 */
@Composable
fun UsageLimitsScreen(
    ledger: UsageLedger,
    store: ProviderStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    val version by ledger.version.collectAsState()
    val live by store.providers.collectAsState()
    var budget by remember { mutableIntStateOf(flags.usageDailyBudget()) }
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The page moves every 5 seconds when it is in the foreground: when it crosses zero, it will change
    // today/this week; it will stop when it returns to the background to save power.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            now = Instant.now()
            while (isActive) {
                delay(5_000)
                now = Instant.now()
            }
        }
    }
    val zone = remember { ZoneId.of("Asia/Kolkata") }
    // If Gemini Web is installed, its account usage will be used; otherwise, only the local machine will
    // be counted.
    val providerId = remember(live) {
        live.firstOrNull { it.kind == ProviderConfig.Kind.GeminiWeb || it.signIn == ProviderStore.GEMINI_WEB_SIGN_IN }?.id
    }
    val usage by produceState(LimitUsage(), version, providerId, now) {
        value = withContext(Dispatchers.Default) { UsageLimitsQuery.report(ledger.snapshot(), providerId, now, zone) }
    }

    var serverUsage by remember(providerId) { mutableStateOf<GeminiUsage?>(null) }
    var lastSyncAt by remember(providerId) { mutableStateOf(0L) }
    // Display "Checking" before reading for the first time. Don't flash the local number first and then
    // jump to Google.
    var phase by remember(providerId) {
        mutableStateOf(if (providerId == null) SyncPhase.Local else SyncPhase.Checking)
    }
    LaunchedEffect(providerId, lifecycle) {
        if (providerId == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var failures = 0
            while (isActive) {
                val fetched = withContext(Dispatchers.IO) {
                    val session = store.geminiWebSession(providerId) ?: return@withContext null
                    val result = runCatching {
                        GeminiWebClient.fetchUsage(GeminiWebClient.geminiWebHttp, session)
                    }.getOrNull() ?: return@withContext null
                    if (result.session != session) store.updateGeminiWebSession(providerId, result.session)
                    result.usage
                }
                if (fetched != null) {
                    serverUsage = fetched
                    lastSyncAt = System.currentTimeMillis()
                    phase = SyncPhase.Server
                    failures = 0
                } else if (serverUsage == null) {
                    phase = SyncPhase.Local
                    failures++
                } else {
                    // If it has been read before, it will continue to be displayed, and only the "how
                    // long ago it was updated" will become old; the more times it fails, the slower the
                    // retry will be.
                    failures++
                }
                delay(if (failures == 0) 60_000L else (60_000L * failures).coerceAtMost(300_000L))
            }
        }
    }

    val server = if (phase == SyncPhase.Server) serverUsage else null
    val tier = server?.tier?.uppercase().orEmpty()
    val plan = when {
        "ULTRA" in tier -> stringResource(R.string.usage_limits_ultra)
        "PRO" in tier -> stringResource(R.string.usage_limits_pro)
        else -> null
    }

    PageScaffold(stringResource(R.string.usage_limits_title), onBack) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when (phase) {
                SyncPhase.Server -> {
                    plan?.let {
                        PlanChip(it)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        updatedLabel(lastSyncAt, now),
                        style = MinaType.Hint, color = MinaTheme.colors.ink3,
                    )
                }
                SyncPhase.Checking -> Text(
                    stringResource(R.string.usage_limits_checking),
                    style = MinaType.Hint, color = MinaTheme.colors.ink3,
                )
                SyncPhase.Local -> {
                    LocalChip()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.usage_limits_updates_live),
                        style = MinaType.Hint, color = MinaTheme.colors.ink3,
                    )
                }
            }
        }
        Text(
            stringResource(
                when {
                    phase != SyncPhase.Local -> R.string.usage_limits_plan_desc
                    providerId != null -> R.string.usage_limits_local_desc
                    else -> R.string.usage_limits_app_desc
                }
            ),
            Modifier.padding(top = 8.dp, bottom = 14.dp),
            style = MinaType.Caption, color = MinaTheme.colors.ink2,
        )

        when {
            phase == SyncPhase.Checking -> CheckingCard()
            server != null -> {
            server.daily?.let {
                ServerLimitCard(
                    icon = MinaIcons.Chart,
                    title = stringResource(R.string.usage_limits_current),
                    window = it,
                    weekly = false,
                    tag = "usage-limits-daily",
                )
            }
            server.weekly?.let {
                ServerLimitCard(
                    icon = MinaIcons.Clock,
                    title = stringResource(R.string.usage_limits_weekly),
                    window = it,
                    weekly = true,
                    tag = "usage-limits-weekly",
                )
            }
            FooterNote(stringResource(R.string.usage_limits_server_note))
            }
            else -> {
            val safeBudget = budget.coerceAtLeast(1)
            val weeklyBudget = safeBudget * 7
            LimitCard(
                icon = MinaIcons.Chart,
                title = stringResource(R.string.usage_limits_today_app),
                share = usage.dailyCalls.toDouble() / safeBudget,
                detail = stringResource(R.string.usage_limits_of, usage.dailyCalls.toInt(), safeBudget),
                reset = stringResource(R.string.usage_limits_resets_daily),
                tokens = usage.dailyTokens,
                tag = "usage-limits-daily",
            )
            LimitCard(
                icon = MinaIcons.Clock,
                title = stringResource(R.string.usage_limits_week_app),
                share = usage.weeklyCalls.toDouble() / weeklyBudget,
                detail = stringResource(R.string.usage_limits_of, usage.weeklyCalls.toInt(), weeklyBudget),
                reset = stringResource(R.string.usage_limits_rolling),
                tokens = usage.weeklyTokens,
                tag = "usage-limits-weekly",
            )

            GroupLabel(stringResource(R.string.usage_limits_budget))
            MinaCard(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.usage_limits_budget), Modifier.weight(1f),
                            style = MinaType.BodyStrong, color = MinaTheme.colors.ink,
                        )
                        BudgetButton(
                            icon = MinaIcons.Minus, label = stringResource(R.string.usage_limits_less),
                            enabled = budget > FeatureFlags.MIN_USAGE_BUDGET, tag = "usage-budget-minus",
                        ) {
                            budget = (budget - FeatureFlags.USAGE_BUDGET_STEP).coerceAtLeast(FeatureFlags.MIN_USAGE_BUDGET)
                            flags.setUsageDailyBudget(budget)
                        }
                        Text(
                            stringResource(R.string.usage_limits_budget_value, budget),
                            Modifier.padding(horizontal = 8.dp).width(120.dp),
                            style = MinaType.Value.copy(fontWeight = FontWeight.SemiBold), color = MinaTheme.colors.ink,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        BudgetButton(
                            icon = MinaIcons.Plus, label = stringResource(R.string.usage_limits_more),
                            enabled = budget < FeatureFlags.MAX_USAGE_BUDGET, tag = "usage-budget-plus",
                        ) {
                            budget = (budget + FeatureFlags.USAGE_BUDGET_STEP).coerceAtMost(FeatureFlags.MAX_USAGE_BUDGET)
                            flags.setUsageDailyBudget(budget)
                        }
                    }
                    Text(
                        stringResource(R.string.usage_limits_budget_note), Modifier.padding(top = 8.dp),
                        style = MinaType.Hint, color = MinaTheme.colors.ink3,
                    )
                }
            }
            FooterNote(stringResource(R.string.usage_limits_note))
            }
        }
    }
}

/** Gemini account usage window: percentage + progress bar + real reset time. */
@Composable
private fun ServerLimitCard(
    icon: ImageVector,
    title: String,
    window: GeminiUsageWindow,
    weekly: Boolean,
    tag: String,
) {
    val reset = if (weekly) {
        stringResource(
            R.string.usage_limits_resets_on,
            dateText(window.resetsAtEpochMs),
            timeText(window.resetsAtEpochMs),
        )
    } else {
        stringResource(R.string.usage_limits_resets_at, timeText(window.resetsAtEpochMs))
    }
    LimitCard(
        icon = icon,
        title = title,
        share = window.percentUsed / 100.0,
        detail = null,
        reset = reset,
        tokens = 0,
        tag = tag,
    )
}

@Composable
private fun PlanChip(label: String) {
    val c = MinaTheme.colors
    Text(
        label,
        Modifier.clip(MinaShapes.Pill).background(c.accentSoft).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MinaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
    )
}

@Composable
private fun LocalChip() {
    val c = MinaTheme.colors
    Row(
        Modifier.clip(MinaShapes.Pill).background(c.fill).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.ink3))
        Text(
            stringResource(R.string.usage_limits_local_chip),
            style = MinaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = c.ink2,
        )
    }
}

/** Placeholder when reading the account usage for the first time to prevent local numbers from flashing by. */
@Composable
private fun CheckingCard() {
    MinaCard(Modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(18.dp), strokeWidth = 2.dp, color = MinaTheme.colors.accent,
            )
            Text(
                stringResource(R.string.usage_limits_checking),
                style = MinaType.BodyStrong, color = MinaTheme.colors.ink2,
            )
        }
    }
}

/** "Updated just now / 3 min ago" depends on the outer layer ticking every 5 seconds. */
@Composable
private fun updatedLabel(lastSyncAt: Long, now: Instant): String {
    val minutes = ((now.toEpochMilli() - lastSyncAt).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes <= 0 -> stringResource(R.string.usage_limits_updated_now)
        minutes < 60 -> stringResource(R.string.usage_limits_updated_min, minutes)
        else -> stringResource(R.string.usage_limits_updated_hour, minutes / 60)
    }
}

/** A cap card: title, "X% used" on the right, progress bar, description and reset time. */
@Composable
private fun LimitCard(
    icon: ImageVector,
    title: String,
    share: Double,
    detail: String?,
    reset: String,
    tokens: Long,
    tag: String,
) {
    val c = MinaTheme.colors
    val fraction = share.toFloat().coerceIn(0f, 1f)
    val color = when {
        share >= 1.0 -> c.bad
        share >= 0.8 -> c.warn
        else -> c.accent
    }
    val percent = (share * 100).roundToInt()
    MinaCard(Modifier.padding(bottom = 12.dp).testTag(tag)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(17.dp), tint = c.ink3)
                Spacer(Modifier.width(8.dp))
                Text(title, Modifier.weight(1f), style = MinaType.BodyStrong, color = c.ink)
                Text(
                    stringResource(R.string.usage_limits_used_pct, percent),
                    style = MinaType.BodyStrong.copy(fontFeatureSettings = "tnum"), color = color,
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(MinaShapes.Pill).background(c.hair)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
            ) {
                if (fraction > 0f) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(MinaShapes.Pill).background(color))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail != null) {
                    Text(detail, Modifier.weight(1f), style = MinaType.Hint, color = c.ink2)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(reset, style = MinaType.Hint, color = c.ink3)
            }
            if (tokens > 0) {
                Text(
                    stringResource(R.string.usage_limits_tokens, UsageCopy.tokens(tokens)), Modifier.padding(top = 4.dp),
                    style = MinaType.Hint, color = c.ink3,
                )
            }
        }
    }
}

/** Budget reduction/addition: Click the small round button to change the budget. */
@Composable
private fun BudgetButton(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val c = MinaTheme.colors
    val tint = if (enabled) c.ink else c.ink3.copy(alpha = 0.4f)
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(17.dp), tint = tint) }
}

private fun timeText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun dateText(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM"))
