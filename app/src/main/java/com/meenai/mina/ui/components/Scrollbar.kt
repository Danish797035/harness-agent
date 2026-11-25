package com.meenai.mina.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.delay

/**
 * Draw a thin scroll bar for the vertical lazy loading list: stick it on the right, the length reflects
 * how much of the visible part occupies the entire list, and the position reflects where to scroll. It
 * will not be drawn when the list can fit; it will appear when scrolling and when the content just
 * changes, and fade out after stopping for a while. Draw on the content and do not occupy the layout.
 * Press and hold to estimate the average height of the displayed items. It will be accurate enough when
 * the height differences between the items are not large.
 */
@Composable
fun Modifier.lazyListScrollbar(
    state: LazyListState,
    color: Color = MinaTheme.colors.ink3,
    width: Dp = 3.dp,
    edge: Dp = 2.dp,
): Modifier {
    val scrolling = state.isScrollInProgress
    val count = state.layoutInfo.totalItemsCount
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(scrolling, count) {
        if (scrolling) {
            shown = true
        } else {
            delay(IDLE_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) THUMB_ALPHA else 0f, tween(if (shown) 120 else 400), label = "scrollbar")
    return drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return@drawWithContent
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val average = items.sumOf { it.size }.toFloat() / items.size
        val total = average * info.totalItemsCount
        if (viewport <= 0f || total <= viewport + 1f) return@drawWithContent
        val w = width.toPx()
        val thumb = (viewport * viewport / total).coerceAtLeast(MIN_THUMB.toPx()).coerceAtMost(viewport)
        val scrolled = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
        val y = (scrolled / (total - viewport)).coerceIn(0f, 1f) * (viewport - thumb)
        drawRoundRect(
            color, Offset(size.width - w - edge.toPx(), y), Size(w, thumb), CornerRadius(w / 2), alpha = alpha,
        )
    }
}

private const val IDLE_MS = 900L
private const val THUMB_ALPHA = 0.55f
private val MIN_THUMB = 24.dp
