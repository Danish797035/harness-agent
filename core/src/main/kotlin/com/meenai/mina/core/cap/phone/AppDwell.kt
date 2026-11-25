package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.DwellInterrupted
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PageLimitReached
import com.meenai.mina.core.uistate.PlacePace
import com.meenai.mina.core.run.waitOrSteer
import com.meenai.mina.core.uistate.ScreenPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Press [AppPacing] to pause in the App during a task: write down each action to change the screen
 * ([after]), and make up for the unsatisfied time before the next one ([before]). [pace] gives the app
 * knowledge’s opinion on the browsing speed of this app based on the package name. When [idle] is given,
 * this time the range is allowed, and the caller hands over the screen port (the next action is not
 * based on the position on the screen), lightly roll a few times according to its arrangement during the
 * stay ([ScreenPort.idleScroll]). this action, and the insufficient part will be made up next time.
 */
internal class AppDwell(
    private val pacing: AppPacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** It stopped for a long time and rolled a few times during the stay. */
    class Dwelt(val waitMs: Long, val idleScrolls: Int)

    /** After staying there, roll it slightly and then call it (the position of the screen in the model's hand is no longer accurate). */
    var onIdleScroll: () -> Unit = {}

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** An action of changing the screen is completed in [app]: [newScreen] is changed to a new screen, and the number of screens entered is counted in [counts] (see [AppPacing.noteAction]). */
    fun after(app: String?, newScreen: Boolean, counts: Boolean = newScreen) {
        if (!paced) return
        pacing.noteAction(app, clock(), newScreen, app?.let(::paceOf) ?: PlacePace.NORMAL, counts)
    }

    /**
     * Before making the next action to change the screen in [app]: make up for it if it has not stopped
     * enough. [textChars] is the number of words on this screen at the moment (press the new screen to
     * add the reading time). When [gate], first check the upper limit of the number of screens, and then
     * throw [PageLimitReached]. [port] You can roll it gently a few times while holding it.
     */
    suspend fun before(app: String?, textChars: Int, gate: Boolean, port: ScreenPort? = null): Dwelt {
        if (!paced) return Dwelt(0, 0)
        val p = app?.let(::paceOf) ?: PlacePace.NORMAL
        if (gate && pacing.capReached(app, p)) throw PageLimitReached(app!!, pacing.screensIn(app), pacing.screenCap)
        val wait = pacing.waitBefore(clock(), textChars)
        if (wait <= 0) return Dwelt(0, 0)
        val plan = idle?.takeIf { port != null && pacing.idleAllowed() }
        val scrolls = if (plan != null) idleWait(port!!, plan, wait) else 0.also { pause(wait) }
        return Dwelt(wait, scrolls)
    }

    /** Users have answered: The number of screens for each App will be recalculated. */
    fun userAnswered() = pacing.userAnswered()

    /** After the external actions in [app] are completed; when [done] (made), the App no longer sets an upper limit on the number of screens. */
    fun outwardEnd(app: String, done: Boolean) {
        if (done) pacing.outwardDone(app)
    }

    /** Stop [ms] milliseconds; stop when the user interrupts, throw [DwellInterrupted]. */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /** Stop for [wait] milliseconds, during which time press [plan] and scroll lightly a few times; if the port says it is inconvenient to scroll (pop-up box, input method, focus) at this time, it will stop scrolling, and it is still enough. Go back and roll a few times. */
    private suspend fun idleWait(port: ScreenPort, plan: IdleScroll, wait: Long): Int {
        val start = clock()
        var done = 0
        for (step in plan.plan(wait)) {
            val left = step.atMs - (clock() - start)
            if (left > 0) pause(left)
            val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) break
            done++
            onIdleScroll()
        }
        val rest = wait - (clock() - start)
        if (rest > 0) pause(rest)
        return done
    }

    private fun paceOf(app: String): PlacePace = synchronized(paces) { paces.getOrPut(app) { pace(app) } }
}
