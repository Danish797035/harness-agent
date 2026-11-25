package com.meenai.mina.core.verify

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelJson
import com.meenai.mina.core.model.ModelJson.field
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.meenai.mina.core.vision.ImageOps
import com.meenai.mina.core.vision.ModelImage

/**
 * The standard to compare when checking: [items] is a fact that should be seen after it is made; [kind]
 * is read / irreversible (having done things such as sending, submitting, placing an order, deleting,
 * and making a payment that cannot be recovered). [fallback] means there is no other listing standard,
 * [items] is just the user's original words themselves (this is always the case on the task path, and
 * does not cost another model call to list).
 */
data class Criteria(val items: List<String>, val kind: String, val fallback: Boolean) {
    companion object {
        /** The criteria used for the task path: the user's original words themselves. */
        fun of(goal: String, irreversible: Boolean) = Criteria(listOf(goal), if (irreversible) "irreversible" else "read", fallback = true)
    }
}

enum class Verdict(val wire: String) { Pass("pass"), Fail("fail"), CannotVerify("cannot_verify") }

/** A standard check result; [status] is met / unmet / unknown, [quote] is the screen text that supports it. */
data class CriterionCheck(val criterion: String, val status: String, val quote: String?)

/**
 * [groundedBy] is `"image"` when only on-screen state (non-numeric quote) absent from text evidence
 * is passed by a screenshot-capable verifier, distinguished in run records from text-verified passes;
 * [notFound] is an answer fact not found in any evidence source.
 */
data class Verification(
    val verdict: Verdict,
    val checks: List<CriterionCheck>,
    val reason: String,
    val groundedBy: String? = null,
    val notFound: List<String> = emptyList(),
) {
    /** Criteria that are not met; the validator fails without specifying the reason given when using it. */
    val unmet: List<String>
        get() = checks.filter { it.status == "unmet" }.map { it.criterion }.ifEmpty { if (verdict == Verdict.Fail) listOf(reason) else emptyList() }
}

/**
 * Evidence handed to the verifier. [fresh] is a look at the most recently used ability after answering
 * ([com.meenai.mina.core.capability.Capability.freshLook]: interface status, the beginning of the
 * webpage text...), [freshImage] is the picture at the same time (only available when you ask for a
 * picture); [ledger] is all the observations read in this task (evidence ledger, see [EvidenceLedger];
 * select relevant entries according to the answer during verification), [actions] It is a streamlined
 * action list ("Step 3 tap (search) takes effect"). [protected] indicates that the page at this moment
 * is protected from screenshots. [appOpened] is false, which means that the App has not been
 * successfully opened in this task.
 */
class Evidence(
    val fresh: List<Observation>,
    val freshImage: ByteArray?,
    val ledger: EvidenceLedger,
    val actions: List<String>,
    val protected: Boolean,
    val appOpened: Boolean = true,
    /** The ending of each takeover does not roll out with the action list (skipping or timeout indicates that the user did not process it). */
    val takeovers: List<String> = emptyList(),
    /** Facts given by the user: memory retrieval results, answers to questions in this task. */
    val userFacts: List<String> = emptyList(),
    /** Run the clock: Check the current time and time zone. Convert and check the dates next to "tomorrow" and "Sunday" in the text of the answer (see [Grounding.check]). */
    val nowMs: Long = System.currentTimeMillis(),
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    /** The date the clock is running on. */
    val today: java.time.LocalDate get() = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** Take another look at the text you read and observe each paragraph. */
    val freshText: String get() = fresh.joinToString("\n") { it.text }

    /** I haven’t opened the app, and I haven’t read any interface, web page or command output: I can only answer based on conversation or common sense, and there is no reference material. */
    val nothingRead: Boolean get() = !appOpened && !protected && freshText.isBlank() && ledger.isEmpty && userFacts.isEmpty()

    /** The original texts of certain types of observations in the ledger are listed in the order they are read (see what is recorded in the ledger). */
    fun read(vararg kinds: ObsKind): List<String> = ledger.all.filter { it.kind in kinds }.map { it.text }

    /** Can compare the entire text of the answer, read one place at a time: a second glance, the entry [chosen] (full text) selected in the ledger, each fact given by the user. */
    fun sources(chosen: List<Observation>): List<String> =
        (listOf(freshText) + chosen.map { it.text } + userFacts).filter { it.isNotBlank() }
}

/**
 * Independent verification before the final answer is handed over (request it only when irreversible,
 * external things have been done, or when the literal verification is inconsistent, see AnswerReview): a
 * new model call, without tools, without the history of the working model, only looking at the goals,
 * standards, answers and evidence. When there are screenshots and a visual model, use [visionModel] and
 * attach pictures, otherwise use [textModel]. Division of labor: The code summary ([Grounding]) only
 * verifies the literal text - model judgment pass but the specific facts in the answer (citations,
 * numbers with units, numbers, dates) cannot be found in the evidence text → cannot_verify; Attached is
 * a screenshot, but what cannot be found is just the status on the screen → pass, recorded as
 * verification by looking at the picture. Whether a value belongs to the thing mentioned in the answer
 * (which page, which product, which row) is determined by the verification model: the ledger entry
 * handed to it is an excerpt ([EvidenceLedger.excerpt]), with the source. Error, the reply cannot be
 * parsed → cannot_verify; the timeout is relaxed according to the amount of evidence ([timeoutMs]),
 * after the timeout, the evidence is streamlined and asked again, and it cannot_verify until the timeout
 * continues. The task of "I haven't read anything" ([Evidence.nothingRead]) has no material to compare
 * with, so I don't do this. I just tell the checker that I haven't read any material, and let it judge
 * whether the answer is relevant.
 */
class Verifier(
    private val textModel: ChatModel,
    private val visionModel: ChatModel?,
    /** The screenshot is sent according to the unified code of [ModelImage]; if it is empty, it will be sent as it is. */
    private val ops: ImageOps? = null,
) {
    /** It's only worth attaching images if you have a visual model. */
    val wantsImage: Boolean get() = visionModel != null

    /**
     * [earlierGoals] is what the user said in the previous rounds of this conversation (only the user's
     * original words, without anything from the work model), and is used to understand the reference
     * when asking questions. Goals, answers, screen readings, and action lists are all untrustworthy
     * materials. They are compiled into a JSON object and sent out. The line breaks in it cannot create
     * a new paragraph.
     */
    suspend fun verify(goal: String, criteria: Criteria, answer: String, evidence: Evidence, earlierGoals: List<String> = emptyList()): Verification {
        val chosen = evidence.ledger.forAnswer(goal, answer)
        val reads = evidence.sources(chosen)
        if (evidence.protected && reads.isEmpty()) return cannot(PROTECTED)
        val image = evidence.freshImage?.takeIf { visionModel != null && !evidence.protected }
        val model = if (image != null) visionModel!! else textModel
        val v = when (val first = ask(model, goal, criteria, answer, evidence, chosen, EvidenceLedger.EXCERPT_CHARS, earlierGoals, image)) {
            is Asked.Done -> first.v
            is Asked.Failed -> return cannot(first.reason)
            // Long tasks with a lot of evidence are prone to timeout: streamline and ask again
            Asked.TimedOut -> when (val second = ask(model, goal, criteria, answer, trimmed(evidence), chosen, TRIM_EXCERPT_CHARS, earlierGoals, image)) {
                is Asked.Done -> second.v
                is Asked.Failed -> return cannot(second.reason)
                Asked.TimedOut -> return cannot(TIMEOUT)
            }
        }
        return when (v.verdict) {
            Verdict.Pass -> when {
                // Passes that fail to check each criterion one by one will not count.
                v.checks.size < criteria.items.size -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_EVERY_CRITERION)
                evidence.nothingRead -> v
                else -> backstop(v, goal, answer, reads, imageSent = image != null, evidence.today)
            }
            // Next, the above question did not list the criteria. We could only compare it with an
            // original sentence without beginning or end, and judge that fail is unreliable.
            Verdict.Fail -> if (criteria.fallback && earlierGoals.isNotEmpty()) v.copy(verdict = Verdict.CannotVerify, reason = FOLLOW_UP_UNCLEAR)
                else if (v.reason.isBlank()) v.copy(reason = FAIL_WITHOUT_REASON) else v
            Verdict.CannotVerify -> v
        }
    }

    private fun cannot(reason: String) = Verification(Verdict.CannotVerify, emptyList(), reason)

    private sealed interface Asked {
        class Done(val v: Verification) : Asked
        class Failed(val reason: String) : Asked
        data object TimedOut : Asked
    }

    private suspend fun ask(
        model: ChatModel, goal: String, criteria: Criteria, answer: String, evidence: Evidence, chosen: List<Observation>, budget: Int,
        earlierGoals: List<String>, image: ByteArray?,
    ): Asked {
        val body = request(goal, criteria, answer, evidence, excerpts(goal, answer, chosen, budget, evidence.today), earlierGoals)
        val parts = listOfNotNull(Part.Text(body), image?.let { ModelImage.part(it, ops) })
        var unparsed = false
        return try {
            withTimeoutOrNull(timeoutMs(body.length, if (image != null) 1 else 0)) {
                // The reply goes through fault-tolerant parsing; if it cannot be read, ask again, only
                // JSON; if it still fails, it is considered that it cannot be parsed.
                ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, parts, Author.Mina)), ::parse).also { unparsed = it == null }
            }?.let { Asked.Done(it) } ?: if (unparsed) Asked.Failed(UNPARSED) else Asked.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Asked.Failed("$ERROR_PREFIX${e.message ?: e::class.simpleName}")
        }
    }

    /** Streamlined evidence for re-examination after timeout: one screen and screenshots are retained at this time, only the most recent actions are retained, and each paragraph is truncated; excerpts of ledger entries are subject to a smaller word limit ([TRIM_EXCERPT_CHARS]). */
    private fun trimmed(e: Evidence) = Evidence(
        trimmedFresh(e.fresh), e.freshImage, e.ledger,
        e.actions.takeLast(TRIM_ACTIONS), e.protected, e.appOpened, e.takeovers,
        e.userFacts.map { it.take(TRIM_CHARS) }, e.nowMs, e.zone,
    )

    /** A second glance will add up to at most [TRIM_FRESH_CHARS] characters (cut in order). */
    private fun trimmedFresh(fresh: List<Observation>): List<Observation> {
        var room = TRIM_FRESH_CHARS
        return fresh.mapNotNull { o ->
            if (room <= 0) null else o.copy(text = o.text.take(room)).also { room -= it.text.length + 1 }
        }
    }

    private fun request(goal: String, c: Criteria, answer: String, e: Evidence, shown: List<Observation>, earlierGoals: List<String>): String {
        val material = buildJsonObject {
            put("goal", goal)
            if (earlierGoals.isNotEmpty()) put("earlierGoals", JsonArray(earlierGoals.map(::JsonPrimitive)))
            put("criteria", JsonArray(c.items.map(::JsonPrimitive)))
            if (c.fallback) put("criteriaNote", "没有另列标准,criteria 只是用户原话本身,请对照用户的目标核对")
            put("kind", c.kind)
            put("answer", answer)
            put("freshLook", if (e.nothingRead) NOTHING_READ else freshShown(goal, answer, e).ifBlank { if (e.protected) "(页面受防截屏保护,读不到)" else "(没读到文字)" })
            put("ledger", JsonArray(shown.map { r ->
                buildJsonObject {
                    if (r.step > 0) put("step", r.step)
                    put("kind", r.kind.wire)
                    r.source?.let { put("source", it) }
                    r.where?.let { put("where", it) }
                    r.title?.let { put("title", it) }
                    put("text", r.text)
                }
            }))
            put("actions", JsonArray(e.actions.map(::JsonPrimitive)))
            if (e.takeovers.isNotEmpty()) put("takeovers", JsonArray(e.takeovers.map(::JsonPrimitive)))
            if (e.userFacts.isNotEmpty()) put("userFacts", JsonArray(e.userFacts.map(::JsonPrimitive)))
        }
        return "下面是待核对的材料(JSON,字段说明见系统提示):\n$material"
    }

    companion object {
        /** Verification should not delay the task: basic waiting time; press [timeoutMs] to relax when there is a lot of evidence, up to [MAX_TIMEOUT_MS]. */
        const val TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 150_000L
        private const val PER_IMAGE_MS = 20_000L
        private const val PER_KB_MS = 1_000L
        private const val TRIM_FRESH_CHARS = 6_000
        private const val TRIM_CHARS = 3_000
        private const val TRIM_ACTIONS = 10
        private const val TRIM_EXCERPT_CHARS = EvidenceLedger.EXCERPT_CHARS / 2
        /** If the second glance is longer than this number of words, only excerpts will be given (the beginning and the lines above that answer the facts). */
        private const val FRESH_CHARS = 4_000
        const val IMAGE = "image"

        /** How long to wait is directly proportional to what you want to read: 60 seconds for the base, plus 150 seconds for each image and KB of text, up to a maximum of 150 seconds. */
        fun timeoutMs(textChars: Int, images: Int): Long =
            minOf(MAX_TIMEOUT_MS, TIMEOUT_MS + images * PER_IMAGE_MS + textChars / 1024 * PER_KB_MS)
        const val PROTECTED = "结果页受系统防截屏保护,看不到画面,没法核实"
        const val TIMEOUT = "核查超时"
        const val UNPARSED = "核查器的回复无法解析"
        const val ERROR_PREFIX = "核查器出错:"
        const val VERIFYING = "正在核实结果"
        const val NOT_IN_READS = "回答的依据在读到的界面、网页或命令输出里都找不到"
        const val NOT_EVERY_CRITERION = "核查器没有逐条核对每一条标准"
        const val FAIL_WITHOUT_REASON = "核查器认为没有做成,但没有指出哪条标准没满足"
        const val FOLLOW_UP_UNCLEAR = "这是接着上文的追问,没能列出具体标准,核查判不准"
        const val NOTHING_READ = "本次任务没有打开 App,也没有读到任何材料"
        const val TRUNCATED = "回答依据的是屏上被截断的文字(以…结尾),只看到了一部分,没能完整核实"
        private val STATUSES = setOf("met", "unmet", "unknown")

        val PROMPT: String by lazy { Prompts.role("verifier") }

        /**
         * The ledger entries submitted to the verification model: each entry is replaced with an excerpt
         * ([EvidenceLedger.excerpt]), and the rows that answer the facts must be left, and no entry is
         * lost. First, extract the lines of [EvidenceLedger.EXCERPT_CONTEXT] before and after. If the
         * total number of words exceeds [budget], reduce the number of lines before and after. If it
         * exceeds, cut each line to an average share (at least [EvidenceLedger.MIN_EXCERPT] words).
         */
        internal fun excerpts(goal: String, answer: String, chosen: List<Observation>, budget: Int, today: java.time.LocalDate? = null): List<Observation> {
            val hits = chosen.map { Grounding.hitLines(goal, answer, it.text, today) }
            for (ctx in EvidenceLedger.EXCERPT_CONTEXT downTo 0) {
                val ex = chosen.mapIndexed { i, r -> EvidenceLedger.excerpt(r.text, hits[i], ctx) }
                if (ex.sumOf { it.length } <= budget) return chosen.mapIndexed { i, r -> r.copy(text = ex[i]) }
            }
            val cap = maxOf(EvidenceLedger.MIN_EXCERPT, budget / chosen.size.coerceAtLeast(1))
            return chosen.mapIndexed { i, r -> r.copy(text = EvidenceLedger.excerpt(r.text, hits[i], 0, cap)) }
        }

        /** Give it to the person who checked the model and take another look: it is not as long as it is, but has the beginning and the lines that answer the facts. */
        private fun freshShown(goal: String, answer: String, e: Evidence): String {
            val t = e.freshText
            if (t.length <= FRESH_CHARS) return t
            return EvidenceLedger.excerpt(t, Grounding.hitLines(goal, answer, t, e.today), 1, FRESH_CHARS, withHead = true)
        }

        /**
         * The bottom line after passing the model judgment (see [Grounding.check] for rules): only check
         * the literal, not what the value belongs to. When the text does not match: A screenshot is
         * attached, and the only thing that does not match is the status on the screen (citation without
         * numbers; the citation also requires that the answer has been matched in the text elsewhere).
         * If you have seen the picture, the pass will be counted, and it will be recorded as
         * verification by looking at the picture; if the number and date can be written in text, if it
         * does not match, it will be rejected. [today] is the date of the running clock (relative date
         * is converted according to it, see [Grounding.check]). It is also used for offline
         * recalculation of recorded runs.
         */
        internal fun backstop(v: Verification, goal: String, answer: String, screens: List<String>, imageSent: Boolean, today: java.time.LocalDate? = null): Verification {
            val g = Grounding.check(goal, answer, screens, today)
            val ok = when (g.found) {
                true -> true
                false -> false
                // There is nothing to check in the answer, or there are only a few matching numbers and
                // no citation positioning: at least one met citation must be on the screen.
                null -> v.checks.any { it.status == "met" && it.quote != null && Grounding.quoteInReads(it.quote, screens) }
            }
            val missing = g.unmatched.map { it.text }
            val result = when {
                ok -> v
                imageSent && g.unmatched.all { it.visualOnly } && (g.anchored || g.unmatched.none { it.kind == Grounding.Kind.Quote }) ->
                    v.copy(groundedBy = IMAGE, notFound = missing)
                else -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_IN_READS, notFound = missing)
            }
            // The basis is the truncated text on the screen (ending with...): only part of it is seen,
            // and the whole picture cannot be verified
            return if (result.verdict == Verdict.Pass && Grounding.builtOnTruncated(answer, screens)) result.copy(verdict = Verdict.CannotVerify, reason = TRUNCATED) else result
        }

        /**
         * The first readable JSON object in the reply (see [ModelJson]: Tolerance of code blocks, words
         * before and after, key name writing); returns null when the conclusion is missing or unknown.
         * If there is any conflict between each conclusion and the general conclusion, the individual
         * conclusions shall be considered.
         */
        internal fun parse(text: String): Verification? {
            val obj = ModelJson.firstObject(text) ?: return null
            val verdict = when ((obj.field("verdict", "result", "conclusion") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
                "pass", "passed" -> Verdict.Pass
                "fail", "failed" -> Verdict.Fail
                "cannot_verify", "cannotverify", "unknown" -> Verdict.CannotVerify
                else -> return null
            }
            val checks = (obj.field("checks", "criteria_checks", "results") as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val criterion = (o.field("criterion", "criteria", "item") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val status = (o.field("status", "state") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it in STATUSES } ?: "unknown"
                val quote = (o.field("quote", "evidence") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
                CriterionCheck(criterion, status, quote)
            }
            val reason = (obj.field("reason", "explanation") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val effective = when {
                checks.any { it.status == "unmet" } -> Verdict.Fail
                verdict == Verdict.Pass && checks.any { it.status == "unknown" } -> Verdict.CannotVerify
                else -> verdict
            }
            return Verification(effective, checks, reason)
        }
    }
}
