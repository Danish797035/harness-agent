package com.meenai.mina.core.memory

import com.meenai.mina.core.agent.TokenEstimate

/** [message] is null, which means there is no memory to bring this time; [usedIds] is the items actually listed in the block, and the order is the same as the list. */
data class MemoryBlock(val message: String?, val usedIds: List<String>)

/**
 * Memory block for each task: placed as a user message after the conversation above and before the
 * original task text.
 *
 * Two ways to give:
 * - - [Mode.Relevant]: Top search results + search results that exceed the threshold (approved practices
 * are listed separately and have an upper limit), and the total length is subject to [MAX_TOKENS]. An
 * old practice with many memories.
 * - - [Mode.Full]: If you don’t give up one item, you will get all (the method is still subject to
 * [MAX_PROCEDURES]), and the total length is subject to [FULL_MAX_TOKENS].
 *
 * The default of the product is [Mode.Auto]: if the total memory does not exceed [FULL_MAX_TOKENS], the
 * full amount will be injected (the method will also be given), and it will be returned for retrieval if
 * the total memory exceeds [FULL_MAX_TOKENS]. The memory is not much (dozens of items, thousands of
 * words), but the current model context often costs hundreds of thousands; full injection will not leak,
 * the prefix is fixed, and it is cheaper to hit the context cache than "retrieval is different every
 * time". But when the memory grows to tens of thousands of words, the context will be eaten up when the
 * memory is full, so retrieval is retained as a retreat.
 */
object MemoryContext {
    const val MAX_TOKENS = 800
    /** The total upper limit of full injection; if it is exceeded, it means it is time to return to the search (see [Mode.Auto]). */
    const val FULL_MAX_TOKENS = 4_000
    const val MAX_PINNED = 5
    const val MAX_RELEVANT = 8
    const val MAX_PROCEDURES = 3
    const val MIN_SCORE = 0.5

    /** [Relevant]: pinned plus retrieved memories only; [Full]: all memories (dropping oldest when over budget); [Auto]: uses Full if within budget, otherwise falls back to [Relevant]. */
    enum class Mode { Relevant, Full, Auto }

    private const val FACTS_HEADER = "Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):"
    private const val PROCEDURES_HEADER = "Ways of doing things the user approved (when they conflict with what the user says now, follow what they say now):"

    /**
     * [rewrite]: Replace the old tool name mentioned in the method with the current one (see
     * LegacyNames.rewrite); the default is as is. [mode] keeps the old retrieval behavior by default;
     * the product path (AgentLoop) is explicitly passed to [Mode.Auto].
     */
    fun build(
        items: List<MemoryItem>,
        goal: String,
        lastUserTurn: String?,
        rewrite: (String) -> String = { it },
        mode: Mode = Mode.Relevant,
    ): MemoryBlock {
        val active = items.filter { it.status == MemoryStatus.active }
        if (active.isEmpty()) return MemoryBlock(null, emptyList())
        if (mode == Mode.Full) full(active, rewrite)?.let { return it }
        // Auto: If you can fit it in, just give it all; if you can’t fit it in (you already have a lot
        // of memories), return to search, and don’t cram tens of thousands of words into the context.
        if (mode == Mode.Auto) fullIfFits(active, rewrite)?.let { return it }

        val query = listOfNotNull(goal, lastUserTurn).joinToString("\n")
        val ranked = MemoryIndex(active).search(query, limit = active.size)
        val score = ranked.associate { it.first.id to it.second }

        // The upper limit of the method includes the top ones.
        var procedures = 0
        fun procedureFits(m: MemoryItem) = m.kind != MemoryKind.procedure || procedures < MAX_PROCEDURES

        val pinned = mutableListOf<MemoryItem>()
        for (m in active.filter { it.pinned }
            .sortedWith(compareByDescending<MemoryItem> { score[it.id] ?: 0.0 }.thenByDescending { it.updatedAt })) {
            if (pinned.size == MAX_PINNED) break
            if (!procedureFits(m)) continue
            if (m.kind == MemoryKind.procedure) procedures++
            pinned += m
        }
        val relevant = mutableListOf<MemoryItem>()
        for ((item, s) in ranked) {
            if (s < MIN_SCORE || relevant.size == MAX_RELEVANT) break
            if (item.pinned || !procedureFits(item)) continue
            if (item.kind == MemoryKind.procedure) procedures++
            relevant += item
        }

        // Keep the priority from high to low: top (according to score) first, relevant (according to
        // score) last; super long ones are removed from the end.
        val keep = (pinned + relevant).toMutableList()
        while (keep.isNotEmpty()) {
            val (message, ids) = render(keep, rewrite)
            if (TokenEstimate.of(message) <= MAX_TOKENS) return MemoryBlock(message, ids)
            keep.removeAt(keep.lastIndex)
        }
        return MemoryBlock(null, emptyList())
    }

    /**
     * Full quantity: top ones first, others in descending order of update time; maximum number of
     * methods is [MAX_PROCEDURES]. When [FULL_MAX_TOKENS] is exceeded, the oldest common entry is
     * discarded first, and then the method is discarded; if all are lost, null is returned (leaved to
     * retrieval).
     */
    private fun full(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (allFacts, allProcedures) = ordered(active)
        val facts = allFacts.toMutableList()
        val procedures = allProcedures.toMutableList()
        while (facts.isNotEmpty() || procedures.isNotEmpty()) {
            val (message, ids) = render(facts + procedures, rewrite)
            if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) return MemoryBlock(message, ids)
            if (facts.isNotEmpty()) facts.removeAt(facts.lastIndex) else procedures.removeAt(procedures.lastIndex)
        }
        return null
    }

    /**
     * Only the full amount can be loaded; if it cannot be loaded, null will be returned, and the caller
     * will return to retrieval. The method is consistent: cutting off the first few items based on the
     * update time will discard the ones related to this task (truncation is only done based on relevance
     * in the search).
     */
    private fun fullIfFits(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (facts, procedures) = ordered(active, maxProcedures = Int.MAX_VALUE)
        val (message, ids) = render(facts + procedures, rewrite)
        return if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) MemoryBlock(message, ids) else null
    }

    private fun ordered(active: List<MemoryItem>, maxProcedures: Int = MAX_PROCEDURES): Pair<List<MemoryItem>, List<MemoryItem>> {
        val sorted = active.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        return sorted.filter { it.kind != MemoryKind.procedure } to
            sorted.filter { it.kind == MemoryKind.procedure }.take(maxProcedures)
    }

    private fun render(items: List<MemoryItem>, rewrite: (String) -> String): Pair<String, List<String>> {
        val (procedures, facts) = items.partition { it.kind == MemoryKind.procedure }
        val sections = listOf(FACTS_HEADER to facts, PROCEDURES_HEADER to procedures).filter { it.second.isNotEmpty() }
        val message = sections.joinToString("\n\n") { (header, list) ->
            header + "\n" + list.withIndex().joinToString("\n") { (i, m) ->
                "${i + 1}. ${if (m.kind == MemoryKind.procedure) rewrite(m.content) else m.content}"
            }
        }
        return message to (facts + procedures).map { it.id }
    }
}
