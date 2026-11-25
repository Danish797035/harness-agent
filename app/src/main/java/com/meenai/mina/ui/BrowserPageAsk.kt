package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.HarnessApp
import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.browser.PageAskMode
import com.meenai.mina.browser.PageAskModel
import com.meenai.mina.browser.PageConversations
import com.meenai.mina.browser.UserBrowser
import com.meenai.mina.capsule.CapsuleModel
import com.meenai.mina.capsule.CapsuleRun
import com.meenai.mina.capsule.CapsuleThreads
import com.meenai.mina.capsule.CardActions
import com.meenai.mina.capsule.CardContent
import com.meenai.mina.capsule.CardRun
import com.meenai.mina.capsule.CapsuleCards
import com.meenai.mina.capsule.ChatThread
import com.meenai.mina.capsule.PastTurn
import com.meenai.mina.capsule.Pinned
import com.meenai.mina.capsule.Steps
import com.meenai.mina.capsule.StopKey
import com.meenai.mina.capsule.ThreadList
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.PACKAGE_NAME_RE
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.task.TaskRun
import com.meenai.mina.task.TaskService
import com.meenai.mina.task.TaskService.Companion.isActive
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.floatShadow
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Fixed term for the "Ask Mina" section on the browser page. */
object PageAskCopy {
    val HINT: String get() = tr(R.string.browser_ask_hint)
    val FROM_PAGE: String get() = tr(R.string.browser_ask_from_page)
    val OPEN_CHAT: String get() = tr(R.string.browser_ask_open_chat)
    val MENU: String get() = tr(R.string.browser_ask_menu)
    val SELECTION: String get() = tr(R.string.browser_ask_selection)
    val DROP_SELECTION: String get() = tr(R.string.browser_ask_drop_selection)
    val PAUSED: String get() = tr(R.string.browser_ask_paused)
    val PAUSED_CARD: String get() = tr(R.string.browser_ask_paused_card)
    val PAUSED_HINT: String get() = tr(R.string.browser_ask_paused_hint)
    val EXPAND: String get() = tr(R.string.browser_ask_expand)
    val WEB: String get() = tr(R.string.browser_ask_web)
    val APP: String get() = tr(R.string.browser_ask_app)
    val HANDLING: String get() = tr(R.string.browser_ask_handling)
    fun continuing(title: String): String = tr(R.string.browser_ask_continuing, title)
}

/**
 * Ask Mina's status on this page on the browser page: which conversation follows this page
 * ([conversationId], the most recent question asked on the same page or the same website, see
 * [PageConversations]; [fresh] when "New Conversation" is selected), whether the bottom block is open
 * ([open]), the words in the input box and the selected text; the current task in that conversation
 * ([run], [card]) And the back and forth of dialogue ([thread], the same set of arrangement as the
 * dialogue cards of the suspension capsule). The first sentence sent carries the description of this
 * page ([AttachedPage]), and the page is lent to that task ([UserBrowser.lend]); what the task says when
 * running is an interjection (when answering a question, it is an answer), and all go through the
 * existing entrance of the task service. Only used on the main thread.
 */
@Stable
class PageChat(private val context: Context, private val browser: UserBrowser, private val scope: CoroutineScope) {
    private val app = context.applicationContext as HarnessApp
    private val store = ConversationStore.shared(context)
    private val model = CapsuleModel()
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()

    var conversationId by mutableStateOf<String?>(null)
        private set
    /** The title of the next conversation (given to the line "Following "...""). */
    var title by mutableStateOf<String?>(null)
        private set
    /** This page has already been asked (this conversation was started or continued on this page this time), and "Continue New Conversation" will no longer be displayed. */
    var started by mutableStateOf(false)
        private set
    var fresh by mutableStateOf(false)
    var open by mutableStateOf(false)
    var draft by mutableStateOf("")
    var selection by mutableStateOf<String?>(null)
    /** Click "Ask Mina" in the text selection menu: the input box needs to take focus (increase by one each time). */
    var focusAsk by mutableIntStateOf(0)
        private set

    var card by mutableStateOf<CardRun?>(null)
        private set
    var thread by mutableStateOf<ChatThread?>(null)
        private set
    /** Where are the steps for that matter at this moment ("webpage" "App"), which correspond to the steps of [CardContent.Working] one-to-one. */
    var stepTags by mutableStateOf<List<String?>>(emptyList())
        private set
    private var run: TaskRun? = null
    private var past: List<PastTurn>? = null
    private var shown = CapsuleThreads.PAGE
    private var watching: Job? = null
    private var loading: Job? = null
    private var lastState: TaskState? = null
    private var exchange: Exchange? = null

    val state: TaskState? get() = card?.run?.state
    val mode: PageAskMode get() = PageAskModel.mode(state?.takeIf { it.isActive() }, open)
    val target: String? get() = if (fresh) null else conversationId

    /** A page is opened (or another page is changed): If you haven't asked a question on this page yet, click on this page to find the next conversation. */
    fun onPage(url: String?) {
        if (url == null) {
            reset()
            return
        }
        if (started) return
        scope.launch {
            val pick = withContext(Dispatchers.IO) { runCatching { PageConversations.pick(store.list(), url, System.currentTimeMillis()) }.getOrNull() }
            if (started) return@launch
            follow(pick?.id, pick?.title)
        }
    }

    /** The page is closed: back to nothing. */
    private fun reset() {
        started = false
        fresh = false
        open = false
        selection = null
        follow(null, null)
    }

    /** The selected text needs to be asked to Mina: bring it, and the input box takes focus. */
    fun askAbout(text: String) {
        selection = AttachedPage.clip(text)
        focusAsk++
    }

    /** Issue the words in the input box: that thing is interjected when running (answered when asked), otherwise it starts on this page (brings the page description, borrows the page). */
    fun send() {
        val words = draft.trim()
        val sel = selection
        val live = run?.takeIf { it.state.value.isActive() && it.conversationId == target }
        if (live != null) {
            if (words.isEmpty() && sel == null) return
            if (live.state.value is TaskState.WaitingInput) TaskService.answer(context, words.ifEmpty { sel.orEmpty() }, live.key)
            else {
                // Interject with newly selected words: write the page description as at the beginning
                // (what this page looks like at the moment)
                val text = sel?.let { browser.pageInfo()?.let { p -> AttachedPage.compose(p.copy(selection = it), words) } } ?: words
                TaskService.start(context, text, live.conversationId, messageId = UUID.randomUUID().toString())
            }
            draft = ""
            selection = null
            return
        }
        if (words.isEmpty() && sel == null) return
        val page = browser.pageInfo() ?: return
        val id = target ?: store.newConversation()
        val origin = PageConversations.origin(page.url, page.title)
        browser.lend(id)
        TaskService.start(context, AttachedPage.compose(page.copy(selection = sel), words), id)
        if (origin != null) scope.launch(Dispatchers.IO) { runCatching { store.setPageOrigin(id, origin) } }
        draft = ""
        selection = null
        fresh = false
        started = true
        open = true
        follow(id, title.takeIf { id == conversationId })
    }

    /** Instead, follow the conversation [id]: Read the turns it recorded and stare at its current task. */
    private fun follow(id: String?, title: String?) {
        this.title = title
        if (id == conversationId && watching != null) return
        conversationId = id
        past = null
        shown = CapsuleThreads.PAGE
        card = null
        thread = null
        run = null
        exchange = null
        lastState = null
        watching?.cancel()
        if (id == null) {
            watching = null
            return
        }
        loadPast(id)
        watching = scope.launch {
            launch { store.changes.collect { if (it == id) loadPast(id) } }
            watch(id)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch(id: String) {
        app.taskState.runs.map { runs -> runs.firstOrNull { it.conversationId == id } }.flatMapLatest { r ->
            if (r == null) flowOf(null)
            else combine(r.state, r.takeoverStage, r.activities, r.steers) { s, st, acts, steers ->
                r to CapsuleRun(r.key, r.conversationId, s, st, r.goal, exchangeOf(r, s, acts), runId = r.runId.value, onScreen = true, steers = steers)
            }
        }.collect { pair ->
            val r = pair?.first
            if (r != null) run = r
            val c = pair?.second ?: card?.run?.takeIf { !it.state.isActive() } ?: run?.let { gone ->
                // The task has been removed from the list: leave it in its last state and wait for this
                // round to be recorded in the dialogue
                CapsuleRun(gone.key, gone.conversationId, gone.state.value, Takeover.Stage.None, gone.goal, exchange, runId = gone.runId.value)
            }
            val next = c?.let(model::cardRunOf)
            val before = lastState
            lastState = next?.run?.state
            card = next
            stepTags = tagsOf(next)
            PageAskModel.follow(before, lastState)?.let { open = it }
            if (before?.isActive() == true && lastState?.isActive() != true) loadPast(id)
            rebuild()
        }
    }

    private fun exchangeOf(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchange?.takeIf { it.goal == r.goal } ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchange = it }
    }

    // Where is each step: The activity object of that step is "website..." is the web page, and the
    // package name is App
    private fun tagsOf(c: CardRun?): List<String?> {
        val ex = c?.run?.exchange ?: return emptyList()
        val content = CapsuleCards.content(c) as? CardContent.Working ?: return emptyList()
        val rows = ex.steps
        return content.steps.mapIndexed { i, _ ->
            val row = rows.getOrNull(i) ?: return@mapIndexed null
            val target = ex.activities.lastOrNull { it.step == row.step }?.target?.trim().orEmpty()
            when {
                target.startsWith(SITE_PREFIX) || target.startsWith("网站 ") -> PageAskCopy.WEB
                PACKAGE_NAME_RE.matches(target) -> PageAskCopy.APP
                else -> null
            }
        }
    }

    private fun loadPast(id: String) {
        loading?.cancel()
        val n = shown
        loading = scope.launch {
            val (turns, name) = withContext(Dispatchers.IO) {
                runCatching { store.turns(id) to store.title(id) }.getOrDefault(emptyList<com.meenai.mina.core.agent.Turn>() to null)
            }
            if (conversationId != id) return@launch
            if (name != null && title == null) title = name
            past = turns.takeLast(n.coerceAtLeast(turns.size)).map { PastTurn(it) }
            rebuild()
        }
    }

    private fun rebuild() {
        val id = conversationId ?: return
        val p = past ?: return
        val live = listOfNotNull(card).let { CapsuleThreads.liveTail(it, id, p.mapNotNull { t -> t.turn.runId }.toSet()) }
        thread = CapsuleThreads.build(p, shown, live, app.taskState.summarizing.value) { r ->
            CapsuleThreads.places(r.run.exchange?.activities.orEmpty(), ::appLabel)
        }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    /** Buttons on panels and small cards: all take the existing action of the task service, with the number of that thing. */
    val actions = object : CardActions {
        override fun collapse() { open = false }
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) = TaskService.takeOver(context, waitKey = run?.takeover?.waitKey, runKey = runKey)
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun pause(runKey: String) = TaskService.pause(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMina(conversationId: String) {
            runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
                .onFailure { Log.w(TAG, "could not open the conversation", it) }
        }
        override fun zoom(on: Boolean) = Unit
        override fun openInput() = Unit
        override fun draft(text: String) { draft = text }
        override fun send() = this@PageChat.send()
        override fun freshChat(fresh: Boolean) { this@PageChat.fresh = fresh }
        override fun loadEarlier() {
            val id = conversationId ?: return
            if ((past?.size ?: 0) < shown) return
            shown += CapsuleThreads.PAGE
            loadPast(id)
        }
        override fun captureScreen() = Unit
        override fun dropScreen() = Unit
    }

    /** For interface testing: directly display [card] and [thread] (dialog [id]) without any tasks. */
    @androidx.annotation.VisibleForTesting
    internal fun show(id: String?, card: CardRun?, thread: ChatThread?) {
        watching?.cancel()
        watching = null
        conversationId = id
        this.card = card
        this.thread = thread
        PageAskModel.follow(lastState, card?.run?.state)?.let { open = it }
        lastState = card?.run?.state
    }

    /** Return the page you took over ("Processed"). */
    fun handBack() {
        val r = run ?: return
        TaskService.handBack(context, waitKey = r.takeover?.waitKey, runKey = r.key)
    }

    /** The user touched the page with their own hands. */
    fun touched() = browser.userTouched()

    private companion object {
        const val TAG = "MinaPageChat"
        /** The object on the web page in the running record is written as "Site <domain name>". */
        const val SITE_PREFIX = "Site "
    }
}

/**
 * Container for web views: Tells [onTouch] as soon as a finger is pressed. Only real touches sent
 * through the window will pass through here; Mina's own input (DevTools' mouse and keyboard enters the
 * page directly, and native touches are sent directly to the web view) does not pass through it, so its
 * own actions will not be regarded as user operations. Add an item "Ask Mina" ([onAsk]) to the text
 * selection menu of the web page. Click on it to read the selected word first. After reading, call it
 * and click "done" to close the menu.
 */
class PageFrame(context: Context, private val onTouch: () -> Unit, private val onAsk: (done: () -> Unit) -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
        return super.dispatchTouchEvent(ev)
    }

    override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionModeForChild(originalView, AskCallback(callback), type)

    private inner class AskCallback(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onCreateActionMode(mode, menu).also { if (it) addAsk(menu) }

        // When the web page kernel prepares the menu, it will be cleared and rearranged: add it after
        // the arrangement is complete.
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onPrepareActionMode(mode, menu).also { addAsk(menu) }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ASK_ID) return inner.onActionItemClicked(mode, item)
            // Read out the selected word before closing the menu (the selected word may be canceled when
            // closing)
            onAsk { mode.finish() }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            (inner as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect) ?: super.onGetContentRect(mode, view, outRect)
        }

        private fun addAsk(menu: Menu) {
            if (menu.findItem(ASK_ID) == null) menu.add(Menu.NONE, ASK_ID, 0, PageAskCopy.MENU).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    private companion object {
        val ASK_ID = View.generateViewId()
    }
}

/**
 * The things that cover the web page (placed in the Box in the web page area): the light edges around
 * when you are working; a "continue" message on the top when Mina pauses when you start working on the
 * page; the dialogue panel in the lower half when the panel is open ([PagePanel]), and the small card at
 * the bottom when the task is not over yet ([PageCard]). The usual input bar is not here (under the web
 * page).
 */
@Composable
fun BoxScope.PageAskOverlay(chat: PageChat, site: String, pageTitle: String) {
    val state = chat.state
    if (PageAskModel.glow(state)) PageGlow()
    if (PageAskModel.pausedForUser(state)) {
        PausedBanner({ chat.card?.run?.key?.let(chat.actions::resume) }, Modifier.align(Alignment.TopCenter).padding(10.dp))
    }
    when (chat.mode) {
        PageAskMode.Panel -> PagePanel(chat, site, pageTitle, Modifier.align(Alignment.BottomCenter).fillMaxHeight(PANEL_SHARE).imePadding())
        PageAskMode.Card -> PageCard(chat, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        PageAskMode.Bar -> Unit
    }
}

/** The height ratio of the dialog panel to the web page area: the web page exposed above is the page Mina is operating on. */
private const val PANEL_SHARE = 0.58f

/** A lavender border around the page: Mina is working on this page. Does not block touch. */
@Composable
fun PageGlow(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().testTag("page-glow").drawBehind {
            val w = 2.dp.toPx()
            for (i in 0 until GLOW_LAYERS) {
                val inset = w / 2 + i * w * 2
                drawRoundRect(
                    GlowColor.copy(alpha = 0.7f / (i + 1)), topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(w),
                )
            }
        },
    )
}

private const val GLOW_LAYERS = 4
/** The color of Mina's edges when working on the page (lavender, separate from the color of the page itself). */
private val GlowColor = Color(0xFF7B5CFF)

/**
 * The usual line at the bottom of the browser page: "✦ Ask Mina..." input box; when there is a
 * subsequent conversation on this page, the line above "Continue "..." · New conversation"; when text is
 * selected to ask, the line above "Selected text" and ×.
 */
@Composable
fun PageAskBar(chat: PageChat, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    Column(modifier.fillMaxWidth().background(c.bg).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp).testTag("page-ask-bar")) {
        val continuing = chat.conversationId
        if (continuing != null && !chat.started) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chat.fresh) CardCopyText.NEW_CHAT else PageAskCopy.continuing(ChatCopy.plainTitle(AttachedPage.words(chat.title.orEmpty())).take(TITLE_MAX)),
                    Modifier.weight(1f, fill = false), style = MinaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(" · ", style = MinaType.Hint, color = c.ink3)
                Text(
                    if (chat.fresh) CardCopyText.CONTINUE_LAST else CardCopyText.NEW_CHAT,
                    Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button) { chat.fresh = !chat.fresh }.padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("page-ask-fresh"),
                    style = MinaType.Hint, color = c.accent,
                )
            }
        }
        chat.selection?.let { SelectionChip(it) { chat.selection = null } }
        AskField(chat, PageAskCopy.HINT, stop = null)
    }
}

private const val TITLE_MAX = 16

/** The words "new conversation" and "continuation of the last conversation" in the card (the same set of words as the suspension capsule). */
private object CardCopyText {
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
}

/** The selected text above the input box: a light bar, × without it on the right. */
@Composable
private fun SelectionChip(text: String, onRemove: () -> Unit) {
    val c = MinaTheme.colors
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.ink.copy(alpha = 0.05f))
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp).testTag("page-ask-selection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(PageAskCopy.SELECTION, style = MinaType.Hint, color = c.ink3)
            Text(text, style = MinaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(28.dp).clip(MinaShapes.Pill).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = PageAskCopy.DROP_SELECTION },
            contentAlignment = Alignment.Center,
        ) { Icon(MinaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3) }
    }
}

/** Input box: ✦ on the left, the word written (or with the selected word) on the right is for sending; when there is [stop], there is always a stop button on the right. */
@Composable
private fun AskField(chat: PageChat, hint: String, stop: (() -> Unit)?) {
    val c = MinaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(chat.focusAsk) { if (chat.focusAsk > 0) runCatching { focus.requestFocus() } }
    val canSend = chat.draft.isNotBlank() || chat.selection != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(c.fill).padding(start = 12.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MinaIcons.Spark, null, Modifier.size(17.dp), tint = GlowColor)
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            if (chat.draft.isEmpty()) Text(hint, style = MinaType.Body.copy(fontSize = 14.5.sp), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                chat.draft, { chat.draft = it },
                Modifier.fillMaxWidth().focusRequester(focus).testTag("page-ask-field").semantics { contentDescription = hint },
                textStyle = MinaType.Body.copy(fontSize = 14.5.sp, color = c.ink), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) chat.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || canSend) {
            Box(
                Modifier.testTag("page-ask-send").size(32.dp).clip(MinaShapes.Pill).background(if (canSend) c.ink else c.ink3.copy(alpha = 0.3f))
                    .clickable(enabled = canSend, role = Role.Button, onClick = chat::send)
                    .semantics { contentDescription = tr(R.string.chat_send) },
                contentAlignment = Alignment.Center,
            ) { Icon(MinaIcons.Up, null, Modifier.size(16.dp), tint = c.bg) }
        }
    }
}

private val PanelShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val CardShape = RoundedCornerShape(16.dp)

/**
 * Half-screen dialogue panel: Covers the lower half of the web page, and the web page exposed above is
 * the page Mina is operating on. The top is the name of the website, "Start from this page·Page title",
 * "Open in chat" and collapse; in the middle is the back and forth of this conversation (the same
 * arrangement as the floating capsule's dialogue cards); below are the steps during work, marking which
 * step is on the web page or the app; the buttons you want to click (answer options, agree, take over,
 * continue) are fixed below; the bottom is the input box: words spoken while running are interrupted
 * (stop button on the right), and when Mina asks you, it is answered. The end is followed by questions
 * on this page. A page is "processed" when it is in your hands (under control).
 */
@Composable
fun PagePanel(chat: PageChat, site: String, pageTitle: String, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val card = chat.card
    val content = card?.let(CapsuleCards::content)
    val live = card?.run?.state?.isActive() == true
    Column(
        modifier.fillMaxWidth().clip(PanelShape).background(c.bg).border(0.5.dp, c.hair, PanelShape)
            .padding(horizontal = 14.dp).testTag("page-panel"),
    ) {
        Box(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr(R.string.collapse)) { chat.open = false }.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(36.dp).height(4.dp).clip(MinaShapes.Pill).background(c.hair)) }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(site, style = MinaType.Label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(PageAskCopy.FROM_PAGE, pageTitle).filter(String::isNotBlank).joinToString(" · "),
                    style = MinaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            chat.conversationId?.let { id ->
                Row(
                    Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button) { chat.actions.openMina(id) }.padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("page-panel-open-chat"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(PageAskCopy.OPEN_CHAT, style = MinaType.Hint.copy(fontSize = 12.5.sp), color = c.accent)
                    Icon(MinaIcons.External, null, Modifier.size(13.dp), tint = c.accent)
                }
            }
            Box(
                Modifier.size(30.dp).clip(MinaShapes.Pill).clickable(role = Role.Button, onClick = chat.actions::collapse)
                    .semantics { contentDescription = tr(R.string.collapse) }.testTag("page-panel-collapse"),
                contentAlignment = Alignment.Center,
            ) { Icon(MinaIcons.Close, null, Modifier.size(17.dp), tint = c.ink3) }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        // The steps are immediately below the last line of the conversation (the line
        // "Website·Working"), and follow the same length as the conversation page, without pressing to
        // the bottom of the panel.
        val steps: (@Composable () -> Unit)? = if (content is CardContent.Working && content.steps.isNotEmpty()) {
            { Box(Modifier.padding(bottom = 6.dp)) { Steps(content.steps, StepsMax, chat.stepTags) } }
        } else null
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            chat.thread?.let { t -> androidx.compose.runtime.key(chat.conversationId) { ThreadList(t, chat.conversationId, chat.actions, footer = steps) } }
        }
        if (card != null && card.run.stage == Takeover.Stage.HandedOver) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PageAskCopy.HANDLING, style = MinaType.Caption, color = c.ink2)
                MinaButton(tr(R.string.action_hand_back), chat::handBack, Modifier.fillMaxWidth().testTag("page-panel-hand-back"), kind = ButtonKind.Primary, size = ButtonSize.Medium)
            }
        } else if (card != null && content != null && live) {
            Box(Modifier.padding(top = 4.dp)) { Pinned(content, card.run.key, chat.actions) }
        }
        val hint = when (content) {
            is CardContent.Takeover, is CardContent.Confirm -> null
            is CardContent.Question -> CapsuleCards.HINT_ANSWER
            is CardContent.Working, is CardContent.Held -> CapsuleCards.HINT_STEER
            else -> CapsuleCards.HINT_CONTINUE
        }
        val stoppable = live && (content is CardContent.Working || content is CardContent.Question || content is CardContent.Held)
        if (content is CardContent.Working) {
            // There is no input box when the task itself operates in the browser (like Google's
            // intelligent browsing): only pause and stop are allowed, and no one can interrupt while it
            // is working.
            BrowsingBar(card?.line.orEmpty(), card?.run?.key, chat.actions)
        } else if (hint != null) Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
            chat.selection?.let { SelectionChip(it) { chat.selection = null } }
            AskField(chat, hint, stop = if (stoppable) ({ card?.run?.key?.let(chat.actions::stop) }) else null)
        } else Box(Modifier.height(10.dp))
    }
}

private val StepsMax = 132.dp

/** When the agent is working on its own in the browser, the control bar below: says what it is doing, pauses and stops on the right side; no input box is provided. */
@Composable
private fun BrowsingBar(line: String, runKey: String?, actions: CardActions) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.fill)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("page-browsing-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(MinaShapes.Pill).background(GlowColor))
        Text(
            line.ifBlank { tr(R.string.page_browsing) }, Modifier.weight(1f),
            style = MinaType.Caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        runKey?.let { key ->
            Box(
                Modifier.size(34.dp).clip(MinaShapes.Pill).clickable(role = Role.Button, onClickLabel = tr(R.string.chat_pause)) { actions.pause(key) }
                    .semantics { contentDescription = tr(R.string.chat_pause) }.testTag("page-pause"),
                contentAlignment = Alignment.Center,
            ) { Icon(MinaIcons.Pause, null, Modifier.size(18.dp), tint = c.ink2) }
            StopKey { actions.stop(key) }
        }
    }
}

/**
 * The small card at the bottom when the panel is closed: What is that thing doing at the moment? Click
 * to expand. Write "Paused" and "Continue" when you pause while working on the page.
 */
@Composable
fun PageCard(chat: PageChat, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val card = chat.card ?: return
    val paused = PageAskModel.pausedForUser(card.run.state)
    val warn = paused || card.kind.needsYou
    Row(
        modifier.fillMaxWidth().floatShadow(c, CardShape).clip(CardShape).background(c.bg).border(0.5.dp, c.hair, CardShape)
            .clickable(role = Role.Button, onClickLabel = PageAskCopy.EXPAND) { chat.open = true }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).testTag("page-card"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(MinaShapes.Pill).background(if (warn) c.warnSolid else GlowColor))
        Column(Modifier.weight(1f)) {
            Text(
                if (paused) PageAskCopy.PAUSED_CARD else card.line.ifBlank { CapsuleCards.status(card.kind) },
                style = MinaType.Label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (paused) Text(PageAskCopy.PAUSED_HINT, style = MinaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (paused) MinaButton(tr(R.string.chat_continue), { chat.actions.resume(card.run.key) }, Modifier.testTag("page-card-resume"), kind = ButtonKind.Primary, size = ButtonSize.Small)
        Icon(MinaIcons.Up, PageAskCopy.EXPAND, Modifier.size(18.dp), tint = c.ink3)
    }
}

/** When you do something on the page and Mina stops, there is a message on top: "You are operating, Mina has paused and continue." */
@Composable
fun PausedBanner(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1D1F24))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("page-paused-banner"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MinaIcons.Hand, null, Modifier.size(16.dp), tint = Color.White)
        Text(PageAskCopy.PAUSED, Modifier.weight(1f), style = MinaType.Caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            tr(R.string.chat_continue),
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(role = Role.Button, onClick = onResume)
                .padding(horizontal = 12.dp, vertical = 5.dp).testTag("page-paused-resume"),
            style = MinaType.Label.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF111111),
        )
    }
}

/** The sentence on the conversation page was asked to a page on the browser page: above the bubble is a small piece of "web page · website", with the selected word below it is that paragraph. */
@Composable
internal fun SentPage(page: AttachedPage.Page, maxWidth: androidx.compose.ui.unit.Dp) {
    val c = MinaTheme.colors
    val host = com.meenai.mina.core.cap.web.WebUrlPolicy.host(page.url) ?: page.url
    Column(
        Modifier.padding(bottom = 4.dp).widthIn(max = maxWidth).background(c.ink.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("sent-page"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(MinaIcons.Globe, null, Modifier.size(13.dp), tint = c.ink3)
            Text("${PageAskCopy.WEB} · $host", style = MinaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        page.selection?.let { Text(it, style = MinaType.Caption, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}
