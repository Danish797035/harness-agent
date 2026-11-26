package com.meenai.mina.browser

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import com.meenai.mina.cap.web.LoginProbe
import com.meenai.mina.cap.web.LoginState
import com.meenai.mina.cap.web.SiteRegistry
import com.meenai.mina.cap.web.WebSites
import com.meenai.mina.cap.web.WebTab
import com.meenai.mina.core.cap.web.ExternalLink
import com.meenai.mina.core.cap.web.WebUrlPolicy
import com.meenai.mina.core.uistate.PageDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The web page currently open on the browser page. [desktop]: the tab is currently using the desktop
 * site; [loginFor]: this session is "Sign in for Mina" for the target site (opened with the
 * identity used when Mina operates, see [BrowserIdentity]), or null during normal browsing.
 */
data class BrowserPage(
    val url: String,
    val title: String,
    val progress: Int,
    val loading: Boolean,
    val canBack: Boolean,
    val canForward: Boolean,
    val desktop: Boolean = false,
    val loginFor: String? = null,
)

/**
 * The user's own browser (sidebar "Browser"): a visible [WebTab], and the same web page configuration
 * (Cookie, web page storage) as the Agent's label. I'm logged in here, and Mina is also logged in when
 * I'm working. Which version of the web page is used is determined by [BrowserIdentity]: the mobile
 * version is usually the default, and it can be changed to the computer version by pressing the website
 * (recorded in [modes]). When "logging in to Mina", the version used by Mina will be used; the rules are
 * only installed on this label (WebDevtools.setModePolicy), and the Agent label is not affected.
 *
 * It does not enter the tag pool of any task, nor does it go through the
 * [com.meenai.mina.cap.web.WebHandover] takeover: Agent tags and takeovers proceed as usual without
 * affecting each other. The label is placed in the process (the same WebView is used when switching
 * screens and page reconstructions), and is closed when leaving the browser page. When there is more
 * than one instance of the interface, the browser page may have several places at the same time: where
 * the view is hung and where the page is closed after leaving it, see [PageHosts]. The environment of
 * WebView is [MutableContextWrapper]: when it is hung on the interface, it is changed to that Activity
 * (drop-down box, long press the menu to ask for it), and when it is taken off, it is changed back to
 * the App. Go through [WebUrlPolicy.checkNavigable] before opening the page; the visited pages are
 * recorded in [recent]; after the page stops, take a look at the login status and record it in the site
 * registration form. When the user asks Mina on the page, the page he is looking at is lent to the task
 * of that conversation when the current web page label (see [loans]): when the page opens and closes the
 * pop-up window, the borrowed task is changed, and when the page is closed, the task is handed over to
 * continue to be used without being destroyed; when the user touches the page ([userTouched]), the task
 * borrowed from it is told. Only called on the main thread.
 */
class UserBrowser private constructor(private val app: Context) {
    val sites: SiteRegistry = WebSites.registry(app)
    private val dir = File(app.filesDir, "web")
    val recent = RecentPages(dir)
    val favorites = BrowserFavorites(dir)
    val looks = SiteLooks(dir)
    private val modes = UserSiteModes(dir)
    /** Looking at this page lends the task (see [PageLoans]). */
    val loans = PageLoans<WebTab>()
    /** Tag number: Each one is different (the page and its pop-up window may be lent to the same task one after another). */
    private var tabCount = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wrapper = MutableContextWrapper(app)
    private var tab: WebTab? = null
    /** Stack of parent tabs when nested popup windows are opened (last = immediate opener). */
    private val openers = ArrayDeque<WebTab>()
    private val opener: WebTab? get() = openers.lastOrNull()
    private val hosts = PageHosts<ViewGroup, Context>()
    private var probe: Runnable? = null
    /** "Login for Mina" The website to log in to; for normal browsing, it is null. Tag version rules are also read in the background thread. */
    @Volatile private var loginHost: String? = null

    private val _page = MutableStateFlow<BrowserPage?>(null)
    /** When null there is no web page to open (show the start page). */
    val page: StateFlow<BrowserPage?> = _page.asStateFlow()

    private val _dialog = MutableStateFlow<PageDialog?>(null)
    /** The page pops up and waits for the user to reply. */
    val dialog: StateFlow<PageDialog?> = _dialog.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A reminder to the user (something was blocked, the page crashed); adjust [noticeShown] after displaying. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Open a sentence in the address bar or search box (see [AddressInput]). The reason why it is
     * returned to the user when it cannot be opened, and the current page is not moved.
     */
    fun submit(input: String): String? {
        val url = AddressInput.resolve(input) ?: return null
        return open(url)
    }

    /** Open the URL for normal browsing; return the reason when it cannot be opened. */
    fun open(url: String): String? = load(url, null)

    /** Open frequently used websites: If you haven't logged in yet, go to "Log in for Mina" and open it in the same version as when Mina is working. */
    fun openFavorite(site: FavoriteSite): String? =
        load("https://${site.host}/", site.host.takeIf { BrowserIdentity.loginFlow(site) })

    /**
     * Change the current website to the computer version ([desktop]) or switch back to the mobile
     * version. Remember, it will be the same next time; it will no longer count as "logging in for
     * Mina". Reopen the current page with a new version.
     */
    fun setDesktop(desktop: Boolean) {
        val t = tab ?: return
        val url = currentUrl() ?: return
        val host = WebUrlPolicy.host(url) ?: return
        modes.set(host, desktop)
        loginHost = null
        navigate(t, url)
    }

    /**
     * Click "Login OK" in "Login Mina": Take a look at the page. If you see that you are not logged in,
     * you will be prompted; if you see that you are logged in, or if you cannot see it (if you believe
     * the user), mark this website as logged in and return to the start page. If the page is still
     * loading and cannot be viewed, wait for [CONFIRM_WAIT_MS] and then click "Cannot View".
     */
    fun confirmLogin() {
        val t = tab ?: return
        val site = loginHost ?: return
        var done = false
        fun finish(state: LoginState) {
            if (done || t !== tab || t.destroyed) return
            done = true
            if (state == LoginState.SignedOut) {
                _notice.value = tr(R.string.browser_not_logged_in)
                return
            }
            sites.markLoggedIn(site)
            currentUrl()?.let(WebUrlPolicy::host)?.takeIf { it != site && BrowserFavorites.siteKey(it) == BrowserFavorites.siteKey(site) }?.let(sites::markLoggedIn)
            WebTab.cookieManager().flush()
            Log.i(TAG, "browser login confirmed for $site ($state)")
            _notice.value = LOGGED_IN_NOTICE
            closePage()
        }
        val timeout = Runnable { finish(LoginState.Unknown) }
        main.postDelayed(timeout, CONFIRM_WAIT_MS)
        LoginProbe.probe(t) { _, state ->
            main.post {
                main.removeCallbacks(timeout)
                finish(state)
            }
        }
    }

    private fun load(url: String, login: String?): String? {
        refusal(url)?.let { return it }
        drainOpeners()
        loginHost = login
        val t = tab ?: newTab().also {
            tab = it
            place()
        }
        _page.value = BrowserPage(url, "", 0, true, false, false, desktop = !t.devtools.isMobile(t), loginFor = login)
        navigate(t, url)
        return null
    }

    private fun navigate(t: WebTab, url: String) {
        scope.launch {
            // Same as the Agent label: before navigating, change the settings and simulation of the
            // desktop or mobile version according to the rules of this label (wait for a short while at
            // most)
            t.devtools.beforeLoad(t, url)
            if (!t.destroyed) t.view.loadUrl(url)
        }
    }

    private fun currentUrl(): String? = tab?.view?.url?.takeIf { it.startsWith("http") } ?: _page.value?.url

    fun back() { tab?.view?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { tab?.view?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { tab?.view?.reload() }
    fun stop() {
        tab?.view?.stopLoading()
        refresh()
    }

    /** System return key: Go back if the web page can go back; close the pop-up window if it cannot be opened; otherwise, close the web page and return to the start page. Returns false if there is no open web page. */
    fun systemBack(): Boolean {
        val v = tab?.view ?: return false
        if (v.canGoBack()) v.goBack() else if (!closePopup()) closePage()
        return true
    }

    /** Close the web page and return to the home page. The tag lent to the task is given to the task for further use (it will not be destroyed after the task accepts it). */
    fun closePage() {
        probe?.let(main::removeCallbacks)
        probe = null
        loginHost = null
        tab?.let { t ->
            tab = null
            if (!loans.closing(t)) t.destroy() else handedOff(t)
        }
        drainOpeners()
        loans.withdraw()
        _page.value = null
        _dialog.value = null
    }

    private fun drainOpeners() {
        while (openers.isNotEmpty()) {
            val prevOpener = openers.removeLast()
            if (!loans.closing(prevOpener)) prevOpener.destroy() else handedOff(prevOpener)
        }
    }

    // The label assigned to the task is no longer here (the view has been hung from the task to its web
    // page display), and the environment is changed back to App (no interface is retained)
    private fun handedOff(t: WebTab) {
        wrapper.baseContext = app
        Log.i(TAG, "browser page ${t.id} handed to the task that borrowed it")
    }

    // ---------- Ask Mina on this page ----------

    /** The page you are looking at at the moment (used for Mina’s instructions); web pages that are not open are null. */
    fun pageInfo(): com.meenai.mina.core.cap.web.AttachedPage.Page? {
        val url = currentUrl()?.takeIf { it.startsWith("http") } ?: return null
        val title = tab?.view?.title?.takeIf { it != url }.orEmpty().ifEmpty { _page.value?.title.orEmpty() }
        return com.meenai.mina.core.cap.web.AttachedPage.Page(url, title)
    }

    /** Read the word selected by the user on the page and give it to [onText] (null if not selected or cannot be read). */
    fun readSelection(onText: (String?) -> Unit) {
        val t = tab ?: return onText(null)
        t.view.evaluateJavascript(SELECTION_SCRIPT) { raw ->
            val text = runCatching { org.json.JSONArray("[$raw]").optString(0) }.getOrNull()
            onText(text?.takeIf { raw != "null" && it.isNotBlank() })
        }
    }

    /** The user asked on this page, and the page was lent to the conversation [conversationId] for the next task to run. */
    fun lend(conversationId: String) = loans.offer(conversationId)

    /** Dialog [conversationId]'s task [runKey] is to retrieve the page that has been lent to it when it starts running; it is null when it has not been agreed, the page is gone, or it has been lent. */
    fun claim(conversationId: String, runKey: String): PageLoans.Loan<WebTab>? =
        loans.claim(conversationId, runKey, tab?.takeIf { !it.destroyed && !it.crashed })

    /** The user touched the page he was looking at (a real touch, not an input sent by Mina himself). */
    fun userTouched() {
        tab?.let(loans::touched)
    }

    /** Answer the page pop-up box. */
    fun answerDialog(accept: Boolean, text: String?) {
        tab?.dialogs?.answer(accept, text)
        _dialog.value = tab?.dialogs?.current
    }

    fun noticeShown() {
        _notice.value = null
    }

    /** The location to be used on a browser page (on an instance of the interface); given to [openHost], [setContainer], [hostStarted], [closeHost]. */
    fun newHost(activity: Context): PageHosts.Host<ViewGroup, Context> = PageHosts.Host(activity)

    /** This is the browser page. */
    fun openHost(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.open(host)
        place()
    }

    /** This is back to the front desk: the web view hangs here. */
    fun hostStarted(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.started(host)
        place()
    }

    /** The container left for the web page here is available ([container]) or missing (null). */
    fun setContainer(host: PageHosts.Host<ViewGroup, Context>, container: ViewGroup?) {
        val old = host.container
        hosts.setContainer(host, container)
        if (old !== container) old?.removeAllViews()
        place()
    }

    /**
     * This point leaves the browser page. When it is the last position and it is not a screen rotation
     * ([keepPage] is false), the web page is closed; when it is not the last position, the web page is
     * left elsewhere (the view is changed to another place).
     */
    fun closeHost(host: PageHosts.Host<ViewGroup, Context>, keepPage: Boolean) {
        host.container?.removeAllViews()
        if (hosts.close(host) && !keepPage) closePage() else place()
    }

    /**
     * Hang the web view to the place where it should be hung ([PageHosts.target]), and the environment
     * will be changed to the interface of that place; when there is no place to hang it, take it off,
     * and the environment will be changed back to the App (the old interface will not be retained).
     */
    private fun place() {
        val v = tab?.view
        val target = hosts.target()
        val container = target?.container
        if (v == null || container == null) {
            v?.let { (it.parent as? ViewGroup)?.removeView(it) }
            wrapper.baseContext = app
            return
        }
        if (v.parent === container) return
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper.baseContext = target.activity
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url) ?: return null
        return if (WebUrlPolicy.external(url) != null) tr(R.string.browser_external_link) else tr(R.string.browser_bad_scheme)
    }

    private fun newTab(): WebTab = WebTab(wrapper, "browser${++tabCount}", OWNER).also { t ->
        t.view.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
        }
        // Users can see and click with their own hands: the computer version does not have to be zoomed
        // in and out, and can be zoomed in with two fingers.
        t.devtools.setHandOperated(t)
        t.devtools.setModePolicy(t) { host ->
            BrowserIdentity.mobile(loginForMina = loginHost != null, agentMobile = t.devtools.agentWantsMobile(host), userDesktop = modes.isDesktop(host))
        }
        t.listener = listener
    }

    private fun refresh() {
        val t = tab ?: return
        val v = t.view
        val url = v.url?.takeIf { it.isNotEmpty() && it != "about:blank" } ?: _page.value?.url ?: return
        _page.value = BrowserPage(
            url, v.title.orEmpty().takeIf { it != url }.orEmpty(), t.progress, t.loading, v.canGoBack(), v.canGoForward(),
            desktop = !t.devtools.isMobile(t), loginFor = loginHost,
        )
    }

    private val listener = object : WebTab.Listener {
        override fun onPageChanged(tab: WebTab) {
            if (tab !== this@UserBrowser.tab) return
            refresh()
            val url = tab.view.url
            if (url != null && url.startsWith("http")) tab.view.title?.let { recent.retitle(url, it) }
        }

        override fun onPageFinished(tab: WebTab) {
            if (tab !== this@UserBrowser.tab) return
            refresh()
            val url = tab.view.url ?: return
            if (!url.startsWith("http")) return
            recent.add(url, tab.view.title?.takeIf { it != url })
            WebTab.cookieManager().flush()
            readName(tab, url)
            scheduleProbe(tab, 0)
        }

        override fun onIcon(tab: WebTab, icon: Bitmap) {
            if (tab !== this@UserBrowser.tab) return
            val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
            if (icon.width < MIN_ICON_PX || icon.height < MIN_ICON_PX) return
            val side = minOf(maxOf(icon.width, icon.height), MAX_ICON_PX)
            val scaled = runCatching { Bitmap.createScaledBitmap(icon, side, side, true) }.getOrNull() ?: return
            scope.launch {
                val targets = looks.iconTargets(host)
                val saved = withContext(Dispatchers.IO) {
                    targets.filter { h ->
                        runCatching {
                            val f = looks.iconFile(h)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.getOrDefault(false)
                    }
                }
                saved.forEach(looks::iconSaved)
            }
        }

        override fun onDialog(tab: WebTab) {
            if (tab === this@UserBrowser.tab) _dialog.value = tab.dialogs.current
        }

        override fun onExternalLink(tab: WebTab, link: ExternalLink) {
            _notice.value = tr(R.string.browser_blocked_app_link)
        }

        override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
            _notice.value = tr(R.string.browser_no_download)
        }

        override fun onCrashed(tab: WebTab) {
            if (tab !== this@UserBrowser.tab) return
            // Destroyed outside callback
            main.post {
                if (tab === this@UserBrowser.tab) closePage()
                _notice.value = tr(R.string.browser_crashed)
            }
        }

        /**
         * The page needs to open a new window (target=_blank, window.open): only the one clicked by the
         * user will be recognized, and only one layer will be opened. The new window is displayed on the
         * current page, and the current page remains ([opener]): "Log in with a third-party account."
         * After logging in, the result of a pop-up window such as "Log in with a third-party account"
         * must be returned to the page where it was opened. If the page is gone, it will stop at a white
         * screen. When the pop-up window closes itself, or when there is no way out of the pop-up
         * window, return to the page on which it was opened.
         */
        override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture || tab !== this@UserBrowser.tab) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = newTab()
            transport.webView = popup.view
            resultMsg.sendToTarget()
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            if (openers.size >= MAX_OPENERS) {
                val oldest = openers.removeFirst()
                if (!loans.closing(oldest)) oldest.destroy() else handedOff(oldest)
            }
            openers.addLast(tab)
            this@UserBrowser.tab = popup
            place()
            // The one lent to the task is the visible one: then replace it with the pop-up window
            loans.swapped(tab, popup)
            return true
        }

        override fun onCloseWindow(tab: WebTab) {
            if (tab === this@UserBrowser.tab) closePopup()
        }
    }

    /** Close the pop-up window and return to the page where it was opened; return false if there is no pop-up window. */
    private fun closePopup(): Boolean {
        val o = openers.removeLastOrNull() ?: return false
        tab?.let { popup ->
            loans.swapped(popup, o)
            popup.destroy()
        }
        tab = o
        place()
        refresh()
        return true
    }

    /**
     * Most of the page scripts draw the account area after loading: stop for a while and then check the
     * login status; wait while it is still loading or the address has changed, at most a few times. Some
     * websites in the registration form can be recorded or deleted according to what is seen; those that
     * do not are only recorded when logged in (the user has logged in to a new website here).
     */
    private fun scheduleProbe(t: WebTab, attempt: Int) {
        probe?.let(main::removeCallbacks)
        val urlThen = t.view.url
        val run = Runnable {
            probe = null
            if (t !== tab || t.destroyed) return@Runnable
            if (t.loading || t.view.url != urlThen) {
                if (attempt < PROBE_RETRIES) scheduleProbe(t, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(t) { host, state ->
                val known = sites.list().any { it.host == host }
                val login = loginHost
                val wasIn = login != null && BrowserFavorites.loggedIn(sites.list(), login)
                when {
                    known -> sites.loginObserved(host, state)
                    state == LoginState.SignedIn -> sites.markLoggedIn(host)
                    else -> Unit
                }
                // When "Login Mina", you can see that you have logged in: tell the user (the banner is
                // closed with the login status)
                if (login != null && !wasIn && state == LoginState.SignedIn && BrowserFavorites.siteKey(host) == BrowserFavorites.siteKey(login)) {
                    _notice.value = LOGGED_IN_NOTICE
                }
                Log.i(TAG, "browser login probe $host: $state (known=$known)")
            }
        }
        probe = run
        main.postDelayed(run, PROBE_DELAY_MS)
    }

    /** After the page is loaded, read the website name (the website name or title declared on the page) and write it down as [looks]. */
    private fun readName(t: WebTab, url: String) {
        val host = WebUrlPolicy.host(url) ?: return
        if (AddressInput.searchQuery(url) != null) return
        t.view.evaluateJavascript(SiteNames.SCRIPT) { raw ->
            if (t.destroyed) return@evaluateJavascript
            val (declared, title) = SiteNames.parse(raw) ?: return@evaluateJavascript
            val (name, rank) = SiteNames.pick(declared, title, SiteNames.isHomePage(url)) ?: return@evaluateJavascript
            looks.named(host, name, rank)
        }
    }

    companion object {
        private const val TAG = "MinaUserBrowser"
        /** This tag does not belong to any task. */
        const val OWNER = "user-browser"
        private const val PROBE_DELAY_MS = 1_500L
        private const val PROBE_RETRIES = 3
        private const val CONFIRM_WAIT_MS = 2_500L
        private const val MAX_OPENERS = 5
        /** The accepted website icon must be at least this big, and the saved website icon must be at most this big (in pixels). */
        private const val MIN_ICON_PX = 16
        private const val MAX_ICON_PX = 128
        val LOGGED_IN_NOTICE: String get() = tr(R.string.browser_logged_in)
        /** The selected words on the page (including the selected paragraph in the input box). */
        private const val SELECTION_SCRIPT = "(function(){var s=window.getSelection();var t=s?String(s):'';" +
            "var a=document.activeElement;if(!t&&a&&typeof a.selectionStart==='number'&&a.type!=='password')" +
            "t=String(a.value||'').substring(a.selectionStart,a.selectionEnd);return t;})()"

        /** The copy that already exists in the process; the browser page that has not been opened is null (do not create a copy for it). */
        fun existing(): UserBrowser? = instance

        @Volatile private var instance: UserBrowser? = null

        fun of(context: Context): UserBrowser =
            instance ?: synchronized(this) { instance ?: UserBrowser(context.applicationContext).also { instance = it } }
    }
}
