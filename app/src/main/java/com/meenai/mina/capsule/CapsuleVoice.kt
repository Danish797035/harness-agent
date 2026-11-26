package com.meenai.mina.capsule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.ChatCopy
import com.meenai.mina.ui.Spinner
import com.meenai.mina.ui.VoiceCopy
import com.meenai.mina.ui.VoiceWave
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.theme.DarkColors
import com.meenai.mina.ui.theme.LocalHarnessColors
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.voice.HoldToTalk
import com.meenai.mina.voice.SpeechEngineSpec
import com.meenai.mina.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Press and hold the floating capsule to speak: where your finger falls when you release it
 * ([CapsuleVoice.drop]), the sentence that flashes on the capsule after uttering it
 * ([CapsuleVoice.sent]), the conversation and interface status of a conversation ([CapsuleTalk], the
 * state machine uses the same [HoldToTalk] as the input field), and the panel that floats next to the
 * capsule when you press and hold it ([CapsuleVoicePanel]: to whom, real-time words, waveforms, two
 * landing points of "cancel" and "change"). After talking about who to hand it to and how to send it, it
 * is up to [CapsuleController] (it is the same as double-clicking to send the screen to find the
 * target).
 */
object CapsuleVoice {
    /** When releasing: send it out directly, open the card and change it, or throw it away. */
    enum class Drop { Send, Edit, Cancel }

    /** The sentence flashed on the capsule contains at most a few words. */
    private const val SENT_CHARS = 12

    /**
     * The position of the finger on the screen ([x], [y]) falls on: the two circles expand [slopPx]
     * respectively, and the rest are sent. It is not counted as a hit when the circle has not been
     * arranged (empty) to avoid misjudgment when the panel just comes out.
     */
    fun drop(x: Float, y: Float, cancel: Rect, edit: Rect, slopPx: Int): Drop {
        fun Rect.hit() = !isEmpty && x >= left - slopPx && x <= right + slopPx && y >= top - slopPx && y <= bottom + slopPx
        return when {
            cancel.hit() -> Drop.Cancel
            edit.hit() -> Drop.Edit
            else -> Drop.Send
        }
    }

    /** After sending out the sentence on the capsule: interject, answer or send out, followed by the first few words of what was said. */
    fun sent(kind: CardInputKind, text: String): String {
        val head = text.trim().let { if (it.length > SENT_CHARS) it.take(SENT_CHARS) + "…" else it }
        return when (kind) {
            CardInputKind.Steer -> tr(R.string.capsule_voice_steered, head)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answered, head)
            CardInputKind.Continue, CardInputKind.Start -> tr(R.string.capsule_voice_sent, head)
        }
    }

    /** The sentence at the top of the panel: Tell it to someone. It is consistent with who the card input box is given to; there is no input box (requiring you to accept and agree) or "listening" when opening a new conversation. */
    fun title(card: CardView?): String {
        val input = card?.let(CapsuleCards::input)?.kind ?: return tr(R.string.voice_listening)
        val goal = when (card) {
            is CardView.Detail -> card.run.run.goal
            is CardView.Chat -> card.run?.run?.goal ?: card.title.takeIf { card.target != null }
            is CardView.Kept -> null
        }?.takeIf { it.isNotBlank() }
        return when (input) {
            CardInputKind.Steer -> goal?.let { tr(R.string.voice_steer_to, it) } ?: tr(R.string.voice_listening)
            CardInputKind.Answer -> tr(R.string.capsule_voice_answer_title)
            CardInputKind.Continue, CardInputKind.Start -> goal?.let { tr(R.string.capsule_voice_continue_title, it) } ?: tr(R.string.voice_listening)
        }
    }
}

/**
 * The last time the capsule spoke: start a conversation ([begin]), receive events, push the timing when
 * pressed, calculate the landing point when the finger moves ([move]), release ([finish]). When pressing
 * with your finger ([byTouch]), the landing point follows your finger; the "speak" of reading the screen
 * does not have fingers, it can only be emitted. After saying ([HoldToTalk.State.Done],
 * [HoldToTalk.State.Problem]), hand it over to [onResult], together with the landing point when
 * released. The session ends first while the hand is still pressed, and the result is retained by
 * [HoldToTalk]. When the finger is removed, it will be processed according to the landing point at that
 * time (throw it away when canceling, change it to open the card, and send the rest); there are no
 * fingers in reading the screen, and it will be "sent" as soon as the session ends. After releasing it,
 * turn it again and press it again: I don’t want the old part anymore and start listening again. Only
 * used on the main thread.
 */
internal class CapsuleTalk(
    private val context: Context,
    private val voice: () -> VoiceSource,
    private val scope: CoroutineScope,
    private val onResult: (CapsuleVoice.Drop, HoldToTalk.State) -> Unit,
) {
    /** [begin] result: It started, or why it didn’t start. */
    sealed interface Begin {
        data object Started : Begin
        data object NoPermission : Begin
        data class Unavailable(val why: SpeechEngineSpec.Why) : Begin
    }

    // "Too short" is calculated from finger presses: HOLD_MS has been pressed when starting to listen,
    // and the state machine only counts the remaining ones.
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** The panel is showing (it is rotating after pressing or releasing it). */
    var active by mutableStateOf(false)
        private set
    /** Released (or "sent" in screen reading), waiting for words. */
    var released by mutableStateOf(false)
        private set
    var byTouch by mutableStateOf(true)
        private set
    var aim by mutableStateOf(CapsuleVoice.Drop.Send)
        private set
    var title by mutableStateOf("")
    var engine by mutableStateOf("")
        private set

    /** The positions of the two landing points on the screen will be reported after the panel is arranged. */
    var cancelAt = Rect.Zero
    var editAt = Rect.Zero

    private var drop: CapsuleVoice.Drop? = null
    private var collector: Job? = null
    private var ticker: Job? = null
    private var watcher: Job? = null
    private var startedAt = 0L
    private var lastHeard = ""
    private var lastSpeechActivityAt = 0L

    /** Can it be used? Do you have recording permission? Start listening if it works. The capsule is not an Activity and cannot open the system's permission box. */
    fun begin(title: String, byTouch: Boolean): Begin {
        if (active && !released) return Begin.Started
        val source = voice()
        val spec = source.spec()
        if (spec is SpeechEngineSpec.Unavailable) return Begin.Unavailable(spec.why)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return Begin.NoPermission
        // The previous paragraph is still being transferred after being released (the service provider
        // is stuck, or the built-in recognition is at the end): the user presses again, and the previous
        // paragraph is no longer needed; the session is canceled by press
        if (active) stop()
        hold.reset()
        val session = source.start()
        this.title = title
        this.byTouch = byTouch
        engine = VoiceCopy.engine(spec)
        aim = CapsuleVoice.Drop.Send
        drop = null
        released = false
        cancelAt = Rect.Zero
        editAt = Rect.Zero
        startedAt = SystemClock.elapsedRealtime()
        lastHeard = ""
        lastSpeechActivityAt = 0L
        hold.press(session)
        active = true
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
        watcher = scope.launch {
            hold.state.collect { s ->
                if (s is HoldToTalk.State.Listening) {
                    val heard = (s.stable + s.tail).trim()
                    if (heard.isNotEmpty() && (heard != lastHeard || s.level >= SPEECH_ACTIVE_LEVEL)) {
                        lastHeard = heard
                        lastSpeechActivityAt = SystemClock.elapsedRealtime()
                    }
                }
                // There are no fingers to wait for screen reading: when the conversation ends by itself,
                // just press "Send"
                if (s is HoldToTalk.State.Listening && s.ended && !this@CapsuleTalk.byTouch) finish(CapsuleVoice.Drop.Send)
                if (s is HoldToTalk.State.Done || s is HoldToTalk.State.Problem) {
                    val d = drop ?: aim
                    stop()
                    hold.reset()
                    onResult(d, s)
                }
            }
        }
        ticker = scope.launch {
            while (active && !released) {
                delay(TICK_MS)
                val nowMs = SystemClock.elapsedRealtime()
                val silenceAutoSend = !this@CapsuleTalk.byTouch &&
                    lastHeard.isNotEmpty() &&
                    lastSpeechActivityAt > 0L &&
                    nowMs - lastSpeechActivityAt >= AUTO_SEND_SILENCE_MS
                // When the point is reached, press the landing point at this moment to end (the state
                // machine will release itself when it reaches the point, but it does not know the
                // landing point)
                if (nowMs - startedAt >= MAX_MS || silenceAutoSend) {
                    finish(if (silenceAutoSend) CapsuleVoice.Drop.Send else aim)
                } else {
                    hold.tick()
                }
            }
        }
        return Begin.Started
    }

    /** The finger moved to ([x],[y]) on the screen. */
    fun move(x: Float, y: Float, slopPx: Int) {
        if (!active || released || !byTouch) return
        aim = CapsuleVoice.drop(x, y, cancelAt, editAt, slopPx)
        // The state machine also needs to know whether the finger is on the cancel button: an error
        // occurs when the session is pressed, and there will be no prompt when it stops on the cancel
        // button.
        hold.armCancel(aim == CapsuleVoice.Drop.Cancel)
    }

    /** Release (or "send" in screen reading): if you cancel, throw it away, otherwise you will end with words such as "end". */
    fun finish(at: CapsuleVoice.Drop) {
        if (!active || released) return
        if (at == CapsuleVoice.Drop.Cancel) {
            abort()
            return
        }
        aim = at
        drop = at
        released = true
        hold.armCancel(false)
        hold.release()
    }

    /** This section is no longer needed (drag until cancelled, capsule removed, touch taken away by the system). Can be adjusted repeatedly. */
    fun abort() {
        stop()
        hold.dispose()
    }

    private fun stop() {
        active = false
        released = false
        drop = null
        lastHeard = ""
        lastSpeechActivityAt = 0L
        ticker?.cancel()
        ticker = null
        watcher?.cancel()
        watcher = null
        collector?.cancel()
        collector = null
    }

    companion object {
        /** It takes this long to count as a long press (milliseconds). */
        const val HOLD_MS = 400L
        /** Counting from the time the finger was pressed down, it took less than this long to release it, so it was assumed that the sound was not heard clearly. */
        const val MIN_TOTAL_MS = 500L
        const val MAX_MS = 60_000L
        const val AUTO_SEND_SILENCE_MS = 2_000L
        private const val SPEECH_ACTIVE_LEVEL = 0.45f
        private const val TICK_MS = 100L
    }
}

/** How far are the panel and the two circles from the capsule and each other; how big are the two circles? */
private val PanelGap = 16.dp
private val TargetsGap = 18.dp
private val TargetSize = 62.dp
private val PanelShape = RoundedCornerShape(24.dp)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val SaidText = TextStyle(fontSize = 17.5.sp, lineHeight = 26.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
private val TargetText = TextStyle(
    fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
    shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), 3f),
)
/** The apps behind are dimmed enough to keep focus and legibility on the floating card. */
private const val DIM = 0.42f
private const val VOICE_CARD_ALPHA = 0.94f

/**
 * Press and hold the layer of the entire screen when the capsule is talking (without touching it): the
 * back is slightly darkened, and the panel is close to the side of the capsule
 * ([anchorTop]..[anchorBottom], screen ordinate). If you can place it, it is above the capsule,
 * otherwise it is below; the two landing points are on the outside of the panel (further away from the
 * capsule), and drag your fingers over them. [insetTop]/[insetBottom] is the height of the status bar
 * and bottom gesture bar. After the two circles are arranged, report the position on the screen to
 * [talk].
 */
@Composable
internal fun CapsuleVoicePanel(talk: CapsuleTalk, anchorTop: Int, anchorBottom: Int, insetTop: Int, insetBottom: Int) {
    if (!talk.active) return
    val c = MinaTheme.colors
    val state by talk.hold.state.collectAsState()
    val view = LocalView.current
    val dim by animateFloatAsState(DIM, if (MinaTheme.reduceMotion) snap() else tween(200), label = "voice-dim")
    var below by remember { mutableStateOf(false) }
    // The position of the circle on the screen: the coordinates within the window plus the position of
    // the window on the screen
    fun Rect.onScreen(): Rect {
        val at = IntArray(2).also(view::getLocationOnScreen)
        return translate(Offset(at[0].toFloat(), at[1].toFloat()))
    }
    Layout(
        content = {
            Targets(talk, show = talk.byTouch && !talk.released) { cancel, edit ->
                talk.cancelAt = cancel.onScreen()
                talk.editAt = edit.onScreen()
            }
            Panel(talk, state, below)
        },
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).testTag("capsule-voice"),
    ) { measurables, cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val targets = measurables[0].measure(loose)
        val panel = measurables[1].measure(loose)
        val gap = PanelGap.roundToPx()
        val between = TargetsGap.roundToPx()
        val total = targets.height + between + panel.height
        val top = insetTop + gap
        val bottom = cons.maxHeight - insetBottom - gap
        // If the capsule can fit on it, place it on the top (two circles on top), otherwise put it on
        // the bottom (two circles on bottom)
        val goBelow = anchorTop - gap - total < top
        if (goBelow != below) below = goBelow
        val groupTop = (if (goBelow) anchorBottom + gap else anchorTop - gap - total)
            .coerceAtMost(bottom - total).coerceAtLeast(top)
        layout(cons.maxWidth, cons.maxHeight) {
            if (goBelow) {
                panel.place((cons.maxWidth - panel.width) / 2, groupTop)
                targets.place((cons.maxWidth - targets.width) / 2, groupTop + panel.height + between)
            } else {
                targets.place((cons.maxWidth - targets.width) / 2, groupTop)
                panel.place((cons.maxWidth - panel.width) / 2, groupTop + targets.height + between)
            }
        }
    }
}

/** "Cancel" and "Change" two circles; the one you move your finger up enlarges and changes color. After releasing it, hide it while turning (the position is still retained and the panel does not jump). */
@Composable
private fun Targets(talk: CapsuleTalk, show: Boolean, onPlaced: (Rect, Rect) -> Unit) {
    // The two circles have their respective positions (coordinates in the window) arranged; only report
    // when both are present.
    val at = remember { arrayOf(Rect.Zero, Rect.Zero) }
    Row(
        Modifier.alpha(if (show) 1f else 0f),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        Target(MinaIcons.Close, stringResource(R.string.capsule_voice_cancel), hot = show && talk.aim == CapsuleVoice.Drop.Cancel, bad = true) {
            at[0] = it
            if (!at[1].isEmpty) onPlaced(at[0], at[1])
        }
        Target(MinaIcons.Edit, stringResource(R.string.capsule_voice_edit), hot = show && talk.aim == CapsuleVoice.Drop.Edit, bad = false) {
            at[1] = it
            if (!at[0].isEmpty) onPlaced(at[0], at[1])
        }
    }
}

@Composable
private fun Target(icon: ImageVector, label: String, hot: Boolean, bad: Boolean, onPlaced: (Rect) -> Unit) {
    val c = MinaTheme.colors
    val scale by animateFloatAsState(if (hot) 1.18f else 1f, if (MinaTheme.reduceMotion) snap() else tween(150), label = "target")
    val fill = when {
        !hot -> c.surface.copy(alpha = 0.95f)
        bad -> c.bad
        else -> c.accent
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(TargetSize)
                .onGloballyPositioned { onPlaced(it.boundsInWindow()) }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glass(c, CircleShape, need = false)
                .background(fill, CircleShape)
                .testTag(if (bad) "capsule-voice-cancel" else "capsule-voice-edit"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (hot) c.onAccent else c.ink)
        }
        Text(label, style = TargetText, color = Color.White, maxLines = 1)
    }
}

/** Panel: The top is the recording point, who is speaking to, which text to use, and the time used; the real-time words in the middle; the waveform; and what happens when you release it at the bottom. */
@Composable
private fun Panel(talk: CapsuleTalk, state: HoldToTalk.State, below: Boolean) {
    val baseColors = MinaTheme.colors
    val panelColors = if (baseColors.isDark) baseColors else DarkColors
    CompositionLocalProvider(LocalHarnessColors provides panelColors) {
        val c = MinaTheme.colors
        val listening = state as? HoldToTalk.State.Listening
        val converting = talk.released || state is HoldToTalk.State.Converting
        val aim = if (converting) CapsuleVoice.Drop.Send else talk.aim
        val cancel = aim == CapsuleVoice.Drop.Cancel
        val edit = aim == CapsuleVoice.Drop.Edit
        Column(
            Modifier.padding(horizontal = 14.dp).widthIn(max = 520.dp).fillMaxWidth()
                .shadow(10.dp, PanelShape, clip = false, ambientColor = c.floatShadow.copy(alpha = 0.35f), spotColor = c.floatShadow.copy(alpha = 0.4f))
                .clip(PanelShape)
                .background(c.surface.copy(alpha = VOICE_CARD_ALPHA))
                .border(if (cancel) 1.5.dp else 0.75.dp, if (cancel) c.bad else c.glassEdge, PanelShape)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                .testTag("capsule-voice-panel"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dot = when {
                    cancel -> c.bad
                    edit || converting -> c.ink2
                    else -> c.accent
                }
                Box(Modifier.size(8.dp).drawBehind { drawCircle(dot) })
                Text(talk.title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (talk.engine.isNotEmpty()) {
                    Text(
                        talk.engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ChipText, color = c.ink2, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                listening?.let { Text(ChatCopy.clock(it.elapsedMs), style = TimeText, color = c.ink3) }
            }
            val heard = listening?.let { it.stable + it.tail }.orEmpty()
            val words = buildAnnotatedString {
                if (heard.isEmpty()) {
                    val ghost = when {
                        converting -> ""
                        listening?.live == true -> stringResource(R.string.voice_say_now)
                        else -> stringResource(R.string.voice_recording)
                    }
                    withStyle(SpanStyle(color = c.ink3)) { append(ghost) }
                } else {
                    withStyle(SpanStyle(color = c.ink)) { append(listening?.stable.orEmpty()) }
                    withStyle(SpanStyle(color = c.ink2)) { append(listening?.tail.orEmpty()) }
                }
            }
            if (converting && heard.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).testTag("capsule-voice-converting"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Spinner(Modifier.size(15.dp), spinning = !MinaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
                    Text(stringResource(R.string.voice_converting), style = SaidText, color = c.ink2, maxLines = 1)
                }
            } else {
                Text(
                    words,
                    Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).alpha(if (cancel) 0.4f else 1f),
                    style = if (cancel) SaidText.copy(textDecoration = TextDecoration.LineThrough) else SaidText,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!converting) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    VoiceWave(listening?.level ?: 0f, flat = cancel, reduceMotion = MinaTheme.reduceMotion, color = if (cancel || edit) c.ink3 else c.accent)
                }
            }
            if (talk.byTouch && !converting) {
                val foot = when {
                    cancel -> stringResource(R.string.capsule_voice_foot_cancel)
                    edit -> stringResource(R.string.capsule_voice_foot_edit)
                    listening?.live == true -> stringResource(if (below) R.string.capsule_voice_foot_send_down else R.string.capsule_voice_foot_send_up)
                    else -> stringResource(if (below) R.string.capsule_voice_foot_convert_down else R.string.capsule_voice_foot_convert_up)
                }
                Text(foot, Modifier.fillMaxWidth().padding(top = 8.dp), style = FootText, color = c.ink3, maxLines = 2)
            }
            if (!talk.byTouch || talk.released) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MinaButton(
                        text = stringResource(R.string.capsule_voice_cancel),
                        onClick = { talk.abort() },
                        modifier = Modifier.weight(1f).testTag("capsule-voice-btn-cancel"),
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Medium,
                    )
                    if (!talk.released) {
                        MinaButton(
                            text = stringResource(R.string.capsule_voice_send),
                            onClick = { talk.finish(CapsuleVoice.Drop.Send) },
                            modifier = Modifier.weight(1f).testTag("capsule-voice-btn-send"),
                            kind = ButtonKind.Primary,
                            size = ButtonSize.Medium,
                            enabled = heard.isNotBlank(),
                        )
                    }
                }
            }
        }
    }
}
