package com.meenai.mina.ui.router

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.delay

/**
 * 3-second non-blocking toast/snackbar shown upon model switch.
 * e.g. "Gemini limit khatam, Llama (OpenRouter) par switch".
 * Suppressed when [showNotice] is false.
 */
@Composable
fun SwitchNoticeToast(
    event: SwitchEvent?,
    showNotice: Boolean = true,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var visible by remember(event) { mutableStateOf(event != null && showNotice) }

    LaunchedEffect(event, showNotice) {
        if (event != null && showNotice) {
            visible = true
            delay(3000L)
            visible = false
            onDismiss()
        } else {
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && event != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier.testTag("switch-notice-toast"),
    ) {
        if (event != null) {
            val colors = MinaTheme.colors
            val target = if (event.toProvider.isNotBlank()) "${event.toModel} (${event.toProvider})" else event.toModel
            val message = stringResource(R.string.router_switch_toast_format, event.fromModel, target)

            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(MinaShapes.Pill)
                    .background(colors.surface)
                    .border(0.5.dp, colors.hair, MinaShapes.Pill)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(7.dp)
                            .background(colors.warn, CircleShape)
                    )
                    Text(
                        text = message,
                        style = MinaType.Caption.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        ),
                        color = colors.ink,
                    )
                }
            }
        }
    }
}
