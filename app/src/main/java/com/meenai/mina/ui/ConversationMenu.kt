package com.meenai.mina.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.ui.components.GlassBlurSupported
import com.meenai.mina.ui.components.floatShadow
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where to place the long-pressed menu: the top edge of the raised row (perhaps moved over to drop the menu), the top edge of the menu, and whether the menu is on that row. */
internal data class MenuPlacement(val rowTop: Float, val menuTop: Float, val above: Boolean)

/**
 * Long press the menu location. [top] and [bottom] are the upper and lower sides where things can be
 * placed (removing the status bar, navigation bar, and keyboard). Move that row into this range first;
 * if the menu can fit at the bottom, place it below; otherwise, if it can fit on the top, place it on
 * top; if it cannot fit on either side (the keyboard takes up more than half of the screen), move that
 * row up, and connect the menu below it; if it is not enough, move that row to the top.
 */
internal fun placeMenu(rowTop: Float, rowHeight: Float, menuHeight: Float, gap: Float, top: Float, bottom: Float): MenuPlacement {
    val row = rowTop.coerceIn(top, max(top, bottom - rowHeight))
    if (row + rowHeight + gap + menuHeight <= bottom) return MenuPlacement(row, row + rowHeight + gap, above = false)
    if (row - gap - menuHeight >= top) return MenuPlacement(row, row - gap - menuHeight, above = true)
    val moved = max(top, bottom - menuHeight - gap - rowHeight)
    return MenuPlacement(moved, moved + rowHeight + gap, above = false)
}

/**
 * The menu after long-pressing a conversation in the sidebar (see [LongPressMenu]): that line lifts up
 * into a white card, and the menu includes pin/unpin, name change, and red text deletion. [bounds] is
 * the position and size of that line in the window.
 */
@Composable
internal fun ConversationMenu(
    item: DrawerConversation,
    bounds: Rect,
    progress: () -> Float,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MinaTheme.colors
    LongPressMenu(
        bounds, progress, pane = stringResource(R.string.drawer_actions), tag = "drawer-menu",
        entries = listOf(
            MenuEntry(stringResource(if (item.pinned) R.string.unpin else R.string.pin), MinaIcons.Pin, "drawer-menu-pin", onClick = onPin),
            MenuEntry(stringResource(R.string.drawer_rename), MinaIcons.Edit, "drawer-menu-rename", onClick = onRename),
            MenuEntry(stringResource(R.string.confirm_delete), MinaIcons.Trash, "drawer-menu-delete", c.bad, onDelete),
        ),
        onDismiss = onDismiss,
    ) { LiftedRow(item) }
}

/** Long press an item in the menu: text, icon on the right, test label, color (null is the text color). */
internal class MenuEntry(val text: String, val icon: ImageVector, val tag: String, val color: Color? = null, val onClick: () -> Unit)

/**
 * Menu that appears in place after long-pressing something (shared by sidebar conversations and
 * conversation messages): the whole screen behind dims (blurred since Android 12; the content behind is
 * supplied from outside, see [MenuBlur]); the pressed item is "lifted" in place ([lifted], drawn as a
 * slightly enlarged white card), and a narrower white card below (above when there is no room) lists
 * [entries] with text on the left and icons on the right. Tapping outside or pressing Back closes it.
 * [progress] is the appearance progress (0 to 1); the caller decides whether to animate to the end or
 * jump there. It is read only while drawing and laying out, never during recomposition. [bounds] is the
 * position and size of the item in the window: the lifted card has the same width, its height follows
 * the content up to half the screen (a long answer lifts only its beginning, clipped by [lifted]).
 * With [alignEnd] the menu aligns to its right edge (the user's own words sit on the right).
 */
@Composable
internal fun LongPressMenu(
    bounds: Rect,
    progress: () -> Float,
    pane: String,
    tag: String,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
    lifted: @Composable () -> Unit,
) {
    val c = MinaTheme.colors
    val insets = WindowInsets.safeDrawing
    val view = LocalView.current
    val close = stringResource(R.string.collapse)
    // When it can be blurred, the back is already blurry, so just make it darker; when it can't be
    // blurred, just darken the back to suppress it.
    val dim = c.floatShadow.copy(alpha = if (GlassBlurSupported) 0.16f else 0.32f)
    BackHandler(onBack = onDismiss)
    Layout(
        content = {
            lifted()
            MenuCard {
                entries.forEachIndexed { i, e ->
                    if (i > 0) MenuDivider(c.hair)
                    MenuItem(e.text, e.icon, e.tag, e.color ?: c.ink, e.onClick)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
            .drawBehind { drawRect(dim.copy(alpha = dim.alpha * progress())) }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics {
                paneTitle = pane
                isTraversalGroup = true
                dismiss(close) { onDismiss(); true }
            }
            .testTag(tag),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val rowW = bounds.width.roundToInt().coerceIn(0, w)
        val lifted = measurables[0].measure(Constraints(minWidth = rowW, maxWidth = rowW, maxHeight = (h / 2).coerceAtLeast(0)))
        val menuW = min(MenuWidth.roundToPx(), w).coerceAtLeast(0)
        val menu = measurables[1].measure(Constraints(minWidth = menuW, maxWidth = menuW, maxHeight = h))
        layout(w, h) {
            // The position of that line is the window coordinate; this layer does not necessarily start
            // from the upper left corner of the window (when it is not spread to the bottom of the
            // system bar)
            val origin = coordinates?.positionInWindow() ?: Offset.Zero
            val top = max(0f, insets.getTop(this@Layout) - origin.y)
            val bottom = min(h.toFloat(), view.rootView.height - insets.getBottom(this@Layout) - origin.y)
            val at = placeMenu(bounds.top - origin.y, lifted.height.toFloat(), menu.height.toFloat(), MenuGap.toPx(), top, bottom)
            val x = (bounds.left - origin.x).roundToInt()
            val menuX = (if (alignEnd) x + rowW - menuW else x).coerceIn(0, max(0, w - menuW))
            lifted.placeWithLayer(x, at.rowTop.roundToInt()) {
                scaleX = 1f + LiftScale * progress()
                scaleY = scaleX
            }
            menu.placeWithLayer(menuX, at.menuTop.roundToInt()) {
                val p = progress()
                alpha = p
                scaleX = 0.92f + 0.08f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (at.above) 1f else 0f)
            }
        }
    }
}

/** The raised row: the same content as the row in the side column, replaced with a white background, rounded corners, and shadow. */
@Composable
private fun LiftedRow(item: DrawerConversation) {
    val c = MinaTheme.colors
    Box(
        Modifier
            .floatShadow(c, NavShape)
            .background(c.surface, NavShape)
            .clip(NavShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ConversationRowContent(item)
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    val c = MinaTheme.colors
    Column(
        Modifier.floatShadow(c, MinaShapes.Card).clip(MinaShapes.Card)
            .background(c.surface)
            .border(0.5.dp, c.edge, MinaShapes.Card)
            .semantics { popup() },
    ) { content() }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tag: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MinaType.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, null, Modifier.padding(start = 12.dp).size(MinaSize.Icon), tint = color)
    }
}

@Composable
private fun MenuDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/** How much the content behind the menu will be blurred when the menu appears (from Android 12). */
internal val MenuBlur = 14.dp
private val MenuWidth = 220.dp
private val MenuGap = 8.dp
private const val LiftScale = 0.03f
