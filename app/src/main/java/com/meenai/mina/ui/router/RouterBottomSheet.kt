package com.meenai.mina.ui.router

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Bottom sheet opened upon tapping the router chip:
 * - Current model & why chosen ("abhi ka model, kyun chuna")
 * - Each provider's today quota & cooldown ("har provider ka aaj ka quota, cooldown kab tak")
 * - "Pin this model" button (disabled when no candidates available)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterBottomSheet(
    activeModel: ActiveModel,
    usages: List<ProviderUsage>,
    onPinClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    val colors = MinaTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        onRefresh?.invoke()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier.testTag("router-bottom-sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.router_sheet_title),
                style = MinaType.Title,
                color = colors.ink,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Current Model & Selection Reason
            GroupCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.router_current_model),
                        style = MinaType.Hint,
                        color = colors.ink3,
                    )
                    Text(
                        text = if (activeModel.provider.isNotBlank()) "${activeModel.name} · ${activeModel.provider}" else activeModel.name,
                        style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )

                    if (activeModel.reason.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.router_why_chosen),
                            style = MinaType.Hint,
                            color = colors.ink3,
                        )
                        Text(
                            text = activeModel.reason,
                            style = MinaType.Caption,
                            color = colors.ink2,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Providers Quota & Limits
            GroupLabel(stringResource(R.string.router_provider_quotas))
            GroupCard {
                usages.forEachIndexed { index, usage ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = colors.hair,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                    ProviderUsageBlock(usage = usage, isFirst = index == 0)
                }
            }

            Spacer(Modifier.height(18.dp))

            // "Pin this model" button
            val pinText = if (activeModel.isPinned) {
                stringResource(R.string.router_model_pinned)
            } else {
                stringResource(R.string.router_pin_model)
            }

            MinaButton(
                text = pinText,
                onClick = onPinClick,
                kind = if (activeModel.isPinned) ButtonKind.Tonal else ButtonKind.Primary,
                size = ButtonSize.Large,
                enabled = activeModel.canPin,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pin-model-button"),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun ProviderUsageBlock(
    usage: ProviderUsage,
    isFirst: Boolean,
) {
    val colors = MinaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Provider Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = usage.provider,
                    style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                )
                if (usage.isBadKey) {
                    Text(
                        text = " · ${stringResource(R.string.router_status_bad_key)}",
                        style = MinaType.Caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.bad,
                    )
                }
            }

            val metaTexts = mutableListOf<String>()
            if (usage.concurrency != null) {
                metaTexts.add(stringResource(R.string.router_concurrency, usage.concurrency))
            }
            if (usage.totalCostToday != null) {
                val formattedCost = if (usage.totalCostToday % 1.0 == 0.0) {
                    usage.totalCostToday.toInt().toString()
                } else {
                    String.format(java.util.Locale.US, "%.1f", usage.totalCostToday)
                }
                metaTexts.add(stringResource(R.string.router_magicube_total, formattedCost))
            }
            if (metaTexts.isNotEmpty()) {
                Text(
                    text = metaTexts.joinToString(" · "),
                    style = MinaType.Hint,
                    color = colors.ink3,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (usage.models.isNotEmpty()) {
            usage.models.forEachIndexed { index, model ->
                if (index > 0) {
                    HorizontalDivider(
                        color = colors.hair,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                ModelQuotaRow(model = model)
            }
        } else {
            LegacyProviderUsageRow(usage = usage)
        }
    }
}

@Composable
fun ModelQuotaRow(
    model: ModelQuotaLine,
) {
    val colors = MinaTheme.colors
    val estimateLabel = stringResource(R.string.router_estimate)

    val requestsText = if (model.limit != null) {
        "${model.todayRequests}/${model.limit}"
    } else if (model.isEstimate) {
        "${model.todayRequests} ($estimateLabel)"
    } else {
        "${model.todayRequests}"
    }

    val subtitle = if (model.costPerCall != null) {
        val formattedCost = if (model.costPerCall % 1.0 == 0.0) {
            model.costPerCall.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", model.costPerCall)
        }
        "$requestsText · " + stringResource(R.string.router_magicube_cost, formattedCost)
    } else {
        requestsText
    }

    val (statusText, statusColor) = when (model.status) {
        ModelStatusKind.Ready -> stringResource(R.string.router_status_ready) to colors.ok
        ModelStatusKind.Cooldown -> stringResource(R.string.router_status_cooldown, model.cooldownMinutes) to colors.warn
        ModelStatusKind.Unsupported -> stringResource(R.string.router_status_unsupported) to colors.ink3
        ModelStatusKind.BadKey -> stringResource(R.string.router_status_bad_key) to colors.bad
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = model.modelName.ifBlank { model.modelId },
                style = MinaType.Body.copy(fontSize = 14.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MinaType.Hint.copy(fontSize = 12.sp),
                color = colors.ink3,
            )
        }

        Box(
            modifier = Modifier
                .clip(MinaShapes.Pill)
                .background(statusColor.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = statusText,
                style = MinaType.Caption.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                color = statusColor,
            )
        }
    }
}

@Composable
private fun LegacyProviderUsageRow(
    usage: ProviderUsage,
) {
    val colors = MinaTheme.colors
    val now = remember { System.currentTimeMillis() }
    val isCooldown = usage.cooldownUntilMs > now
    val cooldownSeconds by remember(usage.cooldownUntilMs, now) {
        derivedStateOf {
            if (isCooldown) ((usage.cooldownUntilMs - now) / 1000L).coerceAtLeast(1L) else 0L
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val quotaText = if (usage.totalQuota > 0) {
            stringResource(R.string.router_quota_format, usage.remainingQuota, usage.totalQuota)
        } else {
            stringResource(R.string.router_today_count, usage.todayCount)
        }
        Text(
            text = quotaText,
            style = MinaType.Hint,
            color = colors.ink3,
        )

        val cooldownText = if (isCooldown) {
            stringResource(R.string.router_cooldown_active, "${cooldownSeconds}s")
        } else {
            stringResource(R.string.router_no_cooldown)
        }
        val cooldownColor = if (isCooldown) colors.warn else colors.ok

        Text(
            text = cooldownText,
            style = MinaType.Caption.copy(fontWeight = FontWeight.Medium),
            color = cooldownColor,
        )
    }
}
