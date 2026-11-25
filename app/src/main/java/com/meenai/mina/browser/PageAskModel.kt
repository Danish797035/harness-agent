package com.meenai.mina.browser

import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.task.TaskGate
import com.meenai.mina.task.TaskService.Companion.isActive

/** What is at the bottom of the browser page at the moment: the usual input bar, a half-screen dialogue panel, and a small folded card. */
enum class PageAskMode { Bar, Panel, Card }

/**
 * Ask Mina how to display the panel on the browser page (pure logic). [state] is the current status
 * of the task in this page's conversation (null if none); [open] is whether the panel is open
 * (opened when the user asks, taps the small card, or Mina needs a reply; closed when the user
 * collapses it or interacts with the page).
 * - - When the panel is open, it is the panel;
 * - - Collect it, that thing is not over yet: a small card at the bottom (saying what it is doing at the
 * moment); when you are working on the page and pause it, the card says "Paused", and the top one says
 * "You are operating, Mina has been paused·Continue";
 * - - The rest (no tasks, completed and put away): the usual input bar.
 */
object PageAskModel {
    fun mode(state: TaskState?, open: Boolean): PageAskMode = when {
        open -> PageAskMode.Panel
        state != null && state.isActive() -> PageAskMode.Card
        else -> PageAskMode.Bar
    }

    /** You work on the page yourself, and Mina waits for you to click "Continue". */
    fun pausedForUser(state: TaskState?): Boolean =
        state is TaskState.Held && state.reason.code == "paused" && state.reason.why == TaskGate.USER_ON_PAGE

    /** A light border lights up around the page: Mina is working (not waiting for you, not pausing). */
    fun glow(state: TaskState?): Boolean = state is TaskState.Running

    /** Mina wants you to reply (answer, agree, take over, move your hands): The panel opens by itself when entering here from other states. */
    fun needsYou(state: TaskState?): Boolean = when (state) {
        is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> true
        is TaskState.Held -> state.reason.needsYou
        else -> false
    }

    /**
     * How should the panel change when the status changes from [before] to [after]: true to open, false
     * to close, and null to remain unchanged. When you are doing something on the page and paused, it is
     * folded into a small card (getting out of the way of the page); when Mina wants you to reply, it is
     * opened.
     */
    fun follow(before: TaskState?, after: TaskState?): Boolean? = when {
        pausedForUser(after) && !pausedForUser(before) -> false
        needsYou(after) && !needsYou(before) -> true
        else -> null
    }
}
