package com.meenai.mina.core.uistate

import com.meenai.mina.core.tool.Until
import com.meenai.mina.core.tool.UntilOutcome
import com.meenai.mina.core.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The interval between two screen readings is at least this long: interface events come one after another, and there is no need to read each one. */
private const val MIN_GAP_MS = 200L
/** When the port does not report interface events, it will be reread after such a long interval; it is also the longest interval between two checks to see if the user has interrupted. */
private const val POLL_MS = 500L
/** The upper limit of each time it is handed over to the port when waiting for the "interface to be stable" (the port is judged according to its own quiet time). */
private const val STABLE_SLICE_MS = 1_500L
/** Accidents must be read several times in succession to count (when the page is changed, the new and old windows will briefly appear at the same time). */
private const val CONFIRM_READS = 2

/**
 * Waiting after completing one step in a batch of actions: Press [Until] to read the screen one by one
 * (without registering the number) for literal comparison. The mobile phone and web page are the same,
 * and only [ScreenPort] and the screen reading result are used. Wake up early when there is an interface
 * event on the port ([ScreenPort.awaitChange]), otherwise reread after a while. If something unexpected
 * happens (a pop-up box, another package cover comes up, the app is changed), the user interrupts, and
 * when the time limit is reached, he no longer waits and returns it to the model for judgment. The
 * reference is the screen at which waiting begins: the switches caused by this step itself are
 * additionally checked by the execution entry.
 */
internal class UntilWait(
    private val screen: ScreenPort,
    private val read: suspend () -> ScreenSnapshot?,
    private val clock: () -> Long,
    private val interrupted: () -> Boolean,
) {
    suspend fun run(until: Until): UntilOutcome {
        val start = clock()
        val timeout = until.timeoutMs.coerceIn(0, Until.MAX_MS)
        // It will also end when the clock does not run (test, clock error): each round takes at least
        // MIN_GAP_MS
        val maxRounds = timeout / MIN_GAP_MS + 2
        var base: ScreenSnapshot? = null
        var stableNow = false
        var suspect: String? = null
        var seen = 0
        fun done(met: Boolean, stopped: String?) = UntilOutcome(met, stopped, (clock() - start).coerceAtLeast(0))
        repeat(maxRounds.toInt()) {
            val snap = read()
            if (snap != null) {
                val first = base ?: snap.also { base = it }
                // If there is an accident, this round will not be completed: the words shown behind the
                // pop-up box do not mean that you can continue to do it.
                val odd = unexpected(first, snap, until)
                if (odd == null && (!until.stable || stableNow) && holds(until, snap)) return done(true, null)
                if (odd != null && odd == suspect) seen++ else { suspect = odd; seen = if (odd == null) 0 else 1 }
                // The page stops when the page pop-up box hangs. There is no need to read it again to
                // confirm.
                if (suspect != null && (seen >= CONFIRM_READS || snap.web?.dialog != null)) return done(false, suspect)
            }
            if (interrupted()) return done(false, UntilOutcome.NEW_MESSAGE)
            val left = timeout - (clock() - start)
            if (left <= 0) return done(false, UntilOutcome.TIMEOUT)
            // Each period of waiting ends immediately when the user interrupts (see waitOrSteer), and
            // does not wait until the next round.
            if (until.stable) {
                stableNow = waitOrSteer { waitStable(minOf(left, STABLE_SLICE_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)
            } else if (waitOrSteer { awaitChange(minOf(left, POLL_MS)) } ?: return done(false, UntilOutcome.NEW_MESSAGE)) {
                delay(MIN_GAP_MS)
            }
        }
        return done(false, UntilOutcome.TIMEOUT)
    }

    private suspend fun waitStable(ms: Long): Boolean =
        try { screen.waitStable(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    private suspend fun awaitChange(ms: Long): Boolean =
        try { screen.awaitChange(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(ms); false }

    companion object {
        /** Whether the condition is established at the moment (only the given items are checked); the same as the check of expect in the action result, based on the element name, regardless of case. */
        fun holds(until: Until, s: ScreenSnapshot): Boolean {
            val names = s.elements.map { it.name }
            until.appears?.trim()?.let { want -> if (names.none { it.contains(want, ignoreCase = true) }) return false }
            // Nothing read (protected, unreadable) does not count as "gone"
            until.gone?.trim()?.let { gone -> if (names.isEmpty() || names.any { it.contains(gone, ignoreCase = true) }) return false }
            until.place?.trim()?.let { p -> if (!inPlace(s, p)) return false }
            return true
        }

        private fun inPlace(s: ScreenSnapshot, p: String) =
            s.packageName?.contains(p, ignoreCase = true) == true || s.web?.url?.contains(p, ignoreCase = true) == true

        /**
         * Compared with the screen [base] when we started waiting, is there any obvious surprise in
         * [now] at this moment; if not, it is null. When the page pop-up box hangs and one more
         * application window appears in the same App, it is [UntilOutcome.POPUP]; when there is an
         * additional application or system window with other packages, it is
         * [UntilOutcome.SYSTEM_DIALOG]; when the front desk is replaced by another App (and it is not
         * the one that [until] is waiting for), it is [UntilOutcome.APP_SWITCHED]. Changing the web page
         * is a normal jump and does not count as changing the app.
         */
        fun unexpected(base: ScreenSnapshot, now: ScreenSnapshot, until: Until): String? {
            if (now.web?.dialog != null && base.web?.dialog == null) return UntilOutcome.POPUP
            val top = now.packageName
            val foreign = { s: ScreenSnapshot ->
                s.windows.filter { it.type in OVERLAY_TYPES && it.packageName != null && it.packageName != s.packageName }.map { it.id }.toSet()
            }
            if ((foreign(now) - foreign(base)).isNotEmpty()) return UntilOutcome.SYSTEM_DIALOG
            if (now.web == null && top != null && top != base.packageName && until.place?.let { inPlace(now, it.trim()) } != true) return UntilOutcome.APP_SWITCHED
            val own = { s: ScreenSnapshot -> s.windows.count { it.type == APPLICATION && it.packageName == top } }
            if (top == base.packageName && own(now) > own(base)) return UntilOutcome.POPUP
            return null
        }

        private const val APPLICATION = "application"
        private val OVERLAY_TYPES = setOf(APPLICATION, "system")
    }
}
