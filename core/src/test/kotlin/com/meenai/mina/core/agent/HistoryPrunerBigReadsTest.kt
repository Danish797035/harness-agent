package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.TOOLS

/** Large results other than screen reading (reading web pages, command output, reading files...) are cut according to the same cutting line: only short fields are left in front of the line. */
class HistoryPrunerBigReadsTest {
    private fun round(id: String, tool: String, content: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, tool, "{}")), Author.Mina),
        Message(Role.Tool, listOf(Part.ToolResult(id, content, false)), Author.Mina),
    )

    private fun page(i: Int, chars: Int, filler: String) = buildJsonObject {
        put("ok", true); put("url", "https://example.com/$i"); put("text", filler.repeat(chars / filler.length)); put("nextOffset", chars)
    }.toString()

    private fun results(ms: List<Message>) = ms.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()

    @Test fun fivePageReadsOf30kFitA64kBudget() {
        val history = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mina)) +
            (1..5).flatMap { round("c$it", "web_read_page", page(it, 30_000, "网页正文内容")) }
        assertTrue(TokenEstimate.of(history) > 64_000)
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 64_000).messages
        assertTrue("estimate ${TokenEstimate.of(out)}", TokenEstimate.of(out) < 64_000)
        val rs = results(out)
        // Only shortening the old results is over: the old read only has short fields (you can press
        // url/nextOffset to read again) or fold in the summary; the latest one is as it is
        for (r in rs.dropLast(1)) {
            val o = Json.parseToJsonElement(r.content) as JsonObject
            assertEquals(HistoryPruner.OMITTED_READ, o["text"]!!.jsonPrimitive.content)
            assertEquals(30_000, o["nextOffset"]!!.jsonPrimitive.content.toInt())
            assertTrue(o["url"]!!.jsonPrimitive.content.startsWith("https://"))
        }
        assertEquals(results(history).last().content, rs.last().content)
    }

    @Test fun sameRuleForEveryReadingToolAndReferenceIsKept() {
        val big = "x".repeat(10_000)
        val history = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mina)) +
            round("a", "knowledge_read", buildJsonObject { put("content", big) }.toString()) +
            round("b", "run_command", buildJsonObject { put("output", big); put("exitCode", 0) }.toString()) +
            round("c", "read_file", buildJsonObject { put("content", big); put("path", "/workspace/a.txt"); put("nextOffset", 10) }.toString()) +
            round("d", "web_evaluate_js", buildJsonObject { put("result", JsonObject(mapOf("rows" to JsonPrimitive(big)))) }.toString()) +
            round("e", "run_bundled_script", "plain text output " + big) +
            round("f", "search_files", buildJsonObject { put("matches", big) }.toString()) +
            round("g", "phone_tap", "{\"ok\":true}")
        val rs = results(HistoryPruner.prune(history, TOOLS, HistoryPruner.Cut(2))).associateBy { it.callId }
        assertEquals(big, (Json.parseToJsonElement(rs["a"]!!.content) as JsonObject)["content"]!!.jsonPrimitive.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["b"]!!.content && "\"exitCode\":0" in rs["b"]!!.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["c"]!!.content && "/workspace/a.txt" in rs["c"]!!.content)
        // 7 round trips: After the 5th cutting line, all b–e in front of the line are cut (except for
        // reference a), and f after the line remains as it is.
        assertTrue(HistoryPruner.OMITTED_READ in rs["d"]!!.content)
        assertEquals(HistoryPruner.OMITTED_READ, rs["e"]!!.content)
        assertEquals(results(history).first { it.callId == "f" }.content, rs["f"]!!.content)
        // Small results don’t move
        val small = listOf(Message(Role.User, listOf(Part.Text("t")), Author.Mina)) + (1..9).flatMap { round("s$it", "run_command", "{\"output\":\"ok\"}") }
        assertEquals(small, HistoryPruner.prune(small, TOOLS, HistoryPruner.Cut(2)))
    }
}
