package com.meenai.mina.core.action

import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.RunProvenance

/**
 * The risk level of an action (recorded in the approval record, and verified based on this to know that
 * the task has done external things). [Read] only sees or moves between interfaces; [Change] changes
 * things on the interface (clicking, typing); [External] gives things to others (send, submit);
 * [Irreversible] cannot take back what is done (payment, ordering, deletion); [Unknown] cannot see what
 * is clicked.
 */
enum class ActionRisk { Read, Change, External, Irreversible, Unknown;

    /** Will affect the world outside the screen (or not sure if it will). */
    val outward: Boolean get() = this == External || this == Irreversible || this == Unknown
}

/** When [payment] is true, this step costs money (payment, ordering, or external actions when there is an amount due on the screen): only it asks the user first when asking. */
data class Risk(val level: ActionRisk, val payment: Boolean = false, val alwaysAsk: Boolean = false)

/** The user's choice on the confirmation card: Agree this time, or disagree. */
enum class ConfirmChoice { Once, Deny;

    val approved: Boolean get() = this != Deny
}

/** Ask/Autonomous: Is it in autonomous mode at this moment (when it is switched to autonomous mode midway, the running task will follow it from the next step). Provided by the settings, it is changed to a fixed value in the test. */
fun interface ApprovalSettings {
    /** Autonomous mode: Don’t even ask for payment first. */
    fun autonomous(): Boolean

    companion object {
        /** Ask (Default): Only payment will be asked first. */
        val ASK = ApprovalSettings { false }
    }
}

sealed interface Approval {
    /** No need to ask. */
    data object Allow : Approval
    /** The user's consent is required this time (if there is a one-time consent, check it and use it). */
    data object Ask : Approval
    /** This operation cannot ask for user consent, and the action must be stopped. */
    data object Deny : Approval
}

/** Review and Determination: Pure function. Only payment and tools marked [Risk.alwaysAsk] need to be asked; the question will not be done when no one is available (unattended, sub-task) or not on the mobile phone (channel, scheduled, MCP initiated). */
object ApprovalPolicy {
    fun decide(risk: Risk, run: RunProvenance = RunProvenance()): Approval = when {
        risk.alwaysAsk -> if (run.attendance == Attendance.Unattended || run.origin != RunOrigin.Ui) Approval.Deny else Approval.Ask
        !risk.payment -> Approval.Allow
        run.attendance == Attendance.Unattended || run.origin != RunOrigin.Ui -> Approval.Deny
        else -> Approval.Ask
    }
}
