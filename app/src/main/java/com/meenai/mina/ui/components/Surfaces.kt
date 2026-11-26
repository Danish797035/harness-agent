package com.meenai.mina.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.HarnessColors
import com.meenai.mina.ui.theme.HarnessShapes
import com.meenai.mina.ui.theme.HarnessSize
import com.meenai.mina.ui.theme.HarnessTheme
import com.meenai.mina.ui.theme.HarnessType
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/** Bottom of the page: clean near white (near black under dark colors), no gradient. */
@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(MinaTheme.colors.bg), content = content)
}

/** The base of the card: white + black 5% stroke + very light shadow; dark only stroke. */
fun Modifier.cardSurface(colors: MinaColors, shape: Shape = MinaShapes.Card): Modifier =
    (if (colors.cardShadow.alpha > 0f) {
        shadow(6.dp, shape, clip = false, ambientColor = colors.cardShadow.copy(alpha = 0.45f), spotColor = colors.cardShadow.copy(alpha = 0.16f))
    } else this)
        .background(colors.surface, shape)
        .border(0.5.dp, colors.edge, shape)

/** Shadows for suspended elements (input fields, buttons floating on content). */
fun Modifier.floatShadow(colors: MinaColors, shape: Shape): Modifier =
    shadow(14.dp, shape, clip = false, ambientColor = colors.floatShadow.copy(alpha = 0.3f), spotColor = colors.floatShadow.copy(alpha = 0.35f))

/** A card with the content cut in the rounded corners (the row press effect does not reveal the corners). */
@Composable
fun HarnessCard(modifier: Modifier = Modifier, shape: Shape = HarnessShapes.Card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(HarnessTheme.colors, shape).clip(shape), content = content)
}

@Composable
fun MinaCard(modifier: Modifier = Modifier, shape: Shape = HarnessShapes.Card, content: @Composable ColumnScope.() -> Unit) =
    HarnessCard(modifier, shape, content)

/**
 * Group cards: a group of [ListRow]. The following comes with a 12dp spacing, which together with the
 * next group of [GroupLabel] (on 8) is 20 between groups. The lines separating lines are drawn by the
 * lines themselves, starting from the text.
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MinaCard(modifier.padding(bottom = 12.dp), content = content)
}

/** Status card: circular icon on the left, title and description on the right. Used for the overall status at the top of the page. */
@Composable
fun StateCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MinaTheme.colors
    MinaCard(modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (bg, fg) = tone.colors(c)
            Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MinaType.BodyStrong, color = c.ink)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MinaType.Caption, color = c.ink3)
            }
            trailing()
        }
    }
}

// ---------- Suspended glass ----------

/** It can blur the background (RenderEffect is available starting from Android 12); when it cannot, the glass will return to the opaque card bottom. */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The picture of the content behind the glass: use [glassSource] to draw the content, blur it with [GlassBar] and put it under yourself. */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/**
 * Returns an optional blur backdrop when [enableBlur] is explicitly true and RenderEffect blur is supported.
 * Defaults to false so scrolling LazyColumns avoid full-surface GraphicsLayer recording and 30px real-time blur.
 */
@Composable
fun rememberGlassBackdrop(enableBlur: Boolean = false): GlassBackdrop? {
    if (!enableBlur || !GlassBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Mark this piece of content as the background of the glass. The glass itself cannot be inside this content. */
fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

private val GlassBlur = BlurEffect(30f, 30f, TileMode.Clamp)

/**
 * Shell of glass input field: translucent bottom + background blur + floating shadow, height at least
 * 52. When [backdrop] is null (blurring is not supported, or there is no content behind it), opaque
 * background is used.
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = MinaShapes.Pill,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MinaTheme.colors
    var origin = if (backdrop != null) remember { mutableStateOf(Offset.Zero) } else null
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(
                if (backdrop != null && origin != null) Modifier.onGloballyPositioned { origin.value = it.positionInRoot() }
                else Modifier,
            ),
    ) {
        if (backdrop != null && origin != null) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin.value
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        val fill = if (backdrop != null) c.glass else c.surface.copy(alpha = if (c.isDark) 0.92f else 0.94f)
        val borderWidth = if (backdrop != null) 0.5.dp else 1.dp
        Row(
            Modifier.fillMaxWidth().background(fill)
                .border(borderWidth, c.glassEdge, shape)
                .heightIn(min = MinaSize.Composer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}
