package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Some models spit out tool call markers that should go through the `tool_calls` channel directly into
 * the text: DSML (`<｜DSML｜invoke name="…">…`) of DeepSeek V3.2 / V4, and `<tool_call>{…}</tool_call>`
 * such as Qwen. If you hand it over to the interface as it is, there will be a bunch of `<｜｜DSML｜｜
 * calls>` garbled characters, and it will be recorded in history as text.
 *
 * Two things are done here, the order is to restore first and then strip off: 1. Restore the ones that
 * can be restored to the tool call (the parameters are converted into strings or JSON values according
 * to the `string` flag), and hand them over to the tool engine for execution as usual; 2. For the DSML
 * that cannot be restored, only the tags are stripped off, leaving the text outside the tags; the
 * `<tool_call>` blocks that cannot be restored are left as they are, and are used by AgentLoop The
 * missed call check (LeakedToolCall) is returned once and is not handed over as an answer.
 *
 * It only changes when the mark actually appears in the text. Normal answers do not change a single
 * character (see the changed judgment of [repair]). The `<tool_call>` blocks written in pairs in the
 * answer and containing legal JSON calls will be treated as calls. This is an intentional trade-off: the
 * cost of displaying garbled characters to the user is greater; when a tool that does not exist is
 * adjusted, the tool engine will report an error as usual.
 */
object LeakedToolMarkup {
    data class Invocation(val name: String, val argsJson: String)

    /** [text] is the text remaining after stripping off the mark; [calls] is the tool call restored from the mark (may be empty). */
    data class Scan(val text: String, val calls: List<Invocation>)

    /** There are two ways to write vertical lines: full-width `｜` and half-width `|`. When leaked, it will be written as one or two. */
    private const val B = "[｜|]"

    private fun bars(prefix: String) = prefix + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}"

    private val INVOKE = Regex(
        bars("<") + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*invoke\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAM = Regex(
        bars("<") + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" +
            B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*parameter\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The remaining pair/single marks (outer `calls`, `function_calls`, etc.) will be removed. */
    private val TAG = Regex("</?" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}[^>]*>")

    /** Qwen Category 1: `<tool_call>{"name":…,"arguments":…}</tool_call>`. */
    private val TOOL_CALL_BLOCK = Regex(
        "<tool_call>\\s*(.*?)\\s*</tool_call>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PIPE_TOOL_CALL_BLOCK = Regex(
        "<" + B + "{1,2}tool_call" + B + "{1,2}\\s*>(.*?)</" + B + "{1,2}tool_call" + B + "{1,2}\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val FENCED_TOOL_CALLS_BLOCK = Regex(
        "```(?:json)?\\s*(\\{[\\s\\S]*?\"(?:tool_calls|name)\"[\\s\\S]*?\\})\\s*```",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val RAW_TOOL_CALLS_BLOCK = Regex(
        "(?:^|\\n)\\s*(\\{[\\s\\S]*?\"tool_calls\"\\s*:\\s*\\[[\\s\\S]*?\\][\\s\\S]*?\\})\\s*(?:$|\\n)",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Common in streaming: the mark has just started (`<｜`, `<｜｜DSML`, `<｜｜DSML｜｜ invoke name="pho`).
     * Only half tags starting with a vertical bar and followed by the first few letters of DSML are
     * recognized, which do not match normal text (`a<b This paragraph talks about DSML` does not count).
     */
    private val PARTIAL_TAIL = Regex("^/?" + B + "{1,2}\\s*(?:D(?:S(?:M(?:L.*)?)?)?)?$", RegexOption.DOT_MATCHES_ALL)

    /** For streaming preview: Every time a frame comes, peel off the part of the mark that has been received, so as not to let the user watch the garbled code flash past. */
    fun stripMarkup(text: String): String = scan(text).text

    /** Scan a paragraph of text: peel off the mark and restore the call. If there is no mark, it will be returned as is and the call will be empty. */
    fun scan(text: String): Scan {
        if (text.isEmpty()) return Scan(text, emptyList())
        var rest = text
        val calls = mutableListOf<Invocation>()

        // 1. DSML: First press invoke to extract the whole block, and press parameter to extract the
        // parameters.
        if (rest.contains("DSML", ignoreCase = true)) {
            for (m in INVOKE.findAll(rest)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                calls += Invocation(name, paramsJson(m.groupValues[2]))
            }
            if (calls.isNotEmpty()) rest = rest.replace(INVOKE, " ")
            rest = TAG.replace(rest, " ")
        }

        // 2. <tool_call>{...}</tool_call> (it is JSON, which may be an object or a group)
        rest = extractJsonBlocks(rest, TOOL_CALL_BLOCK, calls)
        rest = extractJsonBlocks(rest, PIPE_TOOL_CALL_BLOCK, calls)
        rest = extractJsonBlocks(rest, FENCED_TOOL_CALLS_BLOCK, calls)
        if (rest.contains("\"tool_calls\"")) {
            rest = extractJsonBlocks(rest, RAW_TOOL_CALLS_BLOCK, calls)
        }

        // Streaming preview: Don’t show the half-marks (<｜｜DSML｜, <｜...) that are not fully confiscated
        // at the end.
        val lt = rest.lastIndexOf('<')
        if (lt >= 0) {
            val tail = rest.substring(lt + 1)
            if (!tail.contains('>') && PARTIAL_TAIL.matches(tail)) {
                rest = rest.substring(0, lt)
            }
        }
        if (calls.isEmpty() && rest == text) return Scan(text, emptyList())
        // The empty lines left after the mark is peeled off are cleaned up; the line breaks inside the
        // text are not changed.
        rest = rest.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return Scan(rest, calls)
    }

    /** Clean the text in a reply; add [Part.ToolCall] when restoring the call, and mark it as ToolUse when it ends. */
    fun repair(reply: ModelReply): ModelReply {
        if (reply.parts.none { it is Part.Text || it is Part.Thinking }) return reply
        val out = mutableListOf<Part>()
        val calls = mutableListOf<Part.ToolCall>()
        var changed = false
        for (p in reply.parts) {
            if (p is Part.Thinking) {
                // Marks leaked from the thinking content are only peeled off and not regarded as calls:
                // calls can only be recognized from the text.
                val cleaned = stripMarkup(p.text)
                if (cleaned != p.text) changed = true
                if (cleaned.isNotEmpty()) out += Part.Thinking(cleaned)
                continue
            }
            if (p !is Part.Text) {
                out += p
                continue
            }
            val scan = scan(p.text)
            if (scan.text != p.text || scan.calls.isNotEmpty()) changed = true
            if (scan.text.isNotEmpty()) out += Part.Text(scan.text)
            scan.calls.forEach { c -> calls += Part.ToolCall(callId(c, calls.size), c.name, c.argsJson) }
        }
        if (!changed) return reply
        out += calls
        return ModelReply(out, if (calls.isEmpty()) reply.stopReason else StopReason.ToolUse, reply.usage)
    }

    /** ParameterBlock → ToolParameters JSON. `string="false"` is collected as JSON value, and the rest is collected as string. */
    private fun paramsJson(block: String): String {
        if (PARAM.find(block) == null) return "{}"
        return buildJsonObject {
            for (m in PARAM.findAll(block)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                val raw = m.groupValues[3]
                val isString = m.groupValues[2] != "false"
                put(name, if (isString) JsonPrimitive(raw) else jsonValue(raw))
            }
        }.toString()
    }

    private fun jsonValue(raw: String) = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        JsonPrimitive(raw)
    }

    /** The name + parameters are used to create a stable call number (differentiated by serial number in the same message), without random numbers, which is convenient for single testing. */
    private fun callId(c: Invocation, index: Int): String =
        "leaked_" + Integer.toHexString((c.name + "\u0000" + c.argsJson).hashCode()) + "_" + index

    private fun extractJsonBlocks(text: String, block: Regex, calls: MutableList<Invocation>): String {
        // Only peel off the blocks that can restore the call; leave the blocks that cannot be restored
        // as they are, and return them to AgentLoop's leaked call check (LeakedToolCall) to avoid being
        // handed over as the final answer after peeling off only the previous sentence "I'll check it
        // out."
        return block.replace(text) { m ->
            val found = invocationsOf(m.groupValues[1])
            if (found.isEmpty()) m.value else " ".also { calls += found }
        }
    }

    private fun invocationsOf(body: String): List<Invocation> {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val items = when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> {
                val toolCallsArr = element["tool_calls"] as? JsonArray
                if (toolCallsArr != null) {
                    toolCallsArr.toList()
                } else {
                    listOf(element)
                }
            }
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val fnObj = obj["function"] as? JsonObject
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: (fnObj?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
                ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val args = obj["arguments"] ?: obj["parameters"] ?: fnObj?.get("arguments")
            Invocation(name, when (args) {
                null -> "{}"
                is JsonPrimitive -> args.contentOrNull ?: "{}"
                else -> args.toString()
            })
        }
    }
}
