package com.meenai.mina.capsule

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import android.graphics.Rect
import com.meenai.mina.record.RunStore
import com.meenai.mina.R
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.core.cap.web.KeptPageClose
import com.meenai.mina.i18n.tr
import com.meenai.mina.chat.ConversationStore
import java.util.UUID
import kotlinx.coroutines.withContext
import com.meenai.mina.HarnessApp
import com.meenai.mina.cap.phone.a11y.MainScreenCapture
import com.meenai.mina.cap.phone.a11y.OverlayDrag
import com.meenai.mina.cap.phone.a11y.OverlayHold
import com.meenai.mina.cap.phone.a11y.OverlayPlacement
import com.meenai.mina.cap.phone.a11y.OverlayWindow
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Path
import android.os.Bundle
import com.meenai.mina.capsule.acthere.ScreenSnapshotData
import com.meenai.mina.capsule.acthere.UiElementSummary
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.cap.phone.a11y.ScreenReader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.userscreen.CapturedScreen
import com.meenai.mina.core.userscreen.UserScreenMessage
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.task.DeviceStateWatcher
import com.meenai.mina.task.TaskCenterState
import com.meenai.mina.task.TaskRun
import com.meenai.mina.task.TaskService
import com.meenai.mina.task.TaskService.Companion.isActive
import com.meenai.mina.takeover.TakeoverQueue
import com.meenai.mina.ui.ChatCopy
import com.meenai.mina.ui.VoiceCopy
import com.meenai.mina.i18n.tr
import com.meenai.mina.voice.HoldToTalk
import com.meenai.mina.voice.SpeechChoice
import com.meenai.mina.voice.SpeechFailure
import com.meenai.mina.ui.ChatViewModel
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import com.meenai.mina.ui.ScreenCopy
import com.meenai.mina.ui.screenThumbnail
import com.meenai.mina.ui.ToolLabels
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.animationsOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wiring of the suspension capsule: hand over the status of each task (the status of
 * [TaskCenterState.runs] and each [TaskRun], takeover stage, activity), switches, whether the Mina
 * interface is in the foreground ([AppForeground]), whether the screen is on ([DeviceStateWatcher]), and
 * whether the accessibility service is present, to [CapsuleModel], and add, move or remove the floating
 * window through [com.meenai.mina.cap.phone.a11y.OverlayHost] according to its results. Click one or the
 * capsule to expand the card ([CapsuleCards] determines the content and only says one thing), click
 * outside the card, close the button or return to retract it into one (the ones you have seen will not
 * be expanded, the model will remember); the buttons on the cards and capsules are converted into
 * existing actions of the task service. The card and the card are each in a window, each waiting for its
 * own position (see [window]): when expanded, the window of the card is empty, and the card zooms and
 * fades in from the position of that card in its own window. Double-clicking a pill or capsule (see
 * [DoubleTap]: Clicking will not expand until the double-click time limit has passed) will send the
 * user's current screen to the conversation in the card, which is the same way as sending a sentence in
 * the card; take a screenshot of the screenshot button on the left side of the card input box and hang
 * it on the input box, and you can add a sentence and then send it. When cutting, the floating layer is
 * hidden first, and then comes out after cutting, and one of them flashes up. The floating layer can
 * receive the keyboard only when the input box at the bottom of the card is clicked open. It can be
 * sent, folded, clicked outside, and returned immediately when pressing return; when the input method
 * pops up, the capsule and card move to it. Press and hold a capsule or a capsule to speak (see
 * [CapsuleTalk], [CapsuleVoicePanel]): when pressed, the entire screen will be darkened, a panel will
 * pop up next to the capsule, and two drop points are "Cancel" and "Change". Release and send directly
 * to the card that will expand when you click it (same as double-clicking to send the screen to find the
 * target), drag to "Change" to open the card, put the word in the input box, drag to "Cancel" and
 * discard; When the message is issued, not heard clearly, or cannot be used, the capsule will flash a
 * message ([note]). The panel is in another window below that window and cannot be touched. Only when
 * the card is expanded will the scene of that incident be collected (at most [PREVIEW_FPS] pictures per
 * second, no change will be made). Usually it is an icon, but when you have something to say, it expands
 * into a capsule (depending on the model); you can drag it and let go and stick it to the nearest left
 * or right side; the stopping position is recorded as [FeatureFlags] according to the ratio of side and
 * height, and it can be rearranged according to the proportion after the screen is turned. The card is
 * centered on the left and right (conversation cards are at a fixed height, task cards are as high as
 * the content, at most), drag up and down according to the title bar, and record the ratio of the upper
 * part, and the upper part does not move when changing cards; the dialogue card is read from
 * [ConversationStore] (the same copy as the conversation page), and the operated App is read from the
 * running record of that round ([RunStore]). When the floating layer cannot be drawn (the service has
 * just been disconnected and the system refuses to allow it), the service instance will not be retried,
 * the capsule will not appear, and the notification will remain the same. All on the main thread.
 */
class CapsuleController(
    private val context: Context,
    private val tasks: TaskCenterState,
    private val foreground: AppForeground,
    /** A takeover queue shared by several tasks: the card and that one are read from here one after another. */
    private val takeoverQueue: TakeoverQueue,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = CapsuleModel(::reasonLabel)
    private val flags = FeatureFlags.get(context)
    /** The page left running in the background after the task is completed (the capsule says it when there is no task, and you can see the picture in the card and close it). */
    private val keeper get() = (context.applicationContext as HarnessApp).pageKeeper
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()
    /** The questions and answers for each task look like this at the moment, and they follow the same reasoning method as the dialogue page (steps, what you are asked to do, and the result). */
    private val exchanges = HashMap<String, Exchange>()

    private var view by mutableStateOf<CapsuleView.Shown?>(null)
    private var card by mutableStateOf<CardView?>(null)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var zoomed by mutableStateOf(false)
    private var dock by mutableStateOf(flags.capsuleDock())
    private var inputUi by mutableStateOf(CardInputUi())
    /** When a screenshot is taken, add one: one will flash up. */
    private var flash by mutableIntStateOf(0)
    /** Taking a screenshot (hidden in the floating layer): Clicking and taking screenshots at this time will not count. */
    private var capturing = false
    /** The conversation that was spoken in the card: follow its task when it is expanded so that you can see the answer. */
    private var follow: String? = null
    /** The most recent conversation following the idle card (read once when clicking on the idle icon). */
    private var idle: CardView.Chat? = null

    /** Press and hold to speak; [talkIdle] is the most recent conversation on the idle icon when you start speaking (and send it to it after speaking). */
    private val talk = CapsuleTalk(context, { (context.applicationContext as HarnessApp).voice }, scope, ::spoken)
    private var talkIdle: Deferred<CardView.Chat>? = null
    /** A temporary flashing message on the capsule (sent, not heard, unavailable) will disappear by itself. */
    private var note by mutableStateOf<CapsuleNote?>(null)
    private var noteJob: Job? = null
    private var noteCount = 0

    /**
     * The round trip of the dialogue card: [threadOf], all the rounds recorded in that dialogue ([past],
     * read from [ConversationStore] in the background, the same as the dialogue page; if it has not been
     * read, it is null), the last round of [shownTurns], and the tasks that have not been recorded;
     * [thread] is given to the interface at this time. [threadMark] refers to what tasks were included
     * in this dialogue and whether it was ended when it was read last time. If there is a change (the
     * task is completed and removed, the dialogue will only be recorded in that round), reread it.
     * [placesOf] records the apps and websites operated in that round according to the running record
     * number.
     */
    private var thread by mutableStateOf<ChatThread?>(null)
    private var threadOf: String? = null
    private var past: List<PastTurn>? = null
    /** Give this conversation a good title (if not, use the first sentence of the card). */
    private var pastTitle: String? = null
    private var shownTurns = CapsuleThreads.PAGE
    private var threadMark: Any? = null
    private var threadJob: Job? = null
    private val placesOf = HashMap<String, List<String>>()
    private val runStore by lazy { RunStore(context) }

    /** The maximum visible height of the card (in pixels, as defined by screen and input); 0 means it has not been moved yet. [cardFraction] is the ratio on the card, recorded as [FeatureFlags]. */
    private var cardHeight by mutableIntStateOf(0)
    private var cardFraction = flags.capsuleCardFraction()
    /** The position of the card title bar in the window: it can only drag the card by pressing it. */
    private var cardHandle: Rect? = null
    /** The top of the input method on the user's screen; null if it does not pop up. Capsules and cards get out of its way. */
    private var imeTop: Int? = null
    private var imeJob: Job? = null

    /** The transparency of the capsule at the moment (it will fade away after a few seconds if you don’t touch it); [lastActive] is the time when it was last touched or its status changed. */
    private var alpha by mutableStateOf(1f)
    private var lastActive = 0L
    private var look: Any? = null
    private var fadeJob: Job? = null

    /** The card is unfolding; [closing] is retracting the capsule (it is actually retracted after the animation is completed). */
    private var expanded = false
    private var closing by mutableStateOf(false)
    /** The cards really started to come out: only then did one move out of the way (card layout, before coming out, one card was still there, not empty); when the cards started to retract, one card came back. */
    private var cardOut by mutableStateOf(false)
    /** The center of the one before expansion (the vertical coordinate of the screen) and the top of the card window: the card grows out and retracts from the position of that one. */
    private var anchorY = 0
    private var cardTop by mutableIntStateOf(0)
    private var previewKey: String? = null
    private var previewJob: Job? = null

    /** The height ratio of the "you handle" one (only remembered in this run). */
    private var barFraction = 0f
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var placedY = 0
    /** Above the card window. */
    private var cardY = 0
    private var snap: ValueAnimator? = null

    /**
     * The two floating windows each stay in their own position, and no one moves when they are unfolded
     * and folded: [window] puts one, capsule and that one, and stops on the edge; [cardWindow] puts the
     * cards, left and right in the center (when folded, the inside is empty, the window has no size, and
     * does not block the App below). If a window moves back and forth between the position of the star
     * and the card, the system will make the movement into a sliding animation (the original Android
     * window moving animation). It looks like the star slides back from the middle to the edge, and the
     * card slides from the edge to the middle.
     */
    private var window: OverlayWindow? = null
    private var cardWindow: OverlayWindow? = null
    /** Press and hold the panel when talking, which is under the window of the capsule (dark it without covering the capsule); it is usually empty and has no size. You can't press and hold to speak when you can't add it. */
    private var panelWindow: OverlayWindow? = null
    private var dismissWindow: OverlayWindow? = null
    private var menuWindow: OverlayWindow? = null
    private var dismissShowing by mutableStateOf(false)
    private var dismissHovered by mutableStateOf(false)
    /** Which service instance the window was added to; if the service is changed, the old window has been removed along with the old instance. */
    private var windowOn: ScreenAccessibility? = null
    /** Failed to add a window to this service instance: don't try again and wait for the service to reconnect. */
    private var failedOn: ScreenAccessibility? = null
    private var tick: Job? = null
    private val ticks = MutableStateFlow(0)
    private var last: Snapshot? = null

    private val _showing = MutableStateFlow(false)
    /** The capsule is now actually displayed on the illuminated screen; the task service accordingly sends the "need you" notification more quietly. */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    fun start() {
        val screen = DeviceStateWatcher(context) { -1 }
        scope.launch {
            combine(
                combine(runs(), flags.capsuleModeChanges(), foreground.visible, screen.locked, ScreenAccessibility.instance) { r, m, f, l, a -> Snapshot(r, m, f, l, a) },
                ticks,
                keeper.page,
            ) { s, _, kept -> s.copy(kept = kept) }.collect(::render)
        }
        // When the talking panel comes out, it fills the entire screen (dark the back), and becomes
        // touchable/focusable whenever direct panel buttons (Cancel/Send) are active.
        scope.launch {
            snapshotFlow { talk.active to (!talk.byTouch || talk.released) }.collect { (active, interactive) ->
                panelWindow?.setFullScreen(active)
                panelWindow?.setTouchable(active && interactive)
                panelWindow?.setFocusable(active && interactive, showIme = false)
            }
        }
        // Improved dialogue for the round when post-stop explanations were written (or failed to be
        // written): reread the noted turns
        scope.launch { tasks.summarizing.collect { threadOf?.let(::loadPast) } }
        // Dialogue deleted: see [forget]
        scope.launch { ConversationStore.shared(context).deletions.collect(::forget) }
        // The title of the conversation on the card has changed (the user has changed the name in the
        // sidebar and the title has been set in the background): "Continue to chat with "..." at the top
        // of the card has been changed.
        scope.launch {
            val store = ConversationStore.shared(context)
            store.changes.collect { id ->
                if (id != threadOf) return@collect
                val title = withContext(Dispatchers.IO) { store.title(id) }
                if (id == threadOf && title != pastTitle) {
                    pastTitle = title
                    rebuildThread()
                }
            }
        }
        // Rotate the screen: Rearrange according to the noted sides and proportions; the system may give
        // the new size a little later, so you can arrange it again after a while.
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                place()
                scope.launch {
                    delay(ROTATE_SETTLE_MS)
                    place()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    private data class Snapshot(
        val runs: List<CapsuleRun>, val mode: CapsuleMode, val minaInForeground: Boolean, val locked: Boolean,
        val service: ScreenAccessibility?,
        /** The page that remains running in the background after the task is completed. */
        val kept: KeptPage? = null,
    )

    /**
     * What each task looks like at the moment. After the task is completed, it will be removed from
     * [TaskCenterState.runs]; when it is removed, its state is already the final state. If you still
     * carry it (until the task list changes again), the model can record "done" and pause for a while.
     * Listing it after it is completed does not count. Those that are removed before reaching the final
     * state (the service is destroyed) will not be counted directly.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runs(): Flow<List<CapsuleRun>> {
        var previous = emptyList<TaskRun>()
        return tasks.runs.flatMapLatest { current ->
            val gone = previous.filter { r -> current.none { it === r } && !r.state.value.isActive() }
            previous = current
            val all = current + gone
            exchanges.keys.retainAll(all.map { it.key }.toSet())
            if (all.isEmpty()) flowOf(emptyList())
            else combine(all.map { r ->
                // At the moment you are on the operating screen: you have your own virtual screen, or
                // you are using the built-in browser (the card selects a task card or a conversation
                // card accordingly)
                combine(r.state, r.takeoverStage, r.activities, r.displayId, r.webOpen) { s, st, acts, display, web ->
                    CapsuleRun(
                        r.key, r.conversationId, s, st, r.goal, exchange(r, s, acts),
                        runId = r.runId.value, onScreen = display >= 0 || web,
                    )
                }.combine(r.steers) { c, steers -> c.copy(steers = steers) }
            }) { it.toList() }.combine(takeoverQueue.changes) { list, _ ->
                // Take over the order in the queue: If the queue changes (the previous item is
                // returned), read it again, and the next item will immediately become "I need you"
                list.map { c ->
                    val owner = all.firstOrNull { it.key == c.key }?.takeover ?: return@map c
                    val ahead = takeoverQueue.ahead(owner)
                    if (ahead < 0) c else c.copy(queueAhead = ahead, queueBehind = takeoverQueue.behind(owner))
                }
            }
        }
    }

    private fun exchange(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchanges[r.key] ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchanges[r.key] = it }
    }

    private fun render(s: Snapshot) {
        last = s
        val service = s.service
        if (windowOn != null && windowOn !== service) {
            talk.abort()
            window = null
            cardWindow = null
            panelWindow = null
            dismissWindow = null
            closeShortcutMenu()
            windowOn = null
        }
        val inputs = CapsuleInputs(
            s.runs, s.mode, s.minaInForeground, screenInteractive = !s.locked,
            serviceAvailable = service != null && service !== failedOn, cardOpen = expanded, kept = s.kept,
        )
        val shown = model.update(inputs, System.currentTimeMillis()) as? CapsuleView.Shown
        schedule(model.nextChangeAt)
        if (shown == null || service == null) {
            hide()
            return
        }
        // The app is in your hands: the one at the top replaces the card
        if (shown.kind == CapsuleKind.HandedOver) resetCard()
        view = shown
        card = if (expanded) CapsuleCards.of(model.cardRuns(), follow, idle, s.kept) else null
        if (expanded && card == null) resetCard()
        updateThread(card as? CardView.Chat, s.runs)
        // Changed to content without an input box (for example, asking for your consent): the input box
        // is closed and the focus is returned to the app below.
        if (inputUi.open && card?.let(CapsuleCards::input) == null) closeInput()
        val detail = card as? CardView.Detail
        val keptCard = card as? CardView.Kept
        if (keptCard != null) watchKept(keptCard.page.id)
        else watchPreview(detail?.takeIf { CapsuleCards.showsScreen(it.content) }?.run?.run?.key)
        val w = window ?: openWindows(service)
        if (w == null) {
            view = null
            card = null
            _showing.value = false
            return
        }
        applyDrag()
        place()
        // If the status, number of pieces, expansion or retraction is changed: restore immediately and
        // restart the timer; changing only one sentence does not count (you cannot see that sentence)
        val nowLook = listOf(shown.kind, shown.pill, shown.count, shown.needCount)
        if (nowLook != look) {
            look = nowLook
            wake()
        } else fade()
        _showing.value = true
    }

    /** Add two windows (first the one with the icon, then the card one, with the card on top); if you can’t add one, don’t use either one, and you’ll be notified. */
    private fun openWindows(service: ScreenAccessibility): OverlayWindow? {
        val overlays = service.overlays()
        // Add the panel first, under one capsule: press the darkened layer so that it does not cover the
        // capsule
        val panel = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) { VoicePanelOverlay() }
        val dismiss = overlays.open(OverlayPlacement(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 36), touchable = false) { DismissZoneOverlay() }
        val ball = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT)) { CapsuleOverlay() }
        val cardW = ball?.let { overlays.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL), ::outside) { CardOverlay() } }
        if (ball == null || cardW == null) {
            ball?.close()
            panel?.close()
            dismiss?.close()
            failedOn = service
            Log.w(TAG, "capsule overlay unavailable, falling back to notifications")
            return null
        }
        window = ball
        cardWindow = cardW
        panelWindow = panel
        dismissWindow = dismiss
        windowOn = service
        for (it in listOf(ball, cardW)) {
            it.onResize(::place)
            it.onTouch(::wake)
        }
        cardW.onBack(::back)
        if (expanded && !closing) cardW.setFocusable(true, showIme = false)
        panel?.onBack { if (talk.active) talk.abort() }
        watchIme(service)
        return ball
    }

    private fun refresh() {
        last?.let(::render)
    }

    /**
     * The dialogue [id] is deleted: the task it just ended is immediately removed from the capsule and
     * card, and the card is no longer connected to it (the one that just sent a message in the card; the
     * idle card is connected, and when it is expanded, it is replaced by the one that opens a new
     * conversation), and the expanded card is picked again by pressing [CapsuleCards.of] (it is closed
     * if there is nothing). To delete a conversation, first stop its task and wait for it to finish (up
     * to 15 seconds) before deleting the record, so its task is usually over at this time; if it cannot
     * wait for the finish, or if you continue talking in the card after finishing but before deleting
     * the record (a new task will be opened in this conversation), the conversation will be rebuilt in
     * the next round of saving, and the capsule will display it as usual.
     */
    private fun forget(id: String) {
        model.forget(id)
        if (follow == id) follow = null
        if (idle?.conversationId == id) idle = if (expanded) CardView.Chat(null) else null
        refresh()
    }

    private fun hide() {
        snap?.cancel()
        fadeJob?.cancel()
        look = null
        imeJob?.cancel()
        imeJob = null
        imeTop = null
        dragging = false
        talk.abort()
        talkIdle = null
        noteJob?.cancel()
        note = null
        window?.close()
        window = null
        cardWindow?.close()
        cardWindow = null
        panelWindow?.close()
        panelWindow = null
        dismissWindow?.close()
        dismissWindow = null
        closeShortcutMenu()
        closeCircleOverlay()
        stopActHere()
        windowOn = null
        view = null
        resetCard()
        card = null
        _showing.value = false
    }

    private fun resetCard() {
        expanded = false
        closing = false
        cardOut = false
        follow = null
        zoomed = false
        cardHandle = null
        closeInput()
        cardWindow?.setFocusable(false)
        inputUi = CardInputUi()
        watchPreview(null)
        updateThread(null, emptyList())
    }

    // ---------- Enter ----------

    /** Click on the input box: Only then can the floating layer receive the keyboard, and the input method pops up against it. */
    private fun openInput() {
        if (inputUi.open) return
        cardWindow?.setFocusable(true)
        inputUi = inputUi.copy(open = true)
    }

    /** Close the input box and keep focusable (without IME) while expanded so Back collapses the card cleanly. */
    private fun closeInput() {
        if (!inputUi.open) return
        inputUi = inputUi.copy(open = false)
        cardWindow?.setFocusable(expanded && !closing, showIme = false)
    }

    /** Return key: first close the input box, then close the enlarged screen, and finally close the card. */
    private fun back() {
        when {
            inputUi.open -> closeInput()
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    /** Enlarge or close the screen; cards cannot be dragged when zoomed in (there is no title bar). */
    private fun zoom(on: Boolean) {
        zoomed = on
        applyDrag()
    }

    /**
     * To send words in the input box, follow the same path as sending words in the dialogue page (both
     * are recorded in that dialogue): interrupt it when running or waiting for conditions, answer it
     * when Mina asks you, continue it in the same dialogue after completing it, continue the most recent
     * dialogue or open a new dialogue when you are free; after the latter two, the card will follow the
     * task of that dialogue to see the answer.
     */
    private fun send() {
        val text = inputUi.draft.trim()
        val shot = inputUi.screen?.shot
        val c = card ?: return
        val kind = CapsuleCards.input(c)?.kind ?: return
        if (text.isEmpty() && shot == null) return
        inputUi = CardInputUi()
        cardWindow?.setFocusable(expanded && !closing, showIme = false)
        if (shot != null) deliver(c, kind, shot, text) else submit(c, kind, text, CapsuleCards.conversationOf(c))
        refresh()
    }

    /** Press the input box to whomever you want to give it at this moment ([kind]) to issue [text]; start to say [conversationId] (open a new one when it is null). */
    private fun submit(c: CardView, kind: CardInputKind, text: String, conversationId: String?) {
        val run = when (c) {
            is CardView.Detail -> c.run.run
            is CardView.Chat -> c.run?.run
            is CardView.Kept -> null
        }
        when (kind) {
            CardInputKind.Steer -> run?.let { TaskService.start(context, text, it.conversationId, messageId = UUID.randomUUID().toString()) }
            CardInputKind.Answer -> run?.let { TaskService.answer(context, text, it.key) }
            CardInputKind.Continue -> run?.let { startIn(it.conversationId, text) }
            CardInputKind.Start -> startIn(conversationId ?: ConversationStore.shared(context).newConversation(), text)
        }
    }

    /**
     * Send the screenshot together with the postscript [words]: first save the conversation where this
     * sentence is going (the new conversation will be numbered at this time), then write a sentence with
     * the screen ([UserScreenMessage]) and send it as usual; if it cannot be saved, it will not be sent.
     */
    private fun deliver(c: CardView, kind: CardInputKind, shot: CapturedScreen, words: String) {
        val access = (context.applicationContext as HarnessApp).appAccess.current()
        val isLocked = CapsuleSecurityPolicy.isDeviceLocked(context)
        if (shot.sourcePackages.isEmpty() || !CapsuleSecurityPolicy.canPerformScreenAction(shot.sourcePackages, isLocked, access)) {
            inputUi = inputUi.copy(screen = null)
            showNote(tr(R.string.capsule_sensitive_blocked), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
            return
        }
        val conversationId = CapsuleCards.conversationOf(c) ?: ConversationStore.shared(context).newConversation()
        scope.launch {
            val saved = runCatching { (context.applicationContext as HarnessApp).userScreens.save(conversationId, shot) }
                .onFailure { Log.w(TAG, "could not save the screen", it) }.getOrNull() ?: return@launch
            val latest = (context.applicationContext as HarnessApp).appAccess.current()
            if (shot.sourcePackages.any { !latest.canShare(it) }) {
                inputUi = inputUi.copy(screen = null)
                android.widget.Toast.makeText(context, tr(R.string.appaccess_share_expired), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            submit(c, kind, UserScreenMessage.compose(saved.id, words), conversationId)
            refresh()
        }
    }

    /**
     * Double-click: Send the current screen to the conversation in the card that will be expanded by
     * clicking, just like sending a sentence in that card (interject while running, answer when asking
     * you, continue talking after finishing, continue the latest conversation or open a new conversation
     * when free), and then expand the card to see the answer. When the card does not have an input box
     * (requiring you to take over and agree) and there is no place to send it, it will be expanded just
     * like clicking.
     */
    private fun sendScreen() {
        val v = view ?: return
        if (capturing) return
        scope.launch {
            if (v.kind == CapsuleKind.Idle) idle = loadIdle()
            val target = CapsuleCards.of(model.cardRuns(), follow, idle)
            val kind = target?.let(CapsuleCards::input)?.kind
            if (target == null || kind == null) {
                expand()
                return@launch
            }
            val shot = captureScreen() ?: return@launch
            deliver(target, kind, shot, "")
            CapsuleCards.conversationOf(target)?.let { follow = it }
            open()
            refresh()
        }
    }

    /** Screenshot button in the card: Take a screenshot and hang it on the input box, and you can add a sentence or not. */
    private fun captureForCard() {
        if (capturing) return
        scope.launch {
            val shot = captureScreen() ?: return@launch
            val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
            if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
        }
    }

    /**
     * Take a screenshot of the user's current screen: the input box is open and closed first (waiting
     * for the input method to be removed), the floating layer is hidden, and it is really removed from
     * the screen before taking a cut. After the cut is completed, it will come out immediately, and one
     * will flash up; then the words on the screen will be sorted in the background. The front desk is
     * Mina herself, and it is null when the deadline cannot be reached.
     */
    private suspend fun captureScreen(): CapturedScreen? {
        val service = windowOn ?: return null
        val w = window ?: return null
        val c = cardWindow ?: return null
        capturing = true
        try {
            if (inputUi.open) {
                closeInput()
                withTimeoutOrNull(IME_GONE_MS) { while (imeTop != null) delay(IME_POLL_MS) }
            }
            // Don’t draw anything in the empty window (the one with a star when expanded, the one with
            // cards when collapsed), so you don’t have to wait for it.
            suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
            val store = (context.applicationContext as HarnessApp).appAccess
            val sources = MainScreenCapture.sourcePackages(service) ?: return null
            if (context.packageName in sources || !store.current().available) return null
            val isLocked = CapsuleSecurityPolicy.isDeviceLocked(context)
            if (!CapsuleSecurityPolicy.canPerformScreenAction(sources, isLocked, store.current())) {
                showNote(tr(R.string.capsule_sensitive_blocked), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                return null
            }
            for (pkg in sources) {
                if (!store.current().canShare(pkg) && !com.meenai.mina.cap.phone.a11y.requestScreenShare(service, store, pkg, appLabel(pkg))) return null
            }
            val capture = w.hidden { c.hidden {
                if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) null
                else MainScreenCapture.shoot(service)
            } } ?: return null
            val (shot, read) = capture
            if (MainScreenCapture.sourcePackages(service) != sources || sources.any { !store.current().canShare(it) }) {
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return null
            }
            flash++
            wake()
            val area = service.overlays().area()
            val r = MainScreenCapture.build(service, shot, read, area.insetTop, area.insetBottom) { ConfirmationNotifier.appLabel(context, it) }
            if (sources.any { !store.current().canShare(it) } || MainScreenCapture.sourcePackages(service) != sources) return null
            val captured = (r as? MainScreenCapture.Result.Captured)?.shot ?: return null
            return CapturedScreen(captured.jpeg, captured.screen, sources)
        } finally {
            capturing = false
        }
    }

    /**
     * Start saying [text] in the dialogue [conversationId]; other tasks will be queued up during running
     * and will not be lost. The card continues this conversation; the idle card is also changed to
     * continue this conversation (the title of the newly opened conversation is this sentence).
     */
    private fun startIn(conversationId: String, text: String) {
        TaskService.start(context, text, conversationId)
        follow = conversationId
        idle = idle?.let { CardView.Chat(conversationId, title = if (it.target == conversationId) it.title else ScreenCopy.plain(text)) }
    }

    /** Click on the idle icon: open immediately and refresh the current conversation asynchronously on IO. */
    private fun openIdle() {
        if (idle == null) idle = CardView.Chat(null)
        open()
        refresh()
        scope.launch {
            val fresh = loadIdle()
            if (fresh != idle) {
                idle = fresh
                refresh()
            }
        }
    }

    /**
     * The conversation that the idle card follows: the one where the user is in the App. If there is no
     * record, the most recent one will be followed (see [CapsuleIdle]); if there is no record, a new
     * conversation will be opened. Guessing not based on the time of the conversation file: the newly
     * opened conversation does not have a file yet, the guess is the previous one.
     */
    private suspend fun loadIdle(): CardView.Chat = withContext(Dispatchers.IO) {
        runCatching {
            val store = ConversationStore.shared(context)
            val recent = store.list().firstOrNull()?.let { CardView.Chat(it.id, title = it.title?.let(ScreenCopy::plain)) }
            CapsuleIdle.chat(store.currentChat(), recent) { id ->
                // Same as sidebar: name or first sentence given by the user
                (store.title(id) ?: store.turns(id).firstOrNull()?.userText)?.let(ScreenCopy::plain)
            }
        }.getOrElse { CardView.Chat(null) }
    }

    // ---------- Back and forth of dialogue cards ----------

    /**
     * The back and forth of dialogue cards: read from the beginning when the dialogue is changed (read
     * the most recent [CapsuleThreads.PAGE] round first); reread the recorded turns when the tasks in
     * this dialogue appear, end, and are removed (the round after the task ends is recorded in the
     * dialogue); the rest of the time, only rearrange according to the task at the moment (the words in
     * the answer will be longer). Clear it when it is not a dialogue card.
     */
    private fun updateThread(chat: CardView.Chat?, runs: List<CapsuleRun>) {
        val id = chat?.target
        if (id == null) {
            threadJob?.cancel()
            threadOf = null
            past = null
            threadMark = null
            shownTurns = CapsuleThreads.PAGE
            // New dialogue for idle cards: back and forth is empty
            thread = if (chat != null) ChatThread(emptyList()) else null
            return
        }
        val mark = runs.filter { it.conversationId == id }.map { it.key to it.state.isActive() }
        if (id != threadOf) {
            threadOf = id
            past = null
            shownTurns = CapsuleThreads.PAGE
            thread = null
        }
        if (mark != threadMark) {
            threadMark = mark
            loadPast(id)
        }
        rebuildThread()
    }

    /** Arrange back and forth according to the noted turns and the tasks in the card at the moment; leave the notes blank until they are read. */
    private fun rebuildThread() {
        val id = threadOf ?: return
        val p = past ?: return
        val live = CapsuleThreads.liveTail(model.cardRuns(), id, p.mapNotNull { it.turn.runId }.toSet())
        val built = CapsuleThreads.build(p, shownTurns, live, tasks.summarizing.value) { CapsuleThreads.places(it.run.exchange?.activities.orEmpty(), ::appLabel) }
        thread = pastTitle?.let { built.copy(title = it) } ?: built
    }

    /** The rounds recorded in the background reading conversation [id], together with the apps and websites that have been operated in those rounds to be sorted out (read from the running record, remember the ones that have been read), and rearrange after reading. */
    private fun loadPast(id: String) {
        threadJob?.cancel()
        val known = HashMap(placesOf)
        val n = shownTurns
        threadJob = scope.launch {
            val (turns, found) = withContext(Dispatchers.IO) {
                val turns = runCatching { ConversationStore.shared(context).turns(id) }.getOrDefault(emptyList())
                turns to turns.takeLast(n).mapNotNull { it.runId }.filter { it !in known }.associateWith(::placesIn)
            }
            val title = withContext(Dispatchers.IO) { ConversationStore.shared(context).title(id) }
            placesOf += found
            if (threadOf != id) return@launch
            pastTitle = title
            past = turns.map { PastTurn(it, it.runId?.let(placesOf::get).orEmpty()) }
            rebuildThread()
        }
    }

    /** Apps and websites that have been operated at that time; when the records have been cleared or cannot be read, they will be empty (treated as pure chat). Adjusted in background thread. */
    private fun placesIn(runId: String): List<String> = runCatching {
        CapsuleThreads.places(runStore.events(runId).filterIsInstance<RunEvent.Activity>()) { ConfirmationNotifier.appLabel(context, it) }
    }.getOrDefault(emptyList())

    /** Scroll to the top: Move forward multiple rows of [CapsuleThreads.PAGE] wheels (first read out the apps they have operated on). */
    private fun loadEarlier() {
        val id = threadOf ?: return
        val p = past ?: return
        if (p.size <= shownTurns) return
        shownTurns += CapsuleThreads.PAGE
        loadPast(id)
    }

    /** Follow the input method on the user's screen: pop up, collapse, and reset when it becomes taller. */
    private fun watchIme(service: ScreenAccessibility) {
        imeJob?.cancel()
        imeJob = scope.launch {
            service.overlays().imeTop().collect {
                imeTop = it
                place()
            }
        }
    }

    /** Calculate again when the model says it is done (in other words, shrink or disappear after completion). */
    private fun schedule(at: Long?) {
        tick?.cancel()
        tick = at?.let { t ->
            scope.launch {
                delay((t - System.currentTimeMillis()).coerceAtLeast(0L))
                ticks.value++
            }
        }
    }

    // ---------- Translucent ----------

    /** Encountered: Restore immediately and restart the timer from now on. */
    private fun wake() {
        lastActive = System.currentTimeMillis()
        fade()
    }

    private fun fade() {
        fadeJob?.cancel()
        val v = view ?: return
        val idleFor = System.currentTimeMillis() - lastActive
        alpha = if (expanded) 1f else CapsuleFade.alpha(v.kind, v.needCount, v.pill, idleFor)
        if (!expanded && idleFor < CapsuleFade.IDLE_MS) fadeJob = scope.launch {
            delay(CapsuleFade.IDLE_MS - idleFor)
            fade()
        }
    }

    // ---------- Small screen ----------

    /** Only close the picture of the unfolded thing; stop when another thing is changed or folded. */
    private fun watchPreview(key: String?) {
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        previewJob = null
        preview = null
        zoomed = false
        val run = key?.let(::runFor) ?: return
        previewJob = scope.launch {
            launch { run.frames.collect { if (it == null) preview = null } }
            run.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect {
                preview = it.takeIf { run.frames.value != null }
            }
        }
    }

    /** Cards on the page that are left running in the background: close the displayed screen (the same as the small screen of the task card). */
    private fun watchKept(id: Long) {
        val key = "kept:$id"
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        preview = null
        zoomed = false
        previewJob = scope.launch {
            keeper.frames().filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { preview = it }
        }
    }

    // ---------- Position and Drag ----------

    /** The range that can be moved; when [ime] is true, get out of the way of the input method. */
    private fun area(ime: Boolean = true): DockArea? = windowOn?.overlays()?.area()?.let {
        val room = room()
        DockArea.of(it, margin = dp(EDGE_MARGIN_DP), gap = dp(TOP_GAP_DP), room = room)
            .let { a -> if (ime) a.above(imeTop, dp(TOP_GAP_DP), room) else a }
    }

    private fun room() = dp(CapsuleShadowRoom.value.toInt())

    private fun isBar() = view?.kind == CapsuleKind.HandedOver

    /**
     * Arrange the window according to the noted position: the capsule is attached to the side noted
     * down, the "You handle it" line is in the center of the top, the cards are centered on the left and
     * right, and the top is placed according to the proportion noted down; if it cannot be placed or the
     * input method cover is reached, move it up (the maximum height of the card becomes shorter at the
     * same time). When the card changes height (the card is changed and the content is changed), the
     * window changes accordingly and is placed again, but the top does not move.
     */
    private fun place() {
        val w = window ?: return
        if (dragging || snap?.isRunning == true) return
        val a = area() ?: return
        placeCard(a)
        val h = w.height.coerceAtLeast(dp(PILL_DP))
        if (isBar()) {
            placedY = CapsuleDocking.fitY(CapsuleDocking.y(barFraction, a, dp(BAR_DP)), a, h)
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, placedY))
            return
        }
        // When the card is unfolded, the window is empty and stays on the edge: when it is folded, one
        // card will come out at the same place.
        val d = dock
        // Move the input method cover to its original position: move the capsules and cards to it, put
        // it away and return it to its original position.
        val base = area(ime = false) ?: a
        val natural = CapsuleDocking.fitY(CapsuleDocking.y(d.fraction, base, dp(PILL_DP)), base, h)
        placedY = CapsuleDocking.fitY(natural, a, h)
        val side = if (d.side == DockSide.Left) Gravity.LEFT else Gravity.RIGHT
        w.place(OverlayPlacement(Gravity.TOP or side, CapsuleDocking.edgeOffset(d.side, a), placedY))
    }

    /**
     * Arrange the card window: center left and right, and the upper part according to the proportion
     * noted down. The maximum height of the card is determined by the screen and input rules. If the
     * content is changed, it will be rearranged, and the window will be resized and repositioned. When
     * it is just expanded and the window is still empty, place the cards according to their height
     * (conversation cards are fixed at a fixed height; task cards are taller according to content, and
     * the shortest one is calculated first), and there is no need to move it when the content comes out.
     */
    private fun placeCard(a: DockArea) {
        val w = cardWindow ?: return
        val c = card ?: return
        windowOn?.overlays()?.area()?.let { cardHeight = CardDocking.maxHeight(it.height, a, room()) }
        val base = area(ime = false) ?: a
        val expected = if (c is CardView.Chat) cardHeight + 2 * room() else dp(PILL_DP)
        cardY = CardDocking.y(cardFraction, base, a, cardFull(base), maxOf(w.height, expected))
        cardTop = cardY
        w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cardY))
    }

    /** The window height of the tallest card (dialogue card) in the range of [base] without input method (including the shaded edge): the proportion of the top of the card is calculated according to this. */
    private fun cardFull(base: DockArea): Int =
        CardDocking.maxHeight(windowOn?.overlays()?.area()?.height ?: 0, base, room()) + 2 * room()

    /**
     * Where can it be dragged: the capsule and the whole one can be dragged; cards can only be dragged
     * by pressing the title bar (swiping up and down in the middle is scrolling), and cannot be dragged
     * when the screen is enlarged. You can also press and hold the capsule to talk (that one doesn't
     * work; it also doesn't work when voice input is set to not be used, long pressing always means
     * clicking).
     */
    private fun applyDrag() {
        window?.draggable(drag)
        val talkable = canTalk()
        // You can’t say anything anymore while talking (for example, when the app is handed over to you
        // and the capsule is changed to that one): this paragraph is discarded and not sent - the user
        // has no chance to release the selected drop point.
        if (!talkable && talk.active) talk.abort()
        window?.holdable(shortcutHold.takeIf { !isBar() }, 300)
        cardWindow?.draggable(cardDrag, cardHandle.takeIf { !zoomed } ?: Rect())
    }

    private val shortcutHold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) {
            if (!isBar()) openShortcutMenu()
        }
        override fun move(rawX: Float, rawY: Float) {}
        override fun end(rawX: Float, rawY: Float) {}
        override fun cancel() {
            closeShortcutMenu()
        }
    }

    /** The card reports the location of the title bar. */
    private fun onHandle(r: Rect) {
        if (r == cardHandle) return
        cardHandle = r
        applyDrag()
    }

    /** Press the title bar and drag the card up and down (center left and right without moving); let go and note the ratio above, and press it next time to expand, change cards, or rotate the screen. */
    private val cardDrag = object : OverlayDrag {
        override fun start() {
            dragging = true
            dragStartY = cardY
        }

        override fun move(dx: Float, dy: Float) {
            val w = cardWindow ?: return
            val a = area() ?: return
            if (!dragging) return
            val base = area(ime = false) ?: a
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, CardDocking.dragY(dragStartY, dy, base, a, cardFull(base), w.height)))
        }

        override fun end(dx: Float, dy: Float) {
            val w = cardWindow
            val a = area()
            val was = dragging
            dragging = false
            if (!was || w == null || a == null) return
            val base = area(ime = false) ?: a
            val full = cardFull(base)
            cardFraction = CardDocking.release(CardDocking.dragY(dragStartY, dy, base, a, full, w.height), base, full)
            flags.setCapsuleCardFraction(cardFraction)
            place()
        }
    }

    /** Drag the capsule (left, right, up and down) or the "you handle" one (up and down only); see [cardDrag] for the card. */
    private val drag = object : OverlayDrag {
        override fun start() {
            val w = window ?: return
            val a = area() ?: return
            snap?.cancel()
            wake()
            dragging = true
            dragStartX = CapsuleDocking.dockedX(dock.side, a, w.width)
            dragStartY = placedY
            dismissShowing = true
            dismissHovered = false
        }

        override fun move(dx: Float, dy: Float) {
            val w = window ?: return
            val a = area() ?: return
            if (!dragging) return
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            val curX = CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width)
            if (isBar()) w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, y))
            else w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, curX, y))

            val bubbleCenterX = curX + w.width / 2
            val bubbleCenterY = y + w.height / 2
            val targetCenterX = a.width / 2
            val targetCenterY = a.bottom - dp(48)
            val dist = kotlin.math.hypot(
                (bubbleCenterX - targetCenterX).toDouble(),
                (bubbleCenterY - targetCenterY).toDouble(),
            )
            val isNearDismiss = dist < dp(80) || y + w.height >= a.bottom - dp(40)
            if (isNearDismiss != dismissHovered) {
                dismissHovered = isNearDismiss
            }
        }

        override fun end(dx: Float, dy: Float) {
            val w = window
            val a = area()
            val wasHovered = dismissHovered
            dismissShowing = false
            dismissHovered = false
            if (!dragging || w == null || a == null) {
                dragging = false
                return
            }
            dragging = false
            if (wasHovered) {
                stopActiveTask()
                flags.setCapsuleMode(CapsuleMode.Off)
                return
            }
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) {
                barFraction = CapsuleDocking.fraction(y, a, dp(BAR_DP))
                place()
                return
            }
            val x = CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width)
            dock = CapsuleDocking.release(x, y, w.width, w.height, a)
            flags.setCapsuleDock(dock)
            snapTo(x, y, CapsuleDocking.dockedX(dock.side, a, w.width))
        }
    }

    /** After letting go, slide to the edge; when the animation is turned off, swing directly over. */
    private fun snapTo(fromX: Int, y: Int, toX: Int) {
        val w = window ?: return
        placedY = y
        if (animationsOff(context) || fromX == toX) {
            place()
            return
        }
        snap = ValueAnimator.ofInt(fromX, toX).setDuration(SNAP_MS).apply {
            addUpdateListener { w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, it.animatedValue as Int, y)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snap = null
                    place()
                }
            })
            start()
        }
    }

    /** Instead of dragging when reading the screen: switch to the other side. */
    private fun swapSide() {
        dock = dock.copy(side = if (dock.side == DockSide.Left) DockSide.Right else DockSide.Left)
        flags.setCapsuleDock(dock)
        place()
    }

    // ---------- Press and hold to speak ----------

    /** Can I speak on the capsule at this moment: There is a panel window, it is not the "You handle it" one, and the voice input is not set to disable. */
    private fun canTalk(): Boolean = panelWindow != null && !isBar() &&
        (context.applicationContext as HarnessApp).speech.choice.value != SpeechChoice.Off

    /** A long press from that window: start speaking, move your finger to calculate the landing point, release and press the landing point to finish. */
    private val talkHold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) = beginTalk(byTouch = true)

        override fun move(rawX: Float, rawY: Float) {
            if (talk.byTouch) talk.move(rawX, rawY, dp(TARGET_SLOP_DP))
        }

        override fun end(rawX: Float, rawY: Float) {
            if (!talk.byTouch) return
            talk.finish(CapsuleVoice.drop(rawX, rawY, talk.cancelAt, talk.editAt, dp(TARGET_SLOP_DP)))
        }

        override fun cancel() {
            if (talk.byTouch) talk.abort()
        }
    }

    /** "Speaking" in screen reading: start when you are not speaking (no fingers, you can only speak), and speak when you are speaking. */
    private fun toggleTalk() {
        if (talk.active && !talk.byTouch && !talk.released) talk.finish(CapsuleVoice.Drop.Send)
        else beginTalk(byTouch = false)
    }

    /**
     * Let’s start by saying: Waiting for clicks and double-clicks first does not count. Tell who to
     * click on the card that will expand (on the idle icon, read the most recent conversation first,
     * then change to the sentence at the top of the panel after reading it). There is no recording
     * permission (the capsule cannot pop up the permission box), and voice input will not be recorded
     * when it is not available. Say something on the capsule and click to open Mina.
     */
    private fun beginTalk(byTouch: Boolean) {
        val v = view ?: return
        // You can press it again while it is still spinning after releasing it: the previous segment is
        // lost by talk.begin
        if (capturing || (talk.active && !talk.released) || !canTalk()) return
        wake()
        clearNote()
        val loading = if (v.kind == CapsuleKind.Idle) scope.async { loadIdle() } else null
        when (val b = talk.begin(CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, idle)), byTouch)) {
            CapsuleTalk.Begin.Started -> {
                talkIdle?.cancel()
                talkIdle = loading
                loading?.let {
                    scope.launch {
                        val chat = it.await()
                        if (talk.active && talkIdle === it) talk.title = CapsuleVoice.title(CapsuleCards.of(model.cardRuns(), follow, chat))
                    }
                }
            }
            CapsuleTalk.Begin.NoPermission -> {
                loading?.cancel()
                showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMina = true, NOTE_LONG_MS)
            }
            is CapsuleTalk.Begin.Unavailable -> {
                loading?.cancel()
                showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMina = true, NOTE_LONG_MS)
            }
        }
    }

    /** Finished speaking: press the release point to send or open the card; if you don’t hear clearly or make an error, the capsule will say something (the saying is the same as the input field). */
    private fun spoken(drop: CapsuleVoice.Drop, state: HoldToTalk.State) {
        val idleJob = talkIdle
        talkIdle = null
        when (state) {
            is HoldToTalk.State.Done -> scope.launch {
                idleJob?.let { idle = it.await() }
                if (drop == CapsuleVoice.Drop.Edit) editSpoken(state.text) else sendSpoken(state.text)
            }
            is HoldToTalk.State.Problem -> {
                idleJob?.cancel()
                when (val why = state.why) {
                    null -> showNote(VoiceCopy.NO_MATCH, CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                    SpeechFailure.NoPermission -> showNote(tr(R.string.capsule_voice_need_mic), CapsuleKind.Failed, opensMina = true, NOTE_LONG_MS)
                    is SpeechFailure.Unavailable -> showNote(tr(R.string.capsule_voice_not_ready), CapsuleKind.Failed, opensMina = true, NOTE_LONG_MS)
                    else -> showNote(VoiceCopy.failure(why), CapsuleKind.Failed, opensMina = false, NOTE_LONG_MS)
                }
            }
            else -> idleJob?.cancel()
        }
    }

    /**
     * Release and send directly: Send to the conversation in the card that will be expanded by clicking
     * on it (the same as the double-click to send the screen), and the capsule will flash
     * "Interrupted/Answered/Sent:...". If the card does not have an input box (requiring your acceptance
     * and approval), do not send it. Expand the card and put the words into the input box first.
     */
    private fun sendSpoken(text: String) {
        val target = CapsuleCards.of(model.cardRuns(), follow, idle)
        val kind = target?.let(CapsuleCards::input)?.kind
        if (target == null || kind == null) {
            open()
            inputUi = inputUi.copy(draft = text)
            refresh()
            return
        }
        submit(target, kind, text, CapsuleCards.conversationOf(target))
        showNote(CapsuleVoice.sent(kind, text), CapsuleKind.Done, opensMina = false, NOTE_SENT_MS)
        refresh()
    }

    /** Drag to "Change" and release: Expand the card, the words will appear in the input box, the keyboard will pop up, and you can send it after you have made the changes. */
    private fun editSpoken(text: String) {
        open()
        inputUi = inputUi.copy(draft = text)
        refresh()
        if (card?.let(CapsuleCards::input) != null) openInput()
    }

    private fun showNote(text: String, kind: CapsuleKind, opensMina: Boolean, ms: Long) {
        note = CapsuleNote(text, kind, opensMina, ++noteCount)
        wake()
        noteJob?.cancel()
        noteJob = scope.launch {
            delay(ms)
            note = null
        }
    }

    private fun clearNote() {
        noteJob?.cancel()
        note = null
    }

    /** Click it when a sentence flashes on the capsule: if it says to open Mina, open it; otherwise, close that sentence and click as usual. */
    private fun tapCapsule() {
        val n = note
        clearNote()
        if (n?.opensMina == true) open(null) else tap()
    }

    private fun openShortcutMenu() {
        val service = windowOn ?: return
        if (menuWindow != null) return
        wake()
        val hasActive = tasks.runs.value.any { it.state.value.isActive() } ||
            com.meenai.mina.capsule.live.LiveShareManager.engine.isSharing
        menuWindow = service.overlays().open(
            OverlayPlacement(Gravity.CENTER),
            onOutside = ::closeShortcutMenu,
        ) {
            val reduceMotion = remember(context) { animationsOff(context) }
            MinaTheme(reduceMotion = reduceMotion) {
                CapsuleShortcutMenuOverlay(
                    onSelect = { shortcut ->
                        closeShortcutMenu()
                        handleShortcut(shortcut)
                    },
                    onDismiss = ::closeShortcutMenu,
                    hasActiveTask = hasActive,
                )
            }
        }
        menuWindow?.setFocusable(true, showIme = false)
        menuWindow?.onBack(::closeShortcutMenu)
    }

    private fun closeShortcutMenu() {
        menuWindow?.setFocusable(false)
        menuWindow?.close()
        menuWindow = null
    }

    private fun handleShortcut(shortcut: CapsuleShortcut) {
        when (shortcut) {
            CapsuleShortcut.LiveShare -> requestLiveShare()
            CapsuleShortcut.AskAboutScreen -> sendScreen()
            CapsuleShortcut.CircleToAsk -> requestCircleToAsk()
            CapsuleShortcut.ActHere -> requestActHere()
            CapsuleShortcut.Voice -> beginTalk(byTouch = false)
            CapsuleShortcut.TranslateHindi -> {
                scope.launch {
                    val shot = captureScreen() ?: return@launch
                    sendPromptWithScreen(shot, "इस स्क्रीन का हिंदी में अनुवाद करें।")
                }
            }
            CapsuleShortcut.PageSummary -> {
                scope.launch {
                    val shot = captureScreen() ?: return@launch
                    sendPromptWithScreen(shot, "इस पेज का मुख्य सारांश प्रस्तुत करें।")
                }
            }
            CapsuleShortcut.CopyText -> copyScreenText()
            CapsuleShortcut.StopTask -> stopActiveTask()
        }
    }

    private fun requestLiveShare() {
        if (com.meenai.mina.capsule.live.LiveShareManager.engine.isSharing) {
            com.meenai.mina.capsule.live.LiveShareManager.stop(context)
            showNote(tr(R.string.live_share_stopped), CapsuleKind.Cancelled, opensMina = false, NOTE_SHORT_MS)
            return
        }
        val started = com.meenai.mina.capsule.live.LiveShareManager.start(context)
        if (!started) {
            showNote(tr(R.string.capsule_sensitive_blocked), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
        } else {
            showNote(tr(R.string.live_share_started), CapsuleKind.Running, opensMina = false, NOTE_SHORT_MS)
        }
    }

    private var circleOverlayWindow: OverlayWindow? = null

    private fun hasCircleVisionModel(app: HarnessApp): Boolean =
        app.providers.configFor(ModelRole.Vision) != null ||
            app.providers.configFor(ModelRole.Main)?.capabilities?.vision == true ||
            app.freeKeyRouter.candidates.any {
                it.model.capabilities.vision ||
                    it.roles.contains(com.meenai.mina.core.router.picker.ModelRole.Vision)
            }

    private fun hasCircleAnyModel(app: HarnessApp): Boolean =
        hasCircleVisionModel(app) ||
            app.providers.configFor(ModelRole.Main) != null ||
            app.providers.configFor(ModelRole.Light) != null ||
            app.freeKeyRouter.candidates.isNotEmpty()

    private fun requestCircleToAsk() {
        val service = windowOn ?: return
        val w = window ?: return
        val app = context.applicationContext as HarnessApp
        val store = app.appAccess

        // 1. Model availability check (allows OCR + text model fallback when only non-vision models are set)
        if (!hasCircleAnyModel(app)) {
            showNote(tr(R.string.live_share_no_vision_model), CapsuleKind.Failed, opensMina = false, NOTE_LONG_MS)
            return
        }

        // 2. Security check & screenshot capture without capsule windows
        scope.launch {
            suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
            var sources = MainScreenCapture.sourcePackages(service)
            if (sources.isNullOrEmpty()) {
                delay(120L)
                sources = MainScreenCapture.sourcePackages(service)
            }
            val currentAccess = store.current()
            val isLockedBefore = CapsuleSecurityPolicy.isDeviceLocked(context)
            if (isLockedBefore || !currentAccess.available || sources.isNullOrEmpty() ||
                sources.any { CapsuleSecurityPolicy.isSensitiveApp(it, currentAccess) }
            ) {
                showNote(tr(R.string.capsule_sensitive_blocked), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                return@launch
            }
            for (pkg in sources) {
                if (!store.current().canShare(pkg) &&
                    !com.meenai.mina.cap.phone.a11y.requestScreenShare(service, store, pkg, appLabel(pkg))
                ) {
                    return@launch
                }
            }

            val shot = w.hidden {
                val cw = cardWindow
                if (cw != null) cw.hidden { service.takeMainScreenshot() } else service.takeMainScreenshot()
            }
            if (shot !is ScreenAccessibility.MainShot.Ok) {
                showNote(tr(R.string.capsule_failed), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                return@launch
            }
            val screenshot = shot.bitmap

            val currentSources = MainScreenCapture.sourcePackages(service)
            val isLockedAfter = CapsuleSecurityPolicy.isDeviceLocked(context)
            if (currentSources.isNullOrEmpty() ||
                !CapsuleSecurityPolicy.canPerformScreenAction(currentSources, isLockedAfter, store.current())
            ) {
                screenshot.recycle()
                showNote(tr(R.string.capsule_sensitive_blocked), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                return@launch
            }

            openCircleOverlay(service, screenshot, currentSources)
        }
    }

    private fun openCircleOverlay(
        service: ScreenAccessibility,
        screenshot: android.graphics.Bitmap,
        sources: Set<String>,
    ) {
        circleOverlayWindow?.close()
        var circleInputFocused = false
        var clearInputSignal by mutableIntStateOf(0)
        circleOverlayWindow = service.overlays().open(
            OverlayPlacement(Gravity.FILL),
            touchable = true
        ) {
            val reduceMotion = remember(context) { animationsOff(context) }
            MinaTheme(reduceMotion = reduceMotion) {
                com.meenai.mina.capsule.circle.CircleToAskOverlay(
                    screenshot = screenshot,
                    imeTopPx = imeTop,
                    onRequestKeyboard = { circleOverlayWindow?.setFocusable(true, showIme = true) },
                    onReleaseKeyboard = { circleOverlayWindow?.setFocusable(true, showIme = false) },
                    onInputFocusChanged = { circleInputFocused = it },
                    clearInputSignal = clearInputSignal,
                    onCancel = {
                        closeCircleOverlay(screenshot)
                    },
                    onAsk = { cropRect, question ->
                        closeCircleOverlay()
                        processCircleCrop(cropRect, screenshot, question, sources)
                    }
                )
            }
        }
        circleOverlayWindow?.setFullScreen(true)
        circleOverlayWindow?.setFocusable(true, showIme = false)
        circleOverlayWindow?.onBack {
            if (circleInputFocused) {
                clearInputSignal++
                circleOverlayWindow?.setFocusable(true, showIme = false)
                circleInputFocused = false
            } else {
                closeCircleOverlay(screenshot)
            }
        }
    }

    private fun closeCircleOverlay(bitmapToRecycle: android.graphics.Bitmap? = null) {
        circleOverlayWindow?.setFocusable(false)
        circleOverlayWindow?.close()
        circleOverlayWindow = null
        bitmapToRecycle?.recycle()
    }

    private fun processCircleCrop(
        cropRect: com.meenai.mina.capsule.circle.CropRect,
        fullBitmap: android.graphics.Bitmap,
        question: String,
        sources: Set<String>,
    ) {
        scope.launch(Dispatchers.Default) {
            val app = context.applicationContext as HarnessApp
            val hasVision = hasCircleVisionModel(app)
            val hasAnyModel = hasVision || hasCircleAnyModel(app)
            if (!hasAnyModel) {
                fullBitmap.recycle()
                withContext(Dispatchers.Main) {
                    showNote(tr(R.string.live_share_no_vision_model), CapsuleKind.Failed, opensMina = false, NOTE_LONG_MS)
                }
                return@launch
            }

            val cropX = cropRect.left.toInt().coerceIn(0, (fullBitmap.width - 1).coerceAtLeast(0))
            val cropY = cropRect.top.toInt().coerceIn(0, (fullBitmap.height - 1).coerceAtLeast(0))
            val cropW = minOf(cropRect.width.toInt().coerceAtLeast(1), (fullBitmap.width - cropX).coerceAtLeast(1))
            val cropH = minOf(cropRect.height.toInt().coerceAtLeast(1), (fullBitmap.height - cropY).coerceAtLeast(1))

            val cropped = android.graphics.Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
            fullBitmap.recycle()

            val (targetW, targetH) = com.meenai.mina.capsule.circle.CircleCropGeometry.calculateScaledDimensions(cropW, cropH, 1080)
            val scaled = if (targetW != cropW || targetH != cropH) {
                val s = android.graphics.Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                cropped.recycle()
                s
            } else cropped

            // Extract text inside the circled crop so both multimodal and OCR-text fallback models see crisp text.
            val ocrRaw = withTimeoutOrNull(2_500L) {
                runCatching {
                    val copyForOcr = scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching emptyList()
                    try {
                        com.meenai.mina.cap.phone.a11y.PhoneOcr.read(copyForOcr)
                    } finally {
                        if (!copyForOcr.isRecycled) copyForOcr.recycle()
                    }
                }.getOrNull()
            }.orEmpty()
            val ocrLines = ocrRaw.map { line ->
                val cx = if (line.bounds.size >= 4) (line.bounds[0] + line.bounds[2]) / 2 else 500
                val cy = if (line.bounds.size >= 4) (line.bounds[1] + line.bounds[3]) / 2 else 500
                com.meenai.mina.core.userscreen.ScreenLine(line.text, cx, cy)
            }

            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
            scaled.recycle()

            val jpegBytes = stream.toByteArray()
            val promptText = if (question.isNotBlank()) question else tr(R.string.circle_to_ask_prompt_default)
            val ocrNote = ocrLines.joinToString("\n") { it.text }.trim().takeIf { it.isNotEmpty() }

            val convId = withContext(Dispatchers.Main) {
                if (view?.kind == CapsuleKind.Idle && idle == null) idle = loadIdle()
                val target = CapsuleCards.of(model.cardRuns(), follow, idle)
                target?.let(CapsuleCards::conversationOf) ?: ConversationStore.shared(context).newConversation()
            }

            if (!hasVision) {
                val textFallbackPrompt = when {
                    ocrNote != null -> "$promptText\n\n[Extracted text from selected region]:\n$ocrNote"
                    question.isNotBlank() -> question
                    else -> null
                }
                if (textFallbackPrompt == null) {
                    withContext(Dispatchers.Main) {
                        showNote(tr(R.string.live_share_no_vision_model), CapsuleKind.Failed, opensMina = false, NOTE_LONG_MS)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val target = CapsuleCards.of(model.cardRuns(), follow, idle)
                    val kind = target?.let(CapsuleCards::input)?.kind
                    if (target != null && CapsuleCards.conversationOf(target) == convId &&
                        kind != null && (kind == CardInputKind.Steer || kind == CardInputKind.Answer)
                    ) {
                        submit(target, kind, textFallbackPrompt, convId)
                    } else {
                        startIn(convId, textFallbackPrompt)
                    }
                    clearNote()
                    open()
                    refresh()
                }
                return@launch
            }

            val topPkg = sources.firstOrNull { it != context.packageName } ?: sources.firstOrNull()
            val topLabel = topPkg?.let { ConfirmationNotifier.appLabel(context, it) }
            val userScreen = com.meenai.mina.core.userscreen.UserScreen(
                id = "",
                takenAt = System.currentTimeMillis(),
                packageName = topPkg,
                appLabel = topLabel,
                lines = ocrLines,
                fromOcr = ocrLines.isNotEmpty(),
            )
            val cropShot = CapturedScreen(jpegBytes, userScreen, sources)

            val saved = runCatching { app.userScreens.save(convId, cropShot) }
                .onFailure { Log.w(TAG, "could not save circle crop", it) }
                .getOrNull()

            withContext(Dispatchers.Main) {
                if (saved != null) {
                    val message = UserScreenMessage.compose(saved.id, promptText)
                    val target = CapsuleCards.of(model.cardRuns(), follow, idle)
                    val kind = target?.let(CapsuleCards::input)?.kind
                    if (target != null && CapsuleCards.conversationOf(target) == convId &&
                        kind != null && (kind == CardInputKind.Steer || kind == CardInputKind.Answer)
                    ) {
                        submit(target, kind, message, convId)
                    } else {
                        startIn(convId, message)
                    }
                    clearNote()
                    open()
                    refresh()
                } else {
                    // Pure fallback via direct vision query if workspace storage failed
                    val visionModel: ChatModel? = if (app.freeKeyRouter.candidates.isNotEmpty()) {
                        app.freeKeyRouter.resetTask()
                        app.freeKeyRouter.startTask(promptText, hasImage = true, charCount = promptText.length)
                        app.freeKeyRouter
                    } else {
                        app.providers.modelFor(ModelRole.Vision)
                            ?: app.providers.modelFor(ModelRole.Main)?.takeIf { it.capabilities.vision }
                    }
                    val enrichedPrompt = if (ocrNote != null) "$promptText\n\n[Text in selected region]:\n$ocrNote" else promptText
                    val reply = visionModel?.let { vm ->
                        withContext(Dispatchers.Default) {
                            runCatching {
                                com.meenai.mina.capsule.circle.CircleToAskSender.askAboutCrop(vm, jpegBytes, targetW, targetH, enrichedPrompt)
                            }.getOrNull()
                        }
                    }
                    val replyText = reply?.let { com.meenai.mina.capsule.circle.CircleToAskSender.textOf(it) }
                    if (!replyText.isNullOrBlank()) {
                        app.conversations.append(
                            convId,
                            com.meenai.mina.core.agent.Turn(
                                userText = promptText,
                                outcome = replyText,
                                status = "succeeded",
                            )
                        )
                        follow = convId
                        idle = CardView.Chat(convId, title = ScreenCopy.plain(promptText))
                        loadPast(convId)
                        clearNote()
                        open()
                        refresh()
                    } else {
                        showNote(tr(R.string.capsule_failed), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                    }
                }
            }
        }
    }

    private val actHereCoordinator = com.meenai.mina.capsule.acthere.ActHereCoordinator(
        isDeviceLocked = { CapsuleSecurityPolicy.isDeviceLocked(context) },
    )
    private var actHereJob: Job? = null
    private var actHerePromptWindow: OverlayWindow? = null
    private var actHereConfirmWindow: OverlayWindow? = null
    private var actHereHighlightWindow: OverlayWindow? = null
    private var actHereControlWindow: OverlayWindow? = null
    private var actHereMaxStepsWindow: OverlayWindow? = null

    private fun requestActHere() {
        val service = windowOn ?: return
        val app = context.applicationContext as HarnessApp
        val store = app.appAccess

        val sources = MainScreenCapture.sourcePackages(service)
        if (sources.isNullOrEmpty() || !actHereCoordinator.canOpen(sources, store.current())) {
            showNote(tr(R.string.capsule_sensitive_blocked), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
            return
        }

        openActHerePrompt(service)
    }

    private fun openActHerePrompt(service: ScreenAccessibility) {
        actHerePromptWindow?.close()
        actHerePromptWindow = service.overlays().open(
            OverlayPlacement(Gravity.CENTER),
            touchable = true
        ) {
            val reduceMotion = remember(context) { animationsOff(context) }
            MinaTheme(reduceMotion = reduceMotion) {
                com.meenai.mina.capsule.acthere.ActHerePromptOverlay(
                    onStart = { goal ->
                        actHerePromptWindow?.setFocusable(false)
                        actHerePromptWindow?.close()
                        actHerePromptWindow = null
                        startActHere(service, goal)
                    },
                    onCancel = {
                        actHerePromptWindow?.setFocusable(false)
                        actHerePromptWindow?.close()
                        actHerePromptWindow = null
                    }
                )
            }
        }
        actHerePromptWindow?.setFocusable(true, showIme = false)
        actHerePromptWindow?.onBack {
            actHerePromptWindow?.setFocusable(false)
            actHerePromptWindow?.close()
            actHerePromptWindow = null
        }
    }

    private fun stopActHere() {
        actHereCoordinator.stop()
        actHereJob?.cancel()
        actHereJob = null
        actHerePromptWindow?.setFocusable(false)
        actHerePromptWindow?.close()
        actHerePromptWindow = null
        actHereConfirmWindow?.close()
        actHereConfirmWindow = null
        actHereHighlightWindow?.close()
        actHereHighlightWindow = null
        actHereControlWindow?.close()
        actHereControlWindow = null
        actHereMaxStepsWindow?.close()
        actHereMaxStepsWindow = null
    }

    private fun startActHere(service: ScreenAccessibility, goal: String) {
        val app = context.applicationContext as HarnessApp
        val store = app.appAccess

        stopActHere()
        actHereCoordinator.reset()

        openActHereControlBar(service)

        actHereJob = scope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                showNote(tr(R.string.capsule_running), CapsuleKind.Running, opensMina = false, 15000L)
            }

            val routerModel: ChatModel? = if (app.freeKeyRouter.candidates.isNotEmpty()) {
                app.freeKeyRouter.resetTask()
                app.freeKeyRouter
            } else {
                val candidates = com.meenai.mina.ui.router.AppFreeKeyRouter.createCandidates(app.providers)
                if (candidates.isNotEmpty()) {
                    com.meenai.mina.ui.router.AppFreeKeyRouter.createRouterForCandidates(candidates, preferences = app.modelPreferences)
                } else {
                    val main = app.providers.modelFor(ModelRole.Main)
                    val light = app.providers.modelFor(ModelRole.Light)
                    if (main != null) {
                        val list = mutableListOf(
                            com.meenai.mina.core.router.RouterCandidate(
                                id = main.name.ifBlank { "main" },
                                provider = "configured",
                                model = main,
                                curatedRank = 1,
                                isFree = false
                            )
                        )
                        light?.let {
                            list.add(
                                com.meenai.mina.core.router.RouterCandidate(
                                    id = it.name.ifBlank { "light" },
                                    provider = "configured",
                                    model = it,
                                    curatedRank = 2,
                                    isFree = false
                                )
                            )
                        }
                        com.meenai.mina.ui.router.AppFreeKeyRouter.createRouterForCandidates(list, preferences = app.modelPreferences)
                    } else null
                }
            }

            if (routerModel == null) {
                withContext(Dispatchers.Main) {
                    showNote(tr(R.string.act_here_no_model), CapsuleKind.Failed, opensMina = false, NOTE_SHORT_MS)
                    stopActHere()
                }
                return@launch
            }

            val fetchScreen: suspend () -> ScreenSnapshotData = {
                val currentSources = MainScreenCapture.sourcePackages(service)
                val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val m = service.resources.displayMetrics
                val size = runCatching { service.overlays().area() }.getOrNull()
                val screenWidth = (size?.width ?: m.widthPixels).toFloat()
                val screenHeight = (size?.height ?: m.heightPixels).toFloat()
                val screenRead = ScreenReader.read(windows, screenWidth.toInt(), screenHeight.toInt())
                val elements = screenRead.entries.mapNotNull { entry ->
                    val name = entry.element.name
                    if (name.isBlank() && !entry.element.clickable && !entry.element.editable) null
                    else {
                        UiElementSummary(
                            label = name,
                            x = entry.element.centerX.toFloat(),
                            y = entry.element.centerY.toFloat(),
                            clickable = entry.element.clickable,
                            editable = entry.element.editable,
                            secret = entry.element.secret,
                            bounds = if (entry.element.bounds.size == 4) {
                                listOf(
                                    entry.element.bounds[0] * screenWidth / 1000f,
                                    entry.element.bounds[1] * screenHeight / 1000f,
                                    entry.element.bounds[2] * screenWidth / 1000f,
                                    entry.element.bounds[3] * screenHeight / 1000f
                                )
                            } else emptyList()
                        )
                    }
                }
                val topPkg = currentSources?.firstOrNull() ?: screenRead.topPackage
                ScreenSnapshotData(
                    elements = elements,
                    screenTexts = screenRead.texts,
                    foregroundPackages = currentSources,
                    appAccessState = store.current(),
                    topPackage = topPkg
                )
            }

            actHereCoordinator.runActHereLoop(
                model = routerModel,
                goal = goal,
                fetchScreen = fetchScreen,
                onStatus = { statusMsg ->
                    scope.launch(Dispatchers.Main) {
                        showNote(statusMsg, CapsuleKind.Running, opensMina = false, 3000L)
                    }
                },
                onHighlight = { x, y ->
                    withContext(Dispatchers.Main) {
                        showTapHighlight(service, x, y)
                    }
                    delay(300L)
                    withContext(Dispatchers.Main) {
                        hideTapHighlight()
                    }
                },
                onHandover = {
                    scope.launch(Dispatchers.Main) {
                        showNote(tr(R.string.act_here_handover_payment), CapsuleKind.Done, opensMina = false, NOTE_LONG_MS)
                    }
                },
                onConfirm = { label ->
                    promptConfirmation(service, label)
                },
                onMaxStepsPrompt = {
                    promptMaxSteps(service)
                },
                onStopped = {
                    scope.launch(Dispatchers.Main) {
                        showNote(tr(R.string.capsule_task_stopped), CapsuleKind.Cancelled, opensMina = false, NOTE_SHORT_MS)
                    }
                },
                performTap = { x, y ->
                    val success = service.taps(Display.DEFAULT_DISPLAY, x, y, 1, 50L, 0L)
                    delay(800L)
                    success
                },
                performType = { text, x, y ->
                    if (x > 0f && y > 0f) {
                        service.taps(Display.DEFAULT_DISPLAY, x, y, 1, 50L, 0L)
                        delay(300L)
                    }
                    val activeNode = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    val success = if (activeNode != null) {
                        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                        activeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    } else false
                    delay(500L)
                    success
                },
                performSwipe = { dir ->
                    val m = service.resources.displayMetrics
                    val w = m.widthPixels.toFloat()
                    val h = m.heightPixels.toFloat()
                    val path = Path()
                    when (dir.lowercase()) {
                        "up" -> { path.moveTo(w / 2f, h * 0.7f); path.lineTo(w / 2f, h * 0.3f) }
                        "down" -> { path.moveTo(w / 2f, h * 0.3f); path.lineTo(w / 2f, h * 0.7f) }
                        "left" -> { path.moveTo(w * 0.8f, h / 2f); path.lineTo(w * 0.2f, h / 2f) }
                        "right" -> { path.moveTo(w * 0.2f, h / 2f); path.lineTo(w * 0.8f, h / 2f) }
                        else -> { path.moveTo(w / 2f, h * 0.7f); path.lineTo(w / 2f, h * 0.3f) }
                    }
                    val success = service.gesture(Display.DEFAULT_DISPLAY, path, 300L)
                    delay(800L)
                    success
                }
            )

            withContext(Dispatchers.Main) {
                stopActHere()
            }
        }
    }

    private fun showTapHighlight(service: ScreenAccessibility, x: Float, y: Float) {
        actHereHighlightWindow?.close()
        actHereHighlightWindow = service.overlays().open(
            OverlayPlacement(Gravity.FILL),
            touchable = false
        ) {
            val reduceMotion = remember(context) { animationsOff(context) }
            MinaTheme(reduceMotion = reduceMotion) {
                com.meenai.mina.capsule.acthere.ActHereTapHighlightOverlay(x = x, y = y)
            }
        }
    }

    private fun hideTapHighlight() {
        actHereHighlightWindow?.close()
        actHereHighlightWindow = null
    }

    private fun openActHereControlBar(service: ScreenAccessibility) {
        actHereControlWindow?.close()
        actHereControlWindow = service.overlays().open(
            OverlayPlacement(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, y = 120),
            touchable = true
        ) {
            val reduceMotion = remember(context) { animationsOff(context) }
            MinaTheme(reduceMotion = reduceMotion) {
                com.meenai.mina.capsule.acthere.ActHereControlBar(
                    onStop = {
                        stopActHere()
                        showNote(tr(R.string.capsule_task_stopped), CapsuleKind.Cancelled, opensMina = false, NOTE_SHORT_MS)
                    },
                    onTakeover = {
                        stopActHere()
                        showNote(tr(R.string.capsule_you_handle), CapsuleKind.Done, opensMina = false, NOTE_SHORT_MS)
                    }
                )
            }
        }
    }

    private suspend fun promptConfirmation(service: ScreenAccessibility, actionLabel: String): Boolean =
        suspendCancellableCoroutine { cont ->
            scope.launch(Dispatchers.Main) {
                actHereConfirmWindow?.close()
                actHereConfirmWindow = service.overlays().open(
                    OverlayPlacement(Gravity.CENTER),
                    touchable = true
                ) {
                    val reduceMotion = remember(context) { animationsOff(context) }
                    MinaTheme(reduceMotion = reduceMotion) {
                        com.meenai.mina.capsule.acthere.ActHereConfirmationOverlay(
                            actionLabel = actionLabel,
                            onConfirm = {
                                actHereConfirmWindow?.close()
                                actHereConfirmWindow = null
                                if (cont.isActive) cont.resume(true)
                            },
                            onCancel = {
                                actHereConfirmWindow?.close()
                                actHereConfirmWindow = null
                                if (cont.isActive) cont.resume(false)
                            }
                        )
                    }
                }
                actHereConfirmWindow?.onBack {
                    actHereConfirmWindow?.close()
                    actHereConfirmWindow = null
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    private suspend fun promptMaxSteps(service: ScreenAccessibility): Boolean =
        suspendCancellableCoroutine { cont ->
            scope.launch(Dispatchers.Main) {
                actHereMaxStepsWindow?.close()
                actHereMaxStepsWindow = service.overlays().open(
                    OverlayPlacement(Gravity.CENTER),
                    touchable = true
                ) {
                    val reduceMotion = remember(context) { animationsOff(context) }
                    MinaTheme(reduceMotion = reduceMotion) {
                        com.meenai.mina.capsule.acthere.ActHereMaxStepsOverlay(
                            onContinue = {
                                actHereMaxStepsWindow?.close()
                                actHereMaxStepsWindow = null
                                if (cont.isActive) cont.resume(true)
                            },
                            onStop = {
                                actHereMaxStepsWindow?.close()
                                actHereMaxStepsWindow = null
                                if (cont.isActive) cont.resume(false)
                            }
                        )
                    }
                }
                actHereMaxStepsWindow?.onBack {
                    actHereMaxStepsWindow?.close()
                    actHereMaxStepsWindow = null
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    private fun stopActiveTask() {
        if (com.meenai.mina.capsule.live.LiveShareManager.engine.isSharing) {
            com.meenai.mina.capsule.live.LiveShareManager.stop(context)
        }
        stopActHere()
        val active = (view as? CapsuleView.Shown)?.runKey ?: tasks.runs.value.firstOrNull { it.state.value.isActive() }?.key
        if (active != null) {
            TaskService.cancel(context, active)
            showNote(tr(R.string.capsule_task_stopped), CapsuleKind.Cancelled, opensMina = false, NOTE_SHORT_MS)
        }
    }

    private fun copyScreenText() {
        if (capturing) return
        scope.launch {
            val shot = captureScreen() ?: return@launch
            val text = shot.screen.lines.joinToString("\n") { it.text }
            if (text.isNotBlank()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Screen text", text))
                showNote(tr(R.string.capsule_text_copied), CapsuleKind.Done, opensMina = false, NOTE_SHORT_MS)
            } else {
                showNote(tr(R.string.capsule_text_copied), CapsuleKind.Done, opensMina = false, NOTE_SHORT_MS)
            }
        }
    }

    private fun sendPromptWithScreen(shot: CapturedScreen, prompt: String) {
        val v = view ?: return
        scope.launch {
            if (v.kind == CapsuleKind.Idle) idle = loadIdle()
            val target = CapsuleCards.of(model.cardRuns(), follow, idle)
            val kind = target?.let(CapsuleCards::input)?.kind
            if (target == null || kind == null) {
                expand()
                return@launch
            }
            deliver(target, kind, shot, prompt)
            CapsuleCards.conversationOf(target)?.let { follow = it }
            open()
            refresh()
        }
    }

    // ---------- Tap and Card ----------

    /** Click on a pill or capsule: expand immediately without waiting on a double-tap timeout (send-screen remains on double-tap accessibility action). */
    private fun tap() {
        if (capturing) return
        expand()
    }

    /** Expand the card (the idle icon is an idle card). Each time it is expanded, the idle card is reread according to which conversation the user is in at the moment, without taking the last copy. */
    private fun expand() {
        val v = view ?: return
        if (v.kind == CapsuleKind.Idle) {
            openIdle()
            return
        }
        open()
        refresh()
        scope.launch {
            val fresh = loadIdle()
            if (fresh != idle) {
                idle = fresh
                refresh()
            }
        }
    }

    /** Expand the card: first note the position of the one at the moment, and the card will grow from there. */
    private fun open() {
        if (!expanded) anchorY = placedY + (window?.height ?: 0) / 2
        expanded = true
        closing = false
        cardWindow?.setFocusable(true, showIme = false)
    }

    /** Click outside the window: first close the enlarged screen, then close the card. */
    private fun outside() {
        when {
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    private fun freshChat(fresh: Boolean) {
        idle = idle?.copy(fresh = fresh)
        refresh()
    }

    /** Collect the card: The input box is immediately closed (the focus is returned to the app below), and the card is really closed only after it is retracted to that position ([closed]); it is closed directly when the animation is turned off. */
    private fun collapse() {
        if (!expanded) return
        closeInput()
        cardWindow?.setFocusable(false)
        if (closing) return
        if (animationsOff(context) || card == null) {
            resetCard()
            refresh()
            return
        }
        closing = true
    }

    private fun closed() {
        if (!closing) return
        resetCard()
        refresh()
    }

    /** The origin of the card scaling: the side where the card is docked, and the height is the position of the center of the card on the card (take the upper and lower sides when it exceeds the card). */
    private fun cardOrigin(size: Size): TransformOrigin {
        val y = if (size.height > 0f) ((anchorY - cardTop) / size.height).coerceIn(0f, 1f) else 0f
        return TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, y)
    }

    private fun runFor(key: String): TaskRun? = tasks.runs.value.firstOrNull { it.key == key }

    private val actions = object : CardActions {
        override fun collapse() = this@CapsuleController.collapse()
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) {
            // Just like "I'll handle it" on the notification, it also carries the number of this wait:
            // clicking on the old wait that has been replaced does not count.
            TaskService.takeOver(context, waitKey = runFor(runKey)?.takeover?.waitKey, runKey = runKey)
            collapse()
        }
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMina(conversationId: String) {
            open(conversationId)
            collapse()
        }
        override fun zoom(on: Boolean) = this@CapsuleController.zoom(on)
        override fun openInput() = this@CapsuleController.openInput()
        override fun draft(text: String) {
            inputUi = inputUi.copy(draft = text)
        }
        override fun send() = this@CapsuleController.send()
        override fun freshChat(fresh: Boolean) = this@CapsuleController.freshChat(fresh)
        override fun loadEarlier() = this@CapsuleController.loadEarlier()
        override fun captureScreen() = captureForCard()
        override fun dropScreen() {
            inputUi = inputUi.copy(screen = null)
        }
        override fun closeKeptPage() {
            keeper.close(KeptPageClose.User)
            collapse()
        }
    }

    /** In the card window: it is a card when it is expanded, and nothing when it is collapsed (the window has no size). */
    @Composable
    private fun CardOverlay() {
        val c = card ?: return
        val reduceMotion = remember(context) { animationsOff(context) }
        MinaTheme(reduceMotion = reduceMotion) {
            val height = if (cardHeight > 0) with(LocalDensity.current) { cardHeight.toDp() } else Dp.Unspecified
            OverlayMotion(open = !closing, origin = ::cardOrigin, onClosed = ::closed, onOpened = { cardOut = true }) {
                CapsuleCard(c, preview, zoomed, actions, { elapsed(it) }, input = inputUi, thread = thread, height = height, onHandle = ::onHandle)
            }
        }
    }

    /** In the panel window: press the layer that fills the screen when speaking, and stick to the position of the star at the moment; usually there is nothing. */
    @Composable
    private fun VoicePanelOverlay() {
        if (!talk.active) return
        val a = windowOn?.overlays()?.area()
        val top = placedY + room()
        val bottom = placedY + (window?.height ?: 0) - room()
        val reduceMotion = remember(context) { animationsOff(context) }
        MinaTheme(reduceMotion = reduceMotion) {
            CapsuleVoicePanel(talk, top, bottom.coerceAtLeast(top), a?.insetTop ?: 0, a?.insetBottom ?: 0)
        }
    }

    @Composable
    private fun DismissZoneOverlay() {
        if (!dismissShowing) return
        val reduceMotion = remember(context) { animationsOff(context) }
        MinaTheme(reduceMotion = reduceMotion) {
            CapsuleDismissZone(hovered = dismissHovered)
        }
    }

    /** In the window on the side: a pill, a capsule or that one; when the card comes out, it is empty, and when the card is retracted, a pill pops out from the side at the same time. Display that sentence when the sentence is flashing on the capsule. */
    @Composable
    private fun CapsuleOverlay() {
        val shown = view ?: return
        if (card != null && cardOut && !closing) return
        val n = note
        val v = if (n == null || shown.kind == CapsuleKind.HandedOver) shown
            else shown.copy(kind = n.kind, text = n.text, detail = null, count = 0, needCount = 0, pill = true, widthEpoch = -n.id)
        val talking = talk.active && !talk.released
        val reduceMotion = remember(context) { animationsOff(context) }
        MinaTheme(reduceMotion = reduceMotion) {
            OverlayMotion(open = true, origin = { TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, 0.5f) }, onClosed = {}) {
                CapsuleContent(
                    v,
                    onTap = ::tapCapsule,
                    onSendScreen = ::sendScreen,
                    flash = flash,
                    onStop = { v.runKey?.let { TaskService.cancel(context, it) } },
                    onHandBack = { v.runKey?.let { TaskService.handBack(context, runKey = it) } },
                    side = dock.side,
                    tapLabel = when {
                        n?.opensMina == true -> tr(R.string.capsule_voice_open)
                        v.kind == CapsuleKind.Idle -> CapsuleCopy.TALK
                        else -> CapsuleCopy.EXPAND
                    },
                    onSwapSide = if (v.kind == CapsuleKind.HandedOver) null else ::swapSide,
                    alpha = alpha,
                    holding = talking && talk.byTouch,
                    onTalk = if (canTalk()) ::toggleTalk else null,
                    talkLabel = tr(if (talking && !talk.byTouch) R.string.capsule_voice_send else R.string.capsule_voice_talk),
                )
            }
        }
    }

    /** Elapsed time, updated every second; null when the elapsed time is not displayed. */
    @Composable
    private fun elapsed(startedAt: Long?): String? {
        if (startedAt == null) return null
        val now by produceState(System.currentTimeMillis(), startedAt) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000L - value % 1_000L)
            }
        }
        return ChatCopy.clock(now - startedAt)
    }

    /** Go back to Mina's conversation (the same entrance as the notification). */
    private fun open(conversationId: String?) {
        runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
            .onFailure { Log.w(TAG, "could not open Mina from capsule", it) }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    private fun reasonLabel(reason: String): String? = when (reason) {
        "captcha" -> R.string.takeover_reason_captcha
        "login" -> R.string.takeover_reason_login
        "access_blocked" -> R.string.takeover_reason_access_blocked
        "payment" -> R.string.takeover_reason_payment
        // "I need you to decide" is repeated after "I need you:", if it is written in a model
        else -> null
    }?.let(context::getString)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "MinaCapsule"
        /** Visible capsules are located on the left and right sides of the screen, the status bar, and the bottom. */
        const val EDGE_MARGIN_DP = 12
        const val TOP_GAP_DP = 8
        /** The window height of the capsule, that one and the shadow edge (used when setting the height proportionally). */
        const val PILL_DP = 40 + 16
        const val BAR_DP = 48 + 16
        const val SNAP_MS = 220L

        const val ROTATE_SETTLE_MS = 300L
        /** When speaking, the two landing points each expand outward to a certain extent, which is considered to be delayed. */
        const val TARGET_SLOP_DP = 14
        /** How long does it take for the sentence that flashes on the capsule to stop: Sent, not heard clearly, error or unavailable. */
        const val NOTE_SENT_MS = 1_500L
        const val NOTE_SHORT_MS = 2_000L
        const val NOTE_LONG_MS = 3_500L
        /** Put away the input method in the card before taking a screenshot. Wait for this long at most. */
        const val IME_GONE_MS = 700L
        const val IME_POLL_MS = 30L
    }
}

/** A sentence flashes temporarily on the capsule: [kind] marks the front of the setting (if it is completed, it will be a check, if it is not completed, it will be an exclamation mark); [opensMina], click it to open Mina; [id], each sentence is different (click it to reset the width of the capsule). */
private data class CapsuleNote(val text: String, val kind: CapsuleKind, val opensMina: Boolean, val id: Int)
