package com.meenai.mina.capsule.acthere

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme

@Composable
fun ActHerePromptOverlay(
    onStart: (goal: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors
    var prompt by remember { mutableStateOf("") }

    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = colors.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MinaIcons.Hand,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.capsule_menu_act_here),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancel)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MinaIcons.Close,
                        contentDescription = stringResource(R.string.act_here_cancel),
                        tint = colors.ink3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Input field
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.act_here_prompt_hint),
                        fontSize = 14.sp,
                        color = colors.ink3,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(16.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.act_here_cancel),
                        color = colors.ink,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = prompt.trim()
                        if (trimmed.isNotEmpty()) {
                            onStart(trimmed)
                        }
                    },
                    enabled = prompt.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.act_here_start),
                    )
                }
            }
        }
    }
}

@Composable
fun ActHereConfirmationOverlay(
    actionLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors

    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = colors.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.act_here_confirm_title, actionLabel),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.act_here_cancel),
                        color = colors.ink,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.bad,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.act_here_confirm),
                    )
                }
            }
        }
    }
}

@Composable
fun ActHereTapHighlightOverlay(
    x: Float,
    y: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val colors = MinaTheme.colors

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(x, y)
        val radiusPx = 28f * density
        drawCircle(
            color = colors.accent.copy(alpha = 0.35f),
            radius = radiusPx,
            center = center,
        )
        drawCircle(
            color = colors.accent.copy(alpha = 0.8f),
            radius = radiusPx,
            center = center,
            style = Stroke(width = 3f * density),
        )
    }
}

@Composable
fun ActHereControlBar(
    onStop: () -> Unit,
    onTakeover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors
    Surface(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp)),
        color = colors.surface,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = colors.bad),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = MinaIcons.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.capsule_stop),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            OutlinedButton(
                onClick = onTakeover,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = MinaIcons.Hand,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.ink,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.capsule_you_handle),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                )
            }
        }
    }
}

@Composable
fun ActHereMaxStepsOverlay(
    onContinue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors

    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = colors.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.act_here_max_steps),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.capsule_stop),
                        color = colors.ink,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.act_here_continue),
                    )
                }
            }
        }
    }
}
