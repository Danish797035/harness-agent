package com.meenai.mina.cap.web.cdp

import android.content.Context
import android.icu.util.ULocale
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebViewCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.meenai.mina.core.cap.web.cdp.CdpDiscovery
import com.meenai.mina.core.cap.web.cdp.CdpSession
import com.meenai.mina.core.cap.web.cdp.CdpTransport
import com.meenai.mina.core.cap.web.cdp.evaluate
import com.meenai.mina.core.shell.DevtoolsSocket
import com.meenai.mina.core.cap.web.WebUrlPolicy
import android.os.ParcelFileDescriptor
import com.meenai.mina.cap.web.WebHost
import com.meenai.mina.cap.web.WebTab
import com.meenai.mina.i18n.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.meenai.mina.cap.web.WebOwner

/**
 * DevTools session entry for each web page tab. When the connection cannot be made (the system does not
 * support it, it is blocked, or the label is closed), it is null, and the caller returns to the method
 * of injecting the script. All labels default to the desktop version, which is the new baseline and does
 * not depend on sessions: when there is no session, the desktop UA, UA-CH (when supported) and wide
 * viewport in the WebView settings still take effect, but the simulation layer (viewport width,
 * navigator.platform) is missing.
 *
 * Try the connection methods in order and remember the results: direct connection to
 * `webview_devtools_remote_<pid>` of this process; if rejected, go through [relay] proxy connection
 * (built-in assistant, shell identity; given by the assembly layer); if it fails, there will be no
 * connection. There is usually one copy in the process (given by the App, see [of]); for testing, you
 * can create your own copy. Changing the session source and connection method will only affect you. When
 * each tag is used for the first time, write down a tag, find it one by one and remember the targetId.
 * After that, keep using this session; disconnect and reconnect once, and if it fails, the tag will be
 * downgraded (try again after a while).
 *
 * The desktop version of the webpage is also here: as soon as the label is created, change the WebView
 * settings to desktop UA (the fallback when the DevTools cannot be connected) and inject the identity
 * patch at the beginning of the document. After the session is connected, use simulation to change the
 * UA-CH, viewport, touch and pointer to the desktop (see [DesktopProfile]); change the mobile version of
 * the site remembered by the site back to the default. When the user selects the interface language in
 * Mina, both versions of the web page are requested in that language (see [PageLanguage]); they do not
 * move when following the system.
 */
class WebDevtools(private val relay: ((String) -> ParcelFileDescriptor?)?) {
    enum class Route { Unknown, Direct, Relay, None }

    /** For testing: only use direct connection / only use proxy connection / treat as unable to connect; null means automatic. After making the change, adjust [resetRoute]. */
    @Volatile var forceRoute: Route? = null

    private val _route = MutableStateFlow(Route.Unknown)
    /** The most recently detected connection method; Unknown has not been tested yet. */
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** DevTools (for diagnostics and interfaces) are now available. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile var provider: (WebTab) -> CdpSession? = this::cached

    /** The session is already connected; if it is not connected yet, it will start the connection in the background and return null first. No blocking, any thread can be adjusted. */
    fun session(tab: WebTab): CdpSession? =
        // Sessions that have changed sources (those prepared for testing, etc.) must also convert the
        // Input coordinates according to the viewport simulation of this label.
        provider(tab)?.also { s -> links[tab]?.let { s.inputScale = it.inputScale } }

    /** Wait for the session to be connected (at most [timeoutMs]); if not connected, it is null. Don't call it where the main thread is waiting. */
    suspend fun awaitSession(tab: WebTab, timeoutMs: Long = ATTACH_TIMEOUT_MS): CdpSession? {
        val link = links[tab] ?: return null
        return bounded(timeoutMs) { attach(link) }
    }

    /** This tag is now used on the mobile version of the webpage. */
    fun isMobile(tab: WebTab): Boolean = links[tab]?.mobile ?: false

    /** The DevTools target id of this tag; null if not connected. */
    fun targetId(tab: WebTab): String? = links[tab]?.targetId

    // ---------- Tag life cycle (WebTab adjustment) ----------

    /** The label has just been created (main thread): open the debugging interface, change to the desktop version of WebView settings, and turn off the X-Requested-With header. */
    fun prepare(tab: WebTab, context: Context) {
        val st = tab.view.settings
        init(context, st)
        val link = Link(tab)
        links[tab] = link
        // The view changes size (changes host display, enters and exits the takeover interface): the
        // viewport zoom of the desktop version is recalculated accordingly
        tab.view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onViewResized(link)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, emptySet()) }
        }
        applySettings(st, mobile = false)
        applyIdentityScript(link)
        link.acceptLanguage = chosenAcceptLanguage()
    }

    /** Before loading the main document (main thread, can be suspended): Select the mobile version or desktop version according to the target website, and try to connect the session and set up the simulation before the first navigation. */
    suspend fun beforeLoad(tab: WebTab, url: String) {
        val link = links[tab] ?: return
        val mobile = wantsMobile(link, url) ?: false
        val changed = link.mobile != mobile
        if (changed) switchMode(link, mobile)
        // The user changed Mina's language midway: open tabs will be changed before the next main
        // document is loaded (the simulation is reset here, and this page is requested in the new
        // language)
        chosenAcceptLanguage().let { lang ->
            if (lang != link.acceptLanguage) {
                link.acceptLanguage = lang
                link.configured = null
            }
        }
        // The connection and simulation are done in the background. Wait for a short while at most: it
        // will load as usual when the point is reached (this page first follows the retreat path set by
        // WebView, and then changes after connecting)
        bounded(BEFORE_LOAD_WAIT_MS) {
            val s = attach(link)
            if (s != null && link.configured != link.mobile) link.lock.withLock { configure(link, s) }
            s
        }
    }

    // Do it in an independent coroutine. The caller will wait until the upper limit and leave without
    // being held back by the blocking reading and writing inside.
    private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T?): T? {
        val job = scope.async { block() }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    /**
     * The main document starts loading (main thread): when you click on the link and jump to another
     * version of the website, change it and reload it. It's not that web pages (about:blank, etc.) don't
     * move.
     */
    fun onPageStarted(tab: WebTab, url: String?) {
        val link = links[tab] ?: return
        if (url == null || !url.startsWith("http")) return
        link.loadedBeforeAttach = true
        val mobile = wantsMobile(link, url) ?: return
        if (link.mobile == mobile) return
        switchMode(link, mobile)
        scope.launch {
            link.session?.takeIf { !it.closed.value }?.let { runCatching { configure(link, it) } }
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
    }

    /**
     * This tag determines which version to use for each website ([policy] collects the host name,
     * returns true for the mobile version), and no longer looks at the version remembered by the site
     * (Agent's); set it to null to change back to that version. Only affects this tag and will take
     * effect the next time the main document is loaded (the current page is reloaded by the caller).
     * Called by the main thread.
     */
    fun setModePolicy(tab: WebTab, policy: ((String?) -> Boolean)?) {
        links[tab]?.policy = policy
    }

    /**
     * This label is viewed and clicked by the user (the label in the browser page): the desktop version
     * does not include viewport simulation, and the page can be zoomed in and out with two fingers (see
     * [DesktopProfile.viewportCall]). It takes effect the next time the session is configured. Called by
     * the main thread.
     */
    fun setHandOperated(tab: WebTab) {
        links[tab]?.handOperated = true
    }

    /** When opening the Agent's label [host], do you need to use the mobile version (click the one remembered by the site; click the default desktop version when no label has been created). */
    fun agentWantsMobile(host: String?): Boolean = modes?.isMobile(host) ?: false

    // The rules of the tag itself take precedence; if there is no rule, it will look at the one
    // remembered by the site. If it has not been read in, it will be null.
    private fun wantsMobile(link: Link, url: String): Boolean? {
        val host = WebUrlPolicy.host(url)
        link.policy?.let { return it(host) }
        return modes?.isMobile(host)
    }

    /** This view should be hung on the host display of the desktop viewport size (use the desktop version for the label). Unknown views are false. */
    fun wantsDesktopHost(view: WebView): Boolean = links.values.firstOrNull { it.tab.view === view }?.let { !it.mobile } ?: false

    // Main thread: Switch the two-layer settings, and switch the view hanging on the host to a display
    // of the corresponding size.
    private fun switchMode(link: Link, mobile: Boolean) {
        link.mobile = mobile
        applySettings(link.tab.view.settings, mobile)
        applyIdentityScript(link)
        WebHost.followMode(link.tab)
    }

    /**
     * Main thread: The desktop version injects the identity patch ([DesktopProfile.identityScript]) at
     * the beginning of each document (including frames), and the mobile version removes it. The system
     * does not support document start injection being replaced by Page.addScriptToEvaluateOnNewDocument
     * on the session (see [configure]).
     */
    private fun applyIdentityScript(link: Link) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (link.mobile) {
            link.identityScript?.let { runCatching { it.remove() } }
            link.identityScript = null
        } else if (link.identityScript == null) {
            link.identityScript = runCatching { WebViewCompat.addDocumentStartJavaScript(link.tab.view, DesktopProfile.identityScript(), setOf("*")) }
                .onFailure { Log.w(TAG, "identity script on tab ${link.tab.id}: ${it.message}") }.getOrNull()
        }
    }

    private fun onViewResized(link: Link) {
        if (link.mobile) return
        val s = link.session?.takeIf { !it.closed.value } ?: return
        scope.launch {
            link.lock.withLock {
                if (link.session === s && !link.mobile) runCatching { configure(link, s) }.onFailure { Log.w(TAG, "re-fit tab ${link.tab.id}: ${it.message}") }
            }
        }
    }

    /** The tab is closed: disconnect the session and forget about it. */
    fun release(tab: WebTab) {
        val link = links.remove(tab) ?: return
        link.session?.let { s -> scope.launch { s.close() } }
        link.session = null
    }

    /** Note whether the site uses the mobile version or the desktop version; the current page will be reloaded by the caller ([beforeLoad] will be swapped before loading). */
    fun setSiteMode(host: String, mobile: Boolean) {
        modes?.set(host, mobile)
    }

    /** For testing: Change back to the real session source (when other tests have changed [provider] but not changed back). */
    fun resetProvider() {
        provider = this::cached
    }

    /** For testing purposes: Forget the detected connection method and retest next time. */
    fun resetRoute() {
        path = null
        directFailed = false
        _route.value = Route.Unknown
        _available.value = false
    }

    // ---------- Internal ----------

    private class Link(val tab: WebTab) {
        val lock = Mutex()
        @Volatile var session: CdpSession? = null
        @Volatile var targetId: String? = null
        @Volatile var mobile = false
        /** This tag's own version rule (see [setModePolicy]); when null, the one remembered by the site. */
        @Volatile var policy: ((String?) -> Boolean)? = null
        /** Labels operated by the user (see [setHandOperated]): No viewport emulation is added. */
        @Volatile var handOperated = false
        /** The language in which the web page is requested (the value of Accept-Language); null means following the system and not covering it. Create tags and retrieve them in Mina's language each time before loading the main document. */
        @Volatile var acceptLanguage: String? = null
        /** The version that has been set on the session; null means it has not been set yet. */
        @Volatile var configured: Boolean? = null
        @Volatile var degradedAt = 0L
        @Volatile var loadedBeforeAttach = false
        @Volatile var everAttached = false
        /** The ratio of Input field coordinates to CSS pixels under the current simulation (see [CdpSession.inputScale]). */
        @Volatile var inputScale = 1.0
        /** The devicePixelRatio (how many view pixels each CSS pixel of WebView occupies) of the page without viewport simulation, and the display where the view is when measuring it. */
        @Volatile var baseDpr: Double? = null
        @Volatile var baseDprDisplay = -1
        /** Viewport emulation has been set up on this session; set the devicePixelRatio that the page reads later. */
        @Volatile var metricsSet = false
        @Volatile var emulatedDpr: Double? = null
        /** Desktop identity patch injected at the beginning of the document (main thread active); null for mobile version. */
        var identityScript: ScriptHandler? = null
        /** The system does not support the same patch number registered on this session when the document injection starts. */
        @Volatile var sessionScriptId: String? = null
    }

    /** A kind of connection method. */
    private class Path(val route: Route, val transport: CdpTransport)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = ConcurrentHashMap<WebTab, Link>()
    private val pathLock = Mutex()
    @Volatile private var path: Path? = null
    @Volatile private var directFailed = false
    @Volatile private var relayFailedAt = 0L
    @Volatile private var modes: SiteModes? = null
    @Volatile private var desktop: JsonObject? = null
    @Volatile private var desktopUa: String? = null
    @Volatile private var desktopMetadata: UserAgentMetadata? = null
    @Volatile private var defaultMetadata: UserAgentMetadata? = null
    /** The default mobile identity of WebView (UA-CH in the form of UA and CDP): When the language is selected in the mobile version, it will be copied and only the language will be changed. */
    @Volatile private var defaultUa: String = ""
    @Volatile private var defaultMetadataJson: JsonObject? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var debuggingOn = false

    private val socketName: String get() = DevtoolsSocket.name(Process.myPid())

    @Synchronized
    private fun init(context: Context, st: WebSettings) {
        if (!debuggingOn) {
            WebView.setWebContentsDebuggingEnabled(true)
            debuggingOn = true
        }
        if (modes == null) modes = SiteModes(File(context.applicationContext.filesDir, "web"))
        if (appContext == null) appContext = context.applicationContext
        if (desktopUa != null) return
        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        this.defaultUa = defaultUa
        val (major, full) = DesktopProfile.version(defaultUa) ?: ("120" to "120.0.0.0")
        val metadataOk = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        // Before changing the settings for the first time, take the kernel's own UA-CH and replace it
        // with the mobile site.
        val probe = if (metadataOk) runCatching { WebSettingsCompat.getUserAgentMetadata(st) }.getOrNull() else null
        val engine = probe?.brandVersionList?.map { Brand(it.brand, it.majorVersion, it.fullVersion) }
        val brands = DesktopProfile.brands(engine, major, full)
        defaultMetadata = probe
        defaultMetadataJson = probe?.let { m ->
            DesktopProfile.metadata(
                m.brandVersionList.map { Brand(it.brand, it.majorVersion, it.fullVersion) }, m.fullVersion.orEmpty(), m.platform.orEmpty(),
                m.platformVersion.orEmpty(), m.architecture.orEmpty(), m.model.orEmpty(), m.isMobile,
                if (m.bitness == UserAgentMetadata.BITNESS_DEFAULT) "" else m.bitness.toString(), m.isWow64,
            )
        }
        desktopMetadata = if (metadataOk) {
            UserAgentMetadata.Builder()
                .setBrandVersionList(brands.map { UserAgentMetadata.BrandVersion.Builder().setBrand(it.brand).setMajorVersion(it.major).setFullVersion(it.full).build() })
                .setFullVersion(full).setPlatform(DesktopProfile.PLATFORM).setPlatformVersion("").setArchitecture("x86")
                .setModel("").setMobile(false).setBitness(64).setWow64(false).build()
        } else null
        desktop = DesktopProfile.userAgentOverride(major, full, brands)
        desktopUa = DesktopProfile.userAgent(major)
    }

    /**
     * The Accept-Language corresponding to the language selected by the user in Mina; the following
     * system is null. The label is first completed according to the common writing method of ICU (en →
     * en-Latn-US) and then written as a request header.
     */
    private fun chosenAcceptLanguage(): String? {
        val tag = appContext?.let { runCatching { AppLanguage.current(it).tag }.getOrNull() } ?: return null
        return PageLanguage.acceptLanguage(ULocale.addLikelySubtags(ULocale.forLanguageTag(tag)).toLanguageTag())
    }

    // Fallback layer: UA, UA-CH (when supported), wide viewport. After the simulation is connected, the
    // simulation shall prevail (the language can only be set by the simulation, see reloading in
    // [attach])
    private fun applySettings(st: WebSettings, mobile: Boolean) {
        st.userAgentString = if (mobile) null else desktopUa
        st.useWideViewPort = !mobile
        st.loadWithOverviewMode = !mobile
        val meta = if (mobile) defaultMetadata else desktopMetadata
        if (meta != null && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching { WebSettingsCompat.setUserAgentMetadata(st, meta) }
        }
    }

    private fun cached(tab: WebTab): CdpSession? {
        val link = links[tab] ?: return null
        link.session?.takeIf { !it.closed.value }?.let { return it }
        if (!tab.destroyed && !link.lock.isLocked && retryAllowed(link)) scope.launch { attach(link) }
        return null
    }

    private fun retryAllowed(link: Link) =
        link.degradedAt == 0L || SystemClock.elapsedRealtime() - link.degradedAt >= DEGRADED_RETRY_MS

    private suspend fun attach(link: Link): CdpSession? = link.lock.withLock {
        link.session?.takeIf { !it.closed.value }?.let { return it }
        val tab = link.tab
        if (tab.destroyed || tab.crashed || links[tab] !== link || !retryAllowed(link)) return null
        val p = path() ?: return null
        val s = try {
            link.targetId?.let { reopen(p, it) } ?: find(p, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "attach tab ${tab.id}: ${e.message}")
            null
        }
        if (s == null) {
            link.degradedAt = SystemClock.elapsedRealtime()
            // The proxy connection path may have been broken (the assistant is gone): recheck next time
            if (p.route == Route.Relay) invalidate(p)
            return null
        }
        link.session = s
        link.degradedAt = 0L
        link.configured = null
        // No emulation on new session (WebView revoked when disconnected)
        link.metricsSet = false
        link.sessionScriptId = null
        runCatching { configure(link, s) }.onFailure { Log.w(TAG, "emulation on tab ${tab.id}: ${it.message}") }
        if (!link.everAttached && link.loadedBeforeAttach && (!link.mobile || link.acceptLanguage != null)) {
            // Page loaded before emulation was set up (pop-up tabs, first page not waiting for session,
            // etc.): Reload once for desktop or selected language. There is no language item in the
            // WebView settings. Only simulation can change the request header, so the mobile version
            // must also reload when the language is selected.
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
        link.everAttached = true
        watch(link, s)
        s
    }

    private suspend fun configure(link: Link, s: CdpSession) {
        val mobile = link.mobile
        if (mobile) {
            // Follow the system time and space UA to cancel the override and return to the WebView
            // settings (changed back to the default); when the language is selected, the default
            // identity will be copied and only the language will be changed.
            s.send("Emulation.setUserAgentOverride", PageLanguage.mobileOverride(defaultUa, defaultMetadataJson, link.acceptLanguage), timeoutMs = CALL_TIMEOUT_MS)
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            s.inputScale = 1.0
            link.inputScale = 1.0
            DesktopProfile.clearMediaCall().let { (m, p) -> runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) } }
            link.sessionScriptId?.let { id ->
                runCatching { s.send("Page.removeScriptToEvaluateOnNewDocument", buildJsonObject { put("identifier", id) }, timeoutMs = CALL_TIMEOUT_MS) }
                link.sessionScriptId = null
            }
        } else {
            // Before typesetting, press the host’s default desktop viewport size.
            val (size, display) = withContext(Dispatchers.Main) {
                val v = link.tab.view
                (if (v.width > 0 && v.height > 0) v.width to v.height else DesktopProfile.HOST_WIDTH to DesktopProfile.HOST_HEIGHT) to (v.display?.displayId ?: -1)
            }
            val (w, h) = size
            desktop?.let { s.send("Emulation.setUserAgentOverride", PageLanguage.withLanguage(it, link.acceptLanguage), timeoutMs = CALL_TIMEOUT_MS) }
            // Touch, pointer and hover: Each one is sent separately, and the one that the kernel does
            // not recognize will not affect the others.
            for ((m, p) in DesktopProfile.emulationCalls()) {
                runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }.onFailure { Log.i(TAG, "$m not applied on tab ${link.tab.id}: ${it.message}") }
            }
            if (link.sessionScriptId == null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                link.sessionScriptId = runCatching {
                    s.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", DesktopProfile.identityScript()) }, timeoutMs = CALL_TIMEOUT_MS)["identifier"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            val hand = link.handOperated
            val fit = if (hand) null else DesktopProfile.fitScale(w, baseDpr(link, s, display).toFloat())
            DesktopProfile.viewportCall(hand, w, h, fit).let { (m, p) -> s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }
            link.metricsSet = !hand
            link.emulatedDpr = if (hand) null else readDpr(s)
            // The coordinates of the Input field after scaling are interpreted according to the view
            // before scaling (DIP): the caller still gives CSS pixels as usual, and is multiplied by
            s.inputScale = fit ?: 1.0
            link.inputScale = s.inputScale
            Log.i(TAG, if (hand) "tab ${link.tab.id} desktop identity without viewport emulation (hand-operated)" else "tab ${link.tab.id} desktop viewport fitted to ${w}x$h view px (scale $fit)")
        }
        link.configured = mobile
    }

    /**
     * How many view pixels does WebView occupy per CSS pixel without emulation. It depends on the
     * display density recognized by WebView. The display may change when the view is changed, so press
     * Display Note; when you want the weight, cancel the simulation first, and wait until the value read
     * on the page leaves the simulation value before taking it.
     */
    private suspend fun baseDpr(link: Link, s: CdpSession, display: Int): Double {
        link.baseDpr?.takeIf { link.baseDprDisplay == display }?.let { return it }
        var v = if (link.metricsSet) {
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            var cur: Double? = null
            val until = SystemClock.elapsedRealtime() + DPR_SETTLE_MS
            while (SystemClock.elapsedRealtime() < until) {
                cur = readDpr(s)
                if (cur != null && cur != link.emulatedDpr) break
                delay(DPR_POLL_MS)
            }
            cur
        } else readDpr(s)
        if (v == null || v <= 0) v = link.baseDpr ?: withContext(Dispatchers.Main) { link.tab.view.resources.displayMetrics.density.toDouble() }
        link.baseDpr = v
        link.baseDprDisplay = display
        return v
    }

    private suspend fun readDpr(s: CdpSession): Double? = runCatching {
        s.evaluate("devicePixelRatio", timeoutMs = CALL_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull?.toDouble()
    }.getOrNull()

    private fun watch(link: Link, s: CdpSession) {
        scope.launch {
            s.closed.first { it }
            val tab = link.tab
            if (links[tab] !== link || tab.destroyed || tab.crashed || link.session !== s) return@launch
            link.session = null
            // Reconnect every time you are disconnected; if you cannot connect to this label, it will be
            // downgraded. Please try again after a while.
            delay(RECONNECT_BACKOFF_MS)
            if (attach(link) == null) Log.w(TAG, "tab ${tab.id} lost its devtools session and could not reconnect: degraded")
        }
    }

    /** Known targetId: Directly connect back (the correspondence between label and target remains unchanged across navigations). If the target is gone, it is null. */
    private suspend fun reopen(p: Path, targetId: String): CdpSession? {
        val t = CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS).firstOrNull { it.id == targetId } ?: return null
        val ws = t.webSocketDebuggerUrl ?: return null
        return connect(p, ws)
    }

    private suspend fun find(p: Path, link: Link): CdpSession? {
        val tab = link.tab
        val marker = UUID.randomUUID().toString()
        if (!mark(tab, marker)) return null
        val claimed = links.values.mapNotNull { l -> l.targetId.takeIf { l !== link } }.toSet()
        val tabUrl = withContext(Dispatchers.Main) { tab.view.url }
        val candidates = TargetMatch.candidates(CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS), claimed, tabUrl)
        for (t in candidates) {
            val s = connect(p, t.webSocketDebuggerUrl!!) ?: continue
            val v = runCatching {
                s.evaluate(TargetMatch.READ_SCRIPT, timeoutMs = MARK_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (TargetMatch.isMarker(v, marker)) {
                runCatching { s.evaluate(TargetMatch.CLEAR_SCRIPT, timeoutMs = MARK_TIMEOUT_MS) }
                link.targetId = t.id
                return s
            }
            s.close()
        }
        Log.w(TAG, "no devtools target carries tab ${tab.id}'s marker (${candidates.size} candidates)")
        return null
    }

    // The handshake has an upper limit (the flow is closed at the end), and the stuck handshake will not
    // always occupy the tag's lock.
    private suspend fun connect(p: Path, webSocketUrl: String): CdpSession? =
        try {
            CdpSession.connect(p.transport, socketName, CdpDiscovery.webSocketPath(webSocketUrl), handshakeTimeoutMs = CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "devtools connect via ${p.route}: ${e.message}")
            null
        }

    /** Write the tag in the current document of the tag (executed by the main thread) and wait for it to complete. */
    private suspend fun mark(tab: WebTab, marker: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (tab.destroyed) done.complete(false)
            else tab.view.evaluateJavascript(TargetMatch.markScript(marker)) { done.complete(true) }
        }
        return withTimeoutOrNull(MARK_TIMEOUT_MS) { done.await() } ?: false
    }

    private suspend fun path(): Path? = pathLock.withLock {
        val forced = forceRoute
        if (forced == Route.None) return publish(null, Route.None)
        path?.let { if (forced == null || it.route == forced) return it }
        if (forced == Route.Direct || (forced == null && !directFailed)) {
            val direct = DirectTransport()
            if (probe(direct)) return publish(Path(Route.Direct, direct), Route.Direct)
            if (forced == null) {
                directFailed = true
                Log.i(TAG, "direct devtools connection refused; trying the phone helper")
            }
        }
        if (forced == Route.Relay || forced == null) {
            if (forced == null && SystemClock.elapsedRealtime() - relayFailedAt < RELAY_RETRY_MS && relayFailedAt != 0L) return publish(null, Route.None)
            relay?.let { open ->
                val t = RelayTransport(open)
                if (probe(t)) return publish(Path(Route.Relay, t), Route.Relay)
                relayFailedAt = SystemClock.elapsedRealtime()
            }
        }
        publish(null, Route.None)
    }

    private fun publish(p: Path?, r: Route): Path? {
        path = p
        _route.value = r
        _available.value = p != null
        return p
    }

    private suspend fun invalidate(p: Path) = pathLock.withLock {
        if (path === p) publish(null, Route.Unknown)
    }

    private suspend fun probe(t: CdpTransport): Boolean =
        runCatching { CdpDiscovery.version(t, socketName, timeoutMs = PROBE_TIMEOUT_MS) }.isSuccess

    companion object {
        private const val TAG = "MinaDevtools"
        private const val ATTACH_TIMEOUT_MS = 3_000L
        /** The upper limit of the waiting session before the first navigation: If you cannot connect, you cannot delay opening the web page for too long. */
        private const val BEFORE_LOAD_WAIT_MS = 2_500L
        private const val PROBE_TIMEOUT_MS = 2_000L
        private const val DISCOVERY_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val MARK_TIMEOUT_MS = 2_000L
        private const val CALL_TIMEOUT_MS = 3_000L
        private const val RECONNECT_BACKOFF_MS = 500L
        private const val DPR_SETTLE_MS = 500L
        private const val DPR_POLL_MS = 50L
        /** The downgraded label will try again after so long (the assistant may have started again). */
        private const val DEGRADED_RETRY_MS = 60_000L
        /** It took so long to test again after the proxy connection test failed (the assistant may not have gotten up yet). */
        private const val RELAY_RETRY_MS = 15_000L

        /** The one in the process (given by the App). */
        fun of(context: Context): WebDevtools = WebOwner.of(context).webDevtools
    }
}
