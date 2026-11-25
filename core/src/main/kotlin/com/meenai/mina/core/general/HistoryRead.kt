package com.meenai.mina.core.general

import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Trust
import com.meenai.mina.core.observation.Untrusted
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunRecords
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.okObj
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val HISTORY_READ = "history_read"

/**
 * Check the records of the previous rounds of this conversation: without turn, list the previous rounds
 * (the new one first: which round, what the user said, the ending, the time), with turn, read the things
 * recorded in that round (the complete ending, Mina's progress description, the original text read), and
 * paginate by article. Only the rounds brought about by the start of this run
 * ([com.meenai.mina.core.run.RunSpec.priorTurns], the same conversation) are recognized, read-only; the
 * original text is taken from the run record (desensitized and truncated when placing the disk), without
 * screenshots. The original text read is submitted for verification as an observation and can be quoted
 * in the answer. Only provided if a run record is given and a previous run was recorded.
 */
internal object HistoryRead : AgentTool {
    /** The list can be made in at most several rounds (newest first). */
    const val LIST_MAX = 20
    /** Approximately how many words do the original texts on a page add up to (give at least one). */
    const val PAGE_CHARS = 8_000
    /** How many words can be left in an original text at most. */
    const val ITEM_CHARS = 2_000
    /** How many words can be left in the ending during one round of reading. */
    const val OUTCOME_CHARS = 3_000
    /** How many words should be left in the list for the user's original words and the ending. */
    private const val GIST_CHARS = 200

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)

    override val verb = Verb.HistoryRead
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(HISTORY_READ,
        Prompts.tool(HISTORY_READ, "page" to PAGE_CHARS),
        mapOf(
            "turn" to int("Which earlier turn to read: 1 = the turn just before this one, 2 = the one before that, and so on. Leave out to list the earlier turns.", 1),
            "offset" to int("Item to start from, from nextOffset of the previous call (default 0).", 0),
        ), emptyList())

    override fun available(ctx: ToolContext): Boolean =
        ctx.config.runRecords != null && ctx.config.runSpec?.priorTurns.orEmpty().any { it.runId != null }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val turns = ctx.config.runSpec!!.priorTurns.asReversed()
        val records = ctx.config.runRecords!!
        val n = a.int("turn", 1, Int.MAX_VALUE)
        val offset = a.int("offset", 0, Int.MAX_VALUE) ?: 0
        if (n == null) return list(turns, records)
        val turn = turns.getOrNull(n - 1)
            ?: return fail("turn_not_found", Feedback.format("history.turn_not_found", "count" to turns.size))
        return read(n, turn, records, offset)
    }

    private suspend fun list(turns: List<Turn>, records: RunRecords): ToolExecution {
        val shown = turns.take(LIST_MAX)
        val zone = ZoneId.systemDefault()
        val summaries = shown.map { t -> t.runId?.let { id -> safe(null) { records.summary(id) } } }
        return ToolExecution(okObj {
            put("turns", JsonArray(shown.mapIndexed { i, t ->
                val summary = summaries[i]
                buildJsonObject {
                    put("turn", i + 1)
                    put("said", clip(t.userText, GIST_CHARS))
                    put("status", t.status)
                    put("outcome", clip(t.outcome, GIST_CHARS))
                    summary?.let { put("time", time(it.startedAtMs, zone)) }
                    put("recorded", summary != null)
                }
            }))
            if (turns.size > shown.size) put("older", turns.size - shown.size)
        })
    }

    private suspend fun read(n: Int, t: Turn, records: RunRecords, offset: Int): ToolExecution {
        val id = t.runId
        val summary = id?.let { safe(null) { records.summary(it) } }
        val items = if (summary != null) items(safe(emptyList()) { records.events(id) }) else emptyList()
        val page = mutableListOf<Item>()
        var chars = 0
        for (item in items.drop(offset)) {
            if (page.isNotEmpty() && chars + item.text.length > PAGE_CHARS) break
            page += item
            chars += item.text.length
        }
        val next = offset + page.size
        val content = okObj {
            put("turn", n)
            put("said", t.userText)
            put("status", t.status)
            put("outcome", clip(t.outcome, OUTCOME_CHARS))
            if (t.steers.isNotEmpty()) put("userAddedDuringTask", JsonArray(t.steers.map { JsonPrimitive(clip(it, GIST_CHARS)) }))
            summary?.let { put("time", time(it.startedAtMs, ZoneId.systemDefault())) }
            put("recorded", summary != null)
            if (summary == null) put("message", Feedback["history.not_recorded"])
            else {
                put("totalItems", items.size)
                put("items", JsonArray(page.mapIndexed { i, item ->
                    buildJsonObject {
                        put("n", offset + i)
                        put("kind", item.kind)
                        item.where?.let { put("where", it) }
                        put("text", if (item.untrusted) Untrusted.wrap(item.text) else item.text)
                    }
                }))
                if (next < items.size) put("nextOffset", next)
            }
        }
        // The original text read is submitted for verification: when the answer uses something read in
        // previous rounds, it is well documented (the source is also recorded as history, and does not
        // refer back to the scene at that time)
        val observations = page.mapNotNull { item ->
            item.obsKind?.let { Observation(it, item.text, source = SOURCE, title = item.where, trust = if (item.untrusted) Trust.Untrusted else Trust.Trusted) }
        }
        return ToolExecution(content, observations = observations)
    }

    /** One readable thing in the record: Mina's progress description (note, words in the reply with the call), or the original text read (type of observation). */
    private class Item(val kind: String, val text: String, val where: String?, val untrusted: Boolean, val obsKind: ObsKind?)

    private fun items(events: List<RunEvent>): List<Item> {
        val seen = mutableSetOf<String>()
        return events.mapNotNull { e ->
            when (e) {
                // The reply without the call is the answer of that round, which is already in the
                // outcome.
                is RunEvent.ModelReply -> e.text.trim().takeIf { e.acted && e.tool != null && it.isNotEmpty() && seen.add(it) }
                    ?.let { Item(NOTE, clip(it, ITEM_CHARS), null, false, null) }
                is RunEvent.Observed -> e.text.trim().takeIf { it.isNotEmpty() && seen.add(it) }?.let {
                    val o = e.observation()
                    Item(o.kind.wire, clip(it, ITEM_CHARS), o.title ?: o.place?.name, e.untrusted, o.kind)
                }
                else -> null
            }
        }
    }

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    /** If there is an error when reading the record: If it cannot be found, it will not affect the task. */
    private suspend fun <T> safe(fallback: T, read: suspend () -> T): T =
        try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { fallback }

    private const val NOTE = "note"
    private const val SOURCE = "history"
}
