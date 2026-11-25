package com.meenai.mina.core.agent

/**
 * Compression accounting of a model: whether this task has been changed to severe compression (the model
 * was reported to be too long), how many steps were folded when it was last issued, and what intensity
 * was used. Each model has one copy, and the compression of the lightweight model does not affect the
 * main model.
 */
internal data class CompactionState(val aggressive: Boolean = false, val lastFolded: Int = 0, val lastAggressive: Boolean = false)

/** Compression timing: when to record a compression event and whether to compress it harder before sending when the context is too long. Pure function. */
internal object CompactionPolicy {
    /** This time it was issued with [folded] steps folded. Accounting after returning, and whether to record a compression event (only when the number of folding steps or intensity changes). */
    fun sent(s: CompactionState, folded: Int): Pair<CompactionState, Boolean> {
        val report = folded > 0 && (folded != s.lastFolded || s.aggressive != s.lastAggressive)
        return s.copy(lastFolded = folded, lastAggressive = s.aggressive) to report
    }

    /**
     * The model reported that the context was too long. It will only be re-sent once for every
     * over-length: if [retried] is true (this call has already been re-sent due to over-length), it will
     * not be sent again; otherwise, [shrinks] will be re-sent from the complete history, and the re-send
     * will be smaller than the one just sent (it does not matter if the history is longer after the last
     * hard press). Then compress it very hard.
     */
    fun overflow(s: CompactionState, retried: Boolean, shrinks: () -> Boolean): Pair<CompactionState, Boolean> =
        s.copy(aggressive = true) to (!retried && shrinks())
}
