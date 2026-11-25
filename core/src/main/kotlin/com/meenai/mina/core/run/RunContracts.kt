package com.meenai.mina.core.run

import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.core.agent.Turn

@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/** Immutable opening information for one run. Subsequent steps will include capabilities, resource budgets, and approval snapshots. */
data class RunSpec(
    val id: RunId,
    val goal: String,
    val threadId: String,
    val priorTurns: List<Turn> = emptyList(),
    val provenance: RunProvenance = RunProvenance(),
    /** null indicates default capabilities; sub-Run can give strict subsets. */
    val capabilities: Set<String>? = null,
    /** Conditional check Run only allows reading; the exception is web navigation as a preparatory action to obtain the read object. */
    val readOnly: Boolean = false,
    val parent: RunId? = null,
    val stepBudget: Int? = null,
) {
    init { require(goal.isNotBlank() && threadId.isNotBlank()) }
}

/** Input is given to the run from this session. If you cancel the control channel, you will not wait in the interrupt queue. */
sealed interface RunInput {
    /**
     * The interjection is a supplement: Cancel the model request that is still waiting, and ask again
     * after the existing history (retain all the ones that have been done); the tool being executed is
     * completed as usual, the results are remembered, and the waiting ends early without losing anything
     * (see [waitOrSteer]).
     */
    data class Steer(val messageId: String, val text: String) : RunInput
    /** Take the same road as [Steer] (the name of the old entrance). */
    data class Redirect(val messageId: String, val text: String) : RunInput
    data class Answer(val text: String, val askId: String? = null) : RunInput
    data class Confirm(val choice: ConfirmChoice, val askId: String? = null) : RunInput
    data class HandBack(val result: com.meenai.mina.core.tool.HandBack, val askId: String? = null) : RunInput
    data object Cancel : RunInput
}

enum class InputReceipt { Accepted, NoPendingInteraction, NextTask, Closed }

sealed interface RunPhase {
    data object Queued : RunPhase
    data object Preparing : RunPhase
    data object Running : RunPhase
    data class WaitingUser(val askId: String? = null) : RunPhase
    data class Parked(val askId: String) : RunPhase
    data class Paused(val reason: String) : RunPhase
    data object Finishing : RunPhase
    data object Aftercare : RunPhase
    data object Ended : RunPhase
    data object Interrupted : RunPhase
}
