package com.meenai.mina.settings

import com.meenai.mina.core.action.ApprovalSettings
import com.meenai.mina.core.gate.ConfirmationGate

/**
 * A task's own approval setting: whether it is autonomous or not is determined globally when starting
 * work, and then changes with the "autonomous mode" in the developer options ([set]). When asking, you
 * only ask for payment first; when you are independent, you don’t even ask for payment.
 */
class RunApproval(startAutonomous: Boolean) : ApprovalSettings {
    /** It is autonomous when starting work: there is no confirmation level for this task, and no request_confirmation is given; the autonomy is turned off midway and left to the next task. */
    val startedAutonomous: Boolean = startAutonomous
    @Volatile private var auto = startAutonomous

    override fun autonomous(): Boolean = auto

    fun set(autonomous: Boolean) {
        auto = autonomous
    }

    /** The payment confirmation level used by this task (and its subtasks) is only available upon request when starting work, and it is a new one each time. */
    fun gate(): ConfirmationGate? = if (startedAutonomous) null else ConfirmationGate()
}

/**
 * Query / Autonomy is a global switch, exists [ApprovalStore.autonomous], default query. When each task
 * starts, get your own copy ([start]); change the switch ([setAutonomous]): save it to manage the
 * subsequent tasks, and change the tasks you are running (change from inquiry to autonomy, and you will
 * no longer ask for payment from the next step).
 */
class ApprovalControl(private val store: ApprovalStore) {
    private val runs = mutableSetOf<RunApproval>()

    /**
     * When [remote] is true, it is a task handed over by the computer through MCP: regardless of the
     * switch, it will always start according to the query, and will not change according to the switch.
     * The payment step will never be done in this type of task (the review will reject all requests
     * initiated by non-mobile phones), and the remote end has no way to let it go.
     */
    @Synchronized fun start(remote: Boolean = false): RunApproval =
        if (remote) RunApproval(false) else RunApproval(store.autonomous.value).also { runs += it }

    @Synchronized fun end(run: RunApproval) {
        runs -= run
    }

    @Synchronized fun setAutonomous(on: Boolean) {
        store.setAutonomous(on)
        runs.forEach { it.set(on) }
    }
}
