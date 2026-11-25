package com.meenai.mina.cap.web

import com.meenai.mina.core.cap.web.KEEP_OPEN_HOURS
import com.meenai.mina.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The external appearance of the page that is left in the background and continues to run after the task
 * is: which round of conversation ([conversationId], [runId]), the original words of that round [goal],
 * the site, address, title of the page, the time left and the time when it is automatically closed (wall
 * clock, milliseconds).
 */
data class KeptPage(
    val id: Long,
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val host: String,
    val url: String?,
    val title: String?,
    val keptAt: Long,
    val closesAt: Long,
)

/** Who is the task that left the page: dialogue, running record number, original words. */
data class KeptOrigin(val conversationId: String, val runId: String?, val goal: String)

/**
 * The pure logic of leaving pages (without touching WebView): the whole world is at most one page,
 * leaving the new one and closing the old one first ([KeptPageClose.Replaced]); when it comes to
 * [closesAt][KeptPage.closesAt], it is closed by [expireIfDue]; when it is closed, let go of what you
 * are holding ([release]) and report it to [onClosed] (write into the source dialogue round). [H] is
 * what really holds up the page (the label and its display). Remember the recently closed page and the
 * reason, and you can make up for it even if the task round has not yet been written into the dialogue
 * ([recordFor]). The time is given by the caller, and a fake clock is used in the test. Thread safe.
 */
class KeptPageSlot<H>(
    private val clock: () -> Long,
    private val release: (H) -> Unit,
    private val onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> },
    private val ttlMs: Long = KEEP_OPEN_HOURS * HOUR_MS,
) {
    private val _page = MutableStateFlow<KeptPage?>(null)
    /** The page that remains at this moment; is null. */
    val page: StateFlow<KeptPage?> = _page.asStateFlow()

    private var held: H? = null
    private var counter = 0L
    private var lastClosed: Pair<KeptPage, KeptPageClose>? = null

    /** Leave the page held by [handle]; close it first when a page is already left. Return to the newly left page. */
    fun keep(handle: H, origin: KeptOrigin, host: String, url: String?, title: String?): KeptPage {
        close(KeptPageClose.Replaced)
        val now = clock()
        val p = synchronized(this) {
            held = handle
            KeptPage(++counter, origin.conversationId, origin.runId, origin.goal, host, url, title, now, now + ttlMs).also { _page.value = it }
        }
        return p
    }

    /** Close the remaining page; return false if there is no remaining page. */
    fun close(reason: KeptPageClose): Boolean {
        val (p, h) = synchronized(this) {
            val p = _page.value ?: return false
            val h = held
            held = null
            _page.value = null
            lastClosed = p to reason
            p to h
        }
        h?.let { runCatching { release(it) } }
        onClosed(p, reason)
        return true
    }

    /** Only close it when the [id] page is still there (the callbacks for crashes and recycling will arrive late, so don't close the wrong page that is left later). */
    fun closeIf(id: Long, reason: KeptPageClose): Boolean = if (_page.value?.id == id) close(reason) else false

    /** When the time is up, turn it off ([KeptPageClose.Expired]); return true when it is turned off. */
    fun expireIfDue(): Boolean {
        val p = _page.value ?: return false
        return if (clock() >= p.closesAt) closeIf(p.id, KeptPageClose.Expired) else false
    }

    /** How long is left to automatically shut down (milliseconds, not less than 0); if not left, it is null. */
    fun remainingMs(): Long? = _page.value?.let { (it.closesAt - clock()).coerceAtLeast(0) }

    @Synchronized
    fun handle(): H? = held

    /**
     * The remaining pages to be recorded in the round of running [runId]: (site, reason for shutdown);
     * the reason is null when it is still retained, and it is null when there is no remaining page in
     * this round. The dialogue was only written in the round at the end of the mission. During this
     * period, the page may have been closed (collapsed or pushed out by a new one), but it can still be
     * seen here.
     */
    @Synchronized
    fun recordFor(runId: String?): Pair<String, String?>? {
        if (runId == null) return null
        _page.value?.takeIf { it.runId == runId }?.let { return it.host to null }
        return lastClosed?.takeIf { it.first.runId == runId }?.let { (p, r) -> p.host to r.wire }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}
