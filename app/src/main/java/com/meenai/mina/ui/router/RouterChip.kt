package com.meenai.mina.ui.router

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Chip shown at the top of ChatScreen:
 * "Model · provider · bacha quota" (e.g. "Gemini Flash · 38/50 aaj").
 * Updates quietly on model switch.
 */
@Composable
fun RouterChip(
    activeModel: ActiveModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors
    val todayLabel = stringResource(R.string.router_today)
    val text by remember(activeModel, todayLabel) {
        derivedStateOf { formatChipText(activeModel, todayLabel) }
    }

    Box(
        modifier = modifier
            .testTag("router-chip")
            .clip(MinaShapes.Pill)
            .background(colors.surface)
            .border(0.5.dp, colors.hair, MinaShapes.Pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(6.dp)
                    .background(if (activeModel.isPinned) colors.accent else colors.ok, CircleShape)
            )
            Text(
                text = text,
                style = MinaType.Caption.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                ),
                color = colors.ink,
                maxLines = 1,
            )
        }
    }
}
