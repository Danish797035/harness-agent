package com.meenai.mina.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewAssetLoader
import com.meenai.mina.core.cap.web.TabInfo
import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import com.meenai.mina.core.cap.web.ExternalLink as Link
import com.meenai.mina.cap.web.cdp.WebDevtools

/** What happens in the tag pool: newly opened, closed (reason), crashed, blocked jump app link, reloaded when switching back after memory recycling. */
sealed interface PoolEvent {
    data class NewTab(val tabId: String, val taskId: String, val fromPopup: Boolean) : PoolEvent
    /**
     * [reason]: closed automatically closes, limit exceeds the upper limit and closes the longest unused
     * one, task_end ends the task, and popup_other_site pops up the window to go to another website; the
     * borrowed tag ([WebTabPool.attachExternal]) is returned when returned, and the tag left in the
     * background at the end of the task ([WebTabPool.release]) is kept (these two tags themselves are
     * not closed).
     */
    data class Closed(val tabId: String, val reason: String) : PoolEvent
    data class Crashed(val tabId: String) : PoolEvent
    data class ExternalLink(val tabId: String, val link: Link) : PoolEvent
    data class Reloaded(val tabId: String) : PoolEvent
}

/**
 * Web page tag pool: each task can have at most [perTask] tags; all tasks combined can have at most the
 * upper limit of [budget] (shared across tasks); when it exceeds the limit, the task that has been
 * unused for the longest time will be turned off. If there is no other task to turn off, the task that
 * has been unused for the longest time will be turned off. At the same time, only the recently opened or
 * switched tab is hung on [host] for drawing, and the rest run as usual in the background (what the page
 * sees is the background tab). The new window that pops up on the page will only open a new tab in the
 * pool when the user clicks (with a gesture), and it can only be the same website; the link to the App
 * will not jump, and [PoolEvent.ExternalLink] will be sent. The rendering process crashes: destroy the
 * tag, send [PoolEvent.Crashed], and open a new tag next time you open the URL. When the system memory
 * is tight, turn off the background tab and just write down the address, and reload it when switching
 * back; after the page is loaded, the task ends, and the app enters the background, the cookie is
 * written to the disk, and the login is not lost. The borrowed label ([attachExternal], the one the user
 * is looking at on the browser page): is used as the current label of the task, but is not managed by
 * the pool - it is not linked to [host] (it is in the user interface), If it is not counted as the upper
 * limit, it will not be turned off by space vacation or memory recycling, and the pool will not accept
 * the tasks on it (downloads, pop-up windows, and login detection are managed by its original owner); it
 * will be turned off and returned at the end of the task ([detachExternal]) without being destroyed.
 *
 * The methods that move to WebView ([open], [switchTo], [close], [closeAll], [list], [onTrimMemory]) are
 * all called in the main thread; [current], [isLive], [crashedTab] can be adjusted in any thread.
 */
class WebTabPool(
    private val context: Context,
    private val host: WebHost,
    private val perTask: Int = 4,
    /** The upper limit of the total number of tags shared by each task; the default is the one in the process. */
    private val budget: TabBudget = WebOwner.of(context).tabBudget,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    private val registry: SiteRegistry? = null,
    /** The DevTools session entry for the tag in the pool; the default is the one in the process. */
    private val devtools: WebDevtools = WebDevtools.of(context),
) : WebTab.Listener, Closeable, TabBudget.Member {
    /**
     * [background]: Temporary background tag used for reading pages. It is not the current tag, does not
     * enter the tag list, and does not count as the upper limit. It will be closed after reading (see
     * [openBackground]). [external]: Borrowed tags (see [attachExternal]) are not counted as the upper
     * limit and will only be returned when closing.
     */
    private class Entry(
        val id: String, val taskId: String, var tab: WebTab?, var url: String?, var title: String, var lastUsed: Long,
        val background: Boolean = false, val external: Boolean = false,
    ) {
        /** Common tags that count towards per-task and global caps and can be turned off by freeing up space. */
        val counted: Boolean get() = !background && !external
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val currentByTask = HashMap<String, String>()
    private val crashedByTask = HashMap<String, String>()
    /** The label being handed over to the user for processing (under takeover): the task cannot operate it, and the pool does not close it or recycle it. */
    private val locked = HashSet<String>()
    private var counter = 0
    private val main = Handler(Looper.getMainLooper())
    /** Login status detection of each label waiting to be run (main thread active). */
    private val loginProbes = HashMap<String, Runnable>()

    private val _events = MutableSharedFlow<PoolEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PoolEvent> = _events.asSharedFlow()

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = this@WebTabPool.onTrimMemory(level)
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = this@WebTabPool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /** Open a new tab in the task and switch to it; start loading when [url] is not empty (not waiting until loading is completed). */
    fun open(taskId: String, url: String?): WebTab {
        checkMain()
        makeRoom(taskId, keep = null)
        val tab = create(taskId)
        url?.let { u ->
            val refused = WebUrlPolicy.checkNavigable(u)
            if (refused == null) tab.view.loadUrl(u) else Log.w(TAG, "refused to open a non-web address: $refused")
        }
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return tab
    }

    /**
     * Open a temporary background tag for reading pages: it does not cut through, does not hang on the
     * host (no one formats it, press [width] × [height] once, the page has a viewport), does not appear
     * in [list], does not count the upper limit of each task and global tags (there are several limits
     * at the same time by the caller), and will not be turned off by space allocation or memory
     * recycling. Adjust [close] after use.
     */
    fun openBackground(taskId: String, width: Int, height: Int): WebTab {
        checkMain()
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        synchronized(this) { entries[id] = Entry(id, taskId, tab, null, "", budget.tick(), background = true) }
        val w = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        tab.view.measure(w, h)
        tab.view.layout(0, 0, width, height)
        return tab
    }

    @Synchronized
    fun current(taskId: String): WebTab? = currentByTask[taskId]?.let { entries[it]?.tab }

    /**
     * Lend a tag that is not managed by the pool (the one the user is looking at on the browser page) to
     * task [taskId] as its current tag: then web tools read it and operate on it, just like tags in the
     * pool. It is not linked to [host], does not count as the upper limit, and does not take over its
     * monitoring; if the same tag has been borrowed, it will only be cut over. Those that have been
     * closed, crashed, or whose numbers collide with the tags in the pool cannot be borrowed and return
     * false. main thread.
     */
    fun attachExternal(taskId: String, tab: WebTab): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed) return false
        val entry = synchronized(this) {
            val existing = entries[tab.id]
            if (existing != null && (!existing.external || existing.tab !== tab || existing.taskId != taskId)) return false
            existing ?: Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), 0, external = true).also {
                entries[tab.id] = it
                crashedByTask.remove(taskId)
            }
        }
        makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * Return the borrowed label [tabId]: Remove it from the pool without destroying or moving its view;
     * when the current label is replaced with another recently used by this task (if not, there is no
     * current label). Returns false if not borrowed. main thread.
     */
    fun detachExternal(tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.external != true }) return false
        remove(tabId, PoolEvent.Closed(tabId, "returned"), force = true)
        return true
    }

    /**
     * Accept a tag [tab] that was not originally managed by the pool. When the task [taskId] is a normal
     * tag (the browser page that lent the page is closed, the page is handed over to the task and used
     * again): take over the things on it, calculate the upper limit, and can be vacated. When the task
     * ends, it will be closed with the others. [current] or when the task does not have a current label,
     * switch to it and hang it on the host, otherwise it will be in the background. If it is closed,
     * crashed, or the number is already in the pool and cannot be collected, false will be returned.
     * main thread.
     */
    fun adopt(taskId: String, tab: WebTab, current: Boolean): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed || synchronized(this) { entries.containsKey(tab.id) }) return false
        makeRoom(taskId, keep = null)
        (tab.view.parent as? android.view.ViewGroup)?.removeView(tab.view)
        tab.ownerTaskId = taskId
        tab.listener = this
        val entry = Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), budget.tick())
        val makeIt = synchronized(this) {
            entries[tab.id] = entry
            current || currentByTask[taskId] == null
        }
        if (makeIt) makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * At the end of the task, hand over the tag [tabId] to be left (see [PageKeeper]): remove it from
     * the pool, do not destroy it, do not remove it from the host, and do not hang other tags (when it
     * is the current tag, the task will have no current tag); after that, it will no longer take over
     * the things on it, it will not be counted as the upper limit, and it will not be closed with the
     * task. Send Closed(kept). If the borrowed, temporary background, memory recycled, crashed, or
     * non-existent items cannot be handed over, null will be returned. main thread.
     */
    fun release(tabId: String): WebTab? {
        checkMain()
        val tab = synchronized(this) {
            val e = entries[tabId]?.takeIf { it.counted } ?: return null
            val t = e.tab?.takeIf { !it.destroyed && !it.crashed } ?: return null
            entries.remove(tabId)
            locked -= tabId
            if (currentByTask[e.taskId] == tabId) currentByTask.remove(e.taskId)
            t
        }
        loginProbes.remove(tabId)?.let(main::removeCallbacks)
        tab.listener = null
        emit(PoolEvent.Closed(tabId, "kept"))
        return tab
    }

    /** [tabId] is a borrowed label (see [attachExternal]). */
    @Synchronized
    fun isExternal(tabId: String): Boolean = entries[tabId]?.external == true

    @Synchronized
    fun isLive(tabId: String): Boolean = entries[tabId]?.tab != null

    /** Takeover starts: Lock tag until [unlock]. */
    @Synchronized
    fun lock(tabId: String) {
        locked += tabId
    }

    @Synchronized
    fun unlock(tabId: String) {
        locked -= tabId
    }

    @Synchronized
    fun isLocked(tabId: String): Boolean = tabId in locked

    /** The tab that this task recently crashed and has not opened a new tab since then; null if not. */
    @Synchronized
    fun crashedTab(taskId: String): String? = crashedByTask[taskId]

    /** Switch to a label of this task; if it is not of this task or does not exist, it will be null. Tags that have been reclaimed by memory are reloaded concurrently [PoolEvent.Reloaded]. */
    fun switchTo(taskId: String, tabId: String): WebTab? {
        checkMain()
        val entry = synchronized(this) { entries[tabId]?.takeIf { it.taskId == taskId } } ?: return null
        var reloaded = false
        val tab = entry.tab ?: newTab(entry.id, taskId).also { t ->
            synchronized(this) { entry.tab = t }
            entry.url?.let { t.view.loadUrl(it) }
            reloaded = true
        }
        makeCurrent(entry)
        if (reloaded) emit(PoolEvent.Reloaded(tabId))
        return tab
    }

    /** Close a tab (no matter which task). To close the task itself, use close(taskId, tabId). */
    fun close(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "closed"))

    /** The task closes its own label; if it is not for this task, does not exist, or is being handed over to the user for processing, it returns false and does not close. */
    fun close(taskId: String, tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.taskId != taskId || tabId in locked }) return false
        close(tabId)
        return true
    }

    /** Exiting a site: Expires its cookies, clears its web storage, and removes it from the site registry (best effort, see [WebLogin]). Returns the number of expired cookies. */
    fun clearSiteData(host: String): Int {
        checkMain()
        return WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logout(host)
    }

    /** Exit all and clear web data. The open tab is not closed, but it is no longer logged in. */
    fun clearAll() {
        checkMain()
        val views = synchronized(this) { entries.values.mapNotNull { it.tab?.view } }
        WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logoutAll(views, WebViewDatabase.getInstance(context))
    }

    /** The task ends: close all the tabs it opened (return the borrowed ones), and write the cookies to disk (the login status is not lost when the tabs are closed). */
    fun closeAll(taskId: String) {
        checkMain()
        val ids = synchronized(this) { entries.values.filter { it.taskId == taskId }.map { it.id } }
        ids.forEach { remove(it, PoolEvent.Closed(it, if (isExternal(it)) "returned" else "task_end"), force = isExternal(it)) }
        synchronized(this) { crashedByTask.remove(taskId) }
        flushCookies()
    }

    /**
     * Task [taskId] is over. If you want to keep the page [keep]: hand it over first ([release]), and
     * then close the other tabs of this task ([closeAll]). The handed-over tag is taken over by the
     * caller; if [keep] is null or cannot be handed over, it is null (all closed as usual). main thread.
     */
    fun closeKeeping(taskId: String, keep: String?): WebTab? {
        checkMain()
        val tab = keep?.takeIf { synchronized(this) { entries[it]?.taskId == taskId } }?.let(::release)
        closeAll(taskId)
        return tab
    }

    /** Tags for this task, in order of opening. */
    fun list(taskId: String): List<TabInfo> {
        checkMain()
        val (rows, cur) = synchronized(this) { entries.values.filter { it.taskId == taskId && !it.background } to currentByTask[taskId] }
        return rows.map { e ->
            val live = e.tab
            TabInfo(e.id, live?.view?.url ?: e.url.orEmpty(), live?.view?.title ?: e.title, e.id == cur)
        }
    }

    /**
     * When the memory is tight (RUNNING_LOW and above, or has been asked to be released in the
     * background), turn off the non-current tags of each task and only remember the address; when the
     * App enters the background (UI_HIDDEN), the cookie is written to disk.
     */
    fun onTrimMemory(level: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onTrimMemory(level) }
            return
        }
        @Suppress("DEPRECATION")
        val pressure = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushCookies()
        if (!pressure) return
        val victims = synchronized(this) {
            entries.values.filter { it.tab != null && it.counted && currentByTask[it.taskId] != it.id && it.id !in locked }
        }
        for (e in victims) {
            val tab = e.tab ?: continue
            e.url = tab.view.url ?: e.url
            e.title = tab.view.title ?: e.title
            synchronized(this) { e.tab = null }
            tab.destroy()
            Log.i(TAG, "tab ${e.id} released under memory pressure (level $level)")
        }
    }

    /** Close all task labels and no longer receive system memory callbacks. */
    override fun close() {
        checkMain()
        val tasks = synchronized(this) { entries.values.map { it.taskId }.toSet() }
        tasks.forEach(::closeAll)
        budget.leave(this)
        context.applicationContext.unregisterComponentCallbacks(memory)
    }

    // ---------- Global upper limit ----------

    @Synchronized
    override fun tabCount(): Int = entries.values.count { it.counted }

    @Synchronized
    override fun oldestSpare(except: String?): Pair<String, Long>? =
        entries.values.filter { it.id != except && it.counted && it.id !in locked }.minByOrNull { it.lastUsed }?.let { it.id to it.lastUsed }

    override fun evictForBudget(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "limit"))

    // ---------- Things on the label ----------

    override fun onPageFinished(tab: WebTab) {
        val url = tab.view.url
        synchronized(this) {
            entries[tab.id]?.let {
                it.url = url
                it.title = tab.view.title.orEmpty()
            }
        }
        url?.let { u -> WebUrlPolicy.host(u)?.takeIf { u.startsWith("http") }?.let { registry?.visited(it) } }
        flushCookies()
        scheduleLoginProbe(tab, 0)
    }

    /**
     * Most page scripts draw the account area only after loading is complete: wait until the page stops
     * and then check the login status, and the registration form will follow the real status of the
     * page. If it loads (jumps) again, restart the timer; only look at the tags that are being used by
     * the task and are hung on the host; if the address is still changing or is still loading, wait
     * again, at most a few times. main thread.
     */
    private fun scheduleLoginProbe(tab: WebTab, attempt: Int) {
        val r = registry ?: return
        loginProbes.remove(tab.id)?.let(main::removeCallbacks)
        val urlThen = tab.view.url
        val run = Runnable {
            loginProbes.remove(tab.id)
            if (tab.destroyed) return@Runnable
            val current = synchronized(this) { currentByTask[tab.ownerTaskId] == tab.id }
            if (!current || !tab.view.isAttachedToWindow) return@Runnable
            if (tab.loading || tab.view.url != urlThen) {
                if (attempt < LOGIN_PROBE_RETRIES) scheduleLoginProbe(tab, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(tab) { host, state -> r.loginObserved(host, state) }
        }
        loginProbes[tab.id] = run
        main.postDelayed(run, LOGIN_PROBE_DELAY_MS)
    }

    override fun onCrashed(tab: WebTab) {
        // Destroy outside the callback. Do not call destroy in WebView's own callback stack.
        main.post {
            // The temporary background label collapsed. The label that does not count as a task
            // collapsed: report it yourself when reading the page.
            synchronized(this) { if (entries[tab.id]?.background != true) crashedByTask[tab.ownerTaskId] = tab.id }
            remove(tab.id, PoolEvent.Crashed(tab.id), force = true)
        }
    }

    /** The download triggered by the page is handed to it (the task connects it to the workspace); it is ignored when it is empty. Main thread callback: (label, URL, UA, Content-Disposition, type). */
    var downloadHandler: ((WebTab, String, String, String?, String?) -> Unit)? = null

    override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        val h = downloadHandler
        if (h == null) Log.i(TAG, "download ignored on tab ${tab.id}") else h(tab, url, userAgent, contentDisposition, mime)
    }

    override fun onExternalLink(tab: WebTab, link: Link) {
        emit(PoolEvent.ExternalLink(tab.id, link))
    }

    override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // Pop-up windows without user operation will not be opened.
        if (!isUserGesture) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        makeRoom(tab.ownerTaskId, keep = tab.id)
        val popup = create(tab.ownerTaskId)
        popup.popupOpener = tab.view.url ?: ""
        transport.webView = popup.view
        resultMsg.sendToTarget()
        emit(PoolEvent.NewTab(popup.id, tab.ownerTaskId, fromPopup = true))
        return true
    }

    override fun onPopupRefused(tab: WebTab, url: String) {
        Log.i(TAG, "popup ${tab.id} refused: other site ${url.take(80)}")
        main.post { remove(tab.id, PoolEvent.Closed(tab.id, "popup_other_site")) }
    }

    // ---------- Internal ----------

    private fun create(taskId: String): WebTab {
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        val entry = Entry(id, taskId, tab, null, "", 0)
        synchronized(this) {
            entries[id] = entry
            crashedByTask.remove(taskId)
        }
        makeCurrent(entry)
        return tab
    }

    private fun newTab(id: String, taskId: String) = WebTab(context, id, taskId, assetLoaders, devtools).also { it.listener = this }

    private fun makeCurrent(entry: Entry) {
        synchronized(this) {
            currentByTask[entry.taskId] = entry.id
            entry.lastUsed = budget.tick()
        }
        // The borrowed tag remains in the user interface; the one originally hanging on the host remains
        // (runs as usual in the background)
        if (!entry.external) entry.tab?.let { host.attach(it) }
    }

    /**
     * Make a place for this task: when this task is full, close the task that has been useless for the
     * longest time; when all the tasks are combined, close the task of this task first, and do not close
     * the task elsewhere for the longest time. [keep] has nothing to do with being locked (receiving),
     * but it still counts. If there is really nothing to close, just close it (it would rather exceed it
     * slightly than touch the page in the user's hand).
     */
    private fun makeRoom(taskId: String, keep: String?) {
        while (true) {
            val (own, perTaskFull) = synchronized(this) {
                val all = entries.values.filter { it.taskId == taskId && it.counted }
                all.filter { it.id != keep && it.id !in locked }.minByOrNull { it.lastUsed } to (all.size >= perTask)
            }
            when {
                perTaskFull && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() -> if (!budget.evictOldest(except = keep)) return
                else -> return
            }
        }
    }

    /** Close a tag; do not close a tag that is being taken over (locked), unless [force] (the rendering process is gone, return the borrowed tag). Borrowed items will only be taken away, not destroyed. */
    private fun remove(tabId: String, event: PoolEvent, force: Boolean = false) {
        checkMain()
        val (entry, next) = synchronized(this) {
            if (tabId in locked && !force) {
                Log.i(TAG, "tab $tabId is with the user, not closed")
                return
            }
            locked -= tabId
            val e = entries.remove(tabId) ?: return
            var nextEntry: Entry? = null
            if (currentByTask[e.taskId] == tabId) {
                nextEntry = entries.values.filter { it.taskId == e.taskId && !it.background }.maxByOrNull { it.lastUsed }
                if (nextEntry == null) currentByTask.remove(e.taskId) else currentByTask[e.taskId] = nextEntry.id
            }
            e to nextEntry
        }
        if (!entry.external) entry.tab?.let {
            host.detach(it.view)
            it.destroy()
        }
        next?.let { n ->
            // If the next current label has been reclaimed by memory, it will be reloaded when the task
            // switches to it; the borrowed one will remain with the user.
            if (!n.external) n.tab?.let { host.attach(it) }
        }
        emit(event)
    }

    private fun flushCookies() {
        runCatching { WebTab.cookieManager().flush() }
    }

    private fun emit(e: PoolEvent) {
        _events.tryEmit(e)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebTabPool must be used on the main thread" }

    private companion object {
        const val TAG = "MinaWebTabPool"
        const val LOGIN_PROBE_DELAY_MS = 1_500L
        const val LOGIN_PROBE_RETRIES = 3
    }
}
