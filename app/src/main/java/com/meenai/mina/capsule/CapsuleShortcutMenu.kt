package com.meenai.mina.capsule

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme

/**
 * 8 shortcut items for the long-press shortcut menu on the floating capsule bubble:
 * 1. Live share
 * 2. Ask about screen
 * 3. Circle to ask
 * 4. Voice
 * 5. Translate screen to Hindi
 * 6. Page summary
 * 7. Copy text from screen
 * 8. Stop task
 */
enum class CapsuleShortcut(
    val id: String,
    val titleRes: Int,
) {
    LiveShare("live_share", R.string.capsule_menu_live_share),
    AskAboutScreen("ask_about_screen", R.string.capsule_menu_ask_about_screen),
    CircleToAsk("circle_to_ask", R.string.capsule_menu_circle_to_ask),
    ActHere("act_here", R.string.capsule_menu_act_here),
    Voice("voice", R.string.capsule_menu_voice),
    TranslateHindi("translate_hindi", R.string.capsule_menu_translate_hindi),
    PageSummary("page_summary", R.string.capsule_menu_page_summary),
    CopyText("copy_text", R.string.capsule_menu_copy_text),
    StopTask("stop_task", R.string.capsule_menu_stop_task);

    companion object {
        fun allShortcuts(): List<CapsuleShortcut> = entries.toList()
    }

    fun icon(): ImageVector = when (this) {
        LiveShare -> MinaIcons.Globe
        AskAboutScreen -> MinaIcons.Camera
        CircleToAsk -> MinaIcons.Spark
        ActHere -> MinaIcons.Hand
        Voice -> MinaIcons.Mic
        TranslateHindi -> MinaIcons.Doc
        PageSummary -> MinaIcons.Search
        CopyText -> MinaIcons.Edit
        StopTask -> MinaIcons.Stop
    }
}

@Composable
fun CapsuleShortcutMenuOverlay(
    onSelect: (CapsuleShortcut) -> Unit,
    onDismiss: () -> Unit,
    hasActiveTask: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MinaTheme.colors
    Surface(
        modifier = modifier
            .widthIn(min = 260.dp, max = 320.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = colors.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.capsule_menu_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MinaIcons.Close,
                        contentDescription = stringResource(R.string.capsule_menu_close),
                        tint = colors.ink3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 8 Menu items
            CapsuleShortcut.allShortcuts().forEach { item ->
                val isStop = item == CapsuleShortcut.StopTask
                val enabled = !isStop || hasActiveTask

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) {
                            onSelect(item)
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (isStop) (if (hasActiveTask) colors.warnSoft else colors.surface)
                                else colors.accentSoft
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon(),
                            contentDescription = null,
                            tint = if (isStop) {
                                if (hasActiveTask) colors.warn else colors.ink3
                            } else colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = stringResource(item.titleRes),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isStop) {
                            if (hasActiveTask) colors.warn else colors.ink3
                        } else colors.ink,
                    )
                }
            }
        }
    }
}
