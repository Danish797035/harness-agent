package com.meenai.mina.ui

import androidx.compose.foundation.gestures.scrollBy
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.ui.components.keepsKeyboard
import com.meenai.mina.ui.router.ActiveModel
import com.meenai.mina.ui.router.LocalRouterStatus
import com.meenai.mina.ui.router.RouterBottomSheet
import com.meenai.mina.ui.router.RouterChip
import com.meenai.mina.ui.router.RouterStatus
import com.meenai.mina.ui.router.SwitchEvent
import com.meenai.mina.ui.router.SwitchNoticeToast
import com.meenai.mina.ui.router.resolveDisplayModel
import com.meenai.mina.ui.router.waitModelName
import com.meenai.mina.ui.image.GeneratedImageCard
import com.meenai.mina.ui.image.GeneratedImageUi
import com.meenai.mina.image.ImageStorageManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.task.SteerNote
import com.meenai.mina.task.SteerState
import com.meenai.mina.task.TaskService
import com.meenai.mina.task.unconsumed
import com.meenai.mina.ui.components.BarIconButton
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GlassBackdrop
import com.meenai.mina.ui.components.GlassBar
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.cardSurface
import com.meenai.mina.ui.components.floatShadow
import com.meenai.mina.ui.components.glassSource
import com.meenai.mina.ui.components.rememberGlassBackdrop
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.viewer.FileViewer
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.voice.HoldToTalk
import com.meenai.mina.voice.VoiceSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * Conversation page: top bar (open sidebar on the left, conversation title and what you are doing at the
 * moment in the middle, new conversation on the right), the conversation scrolls between the top bar and
 * the glass input field. In progress is a line of status plus a vertical line of steps, without cards;
 * you are asked to stop at the current step when processing, with a white card below; when completed,
 * the process will be in one line, and the answers will be arranged directly on the background. The
 * Agent's virtual screen covers the conversation with a small floating window ([AgentPip]), which does
 * not occupy the layout.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    frames: StateFlow<Bitmap?>,
    webOpen: StateFlow<Boolean>,
    reduceMotion: Boolean,
    /** Upper left corner menu: Open the sidebar. */
    onOpenDrawer: () -> Unit,
    /** "Go to Settings" in the stopped prompt. */
    openSettings: () -> Unit,
    openRun: (String) -> Unit = {},
    /** The task requires the use of a mobile phone and the mobile phone operation is not ready: put a line under the last round to set it up. */
    phoneSetupNeeded: Boolean = false,
    openPhoneSetup: () -> Unit = {},
    /** The source of the basis is obtained based on the running record (the "source" capsule and the screenshot after clicking); the disk reading activity is placed in the background thread by the caller. */
    loadSource: suspend (runId: String) -> EvidenceSource? = { null },
    openSource: (runId: String?, legacyText: String?) -> Unit = { _, _ -> },
    /** Press and hold the input field to speak and start a session from here; when null, you can only type. */
    voice: VoiceSource? = null,
    /** "Go to select" in "Voice input is not yet available". */
    openSpeechModel: () -> Unit = {},
    routerStatus: RouterStatus = LocalRouterStatus.current,
) {
    val routerActiveModel by routerStatus.current.collectAsState()
    val routerUsages by routerStatus.usage.collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }
    var latestSwitchEvent by remember { mutableStateOf<SwitchEvent?>(null) }
    val ui by vm.ui.collectAsState()
    val last = ui.exchanges.lastOrNull()
    val isTaskRunning = last?.status?.isActive() == true
    // The model usage changes while the task is running: it is refreshed regularly, and the "how many
    // times today/how many are left" on the model label follows.
    LaunchedEffect(isTaskRunning) {
        while (isTaskRunning) {
            routerStatus.refresh()
            delay(4_000L)
        }
    }
    val activeModel = remember(routerActiveModel, routerUsages, isTaskRunning, last?.activeModel) {
        resolveDisplayModel(
            routerModel = routerActiveModel,
            runningModelId = last?.activeModel,
            isTaskRunning = isTaskRunning,
            usages = routerUsages,
        )
    }
    val others by vm.otherRunning.collectAsState()
    val keptAny by vm.keptPage.collectAsState()
    // Just leave the page left behind by this conversation
    val kept = keptAny?.takeIf { it.conversationId == ui.conversationId }
    val live by frames.collectAsState()
    val webLive by webOpen.collectAsState()
    // The virtual screen is released as soon as the task is completed; when the task is completed, the
    // small window will stay for a few seconds and the check box will continue to display the last
    // frame.
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastFrameWasWeb by remember { mutableStateOf(false) }
    LaunchedEffect(live, webLive, ui.pip.mode) {
        live?.let {
            lastFrame = it
            if (webLive) lastFrameWasWeb = true
            else if (ui.pip.mode != PipMode.Done) lastFrameWasWeb = false
        }
    }
    val frame = live ?: lastFrame.takeIf { ui.pip.visible && ui.pip.mode == PipMode.Done }
    val webContent = if (ui.pip.mode == PipMode.Done) lastFrameWasWeb else webLive
    var input by rememberSaveable { mutableStateOf("") }
    var voiceHolding by remember { mutableStateOf(false) }
    var editRequest by remember { mutableIntStateOf(0) }
    var composerHeight by rememberSaveable { mutableIntStateOf(0) }
    var topHeight by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Only continue to follow the latest news. This state is saved across pages along with the list
    // position.
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var previousScrollMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var previousConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val tailThresholdPx = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(listState, tailThresholdPx) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.nearTail(tailThresholdPx) }.collect { (scrolling, nearTail) ->
            if (scrolling || wasScrolling) followingTail = nearTail
            wasScrolling = scrolling
        }
    }

    // The same piece of content is not re-scrolled when returning to the chat; new developments are only
    // scrolled when following the end.
    val scrollMarker = "${ui.conversationId}:${ui.exchanges.size}:${last?.steps?.size}:${last?.steers?.size}:${last?.status?.hashCode()}:${ui.blocked?.hashCode()}"
    LaunchedEffect(scrollMarker) {
        if (previousScrollMarker == scrollMarker) return@LaunchedEffect
        val firstEntry = previousScrollMarker == null
        val changedConversation = previousConversation != ui.conversationId
        previousScrollMarker = scrollMarker
        previousConversation = ui.conversationId
        if (changedConversation) followingTail = true
        if (!firstEntry && !changedConversation && !followingTail) return@LaunchedEffect
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            if (firstEntry || changedConversation || reduceMotion) listState.scrollToItem(count - 1)
            else listState.animateScrollToItem(count - 1)
        }
    }
    // When the flowing text grows, it follows the bottom; when the user actively scrolls up, it does not
    // pull back.
    LaunchedEffect(last?.draft?.length) {
        if (last?.draft.isNullOrEmpty() || !followingTail || listState.isScrollInProgress) return@LaunchedEffect
        val layout = listState.layoutInfo
        val end = layout.totalItemsCount - 1
        val draft = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return@LaunchedEffect
        val tailGap = draft.offset + draft.size - layout.viewportEndOffset
        if (tailGap > with(density) { 240.dp.roundToPx() }) return@LaunchedEffect
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val offset = (draft.size - viewport + with(density) { 12.dp.roundToPx() }).coerceAtLeast(0)
        listState.scrollToItem(end, offset)
    }

    // The input field becomes taller (mostly due to the pop-up of the input method, or attachments, or
    // multi-line input): when originally following the latest message, the conversation flow went
    // upwards by the same distance, and the last one was still attached to the input field; the user did
    // not move while scrolling upwards. The list will retract itself when it becomes shorter.
    var lastComposerHeight by remember { mutableIntStateOf(composerHeight) }
    LaunchedEffect(composerHeight) {
        val grown = composerHeight - lastComposerHeight
        lastComposerHeight = composerHeight
        if (grown > 0 && followingTail && !listState.isScrollInProgress) listState.scrollBy(grown.toFloat())
    }

    val c = MinaTheme.colors
    val backdrop = rememberGlassBackdrop()
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    val showNotice = flags.showSwitchNotice()
    LaunchedEffect(routerStatus) {
        routerStatus.events.collect { event ->
            latestSwitchEvent = event
        }
    }
    /** The message that was long pressed; the menu is open (the folding animation is cleared only after playing). */
    var menu by remember { mutableStateOf<MessageTarget?>(null) }
    /** The one with "Select text" selected: it switches back to the system's long-press selection; it is cleared when changing conversations. */
    var selecting by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    /** The progress of the menu appearing: 0 means no, 1 means it is completely out; when the animation is turned off, it is directly in place. */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(menu) {
        if (menu != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MinaMotion.Decelerate))
        }
    }
    /** Collapse the menu: Play the animation that appears backwards, and then clear it after playing; [then] Do it on the spot. */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MinaMotion.Decelerate))
            menu = null
        }
    }
    val menuHost = remember(selecting) { MessageMenuHost(selecting) { menu = it } }
    val menuOpen = menu != null
    PageBackground {
        CompositionLocalProvider(
            LocalMessageMenu provides menuHost,
            LocalRouterStatus provides routerStatus,
        ) {
            LazyColumn(
                Modifier.fillMaxSize().behindMenu(menuOpen) { lift.value }.voiceDim(voiceHolding, reduceMotion).glassSource(backdrop),
                state = listState,
                contentPadding = with(density) {
                    PaddingValues(
                        start = 16.dp, end = 16.dp, top = topHeight.toDp() + 4.dp, bottom = composerHeight.toDp() + 12.dp,
                    )
                },
            ) {
                if (ui.exchanges.isEmpty() && ui.blocked == null) {
                    item(key = "empty", contentType = "empty") { EmptyState(ui.conversationId) { input = it } }
                }
                val activeModelName = waitModelName(activeModel)
                ui.exchanges.forEachIndexed { i, ex ->
                    val backgroundNote = i == ui.exchanges.lastIndex && ui.operatingPhone
                    exchange(ex, ui.conversationId, ui.takeoverStage, vm, reduceMotion, backgroundNote, openRun, loadSource, openSource, kept, activeModelName)
                }
                if (phoneSetupNeeded && ui.exchanges.isNotEmpty()) item(key = "phone-setup", contentType = "setup") { PhoneSetupLine(openPhoneSetup) }
                ui.blocked?.let { problems ->
                    item(key = "blocked", contentType = "blocked") {
                        NeedCard(
                            stringResource(R.string.chat_cannot_start),
                            stringResource(R.string.chat_missing, problems.joinToString(stringResource(R.string.list_sep)) { StatusCopy.banner(it).title }),
                        ) {
                            CardButton(stringResource(R.string.ok_got_it), onClick = vm::dismissBlocked)
                            CardButton(stringResource(R.string.chat_go_settings), primary = true, onClick = { vm.dismissBlocked(); openSettings() })
                        }
                    }
                }
            }
        }

        // When there is no task running and a page of this conversation is left running in the
        // background: the top column says "One web page is maintained in the background"
        val sub = ChatCopy.subtitle(last, last?.let(vm::livePlace)) ?: kept?.let { ChatCopy.KEPT_PAGE to ChatCopy.Tone.Ok }
        Column(
            Modifier.behindMenu(menuOpen) { lift.value }.onSizeChanged { topHeight = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(
                ChatCopy.APP_TITLE,
                left = MinaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = onOpenDrawer,
                // There are tasks running in other conversations (in parallel): a small dot on the menu,
                // you can switch to it in the "In Progress" sidebar
                leftBadge = others.isNotEmpty(),
                right = { BarIconButton(MinaIcons.New, stringResource(R.string.chat_new), { vm.newConversation() }, enabled = vm.canSwitch()) },
                subtitle = sub?.first,
                subtitleColor = when (sub?.second) {
                    ChatCopy.Tone.Accent -> c.accent
                    ChatCopy.Tone.Warn -> c.warn
                    ChatCopy.Tone.Ok -> c.ok
                    null -> Color.Unspecified
                },
            )
            RouterChip(
                activeModel = activeModel,
                onClick = {
                    routerStatus.refresh()
                    sheetOpen = true
                },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Composer(
            Modifier.align(Alignment.BottomCenter).behindMenu(menuOpen) { lift.value }.onSizeChanged { composerHeight = it.height },
            backdrop = backdrop,
            text = input,
            onText = { input = it },
            mode = ui.composer,
            hasHistory = ui.exchanges.isNotEmpty(),
            onSend = { if (vm.send(input, check)) input = "" },
            onStop = vm::cancel,
            paused = (last?.status as? ExchangeStatus.Paused)?.resumable == true,
            onPause = { if ((last?.status as? ExchangeStatus.Paused)?.resumable == true) vm.resume() else vm.pause() },
            attachPanel = if (ui.attachEnabled && ui.composer != ComposerMode.Answer) ({
                AttachPanelHost(ui.attachments, onPicked = vm::attach, onToggle = vm::toggleAttachment)
            }) else null,
            attachments = ui.attachments,
            importing = ui.importing,
            rejected = ui.rejectedAttachments,
            overLimit = ui.overLimit,
            reduceMotion = reduceMotion,
            onRemove = vm::removeAttachment,
            onDismissRejected = vm::dismissRejected,
            quote = ui.quote,
            onRemoveQuote = vm::clearQuote,
            voice = voice,
            // The words you say are followed by the existing words; when the task is sent out while
            // running, it means interrupting.
            onVoiceText = { input = HoldToTalk.join(input, it) },
            onPickSpeech = openSpeechModel,
            steerTarget = last?.goal?.let(ChatCopy::plainTitle),
            onVoiceHolding = { voiceHolding = it },
            editRequest = editRequest,
        )

        AgentPip(
            pip = ui.pip,
            frame = frame,
            webContent = webContent,
            reduceMotion = reduceMotion,
            topInset = topHeight.toFloat(),
            bottomInset = composerHeight.toFloat(),
            onExpand = vm::expandPip,
            onCollapse = vm::collapsePip,
            // Turn off the small screen: before the end of this task, only the small button in the lower
            // right corner is left; a reminder of where else to look
            onClose = {
                vm.closePip()
                Toast.makeText(context, context.getString(R.string.pip_closed_hint), Toast.LENGTH_SHORT).show()
            },
            onReopen = vm::reopenPip,
            // When you are asked to process, click on the small window: fold it, and scroll to the card
            // you want to process (in the last question and answer, there may only be a file card and
            // the line to set up after it)
            onAttention = {
                vm.attendPip()
                val count = listState.layoutInfo.totalItemsCount
                val after = (if (last?.files?.isNotEmpty() == true) 1 else 0) + (if (phoneSetupNeeded) 1 else 0)
                val card = count - 1 - after
                if (card >= 0) scope.launch { if (reduceMotion) listState.scrollToItem(card) else listState.animateScrollToItem(card) }
            },
        )

        // The menu of long-pressing the message is covered at the top: copy the plain text; place the
        // quote above the input box; select the text and let the long-press selection return to the
        // system
        menu?.let { m ->
            val pending = withdrawableSteer(ui.exchanges, m.key)
            val canEdit = input.isBlank() && ui.quote == null && ui.attachments.isEmpty() && !ui.importing
            MessageMenu(
                m, progress = { lift.value },
                onCopy = { closeMenu { copyMessage(context, m.text) } },
                onQuote = { closeMenu { vm.quote(m.from, m.text) } },
                onSelect = {
                    closeMenu {
                        selecting = m.key
                        Toast.makeText(context, context.getString(R.string.chat_select_hint), Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { closeMenu() },
                onEdit = if (pending != null && canEdit && editableSteer(pending.text)) ({ closeMenu {
                    vm.editSteer(pending.id)?.let { input = it; editRequest++ }
                } }) else null,
                onWithdraw = pending?.let { n -> ({ closeMenu { vm.withdrawSteer(n.id) } }) },
            )
        }

        if (sheetOpen) {
            RouterBottomSheet(
                activeModel = activeModel,
                usages = routerUsages,
                onPinClick = {
                    if (activeModel.isPinned) {
                        routerStatus.pinModel(null)
                    } else {
                        routerStatus.pinModel(activeModel.id)
                    }
                },
                onSelectModel = { modelId ->
                    if (activeModel.isPinned && activeModel.id == modelId) {
                        routerStatus.pinModel(null)
                    } else {
                        routerStatus.pinModel(modelId)
                    }
                },
                onDismiss = { sheetOpen = false },
                onRefresh = { routerStatus.refresh() },
                onOpenSettings = {
                    sheetOpen = false
                    openSettings()
                },
            )
        }

        SwitchNoticeToast(
            event = latestSwitchEvent,
            showNotice = showNotice,
            onDismiss = { latestSwitchEvent = null },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = with(density) { topHeight.toDp() } + 8.dp),
        )
    }
}

/** The last item may be a long reply, and just seeing the beginning of it doesn't mean the user is reading the end. */
private fun LazyListState.nearTail(thresholdPx: Int): Boolean {
    val layout = layoutInfo
    val end = layout.totalItemsCount - 1
    val tail = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return false
    return tail.offset + tail.size - layout.viewportEndOffset <= thresholdPx
}

// ---------- Empty dialogue ----------

/** A greeting, a sentence "What do you want Mina to do?" and a list of examples; click on the example to fill it in the input box, correct it and send it again. */
@Composable
private fun EmptyState(conversation: String?, onPick: (String) -> Unit) {
    val c = MinaTheme.colors
    val hour = remember(conversation) { LocalTime.now().hour }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
            Text(ChatCopy.greeting(hour), style = MinaType.LargeTitle, color = c.ink)
            Text(ChatCopy.EMPTY_PROMPT, Modifier.padding(top = 6.dp), style = EmptyLead, color = c.ink2)
        }
        MinaCard(Modifier.padding(top = 28.dp)) {
            ChatCopy.examples.forEachIndexed { i, ex ->
                ExampleRow(exampleIcon(ex.kind), ex.text, first = i == 0) { onPick(ex.text) }
            }
        }
    }
}

private fun exampleIcon(kind: ChatCopy.ExampleKind): ImageVector = when (kind) {
    ChatCopy.ExampleKind.App -> MinaIcons.Apps
    ChatCopy.ExampleKind.Web -> MinaIcons.Globe
    ChatCopy.ExampleKind.File -> MinaIcons.Doc
    ChatCopy.ExampleKind.Schedule -> MinaIcons.Clock
    ChatCopy.ExampleKind.Device -> MinaIcons.Alarm
}

@Composable
private fun ExampleRow(icon: ImageVector, text: String, first: Boolean, onClick: () -> Unit) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.drawBehind {
                val y = 0.25.dp.toPx()
                drawLine(c.hair, Offset(52.dp.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            })
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MinaSize.Icon), tint = c.ink2)
        Text(text, Modifier.weight(1f), style = ExampleText, color = c.ink)
    }
}

// ---------- Q&A ----------

private fun LazyListScope.exchange(
    ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    openRun: (String) -> Unit, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    kept: KeptPage? = null, activeModelName: String = "",
) {
    if (ex.goal.isNotEmpty()) item(key = "u:${ex.id}", contentType = "user") { UserBubble(ex.goal, conversationId, key = "u:${ex.id}") }
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    if (active) {
        if (!answering) item(key = "s:${ex.id}", contentType = "step") { WorkBlock(ex, reduceMotion, backgroundNote, openRun, activeModelName) }
    } else if (ex.steps.isNotEmpty() || ex.runId != null) {
        item(key = "s:${ex.id}", contentType = "step") { DoneLine(ex, reduceMotion, openRun) }
    }
    // Words inserted while the task is in progress: the user’s original words, with a line of small
    // print below describing its whereabouts. Replies that have been finished before the interruption
    // are displayed as usual; the section interrupted by the interruption remains in place (light color,
    // marked "Interrupted by your new message"); the interruption comes next. Interjections that have
    // not yet been given to the model are listed behind the growing answer: that answer was written
    // before it arrived, not as a reply to it.
    fun steer(n: SteerNote) {
        n.finished?.takeIf(String::isNotBlank)?.let { done ->
            item(key = "a:${ex.id}-${n.id}", contentType = "assistant") { FinishedReply(done, "a:${ex.id}-${n.id}") }
        }
        n.interrupted?.takeIf(String::isNotBlank)?.let { cut ->
            item(key = "i:${ex.id}-${n.id}", contentType = "assistant") { InterruptedReply(cut) }
        }
        item(key = "m:${ex.id}-${n.id}", contentType = "user") { UserBubble(n.text, conversationId, steerLabel(n.state), "m:${ex.id}-${n.id}") }
    }
    val (joined, waiting) = steersAroundDraft(ex.steers)
    joined.forEach(::steer)
    ex.draft?.takeIf(String::isNotBlank)?.let { draft ->
        item(key = "draft:${ex.id}", contentType = "draft") { DraftText(draft, ex.waitingForModel) }
    }
    waiting.forEach(::steer)
    if (ex.status != ExchangeStatus.Working) {
        val suppressErrorCard = ex.status is ExchangeStatus.Failed && ex.generatedImages.any { !it.filePath.isNullOrBlank() }
        if (!suppressErrorCard) {
            item(key = "r:${ex.id}", contentType = "assistant") { StatusCard(ex.status, stage, vm, ex.runId, loadSource, openSource, "r:${ex.id}") }
        }
    }
    // The page left running in the background after this round: If it is still open, there is a green
    // line (it can be turned off), and if it is turned off, there is a reason.
    keptPageRow(ex, kept, vm::closeKeptPage)
    if (ex.files.isNotEmpty()) item(key = "f:${ex.id}", contentType = "files") { FileCards(ex.files) }
    if (ex.generatedImages.isNotEmpty()) {
        ex.generatedImages.forEachIndexed { imgIdx, img ->
            item(key = "img:${ex.id}-$imgIdx", contentType = "generated_image") {
                val context = androidx.compose.ui.platform.LocalContext.current
                GeneratedImageCard(
                    imageUi = img,
                    onSave = { file ->
                        val uri = ImageStorageManager.saveToGallery(context, file)
                        val msg = if (uri != null) {
                            context.getString(R.string.image_saved_to_gallery)
                        } else {
                            context.getString(R.string.image_save_failed)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                    onShare = { file ->
                        val intent = ImageStorageManager.createShareIntent(context, file)
                        if (intent != null) {
                            context.startActivity(android.content.Intent.createChooser(intent, null))
                        }
                    },
                    onRegenerate = { prompt ->
                        val targetPrompt = ex.goal.takeIf { it.isNotBlank() } ?: prompt
                        if (targetPrompt.isNotBlank()) {
                            vm.send(targetPrompt, com.meenai.mina.settings.Prerequisites.Check(emptyList()))
                        }
                    },
                    onRegenerateWithModel = { prompt, model ->
                        (context.applicationContext as? com.meenai.mina.HarnessApp)?.imageSettings?.setPinnedModel(com.meenai.mina.core.image.ImageRole.Image, model)
                        val basePrompt = ex.goal.takeIf { it.isNotBlank() } ?: prompt
                        val promptWithoutModel = basePrompt
                            .replace(Regex("""\b(?:generate\s+with|using)\s+[\w./-]+\b:?""", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("""\bwith\s+[\w./-]+\b""", RegexOption.IGNORE_CASE), "")
                            .trim()
                        val target = "Generate with $model: $promptWithoutModel"
                        vm.send(target, com.meenai.mina.settings.Prerequisites.Check(emptyList()))
                    }
                )
            }
        }
    }
    learningNotes(ex, vm)
}

private fun LazyListScope.exchange(
    index: Int, ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    openRun: (String) -> Unit, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    kept: KeptPage? = null, activeModelName: String = "",
) = exchange(ex, conversationId, stage, vm, reduceMotion, backgroundNote, openRun, loadSource, openSource, kept, activeModelName)

/**
 * The interjection is divided into two paragraphs: the one before the first sentence
 * ([SteerState.unconsumed]) that has not been handed over to the model, and the one after it. The answer
 * to "Growing Out" is listed in the middle of the two paragraphs.
 */
internal fun steersAroundDraft(steers: List<SteerNote>): Pair<List<SteerNote>, List<SteerNote>> {
    val visible = steers.filterNot { it.state == SteerState.Withdrawn }
    val first = visible.indexOfFirst { it.state.unconsumed }.let { if (it < 0) visible.size else it }
    return visible.take(first) to visible.drop(first)
}

/**
 * The long-pressed [key] is given when the mobile phone that has not taken away the model interrupts
 * during the ongoing question and answer, and the menu provides withdrawal and editing accordingly (see
 * [editableSteer] for editing). The ones added by the computer ([SteerNote.remote]) will not be given:
 * the editor will turn the words that only belong to the current task into mobile phone interruptions,
 * or may be transferred to the next thing; whether to withdraw or not is decided by the computer.
 */
internal fun withdrawableSteer(exchanges: List<Exchange>, key: String): SteerNote? = exchanges.mapIndexedNotNull { i, ex ->
    if (ex.status.isActive()) ex.steers.firstOrNull {
        (key == "m$i-${it.id}" || key == "m:${ex.id}-${it.id}") && it.state.unconsumed && !it.remote
    } else null
}.firstOrNull()

internal fun ExchangeStatus.isActive() =
    this !is ExchangeStatus.Done && this !is ExchangeStatus.Failed && this !is ExchangeStatus.Cancelled

internal fun exchangeItemKeys(ex: Exchange, kept: KeptPage? = null, hasMultipleDrafts: Boolean = false): List<Pair<String, String>> {
    val items = mutableListOf<Pair<String, String>>()
    if (ex.goal.isNotEmpty()) items.add("u:${ex.id}" to "user")
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    if (active) {
        if (!answering) items.add("s:${ex.id}" to "step")
    } else if (ex.steps.isNotEmpty() || ex.runId != null) {
        items.add("s:${ex.id}" to "step")
    }
    val (joined, waiting) = steersAroundDraft(ex.steers)
    fun steerKeys(n: SteerNote) {
        n.finished?.takeIf(String::isNotBlank)?.let { items.add("a:${ex.id}-${n.id}" to "assistant") }
        n.interrupted?.takeIf(String::isNotBlank)?.let { items.add("i:${ex.id}-${n.id}" to "assistant") }
        items.add("m:${ex.id}-${n.id}" to "user")
    }
    joined.forEach(::steerKeys)
    ex.draft?.takeIf(String::isNotBlank)?.let {
        items.add("draft:${ex.id}" to "draft")
    }
    waiting.forEach(::steerKeys)
    if (ex.status != ExchangeStatus.Working) {
        items.add("r:${ex.id}" to "assistant")
    }
    if (KeptRow.of(ex, kept) != null) items.add("k:${ex.id}" to "kept")
    if (ex.files.isNotEmpty()) items.add("f:${ex.id}" to "files")
    if (ex.generatedImages.isNotEmpty()) {
        ex.generatedImages.indices.forEach { imgIdx ->
            items.add("img:${ex.id}-$imgIdx" to "generated_image")
        }
    }
    if (ex.runId != null && (ex.memoryNotes.isNotEmpty() || ex.skillNotes.isNotEmpty())) items.add("ln:${ex.id}" to "learning")
    return items
}

internal fun chatListKeys(
    exchanges: List<Exchange>,
    hasBlocked: Boolean = false,
    phoneSetupNeeded: Boolean = false,
    kept: KeptPage? = null,
): List<Pair<String, String>> {
    val items = mutableListOf<Pair<String, String>>()
    if (exchanges.isEmpty() && !hasBlocked) {
        items.add("empty" to "empty")
    }
    exchanges.forEach { ex ->
        items.addAll(exchangeItemKeys(ex, kept))
    }
    if (phoneSetupNeeded && exchanges.isNotEmpty()) {
        items.add("phone-setup" to "setup")
    }
    if (hasBlocked) {
        items.add("blocked" to "blocked")
    }
    return items
}

/**
 * User's words: blue bubble, rounded corner 20, bottom right corner 6, maximum width 80%. [note] is
 * where the interjection goes. When taking the user's current screen, there is a screenshot thumbnail
 * and "Your current screen · App · Moment", and the postscript is in the bubble below (see
 * [UserMessage]). When you bring attachments, there are photo grids and file cards on top (see
 * [SentAttachments]). Click on the view page in the App to view them; if you want to write, a new bubble
 * will appear below, and the lines of the path will not be displayed. When quoting the previous message,
 * the top one is the quoted one (see [SentQuote]). Long press the bubble to pop up copy/quote/select
 * text ([key] to identify which one, see [MessageArea]). When asked about a page on the browser page,
 * the top is "webpage·website" and the selected word (see [SentPage]), but the page description lines
 * are not displayed.
 */
@Composable
internal fun UserBubble(text: String, conversationId: String?, note: String? = null, key: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paged = remember(text) { AttachedPage.parse(text) }
    val quoted = remember(text) { QuoteMessage.parse(paged.words) }
    val attached = remember(text) { AttachmentMessage.parse(quoted.words) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        Column(horizontalAlignment = Alignment.End) {
            paged.page?.let { SentPage(it, max) }
            quoted.quote?.let { SentQuote(it, max) }
            if (attached.hasAttachments) {
                SentAttachments(attached, conversationId, max) { item ->
                    val id = conversationId ?: return@SentAttachments
                    scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                }
            }
            if (attached.hasAttachments && attached.words.isNotEmpty()) Spacer(Modifier.size(6.dp))
            if ((!attached.hasAttachments || attached.words.isNotEmpty()) && (paged.page == null || quoted.words.isNotEmpty())) UserMessage(conversationId, attached.words.takeIf { attached.hasAttachments } ?: quoted.words, ChatThumbWidth) { words ->
                // When selecting text, the background color selected on the blue background is white and
                // translucent, and the handle is still blue (drawn outside the bubble)
                val colors = MinaTheme.colors
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.onAccent.copy(alpha = 0.35f))) {
                    MessageArea(
                        key, QuoteMessage.From.You, words, "selectable-user",
                        Modifier.widthIn(max = max).background(colors.accent, MinaShapes.UserBubble),
                        lifted = { LiftedMessage(MinaShapes.UserBubble, colors.accent) { UserBubbleText(words) } },
                    ) { UserBubbleText(words) }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 3.dp, end = 4.dp), style = MinaType.Hint, color = MinaTheme.colors.ink3) }
        }
    }
}

@Composable
private fun UserBubbleText(words: String) {
    Text(words, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = BubbleText, color = MinaTheme.colors.onAccent)
}

/** The message raised by long pressing: [color] bottom with shadow; when it is too long (up to half screen) only the beginning is exposed and the bottom is cropped. */
@Composable
private fun LiftedMessage(shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.floatShadow(MinaTheme.colors, shape).background(color, shape).clip(shape)
            .wrapContentHeight(Alignment.Top, unbounded = true),
    ) { content() }
}

/** The width of the screenshot thumbnail in the conversation page. */
private val ChatThumbWidth = 110.dp

/** Generating answers: placed on the same light gray background as completed answers. */
@Composable
private fun DraftText(text: String, generating: Boolean) {
    MarkdownText(
        text, AnswerText.copy(color = MinaTheme.colors.ink),
        Modifier.fillMaxWidth().padding(bottom = 12.dp).answerSurface(), streaming = generating,
    )
}

/** Interject and hand over the reply that has been said before to the model: it has the same base and characters as the answer, and you can copy, quote, and select text by pressing and holding it. */
@Composable
private fun FinishedReply(text: String, key: String) {
    Box(Modifier.padding(bottom = 4.dp)) {
        AnswerArea(key, text, Modifier.fillMaxWidth().answerSurface()) {
            MarkdownText(text, AnswerText.copy(color = MinaTheme.colors.ink), streaming = false)
        }
    }
}

/**
 * An answer from Mina: Press and hold to pop up the copy/quote/select text (see [MessageArea]). What is
 * copied and quoted is the plain text with the Markdown symbols removed; [modifier] is the bottom of the
 * answer, and the one raised is the same word placed on the white card. When selecting text, the
 * selection handle is in a separate pop-up window, and dragging the handle will not bring out the
 * sidebar.
 */
@Composable
private fun AnswerArea(key: String, answer: String, modifier: Modifier, content: @Composable () -> Unit) {
    val plain = remember(answer) { MarkdownModel.toPlainText(answer) }
    MessageArea(
        key, QuoteMessage.From.Mina, plain, "selectable-answer", modifier,
        lifted = {
            LiftedMessage(MinaShapes.AnswerBubble, MinaTheme.colors.surface) {
                MarkdownText(answer, AnswerText.copy(color = MinaTheme.colors.ink), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), streaming = false)
            }
        },
    ) { content() }
}

/** The reply that was interrupted by the interruption: The same background as the answer, with the words in a secondary color, and a line of small words below "Interrupted by your new message." */
@Composable
private fun InterruptedReply(text: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        MarkdownText(text, AnswerText.copy(color = MinaTheme.colors.ink2), Modifier.fillMaxWidth().answerSurface(), streaming = false)
        Text(stringResource(R.string.steer_interrupted_reply), Modifier.padding(top = 3.dp, start = 4.dp), style = MinaType.Hint, color = MinaTheme.colors.ink3)
    }
}

/** Mina's answer: Very light gray, lower left corner. */
@Composable
private fun Modifier.answerSurface(): Modifier =
    background(MinaTheme.colors.answer, MinaShapes.AnswerBubble).padding(horizontal = 14.dp, vertical = 12.dp)

// ---------- Progress ----------

/**
 * In progress: a line of status (circle + current progress + time), a vertical line of steps below
 * (finished gray dots, current blue dots; when stopping to wait for you, the current step is an orange
 * dot). The bottom line of small words when operating the phone in the background.
 */
@Composable
private fun WorkBlock(
    ex: Exchange,
    reduceMotion: Boolean,
    backgroundNote: Boolean,
    openRun: (String) -> Unit,
    activeModelName: String = "",
) {
    val c = MinaTheme.colors
    val paused = ChatCopy.paused(ex.status)
    val elapsed = rememberElapsed(ex.startedAt)
    val isWaitingForModel = ex.waitingForModel
    val modelWaitSeconds = (elapsed?.div(1000L))?.toInt() ?: 0
    val isHindi = ChatCopy.isHindiLocale(stringResource(R.string.ui_locale))
    val progressText = if (isWaitingForModel) {
        ChatCopy.modelWaitLabel(modelWaitSeconds, activeModelName, isHindi = isHindi)
    } else {
        ex.line.takeIf { it.isNotBlank() } ?: ex.liveAction ?: ChatCopy.progressLine(ex)
    }
    var thinkingExpanded by remember(ex.id) { mutableStateOf(false) }
    val liveThinking = ex.thinking?.trim()?.takeIf { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(
                Modifier.size(16.dp), spinning = !paused && !reduceMotion,
                track = if (paused) c.warnSoft else c.accentSoft, head = if (paused) c.warnSolid else c.accent,
            )
            Text(
                progressText, Modifier.weight(1f), style = NowText, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (elapsed != null) Text(ChatCopy.clock(elapsed), style = ClockText, color = c.ink3)
        }
        if (liveThinking != null) {
            val thinkingShape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 26.dp)
                    .clip(thinkingShape)
                    .background(c.fill.copy(alpha = 0.65f))
                    .border(0.5.dp, c.hair, thinkingShape)
                    .clickable(role = Role.Button) { thinkingExpanded = !thinkingExpanded }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(MinaIcons.Spark, null, Modifier.size(12.dp), tint = c.accent)
                    Text(
                        if (isHindi) "Soch raha hai" else "Thinking process",
                        style = NoteText.copy(fontWeight = FontWeight.Medium),
                        color = c.ink2,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (thinkingExpanded) "▴" else "▾",
                        style = NoteText,
                        color = c.ink3,
                    )
                }
                val snippet = if (thinkingExpanded) liveThinking
                else liveThinking.lines().takeLast(4).joinToString("\n").takeLast(220)
                Text(
                    snippet,
                    Modifier.padding(top = 3.dp),
                    style = NoteText,
                    color = c.ink3,
                    maxLines = if (thinkingExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ex.usage?.takeIf { it.calls > 0 }?.let { UsageLine(it) }
        if (ex.steps.isNotEmpty()) StepLine(ex.steps, ex.stepActions, live = true, paused = paused)
        // When running, you can enter the running process (the page continues to grow by the second);
        // after the end, it will be included in the line "N steps done·View process"
        ex.runId?.let { id ->
            Text(
                stringResource(R.string.chat_view_process),
                Modifier.padding(top = 2.dp).clip(MinaShapes.Pill).clickable(role = Role.Button) { openRun(id) }
                    .padding(horizontal = 4.dp, vertical = 4.dp).testTag("work-view-process"),
                style = DoneText.copy(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline), color = c.ink3,
            )
        }
        if (backgroundNote) {
            Row(
                Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MinaIcons.Eye, null, Modifier.size(15.dp), tint = c.ink3)
                Text(ChatCopy.BACKGROUND_NOTE, style = NoteText, color = c.ink3)
            }
        }
    }
}

/** Real-time usage: number of calls, tokens entered and exited this time, how much context is used, and how much is left. */
@Composable
private fun UsageLine(u: com.meenai.mina.core.usage.RunUsage) {
    val c = MinaTheme.colors
    val model by LocalRouterStatus.current.current.collectAsState()
    val calls = stringResource(R.string.chat_usage_calls, u.calls)
    val tokens = if (u.inputTokens + u.outputTokens > 0) {
        stringResource(R.string.chat_usage_tokens, UsageCopy.tokens(u.inputTokens), UsageCopy.tokens(u.outputTokens))
    } else null
    val context = when {
        u.contextLimit > 0 -> stringResource(
            R.string.chat_usage_context,
            UsageCopy.tokens(u.contextTokens.toLong()),
            UsageCopy.tokens(u.contextLimit.toLong()),
            UsageCopy.tokens(u.contextLeft.toLong()),
        )
        u.contextTokens > 0 -> stringResource(R.string.chat_usage_context_open, UsageCopy.tokens(u.contextTokens.toLong()))
        else -> null
    }
    val quota = model.limit?.takeIf { it > 0 }?.let { limit ->
        stringResource(R.string.chat_usage_quota_left, (limit - model.usedToday).coerceAtLeast(0), stringResource(R.string.router_today))
    }
    Text(
        listOfNotNull(calls, tokens, context, quota).joinToString(" · "), Modifier.padding(top = 3.dp),
        style = NoteText.copy(fontFeatureSettings = "tnum"), color = c.ink3,
    )
}

/** The time since [startedAt], updated every second; it is null when the starting time is not known (it is 0). */
@Composable
private fun rememberElapsed(startedAt: Long): Long? {
    if (startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return (now - startedAt).coerceAtLeast(0L)
}

private enum class Dot { Done, Current, Paused, Dim }

/**
 * Vertical steps. When [live], the last step is marked with a blue or orange dot according to the status
 * of the task at the moment; when running, only the latest [ChatCopy.VISIBLE_STEPS] steps are listed,
 * and the previous results are in one row. Click to see them all.
 */
@Composable
private fun StepLine(steps: List<StepRow>, actions: Map<Int, List<StepAction>>, live: Boolean, paused: Boolean) {
    val c = MinaTheme.colors
    var all by rememberSaveable { mutableStateOf(!live) }
    // Which steps have been clicked: Press the serial number on this screen to mark it, and the list
    // will not become cluttered as it becomes longer.
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val hidden = if (all) 0 else (steps.size - ChatCopy.VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().drawBehind {
            val x = StepRail.toPx()
            drawLine(c.hair, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        },
    ) {
        if (hidden > 0) {
            StepItem(ChatCopy.earlierSteps(hidden), "", Dot.Dim, current = false, link = true, onClick = { all = true })
        }
        steps.drop(hidden).forEachIndexed { i, row ->
            val key = hidden + i
            val isLast = key == steps.lastIndex
            val dot = when {
                live && isLast && paused -> Dot.Paused
                live && isLast && row.state == StepState.Run -> Dot.Current
                row.state == StepState.Dim -> Dot.Dim
                else -> Dot.Done
            }
            val open = expanded[key] == true
            val detail = actions[row.step].orEmpty()
            StepItem(
                row.text, row.subtitle, dot, current = dot == Dot.Current || dot == Dot.Paused,
                actions = detail, open = open,
                // You can click on it only if you have specific actions: Click on it to see what this
                // step actually did, what it was correct for, and what the result was.
                onClick = if (detail.isEmpty()) null else ({ expanded[key] = !open }),
            )
        }
    }
}

@Composable
private fun StepItem(
    text: String, subtitle: String, dot: Dot, current: Boolean, link: Boolean = false, onClick: (() -> Unit)? = null,
    actions: List<StepAction> = emptyList(), open: Boolean = false,
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(if (open) R.string.collapse else R.string.chat_expand_steps), onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 28.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StepRail * 2), contentAlignment = Alignment.Center) { StepDot(dot) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = if (current) StepCurrentText else StepText,
                color = when {
                    link -> c.accent
                    current -> c.ink
                    else -> c.ink2
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, style = MinaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Click on: Several things actually done in this step, one by one, "What was done · What was
            // done · Result", the statement is the same as the running process page
            if (open) actions.forEach { a ->
                Text(
                    ChatCopy.actionLine(a), Modifier.padding(top = 2.dp),
                    style = MinaType.Hint, color = if (a.failed) c.bad else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The only ones that can be clicked are those with actions: a small arrow on the right, pointing
        // down when clicked.
        if (actions.isNotEmpty()) Icon(
            MinaIcons.Chevron, null,
            Modifier.padding(start = 6.dp).size(12.dp).graphicsLayer { rotationZ = if (open) 90f else 0f },
            tint = c.ink3,
        )
    }
}

/** Step point: 8dp solid; a 3dp light halo outside the current step. */
@Composable
private fun StepDot(dot: Dot) {
    val c = MinaTheme.colors
    val (fill, ring) = when (dot) {
        Dot.Done -> c.ink3 to null
        Dot.Dim -> c.hair to null
        Dot.Current -> c.accent to c.accentSoft
        Dot.Paused -> c.warnSolid to c.warnSoft
    }
    // The background color should cover the vertical lines first, then the dots will be clean
    val bg = c.bg
    Box(
        Modifier.size(14.dp).drawBehind {
            val center = Offset(size.width / 2, size.height / 2)
            if (ring != null) {
                drawCircle(bg, 7.dp.toPx(), center)
                drawCircle(ring, 7.dp.toPx(), center)
            }
            drawCircle(fill, 4.dp.toPx(), center)
        },
    )
}

/** Circle: light-colored ring with a solid color on top. When [spinning] is false (the animation is turned off, it stops and waits for you), it stops in place. */
@Composable
internal fun Spinner(modifier: Modifier, spinning: Boolean, track: Color, head: Color) {
    val turn = if (!spinning) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    ).value
    Box(
        modifier.graphicsLayer { rotationZ = turn }
            .border(2.dp, track, CircleShape)
            .border(
                2.dp,
                Brush.sweepGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 0.63f to head, 0.88f to head, 0.89f to Color.Transparent),
                CircleShape,
            ),
    )
}

/**
 * After the process is completed, the process will yield one line: "N steps completed and time taken"
 * (click to expand the steps below) and "View process" (enter the timeline).
 */
@Composable
private fun DoneLine(ex: Exchange, reduceMotion: Boolean, openRun: (String) -> Unit) {
    val c = MinaTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (ex.status is ExchangeStatus.Done) Icon(MinaIcons.Check, null, Modifier.size(16.dp), tint = c.ink3)
            if (ex.steps.isNotEmpty()) {
                Text(
                    stepsSummary(ex),
                    Modifier.clickable(onClickLabel = if (open) stringResource(R.string.chat_collapse_steps) else stringResource(R.string.chat_expand_steps)) { open = !open },
                    style = DoneText, color = c.ink3,
                )
            }
            ex.runId?.let { id ->
                Text(
                    // Gray italic and underlined: It blends in with the answer and can be seen clearly.
                    stringResource(R.string.chat_view_process), Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button) { openRun(id) }.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = DoneText.copy(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline), color = c.ink3,
                )
            }
        }
        Reveal(open && ex.steps.isNotEmpty(), reduceMotion) { StepLine(ex.steps, ex.stepActions, live = false, paused = false) }
    }
}

/** The clicked content: usually expands and collapses with animation, and appears and disappears directly when the animation is turned off. */
@Composable
private fun Reveal(visible: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    if (reduceMotion) {
        if (visible) content()
    } else {
        AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { content() }
    }
}

// ---------- Status and results ----------

@StringRes
fun takeoverReasonLabel(reason: String): Int? = when (reason) {
    "captcha" -> R.string.takeover_reason_captcha
    "login" -> R.string.takeover_reason_login
    "access_blocked" -> R.string.takeover_reason_access_blocked
    "payment" -> R.string.takeover_reason_payment
    "decision" -> R.string.takeover_reason_decision
    else -> null
}

/** Question and answer: The current situation: What you are asked to do is a white card ([NeedCard]), and the result is directly arranged on the background. */
@Composable
private fun StatusCard(
    status: ExchangeStatus, stage: Takeover.Stage, vm: ChatViewModel, runId: String?,
    loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit,
    /** Long press the answer menu to identify this item (see [MessageArea]). */
    key: String,
) {
    when (status) {
        ExchangeStatus.Working -> Unit
        ExchangeStatus.WaitingUnlock -> NeedCard(stringResource(R.string.chat_wait_unlock), stringResource(R.string.chat_unlock_body))
        is ExchangeStatus.NeedsTakeover -> {
            val label = takeoverReasonLabel(status.reason)
            val title = if (label != null) stringResource(R.string.takeover_title_reason, stringResource(label)) else stringResource(R.string.takeover_title)
            val handedOver = stage == Takeover.Stage.HandedOver
            val text = if (handedOver) stringResource(R.string.takeover_handed_over_hint) else status.message
            // The paid product, amount, and address are in separate lines: this card is the confirmation
            // before payment.
            val body = listOfNotNull(text, StatusCopy.paymentLines(status.item, status.amount, status.address)).joinToString("\n")
            NeedCard(title, body, footer = { TextLink(stringResource(R.string.action_cancel_task), vm::cancel) }) {
                if (handedOver) {
                    CardButton(stringResource(R.string.action_finished_myself), onClick = vm::finishedMyself)
                    CardButton(stringResource(R.string.action_hand_back), primary = true, onClick = vm::handBack)
                } else {
                    CardButton(stringResource(R.string.action_skip), onClick = vm::skip)
                    CardButton(stringResource(R.string.action_take_over), primary = true, onClick = vm::takeOver)
                }
            }
        }
        is ExchangeStatus.NeedsInput -> {
            val options = status.options.orEmpty()
            val note = stringResource(R.string.no_answer_note, TaskService.INPUT_TIMEOUT_MINUTES)
            NeedCard(
                stringResource(R.string.input_title), stringResource(R.string.chat_input_body, status.prompt, note),
                options = if (options.isEmpty()) null else ({
                    options.forEach { o -> MinaButton(o, { vm.answer(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium) }
                }),
            ) {
                if (options.isEmpty()) CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            }
        }
        // Only money-making operations require your consent, and you only agree to this once each time.
        is ExchangeStatus.NeedsConfirmation -> NeedCard(status.title, status.body + "\n" + ConfirmationCopy.NOTE) {
            CardButton(stringResource(R.string.chat_refuse), onClick = { vm.decline(status.key) })
            CardButton(stringResource(R.string.chat_allow_once), primary = true, onClick = { vm.approve(status.key) })
        }
        is ExchangeStatus.Paused -> NeedCard(stringResource(if (status.resumable) R.string.chat_paused else R.string.chat_waiting_for_you), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
            if (status.resumable) CardButton(stringResource(R.string.chat_continue), primary = true, onClick = vm::resume)
        }
        // Queuing starts: Automatically starts when it is your turn. If you click Continue, you cannot
        // jump in the queue. You can only cancel it.
        is ExchangeStatus.Queued -> NeedCard(stringResource(R.string.chat_queued), status.why) {
            CardButton(stringResource(R.string.action_cancel_task), onClick = vm::cancel)
        }
        // Long press the entire answer to pop up the menu; only the words of the answer are copied and
        // quoted, without the above prompts and the following sources.
        is ExchangeStatus.Done -> Box(Modifier.padding(bottom = 12.dp)) {
            AnswerArea(key, status.answer, Modifier.fillMaxWidth().answerSurface()) {
                Column {
                    status.unverifiedLine?.let { line ->
                        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(MinaTheme.colors.warnSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(MinaIcons.Exclaim, null, Modifier.size(11.dp), tint = MinaTheme.colors.warn)
                            }
                            Text(line, style = MinaType.Caption, fontWeight = FontWeight.SemiBold, color = MinaTheme.colors.ink2)
                        }
                    }
                    MarkdownText(status.answer, AnswerText.copy(color = MinaTheme.colors.ink))
                    // Source: The text based on the model written in the old record, or the source
                    // recorded by the code in the running record; if neither is found, it will not be
                    // displayed.
                    if (status.evidence != null || runId != null) SourceChip(status.evidence, runId, loadSource, openSource)
                }
            }
        }
        is ExchangeStatus.Failed -> Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(MinaTheme.colors.bad, CircleShape))
                Text(stringResource(R.string.chat_not_done), style = MinaType.BodyStrong, color = MinaTheme.colors.ink)
            }
            Text(status.reason, Modifier.padding(top = 4.dp), style = CardBody, color = MinaTheme.colors.ink2)
            val showRetry = status.reason.contains("Model slow tha", ignoreCase = true) ||
                status.reason.contains("dobara try", ignoreCase = true) ||
                status.reason.contains("retry", ignoreCase = true)
            if (showRetry) {
                Box(Modifier.padding(top = 8.dp)) {
                    MinaButton(
                        text = stringResource(R.string.retry),
                        onClick = {
                            val lastGoal = vm.ui.value.exchanges.lastOrNull()?.goal
                            if (!lastGoal.isNullOrBlank()) {
                                vm.send(lastGoal, Prerequisites.Check(emptyList()))
                            }
                        },
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Medium,
                    )
                }
            }
        }
        // The explanations added after the stop are regarded as the answers for this round, and are
        // listed under "Stopped"; while still writing, a line of small words
        is ExchangeStatus.Cancelled -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(stringResource(R.string.chat_stopped), Modifier.padding(start = 2.dp), style = MinaType.Body, color = MinaTheme.colors.ink2)
            if (status.summarizing) Text(
                StatusCopy.STOP_SUMMARIZING, Modifier.padding(start = 2.dp, top = 4.dp), style = MinaType.Caption, color = MinaTheme.colors.ink3,
            )
            status.summary?.let { summary ->
                Box(Modifier.padding(top = 8.dp)) {
                    AnswerArea(key, summary, Modifier.fillMaxWidth().answerSurface()) {
                        MarkdownText(summary, AnswerText.copy(color = MinaTheme.colors.ink))
                    }
                }
            }
        }
    }
}

/** Answer the "source" capsule below, which is just the entrance; see the basis and screenshots on a separate page. */
@Composable
private fun SourceChip(text: String?, runId: String?, loadSource: suspend (String) -> EvidenceSource?, openSource: (String?, String?) -> Unit) {
    val source by produceState<EvidenceSource?>(null, runId) { value = runId?.let { loadSource(it) } }
    if (text == null && source?.items.isNullOrEmpty() && source?.screenshots.isNullOrEmpty()) return
    val c = MinaTheme.colors
    Row(
        Modifier.padding(top = 12.dp).heightIn(min = 30.dp)
            .cardSurface(c, MinaShapes.Pill).clip(MinaShapes.Pill)
            .clickable(onClickLabel = stringResource(R.string.chat_view_source)) { openSource(runId, text) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MinaIcons.Link, null, Modifier.size(15.dp), tint = c.ink2)
        Text(sourceLabel(source), style = PillText, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "Need you" card (requires you to process, ask questions, confirm, pause, lock screen, blocked): white
 * card, an orange dot before the title, explained below; [options] is a column of clickable answers; the
 * buttons are side by side, the secondary one is on the left (light color), the primary one is on the
 * right (blue); [footer] is at the bottom.
 */
@Composable
private fun NeedCard(
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MinaTheme.colors
    MinaCard(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warnSolid, CircleShape))
                Text(title, style = NeedTitle, color = c.ink)
            }
            Text(body, Modifier.padding(top = 6.dp), style = CardBody, color = c.ink2)
            if (options != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { options() }
            }
            if (buttons != null) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
            footer?.let { Box(Modifier.padding(top = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { it() } }
        }
    }
}

@Composable
private fun RowScope.CardButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    MinaButton(
        text, onClick, Modifier.weight(1f),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Medium,
    )
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(MinaShapes.Pill).clickable(onClick = onClick).padding(4.dp), style = MinaType.Caption, color = MinaTheme.colors.ink3)
}

/** Files exported to "Download/Mina": small card, name, size and storage location; click on the visible ones to open them in the App ([FileViewer]), and leave the rest to the App that can open it in the system. */
@Composable
private fun FileCards(files: List<SharedFile>) {
    val context = LocalContext.current
    val c = MinaTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in files) {
            Row(
                Modifier.fillMaxWidth().cardSurface(c, MinaShapes.Card).clip(MinaShapes.Card)
                    .clickable(onClickLabel = stringResource(R.string.chat_open), role = Role.Button) { openShared(context, f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(c.neutralSoft, FileTileShape), contentAlignment = Alignment.Center) {
                    Icon(MinaIcons.Doc, null, Modifier.size(20.dp), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = FileName, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ChatViewModel.sizeLabel(f.size)} · ${f.savedAs.substringBeforeLast('/')}",
                        style = FileMeta, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(MinaIcons.Chevron, null, Modifier.size(MinaSize.SmallIcon), tint = c.ink3)
            }
        }
    }
}

private fun openShared(context: Context, f: SharedFile) = FileViewer.open(context, Uri.parse(f.uri), f.name)

// ---------- Input field ----------

/** "+" panel: closed, open, giving way to the keyboard (click on the input box: when the keyboard rises, the panel lowers, and then closes when the keyboard is in place). */
private enum class Panel { Closed, Open, Yielding }

/**
 * Glass capsule input field: "+" on the left, send on the right (grey when there is no content); the
 * task has an additional pause and stop when running, and the gray text in the input box prompts that
 * the interruption will be handed over to the current task. The accessory panel ([attachPanel]) opened
 * by clicking "+" is below the input field and occupies the space of the keyboard. It is interchanged
 * with the keyboard: open the panel to close the keyboard, then click "+" (this time it is ×) or click
 * the input box to close the panel and replace it with the keyboard; return to closing the panel first.
 * The attachments to be sent are in the row above the input field ([PendingTray]).
 */
@Composable
internal fun Composer(
    modifier: Modifier,
    text: String,
    onText: (String) -> Unit,
    mode: ComposerMode,
    hasHistory: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** The task was paused by the user: "Continue" is displayed next to Stop. */
    paused: Boolean = false,
    onPause: () -> Unit = {},
    /** There is a "+" when the sandbox is ready and not waiting for a response (tasks can be added while running and sent out with interruption); click to open this panel. */
    attachPanel: (@Composable () -> Unit)? = null,
    attachments: List<PendingAttachment> = emptyList(),
    importing: Boolean = false,
    rejected: List<String> = emptyList(),
    overLimit: Int = 0,
    onRemove: (String) -> Unit = {},
    onDismissRejected: () -> Unit = {},
    /** A quote to be sent with the next sentence; sometimes a small bar, ×, is removed above the input box. */
    quote: QuoteMessage.Quote? = null,
    onRemoveQuote: () -> Unit = {},
    editRequest: Int = 0,
    /** The screen content of the layer behind the input field; when it is null, the input field will not be blurred and will have an opaque background. */
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = false,
    /** Press and hold to speak to open a session from here; when null, the input box can only be typed. */
    voice: VoiceSource? = null,
    /** The finished word; the caller attaches it to the existing word and does not send it automatically. */
    onVoiceText: (String) -> Unit = {},
    /** "Deselect" in "Voice input is not yet available": Go to settings and select text. */
    onPickSpeech: () -> Unit = {},
    /** To whom should you give your words while the task is running: Write "Interjection to "..."" on the top of the card. */
    steerTarget: String? = null,
    /** True when pressing to speak: the conversation content will be blurred. */
    onVoiceHolding: (Boolean) -> Unit = {},
) {
    val c = MinaTheme.colors
    val vh = rememberVoiceHold(voice, onVoiceText)
    val vState = voiceState(vh)
    val voiceHolding = vh?.holding == true
    LaunchedEffect(voiceHolding) { onVoiceHolding(voiceHolding) }
    var fieldFocused by remember { mutableStateOf(false) }
    // The bottom color fades from the page background to transparent, and the input field is glass
    // floating on it.
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.6f to c.bg, 1f to c.bg) }
    val busy = mode == ComposerMode.Busy
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    var panel by rememberSaveable { mutableStateOf(Panel.Closed) }
    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            panel = Panel.Closed
            field.requestFocus()
            keyboard?.show()
        }
    }
    // The panel is as high as the keyboard: record the height of the keyboard you have seen (the section
    // above the navigation bar), and use the default height if you have not seen it before.
    var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ime, nav, density) {
        snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.collect { if (it > keyboardPx) keyboardPx = it }
    }
    // When the input box is focused, press and hold to leave the system to select words and paste (the
    // same is true for floating, split, and physical keyboards); when the input box is not focused, a
    // layer of press-and-hold gestures is used to cover the input box.
    val voiceGesture = vh != null && (!fieldFocused || voiceHolding)
    // From playing to retracting the keyboard (return key, the keyboard's own retract key): the input
    // box then loses focus, and you can press and hold to speak. Only see changes in the keyboard, the
    // physical keyboard (which never pops up) is not affected
    LaunchedEffect(ime, density, vh != null) {
        if (vh == null) return@LaunchedEffect
        var wasUp = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { up ->
            if (wasUp && !up && fieldFocused) focus.clearFocus()
            wasUp = up
        }
    }
    // Once the words outside the input box change (fill them in after you finish speaking, click on the
    // example), the cursor is placed at the end, and the next words or typing will be behind.
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shownValue = if (fieldValue.text == text) fieldValue else TextFieldValue(text, TextRange(text.length))
    val panelPx = with(density) {
        if (keyboardPx > 0) keyboardPx.coerceIn(PanelMin.roundToPx(), PanelMax.roundToPx()) else PanelDefault.roundToPx()
    }
    LaunchedEffect(attachPanel == null) { if (attachPanel == null) panel = Panel.Closed }
    BackHandler(enabled = panel == Panel.Open) { panel = Panel.Closed }
    // Give way to the keyboard: Wait until the keyboard is almost as high as the panel (if no keyboard
    // pops up, wait for a while at most) before closing it.
    LaunchedEffect(panel, panelPx) {
        if (panel != Panel.Yielding) return@LaunchedEffect
        withTimeoutOrNull(YIELD_MS) {
            snapshotFlow { ime.getBottom(density) - nav.getBottom(density) }.first { it >= panelPx * 9 / 10 }
        }
        panel = Panel.Closed
    }
    val togglePanel: () -> Unit = {
        if (panel == Panel.Open) {
            panel = Panel.Yielding
            field.requestFocus()
            keyboard?.show()
        } else {
            focus.clearFocus()
            keyboard?.hide()
            panel = Panel.Open
        }
    }
    val turn by animateFloatAsState(if (panel == Panel.Open) 45f else 0f, if (reduceMotion) snap() else tween(200), label = "plus")
    Box(
        modifier.fillMaxWidth()
            .background(fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            quote?.let { QuoteChip(it, onRemoveQuote, Modifier.padding(bottom = 6.dp)) }
            if (attachments.isNotEmpty()) PendingTray(attachments, onRemove, Modifier.padding(bottom = 2.dp))
            if (importing || rejected.isNotEmpty() || overLimit > 0) {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (importing) Text(AttachCopy.IMPORTING, style = MinaType.Caption, color = c.ink3)
                    if (rejected.isNotEmpty() || overLimit > 0) {
                        val why = listOfNotNull(
                            rejected.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.chat_attach_rejected, it.joinToString(stringResource(R.string.list_sep))) },
                            overLimit.takeIf { it > 0 }?.let(AttachCopy::overLimit),
                        ).joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(why, Modifier.weight(1f), style = MinaType.Caption, color = c.warn)
                            TextLink(stringResource(R.string.ok_got_it), onDismissRejected)
                        }
                    }
                }
            }
            if (vh != null) {
                val title = if (busy && !steerTarget.isNullOrBlank()) stringResource(R.string.voice_steer_to, steerTarget)
                else stringResource(R.string.voice_listening)
                VoiceFloat(vh, vState, title, backdrop, reduceMotion)
            }
            Box(Modifier.fillMaxWidth()) {
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard(), backdrop) {
                if (attachPanel != null) {
                    val label = if (panel == Panel.Open) AttachCopy.CLOSE else AttachCopy.ADD
                    Box(
                        Modifier.size(MinaSize.IconButton).clip(CircleShape)
                            .clickable(enabled = !importing, onClickLabel = label, onClick = togglePanel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MinaIcons.Plus, label, Modifier.size(MinaSize.Icon).graphicsLayer { rotationZ = turn }, tint = c.ink)
                    }
                }
                Box(Modifier.weight(1f).padding(start = if (attachPanel != null) 2.dp else 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                    val converting = vState is HoldToTalk.State.Converting
                    if (text.isEmpty() && !converting) {
                        if (vh != null) VoicePlaceholder(mode)
                        else Text(ChatCopy.placeholder(mode, hasHistory), style = MinaType.Body, color = c.ink3, maxLines = 1)
                    }
                    BasicTextField(
                        shownValue,
                        { v -> fieldValue = v; if (v.text != text) onText(v.text) },
                        Modifier.fillMaxWidth().testTag("composer-field").focusRequester(field)
                            .alpha(if (converting) 0f else 1f)
                            // Click on the input box when the panel is open: the panel gives way to the
                            // keyboard
                            .onFocusChanged {
                                fieldFocused = it.isFocused
                                if (it.isFocused && panel == Panel.Open) panel = Panel.Yielding
                            },
                        textStyle = MinaType.Body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = 5,
                    )
                    // After the service provider releases the button, rotate: circle in the input box
                    // and fill in the transferred words.
                    if (converting) VoiceConverting(Modifier.align(Alignment.CenterStart))
                    if (voiceGesture && vh != null) {
                        Box(Modifier.matchParentSize().holdToTalk(vh) { field.requestFocus(); keyboard?.show() })
                    }
                }
                val canSend = AttachTray.canSend(text, attachments, importing)
                // After the keyboard comes out, press and hold the input box to allow the system to
                // select words; press and hold the microphone to listen, and click and hold only to
                // prompt
                if (fieldFocused && vh != null) {
                    val hint = stringResource(R.string.voice_hold_to_talk)
                    Box(
                        Modifier.size(MinaSize.IconButton).testTag("composer-mic").holdToTalk(vh) { vh.toast = hint },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MinaIcons.Mic, hint, Modifier.size(MinaSize.Icon), tint = c.ink)
                    }
                }
                // The task stops while it is running; after writing a word, send another one. If it is
                // sent, it will be handed over to the current task.
                if (busy) {
                    val pauseLabel = stringResource(if (paused) R.string.chat_resume else R.string.chat_pause)
                    Box(
                        Modifier.size(MinaSize.IconButton).clip(CircleShape)
                            .clickable(onClickLabel = pauseLabel, onClick = onPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (paused) MinaIcons.Play else MinaIcons.Pause, pauseLabel,
                            Modifier.size(20.dp), tint = c.ink,
                        )
                    }
                    Box(
                        Modifier.size(MinaSize.IconButton).background(c.ink, CircleShape).clip(CircleShape)
                            .clickable(onClickLabel = stringResource(R.string.chat_stop), onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MinaIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(26.dp), tint = c.bg)
                    }
                }
                if (!busy || canSend) {
                    Box(
                        Modifier.size(MinaSize.IconButton)
                            .background(if (canSend) c.accent else c.greySoft, CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { panel = Panel.Closed; onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MinaIcons.Up, stringResource(R.string.chat_send), Modifier.size(20.dp), tint = if (canSend) c.onAccent else c.ink3)
                    }
                }
            }
            if (vh != null) VoiceBarCover(vh, vState, reduceMotion, Modifier.matchParentSize())
            }
            if (vh != null) VoiceSheetHost(vh, onPickSpeech)
            if (panel != Panel.Closed && attachPanel != null) {
                // The section where the keyboard is still there (it is closing when the panel is first
                // opened and rising when it is given to the keyboard) is deducted from the panel: the
                // input field does not jump up and down.
                Box(
                    Modifier.fillMaxWidth().clipToBounds().layout { m, cons ->
                        val keyboardNow = (ime.getBottom(this) - nav.getBottom(this)).coerceAtLeast(0)
                        val shown = (panelPx - keyboardNow).coerceAtLeast(0)
                        val p = m.measure(Constraints.fixed(cons.maxWidth, panelPx))
                        layout(cons.maxWidth, shown) { p.place(0, 0) }
                    },
                ) {
                    Box(Modifier.fillMaxSize().padding(top = 4.dp)) { attachPanel() }
                }
            }
        }
    }
}

/** Panel height: the default value when the keyboard is not seen, and the upper and lower limits when following the keyboard. */
private val PanelDefault = 320.dp
private val PanelMin = 280.dp
private val PanelMax = 420.dp
/** This is the maximum time (milliseconds) to wait when yielding to the keyboard. */
private const val YIELD_MS = 700L

/** The position of the vertical line (counted from the left); the step points and the center of the circle are all on this line. */
private val StepRail = 8.dp

private val FileTileShape = RoundedCornerShape(10.dp)
private val EmptyLead = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val ExampleText = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val BubbleText = TextStyle(fontSize = 15.5.sp, lineHeight = 22.5.sp)
/** Answer: The text size and line spacing are widened to 1.7. */
private val AnswerText = TextStyle(fontSize = 15.5.sp, lineHeight = 26.sp)
private val NowText = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val ClockText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum")
private val StepText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val StepCurrentText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
private val NoteText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val DoneText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val NeedTitle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
private val CardBody = TextStyle(fontSize = 14.sp, lineHeight = 21.5.sp)
private val PillText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val FileName = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val FileMeta = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
