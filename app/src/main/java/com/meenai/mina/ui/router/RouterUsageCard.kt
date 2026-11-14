package com.meenai.mina.ui.router

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.components.AiLogo
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Settings usage card:
 * - Upar bada card (kaunsa model, kyun chuna, AI logo, provider chip, quota rang)
 * - Neeche card-style list (rank badge, AI logo, provider chip, hara/peela/laal quota)
 * - Toggle for switch notice ("Switch notice dikhao")
 */
@Composable
fun RouterUsageCard(
    usages: List<ProviderUsage>,
    showSwitchNotice: Boolean,
    onToggleSwitchNotice: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activeModel: ActiveModel? = null,
    onPinClick: (() -> Unit)? = null,
    onSelectModel: ((String) -> Unit)? = null,
) {
    val colors = MinaTheme.colors
    val routerStatus = LocalRouterStatus.current
    val currentActive by routerStatus.current.collectAsState()
    val active = activeModel ?: currentActive

    val allModels = remember(usages) {
        val list = usages.flatMap { it.models }
        if (list.isNotEmpty()) list else emptyList()
    }

    Column(modifier = modifier) {
        GroupLabel(stringResource(R.string.router_usage_card_title))

        // 1. Upar Bada Card: Kaunsa model, kyun chuna, AI logo, quota rang
        ActiveModelHeroCard(
            active = active,
            onPinClick = onPinClick ?: {
                routerStatus.pinModel(if (active.isPinned) null else active.id)
            },
        )

        Spacer(Modifier.height(14.dp))

        // 2. Neeche Card-Style List: rank badge, provider chip, quota rang (hara/peela/laal)
        if (allModels.isNotEmpty()) {
            GroupLabel(stringResource(R.string.router_models_list_title))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allModels.forEachIndexed { index, model ->
                    ModelCardItem(
                        model = model,
                        rank = model.rank ?: (index + 1),
                        isPinned = active.isPinned && active.id == model.modelId,
                        onClick = {
                            if (model.quotaState != QuotaState.Exhausted) {
                                if (onSelectModel != null) {
                                    onSelectModel(model.modelId)
                                } else {
                                    routerStatus.pinModel(model.modelId)
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 3. Switch Notice Toggle & Provider Quota Summary
        GroupCard {
            if (allModels.isEmpty() && usages.isNotEmpty()) {
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
                HorizontalDivider(
                    color = colors.hair,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }

            ListRow(
                icon = MinaIcons.Bell,
                title = stringResource(R.string.router_switch_notice_title),
                subtitle = stringResource(R.string.router_switch_notice_sub),
                first = allModels.isNotEmpty(),
            ) {
                MinaSwitch(
                    checked = showSwitchNotice,
                    onCheckedChange = onToggleSwitchNotice,
                    modifier = Modifier.testTag("switch-notice-toggle"),
                )
            }
        }
    }
}

/**
 * Upar Bada Card: Kaunsa model hai, kyun chuna gaya, official AI logo aur quota rang ke saath.
 */
@Composable
fun ActiveModelHeroCard(
    active: ActiveModel,
    onPinClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors
    val hasModel = active.id.isNotBlank() && active.name != "No model"

    // Quota rang (hara / peela / laal)
    val (quotaBg, quotaFg, quotaLabel) = when {
        !hasModel -> Triple(colors.ink3.copy(alpha = 0.1f), colors.ink3, "None")
        active.cooldownUntilMs > System.currentTimeMillis() -> Triple(colors.bad.copy(alpha = 0.12f), colors.bad, "Cooldown")
        active.limit != null && active.usedToday >= active.limit -> Triple(colors.bad.copy(alpha = 0.12f), colors.bad, "Exhausted")
        active.limit != null && (active.limit - active.usedToday) in 1..5 -> Triple(colors.warn.copy(alpha = 0.12f), colors.warn, "Low")
        else -> Triple(colors.ok.copy(alpha = 0.12f), colors.ok, "Ready")
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .testTag("active-model-hero-card"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Header Row: Subtitle + Pinned Badge + Call Status Dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Call Status Dot
                    val dotColor = when (active.callStatus) {
                        ModelCallStatus.Success -> colors.ok
                        ModelCallStatus.Failure -> colors.bad
                        ModelCallStatus.Untested -> colors.ink3
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.router_active_card_title),
                        style = MinaType.Hint.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.ink3,
                    )
                }

                if (active.isPinned) {
                    Box(
                        modifier = Modifier
                            .clip(MinaShapes.Pill)
                            .background(colors.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.router_model_pinned),
                            style = MinaType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Main Row: AI Office Logo + Model Name + Provider Chip + Quota Rang Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Official AI Logo
                AiLogo(
                    providerOrModel = active.id.ifBlank { active.name },
                    size = 44.dp,
                    corner = 12.dp,
                )

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (hasModel) active.name else stringResource(R.string.settings_not_set),
                        style = MinaType.Title.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Provider Chip
                        if (active.provider.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(MinaShapes.Pill)
                                    .background(colors.hair.copy(alpha = 0.5f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = active.provider,
                                    style = MinaType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                    color = colors.ink2,
                                )
                            }
                        }

                        // Quota Color Pill (Hara / Peela / Laal)
                        Box(
                            modifier = Modifier
                                .clip(MinaShapes.Pill)
                                .background(quotaBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = quotaLabel,
                                style = MinaType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = quotaFg,
                            )
                        }
                    }
                }
            }

            // "Kyun chuna" section (Why chosen)
            if (active.reason.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.hair.copy(alpha = 0.25f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = MinaIcons.Spark,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.router_why_chosen),
                                style = MinaType.Hint.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = colors.ink3,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = active.reason,
                            style = MinaType.Caption.copy(fontSize = 12.5.sp),
                            color = colors.ink2,
                        )
                    }
                }
            }

            // Quota requests counter & Pin action button
            if (hasModel) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val requestsText = if (active.limit != null) {
                        "${active.usedToday}/${active.limit} ${stringResource(R.string.router_today)}"
                    } else if (active.isEstimate) {
                        "${active.usedToday} ${stringResource(R.string.router_today)} (${stringResource(R.string.router_estimate)})"
                    } else {
                        "${active.usedToday} ${stringResource(R.string.router_today)}"
                    }

                    Text(
                        text = requestsText,
                        style = MinaType.Hint.copy(fontSize = 12.sp),
                        color = colors.ink3,
                    )

                    if (active.canPin) {
                        MinaButton(
                            text = if (active.isPinned) stringResource(R.string.router_unpin_model) else stringResource(R.string.router_pin_model),
                            onClick = onPinClick,
                            kind = if (active.isPinned) ButtonKind.Tonal else ButtonKind.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Neeche Card-Style List Item:
 * - Rank Badge (#1, #2, etc.)
 * - Official AI Logo
 * - Provider Chip
 * - Quota Rang (Hara = Ready, Peela = Low, Laal = Exhausted/Cooldown)
 */
@Composable
fun ModelCardItem(
    model: ModelQuotaLine,
    rank: Int,
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors

    // Quota status colors (Hara / Peela / Laal)
    val (quotaBg, quotaFg, quotaText) = when (model.quotaState) {
        QuotaState.Ready -> Triple(
            colors.ok.copy(alpha = 0.12f),
            colors.ok,
            stringResource(R.string.router_quota_ready),
        )
        QuotaState.Low -> Triple(
            colors.warn.copy(alpha = 0.14f),
            colors.warn,
            stringResource(R.string.router_quota_low),
        )
        QuotaState.Exhausted -> {
            val label = if (model.cooldownMinutes > 0) {
                stringResource(R.string.router_status_cooldown, model.cooldownMinutes)
            } else if (model.status == ModelStatusKind.BadKey) {
                stringResource(R.string.router_status_bad_key)
            } else {
                stringResource(R.string.router_quota_exhausted)
            }
            Triple(colors.bad.copy(alpha = 0.12f), colors.bad, label)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, if (isPinned) colors.accent else colors.hair, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag("model-card-item-${model.modelId}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Rank Badge (#1, #2, #3...)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (rank <= 3) colors.accent else colors.hair.copy(alpha = 0.8f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "#$rank",
                    style = MinaType.Caption.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (rank <= 3) Color.White else colors.ink2,
                )
            }

            Spacer(Modifier.width(8.dp))

            // 2. Official AI Logo
            AiLogo(
                providerOrModel = model.modelId,
                size = 32.dp,
                corner = 8.dp,
            )

            Spacer(Modifier.width(10.dp))

            // 3. Model Info Column
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.modelName.ifBlank { model.modelId },
                        style = MinaType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isPinned) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(MinaShapes.Pill)
                                .background(colors.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.router_model_pinned),
                                style = MinaType.Caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                                color = colors.accent,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Provider chip + Requests / Cost
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (model.provider.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(MinaShapes.Pill)
                                .background(colors.hair.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = model.provider,
                                style = MinaType.Caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                                color = colors.ink2,
                            )
                        }
                    }

                    val countInfo = if (model.limit != null) {
                        "${model.todayRequests}/${model.limit}"
                    } else if (model.isEstimate) {
                        "${model.todayRequests} (${stringResource(R.string.router_estimate)})"
                    } else {
                        "${model.todayRequests}"
                    }

                    Text(
                        text = if (model.costPerCall != null) {
                            val formattedCost = if (model.costPerCall % 1.0 == 0.0) {
                                model.costPerCall.toInt().toString()
                            } else {
                                String.format(java.util.Locale.US, "%.1f", model.costPerCall)
                            }
                            "$countInfo · ~$formattedCost Magicube"
                        } else {
                            countInfo
                        },
                        style = MinaType.Hint.copy(fontSize = 11.5.sp),
                        color = colors.ink3,
                    )
                }

                // Capability chips (Free, Vision, Tools, Context)
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapabilityBadge(
                        text = if (model.isFree) stringResource(R.string.router_badge_free) else stringResource(R.string.router_badge_paid),
                        color = if (model.isFree) colors.ok else colors.ink3,
                    )
                    if (model.supportsVision) {
                        CapabilityBadge(
                            text = stringResource(R.string.router_badge_vision),
                            color = colors.accent,
                        )
                    }
                    if (model.supportsTools) {
                        CapabilityBadge(
                            text = stringResource(R.string.router_badge_tools),
                            color = colors.accent,
                        )
                    }
                    if (model.contextTokens >= ModelPickerLogic.LONG_CONTEXT_THRESHOLD) {
                        CapabilityBadge(
                            text = ModelPickerLogic.formatContextTokens(model.contextTokens),
                            color = colors.ink2,
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 4. Quota Rang Pill (Hara / Peela / Laal)
            Box(
                modifier = Modifier
                    .clip(MinaShapes.Pill)
                    .background(quotaBg)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = quotaText,
                    style = MinaType.Caption.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                    ),
                    color = quotaFg,
                )
            }
        }
    }
}
