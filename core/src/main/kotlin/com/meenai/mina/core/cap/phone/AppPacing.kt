package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.PlacePace
import com.meenai.mina.core.uistate.Pace
import kotlin.random.Random
import com.meenai.mina.core.uistate.IdleScroll

/**
 * The rhythm of the step-by-step operation in the app (pure logic, without touching the clock and
 * interface): after people click on something, they always have to look at it before clicking on it, and
 * when they get to the new screen, they still have to look at it for a while. After each action of
 * changing the screen ([noteAction]), press whether it has been changed to a new screen to determine the
 * minimum interval until the next action; before the next action, ask [waitBefore]: If the interval is
 * not enough, make up the remaining time.
 *
 * - - Continue on the same screen (typing, flipping switches, only changing one or two elements): stop a
 * random paragraph in [Limits.sameScreenMs];
 * - - Switched to a new screen (big change in interface, changed app): Stop a paragraph in
 * [Limits.newScreenMs], and press the number of words on this screen to add a period of reading time
 * ([Limits.readMsPerChar], at most [Limits.maxReadMs]);
 * - - Occasionally ([Limits.pauseChance]) stop for a while ([Limits.pauseMs]); no longer than
 * [Limits.maxDwellMs] at one time;
 * - - The pause is calculated from the completion of the previous action, including the time spent on
 * model thinking and screen reading, and only the insufficient part is made up.
 * - - App knowledge says that this App limits the browsing speed ([PlacePace.CAREFUL]) and stops in the
 * range of [careful] (longer).
 * There is an upper limit for the number of times a task can be changed to a new screen in this App
 * ([screenCap]). After that, [capReached] is true and the caller will no longer change screens; the
 * count will be restarted after the user answers the question ([userAnswered]); this task will no longer
 * have an upper limit after it has completed external actions ([outwardDone], send and submit) in this
 * App. It is the same as the rhythm of reading web pages page by page
 * ([com.meenai.mina.core.cap.web.PagePacing]).
 *
 * [Off] never waits and has no upper limit (use it for "fast", test, and callers who don't need rhythm).
 */
class AppPacing(
    private val random: Random?,
    val limits: Limits = Limits(),
    val careful: Limits = Limits.CAREFUL,
    /** Limit the browsing speed of an app. The maximum number of times the user can switch to a new screen in one task (between responses). */
    val screenCap: Int = CAREFUL_SCREEN_CAP,
) {
    /** The duration unit is milliseconds. */
    data class Limits(
        /** The staying range when continuing to work on the same screen. */
        val sameScreenMs: LongRange = 500L..1_200L,
        /** The stay range after switching to a new screen (excluding reading time). */
        val newScreenMs: LongRange = 900L..2_000L,
        /** How much reading time is added to each word on the new screen; 0 means no addition. */
        val readMsPerChar: Long = 0L,
        /** The upper limit of the reading time. */
        val maxReadMs: Long = 0L,
        /** A chance to stop a little longer. */
        val pauseChance: Double = 0.05,
        /** Additional range when stopping for a while. */
        val pauseMs: LongRange = 600L..1_500L,
        /** Maximum limit for one stay. */
        val maxDwellMs: Long = 3_500L,
        /** When you stay on the new screen for long enough, you can scroll a few times while staying on it (see [com.meenai.mina.core.uistate.IdleScroll]). */
        val idleScroll: Boolean = false,
    ) {
        companion object {
            /** Apps that limit browsing speed (and all Apps when "like people"): pause longer, read by the number of words, and scroll a few times while staying. */
            val CAREFUL = Limits(
                sameScreenMs = 900L..2_200L, newScreenMs = 2_500L..5_000L, readMsPerChar = 15L, maxReadMs = 8_000L,
                pauseChance = 0.1, pauseMs = 2_000L..5_000L, maxDwellMs = 15_000L, idleScroll = true,
            )
        }
    }

    private class Last(val app: String?, val atMs: Long, val newScreen: Boolean, val baseMs: Long, val limits: Limits)

    private var last: Last? = null

    /** In this task (since the last time the user answered), various apps that limit browsing speed have been changed to new screens several times. */
    private val screens = HashMap<String, Int>()

    /** Apps that have performed external actions in this task: the upper limit of the number of screens does not count for them; the user's answers are also unclear. */
    private val outward = HashSet<String>()

    /** Rhythmic (not [Off]). */
    val paced: Boolean get() = random != null

    /**
     * In [app] (package name; unknown as null), a screen change action is completed: [newScreen] is
     * changed to a new screen, and in [counts], this screen is counted as the number of screens of the
     * App that limits the browsing speed (scrolling down to read the remaining content on the same page
     * does not count). [pace] is how App knowledge describes the browsing speed of this App.
     */
    @Synchronized
    fun noteAction(app: String?, nowMs: Long, newScreen: Boolean, pace: PlacePace = PlacePace.NORMAL, counts: Boolean = newScreen) {
        val r = random ?: return
        val l = limitsFor(pace)
        var base = Pace.pick(r, if (newScreen) l.newScreenMs else l.sameScreenMs)
        if (r.nextDouble() < l.pauseChance) base += Pace.pick(r, l.pauseMs)
        last = Last(app, nowMs, newScreen, base.coerceAtMost(l.maxDwellMs), l)
        if (newScreen && counts && pace == PlacePace.CAREFUL && app != null) screens[app] = (screens[app] ?: 0) + 1
    }

    /**
     * How long to wait before the next action: the remaining time if the last action has not stopped
     * enough, otherwise 0. [textChars] is the number of words on this screen at this moment: when the
     * previous action changes to a new screen, press it to add the reading time. [Off] is always 0.
     */
    @Synchronized
    fun waitBefore(nowMs: Long, textChars: Int = 0): Long {
        val l = last ?: return 0
        val read = if (l.newScreen) (textChars.coerceAtLeast(0) * l.limits.readMsPerChar).coerceAtMost(l.limits.maxReadMs) else 0L
        val dwell = (l.baseMs + read).coerceAtMost(l.limits.maxDwellMs)
        return (dwell - (nowMs - l.atMs)).coerceIn(0, l.limits.maxDwellMs)
    }

    /** You can scroll a few times during this stay: the previous action has changed to a new screen, and the pressing range allows. */
    @Synchronized
    fun idleAllowed(): Boolean = last?.let { it.newScreen && it.limits.idleScroll } == true

    /** In this task (since the last time the user answered) [app] has changed to a new screen several times. */
    @Synchronized
    fun screensIn(app: String): Int = screens[app] ?: 0

    /** [app] The number of times to switch to a new screen has reached the upper limit: stop before changing the screen again (see again after the user agrees). Ordinary apps, apps that have performed external actions, and [Off] are always false. */
    @Synchronized
    fun capReached(app: String?, pace: PlacePace): Boolean =
        random != null && app != null && pace == PlacePace.CAREFUL && app !in outward && screensIn(app) >= screenCap

    /** This task has an external action (send, submit) in [app]: this App will no longer have a screen limit for the rest of the time. */
    @Synchronized
    fun outwardDone(app: String) {
        if (random != null) outward += app
    }

    /** The user answered the question (answered the follow-up question and handed over the takeover): the number of screens for each App was recalculated. */
    @Synchronized
    fun userAnswered() = screens.clear()

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    companion object {
        /** Apps that limit the browsing speed can switch to a new screen at most in one task (one screen in the app is smaller than one page on the web page: clicking to open and returning are counted once). */
        const val CAREFUL_SCREEN_CAP = 24

        /** Never wait. */
        val Off = AppPacing(null)

        /** Like the rhythm of a person operating step by step in the App; [random] can be replaced with a fixed seed (test). */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            AppPacing(random, limits, careful)
    }
}
