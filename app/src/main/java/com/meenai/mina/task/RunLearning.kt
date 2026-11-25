package com.meenai.mina.task

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.core.agent.LearnGate
import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.knowledge.FrontMatter
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.knowledge.KnowledgeStore
import com.meenai.mina.core.knowledge.AppKnowledgeDrafter
import com.meenai.mina.core.knowledge.AppKnowledgeDraftStore
import com.meenai.mina.core.knowledge.AppKnowledgeStage
import com.meenai.mina.core.knowledge.AppRunInput
import com.meenai.mina.core.memory.Change
import com.meenai.mina.core.memory.MemoryExtractor
import com.meenai.mina.core.memory.MemorySource
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.skills.DraftInput
import com.meenai.mina.core.skills.DraftResult
import com.meenai.mina.core.skills.Eligibility
import com.meenai.mina.core.skills.PersonalSkills
import com.meenai.mina.core.skills.SkillDrafter
import com.meenai.mina.core.skills.SkillNote
import com.meenai.mina.core.skills.SkillOutcomes
import com.meenai.mina.core.skills.SkillResult
import com.meenai.mina.core.skills.SkillStatus
import com.meenai.mina.memory.RunNotes
import com.meenai.mina.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.meenai.mina.core.tool.ToolIndex

/** After a run is completed, all the materials needed to learn something will be handed over by [TaskRun] at the end of the task. */
data class RunAftermath(
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val end: TaskState,
    /** What the user said this time; it is empty when the memory is turned off. */
    val utterances: List<String>,
    /** Saved by memory_save in the task. */
    val saved: List<Change>,
    val usedMemory: List<String>,
    val toolNames: Set<String>,
    /** Find the actions performed by the tool by name. */
    val tools: ToolIndex,
    /** The events of this run (read from the run record); if not read, it will be empty. */
    val events: suspend () -> List<RunEvent>,
    val origin: RunOrigin = RunOrigin.Ui,
    val knowledge: KnowledgeStore? = null,
    /** The outcome will be judged after handing in the answer; only learn from the completed ones ([LearnGate]). Failure or failure is [RunOutcome.Unknown]. */
    val outcome: RunOutcome = RunOutcome.Unknown,
)

/**
 * After the task is completed: write down the personal skills used and the results of the App knowledge
 * in the trial (which may be automatically deactivated, converted to official or archived), extract
 * memories from the user's words, and draft skills for completed tasks; draft knowledge supplement
 * revisions from the completed, failed and stumbling operations of the App (see [AppKnowledgeDrafter]).
 * Success will be calculated based on the outcome of the judgment after handing in the answer
 * ([RunAftermath.outcome]). There is no need to press "Submit Answer", and there is no requirement to
 * pass the final verification ([LearnGate]). Everything is done in the background coroutine after the
 * result notification is sent ([launchAfter]); any error in any step will not affect the outcome of the
 * task.
 *
 * There is another situation when not drafting: this time the route is taken in the replay
 * ([RunEvent.Replay] has the corresponding steps) - the route has been memorized, and drafting again
 * will only copy the same route again. Only a few batch steps (do_steps) were taken to draft as usual,
 * which was planned by the model itself.
 */
class RunLearning(
    private val switches: MemorySwitches,
    /** It is null when the memory master switch is turned off. */
    private val store: MemoryStore?,
    private val skills: PersonalSkills,
    private val catalog: GuideCatalog,
    private val extractor: MemoryExtractor?,
    private val drafter: SkillDrafter?,
    private val ids: () -> String,
    /** Evaluation hook: Accept the draft immediately after you start it (training wheels). */
    private val autoApprove: Boolean = false,
    private val yieldToForeground: suspend () -> Unit = {},
    private val appDrafter: AppKnowledgeDrafter? = null,
    private val appDrafts: AppKnowledgeDraftStore? = null,
) {
    suspend fun learn(a: RunAftermath): RunNotes {
        yieldToForeground()
        val events = guarded(emptyList()) { a.events() }
        val used = events.filterIsInstance<RunEvent.SkillUsed>()
        val skillNotes = recordOutcomes(used, a.end, a.outcome).toMutableList()
        // App knowledge and personal skills in the trial will be counted according to the same result:
        // canceled and failed judgments will not be recorded.
        val trial = SkillOutcomes.resultOf(a.end, a.outcome)?.takeIf { it != SkillResult.unreviewed }
        val trialNames = events.filterIsInstance<RunEvent.State>().filter { it.name == "knowledge_trial_used" }.map { it.detail }.distinct()
        if (trial != null) trialNames.forEach { name -> guarded(null) { appDrafts?.recordTrial(name, trial == SkillResult.verified) } }

        val changes = a.saved.toMutableList()
        val store = store
        val extractor = extractor
        // If there is no running number, there will be no prompt. The user cannot see it and cannot undo
        // it: no extraction.
        val learnable = a.origin == RunOrigin.Ui && a.end is TaskState.Succeeded
        val readWeb = events.filterIsInstance<RunEvent.Observed>().any { it.place?.kind in setOf(PlaceKind.Site, PlaceKind.Page) }
        if (learnable && !readWeb && a.runId != null && store != null && extractor != null && switches.autoRemember && a.utterances.isNotEmpty()) {
            val now = store.clock()
            changes += guarded(emptyList()) {
                yieldToForeground()
                extractor.extract(a.utterances, MemoryExtractor.related(a.utterances, store.items.value), store) { q ->
                    MemorySource("user_said", q, a.conversationId, now, a.origin.name)
                }
            }
        }

        // Practice only from what has been accomplished: If the answer is truthfully it was not
        // accomplished, only a part of it was accomplished, or it was not judged to be accomplished, no
        // drafting will be done.
        val done = learnable && LearnGate.done(a.end, a.outcome)
        if (done) draft(a, events, used.map { it.name }.distinct())?.let(skillNotes::add)
        // App knowledge is also learned through unfinished and stumbling operations (whether it is worth
        // it or not will be judged by AppKnowledgeDrafter)
        if (a.origin == RunOrigin.Ui) skillNotes += draftApps(a, events)
        return RunNotes(a.conversationId, a.runId.orEmpty(), MemoryExtractor.notes(changes), a.usedMemory, skillNotes)
    }

    /** Read and rewrite each skill separately (note the results at the same time as other tasks without losing the results); mistakes in one will not affect the others. */
    private suspend fun recordOutcomes(used: List<RunEvent.SkillUsed>, end: TaskState, outcome: RunOutcome): List<SkillNote> {
        val result = SkillOutcomes.resultOf(end, outcome) ?: return emptyList()
        return used.groupBy { it.name }.mapNotNull { (name, uses) ->
            val next = guarded(null) {
                skills.update(name) { s -> if (s.status == SkillStatus.active || s.status == SkillStatus.trial) SkillOutcomes.record(s, result, followed = true) else null }
            } ?: return@mapNotNull null
            when (next.status) {
                SkillStatus.suspended -> SkillNote(tr(R.string.skill_suspended), suspended = name)
                SkillStatus.active -> if (uses.isNotEmpty() && result == SkillResult.verified) SkillNote(tr(R.string.skill_verified, name)) else null
                else -> null
            }
        }
    }

    private suspend fun draft(a: RunAftermath, events: List<RunEvent>, used: List<String>): SkillNote? {
        val d = drafter ?: return null
        val runId = a.runId ?: return null
        if (!switches.learnSkills) return null
        if (events.any { it is RunEvent.Replay && it.matched }) return null
        // If you make it based on your personal skills, you will only record the result once and no
        // longer draft it.
        val followed = used.firstOrNull().takeIf { SkillOutcomes.resultOf(a.end, a.outcome) == SkillResult.verified }
        val done = a.end as? TaskState.Succeeded
        if (Eligibility.check(events, followed, a.tools, done = LearnGate.done(a.end, a.outcome)) != null) return null
        val r = guarded(null) {
            yieldToForeground()
            d.draft(DraftInput(runId, a.goal, done?.answer.orEmpty(), done?.sources.orEmpty(), events, a.toolNames, a.tools), skills, catalog)
        }
        val draft = r as? DraftResult.Draft ?: return null
        // An update to a pending draft: write back that draft, don’t start a new one
        val id = draft.replacesPending ?: ids()
        guarded(Unit) { skills.putPending(id, draft.skill) }
        if (autoApprove) {
            guarded(null) { skills.approve(id) }
            return null
        }
        val description = FrontMatter.parse(draft.skill.markdown).first["description"] as? String ?: draft.skill.name
        return SkillNote(tr(R.string.skill_learned, description), draftId = id)
    }

    /** Each App that has been used this time and has knowledge entries will be judged once; if the same App has revisions that need to be confirmed, it will not be drafted again. */
    private suspend fun draftApps(a: RunAftermath, events: List<RunEvent>): List<SkillNote> {
        val drafter = appDrafter ?: return emptyList()
        val drafts = appDrafts ?: return emptyList()
        val knowledge = a.knowledge ?: return emptyList()
        val runId = a.runId ?: return emptyList()
        if (!switches.learnSkills) return emptyList()
        val run = AppRunInput(runId, a.goal, a.end, a.outcome, events, a.tools)
        return AppKnowledgeDrafter.touched(events, a.tools, knowledge).mapNotNull { app ->
            if (guarded(true) { drafts.all().any { it.appName == app.name && it.stage == AppKnowledgeStage.Draft } }) return@mapNotNull null
            val current = guarded(null) { drafts.inUse(app.name) }
            val draft = guarded(null) {
                yieldToForeground()
                drafter.draft(run, app, knowledge.builtIn(app.name), current, ids(), events.lastOrNull()?.atMs ?: 0L)
            } ?: return@mapNotNull null
            if (!guarded(false) { drafts.putPending(draft) }) return@mapNotNull null
            SkillNote(tr(if (draft.revises != null) R.string.skill_app_revised else R.string.skill_app_draft, app.display))
        }
    }

    /** Any mistakes made in each step of learning will not affect other steps; cancellations will be thrown out as usual. */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    companion object {
        /** Send the results notification first, and then learn in the background: users see the results first, and it doesn’t matter if they learn things slowly. */
        suspend fun launchAfter(scope: CoroutineScope, notify: suspend () -> Unit, learn: suspend () -> Unit): Job {
            notify()
            return scope.launch { learn() }
        }
    }
}
