package com.meenai.mina.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import androidx.compose.runtime.getValue

// ---------- switch ----------

/**
 * Switch: 44×26, emphasizes blue when turned on. The animation is decelerated by 200ms, and it will go
 * directly to the position when the system turns off the animation. [onCheckedChange] is only displayed
 * when it is empty (used when the entire line can be clicked).
 */
@Composable
fun HarnessSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MinaTheme.colors
    val reduce = MinaTheme.reduceMotion
    val position by animateFloatAsState(if (checked) 1f else 0f, MinaMotion.switch(reduce), label = "switch")
    val track by animateColorAsState(if (checked) c.accent else c.greySoft, MinaMotion.switch(reduce), label = "switch-track")
    val thumb = c.thumb
    val ring = c.floatShadow.copy(alpha = 0.12f)
    Box(
        modifier
            .then(
                if (onCheckedChange != null) Modifier.minimumInteractiveComponentSize()
                    .toggleable(checked, enabled = enabled, role = Role.Switch, interactionSource = null, indication = null, onValueChange = onCheckedChange)
                else Modifier,
            )
            .size(44.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                val r = 11.dp.toPx()
                val inset = 2.dp.toPx()
                val cx = inset + r + (size.width - 2 * (inset + r)) * position
                val center = Offset(cx, size.height / 2)
                drawCircle(ring, r + 0.6.dp.toPx(), center.copy(y = center.y + 0.5.dp.toPx()))
                drawCircle(thumb, r, center)
            },
    )
}

@Composable
fun MinaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = HarnessSwitch(checked, onCheckedChange, modifier, enabled)

// ---------- Capsules ----------

enum class PillTone { Ok, Warn, Neutral, Accent }

/** Status capsule: available, needed, deactivated; the accent color indicates "in use" (for example, which purpose the model is working for). */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val (bg, fg) = when (tone) {
        PillTone.Ok -> c.okSoft to c.ok
        PillTone.Warn -> c.warnSoft to c.warn
        PillTone.Accent -> c.accentSoft to c.accent
        PillTone.Neutral -> c.greySoft to c.ink2
    }
    Text(
        text, modifier.background(bg, MinaShapes.Pill).padding(horizontal = 9.dp, vertical = 3.dp),
        style = PillText, color = fg, maxLines = 1,
    )
}

private val PillText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)

// ---------- Button ----------

/** Primary button (emphasis on solid blue background), light secondary button (blue text on white background), light fill button, and text button. */
enum class ButtonKind { Primary, Secondary, Tonal, Text }

/** Large: 52 at the bottom of the page; Medium: 42 side by side in the card; Small: 32 capsules in the row. */
enum class ButtonSize(val height: Dp, val shape: RoundedCornerShape, val text: TextStyle, val padding: Dp) {
    Large(MinaSize.Button, MinaShapes.Button, TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), 20.dp),
    Medium(42.dp, RoundedCornerShape(12.dp), TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold), 14.dp),
    Small(32.dp, RoundedCornerShape(16.dp), TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), 14.dp),
}

/** button. The width is determined by [modifier] (fill, divide by weight or wrap text). */
@Composable
fun HarnessButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
) {
    val c = MinaTheme.colors
    val shape = size.shape
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.accent to c.onAccent
        ButtonKind.Secondary -> c.surface to c.accent
        ButtonKind.Tonal -> c.fill to c.ink
        ButtonKind.Text -> Color.Transparent to c.accent
    }
    Box(
        modifier
            .then(if (kind == ButtonKind.Secondary) Modifier.cardSurface(c, shape) else Modifier.background(bg, shape))
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .height(size.height)
            .padding(horizontal = size.padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = size.text, color = fg, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MinaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
) = HarnessButton(text, onClick, modifier, kind, size, enabled)

// ---------- Segmented Control ----------

/** Segmented control: shallow filled bottom, selected section is white floated. [tags] is the test label of each segment. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String>? = null,
) {
    val c = MinaTheme.colors
    Row(modifier.fillMaxWidth().background(c.fill, SegmentOuter).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(32.dp)
                    .then(
                        if (on) Modifier.shadow(1.dp, SegmentInner, clip = false, ambientColor = c.cardShadow, spotColor = c.cardShadow)
                            .background(c.surface, SegmentInner)
                        else Modifier,
                    )
                    .clip(SegmentInner)
                    .selectable(on, role = Role.Tab) { onSelect(i) }
                    .then(if (tags != null) Modifier.testTag(tags[i]) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = SegmentText, color = if (on) c.ink else c.ink2, maxLines = 1)
            }
        }
    }
}

private val SegmentOuter = RoundedCornerShape(10.dp)
private val SegmentInner = RoundedCornerShape(8.dp)
private val SegmentText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

// ---------- Top bar ----------

/**
 * Round icon button on top bar, 40dp. When [glass] is true, it is a white glass circle floating on the
 * content. When [badge] is true, there is an accent color dot in the upper right corner (something else
 * is going on); when [enabled] is false, it becomes faded and cannot be clicked.
 */
@Composable
fun BarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    glass: Boolean = false,
    enabled: Boolean = true,
    badge: Boolean = false,
) {
    val c = MinaTheme.colors
    Box(
        Modifier.size(MinaSize.IconButton)
            .then(if (glass) Modifier.floatShadow(c, CircleShape).background(c.surface, CircleShape) else Modifier)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(MinaSize.Icon).alpha(if (enabled) 1f else 0.35f), tint = c.ink)
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp).size(8.dp)
                    .background(c.bg, CircleShape).padding(1.dp).background(c.accent, CircleShape),
            )
        }
    }
}

/**
 * Top column: 56 meters high, with an icon button on the left and right, the title in the center, and a
 * line of subtitles. The background color defaults to the page background color, and the text will not
 * show through when the content is scrolled under it.
 */
@Composable
fun TopBar(
    title: String,
    left: ImageVector? = null,
    onLeft: () -> Unit = {},
    leftLabel: String = "",
    /** The small dot on the left button, see [BarIconButton]. */
    leftBadge: Boolean = false,
    right: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    background: Color = MinaTheme.colors.bg,
) {
    val c = MinaTheme.colors
    Box(Modifier.fillMaxWidth().background(background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(MinaSize.TopBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (left != null) BarIconButton(left, leftLabel, onLeft, badge = leftBadge) else Spacer(Modifier.size(MinaSize.IconButton))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MinaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle, style = MinaType.Hint, fontWeight = FontWeight.Medium,
                        color = if (subtitleColor == Color.Unspecified) c.ink3 else subtitleColor, maxLines = 1,
                    )
                }
            }
            if (right != null) Row(verticalAlignment = Alignment.CenterVertically, content = right)
            else Spacer(Modifier.size(MinaSize.IconButton))
        }
    }
}

// ---------- Select Capsule ----------

/** Optional capsules (service provider, interface type): 34 height, white card base; dark solid base, inverted text when selected. [small] is the trumpet number in the industry. */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = MinaTheme.colors
    val shape = if (small) SmallChipShape else MinaShapes.Pill
    Box(
        modifier
            .then(
                when {
                    selected -> Modifier.background(c.ink, shape)
                    small -> Modifier.background(c.fill, shape)
                    else -> Modifier.cardSurface(c, shape)
                },
            )
            .clip(shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .height(if (small) 28.dp else 34.dp)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = if (small) SmallChipText else ChipText, maxLines = 1, softWrap = false,
            color = if (selected) c.bg else c.ink,
        )
    }
}

private val SmallChipShape = RoundedCornerShape(8.dp)
private val ChipText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val SmallChipText = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

// ---------- Small label ----------

enum class TagTone { Neutral, Accent }

/** Capability small labels (tool calling, picture viewing, 64K): 11 / 600, rounded corners 7. */
@Composable
fun CapabilityTag(text: String, tone: TagTone = TagTone.Neutral) {
    val c = MinaTheme.colors
    val (bg, fg) = if (tone == TagTone.Accent) c.accentSoft to c.accent else c.greySoft to c.ink2
    Text(text, Modifier.background(bg, TagShape).padding(horizontal = 7.dp, vertical = 2.dp), style = TagText, color = fg, maxLines = 1)
}

private val TagShape = RoundedCornerShape(7.dp)
private val TagText = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)

// ----------Page break ----------

/** Pagination points at the bottom of the onboarding process: The current step is an 18-wide short strip of accent color, and the rest are 6 gray dots. */
@Composable
fun ProgressDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 18.dp else 6.dp, 6.dp).background(if (i == current) c.accent else c.hair, CircleShape))
        }
    }
}
