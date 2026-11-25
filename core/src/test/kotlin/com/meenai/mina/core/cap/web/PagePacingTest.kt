package com.meenai.mina.core.cap.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PageLimitReached
import com.meenai.mina.core.uistate.PlacePace

/** The rhythm of reading page by page: it varies from the first page to when you have stopped enough and when you change the website. There are upper and lower limits for staying and it becomes longer with the number of pages viewed. */
class PagePacingTest {
    private val limits = PagePacing.Limits()

    @Test
    fun `first page on a site has no dwell`() {
        val p = PagePacing.human(Random(1))
        assertEquals(0, p.waitBefore("shop.example.com", 0))
        assertEquals(0, p.noteLoad("https://shop.example.com/", 0))
        assertEquals(0, p.waitBefore("shop.example.com", 10))
    }

    @Test
    fun `second page dwell is within the first range and counts elapsed time`() {
        repeat(50) { seed ->
            val p = PagePacing.human(Random(seed))
            p.noteLoad("https://shop.example.com/", 0)
            val dwell = p.noteLoad("https://shop.example.com/list?page=2", 1_000)
            assertTrue("$dwell", dwell in limits.firstDwellMs)
            assertEquals(dwell, p.waitBefore("shop.example.com", 1_000))
            // The time spent thinking and reading the model by itself is included in the stay
            assertEquals(dwell - 1_500, p.waitBefore("shop.example.com", 2_500))
            assertEquals(0, p.waitBefore("shop.example.com", 1_000 + dwell))
            assertEquals(0, p.waitBefore("shop.example.com", 100_000))
        }
    }

    @Test
    fun `dwell grows with pages seen and never passes the cap`() {
        val p = PagePacing.human(Random(7))
        var last = 0L
        var t = 0L
        for (n in 1..30) {
            val d = p.noteLoad("https://shop.example.com/p$n", t)
            if (n >= 2) {
                val extra = (n - 2).toLong()
                val lo = (limits.firstDwellMs.first + extra * limits.perPageMs.first).coerceAtMost(limits.maxDwellMs)
                assertTrue("page $n: $d", d in lo..limits.maxDwellMs)
            }
            last = d
            t += 15_000
        }
        assertEquals(limits.maxDwellMs, last)
        assertTrue(p.waitBefore("shop.example.com", t - 15_000) <= limits.maxDwellMs)
    }

    @Test
    fun `another site does not wait and subdomains are the same site`() {
        val p = PagePacing.human(Random(3))
        p.noteLoad("https://www.shop.example.com/", 0)
        p.noteLoad("https://m.shop.example.com/item/1", 100)
        // The same registrable domain name: second page
        assertTrue(p.waitBefore("example.com", 200) > 0)
        // Other websites: No waiting; after arriving at other websites, the action of returning to the
        // original website does not wait according to the page of the original website.
        assertEquals(0, p.waitBefore("news.example.org", 200))
        assertEquals(0, p.noteLoad("https://news.example.org/", 300))
        assertEquals(0, p.waitBefore("shop.example.com", 400))
    }

    @Test
    fun `pages outside the window no longer count`() {
        val p = PagePacing.human(Random(5))
        p.noteLoad("https://shop.example.com/", 0)
        // After the time window has passed, it is the first page of this website again.
        assertEquals(0, p.noteLoad("https://shop.example.com/again", limits.windowMs + 1))
    }

    @Test
    fun `off never waits`() {
        val p = PagePacing.Off
        p.noteLoad("https://shop.example.com/", 0)
        p.noteLoad("https://shop.example.com/2", 1)
        assertEquals(0, p.waitBefore("shop.example.com", 2))
    }

    @Test
    fun `dwell waits only for the rest and only on url changes`() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val waited = mutableListOf<Long>()
        val d = PageDwell(PagePacing.human(Random(11)), clock = { now }, sleep = { slept += it; now += it })
        d.onWait = { waited += it }
        d.seen("https://shop.example.com/")
        assertEquals(0, d.before())
        // The same page (only different with #) is not considered a new page
        d.seen("https://shop.example.com/#top")
        assertEquals(0, d.before())
        d.seen("https://shop.example.com/list?page=2")
        now += 1_000
        val w = d.before()
        assertTrue("$w", w in (limits.firstDwellMs.first - 1_000)..(limits.firstDwellMs.last - 1_000))
        assertEquals(listOf(w), slept)
        assertEquals(listOf(w), waited)
        // Stopped enough: No more waiting for the next action
        assertEquals(0, d.before())
        // Loaded more: Another page
        d.loadedMore()
        assertTrue(d.before() > 0)
        // Wait until you open other websites
        d.seen("https://shop.example.com/list?page=3")
        assertEquals(0, d.before("https://news.example.org/"))
    }

    @Test
    fun `careful sites dwell longer within their own cap`() {
        val c = PagePacing.Limits.CAREFUL
        repeat(30) { seed ->
            val p = PagePacing.human(Random(seed))
            var t = 0L
            for (n in 1..20) {
                val d = p.noteLoad("https://jobs.example.com/p$n", t, PlacePace.CAREFUL)
                if (n == 2) assertTrue("$d", d in c.firstDwellMs)
                if (n >= 2) assertTrue("page $n: $d", d in c.firstDwellMs.first..c.maxDwellMs)
                t += 40_000
            }
        }
        // The scope of ordinary websites remains unchanged
        val p = PagePacing.human(Random(2))
        p.noteLoad("https://shop.example.com/", 0)
        assertTrue(p.noteLoad("https://shop.example.com/2", 1) in limits.firstDwellMs)
        assertTrue(c.firstDwellMs.first > limits.firstDwellMs.last && c.maxDwellMs > limits.maxDwellMs && c.pageCap < limits.pageCap)
    }

    @Test
    fun `page cap counts pages per site in the task and resets when the user answers`() {
        val p = PagePacing.human(Random(4))
        repeat(limits.pageCap - 1) { p.noteLoad("https://shop.example.com/p$it", it * 60_000L) }
        assertFalse(p.capReached("shop.example.com"))
        p.noteLoad("https://www.shop.example.com/last", 3_000_000)
        // Pages that have passed the time window are also counted: the upper limit is based on the
        // number of entire tasks
        assertTrue(p.capReached("m.shop.example.com"))
        assertEquals(limits.pageCap, p.pagesOn("shop.example.com"))
        // Other websites are calculated separately
        assertFalse(p.capReached("news.example.org"))
        p.userAnswered()
        assertFalse(p.capReached("shop.example.com"))
        // Websites that limit browsing speed have lower limits
        repeat(PagePacing.Limits.CAREFUL.pageCap) { p.noteLoad("https://jobs.example.com/p$it", it.toLong(), PlacePace.CAREFUL) }
        assertTrue(p.capReached("jobs.example.com", PlacePace.CAREFUL))
        // There is no upper limit when there is no rhythm
        repeat(50) { PagePacing.Off.noteLoad("https://shop.example.com/p$it", 0) }
        assertFalse(PagePacing.Off.capReached("shop.example.com"))
    }

    @Test
    fun `page cap is lifted for a site once an outward action was done there`() {
        val p = PagePacing.human(Random(5))
        repeat(limits.pageCap) { p.noteLoad("https://jobs.example.com/p$it", it * 1_000L) }
        repeat(limits.pageCap) { p.noteLoad("https://shop.example.org/p$it", it * 1_000L) }
        assertTrue(p.capReached("jobs.example.com"))
        p.outwardDone("www.jobs.example.com")
        // External actions have been taken: This website (the same registrable domain name) no longer
        // has an upper limit, and the stay remains the same
        assertFalse(p.capReached("m.jobs.example.com"))
        val d = p.noteLoad("https://jobs.example.com/more", 100_000)
        assertTrue("$d", d > 0)
        assertEquals(d, p.waitBefore("jobs.example.com", 100_000))
        // The setting remains unchanged after the user responds; other websites are not affected.
        p.userAnswered()
        repeat(limits.pageCap * 2) { p.noteLoad("https://jobs.example.com/x$it", 200_000L + it) }
        assertFalse(p.capReached("jobs.example.com"))
        repeat(limits.pageCap) { p.noteLoad("https://shop.example.org/y$it", 300_000L + it) }
        assertTrue(p.capReached("shop.example.org"))
    }

    @Test
    fun `the outward action itself passes the cap and only a done one lifts it`() = runBlocking {
        var now = 0L
        val d = PageDwell(PagePacing.human(Random(9), limits = PagePacing.Limits(pageCap = 2)), clock = { now }, sleep = { now += it })
        for (i in 1..2) { d.seen("https://jobs.example.com/p$i"); now += 60_000 }
        assertTrue(runCatching { d.before() }.exceptionOrNull() is PageLimitReached)
        // External actions that are being done will not be blocked; unfinished actions will not be
        // lifted from the upper limit.
        d.outwardStart("jobs.example.com")
        d.before()
        d.outwardEnd("jobs.example.com", done = false)
        assertTrue(runCatching { d.before() }.exceptionOrNull() is PageLimitReached)
        d.outwardStart("jobs.example.com")
        d.outwardEnd("jobs.example.com", done = true)
        d.seen("https://jobs.example.com/p3")
        // After canceling, the stay will still be made up.
        assertTrue(d.before() > 0)
    }

    @Test
    fun `dwell refuses page changes past the cap but still scrolls`() = runBlocking {
        var now = 0L
        val d = PageDwell(PagePacing.human(Random(9), limits = PagePacing.Limits(pageCap = 3)), clock = { now }, sleep = { now += it })
        for (i in 1..3) { d.seen("https://shop.example.com/p$i"); now += 60_000; d.before(gate = false) }
        val e = runCatching { d.before() }.exceptionOrNull()
        assertTrue("$e", e is PageLimitReached && e.pages == 3 && e.cap == 3 && e.site == "shop.example.com")
        assertTrue(runCatching { d.before("https://shop.example.com/p4") }.exceptionOrNull() is PageLimitReached)
        // Scroll down (without looking at the upper limit) and other websites as usual
        assertEquals(0, d.before(gate = false))
        assertEquals(0, d.before("https://news.example.org/"))
        // The user replied: Read on
        d.userAnswered()
        assertEquals(0, d.before())
    }

    @Test
    fun `site pace comes from the lookup by host`() = runBlocking {
        var now = 0L
        val asked = mutableListOf<String>()
        val d = PageDwell(PagePacing.human(Random(6)), pace = { asked += it; if (it.endsWith("jobs.example.com")) PlacePace.CAREFUL else PlacePace.NORMAL },
            clock = { now }, sleep = { now += it })
        d.seen("https://www.jobs.example.com/a")
        d.seen("https://www.jobs.example.com/b")
        val w = d.before()
        assertTrue("$w", w in PagePacing.Limits.CAREFUL.firstDwellMs)
        // Ask the same host only once
        assertEquals(listOf("jobs.example.com"), asked)
    }

    @Test
    fun `idle scrolls happen inside a long dwell only when a port is given`() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val port = FakeWebPort()
        var idled = 0
        val d = PageDwell(PagePacing.human(Random(12)), idle = IdleScroll(Random(3)), clock = { now }, sleep = { slept += it; now += it })
        d.onIdleScroll = { idled += it }
        d.seen("https://shop.example.com/")
        d.seen("https://shop.example.com/2")
        val w = d.before(port = port)
        // The total duration of the stop remains unchanged, and the roll is 1–3 times.
        assertEquals(w, slept.sum())
        val scrolls = port.calls.filter { it.startsWith("idle ") }
        assertTrue("$scrolls", scrolls.size in 1..3)
        assertEquals(scrolls.size, idled)
        // Actions based on coordinates (no ports are given) do not roll
        d.seen("https://shop.example.com/3")
        port.calls.clear()
        d.before()
        assertTrue(port.calls.isEmpty())
        // The port said that it is not convenient to roll at this time: if it doesn’t work, don’t try
        // again, just stop.
        d.seen("https://shop.example.com/4")
        port.calls.clear(); slept.clear(); idled = 0
        port.idleOk = false
        val w2 = d.before(port = port)
        assertEquals(w2, slept.sum())
        assertTrue(port.calls.size <= 1)
        assertEquals(0, idled)
    }
}
