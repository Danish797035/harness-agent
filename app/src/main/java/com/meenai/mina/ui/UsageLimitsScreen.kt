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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * 设置 › 用量上限。
 *
 * 连着 Gemini 时先读 Gemini 账号自己的用量(和网页 Usage limits 页同一个 GraphQL 查询),
 * 读到就照网页那样显示 Current usage / Weekly limit 和真实重置时间;
 * 读不到(没登录、接口变了、网络不通)就退回本机账本,老实写「这是本 App 的请求数」,
 * 不再拿本机预算冒充 Google 的额度。
 *
 * 服务端用量每 60 秒读一次(Google 自己的页面也不会更快),本机数字在页面开着时每 5 秒跟着账本更新。
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
    // 页面开着时每 5 秒走一次:跨过零点时今天/这周跟着换
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            now = Instant.now()
        }
    }
    val zone = remember { ZoneId.of("Asia/Kolkata") }
    // 装了 Gemini Web 就用它的账号用量;否则只算本机
    val providerId = remember(live) {
        live.firstOrNull { it.kind == ProviderConfig.Kind.GeminiWeb || it.signIn == ProviderStore.GEMINI_WEB_SIGN_IN }?.id
    }
    val usage by produceState(LimitUsage(), version, providerId, now) {
        value = withContext(Dispatchers.Default) { UsageLimitsQuery.report(ledger.snapshot(), providerId, now, zone) }
    }

    var serverUsage by remember(providerId) { mutableStateOf<GeminiUsage?>(null) }
    LaunchedEffect(providerId) {
        if (providerId == null) return@LaunchedEffect
        while (isActive) {
            val fetched = withContext(Dispatchers.IO) {
                val session = store.geminiWebSession(providerId) ?: return@withContext null
                val result = runCatching {
                    GeminiWebClient.fetchUsage(GeminiWebClient.geminiWebHttp, session)
                }.getOrNull() ?: return@withContext null
                if (result.session != session) store.updateGeminiWebSession(providerId, result.session)
                result.usage
            }
            if (fetched != null) serverUsage = fetched
            delay(60_000)
        }
    }

    val server = serverUsage
    val tier = server?.tier?.uppercase().orEmpty()
    val plan = when {
        "ULTRA" in tier -> stringResource(R.string.usage_limits_ultra)
        "PRO" in tier -> stringResource(R.string.usage_limits_pro)
        else -> null
    }

    PageScaffold(stringResource(R.string.usage_limits_title), onBack) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (server != null) {
                plan?.let {
                    PlanChip(it)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.usage_limits_updated), style = MinaType.Hint, color = MinaTheme.colors.ink3)
            } else {
                LiveChip()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usage_limits_updated), style = MinaType.Hint, color = MinaTheme.colors.ink3)
            }
        }
        Text(
            stringResource(
                when {
                    server != null -> R.string.usage_limits_plan_desc
                    providerId != null -> R.string.usage_limits_local_desc
                    else -> R.string.usage_limits_app_desc
                }
            ),
            Modifier.padding(top = 8.dp, bottom = 14.dp),
            style = MinaType.Caption, color = MinaTheme.colors.ink2,
        )

        if (server != null) {
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
        } else {
            val weeklyBudget = budget * 7
            LimitCard(
                icon = MinaIcons.Chart,
                title = stringResource(R.string.usage_limits_today_app),
                share = usage.dailyCalls.toDouble() / budget,
                detail = stringResource(R.string.usage_limits_of, usage.dailyCalls.toInt(), budget),
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

/** Gemini 账号的用量窗口:百分比 + 进度条 + 真实重置时间。 */
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
private fun LiveChip() {
    val c = MinaTheme.colors
    Row(
        Modifier.clip(MinaShapes.Pill).background(c.okSoft).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.ok))
        Text(stringResource(R.string.usage_limits_live), style = MinaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = c.ok)
    }
}

/** 一张上限卡:标题、右边「X% used」、进度条、说明和重置时间。 */
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
            Box(Modifier.fillMaxWidth().height(8.dp).clip(MinaShapes.Pill).background(c.hair)) {
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

/** 预算的减 / 加:圆形小按钮,点一下改一档。 */
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
