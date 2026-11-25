package com.meenai.mina.core.agent

import com.meenai.mina.core.context.StepSummary
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb

/** [estimate]: A rough estimate (uncalibrated) of the token [messages], which will be used for comparison with the actual usage after it is issued. [rewritten]: Whether the request prefix was rewritten this time. */
data class Compressed(
    val messages: List<Message>,
    val foldedSteps: Int,
    val estimate: Int = TokenEstimate.of(messages),
    val rewritten: Boolean = false,
)

/**
 * The request is partitioned by cache: Area 0 system prompts and tools (unchanged within the task) →
 * Area 1 deployment context (only written once) → Area 2 history (only appended) → Area 3 tail (results,
 * screens, runtime status of this step). The service provider caches by prefix, so messages that have
 * been sent are usually sent again as they are.
 *
 * Only compress once when the size of the entire request ([UsageMeter]: the most recent real usage +
 * subsequent estimates) crosses the trigger point ([ContextLimits.trigger]): First, shorten the old
 * results and old images before the most recent [keepRecent] tool round-trips ([HistoryPruner.prune]),
 * and then fold these round-trips into a deterministic "previous step" summary (without adjusting the
 * model); the original words of the task, interruptions, reference materials, handovers and questioning
 * round-trips are not discounted. The compression should be below [ContextLimits.target] (if it cannot
 * be compressed, use the smallest level that can be compressed). Subsequent requests = the result of
 * this compression + subsequent additional messages, which are only appended until the trigger point is
 * exceeded again (at least one more [ContextLimits.margin] will be added after compression). Round trip
 * deletion of entire segments, the pairing of calls and results is always complete. Only a copy is
 * returned, leaving the caller's complete history unchanged. One instance per task; status is recorded
 * separately according to context size.
 *
 * [structured]: The summary is written as "goal/confirmed facts/progress/next step" ([StepSummary]).
 * [diffScreens]: Action result with difference screen (inlineScreen).
 */
class ContextCompressor(
    /** Check the action performed by the called tool name. */
    private val tools: ToolIndex,
    private val keepRecent: Int = 12,
    private val structured: Boolean = false,
    private val diffScreens: Boolean = false,
    private val triggerOverride: Int? = null,
) {
    /** "Real / rough estimate" ratio: Smoothly updated every time the usage comes back ([TokenEstimate.calibrated]); it only determines when to compress and to which level, and does not change the content that has been posted. */
    private var observed: Double? = null
    /** Estimated multiplication ratio: Use the calibration value after calibration, and use the conservative [UNCALIBRATED_SCALE] when the actual dosage is not available. Both message and fixed overhead are multiplied by it. */
    internal val scale: Double get() = observed ?: UNCALIBRATED_SCALE

    /** The last compression: it covers the complete history starting from [covered], compressed into [base] (rough estimate [estimate]); [aggressive]: compressed according to the model to report the extra long file. */
    private class View(val covered: List<Message>, val base: List<Message>, val folded: Int, val aggressive: Boolean) {
        val estimate = TokenEstimate.of(base)
    }
    private val views = HashMap<Int, View>()

    /** After a request is issued: [estimated] is the rough estimate of the request (including fixed overhead), and [actual] is the input token reported by the service provider. */
    fun calibrate(estimated: Int, actual: Int) {
        observed = TokenEstimate.calibrated(observed, estimated, actual)
    }

    /** New task: Clear the compression state (keep the calibration ratio, the same model). */
    fun forget() = views.clear()

    private fun tokens(ms: List<Message>) = TokenEstimate.of(ms) * scale

    /** The message that will actually be sent after the last compression (complete history without compression); the caller calculates the current context size based on this. */
    fun current(history: List<Message>, contextTokens: Int): List<Message> =
        sent(valid(views[contextTokens], history), history)

    /**
     * [currentTokens]: size of the whole request (including fixed overhead, see [UsageMeter]); when
     * null, estimate it as [current] plus [fixedOverhead]. Compression happens only after the trigger
     * point is crossed. [aggressive]: the model already reported an over-long context, so ignore the
     * estimate and keep only the latest [AGGRESSIVE_KEEP] exchanges (after that, still append-only).
     * [fixedOverhead]: estimate for the parts sent on every request but not in [history] (base prompt,
     * tool definitions), before scaling; used only when estimating the whole request and how much room
     * messages have, and not subtracted from the trigger point itself. [maxImages]: maximum number of
     * images the provider accepts per request (sent images are not normally replaced by placeholders;
     * beyond this limit the oldest are replaced).
     */
    fun compress(
        history: List<Message>,
        contextTokens: Int,
        currentTokens: Int? = null,
        aggressive: Boolean = false,
        fixedOverhead: Int = 0,
        maxImages: Int = Int.MAX_VALUE,
    ): Compressed {
        val limits = ContextLimits.of(contextTokens, triggerOverride)
        val last = views[contextTokens]
        val view = valid(last, history)
        val sent = sent(view, history)
        val size = currentTokens ?: UsageMeter.of(sent, fixedOverhead, scale)
        // Hysteresis: After compression, at least one margin will grow before compressing again. If the
        // history cannot be compressed below the trigger point, it will not be compressed every step;
        // but it does not exceed the available window. If it exceeds the available window, it will be
        // compressed every step.
        val trigger = view?.let {
            maxOf(limits.trigger, UsageMeter.estimate(it.estimate + fixedOverhead, scale) + limits.margin).coerceAtMost(limits.usable)
        } ?: limits.trigger
        val compact = (last != null && view == null) || (aggressive && view?.aggressive != true) || size > trigger
        if (!compact) return Compressed(HistoryPruner.limitImages(sent, maxImages), view?.folded ?: 0)
        val next = compact(history, limits, fixedOverhead, aggressive)
        views[contextTokens] = next
        return Compressed(HistoryPruner.limitImages(next.base, maxImages), next.folded, rewritten = true)
    }

    /**
     * The model reports that the context is too long: press the first level again from the complete
     * history (the history is longer and the pressure is heavier after the last hard press). If the
     * pressed one is smaller than the one just sent, replace it and return true (the caller resends it
     * accordingly); if it is the same size, it means that it cannot be pressed, so it returns false.
     */
    fun overflow(history: List<Message>, contextTokens: Int, fixedOverhead: Int = 0): Boolean {
        val current = sent(valid(views[contextTokens], history), history)
        val next = compact(history, ContextLimits.of(contextTokens, triggerOverride), fixedOverhead, aggressive = true)
        if (next.estimate >= TokenEstimate.of(current)) return false
        views[contextTokens] = next
        return true
    }

    /** The history covered by the compression must not be changed before using it; otherwise (it will not happen in the normal process) re-compress. */
    private fun valid(v: View?, history: List<Message>) = v?.takeIf { it.covered.size <= history.size && history.subList(0, it.covered.size) == it.covered }

    /** Always a copy: the caller's history is appended later. */
    private fun sent(view: View?, history: List<Message>) =
        if (view == null) history.toList() else view.base + history.subList(view.covered.size, history.size)

    /**
     * One compression, from light to heavy, takes the first one that reduces the entire request to
     * [ContextLimits.target] (cannot take the heaviest one): only shorten the old results before the
     * most recent [keepRecent] round trips → fold them into the summary → leave only the latest round
     * trip as it is, and shorten the others before folding them. The round trips left as they are are
     * [keepRecent], [AGGRESSIVE_KEEP], and 1 time. When the model reports an excessive length, it starts
     * from [AGGRESSIVE_KEEP]. When the heaviest level of the entire request still exceeds
     * [ContextLimits.usable] (the latest result is itself too large), the large results will be
     * shortened from the latest until they can be accommodated. The comparison is the estimation of the
     * message: the target and available windows are first deducted from the fixed overhead (the same
     * ratio), and the fixed overhead is only calculated this time.
     */
    private fun compact(history: List<Message>, limits: ContextLimits, fixedOverhead: Int, aggressive: Boolean): View {
        val overhead = UsageMeter.estimate(fixedOverhead, scale)
        val target = limits.target - overhead
        val window = limits.usable - overhead
        val ladder = sequence {
            if (!aggressive) {
                val pruned = HistoryPruner.prune(history, tools, HistoryPruner.Cut(keepRecent))
                yield(Compressed(pruned, 0))
                yield(fold(pruned, keepRecent, false))
            }
            val last = HistoryPruner.prune(history, tools, HistoryPruner.LAST)
            for (keep in listOfNotNull(keepRecent.takeUnless { aggressive }, AGGRESSIVE_KEEP, 1).distinct()) yield(fold(last, keep, aggressive))
        }
        var chosen = Compressed(history, 0)
        for (c in ladder) {
            chosen = c
            if (tokens(c.messages) <= target) break
        }
        if (tokens(chosen.messages) > window) chosen = chosen.copy(messages = HistoryPruner.shrinkOversized(chosen.messages) { tokens(it) <= window })
        // Copy: When nothing is cut, chosen may be the caller's history that will be appended.
        return View(history.toList(), chosen.messages.toList(), chosen.foldedSteps, aggressive)
    }

    private fun fold(pruned: List<Message>, keep: Int, aggressive: Boolean): Compressed {
        // A round trip = one assistant message and the messages after it and before the next assistant
        // message; the one before it is the beginning (task wording, standard, etc.)
        val starts = pruned.indices.filter { pruned[it].role == Role.Assistant }
        if (starts.isEmpty()) return Compressed(pruned, 0)
        val toolStarts = starts.filter { i -> pruned[i].parts.any { it is Part.ToolCall } }
        if (toolStarts.size <= keep) return Compressed(pruned, 0)
        val cut = toolStarts[toolStarts.size - keep]
        // The action comes with the screen: the most recent full screen reading before the folding
        // border and the difference after it are inextricably linked, and the difference depends on them
        // to understand (folding is taken when compressing hard, and the distributor then sends the full
        // screen);
        val rounds = starts.mapIndexed { k, start -> pruned.subList(start, starts.getOrElse(k + 1) { pruned.size }) }
        val screenRounds = rounds.map(::screenKind)
        val before = starts.count { it < cut }
        val anchor = if (aggressive || !diffScreens) -1 else (0 until before).lastOrNull { screenRounds[it] == ScreenKind.FULL } ?: -1

        val out = ArrayList<Message>(pruned.subList(0, starts.first()))
        // Each consecutively folded round trip gets a summary, which is placed in the original position
        // of the paragraph; the round trip left in the middle separates the paragraphs. When
        // structuring, there is only one summary, which is placed in the first paragraph, and the steps
        // of each paragraph are included in sequence.
        var block: MutableList<String>? = null
        var blockAt = 0
        var folded = 0
        var steps: StepSummary? = null
        var stepsAt = 0
        fun closeBlock() {
            if (!structured) block?.takeIf { it.isNotEmpty() }?.let { out.add(blockAt, summary(it)) }
            block = null
        }
        starts.takeWhile { it < cut }.forEachIndexed { k, start ->
            val end = starts.getOrElse(k + 1) { pruned.size }
            val round = pruned.subList(start, end)
            // Only the first call is executed in each round, and the rest are returned with
            // parallel_tool_call_skipped, so judge whether to keep it based on the first call.
            val call = round.first().parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (tools.verb(call?.name) in PINNED_VERBS || (anchor >= 0 && k >= anchor && screenRounds[k] != ScreenKind.NONE)) {
                closeBlock()
                out += round
                return@forEachIndexed
            }
            val lines = block ?: mutableListOf<String>().also { block = it; blockAt = out.size }
            out += round.filter { isPinnedContext(it) || it.author == Author.User }.map(ContextBuilder::withoutRuntimeState)
            if (call != null) {
                lines += line(k + 1, call, round)
                folded++
                if (structured) {
                    if (steps == null) stepsAt = blockAt
                    steps = (steps ?: StepSummary(goal(pruned.subList(0, starts.first())))).next(lines.last(), okResults(round), said(round))
                }
            }
        }
        closeBlock()
        steps?.let { out.add(stepsAt, notice(it.render(HEADER))) }
        out += pruned.subList(cut, pruned.size)
        return Compressed(out, folded)
    }

    private enum class ScreenKind { NONE, FULL, DIFF }

    /** Is there any screen in the result of this round: the complete element list of observe/list_elements or screen.full is true and counts as the full amount, and the remaining screen counts as the difference. */
    private fun screenKind(round: List<Message>): ScreenKind {
        val verbs = round.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().associate { it.id to tools.verb(it.name) }
        var kind = ScreenKind.NONE
        for (r in round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()) {
            val obj = (if ("\"elements\":[" in r.content || "\"screen\":{" in r.content) parse(r.content) else null) ?: continue
            val screen = obj["screen"] as? JsonObject
            when {
                verbs[r.callId] in OBSERVATIONS && obj["elements"] is JsonArray -> return ScreenKind.FULL
                screen != null && screen["full"] == JsonPrimitive(true) -> return ScreenKind.FULL
                screen != null -> kind = ScreenKind.DIFF
            }
        }
        return kind
    }

    private fun summary(lines: List<String>) = notice((listOf(HEADER, SUMMARY_REMINDER) + lines).joinToString("\n"))

    private fun notice(text: String) = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Notice, SUMMARY_SOURCE, text))), Author.Mina)

    /** The user's original words at the beginning (the one not in the context shell). */
    private fun goal(head: List<Message>): String = head.firstOrNull { it.author == Author.User }
        ?.parts?.filterIsInstance<Part.Text>()?.firstOrNull()?.text?.replace('\n', ' ')?.trim()?.let { if (it.length <= GOAL_LIMIT) it else it.take(GOAL_LIMIT) + "…" }
        .orEmpty()

    /** A successful tool result for this round. */
    private fun okResults(round: List<Message>): List<JsonObject> = round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        .filter { !it.isError }.mapNotNull { parse(it.content) }.filter { (it["ok"] as? JsonPrimitive)?.booleanOrNull != false }

    /** What the model says this round. */
    private fun said(round: List<Message>): String? =
        round.first().parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }

    /** Context retained with the round when folded (references, skills, etc., see [ContextKind.pinned]). */
    private fun isPinnedContext(m: Message) = ContextBuilder.kindOf(m)?.pinned == true


    private fun line(step: Int, call: Part.ToolCall, round: List<Message>): String {
        val target = target(parse(call.argsJson))?.let { "(${clip(it)})" }.orEmpty()
        val result = round.firstNotNullOfOrNull { m ->
            m.parts.firstNotNullOfOrNull { (it as? Part.ToolResult)?.takeIf { r -> r.callId == call.id } }
        }
        // Screen reading leaves the screenshot number, which can still be used after folding.
        // View_past_screens Review The screen with the write operation result also has the screenshot
        // number; when there is no screen, the screen number attached to the action is at the top level
        // of the result.
        val shot = result?.let { parse(it.content) }?.let { o -> if (tools.verb(call.name) in OBSERVATIONS) o["screenshotId"] else (o["screen"] as? JsonObject)?.get("screenshotId") ?: o["screenshotId"] }
            ?.let { it as? JsonPrimitive }?.contentOrNull?.let { " #$it" }.orEmpty()
        return "Step $step ${clip(call.name)}$target$shot → ${outcome(result)}"
    }

    private fun outcome(r: Part.ToolResult?): String {
        r ?: return NO_RESULT
        val obj = parse(r.content)
        if (obj == null) {
            // Not JSON: If you can recognize the error code when an error occurs, write the error code.
            val code = r.content.trim().takeIf { CODE.matches(it) }
            return if (r.isError) code ?: FAILED else APPLIED
        }
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        return when {
            // Only error codes are recognized; the description text can be long or short, and no summary
            // is included.
            r.isError || (obj["ok"] as? JsonPrimitive)?.booleanOrNull == false -> (s("error") ?: s("reason"))?.takeIf { CODE.matches(it) } ?: FAILED
            (obj["changed"] as? JsonPrimitive)?.booleanOrNull == false -> UNCHANGED
            else -> APPLIED
        }
    }

    private fun target(args: JsonObject?): String? {
        args ?: return null
        TARGET_KEYS.forEach { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it } }
        val x = (args["x"] as? JsonPrimitive)?.contentOrNull
        val y = (args["y"] as? JsonPrimitive)?.contentOrNull
        return if (x != null && y != null) "$x,$y" else null
    }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length <= TARGET_LIMIT) one else one.take(TARGET_LIMIT) + "…"
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    companion object {
        const val HEADER = "Earlier steps (compressed):"
        const val SUMMARY_REMINDER = "Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only."
        private const val SUMMARY_SOURCE = "earlier steps"
        const val AGGRESSIVE_KEEP = 6
        /**
         * Estimated multiplication ratios when there are no real quantities to calibrate. A rough
         * estimate is that English and JSON are often too small (the tokenizer cuts them into smaller
         * pieces). It is better to press them earlier than to press them too long first and then press
         * them hard.
         */
        const val UNCALIBRATED_SCALE = 1.2
        private const val APPLIED = "applied"
        private const val UNCHANGED = "no change"
        private const val FAILED = "failed"
        private const val NO_RESULT = "no result"
        private const val TARGET_LIMIT = 20
        private const val GOAL_LIMIT = 120
        private val CODE = Regex("[a-z][a-z0-9_:]{0,63}")
        /** The results of returns, follow-up questions and confirmations (whether the answers are tool results or subsequent user messages) are retained in their entirety. */
        private val OBSERVATIONS = setOf(Verb.Observe, Verb.ListElements)
        private val PINNED_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        private val TARGET_KEYS = listOf("target", "text", "package", "query", "direction", "key", "name", "ref", "seconds")
    }
}
