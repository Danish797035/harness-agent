package com.meenai.mina.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaTheme

/** The status of the previous point in the vertical line step: completed is a gray point, current is a blue point with aperture, pause is an orange point, and error is a red point. */
enum class StepState { Done, Current, Paused, Failed }

private fun StepState.color(c: MinaColors) = when (this) {
    StepState.Done -> c.ink3
    StepState.Current -> c.accent
    StepState.Paused -> c.warnSolid
    StepState.Failed -> c.bad
}

private fun StepState.halo(c: MinaColors) = when (this) {
    StepState.Done -> null
    StepState.Current -> c.accentSoft
    StepState.Paused -> c.warnSoft
    StepState.Failed -> c.badSoft
}

/**
 * One step in the vertical line step: a thin line of 1.5dp on the left goes through each step, and the
 * dots are drawn at the height of [dotY] (aligned with the center line of the first line of text); the
 * line of the first step starts from the dot, and the line of the last step ends at the dot. The lines
 * between steps are continuous. The content is placed on the right and can have multiple lines.
 */
@Composable
fun StepItem(
    state: StepState,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    dotY: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MinaTheme.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(GutterWidth).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val y = dotY.toPx()
                val top = if (first) y else 0f
                val bottom = if (last) y else size.height
                drawLine(c.hair, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
                state.halo(c)?.let { drawCircle(it, 7.dp.toPx(), Offset(x, y)) }
                drawCircle(state.color(c), 4.dp.toPx(), Offset(x, y))
            },
        )
        Column(Modifier.weight(1f), content = content)
    }
}

private val GutterWidth = 20.dp
