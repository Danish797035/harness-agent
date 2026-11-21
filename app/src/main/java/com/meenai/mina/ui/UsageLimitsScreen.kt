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
import com.meenai.mina.core.usage.LimitUsage
import com.meenai.mina.core.usage.UsageLedger
import com.meenai.mina.core.usage.UsageLimitsQuery
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.Provider
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 设置 › 用量上限:今天和最近 7 天的请求数,按用户自己设的每日预算画进度条。
 * 数字来自本机用量账本,账本一记新的一笔就重算;页面开着时每 5 秒也重算一次(跨天、看时间)。
 * Gemini 服务端自己的额度比例不对外提供,所以这里画的是「你的预算用掉了多少」。
 */
@Composable
fun UsageLimitsScreen(
    ledger: UsageLedger,
    providers: StateFlow<List<Provider>>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    val version by ledger.version.collectAsState()
    val live by providers.collectAsState()
    var budget by remember { mutableIntStateOf(flags.usageDailyBudget()) }
    var now by remember { mutableStateOf(Instant.now()) }
    // 页面开着时每 5 秒走一次:跨过零点时今天/这周跟着换,「刚刚更新」也一直是新的
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            now = Instant.now()
        }
    }
    val zone = remember { ZoneId.of("Asia/Kolkata") }
    // 装了 Gemini Web 就只算这一家的请求,否则算全部
    val providerId = remember(live) {
        live.firstOrNull { it.kind == ProviderConfig.Kind.GeminiWeb || it.signIn == ProviderStore.GEMINI_WEB_SIGN_IN }?.id
    }
    val usage by produceState(LimitUsage(), version, providerId, now) {
        value = withContext(Dispatchers.Default) { UsageLimitsQuery.report(ledger.snapshot(), providerId, now, zone) }
    }
    val weeklyBudget = budget * 7
    val dailyShare = usage.dailyCalls.toDouble() / budget
    val weeklyShare = usage.weeklyCalls.toDouble() / weeklyBudget

    PageScaffold(stringResource(R.string.usage_limits_title), onBack) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 连着 Gemini 时和 Gemini 自己的页面一样标一个 PRO
            if (providerId != null) {
                PlanChip()
                Spacer(Modifier.width(6.dp))
            }
            LiveChip()
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.usage_limits_updated), style = MinaType.Hint, color = MinaTheme.colors.ink3)
        }
        Text(
            stringResource(R.string.usage_limits_desc), Modifier.padding(top = 8.dp, bottom = 14.dp),
            style = MinaType.Caption, color = MinaTheme.colors.ink2,
        )

        LimitCard(
            icon = MinaIcons.Chart,
            title = stringResource(R.string.usage_limits_current),
            share = dailyShare,
            detail = stringResource(R.string.usage_limits_of, usage.dailyCalls.toInt(), budget),
            reset = stringResource(R.string.usage_limits_resets_daily),
            tokens = usage.dailyTokens,
            tag = "usage-limits-daily",
        )
        LimitCard(
            icon = MinaIcons.Clock,
            title = stringResource(R.string.usage_limits_weekly),
            share = weeklyShare,
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

@Composable
private fun PlanChip() {
    val c = MinaTheme.colors
    Text(
        stringResource(R.string.usage_limits_pro),
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

/** 一张上限卡:标题、右边「X% used」、进度条、这一段的请求数和重置时间。 */
@Composable
private fun LimitCard(
    icon: ImageVector,
    title: String,
    share: Double,
    detail: String,
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
                Text(detail, Modifier.weight(1f), style = MinaType.Hint, color = c.ink2)
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
