package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.observation.ObsForm
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb

/**
 * Cut out outdated content when compressing: the old interface is no longer the status quo, and the old
 * large results can be reread by position, leaving them will only expand the context. Only call it once
 * when [ContextCompressor] is compressed (normally sent messages are sent as they are); only a copy is
 * returned, and the complete history saved by the caller remains unchanged; the call is paired with the
 * id of the result, and other messages are retained as they are.
 *
 * Press a cutting line ([Cut]): the round trip after the line remains as it is, and the round trip
 * before the line is shortened ([ObsForm], see [BY_FORM]): the interface status (reading interface,
 * interface brought back by the action, and element text viewed back) are all replaced by [OMITTED];
 * page content, command output, files, and replies are only replaced with long fields when the result is
 * large (more than [BIG_RESULT_TOKENS] tokens) [OMITTED_READ];What users say (memory) and reference
 * materials are not cut off. The results without observation are recognized by actions. The shortened
 * result retains the ending (ok, error and reason) and the observation number ([OBS_IDS]) pointing back
 * to the evidence ledger. The most recent full interface before the line is not cut: subsequent actions
 * and differences must be understood through it. The picture before the line is replaced with a
 * placeholder (the interface screen is [olderImage] that can be viewed back, and the picture file read
 * is [OMITTED_IMAGE]); the user's words and photos are not cropped.
 *
 * The service provider has a clear upper limit for pictures ([limitImages]). If it exceeds the limit,
 * the placeholder will be replaced from the earliest assistant picture.
 */
object HistoryPruner {
    const val OMITTED = "(screen read earlier, omitted; read it again if needed)"
    const val OMITTED_IMAGE = "(old image omitted)"

    /** A placeholder for an earlier interface screen. */
    fun olderImage(imageId: Int) = "(earlier screen image omitted; to see it again use view_past_screens with ids [$imageId])"
    const val OMITTED_READ = "(content read earlier, omitted; read it again with nextOffset, path and so on if needed)"
    /** The shortened result refers back to the observation number of the evidence ledger. */
    const val OBS_IDS = "obsIds"
    /**
     * Tool results exceeding this many tokens are considered "big results". The tool results are mostly
     * in English and JSON (about 4 characters per token). Taking the number of tokens corresponding to
     * the original line of 4000 characters, the English results will be cut unchanged; the Chinese
     * results will be calculated based on actual occupancy, which will be calculated earlier than the
     * original.
     */
    const val BIG_RESULT_TOKENS = 1_000

    /** Various observations before the line how to shorten. */
    private enum class Shrink { Interface, IfBig, Never }

    /** The shortening method is determined according to the observed form: the interface status is all shortened, and the page, command output, file, reply, and fact are only shortened when they are large, and will not be shortened according to the user's instructions. */
    private val BY_FORM = mapOf(
        ObsForm.Interface to Shrink.Interface,
        ObsForm.Page to Shrink.IfBig, ObsForm.Command to Shrink.IfBig, ObsForm.File to Shrink.IfBig,
        ObsForm.Reply to Shrink.IfBig, ObsForm.Fact to Shrink.IfBig, ObsForm.Image to Shrink.IfBig,
        ObsForm.UserSaid to Shrink.Never,
    )

    /** The model is made according to the reference: not cut to size. */
    private val REFERENCE_VERBS = setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.MemorySearch)
    /** There is no observation result: the action of reading the interface is still shortened according to the interface status. */
    private val INTERFACE_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Diagnose)
    private const val LONG_TEXT = 300
    /** Full interface reading (the element text in the review results will also be out of date; the review itself is not counted as a read). */
    private val FULL_VERBS = setOf(Verb.Observe, Verb.ListElements)
    private const val SCREEN = "screen"
    /** An ending field that must be retained when shortening. */
    private val OUTCOME = setOf("ok", "error", "message", "recoverable", "exitCode")

    /** Trim line: As is the last [window] tool round trip, the previous shortening. */
    data class Cut(val window: Int) {
        init { require(window >= 1) { "need window >= 1" } }

        /** There are a total of [rounds] round trips to the time line. */
        fun before(rounds: Int): Int = (rounds - window).coerceAtLeast(0)
    }

    /** Still excessive after compression: only the most recent round trip remains intact. */
    val LAST = Cut(1)

    /** [tools]: Check the action performed by the called tool name. */
    fun prune(input: List<Message>, tools: ToolIndex, cut: Cut): List<Message> {
        val starts = input.indices.filter { i -> input[i].role == Role.Assistant && input[i].parts.any { it is Part.ToolCall } }
        val k = cut.before(starts.size)
        if (k == 0) return input
        val line = starts[k]
        val names = callVerbs(input, tools)
        val screens = screenResults(input)
        // The most recent full interface before the line
        val anchor = (0 until line).lastOrNull { i ->
            input[i].parts.any { it is Part.ToolResult && (names[it.callId] in FULL_VERBS || screens[it.callId] == true) }
        }
        // User words (along with photos) are not cropped
        return input.mapIndexed { i, m -> if (i >= line || i == anchor || m.author == Author.User) m else shrinkMessage(m, names, screens) }
    }

    /**
     * The bottom line when you still can't let it go: starting from the latest, replace the long fields
     * of tool results (regardless of which type) with more than [BIG_RESULT_TOKENS] tokens into
     * [OMITTED_READ], and keep the ending fields and observation numbers until [fits]. The user's words
     * don't move.
     */
    fun shrinkOversized(messages: List<Message>, fits: (List<Message>) -> Boolean): List<Message> {
        val out = messages.toMutableList()
        for (i in out.indices.reversed()) {
            if (fits(out)) break
            val m = out[i]
            if (m.author == Author.User || m.parts.none { it is Part.ToolResult && TokenEstimate.of(it.content) > BIG_RESULT_TOKENS }) continue
            out[i] = m.copy(parts = m.parts.map { p ->
                if (p is Part.ToolResult && TokenEstimate.of(p.content) > BIG_RESULT_TOKENS) withObsIds(p, shrinkRead(p.content)) else p
            })
        }
        return out
    }

    /** [maxImages]: provider's known per-request image limit; when exceeded, replaces the earliest assistant screenshots with placeholders while keeping user messages and attachments intact. */
    fun limitImages(messages: List<Message>, maxImages: Int): List<Message> {
        require(maxImages >= 0)
        var excess = messages.sumOf { m -> m.parts.count { it is Part.Image } } - maxImages
        if (excess <= 0) return messages
        return messages.map { m ->
            if (m.author == Author.User || excess <= 0) m else {
                val parts = m.parts.map { p ->
                    if (p is Part.Image && excess > 0) {
                        excess--
                        Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                    } else p
                }
                if (parts == m.parts) m else m.copy(parts = parts)
            }
        }
    }

    private fun shrinkMessage(m: Message, names: Map<String, Verb?>, screens: Map<String, Boolean>): Message {
        val parts = m.parts.map { p ->
            when (p) {
                is Part.Image -> Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                is Part.ToolResult -> shrinkResult(p, names[p.callId], screens)
                else -> p
            }
        }
        return if (parts == m.parts) m else m.copy(parts = parts)
    }

    private fun shrinkResult(p: Part.ToolResult, verb: Verb?, screens: Map<String, Boolean>): Part.ToolResult {
        val ways = p.reads.mapNotNull { BY_FORM[it.form] }.toSet()
        val content = when {
            Shrink.Never in ways || verb in REFERENCE_VERBS -> p.content
            p.callId in screens -> shrinkScreen(p.content)
            Shrink.Interface in ways || verb in INTERFACE_VERBS -> shrinkInterface(p.content)
            verb == Verb.ViewPastScreens -> shrinkRecall(p.content)
            TokenEstimate.of(p.content) > BIG_RESULT_TOKENS -> shrinkRead(p.content)
            else -> p.content
        }
        return if (content == p.content) p else withObsIds(p, content)
    }

    /** The shortened results refer back to the few observations in the evidence ledger. */
    private fun withObsIds(p: Part.ToolResult, content: String): Part.ToolResult {
        val ids = p.reads.map { it.obsId }.distinct()
        val withIds = if (ids.isEmpty()) content else parse(content)?.let { o ->
            JsonObject(o + (OBS_IDS to JsonArray(ids.map(::JsonPrimitive)))).toString()
        } ?: content
        return p.copy(content = withIds)
    }

    /** Long fields (long strings, large arrays/objects) are replaced with [OMITTED_READ], and the ending fields are retained; the entire paragraph that is not JSON is replaced. */
    private fun shrinkRead(content: String): String {
        val obj = parse(content) ?: return OMITTED_READ
        return JsonObject(obj.mapValues { (k, v) ->
            val long = if (v is JsonPrimitive) v.isString && v.content.length > LONG_TEXT else v.toString().length > LONG_TEXT
            if (long && k !in OUTCOME) JsonPrimitive(OMITTED_READ) else v
        }).toString()
    }

    private fun callVerbs(messages: List<Message>, tools: ToolIndex): Map<String, Verb?> {
        val verbs = HashMap<String, Verb?>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { verbs[it.id] = tools.verb(it.name) } }
        return verbs
    }

    /** Tool results with screen object: callId → screen is full. */
    private fun screenResults(messages: List<Message>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        messages.forEach { m ->
            m.parts.filterIsInstance<Part.ToolResult>().filter { "\"$SCREEN\":{" in it.content }.forEach { r ->
                (parse(r.content)?.get(SCREEN) as? JsonObject)?.let { out[r.callId] = it["full"] == JsonPrimitive(true) }
            }
        }
        return out
    }

    private fun shrinkRecall(content: String): String {
        val obj = parse(content) ?: return content
        val shots = obj["screenshots"] as? JsonArray ?: return content
        val small = JsonArray(shots.map { e ->
            val o = e as? JsonObject
            if (o == null || "elementsText" !in o) e else JsonObject(o + ("elementsText" to JsonPrimitive(OMITTED)))
        })
        return JsonObject(obj + ("screenshots" to small)).toString()
    }

    private fun shrinkScreen(content: String): String {
        val obj = parse(content) ?: return content
        return JsonObject(obj + (SCREEN to JsonPrimitive(OMITTED))).toString()
    }

    private fun parse(content: String): JsonObject? = try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    /** Interface status: The element table and long fields are replaced by [OMITTED], and the ending field is retained. */
    private fun shrinkInterface(content: String): String {
        val obj = parse(content) ?: return content
        val small = obj.mapValues { (k, v) ->
            val long = v is JsonPrimitive && v.isString && v.content.length > LONG_TEXT
            if (k == "elements" || (long && k !in OUTCOME)) JsonPrimitive(OMITTED) else v
        }
        return JsonObject(small).toString()
    }
}
