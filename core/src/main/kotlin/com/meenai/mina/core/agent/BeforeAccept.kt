package com.meenai.mina.core.agent

/**
 * The moment before a reply without a call is accepted as the final answer (the seam): the outside can
 * also insert a sentence here, such as another message from the user while the task is in progress.
 * Accept [Accept] as usual (check again); do not accept [Continue], give [note] to the model as a
 * reminder, and continue the task.
 */
sealed interface BeforeAccept {
    data object Accept : BeforeAccept
    data class Continue(val note: String) : BeforeAccept
}
