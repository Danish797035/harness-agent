package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.rememberCoroutineScope
import com.meenai.mina.browser.PageAskMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meenai.mina.browser.BrowserFavorites
import com.meenai.mina.browser.SiteLooks
import com.meenai.mina.browser.StartPageModel
import com.meenai.mina.browser.UserBrowser
import com.meenai.mina.core.cap.web.WebUrlPolicy
import com.meenai.mina.core.uistate.PageDialog
import com.meenai.mina.ui.components.PageBackground

/**
 * Browser page (sidebar "Browser"): When there is no open web page, it is the start page
 * ([BrowserStart]: search box, frequently used websites, recently opened), when it is open, it is the
 * web page ([BrowserPageBar] + web page + [BrowserToolbar], "more" is [BrowserMoreSheet]). At the bottom
 * of the webpage is "Ask Mina" ([PageAskBar]): ask this page, and this page is lent to the task as its
 * current webpage label; when working, the lower half of the webpage is covered with a dialogue panel
 * ([PagePanel]), and a light edge is illuminated around the page ([PageGlow]); Mina pauses as soon as
 * you touch the page, and the panel closes with a small card ([PageCard]), with a "Continue"
 * ([PausedBanner]) on top. When selecting text on a web page, there is an additional item "Ask Mina" in
 * the text selection menu, and the selected words are brought in. The status is [PageChat]. The system
 * return key first goes back in the web page. If it cannot go back to the start page, press it again on
 * the start page to go back to where you came in. The web page view is placed in the process
 * ([UserBrowser]) and continues to be used when the screen is turned; it is closed when leaving this
 * page (not turning the screen, and there is no browser page elsewhere).
 */
@Composable
fun BrowserScreen(browser: UserBrowser, onBack: () -> Unit) {
    val context = LocalContext.current
    val page by browser.page.collectAsState()
    val dialog by browser.dialog.collectAsState()
    val notice by browser.notice.collectAsState()
    val registry by browser.sites.entries.collectAsState()
    val recentPages by browser.recent.entries.collectAsState()
    val edits by browser.favorites.edits.collectAsState()
    val looks by browser.looks.entries.collectAsState()
    val activity = remember(context) { context.findActivity() }
    var moreOpen by rememberSaveable { mutableStateOf(false) }

    val face = remember(looks) { { host: String -> SiteLooks.faceOf(host, looks, browser.looks::iconFile) } }
    val favorites = remember(edits, registry) { BrowserFavorites.combine(edits, registry) }
    val recent = remember(recentPages, looks) { StartPageModel.recentRows(recentPages) { face(it).name } }
    val toast: (String) -> Unit = remember(context) { { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } }
    val scope = rememberCoroutineScope()
    val chat = remember(browser) { PageChat(context.applicationContext, browser, scope) }
    // Changed the page (I haven’t asked on this page yet) and found the next conversation again; closed
    // the page and returned to nothing.
    val pageUrl = page?.url
    LaunchedEffect(pageUrl) { chat.onPage(pageUrl) }

    // This browser page: register when you come in, ask for the web page view when you return to the
    // front desk (when there are two instances of the interface, hang it on the visible one), return it
    // when you leave; close the web page when you leave at the last place (not rotate the screen)
    val place = remember(browser, activity) { browser.newHost(activity ?: context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(browser, place, lifecycle) {
        browser.openHost(place)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) browser.hostStarted(place) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            browser.closeHost(place, keepPage = activity?.isChangingConfigurations == true)
        }
    }
    BackHandler(enabled = page != null) { browser.systemBack() }
    LaunchedEffect(notice) {
        notice?.let {
            toast(it)
            browser.noticeShown()
        }
    }

    PageBackground {
        val p = page
        if (p == null) {
            LaunchedEffect(Unit) { moreOpen = false }
            BrowserStart(
                sites = favorites, recent = recent, face = face,
                onBack = onBack,
                onSubmit = { input -> browser.submit(input)?.let(toast) },
                onOpenUrl = { url -> browser.open(url)?.let(toast) },
                onOpenSite = { site -> browser.openFavorite(site)?.let(toast) },
                onRemoveSite = { site ->
                    browser.favorites.remove(site.host)
                    toast(tr(R.string.browser_removed, face(site.host).name))
                },
                onAddHint = { toast(BROWSER_ADD_HINT) },
                onClearRecent = browser.recent::clear,
            )
        } else {
            val host = WebUrlPolicy.host(p.url)
            val siteFace = remember(host, face) { host?.let(face) }
            val loginBanner = p.loginFor != null && !BrowserFavorites.loggedIn(registry, p.loginFor)
            Column(Modifier.fillMaxSize()) {
                BrowserPageBar(
                    p, siteFace,
                    onClose = browser::closePage,
                    onSubmit = { input -> browser.submit(input)?.let(toast) },
                    onReload = browser::reload, onStop = browser::stop,
                )
                if (loginBanner) LoginBanner(desktop = p.desktop, onDone = browser::confirmLogin)
                val mode = chat.mode
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The web view is put into this container by UserBrowser (the same one is put into
                    // the same one even if the label is changed); the finger press tells the task of
                    // borrowing the page, and there is "Ask Mina" in the menu when the text is selected.
                    AndroidView(
                        factory = { ctx ->
                            PageFrame(ctx, onTouch = chat::touched, onAsk = { done ->
                                browser.readSelection { text ->
                                    done()
                                    text?.let(chat::askAbout)
                                }
                            }).also { browser.setContainer(place, it) }
                        },
                        modifier = Modifier.fillMaxSize().testTag("browser-page"),
                        onRelease = { browser.setContainer(place, null) },
                    )
                    PageAskOverlay(chat, site = siteFace?.name ?: p.url, pageTitle = p.title)
                }
                if (mode == PageAskMode.Bar && p.loginFor == null) PageAskBar(chat, Modifier.imePadding())
                BrowserToolbar(
                    p, onBackPage = browser::back, onForward = browser::forward, onHome = browser::closePage,
                    onShare = { share(context, p.url, p.title)?.let(toast) },
                    onMore = { moreOpen = true },
                )
            }
            if (moreOpen) {
                val favorite = host != null && BrowserFavorites.contains(favorites, host)
                BrowserMoreSheet(
                    face = siteFace, host = host, favorite = favorite, desktop = p.desktop,
                    onDismiss = { moreOpen = false },
                    onReload = browser::reload,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(p.title.ifBlank { p.url }, p.url))
                        toast(tr(R.string.browser_link_copied))
                    },
                    onOpenOutside = { openOutside(context, p.url)?.let(toast) },
                    onToggleFavorite = {
                        if (host == null) return@BrowserMoreSheet
                        if (favorite) {
                            browser.favorites.remove(host)
                            toast(tr(R.string.browser_unfavorited))
                        } else {
                            browser.favorites.add(host)
                            toast(tr(R.string.browser_favorited))
                        }
                    },
                    onToggleDesktop = {
                        browser.setDesktop(!p.desktop)
                        toast(if (p.desktop) tr(R.string.browser_switched_mobile) else tr(R.string.browser_switched_desktop))
                    },
                )
            }
        }
    }

    dialog?.let { d -> PageDialogBox(d, browser::answerDialog) }
}

/** System sharing panel: URL, title as theme. The reason is returned when it cannot be opened. */
private fun share(context: Context, url: String, title: String): String? {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title)
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_no_share_app)
    }
}

/** Leave it to the system's default browser to open. The reason is returned when it cannot be opened. */
private fun openOutside(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    null
} catch (e: ActivityNotFoundException) {
    tr(R.string.browser_no_other_browser)
}

/** Page pop-up box: prompt only "OK"; confirm and leave the page with "Cancel"; input box pop-up box has an input box. */
@Composable
private fun PageDialogBox(d: PageDialog, answer: (Boolean, String?) -> Unit) {
    var input by remember(d) { mutableStateOf(d.defaultText.orEmpty()) }
    val leave = d.kind == PageDialog.BEFORE_UNLOAD
    AlertDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(if (leave) tr(R.string.browser_leave_q) else tr(R.string.browser_page_says)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.message.isNotBlank()) Text(d.message)
                if (leave && d.message.isBlank()) Text(tr(R.string.browser_leave_body))
                if (d.kind == PageDialog.PROMPT) TextField(input, { input = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ answer(true, if (d.kind == PageDialog.PROMPT) input else null) }) { Text(if (leave) tr(R.string.browser_leave) else tr(R.string.ok_yes)) }
        },
        dismissButton = if (d.kind == PageDialog.ALERT) null else ({ TextButton({ answer(false, null) }) { Text(tr(R.string.cancel)) } }),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
