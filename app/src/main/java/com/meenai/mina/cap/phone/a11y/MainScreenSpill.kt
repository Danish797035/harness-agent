package com.meenai.mina.cap.phone.a11y

/**
 * Determine whether an action of the Agent causes an App to be opened on the user's home screen. When an
 * App opens another App not from its own page on the Mina screen, but from the background (services,
 * notification jumps, system selectors, cross-user transfers), the system puts it on the home screen by
 * default; no changes are visible on the Mina screen, and the model will think that it has not been
 * clicked and clicked again and again. When you recognize it, move it back to Mina's screen (see
 * AndroidScreen).
 *
 * Only look at the tasks that were changed to the top of the main screen within a short period of time
 * after the action started (note by the built-in assistant, see MainScreenLog). Not counted: not during
 * this period; pages such as desktop and recent tasks, Mina itself, desktop App ([excluded]); moved from
 * other screens (takeover, takeover of other tasks); items that are no longer on the home screen at the
 * moment; the user has clicked on things on the home screen during this period (it is unclear who opened
 * it, it is regarded as opened by the user); the task that was at the top of the home screen before the
 * action returns to the top (revealed after removing the one that was removed). Before the action, the
 * page was changed in the task at the top of the home screen: the App that the user was using was opened
 * and opened in front of the user without touching it ([InUse]). The user has already clicked on the
 * task that popped up (the window clicked belongs to it): it is also being used by the user and will not
 * be moved. Pure logic.
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** The task that pops up to the top of the home screen; which screen [from] was on when it was last seen ([NEW] if it has not been seen before), and which screen [now] is on now (-1 if it is no longer there). */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** When the user clicks on the home screen: [at], [packageName] is the package where the click is located (null if not read). */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }
    /** Move the action to the home screen: Move back to Mina's screen. */
    data class PullBack(override val arrival: Arrival) : Verdict
    /** Open the app that the user is using on the home screen: leave it alone and tell the model. */
    data class InUse(override val arrival: Arrival) : Verdict

    /**
     * [since] is the moment when the action starts, anything that appears after [windowMs] is not
     * counted as this action; [topBefore] is the top task on the home screen when the action starts;
     * [userTaps] is the time and package when the user clicks something on the home screen. There is
     * only one issue for the same task (take the last one), and they are arranged in order.
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
    ): List<Verdict> {
        val out = LinkedHashMap<Int, Verdict>()
        for (a in arrivals.sortedBy { it.at }) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    enum class Handling {
        /** Move back Mina's screen (only move the task, the original app that opens it is still the same). */
        Move,
        /** The system is asking the user which one to open: do not move it, leaving it to the user to choose on their own screen while the task waits. */
        LeaveForUser,
    }

    /**
     * What should I do if I open the system package window on the home screen, and it is the system
     * selector, or the options in the window match two installed Apps ([optionsMatch]). It just asks
     * which one to open and leaves it to the user; move the rest back. Mina never reopens it for other
     * apps.
     */
    fun handling(systemPackage: Boolean, systemPicker: Boolean, optionsMatch: Boolean): Handling =
        if (systemPackage && (systemPicker || optionsMatch)) Handling.LeaveForUser else Handling.Move

    /**
     * The one that pops up after the user has made a selection in the selection box on the home screen
     * (the selection box is no longer there when it is [gone]): the selection box pops up on the home
     * screen after that (not counting the selection box itself [chooserTask] and the [topBefore] at the
     * top of the home screen before it appears); the user has just clicked on the option and does not
     * press "User Clicked" to exclude it. Check whether the selection box is closed or not is checked
     * every once in a while, including the ones that pop up in [slackMs] before closing it. None is
     * null.
     */
    fun picked(arrivals: List<Arrival>, chooserTask: Int, gone: Long, slackMs: Long, waitMs: Long, topBefore: Int, excluded: Set<String>): Arrival? =
        decide(arrivals.filter { it.taskId != chooserTask }, gone - slackMs, waitMs + slackMs, topBefore, excluded, emptyList())
            .filterIsInstance<PullBack>().lastOrNull()?.arrival

    /**
     * The selected App requires the user to deal with it manually: there is a password box in the
     * window, or the main screen cannot be read and the main screen is taken to prevent the screenshot
     * window (this is often the case for pages such as payment and password input). Just that the tree
     * cannot be read (some App trees have very few trees) does not count.
     */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean = passwordField || (!readable && secureWindow)

    /**
     * Is this package the system's account login service? In the authenticator [authenticators], there
     * is no desktop icon ([hasLauncher] is false). This kind of package will only appear on the home
     * screen for pages such as adding an account, logging in, and verifying. The user has to do it
     * manually. The entire screen, without a password box, cannot be moved to Mina's screen.
     * Authenticator packages with desktop icons (chat apps and email apps that synchronize contacts)
     * only register accounts by the way, and their pages follow other criteria as usual. Pure logic.
     */
    fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
        pkg in authenticators && !hasLauncher

    private fun verdict(a: Arrival, since: Long, windowMs: Long, topBefore: Int, excluded: Set<String>, userTaps: List<Tap>): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // The user is already clicking on it (click on the window of this App after it appears): Leave
        // it to the user and do not move it
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}
