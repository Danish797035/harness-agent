package com.meenai.mina.core.verify

import com.meenai.mina.core.record.RunEvent
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Offline recalculation: Recheck the recorded run with the current [Grounding] (without adjusting the
 * model). Skip if no directory is set.
 *
 * MINA_RESCORE_DIR=/path/to/runs./gradlew:core:test --tests '*RescoreRecordedRuns*' --rerun
 *
 * Recursively search for all events.jsonl in the directory; each verification (verifier event) presses
 * [RecordedVerifications] with the finish it verified (regardless of the order) to get the answer. The
 * evidence uses all the screen reading text before verification and all the strings in the tool results
 * (excluding the finish itself), and the relative date is converted according to the time of that
 * verification (the time zone is MINA_RESCORE_ZONE, not the local one). The result is written as
 * <Contents>/rescore.tsv: operation, conclusions and reasons at that time, current conclusions, and facts that
 * cannot be found.
 */
class RescoreRecordedRuns {
    @Test fun rescore() {
        val dir = (System.getenv("MINA_RESCORE_DIR") ?: System.getProperty("mina.rescore.dir"))?.let(::File)
        assumeTrue("MINA_RESCORE_DIR not set", dir != null && dir.isDirectory)
        val zone = System.getenv("MINA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val rows = mutableListOf("run\tthen\tthen_reason\tnow\tnot_found")
        dir!!.walk().filter { it.name == "events.jsonl" }.sorted().forEach { f ->
            val events = f.readLines().mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
            val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal.orEmpty()
            for ((i, fi) in RecordedVerifications.pairs(events)) {
                val e = events[i] as RunEvent.Verifier
                val answer = RecordedVerifications.answer(events, fi) ?: continue
                val g = Grounding.check(goal, answer, RecordedVerifications.sources(events, i, fi),
                    java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate())
                val now = when (g.found) { true -> "found"; false -> "not_found"; null -> "nothing_checkable" }
                val thenReason = RecordedVerifications.arg(e.detailJson, "reason").orEmpty()
                rows += listOf(f.parentFile.name, e.verdict, thenReason, now, g.unmatched.joinToString(" | ") { "${it.kind}:${it.text}" })
                    .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            }
        }
        File(dir, "rescore.tsv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
