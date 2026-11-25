package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.browser.FavoriteSite
import com.meenai.mina.browser.RecentItem
import com.meenai.mina.browser.SiteFace
import com.meenai.mina.browser.StartPageModel
import com.meenai.mina.browser.Suggestion
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.Hairline
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.cardSurface
import com.meenai.mina.ui.components.keepsKeyboard
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSpace
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

val BROWSER_SEARCH_HINT: String get() = tr(R.string.browser_search_hint)
val BROWSER_ENGINE_NOTE: String get() = tr(R.string.browser_engine_note)
val BROWSER_TYPING_NOTE: String get() = tr(R.string.browser_typing_note)
val BROWSER_LOGIN_LEGEND: String get() = tr(R.string.browser_login_legend)
val BROWSER_ADD_HINT: String get() = tr(R.string.browser_add_hint)
private const val COLUMNS = 4

/**
 * The start page of the browser: the top bar only has return and "Browser"; a large search box (Bing),
 * with the association below when typing (first search this sentence or open this URL, and then the most
 * recent one that matches); "frequently used websites" with a row of four (icon, name, logged in with a
 * small green dot, the last box "+"), "organize" or long press to enter the organization (the icon
 * shakes slightly, × removes, "complete" exits); "Recently Opened" (the search is written as
 * "Search:...", the time is on the right, and "Clear" is on the right of the title). Press Return during
 * sorting and input to exit them first.
 */
@Composable
fun BrowserStart(
    sites: List<FavoriteSite>,
    recent: List<RecentItem>,
    face: (String) -> SiteFace,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSite: (FavoriteSite) -> Unit,
    onRemoveSite: (FavoriteSite) -> Unit,
    onAddHint: () -> Unit,
    onClearRecent: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val c = MinaTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editing || query.isNotEmpty()) {
        if (editing) editing = false else query = ""
    }
    Column(Modifier.fillMaxSize()) {
        if (editing) {
            TopBar(tr(R.string.browser_edit_favorites), right = {
                Text(
                    tr(R.string.done), Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button) { editing = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MinaType.BodyStrong, color = c.accent,
                )
            })
        } else {
            TopBar(tr(R.string.drawer_browser), MinaIcons.Back, onBack, tr(R.string.back))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = MinaSpace.Page),
        ) {
            if (editing) {
                SectionHeader(tr(R.string.browser_tap_x))
                SiteGrid(sites, face, editing = true, onOpen = {}, onRemove = onRemoveSite, onEdit = {}, onAdd = null)
                Legend(tr(R.string.browser_long_press), dot = false)
            } else {
                Spacer(Modifier.height(MinaSpace.S20))
                BigSearchField(query, { query = it }, onGo = {
                    if (query.isNotBlank()) {
                        val q = query
                        query = ""
                        onSubmit(q)
                    }
                })
                if (query.isBlank()) {
                    Caption(BROWSER_ENGINE_NOTE)
                    SectionHeader(tr(R.string.browser_favorites), if (sites.isEmpty()) null else tr(R.string.browser_edit)) { editing = true }
                    SiteGrid(sites, face, editing = false, onOpen = onOpenSite, onRemove = {}, onEdit = { editing = true }, onAdd = onAddHint)
                    if (sites.any { it.loggedIn }) Legend(BROWSER_LOGIN_LEGEND, dot = true)
                    SectionHeader(tr(R.string.browser_recent_source), if (recent.isEmpty()) null else tr(R.string.clear), onClearRecent)
                    RecentCard(recent, face, now, onOpenUrl)
                } else {
                    val suggestions = remember(query, recent) { StartPageModel.suggestions(query, recent) }
                    SuggestionCard(suggestions, face) { s ->
                        query = ""
                        onOpenUrl(s.url)
                    }
                    Caption(BROWSER_TYPING_NOTE)
                }
            }
            Spacer(Modifier.height(MinaSpace.S24))
        }
    }
}

/** Large search box: 52-meter-high white capsule with a circle of accent color when focused; "Go" on the keyboard. */
@Composable
private fun BigSearchField(value: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    val c = MinaTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).keepsKeyboard().cardSurface(c, MinaShapes.Pill)
            .then(if (focused) Modifier.border(1.5.dp, c.accent, MinaShapes.Pill) else Modifier)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MinaIcons.Search, null, Modifier.size(20.dp), tint = c.ink3)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(BROWSER_SEARCH_HINT, style = BigText, color = c.ink3, maxLines = 1)
            BasicTextField(
                value, onChange,
                Modifier.fillMaxWidth().testTag("browser-search").onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = BigText.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = tr(R.string.browser_clear_input), role = Role.Button) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Icon(MinaIcons.Close, tr(R.string.browser_clear_input), Modifier.size(16.dp), tint = c.ink3) }
        }
    }
}

private val BigText = MinaType.Body.copy(fontSize = 16.sp)

@Composable
private fun Caption(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(top = 8.dp), style = MinaType.Hint, color = MinaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}

/** The group title can have a small link ([action]) on the right. */
@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = MinaTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MinaType.Label, color = c.ink3)
        if (action != null) {
            Text(
                action, Modifier.clip(MinaShapes.Pill).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MinaType.Label, color = c.accent,
            )
        }
    }
}

@Composable
private fun Legend(text: String, dot: Boolean) {
    val c = MinaTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot) Box(Modifier.size(9.dp).background(c.ok, CircleShape))
        Text(text, style = MinaType.Hint, color = c.ink3)
    }
}

/** Commonly used websites have four cells in a row; when [onAdd] is not null, the last cell is "+". */
@Composable
private fun SiteGrid(
    sites: List<FavoriteSite>,
    face: (String) -> SiteFace,
    editing: Boolean,
    onOpen: (FavoriteSite) -> Unit,
    onRemove: (FavoriteSite) -> Unit,
    onEdit: () -> Unit,
    onAdd: (() -> Unit)?,
) {
    val cells: List<FavoriteSite?> = sites + if (onAdd != null) listOf(null) else emptyList()
    Column(Modifier.fillMaxWidth().testTag("browser-sites"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cells.chunked(COLUMNS).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth()) {
                chunk.forEachIndexed { col, site ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        if (site == null) AddCell(onAdd!!)
                        else SiteCell(site, face(site.host), editing, (row * COLUMNS + col) % 2 == 0, { onOpen(site) }, { onRemove(site) }, onEdit)
                    }
                }
                repeat(COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteCell(
    site: FavoriteSite,
    face: SiteFace,
    editing: Boolean,
    even: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = MinaTheme.colors
    val reduce = MinaTheme.reduceMotion
    val angle = if (editing && !reduce) {
        rememberInfiniteTransition(label = "wiggle").animateFloat(
            -WIGGLE_DEG, WIGGLE_DEG, infiniteRepeatable(tween(WIGGLE_MS), RepeatMode.Reverse), label = "wiggle-angle",
        ).value
    } else 0f
    Column(
        Modifier.graphicsLayer { rotationZ = if (even) angle else -angle }
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClickLabel = if (editing) null else tr(R.string.browser_open_site, face.name),
                onLongClickLabel = if (editing) null else tr(R.string.browser_edit_favorites),
                onClick = { if (!editing) onOpen() },
                onLongClick = { if (!editing) onEdit() },
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            SiteTile(face, 54.dp, TileShape, InitialLarge)
            if (site.loggedIn) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(15.dp)
                        .background(c.bg, CircleShape).padding(2.5.dp).background(c.ok, CircleShape).testTag("browser-site-logged-in"),
                )
            }
            if (editing) {
                Box(
                    Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp).size(22.dp).clip(CircleShape)
                        .background(c.ink2).clickable(onClickLabel = tr(R.string.browser_remove_site, face.name), role = Role.Button, onClick = onRemove)
                        .testTag("browser-site-remove"),
                    contentAlignment = Alignment.Center,
                ) { Icon(MinaIcons.Close, tr(R.string.browser_remove_site, face.name), Modifier.size(11.dp), tint = c.bg) }
            }
        }
        Text(face.name, style = MinaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddCell(onAdd: () -> Unit) {
    val c = MinaTheme.colors
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = tr(R.string.browser_add_favorite), role = Role.Button, onClick = onAdd)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(54.dp).cardSurface(c, TileShape), contentAlignment = Alignment.Center) {
            Icon(MinaIcons.Plus, null, Modifier.size(24.dp), tint = c.ink3)
        }
        Text(tr(R.string.add), style = MinaType.Hint, color = c.ink2, maxLines = 1)
    }
}

@Composable
private fun RecentCard(recent: List<RecentItem>, face: (String) -> SiteFace, now: Long, onOpen: (String) -> Unit) {
    val c = MinaTheme.colors
    GroupCard {
        if (recent.isEmpty()) {
            Text(tr(R.string.browser_no_recent), Modifier.fillMaxWidth().padding(16.dp), style = MinaType.Body, color = c.ink3)
        }
        recent.forEachIndexed { i, r ->
            if (i > 0) Hairline(start = 56.dp)
            SmallRow(
                r.title, r.subtitle, onClick = { onOpen(r.url) },
                lead = { if (r.search || r.host == null) SearchMini() else SiteTile(face(r.host), 28.dp, MiniShape, InitialSmall) },
                trailing = { Text(StartPageModel.relativeTime(r.openedAtMs, now), style = MinaType.Hint, color = c.ink3, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SuggestionCard(list: List<Suggestion>, face: (String) -> SiteFace, onPick: (Suggestion) -> Unit) {
    if (list.isEmpty()) return
    GroupCard(Modifier.padding(top = 8.dp).testTag("browser-suggestions")) {
        list.forEachIndexed { i, s ->
            if (i > 0) Hairline(start = 56.dp)
            SmallRow(
                s.title, s.subtitle, onClick = { onPick(s) },
                lead = {
                    when {
                        s.search -> SearchMini()
                        s.host != null -> SiteTile(face(s.host), 28.dp, MiniShape, InitialSmall)
                        else -> SearchMini(MinaIcons.Globe)
                    }
                },
            )
        }
    }
}

/** A row on the start page: 28 small icons on the left, title (one row) and small words, and time on the right. */
@Composable
private fun SmallRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    lead: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lead()
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = MinaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun SearchMini(icon: ImageVector = MinaIcons.Search) {
    val c = MinaTheme.colors
    Box(Modifier.size(28.dp).background(c.neutralSoft, MiniShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = c.ink2)
    }
}

private val TileShape = RoundedCornerShape(16.dp)
private val InitialLarge = MinaType.Title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
private const val WIGGLE_DEG = 1.5f
private const val WIGGLE_MS = 250
