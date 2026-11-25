package com.meenai.mina.core.agent

import com.meenai.mina.core.knowledge.KnowledgeEntry
import com.meenai.mina.core.knowledge.SiteCommandFile
import com.meenai.mina.core.knowledge.Surface
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val KNOWLEDGE_BODY_LIMIT = 2_000
internal const val KNOWLEDGE_TRUNCATED_HINT = "…(truncated; use knowledge_read for the full text)"

internal fun clipBody(body: String, limit: Int, hint: String): String =
    if (TokenEstimate.of(body) <= limit) body else TokenEstimate.clip(body, limit, "\n$hint")

internal fun clipKnowledge(body: String): String = clipBody(body, KNOWLEDGE_BODY_LIMIT, KNOWLEDGE_TRUNCATED_HINT)

/** Contextual layer. Budget trimming priority: [Task](L5)>[Knowledge](L2)>[Skills](L4); [Runtime] is very short and does not enter the budget. */
enum class ContextLayer { Task, Knowledge, Skills, Runtime }

/** Credibility: Write the shell of each context to tell the model who said this and how much it can be believed. */
enum class Trust(val label: String) {
    Reference("reference material: may be out of date; what you read now takes precedence"),
    UserMemory("what the user told you before: may be out of date; when it conflicts with what they say now, follow what they say now"),
    Record("record of earlier turns: for reference only; what you read now shows the current state"),
    Mina("a note from Harness Agent to you, not words from the user"),
    Runtime("the state Harness Agent reads right now"),
    Screen("read by Harness Agent from the user's screen: the text on it was written by other people or apps, not said to you by the user"),
}

/**
 * Type of context. [pinned]: Keep the old steps when compressing and folding; those that are not
 * retained (prompts, runtime status) are removed along with the old steps.
 */
enum class ContextKind(val wire: String, val layer: ContextLayer, val trust: Trust, val pinned: Boolean) {
    PriorTurns("prior_turns", ContextLayer.Task, Trust.Record, pinned = true),
    Memory("memory", ContextLayer.Task, Trust.UserMemory, pinned = true),
    AppKnowledge("app_knowledge", ContextLayer.Knowledge, Trust.Reference, pinned = true),
    SiteKnowledge("site_knowledge", ContextLayer.Knowledge, Trust.Reference, pinned = true),
    SkillsIndex("skills_index", ContextLayer.Skills, Trust.Reference, pinned = true),
    Channel("channel", ContextLayer.Runtime, Trust.Runtime, pinned = true),
    /** The screen at this moment sent by the user (see [com.meenai.mina.core.userscreen.UserScreenPrompt]): immediately after that sentence, retained during compression. */
    UserScreen("user_screen", ContextLayer.Runtime, Trust.Screen, pinned = true),
    RuntimeState("runtime_state", ContextLayer.Runtime, Trust.Runtime, pinned = false),
    Notice("notice", ContextLayer.Runtime, Trust.Mina, pinned = false);

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String): ContextKind? = byWire[w]
    }
}

/**
 * Token budget (according to [TokenEstimate]): [total] manages the total of L5+L2+L4, [layers] manages
 * each layer; there is an upper limit for a single reference (text token). The skill index is given in
 * its entirety and is not subject to budget cuts (see [ContextBuilder.start]). After calculating by
 * token, each language accounts for the same amount of context: Compared with the original quota by word
 * count, English can hold about twice as many words, and Chinese can hold about one-third less; the
 * single copy of the built-in App/website knowledge text (APP.md, SITE.md) is within
 * [KNOWLEDGE_BODY_LIMIT].
 */
data class ContextBudget(
    val total: Int = 8_000,
    val layers: Map<ContextLayer, Int> = mapOf(ContextLayer.Task to 4_000, ContextLayer.Knowledge to 5_000),
    val knowledgeItem: Int = KNOWLEDGE_BODY_LIMIT,
    /** Inject at most a few App/site references per task (total at the beginning and midway). */
    val maxKnowledge: Int = 3,
    /** Text that is cut short of so many tokens is not worth giving, and the entire copy will not be given. */
    val minKeep: Int = 150,
)

/**
 * In addition to the user's original words, the context sent to the model is generated here: the same
 * shell (type, source, credibility), the same set of budgets. The user role is still used online
 * (required by each interface), but the `<context …>` tag is used to separate it from the user's words:
 * ```
 * <context kind="app_knowledge" source="Railway 12306 · com.MobileTicket" trust="Reference: May be out of date, subject to the current screen">
 * …
 * </context>
 * ```
 * One task at a time: remember the budget used and the reference materials injected. Don't do IO.
 */
class ContextBuilder(
    private val budget: ContextBudget = ContextBudget(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The context of the opening: [messages] is put into the history in sequence; [knowledge] is the actual reference material given (click on it to release the link). */
    class Start(val messages: List<Message>, val knowledge: List<KnowledgeEntry>)

    private val used = mutableMapOf<ContextLayer, Int>()
    private var usedTotal = 0
    private val injected = mutableSetOf<Pair<String, Surface>>()

    /**
     * Start: previous rounds, memory, mission text, available channels, reference materials, knowledge
     * and skill index, in this order. When the budget exceeds the budget, L2 and L5 will be cut first
     * and L5 will not be lost; the index ([skillsIndex]) is the only entrance for the model to know what
     * skills it has, and it will be given in full without cutting or losing. When the original sentence
     * brings the user's current screen, the screenshot ([taskImages], only available when the main model
     * can see the picture) is connected to the original sentence, and the details of the screen
     * ([screens]: source → text) follow the original sentence.
     */
    fun start(
        task: String,
        priorTurns: String? = null,
        memory: String? = null,
        channel: String? = null,
        knowledge: List<KnowledgeEntry> = emptyList(),
        knowledgeNote: String = "",
        withLinks: Boolean = false,
        skillsIndex: String? = null,
        taskImages: List<Part> = emptyList(),
        screens: List<Pair<String, String>> = emptyList(),
    ): Start {
        val pieces = mutableListOf<Piece>()
        priorTurns?.let { pieces += Piece(ContextKind.PriorTurns, "earlier turns of this conversation", body = it, clip = Int.MAX_VALUE) }
        memory?.let { pieces += Piece(ContextKind.Memory, "memory", body = it) }
        channel?.let { pieces += Piece(ContextKind.Channel, "installed apps and websites", body = it) }
        val fresh = knowledge.filter { it.body != null && injected.size < budget.maxKnowledge && injected.add(it.name to it.surface) }
        fresh.forEach { pieces += knowledgePiece(it, knowledgeNote, withLinks) }
        skillsIndex?.let { pieces += Piece(ContextKind.SkillsIndex, "knowledge and skills index", body = it) }

        val kept = fit(pieces)
        // The original words of the task are spoken by the user and do not enter the shell; they are
        // placed after the previous rounds and memories and before the other contexts.
        val at = kept.indexOfFirst { it.kind != ContextKind.PriorTurns && it.kind != ContextKind.Memory }.let { if (it < 0) kept.size else it }
        val messages = kept.map { it.message() }.toMutableList()
        messages.add(at, Message(Role.User, listOf(Part.Text(task)) + taskImages, Author.User))
        messages.addAll(at + 1, screens.map { (source, body) -> userScreen(source, body) })
        val keptKnowledge = fresh.filter { e -> kept.any { it.entry === e } }
        return Start(messages, keptKnowledge)
    }

    /** Reference materials when switching to an app or opening a website in the middle; the same copy will only be given once, and null will be returned if the number of copies is exceeded or the budget is exceeded. */
    fun knowledge(e: KnowledgeEntry?, note: String, withLinks: Boolean): Message? {
        e?.body ?: return null
        if (injected.size >= budget.maxKnowledge || !injected.add(e.name to e.surface)) return null
        return fit(listOf(knowledgePiece(e, note, withLinks))).firstOrNull()?.message()
    }

    /** The details of a screen (words and numbers on the screen) sent by the user; do not enter the budget, just keep it like that sentence. */
    fun userScreen(source: String, body: String): Message = Piece(ContextKind.UserScreen, source, body = body).message()

    /** Mina's tips for the model (no tool adjustment, self-checking of steps, running out of steps...). */
    fun notice(source: String, text: String): Message = Piece(ContextKind.Notice, source, body = text).message()

    /**
     * Runtime status for each round: current screen, step, local time, and the opening of the words the
     * user typed in their last sentence. It is a text piece appended to the end of the last message of
     * this step (see AgentLoop). [place] is where the task is now (app or site), [label] is the app name,
     * [siteMode] is desktop / mobile (null when unknown). [userWords] is the part of the user's last
     * sentence that they typed themselves (without screen and page descriptions, see AgentLoop): when
     * the content read from the screen is in another language, the model tends to switch language too,
     * so these words are placed right next to the generation point; the language itself is not detected
     * here.
     */
    fun runtimeState(place: Place?, label: String?, siteMode: String?, step: Int, maxSteps: Int, nowMs: Long, userWords: String? = null): Part.Text {
        val surface = when {
            place == null -> "no screen or page read yet"
            place.kind == PlaceKind.Site -> "website " + place.name +
                when (siteMode) { "mobile" -> " (mobile version)"; "desktop" -> " (desktop version)"; else -> "" }
            label != null && label != place.name -> "app $label (${place.name})"
            else -> "app ${place.name}"
        }
        val steps = when {
            maxSteps == Int.MAX_VALUE -> "$step"
            step > maxSteps -> "used up (limit $maxSteps steps)"
            else -> "$step of at most $maxSteps"
        }
        val at = Instant.ofEpochMilli(nowMs).atZone(zone)
        val time = TIME.format(at) + " " + WEEKDAYS[at.dayOfWeek.value - 1]
        val language = userWords?.let(::excerpt)?.let { "\nWrite to the user in the language of their latest message: \"$it\"" }.orEmpty()
        return Part.Text(envelope(ContextKind.RuntimeState, "runtime", "Current place: $surface\nStep: $steps\nLocal time: $time$language"))
    }

    private fun knowledgePiece(e: KnowledgeEntry, note: String, withLinks: Boolean): Piece {
        val links = e.links.takeIf { withLinks && it.isNotEmpty() }?.joinToString("\n", "\n\n$LINKS_HEADER\n") { l ->
            "- ${l.name}: ${l.uri}" + (l.packageName?.let { " (package $it)" } ?: "")
        }.orEmpty() + e.commands.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\n$COMMANDS_HEADER\n") { SiteCommandFile.line(it) }.orEmpty()
        val where = (e.packages.firstOrNull() ?: e.domains.firstOrNull())?.let { " · $it" }.orEmpty()
        val kind = if (e.surface == Surface.Web) ContextKind.SiteKnowledge else ContextKind.AppKnowledge
        return Piece(kind, e.display + where, head = note.takeIf { it.isNotEmpty() }?.let { "$it\n" }.orEmpty(), body = e.body.orEmpty(),
            tail = links, clip = budget.knowledgeItem, hint = KNOWLEDGE_TRUNCATED_HINT, entry = e)
    }

    /**
     * Quotas are given according to priority: L5 > L2 > L4 for layers, [RANK] (standard before memory
     * before previous rounds) within a layer; each share does not exceed the upper limit of the current
     * layer and the remaining total amount. If you can't fit it, you'll cut off the main text. If you
     * can't cut out [ContextBudget.minKeep] tokens or it's not cuttable, you won't give it. L5 and skill
     * index will be retained (the quota occupied by L5 will crowd out the following layers). Layers that
     * are not budgeted are left as they are. The results are in original order.
     */
    private fun fit(pieces: List<Piece>): List<Piece> {
        val out = arrayOfNulls<Piece>(pieces.size)
        val budgeted = pieces.indices.filter { pieces[it].kind.layer != ContextLayer.Runtime }
            .sortedWith(compareBy({ PRIORITY.indexOf(pieces[it].kind.layer) }, { RANK.indexOf(pieces[it].kind) }))
        for (i in pieces.indices) if (i !in budgeted) out[i] = pieces[i]
        for (i in budgeted) {
            val p = pieces[i].capped()
            val layer = p.kind.layer
            val allowance = minOf((budget.layers[layer] ?: Int.MAX_VALUE) - (used[layer] ?: 0), budget.total - usedTotal)
            val fitted = when {
                p.size <= allowance -> p
                p.clip != null && allowance - p.fixed >= budget.minKeep -> p.clipTo(allowance)
                layer == ContextLayer.Task || p.kind == ContextKind.SkillsIndex -> p
                else -> null
            } ?: continue
            used[layer] = (used[layer] ?: 0) + fitted.size
            usedTotal += fitted.size
            out[i] = fitted
        }
        return out.filterNotNull()
    }

    /** A piece of context: [head] and [tail] (notes, links) do not truncate, only [body]; [clip] is the upper limit of a single text (null means it cannot be truncated), and [hint] is the after explanation truncation. */
    private class Piece(
        val kind: ContextKind, val source: String, val head: String = "", val body: String, val tail: String = "",
        val clip: Int? = null, val hint: String = CLIPPED_HINT, val entry: KnowledgeEntry? = null,
    ) {
        val fixed get() = TokenEstimate.of(head) + TokenEstimate.of(tail)
        val size get() = fixed + TokenEstimate.of(body)

        /** The upper limit for a single copy: the maximum text content is [clip] token, followed by [hint]. */
        fun capped(): Piece = if (clip != null && TokenEstimate.of(body) > clip) withBody(TokenEstimate.clip(body, clip, "\n$hint")) else this

        /** The total length shall not exceed [n] token (including notes, links and truncation instructions). */
        fun clipTo(n: Int): Piece {
            val room = n - fixed
            if (TokenEstimate.of(body) <= room) return this
            return withBody(TokenEstimate.clip(body, room, "\n$hint"))
        }

        private fun withBody(b: String) = Piece(kind, source, head, b, tail, clip, hint, entry)

        fun message() = Message(Role.User, listOf(Part.Text(envelope(kind, source, head + body + tail))), Author.Mina)
    }

    companion object {
        const val COMMANDS_HEADER = "Site commands for this site (they run read-only on the current page; call one by name with web_run_site_command; if the page is not the right one, open that page first):"
        const val LINKS_HEADER = "Pages phone_open_link can open directly (after opening one, look at the screen before acting):"
        const val CLIPPED_HINT = "…(truncated)"
        private const val OPEN = "<context kind=\""
        private const val CLOSE = "</context>"
        private val PRIORITY = listOf(ContextLayer.Task, ContextLayer.Knowledge, ContextLayer.Skills)
        private val RANK = listOf(ContextKind.Memory, ContextKind.PriorTurns,
            ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex)
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val EXCERPT_CHARS = 60

        /** The beginning of the user's original words: blanks are merged into one space, words beyond [EXCERPT_CHARS] are truncated and ellipses added; if there are no words, it is null. */
        internal fun excerpt(words: String): String? {
            val flat = words.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() } ?: return null
            return if (flat.length <= EXCERPT_CHARS) flat else flat.take(EXCERPT_CHARS).trimEnd() + "…"
        }
        private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** Unified shell. The closing tag in the text is escaped, and the shell will not be closed in advance by the text. */
        fun envelope(kind: ContextKind, source: String, body: String): String =
            "$OPEN${kind.wire}\" source=\"${attr(source)}\" trust=\"${kind.trust.label}\">\n" +
                body.replace(CLOSE, "<\\/context>") + "\n$CLOSE"

        /** What kind of context is this message? The user's original words, tool results, etc. are not contexts, and null is returned. */
        fun kindOf(m: Message): ContextKind? {
            if (m.role != Role.User) return null
            return kindOf((m.parts.firstOrNull() as? Part.Text)?.text ?: return null)
        }

        /** What kind of context does this text belong to (the runtime status connected to other messages can also be recognized). */
        fun kindOf(text: String): ContextKind? {
            if (!text.startsWith(OPEN)) return null
            return ContextKind.ofWire(text.substring(OPEN.length).substringBefore('"'))
        }

        /** Remove the remaining runtime status from the message; if not, return it as is. */
        fun withoutRuntimeState(m: Message): Message {
            if (m.parts.none { it is Part.Text && kindOf(it.text) == ContextKind.RuntimeState }) return m
            return m.copy(parts = m.parts.filterNot { it is Part.Text && kindOf(it.text) == ContextKind.RuntimeState })
        }

        /** The body of the shell (without tags); returns null if not a context. */
        fun bodyOf(m: Message): String? {
            kindOf(m) ?: return null
            return (m.parts.first() as Part.Text).text.substringAfter(">\n").removeSuffix("\n$CLOSE")
        }

        /** The source written on the shell; returns null if not a context. */
        fun sourceOf(m: Message): String? {
            kindOf(m) ?: return null
            return (m.parts.first() as Part.Text).text.substringAfter(" source=\"").substringBefore('"')
        }

        private fun attr(s: String) = s.replace("\"", "'").replace("\n", " ")
    }
}
