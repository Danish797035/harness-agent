package com.meenai.mina.cap.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import com.meenai.mina.core.cap.web.WebUrlPolicy
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewAssetLoader
import com.meenai.mina.takeover.HandoverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.meenai.mina.core.workspace.WorkspaceFileGate
import com.meenai.mina.core.net.NetProxy
import java.io.File
import com.meenai.mina.core.workspace.SandboxTree

/**
 * A built-in browser for the job: its own backend web page display, a pool of tags, web ports and an
 * isolated script engine. One set for each task (in parallel, each current label is hung on its own
 * display and rendered as usual). Build and close on the main thread.
 */
class WebStack private constructor(
    private val context: Context,
    val taskId: String,
    val host: WebHost,
    val pool: WebTabPool,
    workspace: Workspace?,
    /** Hand it to it when a page is left after the task is completed; when it is null, the page cannot be left (web_keep_open is reported as unavailable). */
    private val keeper: PageKeeper?,
) {
    /**
     * The workspace of this conversation: [dir] is the host directory (work/<conversation>, it will be created if
     * it does not exist, and the sandbox is not required to be installed); downloading and writing, and
     * uploading and reading from it all go through [gate] (open once, verify by handle).
     */
    class Workspace(val dir: File, val gate: WorkspaceFileGate, val tree: SandboxTree)

    // The download is performed in the background; the task is canceled when the task is completed, and
    // the unfinished mark is canceled and half of the file is deleted.
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Download and upload; empty when there is no workspace (both are reported as unavailable). */
    val files: WebFiles? = workspace?.let { w ->
        val downloads = WebDownloads(w.dir, w.gate, w.tree, NetProxy.shared.client(), cookies = { url -> WebTab.cookieManager().getCookie(url) }, scope = transfers)
        WebFiles(context, w.dir, downloads, w.gate, transfers)
    }

    val screen: WebScreen = WebScreen(pool, taskId, host, SystemClock::uptimeMillis, files, keptNow = keeper?.let { k -> { k.page.value } })

    /** The page left behind is recorded in whose name it is (conversation, running records, original words); take it when the task ends. Leave no page if not given. */
    @Volatile var keepOrigin: (() -> KeptOrigin)? = null

    init {
        files?.let { f -> pool.downloadHandler = { tab, url, ua, cd, mime -> onDownload(f, tab, url, ua, cd, mime) } }
    }

    // Download triggered by the page: http(s) is fetched directly, blob: is read by the page script,
    // data: is decoded directly; others (such as intent:) are ignored. The file is only saved, not
    // opened. In all three methods, the name given on the page link is first asked (<a download>): the
    // download monitor only contains the name in the response header, blob:/data: does not even have the
    // response header.
    private fun onDownload(f: WebFiles, tab: WebTab, url: String, ua: String, cd: String?, mime: String?) {
        val d = f.downloads
        val pageName: suspend () -> String? = { screen.downloadName(tab, url) }
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                val referer = tab.view.url
                // When I can't connect due to TLS, I use the browser's network stack to retrieve the
                // page: only the address that has the same origin as the current page is given.
                val viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = if (!sameOrigin(url, referer)) null else { max ->
                    (screen.readBlob(tab, url, max) as? PageScript.Blob.Ok)?.let { it.bytes to it.mime }
                }
                d.withPageName(pageName) { name -> d.start(url, ua, cd, mime, referer, name, viaPage) }
            }
            url.startsWith("blob:", true) -> d.withPageName(pageName) { name ->
                d.startFrom(url, cd, mime, name) { max ->
                    when (val b = screen.readBlob(tab, url, max)) {
                        is PageScript.Blob.Ok -> b.bytes to b.mime
                        is PageScript.Blob.Failed -> throw WebDownloads.BlobRefused(b.error)
                    }
                }
            }
            url.startsWith("data:", true) -> d.withPageName(pageName) { name -> d.dataUrl(url, cd, name) }
            else -> Log.i(TAG, "download with an unsupported address ignored")
        }
    }

    /** The two URLs have the same origin (the protocol, host, and port are the same); if one of them is missing, it is false. */
    private fun sameOrigin(a: String, b: String?): Boolean {
        fun origin(u: String?) = u?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
        val x = origin(a) ?: return false
        return x == origin(b)
    }

    /** The label being handed to the user (taking over); null if not. */
    @Volatile private var handedTab: WebTab? = null

    /** The page is now in the user's hands (under control). */
    val handedOver: Boolean get() = handedTab != null

    /** Does this task have a current tag (the current tag is visible on the web page display); it does not exist when the web page is only read in the background temporary tag. */
    val hasVisibleTab: Boolean get() = pool.current(taskId) != null

    /** The current label is borrowed from the page that the user is looking at (see [borrow]): it is not on the web page display, and the picture-in-picture does not display the picture. */
    val onBorrowedTab: Boolean get() = pool.current(taskId)?.let { pool.isExternal(it.id) } == true

    // ---------- Borrowed Tags ----------

    /**
     * The tag [tab] that the user is looking at on the browser page is borrowed and used as the current
     * tag of this task (see [WebTabPool.attachExternal]); life and death still belong to the original
     * owner. If it cannot be borrowed (closed or crashed), it returns false. main thread.
     */
    fun borrow(tab: WebTab): Boolean = pool.attachExternal(taskId, tab).also { if (it) Log.i(TAG, "task $taskId borrowed tab ${tab.id}") }

    /** Return the borrowed tag [tab](the task is over and the user will no longer give it); it is not the borrowed one that does not move. main thread. */
    fun giveBack(tab: WebTab) {
        if (pool.detachExternal(tab.id)) Log.i(TAG, "task $taskId returned tab ${tab.id}")
    }

    /**
     * The borrowed label is replaced by [next] (the user's page opens or closes the pop-up window, and
     * the visible one becomes another): the original one is returned and the new one is borrowed; even
     * if the original one is the current label, the new one is also. main thread.
     */
    fun swapBorrowed(prev: WebTab, next: WebTab) {
        val wasCurrent = pool.current(taskId) === prev
        giveBack(prev)
        if (wasCurrent || pool.current(taskId) == null) borrow(next)
    }

    /**
     * The user closes the browser page where the borrowed page is located, and the label is handed over
     * to this task ([adopt]): return the borrowed copy, accept the same WebView in the pool as a normal
     * label, hang it on the web page display and run as usual, and the page status (scrolling, form,
     * single-page application status) is not lost; when the task ends, it will be closed with other
     * tags. If it cannot be collected (closed or crashed), it returns false and the caller will destroy
     * it himself. main thread.
     */
    fun adopt(tab: WebTab): Boolean {
        val wasCurrent = pool.current(taskId) === tab
        giveBack(tab)
        return pool.adopt(taskId, tab, current = wasCurrent).also { if (it) Log.i(TAG, "task $taskId adopted tab ${tab.id}") }
    }

    /**
     * Takeover: Lock the current tab and bring it to the takeover interface on the home screen. Returns
     * false when there is no current label or the interface cannot be opened. [actions] hands the button
     * on the interface to the task (processed, I finished it myself, canceled, put it aside for now);
     * [currentKey] is the waiting number of the task at the moment.
     */
    fun showForTakeover(reason: String, message: String?, waitKey: Int, actions: (WebHandover.Action) -> Unit, currentKey: () -> Int?): Boolean {
        val tab = pool.current(taskId) ?: return false
        pool.lock(tab.id)
        // The borrowed tag is already in front of the user (browser page): it will not be moved, it will
        // be locked until the user has processed it and handed it back.
        val shown = if (pool.isExternal(tab.id)) !tab.destroyed
            else WebHandover.show(context, tab, host, reason, message, waitKey, taskId, actions, currentKey)
        if (shown) handedTab = tab else pool.unlock(tab.id)
        // Human-machine verification or access interception really blocks the task (it needs to be
        // handed over to the user): record the website where the task is located as having encountered
        // the interception
        if (shown && reason in SiteRegistry.CHALLENGE_KINDS) {
            tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host)?.let { WebSites.registry(context).challenged(it, reason) }
        }
        return shown
    }

    /** Hand back: End the takeover interface, put the label back into the background and unlock it; when [loggedIn], the page can see that you have logged in and the website will be recorded as logged in. Not in takeover is null. */
    fun returnFromTakeover(loggedIn: Boolean): HandoverActivity? {
        val tab = handedTab ?: return null
        handedTab = null
        if (pool.isExternal(tab.id)) {
            pool.unlock(tab.id)
            if (loggedIn && !tab.destroyed) LoginProbe.probe(tab) { host, state -> if (state == LoginState.SignedIn) WebSites.registry(context).markLoggedIn(host) }
            return HandoverActivity(borrowedTouches?.invoke(tab) ?: 0, tab.view.title?.takeIf { it.isNotBlank() })
        }
        val r = WebHandover.end(loggedIn, WebSites.registry(context), tab)
        pool.unlock(tab.id)
        return r
    }

    /** How many times the user touched the borrowed tag (the result will be recorded when the tag is returned); it will be given by the lending party. */
    @Volatile var borrowedTouches: ((WebTab) -> Int)? = null

    /**
     * End of the task: Take it back while it is still being taken over, and then turn off the label and
     * display of this task. When the model uses web_keep_open to mark a page, that page and the display
     * are handed over to [keeper] and left in the background to continue running (the display will not
     * be turned off); if it cannot be handed over (the page has been closed or collapsed), it will be
     * closed as usual.
     */
    fun close() {
        runCatching { returnFromTakeover(loggedIn = false) }.onFailure { Log.w(TAG, "takeover not returned cleanly", it) }
        // First take out the page you want to keep from the pool. When other tabs are closed, other
        // pages will not be hung on the display and pushed out.
        val kept = runCatching { pool.closeKeeping(taskId, screen.keepMark?.tabId) }.onFailure { Log.w(TAG, "kept page not released", it) }.getOrNull()
        runCatching { pool.close() }
        val handed = kept != null && runCatching { handOff(kept) }.onFailure { Log.w(TAG, "kept page not handed off", it) }.getOrDefault(false)
        if (kept != null && !handed) runCatching { kept.destroy() }
        if (!handed) runCatching { host.close() }
        runCatching { files?.close() }
        transfers.cancel()
    }

    /** Give the remaining page to [keeper]; accept it and return true. */
    private fun handOff(tab: WebTab): Boolean {
        val k = keeper ?: return false
        val origin = keepOrigin?.invoke() ?: return false
        return k.keep(tab, host, origin) != null
    }

    companion object {
        private const val TAG = "MinaWebStack"

        /** Build a set; it will be thrown when the system WebView is unavailable and cannot be built due to other reasons, and the caller will only give the mobile interface. Called by the main thread. */
        fun create(
            context: Context, taskId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            assetLoaders: List<WebViewAssetLoader> = emptyList(),
            workspace: Workspace? = null,
            keeper: PageKeeper? = WebOwner.of(context).pageKeeper,
        ): WebStack {
            val host = WebHost(context, widthPx, heightPx, densityDpi)
            return try {
                val pool = WebTabPool(context, host, assetLoaders = assetLoaders, registry = WebSites.registry(context))
                WebStack(context.applicationContext, taskId, host, pool, workspace, keeper)
            } catch (e: Exception) {
                host.close()
                throw e
            }
        }
    }
}
