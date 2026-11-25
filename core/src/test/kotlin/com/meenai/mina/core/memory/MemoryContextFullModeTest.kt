package com.meenai.mina.core.memory

import com.meenai.mina.core.agent.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory is fully injected (the product's default: if it can fit, it will not be left out, and if it
 * cannot fit, it will be returned to the search).
 */
class MemoryContextFullModeTest {
    @Test fun fullGivesEveryActiveItem() {
        // It has nothing to do with the word goal and would have been discarded by searches in the past.
        val items = (1..12).map { mem("无关事实第${it}条:用户周末去爬山") }
        val block = MemoryContext.build(items, goal = "帮我点个外卖", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertEquals(items.map { it.id }.toSet(), block.usedIds.toSet())
        assertEquals(12, block.usedIds.size)
    }

    @Test fun autoGivesEveryItemWhenItFits() {
        val items = (1..30).map { mem("事实第${it}条") }
        val block = MemoryContext.build(items, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertEquals(30, block.usedIds.size)
    }

    @Test fun autoFallsBackToRelevantWhenTooBig() {
        val filler = "很".repeat(120)
        // Only the first 8 items have a takeaway: they should be picked out when returned to the search,
        // rather than not including any of them just because they all contain the same word.
        val items = (1..120).map { mem("第${it}条$filler", keywords = if (it <= 8) listOf("外卖") else emptyList()) }
        val block = MemoryContext.build(items, goal = "点外卖", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertTrue(block.usedIds.size in 1..MemoryContext.MAX_RELEVANT)
        val message = block.message
        assertNotNull(message)
        assertTrue(TokenEstimate.of(message!!) <= MemoryContext.MAX_TOKENS)
    }

    @Test fun fullDropsOldestFactsBeforePinned() {
        val filler = "很".repeat(120)
        val pinned = mem("置顶的住址在张江$filler", pinned = true)
        val facts = (1..80).map { mem("第${it}条$filler") }
        val block = MemoryContext.build(facts + pinned, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertTrue(pinned.id in block.usedIds)
        assertTrue(TokenEstimate.of(block.message!!) <= MemoryContext.FULL_MAX_TOKENS)
        assertTrue(block.usedIds.size < facts.size)
    }

    @Test fun pendingNeverInjected() {
        val pending = mem("还没批准的做法", kind = MemoryKind.procedure, status = MemoryStatus.pending)
        assertNull(MemoryContext.build(listOf(pending), "随便", null, mode = MemoryContext.Mode.Full).message)
    }

    // When the automatic mode can be installed, all the methods are given: cut off the first few entries
    // according to the update time, and you may just throw away the one that is useful this time.
    @Test fun autoGivesEveryProcedureWhenItFits() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Auto)
        assertEquals(procs.map { it.id }.toSet(), block.usedIds.toSet())
    }

    @Test fun fullKeepsProceduresCapped() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Full)
        assertEquals(MemoryContext.MAX_PROCEDURES, block.usedIds.size)
    }
}
