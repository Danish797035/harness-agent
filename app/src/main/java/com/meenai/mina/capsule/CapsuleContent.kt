package com.meenai.mina.capsule

import com.meenai.mina.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.ui.ScreenCopy
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaLogo
import com.meenai.mina.ui.theme.MinaColors
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import kotlin.math.ceil

/** The edge left for the shadow outside the capsule: the floating layer window is sized according to the content, and the shadow is drawn inside the window; the less it is left, the narrower the transparent edge the window blocks the app below. */
internal val CapsuleShadowRoom = 8.dp
private val CapsuleHeight = 40.dp
private val BarHeight = 48.dp
private val LogoSize = 30.dp
private const val FADE_MS = 400
/** When you capture the screen, one flashes on the screen: how bright it is and how long it takes to fade away. */
private const val FLASH_ALPHA = 0.8f
private const val FLASH_MS = 320
/** The time it takes for one to expand into a capsule and retract one. */
private const val MORPH_MS = 260
/** How much it will zoom in when you press and hold to speak, how thick the outer ring will be, and how long it will last. */
private const val HOLD_SCALE = 0.06f
private val HoldRing = 3.dp
private const val HOLD_MS = 180
/** The length of time a work circle lasts. */
private const val SPIN_MS = 1_200
private val CapsuleText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val BarDetail = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val CountText = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)

/** The fixed saying on the capsule. */
object CapsuleCopy {
    val STOP: String get() = tr(R.string.capsule_stop)
    val EXPAND: String get() = tr(R.string.capsule_expand)
    val TALK: String get() = tr(R.string.capsule_talk)
    val SWAP_SIDE: String get() = tr(R.string.capsule_swap_side)
    const val IDLE = "Harness Agent"

    /** What an icon says when reading the screen: What is it doing, do you want it to be processed, and the result; the number of items for several times is already in [CapsuleView.Shown.text]. */
    fun describe(view: CapsuleView.Shown): String = when {
        view.kind == CapsuleKind.Idle -> IDLE
        view.kind.needsYou -> tr(R.string.capsule_desc_needs, view.text)
        view.kind == CapsuleKind.Running -> tr(R.string.capsule_desc_running, view.text)
        view.kind == CapsuleKind.Held || view.kind == CapsuleKind.Cancelled -> tr(R.string.capsule_desc_other, view.text)
        else -> "Harness Agent ${view.text}"
    }
}

/**
 * Suspension capsule, without status: It is usually a Mina icon, and the status is drawn on the outer
 * circle and corner mark (the blue arc rotates around during work, the gray circle is waiting for
 * conditions, and the whole capsule turns orange when you need to process it, and the outer circle
 * exhales and inhales; when there are several pieces, the number of pieces is written on the corner;
 * green tick is done, and gray corner mark is stopped if it is not completed; nothing when idle);
 * [CapsuleView.Shown.pill] It expands into a line of capsules (you have to deal with it and you have
 * just finished it), the width slowly changes, and the icon sticks to the docked side ([side]); when the
 * app is handed over to you, it is the one at the top that says "You handle it·Don't do it·It's done".
 * The capsule only has one line of text, and the maximum width reaches 60% of the screen, and exceeds
 * truncation; in the same [CapsuleView.Shown.widthEpoch], it only becomes wider but not narrower. Click
 * one or the capsule and give it to [onTap] (expand card). When reading the screen, this point is said
 * to be [tapLabel]; give the two buttons on that bar to [onStop] and [onHandBack]. [onSwapSide] gives
 * screen reading software an additional "move to the other side of the screen" function instead of
 * dragging. [alpha] only has a light background and icons, while the outer ring and corner markers
 * remain solid colors. The double-click is recognized by the caller from the two [onTap]; the
 * double-click of the screen reading software is a click, so when [onSendScreen] is given, there is
 * another "send the current screen to Mina". [flash] Every time one is added, one will flash up (screen
 * shot). When [holding] is long pressed and talking, the capsule is slightly enlarged and has a blue
 * border around the outside. [onTalk] gives the screen reading software one more speaking operation
 * ([talkLabel]), instead of long pressing: click to start, click again to send.
 */
@Composable
fun CapsuleContent(
    view: CapsuleView.Shown,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onHandBack: () -> Unit,
    modifier: Modifier = Modifier,
    side: DockSide = DockSide.Right,
    tapLabel: String = CapsuleCopy.EXPAND,
    onSwapSide: (() -> Unit)? = null,
    alpha: Float = 1f,
    onSendScreen: (() -> Unit)? = null,
    flash: Int = 0,
    holding: Boolean = false,
    onTalk: (() -> Unit)? = null,
    talkLabel: String = "",
) {
    // Fade and restore slowly; defer state read to graphicsLayer so parent does not recompose each frame
    val shownState = if (MinaTheme.reduceMotion) null else animateFloatAsState(alpha, tween(FADE_MS), label = "fade")
    val alphaProvider: () -> Float = { shownState?.value ?: alpha }
    val a11y = Modifier.semantics {
        customActions = listOfNotNull(
            onSwapSide?.let { f -> CustomAccessibilityAction(CapsuleCopy.SWAP_SIDE) { f(); true } },
            onSendScreen?.let { f -> CustomAccessibilityAction(ScreenCopy.SEND_SCREEN) { f(); true } },
            onTalk?.let { f -> CustomAccessibilityAction(talkLabel) { f(); true } },
        )
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        if (view.kind == CapsuleKind.HandedOver) TakeoverBar(view, onStop, onHandBack)
        else Capsule(view, side == DockSide.Right, onTap, tapLabel, a11y, alphaProvider, flash, holding)
    }
}

/** A capsule or unfolded capsule: the same glass bottom, the visible width slowly changes between the two (only changes when drawing, see [Morph]); [end] is attached to the right (icon on the right). */
@Composable
private fun Capsule(
    view: CapsuleView.Shown,
    end: Boolean,
    onTap: () -> Unit,
    tapLabel: String,
    a11y: Modifier,
    alphaProvider: () -> Float,
    flash: Int,
    holding: Boolean,
) {
    val c = MinaTheme.colors
    // Long press while talking: Zoom in a little, and there is a blue border around the outside (drawn
    // in drawBehind/graphicsLayer without forcing Capsule recomposition per frame)
    val pressState = if (MinaTheme.reduceMotion) null else animateFloatAsState(if (holding) 1f else 0f, tween(HOLD_MS), label = "hold")
    val staticPress = if (holding) 1f else 0f
    val need = view.kind.needsYou || view.needCount > 0
    // Screen capture: The white light goes from bright to non-existent, which means the screen flashes
    // when taking a screenshot; it does not flash when the animation is turned off.
    val glow = remember { Animatable(0f) }
    val still = MinaTheme.reduceMotion
    val morph = rememberMorph(animate = !still)
    LaunchedEffect(flash) {
        if (flash == 0 || still) return@LaunchedEffect
        glow.snapTo(FLASH_ALPHA)
        glow.animateTo(0f, tween(FLASH_MS))
    }
    Box(
        Modifier.graphicsLayer {
            val press = pressState?.value ?: staticPress
            scaleX = 1f + HOLD_SCALE * press
            scaleY = 1f + HOLD_SCALE * press
        }.drawBehind {
            val press = pressState?.value ?: staticPress
            if (press <= 0f) return@drawBehind
            val ring = HoldRing.toPx()
            val r = size.height / 2 + ring / 2
            drawRoundRect(
                c.accent.copy(alpha = press), Offset(-ring / 2, -ring / 2), Size(size.width + ring, size.height + ring),
                CornerRadius(r, r), style = Stroke(ring),
            )
        }.then(if (holding) Modifier.testTag("capsule-holding") else Modifier),
    ) {
        Box(
            Modifier.testTag(if (view.pill) "capsule" else "capsule-ball")
                .morphGlass(morph, end, c, need, alphaProvider, glow)
                .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap)
                .then(if (view.pill) Modifier else Modifier.semantics { contentDescription = CapsuleCopy.describe(view) })
                .then(a11y)
                .morphLayout(morph, end),
        ) {
            if (view.pill) PillRow(view, end, need)
            else Box(Modifier.size(CapsuleHeight), contentAlignment = Alignment.Center) { CapsuleLogo() }
        }
        // The outer ring and corner mark do not fade with the bottom; when retracted, they stick to the
        // side of the icon and do not follow the unfinished width.
        if (!view.pill) Box(Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart).size(CapsuleHeight)) {
            Ring(view.kind, need, Modifier.matchParentSize())
            Badge(view, need, Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp))
        }
    }
}

/** The row in the expanded capsule: icon, status mark, word; when pasted on the right, the icon is at the far right, the same position as when it is a capsule. */
@Composable
private fun PillRow(view: CapsuleView.Shown, end: Boolean, need: Boolean) {
    val c = MinaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
    // In the same thing and in the same situation, changing sentences only becomes wider: write down the
    // widest moment in this paragraph
    var widest by remember(view.widthEpoch) { mutableIntStateOf(0) }
    val minWidth = with(LocalDensity.current) { widest.toDp() }
    Row(
        Modifier.widthIn(min = minWidth.coerceAtMost(maxWidth), max = maxWidth)
            .onSizeChanged { if (it.width > widest) widest = it.width }
            .height(CapsuleHeight)
            .padding(start = if (end) 13.dp else 5.dp, end = if (end) 5.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (end) Alignment.End else Alignment.Start),
    ) {
        if (!end) CapsuleLogo()
        Leading(view.kind)
        Text(
            view.text, Modifier.weight(1f, fill = false), style = CapsuleText,
            color = if (need) c.warn else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (end) CapsuleLogo()
    }
}

/** The status marks in front of the text: if you want to process it, it is a breathing point, if it is done, it is a check mark, if it is not done, it is an exclamation mark, if it is stopped, it is a stop, and if the background web page is running, it is a green dot. */
@Composable
private fun Leading(kind: CapsuleKind) {
    val c = MinaTheme.colors
    when {
        kind.needsYou -> BreathingDot(c)
        kind == CapsuleKind.Done -> Icon(MinaIcons.Check, null, Modifier.size(16.dp), tint = c.ok)
        kind == CapsuleKind.Failed -> Icon(MinaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.Cancelled -> Icon(MinaIcons.Stop, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.KeptPage -> LiveDot(c)
        else -> Unit
    }
}

/** The background web page is running: green dots with a light green circle outside. */
@Composable
private fun LiveDot(c: MinaColors) {
    Box(Modifier.size(14.dp).testTag("capsule-live-dot"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).background(c.okSoft, MinaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.ok, MinaShapes.Pill))
    }
}

/** Need you: Orange dots, a light orange circle outside, exhale and inhale; do not move when the animation is turned off. */
@Composable
private fun BreathingDot(c: MinaColors) {
    val haloState = rememberBreatheState()
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = haloState?.value ?: 0f }.background(c.warnSoft, MinaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.warnSolid, MinaShapes.Pill))
    }
}

/** One breath (0..1 round trip); null when animations are turned off. */
@Composable
private fun rememberBreatheState(): State<Float>? = if (MinaTheme.reduceMotion) null else rememberInfiniteTransition(label = "breathe").animateFloat(
    0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathe",
)

/** The outer circle of an icon: a blue arc rotates around during work (it stays still in a full circle when the animation is turned off), a gray circle when waiting for conditions, and you are required to deal with an orange circle and exhale; the rest is empty. */
@Composable
private fun Ring(kind: CapsuleKind, need: Boolean, modifier: Modifier) {
    val c = MinaTheme.colors
    val color = when {
        need -> c.warnSolid
        kind == CapsuleKind.Running -> c.accent
        kind == CapsuleKind.Held -> c.ink3
        else -> return
    }
    val spin = kind == CapsuleKind.Running && !need && !MinaTheme.reduceMotion
    val angleState = if (!spin) null else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin",
    )
    val breatheState = if (need) rememberBreatheState() else null
    Canvas(modifier.testTag("capsule-ring")) {
        val stroke = 2.dp.toPx()
        if (size.width <= stroke || size.height <= stroke) return@Canvas
        val topLeft = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        if (spin) {
            val angle = angleState?.value ?: 0f
            drawArc(c.accentSoft, 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
            drawArc(color, angle - 90f, 100f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            val strength = if (need) 0.45f + 0.55f * (breatheState?.value ?: 1f) else 1f
            drawArc(color.copy(alpha = strength), 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
        }
    }
}

/** The mark on the corner of the icon: write the number of pieces when there are several pieces (orange if you need to do it, gray for other conditions, blue for others); green check mark when only one piece is done, grey, if it is not done, it is stopped. */
@Composable
private fun Badge(view: CapsuleView.Shown, need: Boolean, modifier: Modifier) {
    val c = MinaTheme.colors
    if (view.count >= 2) {
        val fill = when {
            need -> c.warnSolid
            view.kind == CapsuleKind.Held -> c.ink3
            else -> c.accent
        }
        Box(
            modifier.testTag("capsule-count").background(c.surface, MinaShapes.Pill).padding(1.5.dp)
                .sizeIn(minWidth = 15.dp, minHeight = 15.dp).background(fill, MinaShapes.Pill).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("${view.count}", style = CountText, color = c.onAccent, maxLines = 1)
        }
        return
    }
    val (icon, tint) = when (view.kind) {
        CapsuleKind.Done -> MinaIcons.Check to c.ok
        CapsuleKind.Failed -> MinaIcons.Exclaim to c.ink3
        CapsuleKind.Cancelled -> MinaIcons.Stop to c.ink3
        else -> return
    }
    Box(
        modifier.testTag("capsule-badge").size(16.dp).background(c.surface, MinaShapes.Pill).padding(1.5.dp)
            .background(tint, MinaShapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(9.dp), tint = c.surface)
    }
}

/**
 * The width between the capsule and the capsule slowly changes, only the painted piece of glass changes,
 * and the layout does not change frame by frame: the floating layer window follows the size of the
 * content, and when the layout changes, the window must be rearranged and the canvas changed, frame by
 * frame. [target] is the width of the content at the moment, [width] is the visible width (animation),
 * [hold] is the width occupied by the layout during the animation - the wider one between the starting
 * point and the end point, so when the window is widened, it reaches the widest at once, and when it is
 * narrowed, it will not shrink until the drawing is completed. The window will change at most once at a
 * time. When it first appears and when the animation is turned off, it is directly the width of the
 * content.
 */
private class Morph {
    val width = Animatable(0f)
    var target by mutableIntStateOf(0)
    var hold by mutableIntStateOf(0)
}

@Composable
private fun rememberMorph(animate: Boolean): Morph {
    val m = remember { Morph() }
    LaunchedEffect(m.target, animate) {
        val t = m.target
        if (t == 0) return@LaunchedEffect
        if (!animate || m.width.value == 0f) m.width.snapTo(t.toFloat())
        else {
            m.hold = maxOf(ceil(m.width.value).toInt(), t)
            m.width.animateTo(t.toFloat(), tween(MORPH_MS))
        }
        m.hold = t
    }
    return m
}

/** The layout occupies the width of [Morph.hold] and the width of the content. The content is placed close to the docked side (on the right side when [end]): it grows inward and shrinks to the side, and the icon does not move. */
private fun Modifier.morphLayout(m: Morph, end: Boolean): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    if (p.width != m.target) m.target = p.width
    val w = maxOf(m.hold, p.width)
    layout(w, p.height) { p.place(if (end) w - p.width else 0, 0) }
}

/**
 * Glass bottom (same as [glass]), only draw the visible width and the side where it is docked: click it
 * for shadow, crop, background color and stroke, and only redraw the layer when the width changes.
 * [alpha] Light background and icon inside.
 */
private fun Modifier.morphGlass(
    m: Morph,
    end: Boolean,
    c: MinaColors,
    need: Boolean,
    alphaProvider: () -> Float,
    flashGlow: Animatable<Float, *>,
): Modifier =
    graphicsLayer {
        this.alpha = alphaProvider()
        shadowElevation = 8.dp.toPx()
        ambientShadowColor = c.floatShadow.copy(alpha = 0.25f)
        spotShadowColor = c.floatShadow.copy(alpha = 0.3f)
        val mw = m.width.value
        shape = if (mw > 0f && ceil(mw).toInt() != m.hold) EdgePill(mw, end) else MinaShapes.Pill
        clip = true
    }.drawWithContent {
        val mw = m.width.value
        val w = if (mw > 0f) mw.coerceAtMost(size.width) else size.width
        val left = if (end) size.width - w else 0f
        val topLeft = Offset(left, 0f)
        val rectSize = Size(w, size.height)
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(c.surface.copy(alpha = 0.95f), topLeft, rectSize, radius)
        if (need) drawRoundRect(c.warnSoft, topLeft, rectSize, radius)
        drawContent()
        val g = flashGlow.value
        if (g > 0f) drawRoundRect(Color.White.copy(alpha = g), topLeft, rectSize, radius)
        val stroke = (if (need) 1.dp else 0.5.dp).toPx()
        val half = stroke / 2f
        val br = (size.height / 2f - half).coerceAtLeast(0f)
        drawRoundRect(
            color = if (need) c.warnSolid.copy(alpha = 0.5f) else c.glassEdge,
            topLeft = Offset(left + half, half),
            size = Size((w - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(br, br),
            style = Stroke(stroke),
        )
    }

/** A capsule shape with a width of [width] (0 or beyond) and close to one side (right side in the case of [end]); [inset] is drawn inwards (for drawing strokes). */
private class EdgePill(private val width: Float, private val end: Boolean, private val inset: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = if (width > 0f) width.coerceAtMost(size.width) else size.width
        val left = if (end) size.width - w else 0f
        val r = size.height / 2 - inset
        return Outline.Rounded(RoundRect(left + inset, inset, left + w - inset, size.height - inset, CornerRadius(r, r)))
    }
}

/** When the App is in your hand, there is a line at the top: "You handle it" and a line of small words below, and on the right side, "No more" and "It's done." */
@Composable
private fun TakeoverBar(view: CapsuleView.Shown, onStop: () -> Unit, onHandBack: () -> Unit) {
    val c = MinaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp
    Row(
        Modifier.testTag("capsule-bar")
            .glass(c, MinaShapes.Pill, need = false)
            .widthIn(max = maxWidth)
            .height(BarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsuleLogo()
        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}) {
            Text(view.text, style = CapsuleText, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            view.detail?.let { Text(it, style = BarDetail, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(
            Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button, onClick = onStop)
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(CapsuleCopy.STOP, style = CapsuleText.copy(fontWeight = FontWeight.Normal), color = c.ink2, maxLines = 1)
        }
        MinaButton(stringResource(R.string.action_hand_back), onHandBack, size = ButtonSize.Small)
    }
}

/** Mina icon (circle) on capsule. */
@Composable
private fun CapsuleLogo() {
    MinaLogo(LogoSize, corner = LogoSize / 2)
}

/**
 * The glass bottom floating on other apps: The screen of the underlying app cannot be seen in the
 * floating layer window to blur, use a nearly opaque card bottom, thin strokes and floating shadows;
 * when you need it, add a layer of light orange and turn the stroke to orange.
 */
internal fun Modifier.glass(c: MinaColors, shape: Shape, need: Boolean): Modifier =
    shadow(8.dp, shape, clip = false, ambientColor = c.floatShadow.copy(alpha = 0.25f), spotColor = c.floatShadow.copy(alpha = 0.3f))
        .clip(shape)
        .background(c.surface.copy(alpha = 0.95f))
        .then(if (need) Modifier.background(c.warnSoft).border(1.dp, c.warnSolid.copy(alpha = 0.5f), shape) else Modifier.border(0.5.dp, c.glassEdge, shape))
