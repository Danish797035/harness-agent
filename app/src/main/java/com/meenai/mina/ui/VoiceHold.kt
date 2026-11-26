package com.meenai.mina.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GlassBackdrop
import com.meenai.mina.ui.components.GlassBar
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.floatShadow
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.voice.HoldToTalk
import com.meenai.mina.voice.SpeechEngineSpec
import com.meenai.mina.voice.SpeechFailure
import com.meenai.mina.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Press and hold to speak in the dialogue input field: the gesture on the input box ([holdToTalk]), the
 * entire input field that turns blue when pressed ([VoiceBarCover]), the "Listening" card above the
 * input field and the swipe up to cancel the prompt, the light prompt such as not being heard clearly
 * ([VoiceFloat]), "voice input is not yet available" and the description of the recording permission
 * ([VoiceSheetHost]). The status is all in [HoldToTalk], only drawing and wiring are done here.
 */

/** Press and hold for about this long (milliseconds) to start listening; if not, just click and type. */
private const val HOLD_MS = 300L

/** Counting from the time when your finger is pressed down, if it takes less than this time (milliseconds) to release it, it is regarded as an accidental touch and failure to hear clearly. */
private const val MIN_TOTAL_MS = 500L

/** The most you can listen to at one time is this long; the last [COUNTDOWN_MS] card counts down in the lower right corner. */
private const val MAX_MS = 60_000L
private const val COUNTDOWN_MS = 10_000L

/** Sliding your finger up this much means "release to cancel". */
private val CancelLift = 80.dp

/** Bottom elastic layer: Which description. */
internal sealed interface VoiceSheet {
    data object Permission : VoiceSheet
    /** Recording has been allowed, but the recognition service still says that there is no permission or there is a recording error: it means that the system's microphone master switch and permission management are occupied by other applications. */
    data object MicBlocked : VoiceSheet
    data class Unavailable(val why: SpeechEngineSpec.Why) : VoiceSheet
}

/** Press and hold the text you want on the interface when speaking; do not touch the interface, just test directly. */
internal object VoiceCopy {
    /** The small label on the top of the card: Which text conversion was used. */
    fun engine(spec: SpeechEngineSpec): String = when (spec) {
        is SpeechEngineSpec.Doubao -> tr(R.string.speech_doubao_model)
        is SpeechEngineSpec.System -> tr(if (spec.onDevice) R.string.voice_engine_phone_offline else R.string.voice_engine_phone)
        is SpeechEngineSpec.Provider -> spec.model
        is SpeechEngineSpec.Unavailable -> ""
    }

    /** The sentence above the input field when it fails: Permissions and engine cannot be used. Normally go to the explanation popup. Here is just one sentence to give you the bottom line. */
    fun failure(why: SpeechFailure): String = when (why) {
        SpeechFailure.Busy -> tr(R.string.voice_busy)
        SpeechFailure.Network -> tr(R.string.voice_network)
        SpeechFailure.MicUnavailable, SpeechFailure.AudioError -> tr(R.string.voice_mic_unavailable)
        // The service provider's error is the same as when the model is wrong (amount, key, model cannot
        // be found...)
        is SpeechFailure.Provider -> tr(R.string.voice_provider_failed, why.error.message.orEmpty())
        SpeechFailure.NoPermission -> tr(R.string.voice_permission_title)
        is SpeechFailure.Unavailable -> tr(R.string.voice_unavailable_title)
    }

    val NO_MATCH get() = tr(R.string.voice_no_match)

    fun unavailable(why: SpeechEngineSpec.Why): String = when (why) {
        SpeechEngineSpec.Why.DoubaoNotConfigured -> tr(R.string.speech_doubao_missing)
        SpeechEngineSpec.Why.NoSystemNoModel -> tr(R.string.voice_unavailable_no_engine)
        SpeechEngineSpec.Why.SystemGone -> tr(R.string.voice_unavailable_system_gone)
        SpeechEngineSpec.Why.SystemNoResponse -> tr(R.string.voice_unavailable_system_silent)
        SpeechEngineSpec.Why.ProviderGone -> tr(R.string.voice_unavailable_provider_gone)
        SpeechEngineSpec.Why.Off -> tr(R.string.voice_unavailable_off)
    }

    /** Countdown to the last ten seconds; null if still early. */
    fun countdown(elapsedMs: Long): String? {
        val left = MAX_MS - elapsedMs
        if (left > COUNTDOWN_MS) return null
        return tr(R.string.voice_left, ((left + 999) / 1000).toInt().coerceAtLeast(0))
    }
}

/** Signs that the recording permission has been denied: When the system no longer pops up the box (it has been rejected and there should be no further explanation), change the popup description and click the button to go directly to the App's permission page. */
private object MicPermission {
    private const val PREFS = "voice_input"
    private const val DENIED = "mic_denied"

    fun denied(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DENIED, false)

    fun record(context: Context, granted: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DENIED, !granted).apply()
}

/**
 * Press and hold the input field once to speak the interface status: whether your finger is still
 * pressed ([holding]), the engine label on the card, the pop-up layer and the light prompt. Opening a
 * session, transferring events, and finishing are all left to [hold].
 */
@Stable
internal class VoiceHold(private val voice: VoiceSource, private val scope: CoroutineScope) {
    // "Too short" is less than half a second when you press your finger: HOLD_MS has been pressed when
    // you start listening, and the state machine only counts the remaining ones.
    val hold = HoldToTalk(clock = SystemClock::elapsedRealtime, maxMs = MAX_MS, minMs = MIN_TOTAL_MS - HOLD_MS)

    /** After long pressing to start, your finger has not left. */
    var holding by mutableStateOf(false)
        private set
    var engine by mutableStateOf("")
        private set
    var sheet by mutableStateOf<VoiceSheet?>(null)
    var toast by mutableStateOf<String?>(null)

    /** Returns true if there is recording permission; if there is no recording permission, it will ask for it (or play the explanation) and return false, and it will not start this time. Installed by the interface (requires Activity). */
    internal var ensureMic: () -> Boolean = { true }
    internal var haptic: () -> Unit = {}
    private var collector: Job? = null

    /**
     * Long press the time: first check whether it can be used and whether there is permission, and then
     * start listening. It is still spinning after releasing the previous section (the service provider
     * is stuck, or the built-in recognition is at the end). You can also press it again: the previous
     * section is no longer needed and will be canceled by [HoldToTalk.press].
     */
    fun begin() {
        if (holding) return
        toast = null
        hold.reset()
        val spec = voice.spec()
        if (spec is SpeechEngineSpec.Unavailable) {
            sheet = VoiceSheet.Unavailable(spec.why)
            return
        }
        if (!ensureMic()) return
        haptic()
        val session = voice.start()
        engine = VoiceCopy.engine(spec)
        hold.press(session)
        holding = true
        collector?.cancel()
        collector = scope.launch { session.events.collect { hold.onEvent(it) } }
    }

    fun drag(upPx: Float, thresholdPx: Float) {
        if (holding) hold.drag(upPx, thresholdPx)
    }

    /** Finger leaves: handed over to the state machine (cancel, too short, end). */
    fun end() {
        if (!holding) return
        holding = false
        hold.release()
    }

    /** Interrupted gestures, leaving the page, turning the screen, and the app going to the background: this section is no longer needed. */
    fun abort() {
        holding = false
        hold.dispose()
        collector?.cancel()
        collector = null
    }
}

private val IdleState = MutableStateFlow<HoldToTalk.State>(HoldToTalk.State.Idle)

/**
 * The [VoiceHold] requested in the input field; if there is no [voice] or the user selects "Not Used" in
 * the settings, it will be null (the input box can only be typed). Give the finished words to [onText].
 */
@Composable
internal fun rememberVoiceHold(voice: VoiceSource?, onText: (String) -> Unit): VoiceHold? {
    val off = remember(voice) {
        val spec = voice?.spec()
        voice == null || (spec is SpeechEngineSpec.Unavailable && spec.why == SpeechEngineSpec.Why.Off)
    }
    val scope = rememberCoroutineScope()
    val vh = remember(voice, off) { if (off || voice == null) null else VoiceHold(voice, scope) } ?: return null
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        MicPermission.record(context, granted)
    }
    vh.haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    vh.ensureMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
        else {
            // You only need to press and hold for the first time to ask for permission; when it is
            // rejected and the system no longer pops up the box, change it to description + go to system
            // settings
            val activity = context.findActivity()
            val blocked = MicPermission.denied(context) && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            if (blocked) vh.sheet = VoiceSheet.Permission else launcher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
    val state by vh.hold.state.collectAsState()
    LaunchedEffect(state) {
        when (val s = state) {
            is HoldToTalk.State.Done -> {
                onText(s.text)
                vh.hold.reset()
            }
            is HoldToTalk.State.Problem -> {
                when (val why = s.why) {
                    null -> vh.toast = VoiceCopy.NO_MATCH
                    is SpeechFailure.Unavailable -> vh.sheet = VoiceSheet.Unavailable(why.why)
                    // The recording permission has not been given yet: Still pop up the system box or
                    // "go to settings". Already given, it still reports that there is no permission, or
                    // the recognition service reports a recording error: additional explanation (system
                    // microphone master switch, permission management). In other situations where the
                    // microphone cannot be turned on, it will still prompt that it may be occupied.
                    SpeechFailure.NoPermission -> if (vh.ensureMic()) vh.sheet = VoiceSheet.MicBlocked
                    SpeechFailure.AudioError -> vh.sheet = VoiceSheet.MicBlocked
                    else -> vh.toast = VoiceCopy.failure(why)
                }
                vh.hold.reset()
            }
            else -> Unit
        }
    }
    // When pressed, the interface will push you away: time, and end in 60 seconds (the recording will
    // stop when the point is reached, otherwise the words will be lost if you don’t end it)
    val listening = state is HoldToTalk.State.Listening
    LaunchedEffect(listening) {
        while (listening) {
            delay(100)
            vh.hold.tick()
        }
    }
    LaunchedEffect(vh.toast) {
        val t = vh.toast ?: return@LaunchedEffect
        delay(if (t == VoiceCopy.NO_MATCH) 2_000 else 3_500)
        vh.toast = null
    }
    // Leave the page, switch screens, and go to the background of the app: discard the paragraph you are
    // talking about.
    LifecycleStartEffect(vh) { onStopOrDispose { vh.abort() } }
    return vh
}

/** The status of [vh] at this moment; without [vh], it is always normal. */
@Composable
internal fun voiceState(vh: VoiceHold?): HoldToTalk.State = (vh?.hold?.state ?: IdleState).collectAsState().value

/**
 * Gestures covering the unfocused input box: Click to continue typing ([onTap] focuses on playing the
 * keyboard), press and hold for about 0.3 seconds to start listening, press and slide up [CancelLift] to
 * cancel, release and hand over to the state machine.
 */
@Composable
internal fun Modifier.holdToTalk(vh: VoiceHold, onTap: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { CancelLift.toPx() }
    return pointerInput(vh, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // true: click; false: if the finger moves or disappears, it is not counted as ours; null:
            // when the finger is full, start listening
            val tapped = withTimeoutOrNull<Boolean>(HOLD_MS) {
                var result: Boolean? = null
                while (result == null) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    result = when {
                        ch == null -> false
                        !ch.pressed -> { ch.consume(); true }
                        (ch.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                        else -> null
                    }
                }
                result
            }
            when (tapped) {
                true -> { onTap(); return@awaitEachGesture }
                false -> return@awaitEachGesture
                null -> Unit
            }
            vh.begin()
            if (!vh.holding) return@awaitEachGesture
            var ended = false
            try {
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) {
                        ch?.consume()
                        ended = true
                        vh.end()
                        break
                    }
                    ch.consume()
                    vh.drag(down.position.y - ch.position.y, threshold)
                }
            } finally {
                if (!ended) vh.abort()
            }
        }
    }
}

/** The entire input field turns blue when pressed (turns red when slid up to cancel), with the waveform in the middle and "release to fill"; cover the glass input field. */
@Composable
internal fun VoiceBarCover(
    vh: VoiceHold,
    state: HoldToTalk.State,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val s = state as? HoldToTalk.State.Listening
    if (!vh.holding || s == null) return
    val c = MinaTheme.colors
    val label = when {
        s.cancelArmed -> stringResource(R.string.voice_release_cancel)
        s.live -> stringResource(R.string.voice_release_fill)
        else -> stringResource(R.string.voice_release_convert)
    }
    Row(
        modifier.clip(shape).background(if (s.cancelArmed) c.bad else c.accent).testTag("voice-bar"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceWave(s.level, flat = s.cancelArmed, reduceMotion = reduceMotion, color = c.onAccent)
        Text(label, style = BarLabel, color = c.onAccent, maxLines = 1)
    }
}

/** Waveform: 14 thin bars, the height follows the volume; when the animation is turned off, it only follows the volume and does not move by itself. The input field is shared with the floating capsule's speaking panel. */
@Composable
internal fun VoiceWave(level: Float, flat: Boolean, reduceMotion: Boolean, color: Color) {
    val loud by animateFloatAsState(if (flat) 0f else level.coerceIn(0f, 1f), if (reduceMotion) snap() else tween(120), label = "wave-level")
    val phase = if (reduceMotion || flat) 0f else rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "wave-phase",
    ).value
    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(WAVE_BARS) { k ->
            val peak = 8f + kotlin.math.abs(kotlin.math.sin(k * 1.7f)) * 14f
            val swing = if (reduceMotion) 1f else 0.55f + 0.45f * kotlin.math.sin(phase + k * 0.9f)
            val h = if (flat) 4f else 5f + (peak - 5f) * (0.25f + 0.75f * loud) * swing
            Box(Modifier.width(3.dp).height(h.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

private const val WAVE_BARS = 14

/**
 * The layer that floats above the input field and does not occupy the layout (placed in front of the
 * input field, the height is 0, and drawn upward): "Swipe up to cancel" prompt when pressed, "Listening"
 * card, and light prompts such as not hearing clearly. [title] is the sentence at the top of the card
 * (usually "listening", when the task is running, "interjecting to "..."").
 */
@Composable
internal fun VoiceFloat(vh: VoiceHold, state: HoldToTalk.State, title: String, backdrop: GlassBackdrop?, reduceMotion: Boolean) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val listening = state as? HoldToTalk.State.Listening
    // The animation of the card being folded also needs to draw the content of the last moment.
    val last = remember { arrayOfNulls<HoldToTalk.State.Listening>(1) }
    if (listening != null) last[0] = listening
    Box(
        Modifier.fillMaxWidth().layout { m, cons ->
            val p = m.measure(cons.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(cons.maxWidth, 0) { p.place(0, -p.height - gap) }
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val armed = listening?.cancelArmed == true
            AnimatedVisibility(vh.holding && listening != null, enter = fadeIn(tween(if (reduceMotion) 0 else 200)), exit = fadeOut(tween(if (reduceMotion) 0 else 150))) {
                CancelHint(armed, Modifier.padding(bottom = 14.dp))
            }
            AnimatedVisibility(
                listening != null,
                enter = if (reduceMotion) fadeIn(snap()) else
                    fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 12 } + scaleIn(tween(200), initialScale = 0.98f),
                exit = fadeOut(tween(if (reduceMotion) 0 else 150)),
            ) {
                last[0]?.let { ListenCard(it, title, vh.engine, backdrop) }
            }
            val toast = vh.toast
            if (toast != null && listening == null) VoiceToast(toast)
        }
    }
}

/** The small capsule of "Swipe up to cancel"; when it slides up, it turns red and reads "Release Cancel". */
@Composable
private fun CancelHint(armed: Boolean, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.floatShadow(c, shape).background(if (armed) c.bad else c.surface, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp).testTag("voice-cancel-hint"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (armed) c.onAccent else c.ink2
        Icon(MinaIcons.Up, null, Modifier.size(15.dp), tint = fg)
        Text(stringResource(if (armed) R.string.voice_release_cancel else R.string.voice_slide_cancel), style = HintText, color = fg)
    }
}

/**
 * "Listening" card: There is a red dot on the top (recording), who it is addressed to, which text to
 * use, and when; the real-time words in the middle (a few words at the end may become lighter); at the
 * bottom, it says what will happen after you release it, and the last ten seconds countdown. When you
 * slide up to cancel, the words are crossed out and the border turns red.
 */
@Composable
private fun ListenCard(s: HoldToTalk.State.Listening, title: String, engine: String, backdrop: GlassBackdrop?) {
    val c = MinaTheme.colors
    val shape = RoundedCornerShape(22.dp)
    GlassBar(
        Modifier.fillMaxWidth().testTag("voice-listen")
            .then(if (s.cancelArmed) Modifier.border(1.5.dp, c.bad, shape) else Modifier),
        backdrop, shape,
    ) {
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecDot(armed = s.cancelArmed)
                Text(title, Modifier.weight(1f, fill = false), style = HeadText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (engine.isNotEmpty()) {
                    Text(
                        engine, Modifier.background(c.fill, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = ChipText, color = c.ink2, maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(ChatCopy.clock(s.elapsedMs), style = TimeText, color = c.ink3)
            }
            val heard = s.stable + s.tail
            val words = if (heard.isEmpty()) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink3)) {
                        append(stringResource(if (s.live) R.string.voice_say_now else R.string.voice_recording))
                    }
                }
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink)) { append(s.stable) }
                    withStyle(SpanStyle(color = c.ink2)) { append(s.tail) }
                }
            }
            Text(
                words,
                Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 54.dp).alpha(if (s.cancelArmed) 0.4f else 1f),
                style = if (s.cancelArmed) ListenText.copy(textDecoration = TextDecoration.LineThrough) else ListenText,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    stringResource(if (s.live) R.string.voice_foot_live else R.string.voice_foot_convert),
                    Modifier.weight(1f), style = FootText, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                VoiceCopy.countdown(s.elapsedMs)?.let { Text(it, style = FootText, color = c.warn) }
            }
        }
    }
}

/** The point on the top of the card: when recording, the blue circle moves outward; when canceling, it turns red and does not move. */
@Composable
private fun RecDot(armed: Boolean) {
    val c = MinaTheme.colors
    val reduce = MinaTheme.reduceMotion
    val haloState = if (armed || reduce) null else rememberInfiniteTransition(label = "rec").animateFloat(
        0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "rec-halo",
    )
    val dot = if (armed) c.bad else c.accent
    val soft = c.accentSoft
    Box(
        Modifier.size(8.dp).drawBehind {
            val halo = haloState?.value ?: 0f
            if (halo > 0f) drawCircle(soft, radius = size.minDimension / 2 + 5.dp.toPx() * halo)
            drawCircle(dot)
        },
    )
}

/** A light prompt above the input field (didn't hear clearly, recognition service is busy...) disappeared after a while. */
@Composable
private fun VoiceToast(text: String) {
    val c = MinaTheme.colors
    Text(
        text,
        Modifier.background(c.ink.copy(alpha = 0.88f), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 9.dp).testTag("voice-toast"),
        style = ToastText, color = c.bg, maxLines = 2,
    )
}

/** After the service provider releases it, circle and add "Transfer text..." in the Zhuan: input box. */
@Composable
internal fun VoiceConverting(modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    Row(modifier.testTag("voice-converting"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spinner(Modifier.size(13.dp), spinning = !MinaTheme.reduceMotion, track = c.accentSoft, head = c.accent)
        Text(stringResource(R.string.voice_converting), style = MinaType.Body, color = c.ink2, maxLines = 1)
    }
}

/** Able to press and hold the gray words when speaking: a small microphone in front. */
@Composable
internal fun VoicePlaceholder(mode: ComposerMode) {
    val c = MinaTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(MinaIcons.Mic, null, Modifier.size(16.dp), tint = c.ink3)
        Text(ChatCopy.voicePlaceholder(mode), style = MinaType.Body, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** When you press to speak, gently fade the dialogue content without per-frame BlurEffect churn. */
@Composable
internal fun Modifier.voiceDim(on: Boolean, reduceMotion: Boolean): Modifier {
    val pState = animateFloatAsState(if (on) 1f else 0f, if (reduceMotion) snap() else tween(250), label = "voice-dim")
    return graphicsLayer {
        alpha = 1f - 0.55f * pState.value
    }
}

/** "Voice input is not yet available" ("Go to settings" and go to settings to select text) and description of recording permissions ("Go to settings" to Mina's permissions page in the system). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSheetHost(vh: VoiceHold, onPickSpeech: () -> Unit) {
    val sheet = vh.sheet ?: return
    val c = MinaTheme.colors
    val context = LocalContext.current
    val title: String
    val body: String
    val action: String
    val go: () -> Unit
    when (sheet) {
        is VoiceSheet.Unavailable -> {
            title = stringResource(R.string.voice_unavailable_title)
            body = VoiceCopy.unavailable(sheet.why)
            action = stringResource(R.string.voice_go_pick)
            go = onPickSpeech
        }
        VoiceSheet.Permission, VoiceSheet.MicBlocked -> {
            val blocked = sheet == VoiceSheet.MicBlocked
            title = stringResource(if (blocked) R.string.voice_mic_blocked_title else R.string.voice_permission_title)
            body = stringResource(if (blocked) R.string.voice_mic_blocked_body else R.string.voice_permission_body)
            action = stringResource(R.string.voice_permission_open)
            go = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { vh.sheet = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 18.dp).testTag("voice-sheet")) {
            Text(title, style = MinaType.Title, color = c.ink)
            Text(body, Modifier.padding(top = 8.dp), style = SheetBody, color = c.ink2)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MinaButton(stringResource(R.string.ok_got_it), { vh.sheet = null }, Modifier.weight(1f), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
                MinaButton(action, { vh.sheet = null; go() }, Modifier.weight(1f), size = ButtonSize.Medium)
            }
        }
    }
}

private val BarLabel = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val HintText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val HeadText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val ChipText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val TimeText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val ListenText = TextStyle(fontSize = 18.sp, lineHeight = 27.sp)
private val FootText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val ToastText = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp)
private val SheetBody = TextStyle(fontSize = 14.sp, lineHeight = 23.sp)
