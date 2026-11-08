package com.meenai.mina.capsule

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme

/**
 * Bottom dismiss target for dragging the bubble to close/dismiss.
 */
@Composable
fun CapsuleDismissZone(
    hovered: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors
    val size by animateDpAsState(if (hovered) 64.dp else 52.dp, label = "dismiss-size")
    val bgColor by animateColorAsState(
        if (hovered) colors.warn else colors.surface.copy(alpha = 0.9f),
        label = "dismiss-bg",
    )
    val iconColor by animateColorAsState(
        if (hovered) colors.surface else colors.ink2,
        label = "dismiss-icon",
    )

    Column(
        modifier = modifier.padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(if (hovered) 16.dp else 8.dp, CircleShape)
                .clip(CircleShape)
                .background(bgColor)
                .border(2.dp, if (hovered) colors.warnSoft else colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MinaIcons.Close,
                contentDescription = stringResource(R.string.capsule_dismiss_zone),
                tint = iconColor,
                modifier = Modifier.size(if (hovered) 28.dp else 22.dp),
            )
        }

        if (hovered) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.capsule_dismiss_zone),
                color = colors.warn,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
