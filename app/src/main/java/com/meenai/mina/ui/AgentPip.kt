package com.meenai.mina.ui

import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.view.Display
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.meenai.mina.ui.theme.MinaType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.PipPhysics.Corner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.components.floatShadow

/** Small window width: collapse/expand. The height is calculated based on the aspect ratio of the screen (or the main screen when there is no screen), and the entire screen is displayed completely without cutting off the bottom. */
private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** The aspect ratio of the main screen (according to real pixels, including system bars): the virtual screen is built according to the size of the main screen, and the small window is sized according to it when there is no picture yet. */
private fun mainScreenAspect(context: Context): Float {
    val m = DisplayMetrics()
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
    return if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 2.2f
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Suspended picture-in-picture: Covers the conversation, only displays the real-time picture of the
 * Agent's virtual screen, and can only view clicks; no text or buttons are placed on it, and everything
 * you are asked to do is in the conversation card. The status depends only on the frame light: a thin
 * green edge when working, and an orange breathing halo when you need it (automatically expanded by
 * [ChatViewModel] when the takeover starts). Usually it stops in the lower right corner, above the input
 * "Turn off the small screen" in the upper left corner when expanding ([onClose]): before the end of
 * this task, there is only a small button in the lower right corner ([PipUi.closed]), click it and open
 * it again ([onReopen]); when you want to process it, the small button turns orange and breathes, click
 * it and give it to [onAttention] as well. When [reduceMotion] (the system turns off the animation),
 * there is no spring, scaling and breathing, and it goes directly to the position.
 * [topInset]/[bottomInset] is the pixel height occupied by the top bar and input box, and the small
 * window will not stop under them.
 */
@Composable
fun AgentPip(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit = {},
    onReopen: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = if (reduceMotion) tween<Float>(150) else tween(300)
        // Turned off: Only the small button in the lower right corner is left (the few seconds after the
        // task is completed are not left)
        AnimatedVisibility(
            pip.visible && pip.closed && pip.mode != PipMode.Done, Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(fade), exit = fadeOut(fade),
        ) {
            val bottom = with(LocalDensity.current) { bottomInset.toDp() }
            ClosedPip(pip.mode == PipMode.NeedsYou, reduceMotion, Modifier.padding(end = 12.dp, bottom = bottom + 12.dp), onReopen, onAttention)
        }
        AnimatedVisibility(pip.visible && !pip.closed && pip.expanded, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(MinaTheme.colors.floatShadow.copy(alpha = 0.12f)).pointerInput(Unit) { detectTapGestures { onCollapse() } })
        }
        AnimatedVisibility(pip.visible && !pip.closed, enter = fadeIn(fade), exit = fadeOut(if (reduceMotion) tween(150) else tween(500))) {
            PipWindow(
                pip, frame, webContent, reduceMotion,
                areaWidth = constraints.maxWidth.toFloat(), areaHeight = constraints.maxHeight.toFloat(),
                topInset = topInset, bottomInset = bottomInset, onExpand = onExpand, onCollapse = onCollapse, onAttention = onAttention,
                onClose = onClose,
            )
        }
    }
}

/**
 * The remaining small buttons after the small screen is turned off: usually write "show screen", click
 * and open it; when you are asked to do something, breathe orange, write "Waiting for you to deal with",
 * click and scroll to the card you want to deal with (the small screen is closed and the things you are
 * asked to do are not missed).
 */
private val ClosedPipShape = RoundedCornerShape(50)
private val ExpandedOuterShape = RoundedCornerShape(26.dp)
private val CollapsedOuterShape = RoundedCornerShape(16.dp)
private val ExpandedInnerShape = RoundedCornerShape(22.dp)
private val CollapsedInnerShape = RoundedCornerShape(13.dp)

@Composable
private fun ClosedPip(needsYou: Boolean, reduceMotion: Boolean, modifier: Modifier, onReopen: () -> Unit, onAttention: () -> Unit) {
    val c = MinaTheme.colors
    val breathState = if (needsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "closedBreathe").animateFloat(
            1f, 0.45f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "closedBreathe",
        )
    } else null
    val label = stringResource(if (needsYou) R.string.pip_closed_needs_you else R.string.pip_reopen)
    Row(
        modifier.floatShadow(c, ClosedPipShape).background(c.glass, ClosedPipShape)
            .drawWithContent {
                drawContent()
                val stroke = (if (needsYou) 2.dp else 0.5.dp).toPx()
                val half = stroke / 2f
                val corner = (size.height / 2f - half).coerceAtLeast(0f)
                val color = if (needsYou) c.warnSolid.copy(alpha = breathState?.value ?: 1f) else c.glassEdge
                drawRoundRect(
                    color = color,
                    topLeft = Offset(half, half),
                    size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(stroke),
                )
            }
            .clip(ClosedPipShape)
            .clickable(onClickLabel = label, role = Role.Button) { if (needsYou) onAttention() else onReopen() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("pip-closed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (needsYou) {
            Box(Modifier.size(7.dp).graphicsLayer { alpha = breathState?.value ?: 1f }.background(c.warnSolid, CircleShape))
        } else {
            Icon(MinaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink2)
        }
        Text(label, style = MinaType.Hint, color = if (needsYou) c.warn else c.ink2)
    }
}

@Composable
private fun PipWindow(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val sizeSpec = if (reduceMotion) snap() else tween<androidx.compose.ui.unit.Dp>(350, easing = Ease)
    // The size follows the aspect ratio of the screen: the mobile screen is the proportion of the main
    // screen (different phones have slightly longer lengths), the desktop web page is horizontal, and
    // the height is determined by a fixed width. The entire screen can fit; if it cannot fit, the entire
    // screen will be reduced proportionally.
    val context = LocalContext.current
    val screenAspect = remember { mainScreenAspect(context) }
    val landscapeWeb = frame != null && webContent && frame.width > frame.height
    val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect
    val target = run {
        val margin = with(density) { 24.dp.toPx() }
        val availableWidth = with(density) { (areaWidth - margin).coerceAtLeast(1f).toDp() }
        val availableHeight = with(density) { (areaHeight - topInset - bottomInset - margin).coerceAtLeast(1f).toDp() }
        val base = when {
            landscapeWeb -> if (pip.expanded) 320.dp else 220.dp
            else -> if (pip.expanded) ExpandedWidth else CollapsedWidth
        }
        val width = minOf(base, availableWidth, availableHeight / aspect)
        width to width * aspect
    }
    val w by animateDpAsState(target.first, sizeSpec, label = "pipW")
    val h by animateDpAsState(target.second, sizeSpec, label = "pipH")
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    val bounds = PipPhysics.Bounds(areaWidth, areaHeight, wPx, hPx, with(density) { 12.dp.toPx() }, topInset, bottomInset)
    val currentBounds by rememberUpdatedState(bounds)
    val motionOff by rememberUpdatedState(reduceMotion)
    val expanded by rememberUpdatedState(pip.expanded)
    val expand by rememberUpdatedState(onExpand)
    val needsYou by rememberUpdatedState(pip.mode == PipMode.NeedsYou)
    val attend by rememberUpdatedState(onAttention)

    var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
    // The position during dragging or springing; if empty, it means sticking to the corner (when
    // expanded and collapsed, the corner will be stuck along with the size)
    var free by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    /** Bounce from [start] to the corner [c] at speed (vx, vy); directly in place when the animation is turned off. */
    fun springTo(c: Corner, start: Offset, vx: Float, vy: Float) {
        settle?.cancel()
        corner = c
        if (motionOff) {
            free = null
            return
        }
        settle = scope.launch {
            val spring = PipPhysics.Spring()
            var mx = PipPhysics.Motion(start.x, vx)
            var my = PipPhysics.Motion(start.y, vy)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val (tx, ty) = currentBounds.position(c)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)
                free = Offset(mx.position, my.position)
                if (spring.settled(mx, tx) && spring.settled(my, ty)) break
            }
            free = null
        }
    }

    fun release(vx: Float, vy: Float) {
        val start = free ?: return
        springTo(PipPhysics.snapCorner(start.x, start.y, vx, vy, currentBounds), start, vx, vy)
    }

    // If it stops at the lower two corners when you are done, move it to the top on the same side first
    // so as not to cover the result card that just came out.
    LaunchedEffect(pip.mode) {
        if (pip.mode != PipMode.Done) return@LaunchedEffect
        val up = when (corner) {
            Corner.BottomStart -> Corner.TopStart
            Corner.BottomEnd -> Corner.TopEnd
            else -> return@LaunchedEffect
        }
        springTo(up, free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }, 0f, 0f)
    }

    val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
    val outer = if (pip.expanded) ExpandedOuterShape else CollapsedOuterShape
    val inner = if (pip.expanded) ExpandedInnerShape else CollapsedInnerShape
    val breathState = if (pip.mode == PipMode.NeedsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        )
    } else null

    Box(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }) {
        if (pip.mode == PipMode.NeedsYou) {
            // Lightweight translucent glow ring instead of expensive real-time Modifier.blur(10.dp).
            val c = MinaTheme.colors
            Box(
                Modifier.offset((-5).dp, (-5).dp).size(w + 10.dp, h + 10.dp)
                    .graphicsLayer { alpha = breathState?.value ?: 1f }
                    .background(c.warnSolid.copy(alpha = 0.22f), outer)
                    .border(1.5.dp, c.warnSolid.copy(alpha = 0.55f), outer),
            )
        }
        Box(
            Modifier.size(w, h)
                .graphicsLayer { alpha = if (pip.mode == PipMode.Done) 0.85f else 1f }
                .floatShadow(MinaTheme.colors, outer)
                .background(MinaTheme.colors.glass, outer)
                .border(0.5.dp, MinaTheme.colors.glassEdge, outer)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    // Count the displacement by yourself instead of detectDragGestures: When the touch
                    // threshold is crossed, the segment within the threshold is also added. The window
                    // always maintains the grab position when pressed, truly 1:1 hand tracking.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            total += change.positionChange()
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                settle?.cancel()
                            }
                            if (dragging) {
                                change.consume()
                                free = origin + total
                                tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                            }
                        }
                        if (dragging) tracker.calculateVelocity().let { v -> release(v.x, v.y) }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { if (needsYou) attend() else if (!expanded) expand() } }
                .padding(if (pip.expanded) 4.dp else 3.dp),
        ) {
            Box(Modifier.fillMaxSize().clip(inner).background(MinaTheme.colors.surface)) {
                if (frame != null) {
                    val image = remember(frame) { frame.asImageBitmap() }
                    Image(image, stringResource(R.string.pip_screen), Modifier.fillMaxSize(), alignment = Alignment.TopCenter,
                        contentScale = if (webContent) ContentScale.Fit else ContentScale.Crop)
                }
            }
            if (pip.expanded) {
                // Upper left corner: Close the small screen of this task (only the small button in the
                // lower right corner remains); Upper right corner × Just collapse
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp)
                        .floatShadow(MinaTheme.colors, CircleShape).background(MinaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.pip_close), onClick = onClose)
                        .testTag("pip-close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(MinaIcons.EyeOff, stringResource(R.string.pip_close), Modifier.size(13.dp), tint = MinaTheme.colors.ink) }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .floatShadow(MinaTheme.colors, CircleShape).background(MinaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.collapse), onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) { Icon(MinaIcons.Close, stringResource(R.string.collapse), Modifier.size(12.dp), tint = MinaTheme.colors.ink) }
            }
        }
        // The border light is painted on the outer edge of the glass frame
        val okColor = MinaTheme.colors.ok
        val warnColor = MinaTheme.colors.warnSolid
        when (pip.mode) {
            PipMode.Running -> Box(Modifier.size(w, h).border(2.dp, okColor, outer))
            PipMode.NeedsYou -> {
                val cornerDp = if (pip.expanded) 26.dp else 16.dp
                Box(
                    Modifier.size(w, h).drawWithContent {
                        drawContent()
                        val stroke = 2.dp.toPx()
                        val half = stroke / 2f
                        val corner = (cornerDp.toPx() - half).coerceAtLeast(0f)
                        drawRoundRect(
                            color = warnColor.copy(alpha = breathState?.value ?: 1f),
                            topLeft = Offset(half, half),
                            size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius(corner, corner),
                            style = Stroke(stroke),
                        )
                    },
                )
            }
            PipMode.Done -> Unit
        }
    }
}
