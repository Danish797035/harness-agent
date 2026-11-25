package com.meenai.mina.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/** Group subtitle: 13 / 500, auxiliary text color. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp, bottom = 8.dp), style = MinaType.Label, color = MinaTheme.colors.ink3)
}

/** Group the instructions below and align them with the text in the card. */
@Composable
fun FooterNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        style = MinaType.Hint, color = color.takeOrElse { MinaTheme.colors.ink3 },
    )
}

/** The background color and graphic color of the icon block. Default is neutral; only status is colored. */
enum class TileTone {
    Neutral, Accent, Ok, Warn, Bad;

    fun colors(c: MinaColors): Pair<Color, Color> = when (this) {
        Neutral -> c.neutralSoft to c.ink
        Accent -> c.accentSoft to c.accent
        Ok -> c.okSoft to c.ok
        Warn -> c.warnSoft to c.warn
        Bad -> c.badSoft to c.bad
    }
}

/** Icon block on the left side of the row: 30dp, rounded corners 9, inner graphic 18dp. */
@Composable
fun IconTile(icon: ImageVector, tone: TileTone = TileTone.Neutral) {
    val (bg, fg) = tone.colors(MinaTheme.colors)
    Box(Modifier.size(MinaSize.Tile).background(bg, MinaShapes.Tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(MinaSize.TileIcon), tint = fg)
    }
}

/** The dividing line between rows is drawn from [start] to the right; the first row of the group ([first]) is not drawn. */
private fun Modifier.topHairline(first: Boolean, start: Dp, color: Color): Modifier =
    if (first) this
    else drawBehind {
        val y = 0.25.dp.toPx()
        drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }

/** Text starts here when there is an icon block: left margin 16 + icon block 30 + spacing 14. */
private val TextStartWithIcon = 60.dp
private val TextStartPlain = 16.dp

/**
 * List row: The icon block on the left is optional, the title and description are in the middle, and
 * [trailing] (value, capsule, switch, arrow) is on the right. [detail] is the small text describing the
 * next line (such as "last result"), and the color is determined by [detailColor]. When [first] is the
 * first line of the group, no dividing line is drawn; when [onClick] is empty, the entire line cannot be
 * clicked.
 */
@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    titleColor: Color = Color.Unspecified,
    tone: TileTone = TileTone.Neutral,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .topHairline(first, if (icon != null) TextStartWithIcon else TextStartPlain, c.hair)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = MinaSize.RowMin)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconTile(icon, tone)
        Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
            Text(title, style = MinaType.Body, color = titleColor.takeOrElse { c.ink })
            if (!subtitle.isNullOrEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3)
            if (!detail.isNullOrEmpty()) Text(detail, Modifier.padding(top = 2.dp), style = MinaType.Hint, color = detailColor.takeOrElse { c.ink3 })
        }
        trailing()
    }
}

enum class ValueTone { Plain, Ok, Warn, Bad, Link }

/** The value at the end of the line. */
@Composable
fun RowValue(text: String, tone: ValueTone = ValueTone.Plain) {
    val c = MinaTheme.colors
    Text(
        text, style = MinaType.Value,
        fontWeight = if (tone == ValueTone.Link) FontWeight.Medium else FontWeight.Normal,
        color = when (tone) {
            ValueTone.Plain -> c.ink3
            ValueTone.Ok -> c.ok
            ValueTone.Warn -> c.warn
            ValueTone.Bad -> c.bad
            ValueTone.Link -> c.accent
        },
        maxLines = 1,
    )
}

/** The small arrow at the end of the line: Click to enter and there will be another page. */
@Composable
fun RowChevron() {
    Icon(MinaIcons.Chevron, null, Modifier.size(MinaSize.SmallIcon), tint = MinaTheme.colors.ink3)
}

/** Single selection row: circle on the left, solid accent color when selected. The separator line starts at the text. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    first: Boolean = false,
    enabled: Boolean = true,
    titleStyle: TextStyle = MinaType.Body,
    /** Additional content below the title (e.g. capability tag). */
    below: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .topHairline(first, 50.dp, c.hair)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioMark(selected, enabled)
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = titleStyle, color = if (enabled) c.ink else c.ink3)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3)
            if (below != null) Box(Modifier.padding(top = 4.dp)) { below() }
        }
        trailing()
    }
}

@Composable
private fun RadioMark(selected: Boolean, enabled: Boolean = true) {
    val c = MinaTheme.colors
    if (selected && enabled) {
        Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(c.onAccent, CircleShape))
        }
    } else {
        Box(Modifier.size(22.dp).border(1.6.dp, c.hair, CircleShape))
    }
}

/**
 * Input row in the card: fixed-width title on the left, single-line input box on the right (displays
 * [placeholder] when empty). When [secret] is true, it is displayed as the password; [trailing] is
 * placed on the right side of the input box (for example, "display").
 */
@Composable
fun InputRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    first: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    labelWidth: Dp = 84.dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth().topHairline(first, TextStartPlain, c.hair).keepsKeyboard()
            .heightIn(min = MinaSize.RowMin).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(labelWidth), style = MinaType.Body, color = c.ink)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MinaType.Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                value, onChange, modifier.fillMaxWidth(), singleLine = true,
                textStyle = MinaType.Body.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
    }
}

/** The dividing line between the self-typed blocks in the card is drawn from [start] to the right. */
@Composable
fun Hairline(start: Dp = TextStartWithIcon) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(MinaTheme.colors.hair))
}
