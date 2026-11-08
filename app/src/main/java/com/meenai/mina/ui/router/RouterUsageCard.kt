package com.meenai.mina.ui.router

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme

/**
 * Settings usage card:
 * - Each provider's limits, models and status ("har provider ka aaj ka count, limit, cooldown")
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
                if (index > 0) {
                    HorizontalDivider(
                        color = colors.hair,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                ProviderUsageBlock(usage = usage, isFirst = index == 0)
            }

            if (usages.isNotEmpty()) {
                HorizontalDivider(
                    color = colors.hair,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
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
