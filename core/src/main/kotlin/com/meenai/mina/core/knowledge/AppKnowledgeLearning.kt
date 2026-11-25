package com.meenai.mina.core.knowledge

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.LearnGate
import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.memory.SensitiveFilter
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.skills.ActionDigest
import com.meenai.mina.core.skills.ActionLine
import com.meenai.mina.core.skills.Eligibility
import com.meenai.mina.core.skills.SkillDrafter
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class AppKnowledgeStage { Draft, Trial, Verified, Archived }

/**
 * A version of the App's Self-Study Knowledge Supplement (a short list of just facts, attached to the
 * App's description). [lesson]: Learned from unfinished or stumbling operations; [revises]: The
 * supplement used by this App when drafting, this is its revision (the first supplement is null).
 */
@Serializable
data class AppKnowledgeDraft(val id: String, val appName: String, val packageName: String,
    val markdown: String, val sourceRun: String, val stage: AppKnowledgeStage, val createdAt: Long,
    val trialOutcomes: List<Boolean> = emptyList(), val lesson: Boolean = false, val revises: String? = null,
    /** App name shown to the user (such as "Settings"); there is no old record, and the interface returns to [appName]. */
    val display: String = "")

/** The self-study supplement attached to the App description when injected; when [trial] is true, it is still on trial and has not been used for completed tasks. */
data class LearnedNote(val text: String, val trial: Boolean)


/**
 * Self-taught app knowledge. Each App has at most one to be confirmed ([AppKnowledgeStage.Draft]) and
 * only one injected ([inUse]). The accepted revision temporarily replaces the revised copy (that copy is
 * not injected or deleted) during the trial period; the old revision is archived when the revision is
 * made official, and the old revision is replaced when the revision is archived.
 */
interface AppKnowledgeDraftStore {
    fun all(): List<AppKnowledgeDraft>
    /** If the same App already needs to be confirmed, or [AppKnowledgeDraft.revises] is not the one injected now (outdated revision), it will not be accepted and false will be returned. */
    fun putPending(draft: AppKnowledgeDraft): Boolean
    /**
     * If the revised copy is not the one injected now (outdated), it will be archived on the spot and
     * false will be returned. After accepting, archiving, and trial changes to the one you are currently
     * using, the outdated and pending revisions of the same app are archived together to prevent them
     * from blocking future drafts.
     */
    fun approve(id: String): Boolean
    fun reject(id: String): Boolean
    /** The run of the trial copy is over: [good] is calculated this time ([LearnGate]); press [AppKnowledgeTrial.next] to convert to official, keep or archive. */
    fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft?
    fun notes(): Map<String, LearnedNote>

    /** That complement that this app now injects. */
    fun inUse(appName: String): AppKnowledgeDraft? = AppKnowledgeTrial.inUse(all(), appName)
}

/** How to change the stage of the trial results; it is the same as personal skills: if it is successfully completed, it will be transferred to the formal level. If it is learned from experience, it should be filed if it fails twice in a row, and if it fails once, it will be filed. */
object AppKnowledgeTrial {
    const val LESSON_BAD_STREAK = 2

    fun next(d: AppKnowledgeDraft, good: Boolean): AppKnowledgeDraft {
        val outcomes = d.trialOutcomes + good
        val stage = when {
            good -> AppKnowledgeStage.Verified
            !d.lesson -> AppKnowledgeStage.Archived
            outcomes.size >= LESSON_BAD_STREAK && outcomes.takeLast(LESSON_BAD_STREAK).none { it } -> AppKnowledgeStage.Archived
            else -> AppKnowledgeStage.Trial
        }
        return d.copy(stage = stage, trialOutcomes = outcomes)
    }

    /** The copy injected by [appName] in [rows]: Trial or verified, and has not been temporarily replaced by a revision in trial. */
    fun inUse(rows: List<AppKnowledgeDraft>, appName: String): AppKnowledgeDraft? {
        val app = rows.filter { it.appName == appName }
        val shadowed = app.filter { it.stage == AppKnowledgeStage.Trial }.mapNotNull { it.revises }.toSet()
        return app.firstOrNull { (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified) && it.id !in shadowed }
    }
}

/** A run to watch for drafting: [end] and [outcome] are its endings with handoffs answering the call. */
data class AppRunInput(
    val runId: String, val goal: String, val end: TaskState, val outcome: RunOutcome, val events: List<RunEvent>, val tools: ToolIndex,
)

/**
 * What kind of process does a run in an App belong to: [Done] was successfully completed; [Bumpy] was
 * completed but errors occurred on the way, the interface did not change, or it returned back and forth;
 * [NotDone] was not completed or only partially completed. The latter two are lessons for "taking less
 * detours next time". [wire] is the ending written to the model in the request.
 */
enum class AppRunKind(val wire: String) { Done("done"), Bumpy("bumpy"), NotDone("not_done") }

/**
 * Write the process of running an app once as a revision of the app's knowledge supplement: ask for a
 * model (without tools), give it the built-in description of the app, the supplements in use, the
 * ending, and a streamlined action list (with error codes, interface changes), and ask for the full text
 * after the modification; if there is nothing worth adding (the most common), nothing will be produced.
 *
 * The returned supplements will be accepted as they are; the complete copies with passwords and
 * verification codes will not be accepted.
 */
class AppKnowledgeDrafter(private val model: ChatModel) {
    suspend fun draft(run: AppRunInput, app: KnowledgeEntry, builtIn: String?, current: AppKnowledgeDraft?, id: String, atMs: Long): AppKnowledgeDraft? {
        if (app.surface != Surface.Android || app.packages.isEmpty()) return null
        if (Eligibility.interrupted(run.events) != null || run.events.any { it is RunEvent.Replay && it.matched }) return null
        val lines = ActionDigest.of(run.events, run.tools).filter { it.where in app.packages }
        val kind = kind(run.end, run.outcome, lines, run.tools) ?: return null
        // For supplements that have been verified, if it is successfully completed at one time, it
        // probably does not mean that there is something new (the model will only return NONE): No
        // please
        if (kind == AppRunKind.Done && current?.stage == AppKnowledgeStage.Verified) return null
        val said = when (val end = run.end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> return null
        }
        val existing = current?.let { facts(it.markdown) }
        val request = Prompts.fill(INPUT, mapOf(
            "app" to app.display + " (" + app.packages.joinToString(", ") + ")",
            "outcome" to kind.wire,
            "goal" to run.goal,
            "said" to said,
            "built_in" to builtIn.orEmpty(),
            "current" to existing.orEmpty(),
            "actions" to ActionDigest.render(lines),
        ))
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mina)), emptyList())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        val raw = SkillDrafter.stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
        if (raw.isEmpty() || raw.equals(NOTHING, ignoreCase = true)) return null
        if (!valid(raw, run.tools)) return null
        val facts = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (facts == existing) return null
        return AppKnowledgeDraft(id, app.name, app.packages.first(), facts, run.runId, AppKnowledgeStage.Draft, atMs,
            lesson = kind != AppRunKind.Done, revises = current?.id, display = app.display)
    }

    companion object {
        /** When it is successfully completed, at least this many steps of effective actions in this app are worth writing (the same threshold as personal skill drafting). */
        const val MIN_DONE_ACTIONS = Eligibility.MIN_ACTIONS
        /** The lesson is that you have to go through at least this many steps in this app: those that stop after one or two steps are mostly stuck at the beginning (not logged in, unable to open), and the interface cannot be explained. */
        const val MIN_LESSON_ACTIONS = 3
        /** If there are so many actions that make errors or the interface does not change, it is considered to be a stumbling block: once there is no click, it is common, and twice it means that the entrance or interface is different from expected. */
        const val BUMPY_MISSES = 2
        /** Returning so many times means you have taken a detour: it is common to return once after reading the details, but twice it is probably because you went to the wrong entrance and returned. */
        const val BUMPY_BACKS = 2
        /** Supplement no more than this many items and this many words: When injecting, it should be appended to the description and should not be longer than the description itself. */
        const val MAX_FACTS = 12
        const val MAX_CHARS = 1500
        /** The model only responds with this word when it says "nothing worth fixing". */
        const val NOTHING = "NONE"

        val PROMPT: String by lazy { Prompts.role("app_knowledge") }
        private val INPUT: String by lazy { Prompts.role("app_knowledge_input") }

        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /**
         * A supplementary fact line: remove the header, title and blank lines, not add the ones starting
         * with "-". The old version drafted the entire APP.md, so that you can continue to revise and
         * compare which lines have been changed.
         */
        fun facts(markdown: String): String = FrontMatter.parse(markdown).second.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n") { if (it.startsWith("- ")) it else "- " + it.removePrefix("-").removePrefix("*").trim() }

        /** What kind of process does this run in [app] count? What is not worth learning is null (cancelled, failed to be judged, failed to be verified, and has too few actions). */
        fun kind(end: TaskState, outcome: RunOutcome, lines: List<ActionLine>, tools: ToolIndex): AppRunKind? {
            val misses = lines.count { it.error != null || it.changed == false }
            val backs = lines.count { tools.verb(it.tool) == Verb.Back }
            val bumpy = lines.size >= MIN_LESSON_ACTIONS && (misses >= BUMPY_MISSES || backs >= BUMPY_BACKS)
            val notDone = (end is TaskState.Succeeded && (outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible)) ||
                (end is TaskState.Failed && (end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit))
            return when {
                LearnGate.done(end, outcome) -> when {
                    bumpy -> AppRunKind.Bumpy
                    lines.count { it.error == null } >= MIN_DONE_ACTIONS -> AppRunKind.Done
                    else -> null
                }
                notDone && lines.size >= MIN_LESSON_ACTIONS -> AppRunKind.NotDone
                else -> null
            }
        }

        /** Among the apps I have used on my mobile phone this time, there are only a few that have knowledge items (in order, one for each). Apps without entries have no place to add supplements and no learning. */
        fun touched(events: List<RunEvent>, tools: ToolIndex, knowledge: KnowledgeStore): List<KnowledgeEntry> =
            ActionDigest.of(events, tools).mapNotNull { it.where }.distinct()
                .mapNotNull { knowledge.byPackage(it)?.takeIf { e -> e.surface == Surface.Android } }.distinctBy { it.name }

        /** There are only fact lines starting with "-". The number of entries and words is within the upper limit. Unregistered tool names are not mentioned. Sensitive filtering fails. */
        private fun valid(facts: String, tools: ToolIndex): Boolean {
            val lines = facts.lines().filter { it.isNotBlank() }
            return lines.isNotEmpty() && lines.size <= MAX_FACTS && facts.length <= MAX_CHARS &&
                lines.all { it.startsWith("- ") && it.length > 4 } &&
                TOOL_MENTION.findAll(facts).all { it.groupValues[1] in tools.names } &&
                SensitiveFilter.hit(facts) == null
        }
    }
}
