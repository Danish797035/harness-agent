package com.meenai.mina.task

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * AgentLoop calls [pass] before every tool that changes the screen. Check in sequence: 1. The mobile
 * operation assistant is unavailable → pause, wait for it to resume and the user clicks "Continue"; 2.
 * The task is moved to the home screen (the user opens the app on the home screen) → pause, and the user
 * clicks "Continue" before moving it back to the virtual screen; Mina does not judge whether the user
 * has finished using it, so as to avoid taking the app away in front of the user; 3. The screen goes out
 * or locks the screen → waits for unlocking, and then automatically continues after the screen is turned
 * on and unlocked. The first two are only checked when a mobile phone is used in this step (phone is
 * true): steps on the web page and sandbox do not require an assistant, and will not be moved to the
 * home screen. In addition, the user touches the page where the task is lent ([onUserOnPage]) on the
 * browser page → No matter which screen is used, it stops first, and the user clicks "Continue" to
 * continue. The steps that do not touch the interface (model reasoning, screen reading) do not pass
 * through here and proceed as usual.
 */
class TaskGate(
    private val locked: StateFlow<Boolean>,
    private val shellAvailable: StateFlow<Boolean>,
    private val moveBack: suspend () -> Boolean,
) {
    private val movedAway = MutableStateFlow(false)
    private val userOnPage = MutableStateFlow(false)
    /** The user pressed pause himself: stop before changing the screen next, click "Continue" and continue. */
    private val userPaused = MutableStateFlow(false)
    private val resumes = MutableStateFlow(0)
    /** The click count at the moment when it is discovered that it has been moved: all subsequent clicks of "Continue" count, even if the Agent has not yet reached the gate. */
    @Volatile private var resumesAtMove = 0
    /** The click count at the moment when pause is pressed: pressing "Continue" previously clicked does not count. */
    @Volatile private var resumesAtPause = 0

    fun onTaskMovedAway() {
        if (!movedAway.value) resumesAtMove = resumes.value
        movedAway.value = true
    }

    /** It has been found that it has been moved but has not been moved back: the task service is immediately suspended accordingly, without waiting for the next tool to change the screen. */
    val movedAwayPending: Boolean get() = movedAway.value

    /**
     * The user touches the page lent to the task on the browser page (he is operating, Mina cannot move
     * at the same time): it stops before the next tool that will change the screen, and waits for the
     * user to click "Continue"; the task service immediately displays a pause accordingly.
     */
    fun onUserOnPage() { userOnPage.value = true }

    /** The user is operating on the lending page and has not clicked "Continue". */
    val userOnPagePending: Boolean get() = userOnPage.value

    /** The user pressed pause but did not click "Continue": the task service immediately displays the pause accordingly, without having to wait for the next tool to change the screen. */
    val userPausedPending: Boolean get() = userPaused.value

    /** The user pressed pause. The step of waiting for the model to be completed is still completed. Stop before changing the screen in the next step. */
    fun pause() {
        if (!userPaused.value) resumesAtPause = resumes.value
        userPaused.value = true
    }

    /** The user clicked "Continue". It is only valid for pauses that started after clicking or are in progress; pauses that the user operates on the page are released on the spot. */
    fun resume() {
        resumes.update { it + 1 }
        userOnPage.value = false
        userPaused.value = false
    }

    suspend fun pass(phone: () -> Boolean = { true }, setWaiting: (TaskState?) -> Unit) {
        while (true) {
            val onPhone = phone()
            when {
                userOnPage.value -> {
                    setWaiting(TaskState.Held(paused(USER_ON_PAGE)))
                    userOnPage.first { !it }
                }
                userPaused.value -> {
                    // I've already clicked "Continue" before I got here: Don't stop anymore
                    if (resumes.value != resumesAtPause) {
                        userPaused.value = false
                        continue
                    }
                    pauseUntilResume(USER_PAUSED, setWaiting, resumesAtPause)
                }
                onPhone && !shellAvailable.value -> {
                    pauseUntilResume(SHELL_DOWN, setWaiting)
                }
                onPhone && movedAway.value -> {
                    pauseUntilResume(MOVED_AWAY, setWaiting, seen = resumesAtMove)
                    while (shellAvailable.value && !moveBack()) pauseUntilResume(MOVE_BACK_FAILED, setWaiting)
                    if (shellAvailable.value) movedAway.value = false
                }
                locked.value -> {
                    setWaiting(TaskState.Held(LOCKED))
                    locked.first { !it }
                }
                else -> {
                    setWaiting(null)
                    return
                }
            }
        }
    }

    /** The user is required to handle one step manually on the mobile phone, and click "Continue" when the process is completed (the interface counts as "I want you to handle it"). */
    suspend fun pauseForUser(why: String, setWaiting: (TaskState?) -> Unit) {
        val seen = resumes.value
        setWaiting(TaskState.Held(GateReason("paused", why, resumable = true, needsYou = true)))
        resumes.first { it != seen }
    }

    /** [seen]: only "Continue" taps after this click count count; defaults to the current value (recording first before showing pause still counts an immediate tap). */
    private suspend fun pauseUntilResume(why: String, setWaiting: (TaskState?) -> Unit, seen: Int = resumes.value) {
        setWaiting(TaskState.Held(paused(why)))
        resumes.first { it != seen }
    }

    companion object {
        /** Lock or turn off the screen: Automatically continue after unlocking (recorded as waiting_unlock in the running record). */
        val LOCKED = GateReason("waiting_unlock")

        /** Pause, and the user clicks "Continue" before continuing (recorded as paused in the running record, with the reason). */
        fun paused(why: String) = GateReason("paused", why, resumable = true)

        /** Wait for the user to move their hands on the phone (for example, select one in the system selection box), and then automatically continue after the movement (recorded as waiting_user in the running record). */
        fun userStep(why: String) = GateReason("waiting_user", why, needsYou = true)

        /** The system asks the user to choose which one of [app] to open on the user screen; it does not say the name when the name cannot be read. */
        fun pickCopy(app: String?) = app?.let { tr(R.string.gate_pick_app, it) } ?: tr(R.string.gate_pick_system)

        /** The selected [app] is the sentence when the user needs to handle it personally. */
        fun finishCopy(app: String?) = app?.let { tr(R.string.gate_finish_app, it) } ?: tr(R.string.gate_finish_unknown)

        val MOVED_AWAY: String get() = tr(R.string.gate_moved_away)
        val MOVE_BACK_FAILED: String get() = tr(R.string.gate_move_back_failed)
        val SHELL_DOWN: String get() = tr(R.string.gate_shell_down)
        /** The reason why the user paused while physically working on the page lent to the task on the browser page (how the interface recognizes this pause). */
        val USER_ON_PAGE: String get() = tr(R.string.gate_user_on_page)

        /** The user pressed pause themselves. */
        val USER_PAUSED: String get() = tr(R.string.gate_user_paused)
    }
}
