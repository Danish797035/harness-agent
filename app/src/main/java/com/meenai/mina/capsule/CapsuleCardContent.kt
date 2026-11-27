package com.meenai.mina.capsule

import kotlinx.coroutines.delay

import androidx.compose.runtime.produceState

import com.meenai.mina.ui.KeptPageCopy

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.core.userscreen.CapturedScreen
import com.meenai.mina.ui.MarkdownText
import com.meenai.mina.ui.ScreenCopy
import com.meenai.mina.ui.ScreenThumb
import com.meenai.mina.ui.UserMessage
import androidx.compose.ui.graphics.ImageBitmap
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaLogo
import com.meenai.mina.ui.components.StepItem
import com.meenai.mina.ui.components.StepState
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.flow.first

private val CardShape = RoundedCornerShape(22.dp)
private val ScreenShape = RoundedCornerShape(14.dp)
private val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
private val CardWidth = 322.dp
private val ScreenWidth = 104.dp
private val ZoomWidth = 300.dp
private val Title = TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
private val Sub = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val Body = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
private val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
private val Step = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** Fixed sayings on cards (buttons, links, screen-reading descriptions). */
object CardCopy {
    val STOP: String get() = tr(R.string.chat_stop)
    val TAKE_OVER: String get() = tr(R.string.action_take_over)
    val RESUME: String get() = tr(R.string.chat_continue)
    val APPROVE_ONCE_ONLY: String get() = tr(R.string.card_approve_once)
    val DECLINE: String get() = tr(R.string.action_decline)
    val SEE_PROCESS: String get() = tr(R.string.card_see_process)
    val BACK_TO_MINA: String get() = tr(R.string.card_back_to_mina)
    val SEND: String get() = tr(R.string.chat_send)
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
    val COLLAPSE: String get() = tr(R.string.collapse)
    val SCREEN: String get() = tr(R.string.card_screen)
    val SCREEN_LABEL: String get() = tr(R.string.card_screen_label)
    val ZOOMED: String get() = tr(R.string.card_zoomed)
    val ZOOM_HINT: String get() = tr(R.string.card_zoom_hint)
}

/** The buttons on the card are converted into existing actions of the task service (see [CapsuleController]). [runKey] is the number of that thing when pressed. */
interface CardActions {
    fun collapse()
    fun stop(runKey: String)
    fun takeOver(runKey: String)
    fun resume(runKey: String)
    /** Pause: Stop before changing the screen in the next step and wait for "Continue". */
    fun pause(runKey: String) {}
    fun answer(runKey: String, text: String)
    fun approve(runKey: String, requestKey: Int)
    fun decline(runKey: String, requestKey: Int)
    fun openMina(conversationId: String)
    fun zoom(on: Boolean)
    /** Click the input box at the bottom: Only then does the floating layer change to be able to receive the keyboard. */
    fun openInput()
    fun draft(text: String)
    /** Send the words in the input box (give it to someone to see [CapsuleCards.input]). */
    fun send()
    /** Idle card: [fresh] is true to open a new conversation, false to continue the most recent conversation. */
    fun freshChat(fresh: Boolean)
    /** The dialogue card has scrolled to the top: load a few more rounds forward. */
    fun loadEarlier()
    /** Screen capture button on the left side of the input box: hide the card first, capture the user's screen at the moment, and hang it above the input box. */
    fun captureScreen()
    /** × on the attachment bar: Don’t want this screenshot. */
    fun dropScreen()
    /** "Close page" on the card that leaves the page running in the background. */
    fun closeKeptPage() {}
}

/** The screen that was captured and hung on the input box waiting to be sent: [thumb] is a thumbnail (the page that does not allow screenshots does not have it). */
data class PendingScreen(val shot: CapturedScreen, val thumb: ImageBitmap?)

/**
 * The input box at the bottom of the card looks like this: [open] is a box that can be typed (the
 * floating layer can accept the keyboard), otherwise it is a line of prompts that will be opened after
 * clicking on it; [screen] is the screen that was cut off and hung on it (send it with the next
 * sentence, or just send it).
 */
data class CardInputUi(val open: Boolean = false, val draft: String = "", val screen: PendingScreen? = null) {
    /** After writing or hanging a screen: you can send it. */
    val canSend: Boolean get() = draft.isNotBlank() || screen != null
}

/**
 * Click on the card to open the capsule, without status, just saying one thing (see [CapsuleCards.of]
 * for which one to choose). The left side of the task card ([CardView.Detail]) is the small virtual
 * screen [preview] (only if that thing has a picture), and the right side contains the steps, things you
 * want to do, and buttons according to the status; the dialogue card ([CardView.Chat]) is the back and
 * forth of the dialogue [thread] (null when still reading, empty in the middle). Dialogue cards are
 * fixed at [height] height (visible cards, without shadow edges; when not given, press the screen height
 * of [CardDocking.SHARE]) and do not change with the content; task cards are based on content height, up
 * to [height], with no space below - the middle section of the task card takes the height of the small
 * screen (according to the aspect ratio of the screen) and the taller one in the text on the right. The
 * steps in the work are at most as high as the small screen (when there is no screen, it is calculated
 * according to the aspect ratio of the main screen, and the card does not jump when the screen comes).
 * No matter how many steps there are, the card will not be high: the latest step is at the bottom, and
 * the old ones are scrolled up; other content cannot be accommodated, so it is scrolled inside, and the
 * buttons and input boxes you want to click are fixed at the bottom. When the height changes (card
 * changes, content changes), it snaps into place: the floating layer window follows the size of the
 * content, only changes once, and does not change the window frame by frame; the upper part does not
 * move (see [CardDocking]). The animation of unfolding and folding is applied outside ([OverlayMotion]).
 * [onHandle] reports the position (pixels) of the title bar in the window: press it to drag the card.
 * [zoomed], the whole picture is changed to the enlarged picture, click to return. [elapsed] Change the
 * start time into elapsed time (updated every second).
 */
@Composable
fun CapsuleCard(
    card: CardView,
    preview: Bitmap?,
    zoomed: Boolean,
    actions: CardActions,
    elapsed: @Composable (Long?) -> String?,
    modifier: Modifier = Modifier,
    input: CardInputUi = CardInputUi(),
    thread: ChatThread? = null,
    height: Dp = Dp.Unspecified,
    onHandle: (Rect) -> Unit = {},
) {
    val field = CapsuleCards.input(card)
    // When the task is still in progress (working, Mina asks you, etc.), stop is placed on the right
    // side of the input box like the dialogue page, without occupying another button
    val stopKey = CapsuleCards.stopKey(card)
    val h = if (height == Dp.Unspecified) (LocalConfiguration.current.screenHeightDp * CardDocking.SHARE).dp else height
    val report by rememberUpdatedState(onHandle)
    val handle = Modifier.testTag("capsule-header").onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        report(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }
    val inputRow: @Composable () -> Unit = {
        field?.let { InputRow(it, input, actions, stop = stopKey?.let { k -> { actions.stop(k) } }) }
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        when {
            zoomed && preview != null -> Zoomed(preview, actions)
            card is CardView.Detail -> Detail(card, preview, actions, elapsed, h, handle, inputRow)
            card is CardView.Chat -> ChatCard(card, thread, actions, elapsed, h, handle, inputRow)
            card is CardView.Kept -> KeptCard(card, preview, actions, h, handle)
        }
    }
}

/**
 * The dynamic effect of the appearance and collapse of things in the floating layer (cards are expanded
 * and folded, and then returned to the side): the floating layer window is the final size and position
 * at once, and the things inside grow from the [origin] side only by the scaling and transparency of the
 * layer (give the orientation according to the current size, for example, the card is from the capsule
 * side). When it really starts to come out, adjust [onOpened]; when [open] becomes false, it shrinks to
 * that side, fades out, and the adjustment is completed [onClosed]. Only the layers are moved, the
 * layout is not changed, and the window is not changed frame by frame. When the animation is turned off,
 * it is directly in place, and when it is put away, it is immediately adjusted to [onClosed].
 */
@Composable
fun OverlayMotion(
    open: Boolean, origin: (Size) -> TransformOrigin, onClosed: () -> Unit, onOpened: () -> Unit = {}, content: @Composable () -> Unit,
) {
    val still = MinaTheme.reduceMotion
    val shown = remember { Animatable(if (still) 1f else 0f) }
    val closed by rememberUpdatedState(onClosed)
    val opened by rememberUpdatedState(onOpened)
    val from by rememberUpdatedState(origin)
    LaunchedEffect(open, still) {
        if (open) {
            if (still) {
                shown.snapTo(1f)
                opened()
            } else {
                opened()
                shown.animateTo(1f, tween(CARD_IN_MS, easing = MinaMotion.Decelerate))
            }
        } else {
            if (!still) {
                shown.animateTo(0f, tween(CARD_OUT_MS, easing = FastOutLinearInEasing))
                // Let the completely invisible frame be drawn first, then change the content and move
                // the window, leaving no translucent card frame.
                withFrameNanos { }
            }
            closed()
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = shown.value
            val scale = CARD_FROM_SCALE + (1f - CARD_FROM_SCALE) * v
            scaleX = scale
            scaleY = scale
            // Transparency goes faster than zooming: you can see it clearly as soon as you come out, and
            // it fades out when you retract it.
            alpha = (v * 2f).coerceAtMost(1f)
            transformOrigin = from(size)
        },
    ) { content() }
}

private const val CARD_IN_MS = 240
private const val CARD_OUT_MS = 180
private const val CARD_FROM_SCALE = 0.6f

/**
 * Bottom input box: It is usually a line of prompts (the floating layer does not grab the keyboard).
 * Click to open the box that can be typed and the input method will pop up; press Enter or press the
 * button on the right to send it. When there is [stop], there is always a black stop button on the right
 * (same as the input field on the dialogue page). After writing the word, there is one more to send.
 */
@Composable
private fun InputRow(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)? = null) {
    ui.screen?.let { ScreenChip(it, actions::dropScreen) }
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CameraKey(actions::captureScreen)
        Box(Modifier.weight(1f)) { InputField(field, ui, actions, stop) }
    }
}

/** The screenshot button on the left side of the input box: a screen inside the round button. */
@Composable
private fun CameraKey(onClick: () -> Unit) {
    val c = MinaTheme.colors
    Box(
        Modifier.testTag("capsule-camera").size(38.dp).clip(MinaShapes.Pill).background(c.ink.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClickLabel = ScreenCopy.CAMERA, onClick = onClick)
            .semantics { contentDescription = ScreenCopy.CAMERA },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MinaIcons.Camera, null, Modifier.size(18.dp), tint = c.ink2)
    }
}

/** The one above the input box: Thumbnail, "Your current screen", App · Moment, right × Don't want it; don't let the screenshot page write "Not captured". */
@Composable
private fun ScreenChip(p: PendingScreen, onRemove: () -> Unit) {
    val c = MinaTheme.colors
    val s = p.shot.screen
    Row(
        Modifier.testTag("capsule-attachment").padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.05f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.blocked) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(MinaIcons.Lock, null, Modifier.size(16.dp), tint = c.ink2) }
        else ScreenThumb(p.thumb, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(if (s.blocked) ScreenCopy.BLOCKED else ScreenCopy.TAG, style = Strong.copy(fontSize = 12.5.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ScreenCopy.detail(s), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.testTag("capsule-attachment-remove").size(26.dp).clip(MinaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = ScreenCopy.REMOVE, onClick = onRemove)
                .semantics { contentDescription = ScreenCopy.REMOVE },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MinaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3)
        }
    }
}

/** Send button: It has a blue background when there is something to send, otherwise it is gray and cannot be clicked. */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit, start: Dp = 0.dp) {
    val c = MinaTheme.colors
    Box(
        Modifier.testTag("capsule-send").padding(start = start).size(KeySize).clip(MinaShapes.Pill)
            .background(if (enabled) c.accent else c.ink3.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = CardCopy.SEND, onClick = onSend)
            .semantics { contentDescription = CardCopy.SEND },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MinaIcons.Up, null, Modifier.size(17.dp), tint = Color.White)
    }
}

@Composable
private fun InputField(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)?) {
    val c = MinaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (!ui.open) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
                .padding(end = if (stop != null || ui.screen != null) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.testTag("capsule-input").weight(1f).heightIn(min = 42.dp)
                    .clickable(role = Role.Button, onClickLabel = field.hint, onClick = actions::openInput).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(ui.draft.ifEmpty { field.hint }, style = Body, color = if (ui.draft.isEmpty()) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stop?.let { StopKey(it) }
            // You can send directly without opening the input box when the screen is hung.
            if (ui.screen != null) SendKey(true, actions::send, start = if (stop != null) 4.dp else 0.dp)
        }
        return
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (ui.draft.isEmpty()) Text(field.hint, style = Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                ui.draft, actions::draft,
                Modifier.testTag("capsule-field").fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.hint },
                textStyle = Body.copy(color = c.ink), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ui.canSend) actions.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || ui.canSend) SendKey(ui.canSend, actions::send, start = if (stop != null) 4.dp else 0.dp)
    }
}

/** The stop key on the right side of the input box: a square in a black round button, the same as the input field on the dialog page. */
@Composable
internal fun StopKey(onStop: () -> Unit) {
    val c = MinaTheme.colors
    Box(
        Modifier.testTag("capsule-stop").size(KeySize).clip(MinaShapes.Pill).background(c.ink)
            .clickable(role = Role.Button, onClickLabel = CardCopy.STOP, onClick = onStop)
            .semantics { contentDescription = CardCopy.STOP },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MinaIcons.Stop, null, Modifier.size(20.dp), tint = c.bg)
    }
}

private val KeySize = 34.dp

private const val TITLE_MAX = 12

@Composable
private fun cardWidth() = minOf(CardWidth, (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(240).dp)

/** When the bottom of the card is [fixed], it is [height] high (dialogue card), otherwise the content is high, up to [height] (task card). */
@Composable
private fun CardFrame(height: Dp, fixed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = MinaTheme.colors
    Column(
        Modifier.testTag("capsule-card").width(cardWidth()).then(if (fixed) Modifier.height(height) else Modifier.heightIn(max = height))
            .glass(c, CardShape, need = false).padding(12.dp),
        content = content,
    )
}

/** Title bar (press it to drag the card, see onHandle of [CapsuleCard]): Mina icon on the left, title and status in the middle, and collapse on the right. */
@Composable
private fun Header(title: String, sub: String, actions: CardActions, handle: Modifier) {
    val c = MinaTheme.colors
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MinaLogo(28.dp, corner = 14.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Sub, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(MinaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

/** The title bar of the idle card: "Chat with "..." · New conversation" or "New conversation · Continue with the last chat"; when there is no conversation, there is only "New conversation". */
@Composable
private fun IdleHeader(card: CardView.Chat, actions: CardActions, handle: Modifier) {
    val c = MinaTheme.colors
    val continuing = !card.fresh && card.conversationId != null
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MinaLogo(28.dp, corner = 14.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (continuing) tr(R.string.card_continuing, card.title.orEmpty().let(ScreenCopy::plain).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }) else CardCopy.NEW_CHAT,
                Modifier.weight(1f, fill = false), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (card.conversationId != null) {
                Text(" · ", style = Sub, color = c.ink3)
                TextLink(if (continuing) CardCopy.NEW_CHAT else CardCopy.CONTINUE_LAST) { actions.freshChat(continuing) }
            }
        }
        IconButton(MinaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

@Composable
private fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(MinaShapes.Pill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MinaTheme.colors.ink3)
    }
}

/** Task card: What you are currently operating on the app or web page; according to the height of the content, the middle section takes the one with the height of the small screen on the left and the text on the right (the step is at most as high as the small screen). */
@Composable
private fun Detail(
    card: CardView.Detail, preview: Bitmap?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit,
) {
    val c = MinaTheme.colors
    val r = card.run
    val sub = listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · ")
    CardFrame(height, fixed = false) {
        Header(ScreenCopy.plain(r.run.goal).ifBlank { "Harness Agent" }, sub, actions, handle)
        val screen = preview?.takeIf { CapsuleCards.showsScreen(card.content) }
        // The upper limit of the height of the step section = the height of the small screen; when there
        // is no screen, the same height is calculated according to the aspect ratio of the main screen,
        // and the screen will not jump when it comes.
        val config = LocalConfiguration.current
        val stepsMax = ScreenWidth / (screen?.let(::ratio) ?: ratio(config.screenWidthDp, config.screenHeightDp))
        Row(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (screen != null) MiniScreen(screen) { actions.zoom(true) }
            Column(Modifier.weight(1f)) {
                val content = card.content
                // The steps in the work are arranged separately (roll by yourself, at most as high as
                // the mini screen), there are no buttons for you to click.
                if (content is CardContent.Working) Steps(content.steps, stepsMax) else {
                    // If you can't fit the text, just scroll in this paragraph; put the button you want
                    // to click below, and don't scroll away with it.
                    Column(
                        Modifier.testTag("capsule-scroll").weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (content) {
                            // Stand alone on top while working
                            is CardContent.Working -> Unit
                            is CardContent.Takeover -> {
                                Text(content.need, style = Strong, color = c.warn)
                                if (content.body.isNotEmpty()) Text(content.body, style = Body, color = c.ink2)
                                content.queueLine?.let { Text(it, style = Sub, color = c.ink3) }
                            }
                            is CardContent.Confirm -> {
                                Text(content.title, style = Strong, color = c.warn)
                                Text(content.body, style = Body, color = c.ink2)
                                Text(ConfirmationCopy.NOTE, style = Sub, color = c.ink3)
                            }
                            is CardContent.Question -> Text(content.prompt, style = Body, color = c.ink)
                            is CardContent.Held -> Text(content.why, style = Body, color = c.ink2)
                            // What ended is the dialogue card, not here
                            is CardContent.Result -> Unit
                        }
                    }
                    Pinned(content, r.run.key, actions)
                }
            }
        }
        input()
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            TextLink(CardCopy.SEE_PROCESS) { actions.openMina(r.run.conversationId) }
        }
    }
}

/**
 * The card left on the page running in the background: the title "Background web page · 1 hour and 52
 * minutes left", the real-time picture (the latest frame displayed on it, click to enlarge), the site
 * and the conversation it comes from, below "Open conversation" and "Close web page". Press content
 * high. Walk every minute the rest of the time.
 */
@Composable
private fun KeptCard(card: CardView.Kept, preview: Bitmap?, actions: CardActions, height: Dp, handle: Modifier) {
    val c = MinaTheme.colors
    val p = card.page
    val now by produceState(System.currentTimeMillis(), p.id) {
        while (true) {
            delay(KEPT_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    CardFrame(height, fixed = false) {
        Header(tr(R.string.kept_card_title), tr(R.string.kept_card_left, KeptPageCopy.duration(p.closesAt - now)), actions, handle)
        Column(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) {
                val image = remember(preview) { preview.asImageBitmap() }
                Box(
                    Modifier.testTag("kept-live").fillMaxWidth().aspectRatio(ratio(preview)).clip(ScreenShape)
                        .border(1.dp, c.glassEdge, ScreenShape).background(Color.Black)
                        .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN) { actions.zoom(true) }
                        .semantics { contentDescription = tr(R.string.kept_card_live) },
                ) {
                    Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            Text(p.host, style = Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(tr(R.string.kept_card_from, ScreenCopy.plain(p.goal).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }), tr(R.string.kept_card_live))
                    .joinToString(" · "),
                style = Sub, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).testTag("kept-open")) { Action(tr(R.string.kept_open_chat), ButtonKind.Tonal) { actions.openMina(p.conversationId) } }
            // Close is a light red button with red letters, separate from other buttons
            Box(
                Modifier.weight(1f).height(40.dp).testTag("kept-close").clip(MinaShapes.Pill).background(c.badSoft)
                    .clickable(role = Role.Button, onClick = actions::closeKeptPage),
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.kept_close), style = Strong, color = c.bad) }
        }
    }
}

private const val KEPT_TICK_MS = 30_000L

/** The buttons you want to click (Accept, Agree, Options, Continue) are fixed under the middle section of the card and do not roll with it. */
@Composable
internal fun Pinned(content: CardContent?, key: String, actions: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content) {
            is CardContent.Takeover -> {
                Spacer(Modifier.height(0.dp))
                if (content.canTakeOver) Action(CardCopy.TAKE_OVER, ButtonKind.Primary) { actions.takeOver(key) }
                Action(CardCopy.STOP, ButtonKind.Tonal) { actions.stop(key) }
            }
            is CardContent.Confirm -> {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.APPROVE_ONCE_ONLY, ButtonKind.Primary) { actions.approve(key, content.key) }
                Action(CardCopy.DECLINE, ButtonKind.Tonal) { actions.decline(key, content.key) }
            }
            is CardContent.Question -> if (content.options.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                content.options.forEach { o -> Action(o, ButtonKind.Tonal) { actions.answer(key, o) } }
            }
            is CardContent.Held -> if (content.resumable) {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.RESUME, ButtonKind.Primary) { actions.resume(key) }
            }
            else -> Unit
        }
    }
}

/**
 * Dialogue card: The back and forth of the conversation scrolls in the middle; when you are asked to
 * answer or agree, the button is fixed below the back and forth, and below that is the input box and
 * "Return to Mina". When there is no task, it is an idle card (the title bar can open a new
 * conversation).
 */
@Composable
private fun ChatCard(
    card: CardView.Chat, thread: ChatThread?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit,
) {
    val r = card.run
    CardFrame(height) {
        if (r == null) IdleHeader(card, actions, handle)
        else Header(
            ScreenCopy.plain(thread?.title ?: r.run.goal).ifBlank { "Harness Agent" },
            listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · "),
            actions, handle,
        )
        Box(Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth()) {
            // When the conversation is changed (the idle card switches to a new conversation), read
            // again from the latest question of that conversation.
            if (thread != null) key(card.target) { ThreadList(thread, card.conversationId, actions) }
        }
        if (r != null) Pinned(card.content, r.run.key, actions)
        input()
        card.target?.let { id ->
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                TextLink(CardCopy.BACK_TO_MINA) { actions.openMina(id) }
            }
        }
    }
}

/**
 * Dialogue back and forth (oldest on top): When opening, the latest sentence you said will stop at the
 * top; scroll to the top and load a few more rounds, and the list will stop in place according to the
 * key of each item; words that are longer will only follow you down when you are already at the bottom
 * (same as the conversation page); scroll to it when a new question comes. [footer] comes after the last
 * one.
 */
@Composable
internal fun ThreadList(thread: ChatThread, conversationId: String?, actions: CardActions, footer: (@Composable () -> Unit)? = null) {
    val c = MinaTheme.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = thread.anchor)
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        following = !state.canScrollForward
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollForward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    val anchorKey = thread.items.getOrNull(thread.anchor)?.key
    var lastAnchor by remember { mutableStateOf(anchorKey) }
    LaunchedEffect(anchorKey) {
        if (anchorKey == lastAnchor) return@LaunchedEffect
        lastAnchor = anchorKey
        state.scrollToItem(thread.anchor)
    }
    // Answers are typeset in Markdown (parsed in the background during streaming), and the height
    // changes only after they are arranged: according to the number of items and the height of the last
    // item, not according to the number of words.
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.size
        }.collect { (n, _) ->
            if (following && !state.isScrollInProgress && n > 0) state.scrollToItem(n - 1, END_OFFSET)
        }
    }
    if (thread.hasEarlier) LaunchedEffect(thread.items.size) {
        snapshotFlow { state.firstVisibleItemIndex == 0 }.first { it }
        actions.loadEarlier()
    }
    val bodyInkStyle = remember(c) { Body.copy(color = c.ink) }
    val bodyInk2Style = remember(c) { Body.copy(color = c.ink2) }
    LazyColumn(Modifier.testTag("capsule-thread").fillMaxWidth().fillMaxHeight(), state = state) {
        items(
            thread.items,
            key = { it.key },
            contentType = { item ->
                when (item) {
                    is ThreadItem.User -> "user"
                    is ThreadItem.Note -> "note"
                    is ThreadItem.Answer -> "answer"
                }
            },
        ) { item ->
            when (item) {
                // The sentence with the screen: thumbnail and "Your screen at the moment · App ·
                // Moment", add another bubble with the postscript
                is ThreadItem.User -> BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    val maxWidth = maxWidth
                    Column(horizontalAlignment = Alignment.End) {
                        UserMessage(conversationId, item.text, ThreadThumbWidth) { text ->
                            Text(
                                text,
                                Modifier.widthIn(max = maxWidth * 0.8f).background(c.accent, BubbleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                                style = Body, color = c.onAccent,
                            )
                        }
                        // The destination of the inserted words (received, added, next thing)
                        item.note?.let { Text(it, Modifier.padding(top = 2.dp, end = 4.dp), style = Sub, color = c.ink3) }
                    }
                }
                is ThreadItem.Note -> Text(
                    item.text, Modifier.padding(bottom = 4.dp), style = Sub,
                    color = if (item.kind.needsYou) c.warn else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // Tables and code blocks can slide left and right in their own section without
                // stretching the card; links can be opened in separate tasks and can also be clicked in
                // the floating layer.
                is ThreadItem.Answer -> if (!item.cut) {
                    MarkdownText(item.text, bodyInkStyle, Modifier.fillMaxWidth().padding(bottom = 8.dp), streaming = item.streaming)
                } else Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    // The paragraph interrupted by the interruption: light color, a line of small words
                    // below (same sentence as the conversation page)
                    MarkdownText(item.text, bodyInk2Style, Modifier.fillMaxWidth(), streaming = false)
                    Text(tr(R.string.steer_interrupted_reply), Modifier.padding(top = 2.dp), style = Sub, color = c.ink3)
                }
            }
        }
        // The paragraph following the round trip (the current steps in the browser page panel): follow
        // the last one, and go down together when you reach the bottom.
        footer?.let { f -> item(key = FOOTER_KEY, contentType = "footer") { f() } }
    }
}

private const val FOOTER_KEY = "thread-footer"

/** The width of the screenshot thumbnail in the card. */
private val ThreadThumbWidth = 78.dp

/** When you reach the bottom, scroll to the last item and then go down so many pixels to get to the real bottom from the list. */
private const val END_OFFSET = 1_000_000

/**
 * Steps in working (the old one is on top): According to the content, the maximum is [max]. When you
 * can't put it down, the latest step stays at the bottom, and the old one is cut off at the top, fades
 * out at the top, and scrolls up to see the earlier one; when a new step comes, it is already at the
 * bottom and then scrolls down, and it is not pulled back when scrolling up (same as [ThreadList]).
 * [tags] and [steps] have a one-to-one correspondence: where is that step ("Webpage" "App"), written as
 * a small label in front of the step; if not, it is null.
 */
@Composable
internal fun Steps(steps: List<String>, max: Dp, tags: List<String?> = emptyList()) {
    val c = MinaTheme.colors
    // Arrange backwards: item 0 is the latest step, posted at the bottom
    val state = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollBackward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(steps.size) {
        if (following && !state.isScrollInProgress) state.scrollToItem(0)
    }
    val fade = with(LocalDensity.current) { FadeEdge.toPx() }
    val fadeBrush = remember(fade) { Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade) }
    LazyColumn(
        Modifier.testTag("capsule-steps").fillMaxWidth().heightIn(max = max)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // There is an earlier step above: top fade
                if (state.canScrollForward) drawRect(fadeBrush, blendMode = BlendMode.DstIn)
            },
        state = state,
        reverseLayout = true,
    ) {
        // Use the serial number from old to new as the key: when you turn up and look up, a new step
        // comes, and the steps you looked at remain where they are.
        items(steps.size, key = { steps.lastIndex - it }, contentType = { "capsule_step" }) { j ->
            val i = steps.lastIndex - j
            val current = j == 0
            StepItem(if (current) StepState.Current else StepState.Done, first = i == 0, last = current, dotY = 11.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    tags.getOrNull(i)?.let { t ->
                        Text(
                            t, Modifier.padding(top = 3.dp, end = 5.dp).background(c.accentSoft, StepTagShape).padding(horizontal = 5.dp),
                            style = StepTagTextStyle, color = c.accent, maxLines = 1,
                        )
                    }
                    Text(
                        steps[i], Modifier.padding(vertical = 2.dp), style = if (current) StepCurrentTextStyle else Step,
                        color = if (current) c.ink else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val StepTagShape = RoundedCornerShape(6.dp)
private val StepTagTextStyle = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
private val StepCurrentTextStyle = Step.copy(fontWeight = FontWeight.Medium)

private val FadeEdge = 28.dp

@Composable
private fun Action(text: String, kind: ButtonKind, onClick: () -> Unit) {
    MinaButton(text, onClick, Modifier.fillMaxWidth(), kind = kind, size = ButtonSize.Medium)
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        style = Sub.copy(fontSize = 13.sp), color = MinaTheme.colors.accent,
    )
}

/** Virtual screen small picture, click to enlarge. */
@Composable
private fun MiniScreen(frame: Bitmap, onZoom: () -> Unit) {
    val c = MinaTheme.colors
    val image = remember(frame) { frame.asImageBitmap() }
    Box(
        Modifier.testTag("capsule-screen").width(ScreenWidth).aspectRatio(ratio(frame)).clip(ScreenShape)
            .border(1.dp, c.glassEdge, ScreenShape).background(c.surface)
            .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN, onClick = onZoom)
            .semantics { contentDescription = CardCopy.SCREEN },
    ) {
        Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        Text(
            CardCopy.SCREEN_LABEL, Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp), color = Color.White,
        )
    }
}

/** Enlarged screen: Click to return to the card. */
@Composable
private fun Zoomed(frame: Bitmap, actions: CardActions) {
    val image = remember(frame) { frame.asImageBitmap() }
    val width = minOf(ZoomWidth, (LocalConfiguration.current.screenWidthDp * 0.72f).dp)
    Box(
        Modifier.testTag("capsule-zoom").width(width).aspectRatio(ratio(frame)).glass(MinaTheme.colors, CardShape, need = false)
            .clickable(role = Role.Image, onClickLabel = CardCopy.ZOOMED) { actions.zoom(false) }
            .semantics { contentDescription = CardCopy.ZOOMED },
    ) {
        Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        Text(
            CardCopy.ZOOM_HINT, Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = Sub, color = Color.White,
        )
    }
}

/** The aspect ratio of the picture is within a reasonable range (to prevent abnormal sizes from damaging the card). */
private fun ratio(b: Bitmap): Float = ratio(b.width, b.height)

private fun ratio(width: Int, height: Int): Float = if (height <= 0) 0.45f else (width.toFloat() / height).coerceIn(0.3f, 1.8f)
