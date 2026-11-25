package com.meenai.mina.core.cap.web

import com.meenai.mina.core.net.UrlHost
import kotlin.random.Random
import com.meenai.mina.core.uistate.PlacePace

/**
 * The rhythm of reading from page to page on a web page (pure logic, without touching the clock and the
 * page): When people open a page, they always have to look at it for a while before turning to the next
 * page and clicking on the next item. Every time a new page of content comes ([noteLoad]: a new address
 * is opened, or the page pulls in a batch of content by itself when scrolling down), determine how long
 * the page should stay based on the recently viewed pages of the website; before changing pages (open
 * the same site URL, click, back, scroll down, press the button), ask [waitBefore]: Before the page has
 * stopped enough, make up for the remaining time.
 *
 * - - The first page in a website does not stop (when you first arrive at this website, the first action
 * is as usual);
 * - - Starting from the second page, the length of stay is randomly selected in [Limits.firstDwellMs].
 * The more you have viewed in [Limits.windowMs] recently, the upper and lower limits will be increased
 * by one ([Limits.perPageMs]).
 * None exceed [Limits.maxDwellMs];
 * - - Starting from the moment when the stay comes from that page, the time spent by the model thinking
 * and reading the page is included, and only the insufficient part is made up;
 * - - The website is identified by the registrable domain name (www.example.com and m.example.com are
 * the same); if you go to another website, it will start from the first page.
 * - - When the site knowledge says that this website limits the browsing speed ([PlacePace.CAREFUL]),
 * the pages of this website will stay in the range of [careful] (longer).
 * - - There is an upper limit on the number of pages from the same website in a task ([Limits.pageCap]).
 * After reaching the limit, [capReached] becomes true and the caller will no longer change pages;
 * The count will be restarted after the user answers ([userAnswered]). The upper limit is only browsing:
 * After this task has performed external actions on this website ([outwardDone], sending, submitting,
 * etc.), this website will no longer set an upper limit for the remaining time of this task (batch
 * contact and delivery must be able to meet the website's own upper limit), and the stay will remain the
 * same.
 *
 * [Off] never waits and does not set an upper limit on the number of pages (use it for testing and
 * callers that do not need rhythm).
 */
class PagePacing(private val random: Random?, val limits: Limits = Limits(), val careful: Limits = Limits.CAREFUL) {
    /** The duration unit is milliseconds. */
    data class Limits(
        /** The range of stay on the second page of a website. */
        val firstDwellMs: LongRange = 4_000L..7_000L,
        /** For each additional page after that, add the upper and lower limits by the amount (lower limit, upper limit). */
        val perPageMs: Pair<Long, Long> = 600L to 1_000L,
        /** The upper limit of stay: no more than one page will exceed it. */
        val maxDwellMs: Long = 12_000L,
        /** Calculate the time window of "how many pages have been viewed recently". */
        val windowMs: Long = 10 * 60_000L,
        /** The maximum number of pages from the same website in one task (between user responses). */
        val pageCap: Int = 20,
    ) {
        companion object {
            /** Websites that limit browsing speed: each page pauses longer and the page limit is lower. */
            val CAREFUL = Limits(firstDwellMs = 10_000L..16_000L, perPageMs = 600L to 1_000L, maxDwellMs = 30_000L, pageCap = 12)
        }
    }

    private class Load(val site: String, val atMs: Long, var dwellMs: Long, val limits: Limits)

    private val loads = ArrayDeque<Load>()

    /** How many pages have been visited by each website in this task (since the last time the user answered); they will not be cleared with the time window. */
    private val pages = HashMap<String, Int>()

    /** Websites that have taken external actions in this task: the page limit no longer counts for them; user responses are also unclear. */
    private val outward = HashSet<String>()

    /** Rhythmic (not [Off]). */
    val paced: Boolean get() = random != null

    /**
     * The website has a new page of content on [site] (either host name or URL); return to the set
     * duration of this page. [pace] is what site knowledge says about the browsing speed of this
     * website.
     */
    @Synchronized
    fun noteLoad(site: String, nowMs: Long, pace: PlacePace = PlacePace.NORMAL): Long {
        if (random == null) return 0
        val key = key(site) ?: return 0
        prune(nowMs)
        val l = limitsFor(pace)
        val seen = loads.count { it.site == key }
        val dwell = if (seen == 0) 0 else dwellFor(seen + 1, l)
        loads.addLast(Load(key, nowMs, dwell, l))
        pages[key] = (pages[key] ?: 0) + 1
        return dwell
    }

    /** In this task (since the last time the user answered) [site] has come up a few pages. */
    @Synchronized
    fun pagesOn(site: String): Int = key(site)?.let { pages[it] } ?: 0

    /** Press the upper page limit of [pace]. */
    fun capFor(pace: PlacePace): Int = limitsFor(pace).pageCap

    /** [site] The number of uploaded pages has reached the upper limit: you must stop before changing the page again (read after the user agrees). Websites that have taken external actions ([outwardDone]) and [Off] are always false. */
    @Synchronized
    fun capReached(site: String, pace: PlacePace = PlacePace.NORMAL): Boolean =
        random != null && key(site)?.let { it in outward } != true && pagesOn(site) >= capFor(pace)

    /** This task has an external action (send, submit) on [site]: there will no longer be a page limit on this website for the rest of the time. */
    @Synchronized
    fun outwardDone(site: String) {
        key(site)?.let(outward::add)
    }

    /** The user answered the question (answered the follow-up question and handed in the acceptance): The number of pages on each website was recalculated. */
    @Synchronized
    fun userAnswered() = pages.clear()

    /**
     * How long to wait before doing a possible page change on [site]: the remaining time if the latest
     * page is from this website and has not stopped enough, otherwise 0. [Off] is always 0.
     */
    @Synchronized
    fun waitBefore(site: String?, nowMs: Long): Long {
        if (random == null) return 0
        val key = site?.let(::key) ?: return 0
        val last = loads.lastOrNull() ?: return 0
        if (last.site != key) return 0
        return (last.dwellMs - (nowMs - last.atMs)).coerceIn(0, last.limits.maxDwellMs)
    }

    /** On [site], it has stopped enough for the latest page (the caller has finished waiting): actions after this page no longer wait. */
    @Synchronized
    fun served(site: String) {
        val key = key(site) ?: return
        loads.lastOrNull()?.takeIf { it.site == key }?.dwellMs = 0
    }

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    /** Page [n] (n ≥ 2) Press the dwell time of [l]. */
    private fun dwellFor(n: Int, l: Limits): Long {
        val r = random ?: return 0
        val extra = (n - 2).toLong()
        val lo = (l.firstDwellMs.first + extra * l.perPageMs.first).coerceAtMost(l.maxDwellMs)
        val hi = (l.firstDwellMs.last + extra * l.perPageMs.second).coerceAtMost(l.maxDwellMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (loads.isNotEmpty() && nowMs - loads.first().atMs > limits.windowMs) loads.removeFirst()
    }

    private fun key(site: String): String? {
        val host = UrlHost.of(if ("://" in site) site else "https://$site") ?: return null
        return UrlHost.registrable(host) ?: host
    }

    companion object {
        /** Never wait. */
        val Off = PagePacing(null)

        /** Like the rhythm of people reading web pages; [random] can be replaced with a fixed seed (test). */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            PagePacing(random, limits, careful)
    }
}
