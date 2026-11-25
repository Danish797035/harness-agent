package com.meenai.mina.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meenai.mina.core.cap.web.KeptPageClose
import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The "webpage left after the task ends" in the process: When the task ends ([WebStack.close]), the page
 * marked by the model with web_keep_open is taken out from the tag pool of the task, and handed over
 * here together with the background webpage display it is hung on - the page is still hung on the
 * display during the task, and is visible and runs as usual during the task, without any other tricks.
 * The global maximum is one page ([KeptPageSlot]),
 * [KEEP_OPEN_HOURS][com.meenai.mina.core.cap.web.KEEP_OPEN_HOURS]. It will be automatically closed after
 * hours; it will also be closed when the system requires the release of memory due to tight memory, the
 * rendering process crashes or is recovered by the system, the user closes it, or leaves another page.
 * When closing, destroy the label, release the display, write the cookie to disk, and hand it to
 * [onClosed] (note the reason in the source dialogue round). The remaining page is not in the tag pool
 * of any task: subsequent tasks cannot be listed and cannot be closed; it is counted in the total number
 * of tags shared by each task (see [TabBudget]), but will not be closed by the vacating position. The
 * process is supported by the foreground notification of the task service (see TaskService). Everything
 * that moves the WebView is in the main thread; [close] can be adjusted in any thread.
 */
class PageKeeper(
    private val context: Context,
    private val budget: TabBudget,
    clock: () -> Long = System::currentTimeMillis,
) : TabBudget.Member {
    /** The label that holds the page and its hanging display. */
    class Held(val tab: WebTab, val host: WebHost)

    private val main = Handler(Looper.getMainLooper())

    /** After closing a page (it may also be adjusted outside the main thread): record the reason during the source dialogue round. Connected by App. */
    @Volatile var onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> }

    private val slot: KeptPageSlot<Held> = KeptPageSlot(clock, release = ::release, onClosed = { p, r ->
        Log.i(TAG, "kept page ${p.id} closed: ${r.wire}")
        main.removeCallbacks(expiry)
        onClosed(p, r)
    })

    /** The page that remains at this moment; is null. */
    val page: StateFlow<KeptPage?> get() = slot.page

    private val expiry: Runnable = object : Runnable {
        override fun run() {
            if (slot.expireIfDue()) return
            // This timer will stop when the system is in deep sleep: check the wall clock every once in
            // a while, and turn it off after a while after waking up.
            slot.remainingMs()?.let { main.postDelayed(this, it.coerceIn(1_000L, CHECK_MS)) }
        }
    }

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) close(KeptPageClose.Memory)
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /** What happens on the remaining page: just crash; do not accept pop-up windows, downloads, or jump apps (there is no task to watch it). */
    private inner class Listener(val id: Long) : WebTab.Listener {
        override fun onCrashed(tab: WebTab) {
            // Close outside WebView's own callback
            main.post { slot.closeIf(id, if (tab.reclaimed) KeptPageClose.Memory else KeptPageClose.Crashed) }
        }
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /**
     * Receive [tab] (has been taken out of the task's label pool) and its display [host], leave it in
     * the background and continue running; close the other page left at this moment. If the label is
     * closed, collapsed or cannot be closed when it is displayed as closed, null will be returned and
     * the caller will destroy it himself. main thread.
     */
    fun keep(tab: WebTab, host: WebHost, origin: KeptOrigin): KeptPage? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PageKeeper.keep must run on the main thread" }
        if (tab.destroyed || tab.crashed || host.closed) return null
        val url = tab.view.url
        val site = url?.let(WebUrlPolicy::host) ?: url.orEmpty()
        // Hang back to its own display (it may not be the one it was hanging on at the end of the task);
        // the page still thinks it is visible
        host.attach(tab)
        runCatching { WebTab.cookieManager().flush() }
        val p = slot.keep(Held(tab, host), origin, site, url, tab.view.title)
        tab.listener = Listener(p.id)
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, (p.closesAt - p.keptAt).coerceIn(1_000L, CHECK_MS))
        Log.i(TAG, "kept page ${p.id} on $site from conversation ${p.conversationId}")
        return p
    }

    /** Close the remaining page (the user clicked close, the button on the notification, memory is tight, etc.); if there is no remaining page, return false. Any thread. */
    fun close(reason: KeptPageClose): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (slot.page.value == null) return false
            main.post { slot.close(reason) }
            return true
        }
        return slot.close(reason)
    }

    /** How long is left to automatically shut down (milliseconds); if not left, it is null. */
    fun remainingMs(): Long? = slot.remainingMs()

    /** The label left (for testing and real machine acceptance, see if the page is still running); if not, it is null. */
    fun keptTab(): WebTab? = slot.handle()?.tab

    /** The remaining pages to be recorded during the round of running [runId]: (site, reason for shutdown), see [KeptPageSlot.recordFor]. */
    fun recordFor(runId: String?): Pair<String, String?>? = slot.recordFor(runId)

    /** The real-time picture of the remaining page is taken every [FRAME_MS] (the same source as the picture-in-picture during the task: the latest frame displayed on that page); null is sent when there is no picture. */
    fun frames(): Flow<Bitmap?> = flow {
        while (true) {
            emit(slot.handle()?.host?.takeIf { !it.closed }?.latestFrame())
            delay(FRAME_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ---------- Global upper limit ----------

    override fun tabCount(): Int = if (slot.page.value != null) 1 else 0

    /** The remaining page is closed without making room for it: the user wants it to remain open. */
    override fun oldestSpare(except: String?): Pair<String, Long>? = null

    override fun evictForBudget(tabId: String) = Unit

    private fun release(h: Held) {
        val run = Runnable {
            runCatching { WebTab.cookieManager().flush() }
            runCatching { h.tab.destroy() }
            runCatching { h.host.close() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private companion object {
        const val TAG = "MinaPageKeeper"
        /** How often do you press the wall clock to check whether it's time? */
        const val CHECK_MS = 60_000L
        const val FRAME_MS = 500L
    }
}
