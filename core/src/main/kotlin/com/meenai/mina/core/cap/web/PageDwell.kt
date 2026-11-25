package com.meenai.mina.core.cap.web

import com.meenai.mina.core.net.UrlHost
import com.meenai.mina.core.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.meenai.mina.core.uistate.DwellInterrupted
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PageLimitReached
import com.meenai.mina.core.uistate.PlacePace

/**
 * Press [PagePacing] to stay in a task: looking at the address of the page ([seen]), you recognize a new
 * page. When scrolling down, the page itself pulls in a batch of content ([loadedMore]), which is also
 * counted as one page; before the page change action, [before] makes up for the time that the page has
 * not been paused. After how long, it is handed over to [onWait] (recorded as the wait for this call).
 * [pace] gives site knowledge on the browsing speed of this website by host name. When [idle] is given
 * and the action is not performed according to the screen coordinates (the port is handed over), the
 * page is gently scrolled a few times according to its arrangement during the stay
 * ([WebPort.idleScroll]), and the page is handed over to [onIdleScroll] after a few scrolls. The pages
 * from this task on the same website have reached the upper limit. [before] will no longer wait and
 * throw [PageLimitReached]: the caller will not perform this action; the upper limit is only browsing:
 * the external actions being done (sending, submitting, between [outwardStart] and [outwardEnd]) will
 * not be blocked. This task will no longer set an upper limit after the external actions are completed
 * on this website. The user interrupts during the pause ([waitOrSteer]), [before] no longer waits,
 * throws [DwellInterrupted]: The caller also does not perform this action; the part that is not paused
 * on this page is not considered paused, and it will be made up before the next page change.
 */
internal class PageDwell(
    private val pacing: PagePacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Called after stopping (the parameter is the number of milliseconds to stop). */
    var onWait: (Long) -> Unit = {}

    /** Called after gently scrolling the page while staying (the parameter is the number of scrolls). */
    var onIdleScroll: (Int) -> Unit = {}

    /** While staying, gently scroll through the page and then call it before taking action (revisit the appearance before the action to avoid counting scrolling as the effect of the action). */
    var afterIdle: suspend () -> Unit = {}

    private var lastUrl: String? = null

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** I saw the address of the page at the moment (the result of reading the screen and opening the web page): it is different from the last time I saw it (not counting the #), it is a new page. */
    @Synchronized
    fun seen(url: String?) {
        val u = url?.substringBefore('#')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (u == lastUrl) return
        lastUrl = u
        pacing.noteLoad(u, clock(), paceOf(u))
    }

    /** The address has not changed, and the page itself has pulled in a batch of content (scroll down to load more). */
    @Synchronized
    fun loadedMore() {
        lastUrl?.let { pacing.noteLoad(it, clock(), paceOf(it)) }
    }

    /** The user replied: The number of pages on each website will be recalculated. */
    fun userAnswered() = pacing.userAnswered()

    /** The website where the external action is being performed (registerable domain name); if not, null. */
    @Volatile private var outwardNow: String? = null

    /** Make an external action on [site] (website host name): this time it is not restricted by the page limit. */
    fun outwardStart(site: String) { outwardNow = siteKey(site) }

    /** When the external action is completed; [done] (made), this website will no longer have a page limit. */
    fun outwardEnd(site: String, done: Boolean) {
        outwardNow = null
        if (done) pacing.outwardDone(site)
    }

    /**
     * Before you want to change the page: If this page has not stopped enough, make up for it; [target]
     * is the URL to be opened (when opening the web page), not the website of this page at the moment.
     * Returns how long it was paused. When [port] is given (the action is not performed according to the
     * screen coordinates), you can gently scroll the page a few times while staying. When it comes to
     * [gate], first look at the upper limit of the number of pages, and then throw [PageLimitReached].
     */
    suspend fun before(target: String? = null, port: WebPort? = null, gate: Boolean = true): Long {
        val site = target ?: synchronized(this) { lastUrl } ?: return 0
        val p = paceOf(site)
        if (gate && siteKey(site) != outwardNow && pacing.capReached(site, p)) throw PageLimitReached(hostOf(site), pacing.pagesOn(site), pacing.capFor(p))
        val wait = pacing.waitBefore(site, clock())
        if (wait <= 0) return 0
        if (port != null && idle != null) idleWait(port, idle, wait) else pause(wait)
        pacing.served(site)
        onWait(wait)
        return wait
    }

    /** Stop [ms] milliseconds; stop when the user interrupts, throw [DwellInterrupted]. */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /**
     * Pause for [wait] milliseconds, during which time you will scroll slightly according to the
     * arrangement of [plan]; if the port says that it is not convenient to scroll at this time (pop-up
     * box, input box holding the focus, etc.), it will stop scrolling, and it is still enough. When the
     * user interrupts the pause by interrupting, the scrolling times are still reported, and the screen
     * in the model's hand is still marked as outdated.
     */
    private suspend fun idleWait(port: WebPort, plan: IdleScroll, wait: Long) {
        val start = clock()
        var done = 0
        suspend fun scrolled() { if (done > 0) { onIdleScroll(done); afterIdle() } }
        try {
            for (step in plan.plan(wait)) {
                val left = step.atMs - (clock() - start)
                if (left > 0) pause(left)
                val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                if (!ok) break
                done++
            }
            val rest = wait - (clock() - start)
            if (rest > 0) pause(rest)
        } catch (e: DwellInterrupted) {
            scrolled()
            throw e
        }
        scrolled()
    }

    private fun paceOf(site: String): PlacePace {
        val host = hostOf(site)
        return synchronized(paces) { paces.getOrPut(host) { pace(host) } }
    }

    private fun siteKey(site: String): String { val h = hostOf(site); return UrlHost.registrable(h) ?: h }

    private fun hostOf(site: String): String = UrlHost.of(if ("://" in site) site else "https://$site") ?: site

    /** Before scrolling down, write down the responses that the page has received; after scrolling, use [afterScroll]. If there are new batches of data, more will be loaded. */
    suspend fun scrollMark(port: WebPort): Set<String>? = if (!paced) null else try {
        port.captures(null, null, false).mapTo(HashSet()) { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun afterScroll(port: WebPort, mark: Set<String>?) {
        mark ?: return
        val fresh = try { port.captures(null, null, false) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        if (fresh.any { it.id !in mark && batch(it) }) loadedMore()
    }

    /** Responses like a batch of list data: successfully received, JSON or text, not too small (small responses such as statistics and heartbeats are not counted). */
    private fun batch(c: CapturedResponse): Boolean {
        if (c.state != CapturedResponse.State.DONE || c.status !in 200..299) return false
        val mime = c.mime?.lowercase().orEmpty()
        if ("json" !in mime && !mime.startsWith("text/")) return false
        return (c.size ?: 0) >= MORE_MIN_BYTES
    }

    private companion object {
        /** The response that counts as "another batch of content is coming" is at least this big. */
        const val MORE_MIN_BYTES = 2_048L
    }
}
