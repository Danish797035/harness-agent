package com.meenai.mina.core.verify

import com.meenai.mina.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedVerificationsTest {
    private var t = 0L
    private fun reply(step: Int, tool: String?, answer: String = "a$step") =
        RunEvent.ModelReply(++t, step, "", tool, if (tool == "finish") """{"answer":"$answer"}""" else "{}", null, null, null)
    private fun result(step: Int, tool: String) = RunEvent.ToolResult(++t, step, tool, """{"ok":true}""", null, null)
    private fun verifier(step: Int? = null) = RunEvent.Verifier(++t, "fail", "{}", step)

    // Ordinary step: verification is recorded before the finish reply; final round: is recorded after.
    // Even if the two orders are mixed in one record, each has its own place.
    @Test fun legacyRecordsPairEitherOrder() {
        val ev = listOf(
            reply(1, "phone_observe"), result(1, "phone_observe"),
            verifier(), reply(2, "finish"), result(2, "finish"),
            reply(3, "phone_observe"), result(3, "phone_observe"),
            reply(4, "finish"), result(4, "finish"), verifier(),
        )
        assertEquals(listOf(2 to 3, 9 to 7), RecordedVerifications.pairs(ev))
    }

    // Two finishes next to each other: the one that checks first and then replies will not be assigned
    // to the previous finish.
    @Test fun backToBackFinishesDoNotSwap() {
        val ev = listOf(verifier(), reply(1, "finish"), result(1, "finish"), verifier(), reply(2, "finish"), result(2, "finish"))
        assertEquals(listOf(0 to 1, 3 to 4), RecordedVerifications.pairs(ev))
    }

    @Test fun stepPairsRegardlessOfPosition() {
        // With step: the last round of verification (after the reply) is followed by another finish and
        // the matching is correct.
        val ev = listOf(reply(1, "finish"), result(1, "finish"), verifier(1), reply(2, "finish"), verifier(2))
        assertEquals(listOf(2 to 0, 4 to 3), RecordedVerifications.pairs(ev))
    }

    @Test fun evidenceExcludesTheCheckedFinishItself() {
        val ev = listOf(reply(1, "phone_observe"), result(1, "phone_observe"), reply(2, "finish"), result(2, "finish"), verifier(2))
        val (v, f) = RecordedVerifications.pairs(ev).single()
        assertEquals(listOf(ev[0], ev[1]), RecordedVerifications.before(ev, v, f))
    }
}
