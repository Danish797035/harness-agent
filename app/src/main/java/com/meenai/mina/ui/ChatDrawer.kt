package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.meenai.mina.chat.DeleteImpact
import com.meenai.mina.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.chat.ConversationSummary
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.ui.components.KeyboardDismissArea
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.toSize
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.components.MinaLogo
import com.meenai.mina.ui.components.keepsKeyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.meenai.mina.ui.components.cardSurface
import com.meenai.mina.ui.components.lazyListScrollbar
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import com.meenai.mina.i18n.uiLocale

/** A conversation in the sidebar. [id] is null, which means the current conversation has not been saved in the conversation list. */
@Immutable
data class DrawerConversation(
    val id: String?,
    val title: String,
    val subtitle: String,
    val running: Boolean,
    val current: Boolean,
    val enabled: Boolean,
    /** The time of the last change (written to the right of the title); the time that has not been saved in the conversation list is empty. */
    val time: String = "",
    /** Pinned: There is a small pushpin before the title, and the long-press menu shows "Unpin". */
    val pinned: Boolean = false,
    /** Conversation started on the browser page facing one page: the website (host name), a small "web page" tag after the title; not null. */
    val site: String? = null,
)

/** Conversation segments in the sidebar: ongoing, pinned, and recent. */
@Immutable
data class DrawerSections(
    val running: List<DrawerConversation>,
    val recent: List<DrawerConversation>,
    val pinned: List<DrawerConversation> = emptyList(),
)

/** How the current conversation looks in the sidebar: whether there are any tasks running, title, subtitle (what is being done). */
@Immutable
data class CurrentConversation(val id: String?, val running: Boolean, val title: String, val subtitle: String)

/**
 * There are three sections in the sidebar: "In progress" refers to the current dialogue (when there is a
 * task) and other dialogues with tasks running; "Top" refers to the other dialogues that are pinned to
 * the top, with the most recently pinned dialogue first; "Recent" refers to the remaining dialogues,
 * with the most recently modified dialogue first. Each dialogue only appears in one paragraph: the
 * pinned dialogue has tasks listed as in progress (with push pins) when running, and returns to the top
 * after running. When [canSwitch] is false (the task is in progress and parallelism is not enabled),
 * only the current dialogue can be clicked.
 */
fun drawerSections(
    all: List<ConversationSummary>,
    current: CurrentConversation,
    others: List<String>,
    otherTitle: (String) -> String?,
    canSwitch: Boolean,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): DrawerSections {
    val byId = all.associateBy { it.id }
    fun timeOf(id: String?) = byId[id]?.let { conversationTime(it.updatedAt, now, zone) }.orEmpty()
    fun pinned(id: String?) = byId[id]?.pinnedAt != null
    val running = buildList {
        if (current.running) add(DrawerConversation(current.id, current.title.ifBlank { tr(R.string.chat_new) }, current.subtitle, true, true, true, time = timeOf(current.id), pinned = pinned(current.id), site = byId[current.id]?.page?.host))
        others.filter { it != current.id }.distinct().forEach { id ->
            val title = (byId[id]?.title ?: otherTitle(id))?.let(ChatCopy::plainTitle)?.ifBlank { null } ?: tr(R.string.drawer_running_task)
            add(DrawerConversation(id, title, tr(R.string.drawer_running), running = true, current = false, enabled = canSwitch, time = timeOf(id), pinned = pinned(id), site = byId[id]?.page?.host))
        }
    }
    val runningIds = running.mapNotNull { it.id }.toSet()
    fun entry(s: ConversationSummary): DrawerConversation {
        val isCurrent = s.id == current.id
        return DrawerConversation(s.id, ChatCopy.plainTitle(s.title), "", false, isCurrent, canSwitch || isCurrent, conversationTime(s.updatedAt, now, zone), pinned = s.pinnedAt != null,
            site = s.page?.host)
    }
    val rest = all.filter { it.id !in runningIds }
    val pinnedRows = rest.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }.map(::entry)
    val recent = rest.asSequence().filter { it.pinnedAt == null }.take(MAX_RECENT).map(::entry).toList()
    return DrawerSections(running, recent, pinnedRows)
}

/** Time of conversation: write the time today, write "yesterday" yesterday, write the day of the week in a week, write the month and day this year, and add the year earlier. */
fun conversationTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = t.toLocalDate()
    val days = today.toEpochDay() - day.toEpochDay()
    return when {
        days <= 0L -> "%d:%02d".format(t.hour, t.minute)
        days == 1L -> tr(R.string.drawer_yesterday)
        days < 7L -> t.dayOfWeek.getDisplayName(DateTextStyle.SHORT, uiLocale())
        day.year == today.year -> tr(R.string.date_month_day, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
        else -> tr(R.string.date_year_month_day, day.year, day.monthValue, day.month.getDisplayName(DateTextStyle.SHORT, uiLocale()), day.dayOfMonth)
    }
}

/** The status line under Mina's name at the top of the sidebar, and whether it is good (green dot) or needs to be processed (orange dot). */
fun readiness(check: Prerequisites.Check): Pair<String, Boolean> = when {
    !check.canStartTask -> tr(R.string.chat_cannot_start) to false
    !check.phoneReady -> tr(R.string.drawer_phone_not_ready) to false
    else -> tr(R.string.all_ready) to true
}

private const val MAX_RECENT = 50

/**
 * Sidebar: Slide out from the left, rounded corners on the right. The top is the logo and status; the
 * four entrances; the middle is ongoing (the right side of the section title enters the "Backstage
 * Screen" page) and recent conversations; the bottom is settings and about side by side. The drawer tube
 * is opened and closed by the outside. Only the content is placed here, and what is clicked is handed
 * over to the callback.
 */
@Composable
fun ChatDrawer(
    check: Prerequisites.Check,
    sections: DrawerSections,
    canSwitch: Boolean,
    onNew: () -> Unit,
    onPlans: () -> Unit,
    onMemory: () -> Unit,
    onBrowser: () -> Unit,
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onOpen: (DrawerConversation) -> Unit,
    onAbout: () -> Unit = {},
    /** There is a new version: "About Mina" with a little red dot. */
    aboutDot: Boolean = false,
    /** Long press a saved conversation: the operation menu will pop up in its place (pin to top, rename, delete); the position and size of that line in the window will be displayed. */
    onActions: (DrawerConversation, Rect) -> Unit = { _, _ -> },
) {
    val c = MinaTheme.colors
    Column(
        Modifier.fillMaxHeight().widthIn(max = DrawerWidth).fillMaxWidth()
            .clip(DrawerShape).background(c.bg, DrawerShape)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
            .testTag("drawer"),
    ) {
        val (state, ok) = readiness(check)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MinaLogo(36.dp, corner = 10.dp)
            Column {
                Text("Harness Agent", style = MinaType.Title, color = c.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (ok) c.ok else c.warnSolid, CircleShape))
                    Text(state, style = MinaType.Hint, fontWeight = FontWeight.Medium, color = c.ink3)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            NavEntry(MinaIcons.New, stringResource(R.string.chat_new), enabled = canSwitch, onClick = onNew)
            NavEntry(MinaIcons.Clock, stringResource(R.string.drawer_plans), onClick = onPlans)
            NavEntry(MinaIcons.Heart, stringResource(R.string.memory_and_skills), onClick = onMemory)
            NavEntry(MinaIcons.Globe, stringResource(R.string.drawer_browser), onClick = onBrowser)
        }
        // When the sidebar is closed, only the last item is left in the list; when the sidebar is
        // opened, the dialogue is inserted in front of it, and the list will stop at the bottom
        // according to the original item. If the top item changes, go back to the top
        val list = rememberLazyListState()
        val first = when {
            sections.running.isNotEmpty() -> "h-running"
            sections.pinned.isNotEmpty() -> "h-pinned"
            sections.recent.isNotEmpty() -> "h-recent"
            else -> "end"
        }
        LaunchedEffect(first) { list.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).lazyListScrollbar(list), state = list) {
            if (sections.running.isNotEmpty()) {
                // Go to the "Backstage Screen" page on the right side of the "In Progress" section: see
                // which app and real-time screen are being used for each running task.
                item("h-running", contentType = "running_label") { RunningLabel(onScreens) }
                items(sections.running, key = { "r-${it.id}" }, contentType = { "drawer_conversation" }) { ConversationEntry(it, onOpen, onActions) }
            }
            // When the conversation cannot be changed, the explanation is placed above the first saved
            // conversation (top or most recent).
            val busyNote: LazyListScope.() -> Unit = {
                if (!canSwitch) {
                    item("busy", contentType = "busy_note") {
                        Text(
                            stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                            style = MinaType.Hint, color = c.ink3,
                        )
                    }
                }
            }
            if (sections.pinned.isNotEmpty()) {
                item("h-pinned", contentType = "section_label") { SectionLabel(stringResource(R.string.drawer_pinned)) }
                busyNote()
                items(sections.pinned, key = { "p-${it.id}" }, contentType = { "drawer_conversation" }) { ConversationEntry(it, onOpen, onActions) }
            }
            if (sections.recent.isNotEmpty()) {
                item("h-recent", contentType = "section_label") { SectionLabel(stringResource(R.string.drawer_recent)) }
                if (sections.pinned.isEmpty()) busyNote()
                items(sections.recent, key = { "c-${it.id}" }, contentType = { "drawer_conversation" }) { ConversationEntry(it, onOpen, onActions) }
            }
            item("end", contentType = "spacer") { Box(Modifier.height(12.dp)) }
        }
        // Settings and About are fixed side by side at the bottom of the sidebar. No matter how many
        // conversations there are, they will not be squeezed out: Settings is on the left, About is on
        // the right, and the middle is left blank.
        Row(
            Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag("drawer-footer"),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavEntry(MinaIcons.Gear, stringResource(R.string.settings_title), Modifier, onClick = onSettings)
            NavEntry(MinaIcons.Info, AboutCopy.TITLE, Modifier, dot = aboutDot, onClick = onAbout)
        }
    }
}

@Composable
private fun NavEntry(
    icon: ImageVector, text: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit,
) {
    val c = MinaTheme.colors
    Row(
        modifier.height(46.dp).clip(NavShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MinaSize.Icon), tint = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MinaType.Body, color = c.ink, maxLines = 1, softWrap = false)
            if (dot) UpdateDot()
        }
    }
}

/** For the "In Progress" section title, there is a "Backstage Screen ›" on the right to enter the background screen page (this section only exists when there is a task running). */
@Composable
private fun RunningLabel(onScreens: () -> Unit) {
    val c = MinaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_running)) }
        Row(
            Modifier.clip(NavShape).clickable(role = Role.Button, onClick = onScreens)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-screens"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MinaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_screens), style = MinaType.Hint, color = c.ink2)
            Icon(MinaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        style = SectionText, color = MinaTheme.colors.ink3,
    )
}

/**
 * A conversation: click on it; long press (with a vibration) to pop up its operation menu (pin to top,
 * rename, delete). When the conversation cannot be changed (the task is in progress and parallelism is
 * not enabled), the click cannot be opened, but it still works if you press and hold it. Those that have
 * not been saved in the conversation list (without numbers) have nothing to change and will not respond
 * to long presses. There is a small thumbtack in front of the pinned title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationEntry(item: DrawerConversation, onOpen: (DrawerConversation) -> Unit, onActions: (DrawerConversation, Rect) -> Unit) {
    val c = MinaTheme.colors
    val deletable = item.id != null
    val haptic = LocalHapticFeedback.current
    // The position and size of this line in the window (without cutting off the part that rolls out of
    // the list), the menu will be placed as the raised line; it is only read in the callback, and does
    // not cause reorganization.
    val bounds = remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .then(if (item.current) Modifier.cardSurface(c, NavShape) else Modifier)
            .clip(NavShape)
            .then(
                if (deletable) Modifier.combinedClickable(
                    onClickLabel = null,
                    onLongClickLabel = stringResource(R.string.drawer_actions),
                    // It vibrates like a long press in the system.
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(item, bounds.value) },
                    onClick = { if (item.enabled) onOpen(item) },
                ) else Modifier.clickable(enabled = item.enabled, onClick = { onOpen(item) }),
            )
            .testTag("conversation-row")
            .alpha(if (item.enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ConversationRowContent(item)
    }
}

/** Things in a conversation: ongoing dots, thumbtacks, title, time, and the line below when there is a subtitle. Use it for both the row in the sidebar and the one raised by long pressing. */
@Composable
internal fun ConversationRowContent(item: DrawerConversation) {
    val c = MinaTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (item.running) Box(Modifier.size(7.dp).background(c.accent, CircleShape))
            if (item.pinned) Icon(MinaIcons.Pin, stringResource(R.string.drawer_pinned_mark), Modifier.size(14.dp).testTag("conversation-pin"), tint = c.ink3)
            Text(item.title, Modifier.weight(1f, fill = item.site == null), style = ConversationTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.site != null) {
                Text(
                    PageAskCopy.WEB, Modifier.background(c.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp).testTag("conversation-web"),
                    style = ConversationTag, color = c.accent, maxLines = 1,
                )
                Box(Modifier.weight(1f))
            }
            if (item.time.isNotEmpty()) {
                Text(item.time, Modifier.padding(start = 5.dp).testTag("conversation-time"), style = ConversationTime, color = c.ink3, maxLines = 1, softWrap = false)
            }
        }
        val sub = item.subtitle.ifEmpty { item.site.orEmpty() }
        if (sub.isNotEmpty()) {
            Text(sub, Modifier.padding(top = 2.dp), style = ConversationTime, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Rename the conversation: the current title is in the input box (select the entire paragraph and replace it by typing directly); empty spaces cannot be saved, and the blank spaces at both ends are removed. */
@Composable
internal fun RenameConversationDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            KeyboardDismissArea {
                OutlinedTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("rename-conversation-field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag("confirm-rename-conversation"), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Confirmation before deleting a conversation: indicate which conversation is being deleted; if there is
 * a task running or a scheduled task tied to it, also indicate that it will be stopped and deleted
 * together. The confirmation for deleting something is the same as elsewhere: the red word "Delete" on
 * the right and "Cancel" on the left.
 */
@Composable
internal fun DeleteConversationDialog(title: String, impact: DeleteImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_delete_body, title),
        if (impact.runningTask) stringResource(R.string.drawer_delete_running) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_schedules, impact.schedules) else null,
    ).joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MinaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal const val MENU_IN_MS = 200
internal const val MENU_OUT_MS = 140
private val DrawerWidth = 304.dp
private val DrawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
internal val NavShape = RoundedCornerShape(12.dp)
private val SectionText = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
private val ConversationTitle = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val ConversationTime = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
private val ConversationTag = TextStyle(fontSize = 10.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

/**
 * The conversation page and its sidebar: The menu in the upper left corner slides out of the sidebar
 * from the left, with a layer of mask outside; click on the mask, slide to the left, and press return to
 * close it. Read the conversation list only when the sidebar is open. When I go to another page and come
 * back, the sidebar is closed. [content] Get "Open Sidebar" and put it on the menu button.
 */
@Composable
fun ChatWithDrawer(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    reduceMotion: Boolean,
    conversations: () -> List<ConversationSummary>,
    /** Modified dialogues in storage (see `ConversationStore.changes`); dialogues saved elsewhere when the sidebar is open are also listed on the spot. */
    conversationChanges: Flow<String> = emptyFlow(),
    /** Give the conversation a good title; you can also call out the name even when the current conversation has not been saved in the list (the task has just started). */
    conversationTitle: (String) -> String? = { null },
    onPlans: () -> Unit,
    onMemory: () -> Unit,
    onBrowser: () -> Unit,
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit = {},
    /** There is a new version: "About Mina" in the sidebar has a little red dot. */
    updateAvailable: Boolean = false,
    /** The page entered from the sidebar has just returned: open the sidebar on the spot and return to the way it was before entering. */
    reopen: Boolean = false,
    onReopened: () -> Unit = {},
    cachedConversations: () -> List<ConversationSummary>? = { null },
    asyncLoad: Boolean = false,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(reopen) {
        if (reopen) {
            drawer.snapTo(DrawerValue.Open)
            onReopened()
        }
    }
    val ui by vm.ui.collectAsState()
    val others by vm.otherRunning.collectAsState()
    fun close(then: () -> Unit = {}) {
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
        then()
    }
    /** Go to another page: close the sidebar on the spot first, and it will be closed when you come back. */
    fun leave(go: () -> Unit) {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    val open = drawer.targetValue == DrawerValue.Open || drawer.isOpen
    // To return (swipe) on the conversation page, open the sidebar first; when the sidebar is open,
    // return and then leave the app (retreat to the background, the same as returning to the desktop).
    // The dialogue page panel, picture-in-picture and other own returns are in the inner layer and are
    // processed here first.
    val activity = LocalContext.current.findActivity()
    BackHandler {
        if (open) activity?.moveTaskToBack(true)
        else scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() }
    }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(conversationChanges) { conversationChanges.collect { revision++ } }
    val deleted by vm.deleted.collectAsState()
    /** The long-pressed item and its position in the window; the menu is open (the folding animation will not be cleared until it is played). */
    var acting by remember { mutableStateOf<Pair<DrawerConversation, Rect>?>(null) }
    /** The progress of the menu appearing: 0 means no, 1 means it is completely out; when the animation is turned off, it is directly in place. */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(acting) {
        if (acting != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MinaMotion.Decelerate))
        }
    }
    /** Collapse the menu: play the animation that appears backwards, and then clear it after playing; [then] does it on the spot (you don’t have to wait for the menu to be closed to change the name or delete the pop-up window). */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MinaMotion.Decelerate))
            acting = null
        }
    }
    /** The one that is being renamed. */
    var renaming by remember { mutableStateOf<DrawerConversation?>(null) }
    /** The article you want to delete and what will happen if you delete it; the confirmation pop-up window is open. */
    var confirming by remember { mutableStateOf<Pair<DrawerConversation, DeleteImpact>?>(null) }
    val menuOpen = acting != null
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            // When the menu is open, the content behind it is dimmed and hidden from accessibility;
            // using lightweight alpha instead of full-screen per-frame BlurEffect keeps animations smooth.
            modifier = if (!menuOpen) Modifier else Modifier
                .graphicsLayer { alpha = 1f - 0.14f * lift.value }
                .clearAndSetSemantics {},
            drawerContent = {
                // The disk is only read when opened (offloaded to Dispatchers.IO in app runtime) and
                // re-read if conversations change in storage while open.
                val initialAll = remember(open) { if (open && asyncLoad) cachedConversations().orEmpty() else emptyList() }
                val all by produceState(initialAll, open, revision, asyncLoad) {
                    if (!open) {
                        value = emptyList()
                    } else if (!asyncLoad) {
                        value = conversations()
                    } else {
                        if (value.isEmpty()) cachedConversations()?.let { value = it }
                        value = withContext(Dispatchers.IO) { conversations() }
                    }
                }
                val stored by produceState<String?>(null, open, revision, ui.conversationId, asyncLoad) {
                    val id = ui.conversationId
                    value = when {
                        !open || id == null -> null
                        !asyncLoad -> conversationTitle(id)
                        else -> withContext(Dispatchers.IO) { conversationTitle(id) }
                    }
                }
                val last = ui.exchanges.lastOrNull()
                val running = vm.busy()
                val current = CurrentConversation(
                    ui.conversationId, running,
                    stored ?: ChatCopy.title(ui.exchanges),
                    if (running) ChatCopy.subtitle(last, last?.let(vm::livePlace))?.first.orEmpty() else "",
                )
                val canSwitch = vm.canSwitch()
                // Deleted conversations will not be listed on the spot until they are deleted in the
                // background.
                val sections = remember(all, current, others, canSwitch, deleted) {
                    drawerSections(all.filter { it.id !in deleted }, current, others.filter { it !in deleted }, vm::runningGoal, canSwitch, System.currentTimeMillis())
                }
                ChatDrawer(
                    check, sections, canSwitch,
                    onNew = { close { vm.newConversation() } },
                    onPlans = { leave(onPlans) },
                    onMemory = { leave(onMemory) },
                    onSettings = { leave(onSettings) },
                    onBrowser = { leave(onBrowser) },
                    onScreens = { leave(onScreens) },
                    onAbout = { leave(onAbout) },
                    aboutDot = updateAvailable,
                    onActions = { item, bounds -> if (item.id != null) acting = item to bounds },
                    onOpen = { item ->
                        close {
                            val id = item.id
                            when {
                                id == null || id == ui.conversationId -> Unit
                                item.running -> vm.switchTo(id)
                                else -> vm.openConversation(id)
                            }
                        }
                    },
                )
            },
            drawerState = drawer,
            // On the conversation page, swipe from left to right to pull out the sidebar (drag with your
            // hand); content that can be scrolled left or right (wide tables, code blocks, etc.) is
            // scrolled by itself first. Sliding in from the left edge is the system's return gesture.
            // The return process above also opens the sidebar.
            gesturesEnabled = true,
            scrimColor = MinaTheme.colors.floatShadow.copy(alpha = 0.28f),
        ) {
            content { scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() } }
        }
        acting?.let { (item, bounds) ->
            val id = item.id ?: return@let
            ConversationMenu(
                item, bounds, progress = { lift.value },
                onPin = { closeMenu { vm.setPinned(id, !item.pinned) } },
                onRename = { closeMenu { renaming = item } },
                onDelete = { closeMenu { confirming = item to vm.deleteImpact(id) } },
                onDismiss = { closeMenu() },
            )
        }
    }
    renaming?.let { item ->
        RenameConversationDialog(
            item.title,
            onSave = { t ->
                renaming = null
                item.id?.let { vm.renameConversation(it, t) }
            },
            onDismiss = { renaming = null },
        )
    }
    confirming?.let { (item, impact) ->
        DeleteConversationDialog(
            item.title, impact,
            onConfirm = {
                confirming = null
                item.id?.let(vm::deleteConversation)
            },
            onDismiss = { confirming = null },
        )
    }
}
