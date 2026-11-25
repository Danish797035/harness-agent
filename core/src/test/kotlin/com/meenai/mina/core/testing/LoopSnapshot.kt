package com.meenai.mina.core.testing

import com.meenai.mina.core.agent.ContextBuilder
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * A snapshot of "what the model saw": system prompts for each request (summary), tool list, message by
 * message (role, context type and source, first line, full text summary). The first line makes it easy
 * to see the differences, and the summary ensures that any changed word will be discovered. The system
 * prompt words and tool list are only remembered when changed.
 *
 * The gold sample file is in core/src/test/resources/loop-snapshots/; regenerate:./gradlew:core:test
 * --tests '*LoopScenarioTest' -PupdateSnapshots
 */
object LoopSnapshot {
    private val dir = File(System.getProperty("mina.snapshotDir") ?: "src/test/resources/loop-snapshots")
    private val update = System.getProperty("mina.updateSnapshots") == "true"

    /** Compare with the gold sample; write directly if regeneration is enabled. */
    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("没有金样 ${file.path};用 ./gradlew :core:test --tests '*LoopScenarioTest' -PupdateSnapshots 生成")
        assertEquals("快照 $name 变了;确认是有意的改动后用 -PupdateSnapshots 重新生成", file.readText(), actual)
    }

    /** A snapshot of a scene: the ending, and then the requests received by each model (main model, support character). */
    fun render(outcome: String, models: List<Pair<String, FakeChatModel>>): String = buildString {
        appendLine("outcome: " + outcome.replace("\n", "⏎"))
        for ((role, m) in models) {
            var system: String? = null
            var tools: List<ToolSpec>? = null
            var previous: List<Message> = emptyList()
            m.requests.forEachIndexed { i, r ->
                appendLine()
                appendLine("## $role #${i + 1}")
                if (r.system != system) {
                    appendLine("system: len=${r.system.length} #${hash(r.system)} | ${oneLine(r.system, 80)}")
                    system = r.system
                }
                if (r.tools != tools) {
                    appendLine("tools: #${hash(r.tools.joinToString("\n") { it.name + "\n" + it.description + "\n" + it.parametersJsonSchema })} ${r.toolNames}")
                    tools = r.tools
                }
                // Only the number of entries that are the same as the last request will be recorded: the
                // request prefix will gradually increase. If it changes (compression, cropping), all
                // entries will be listed starting from the changed one.
                val same = r.messages.zip(previous).takeWhile { (a, b) -> a == b }.size
                if (same > 0) appendLine("… $same messages unchanged")
                r.messages.drop(same).forEach { append(message(it)) }
                previous = r.messages
            }
        }
    }

    private fun message(m: Message): String = buildString {
        appendLine("${m.role.name.lowercase()}:")
        for (p in m.parts) appendLine("  " + part(p))
    }

    private fun part(p: Part): String = when (p) {
        is Part.Text -> {
            val kind = ContextBuilder.kindOf(p.text)
            if (kind == null) "text #${hash(p.text)} | ${oneLine(p.text, 120)}"
            else {
                val source = p.text.substringAfter(" source=\"").substringBefore('"')
                val body = p.text.substringAfter(">\n")
                "ctx:${kind.wire} source=\"$source\" #${hash(p.text)} | ${oneLine(body, 120)}"
            }
        }
        is Part.Thinking -> "thinking #${hash(p.text)} | ${oneLine(p.text, 80)}"
        is Part.ToolCall -> "call ${p.id} ${p.name} ${oneLine(p.argsJson, 160)}" + (p.opaque?.let { " opaque" } ?: "")
        is Part.ToolResult -> "result ${p.callId}${if (p.isError) " error" else ""} #${hash(p.content)} | ${oneLine(p.content, 200)}"
        is Part.Image -> "image ${p.mime} ${p.width}x${p.height} shot=${p.screenshotId} #${hash(p.pngBase64)}"
    }

    private fun oneLine(s: String, max: Int): String {
        val first = s.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val more = s.trim().contains('\n')
        val clipped = if (first.length > max) first.take(max) + "…" else first
        return if (more && clipped == first) "$clipped ⏎…" else clipped
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
}
