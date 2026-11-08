package com.meenai.mina.capsule.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R

/**
 * Screen border overlay displayed during live screen share.
 * Active: perimeter red border.
 * Paused (on sensitive/blocked app): amber/orange border.
 */
@Composable
fun LiveShareBorderOverlay(state: LiveShareState) {
    val borderColor = when (state.status) {
        LiveShareStatus.Paused -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Top edge
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .padding(0.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                border = BorderStroke(4.dp, borderColor),
            ) {}
        }
    }
}

/**
 * Top floating pill showing active live share status and a direct Stop button.
 */
@Composable
fun LiveShareStopOverlay(
    state: LiveShareState,
    onStop: () -> Unit,
) {
    val isPaused = state.status == LiveShareStatus.Paused
    val isDark = isSystemInDarkTheme()
    val bg = if (isDark) Color(0xEE212121) else Color(0xEEFAFAFA)
    val dotColor = if (isPaused) Color(0xFFFFA000) else Color(0xFFE53935)
    val label = if (isPaused) {
        stringResource(R.string.live_share_paused_indicator)
    } else {
        stringResource(R.string.capsule_menu_live_share)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.5f)),
        shadowElevation = 8.dp,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else Color.Black,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFFE53935), RoundedCornerShape(12.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.live_share_stop),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}
