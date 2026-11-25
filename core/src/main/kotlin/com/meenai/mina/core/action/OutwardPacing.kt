package com.meenai.mina.core.action

import com.meenai.mina.core.net.UrlHost
import kotlin.random.Random

/**
 * The interval between external actions (pure logic, without touching the clock and interface):
 * Irreversible judgments recognize sending, submitting (contacting others, submitting applications),
 * deleting, and placing orders. When they are done continuously on the same website or app, there will
 * be a random period of time between the two actions, just like a person processing them one by one,
 * without consecutive seconds. Regardless of whether there is a page break in the middle: the pause of
 * the page break is controlled by the rhythm of the web page. The mobile phone and the web page are the
 * same because the judgment is the same.
 *
 * - - The first time in a website (App) does not wait;
 * - - Each time thereafter, there is at least a random section of [Limits.firstGapMs] between the last
 * time. The more you do in this window, the upper and lower limits will each add one section
 * ([Limits.perActionMs]).
 * None exceed [Limits.maxGapMs];
 * - - The interval is calculated from the last time you finished it. The time spent reading pages and
 * thinking about the next step is also included. Only the insufficient part is made up;
 * - - The web page is identified by the registrable domain name (www.example.com and m.example.com are
 * the same), and the mobile phone is identified by the package name; other websites and apps are counted
 * separately.
 *
 * [Off] Never wait (use this for testing and callers that don't need cadence).
 */
class OutwardPacing(private val random: Random?, val limits: Limits = Limits()) {
    /** The duration unit is milliseconds. */
    data class Limits(
        /** The range of intervals between the second and first times. */
        val firstGapMs: LongRange = 8_000L..20_000L,
        /** Each time you do it again, add the upper and lower limits (lower limit, upper limit). */
        val perActionMs: Pair<Long, Long> = 500L to 1_000L,
        /** The upper limit of the interval. */
        val maxGapMs: Long = 30_000L,
        /** Calculate the time window of "how many times have you done it recently". */
        val windowMs: Long = 30 * 60_000L,
    )

    private class Done(val site: String, val atMs: Long, val gapMs: Long)

    private val done = ArrayDeque<Done>()

    /** Rhythmic (not [Off]). */
    val paced: Boolean get() = random != null

    /** How long to wait before making the next external action on [site]: if there is not enough time since the last time, it is the remaining time, otherwise 0. */
    @Synchronized
    fun waitBefore(site: String, nowMs: Long): Long {
        if (random == null) return 0
        prune(nowMs)
        val last = done.lastOrNull { it.site == site } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, limits.maxGapMs)
    }

    /** An external action was made on [site]: click on how many times you have done it in this window to determine at least how long it will take until the next time. */
    @Synchronized
    fun noteDone(site: String, nowMs: Long) {
        if (random == null) return
        prune(nowMs)
        val count = done.count { it.site == site } + 1
        done.addLast(Done(site, nowMs, gapAfter(count)))
    }

    /** The interval between doing [n] times (n ≥ 1) and the next time. */
    private fun gapAfter(n: Int): Long {
        val r = random ?: return 0
        val extra = (n - 1).toLong()
        val lo = (limits.firstGapMs.first + extra * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
        val hi = (limits.firstGapMs.last + extra * limits.perActionMs.second).coerceAtMost(limits.maxGapMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (done.isNotEmpty() && nowMs - done.first().atMs > limits.windowMs) done.removeFirst()
    }

    companion object {
        /** Never wait. */
        val Off = OutwardPacing(null)

        /** Like the rhythm of processing one by one; [random] can be replaced with a fixed seed (test). */
        fun human(random: Random = Random.Default, limits: Limits = Limits()) = OutwardPacing(random, limits)

        /** Which website or app a screen belongs to: press the registered domain name for the web page ([web] is true), and press the package name for the mobile phone. */
        fun siteOf(packageName: String?, web: Boolean): String? {
            val p = packageName?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return if (web) UrlHost.registrable(p) ?: p else p
        }
    }
}
