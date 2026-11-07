package com.meenai.mina.ui.router

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Settings usage card:
 * - Each provider's today count, last error, cooldown ("har provider ka aaj ka count, last error, cooldown")
 * - Toggle for switch notice ("Switch notice dikhao")
 */
@Composable
fun RouterUsageCard(
    usages: List<ProviderUsage>,
    showSwitchNotice: Boolean,
    onToggleSwitchNotice: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors

    Column(modifier = modifier) {
        GroupLabel(stringResource(R.string.router_usage_card_title))
        GroupCard {
            usages.forEachIndexed { index, usage ->
                ProviderUsageDetailRow(usage = usage, isFirst = index == 0)
            }

            // Toggle for "Switch notice dikhao"
            ListRow(
                icon = MinaIcons.Bell,
                title = stringResource(R.string.router_switch_notice_title),
                subtitle = stringResource(R.string.router_switch_notice_sub),
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

@Composable
private fun ProviderUsageDetailRow(
    usage: ProviderUsage,
    isFirst: Boolean,
) {
    val colors = MinaTheme.colors
    val now = remember { System.currentTimeMillis() }
    val isCooldown = usage.cooldownUntilMs > now
    val cooldownSeconds by remember(usage.cooldownUntilMs, now) {
        derivedStateOf {
            if (isCooldown) ((usage.cooldownUntilMs - now) / 1000L).coerceAtLeast(1L) else 0L
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = usage.provider,
                style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )

            val cooldownText = if (isCooldown) {
                stringResource(R.string.router_cooldown_active, "${cooldownSeconds}s")
            } else {
                stringResource(R.string.router_no_cooldown)
            }
            Text(
                text = cooldownText,
                style = MinaType.Caption.copy(fontWeight = FontWeight.Medium),
                color = if (isCooldown) colors.warn else colors.ok,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.router_today_count, usage.todayCount),
                style = MinaType.Hint,
                color = colors.ink2,
            )

            val errorText = usage.lastError?.let {
                stringResource(R.string.router_last_error, it)
            } ?: stringResource(R.string.router_no_error)
            Text(
                text = errorText,
                style = MinaType.Hint,
                color = if (usage.lastError != null) colors.bad else colors.ink3,
            )
        }
    }
}
