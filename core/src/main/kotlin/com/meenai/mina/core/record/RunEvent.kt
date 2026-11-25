package com.meenai.mina.core.record

import com.meenai.mina.core.replay.Anchors
import com.meenai.mina.core.replay.Selector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.meenai.mina.core.observation.InputHints
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.Trust

/** An event in the running record; the placement is a row of events.jsonl, and the `type` field distinguishes the type. */
@Serializable
sealed interface RunEvent {
    val atMs: Long

    /** [origin] Who initiated this run (the lowercase name of `RunOrigin`); do not write if it was initiated on the mobile phone. */
    @Serializable @SerialName("task_started")
    data class TaskStarted(override val atMs: Long, val goal: String, val model: String, val origin: String? = null) : RunEvent

    /** A summary of the system prompt words and tool definitions sent to the model for this task ([com.meenai.mina.core.prompt.PromptVersion]). */
    @Serializable @SerialName("prompt_version")
    data class PromptVersion(override val atMs: Long, val hash: String) : RunEvent

    /** [fallback] means that the standard cannot be listed, [criteria] is just the user's original words. */
    @Serializable @SerialName("criteria")
    data class Criteria(
        override val atMs: Long,
        val criteria: List<String>,
        val ambiguities: List<String>,
        val kind: String = "",
        val fallback: Boolean = false,
    ) : RunEvent

    /** [tool]/[argsJson] is the call actually executed in this round; the parameters have been desensitized. */
    @Serializable @SerialName("model_reply")
    data class ModelReply(
        override val atMs: Long,
        val step: Int,
        val text: String,
        val tool: String?,
        val argsJson: String?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cachedTokens: Int?,
        /** The last request sent to the reply received; previous failed requests and backoffs are recorded separately in [retryWaitMs]. */
        val latencyMs: Long = 0,
        /** The actual model used in this call; null = main model. */
        val model: String? = null,
        /** When the division of labor between large and small models takes effect: main / light / light_rejected (the reply of the lightweight model is not executed, and the main model is asked instead); otherwise null. */
        val role: String? = null,
        /** Time spent before retrying: failed requests plus backoff wait. */
        val retryWaitMs: Long = 0,
        /**
         * The moment when the reply is received: the instructions for this step on the interface, what
         * to do or the answer are displayed from this time (the reply with the call is not recorded
         * until it is executed, [atMs] is later than it). There is no reply or old record of the
         * reassigned lightweight model.
         */
        val repliedAtMs: Long? = null,
    ) : RunEvent {
        /** This reply really drove this step (the reply of the redistributed lightweight model does not count). */
        val acted: Boolean get() = role != "light_rejected"
    }

    /** [resultJson] contains the guarded conclusion, so write it down in its entirety; only characters exceeding [MAX_RESULT] will be truncated and marked. */
    @Serializable @SerialName("tool_result")
    data class ToolResult(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val resultJson: String,
        val changed: Boolean?,
        val screenshotId: Int?,
        /** The tool execution is time-consuming, including stable interfaces within the port; excluding waiting for users (confirmation, questioning, takeover). */
        val durationMs: Long = 0,
        /** Among them are the stable parts of the interface (the value is only when the port can report), and the time when the rhythm is deliberately stopped (the pause is made when changing pages after not reading enough of the web page). */
        val waitMs: Long = 0,
        /** model | batch | replay | script (action in web_script) */
        val source: String = "model",
        /** The id of this call to the model; the route pairs the action with its result. */
        val callId: String? = null,
    ) : RunEvent

    /** The playback route reaches step [step]/[total]; [matched] is false to indicate that it is returned to the model here. */
    @Serializable @SerialName("replay")
    data class Replay(override val atMs: Long, val routeId: String, val step: Int, val total: Int, val matched: Boolean, val detail: String) : RunEvent

    /** A batch of actions reaches step [step]/[total]; when [stopped], [reason] explains why it stops here. */
    @Serializable @SerialName("batch")
    data class Batch(override val atMs: Long, val step: Int, val total: Int, val stopped: Boolean, val reason: String?) : RunEvent

    /**
     * The target element and the front and rear anchor points at the time of a write operation (the tool
     * result is that the element list is incomplete after truncation, and the route depends on it);
     * [argsJson] has been desensitized. [effective]: ok and the interface has changed, or the input
     * readback is consistent, or the startup is successful. [callId] is the same as [ToolResult.callId]
     * in the same call; [protected]: The screen before the action is protected by the system against
     * screenshots.
     */
    @Serializable @SerialName("action_target")
    data class ActionTarget(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val argsJson: String,
        val target: Selector?,
        val before: Anchors,
        val after: Anchors,
        val effective: Boolean,
        val callId: String? = null,
        val protected: Boolean = false,
        /** model | batch */
        val source: String = "model",
        /** The goal and anchor point of this step cannot be obtained (the sub-step in the batch); the route cannot be missing steps, and the entire route cannot be memorized. */
        val incomplete: Boolean = false,
    ) : RunEvent

    /**
     * Save a frame (for visualization: review, screenshot in source): [mediaId] in step [step], where
     * [place] was at that time (package name or "domain name"). The old record is screen_saved (change
     * to this one when reading).
     */
    @Serializable @SerialName("media")
    data class Media(override val atMs: Long, val mediaId: Int, val step: Int, val place: String?) : RunEvent

    /**
     * An observation recorded in the evidence ledger (see
     * [com.meenai.mina.core.observation.Observation]): The original text has been desensitized and
     * truncated, and is the same as the one in the runtime ledger. The review reconstructs the ledger
     * accordingly. [id] is the number in the ledger (the source of the answer refers back to it); [kind]
     * is the type of observation, and [source] is the ability to give it. [inputs] and [focused] are the
     * words in the input box at that time (used to determine the input box echo; null when reading the
     * page content). The old record is screen_text (only the read interface and the full page text are
     * recorded, when reading, change to this one, [id] is 0).
     */
    @Serializable @SerialName("observed")
    data class Observed(
        override val atMs: Long, val step: Int, val text: String, val id: Int = 0, val kind: String = ObsKind.Read.wire,
        val source: String? = null, val place: Place? = null, val title: String? = null,
        val inputs: List<String>? = null, val focused: String? = null, val untrusted: Boolean = false,
    ) : RunEvent {
        /** Return to an observation. */
        fun observation() = Observation(ObsKind.ofWire(kind) ?: ObsKind.Read, text, source, place, title,
            trust = if (untrusted) Trust.Untrusted else Trust.Trusted, inputs = inputs?.let { InputHints(it, focused) }, step = step, atMs = atMs, id = id)

        companion object {
            /** An observation noted in the ledger. */
            fun of(o: Observation) = Observed(o.atMs, o.step, o.text, o.id, o.kind.wire, o.source, o.place, o.title,
                o.inputs?.names, o.inputs?.focused, o.trust == Trust.Untrusted)
        }
    }

    /** The progress of an actual tool call. The reading categories can be displayed together, and the action categories can be displayed one by one; [obsIds] points to the original text read in this step, and [title] is the title of the page read. */
    @Serializable @SerialName("activity")
    data class Activity(
        override val atMs: Long,
        val step: Int,
        val kind: String,
        val label: String,
        val target: String? = null,
        val effect: String? = null,
        val obsIds: List<Int> = emptyList(),
        val source: String = "model",
        val callId: String? = null,
        val title: String? = null,
        /** This call was unsuccessful ([effect] is an error code, only for development). */
        val failed: Boolean = false,
        /** Irreversible judgment recognizes that this is an external action (send, submit); after stopping, when explaining what was done, count the number of times it was sent. */
        val outward: Boolean = false,
    ) : RunEvent

    @Serializable @SerialName("state")
    data class State(override val atMs: Long, val name: String, val detail: String) : RunEvent

    @Serializable @SerialName("confirmation")
    data class Confirmation(
        override val atMs: Long,
        val action: String,
        val target: String?,
        val content: String?,
        val amount: String?,
        val decision: String?,
    ) : RunEvent

    /**
     * The approval conclusion of an action that is external, irreversible or requires money (determined
     * by the code, not the model's readme). Who initiated [source] (model / batch / script / replay);
     * This step costs money when [payment] is true (only it requires user consent when asking);
     * [decision] allowed / required / mismatch / denied.
     */
    @Serializable @SerialName("approval")
    data class Approval(
        override val atMs: Long, val step: Int, val source: String, val tool: String, val action: String, val matched: String,
        val risk: String, val payment: Boolean = false, val decision: String,
    ) : RunEvent

    @Serializable @SerialName("takeover")
    data class Takeover(override val atMs: Long, val reason: String, val outcome: String?, val durationMs: Long?, val inputCount: Int?) : RunEvent

    @Serializable @SerialName("barrier")
    data class Barrier(override val atMs: Long, val kind: String) : RunEvent

    /**
     * Literal verification of the final answer (answer in step [step]): [claims] is the number of
     * verifiable facts in the answer, [unmatched] is the number of facts that cannot be found in the
     * material read; [outward] means that this task has done something external or irreversible (so a
     * separate verification model is requested).
     */
    @Serializable @SerialName("answer_check")
    data class AnswerCheck(override val atMs: Long, val step: Int, val claims: Int, val unmatched: List<String>, val outward: Boolean = false) : RunEvent

    /** In the submitted answers, there were a few things that were still not correct after being sent back once and were marked as unconfirmed. */
    @Serializable @SerialName("unconfirmed")
    data class Unconfirmed(override val atMs: Long, val step: Int, val items: List<String>) : RunEvent

    /** The code finds the answer source (App or web page, step, screenshot of that step) from the evidence ledger; use it for the "source" on the interface. */
    @Serializable @SerialName("sources")
    data class Sources(override val atMs: Long, val items: List<com.meenai.mina.core.verify.AnswerSource>) : RunEvent

    /** [step]: Which step is the final answer being checked (in the old record, it is the finish call; this event may be recorded before or after the reply); the old record does not. */
    @Serializable @SerialName("verifier")
    data class Verifier(override val atMs: Long, val verdict: String, val detailJson: String, val step: Int? = null) : RunEvent

    /** The actual items listed in the memory block of this task (code records, not model readme). */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(override val atMs: Long, val ids: List<String>) : RunEvent

    /** Agent uses memory_save to save one; [status] is active/pending. */
    @Serializable @SerialName("memory_saved")
    data class MemorySaved(override val atMs: Long, val id: String, val kind: String, val status: String) : RunEvent

    /** Personal skills used: [how] = viewed (Agent used skill_read to view it); there is also injected in the old record (the full text was injected from the previous task), which is also used when reading. */
    @Serializable @SerialName("skill_used")
    data class SkillUsed(override val atMs: Long, val name: String, val how: String) : RunEvent

    @Serializable @SerialName("compressed")
    data class Compressed(override val atMs: Long, val foldedSteps: Int) : RunEvent

    /** [outcome]: succeeded / unverified / failed / canceled / error; [kind] is the failed category (lowercase name of FailKind), the rest are null, and there are no old records. */
    @Serializable @SerialName("task_ended")
    data class TaskEnded(override val atMs: Long, val outcome: String, val answerOrReason: String, val kind: String? = null) : RunEvent

    /**
     * The total of all model requests measured during this run (see
     * [com.meenai.mina.core.usage.RunUsageTally]): outside the main loop, every bypass call and failed
     * retry such as intent judgment, verification, image viewing, compression, etc. are counted;
     * [unreported] is the number of times the service provider did not report the usage (or the call
     * failed). Make a note at the end, after [TaskEnded]; the ending judgment and memory retrieval after
     * handing in the answer are not included (the Token usage page is counted). Old records don't.
     */
    @Serializable @SerialName("model_calls")
    data class ModelCalls(
        override val atMs: Long, val calls: Int, val unreported: Int, val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
    ) : RunEvent

    /**
     * After the answer is given to the user, the model is also asked to judge the outcome of this run
     * according to the result the user wants (see [com.meenai.mina.core.agent.OutcomeJudge]): [outcome]
     * is done / partial / not_possible / unknown (not judged as done); [reason] means something is
     * missing in one sentence. Recorded after [TaskEnded]; old records do not.
     */
    @Serializable @SerialName("outcome")
    data class OutcomeJudged(override val atMs: Long, val outcome: String, val reason: String = "", val model: String? = null) : RunEvent

    companion object {
        const val MAX_FIELD = 2000

        /** The upper limit of tool results: much larger than a one-screen element list, just to prevent abnormally large output from overwhelming the record file and memory. */
        const val MAX_RESULT = 100_000

        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

        fun encode(e: RunEvent): String = json.encodeToString(serializer(), e)

        /** One line record; the events (screen_text, screen_saved) before the name change should be changed to the current writing method before reading. */
        fun decode(line: String): RunEvent = json.decodeFromJsonElement(serializer(), LegacyEvents.upgrade(json.parseToJsonElement(line)))

        /** Where it is written in the running record: App is the package name, and the site is the "Site domain name" (the same writing method as in previous records). */
        fun placeLabel(p: Place?): String? = p?.let { if (it.kind == com.meenai.mina.core.observation.PlaceKind.Site) "Site ${it.name}" else it.name }

        fun clip(s: String): String = if (s.length <= MAX_FIELD) s else s.take(MAX_FIELD) + "…(truncated)"

        fun clipResult(s: String): String =
            if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "…(truncated, originally ${s.length} chars)"
    }
}

/** The content of run.json: a summary of a run, accumulated from events. */
@Serializable
data class RunSummary(
    val id: String,
    val goal: String,
    val model: String = "",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val answerOrReason: String? = null,
    val criteria: List<String> = emptyList(),
    val verdict: String? = null,
    /** The outcome of the judgment after handing in the answer ([RunEvent.OutcomeJudged.outcome]); if it has not been judged (failure, cancellation, old record), it is null. */
    val judged: String? = null,
    val steps: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** Who initiated it (see [RunEvent.TaskStarted.origin]); the one initiated on the mobile phone is empty. */
    val origin: String? = null,
) {
    /** Count an event into the summary. */
    fun with(e: RunEvent): RunSummary = when (e) {
        is RunEvent.TaskStarted -> copy(model = e.model, origin = e.origin)
        is RunEvent.Criteria -> copy(criteria = e.criteria)
        is RunEvent.ModelReply -> copy(
            steps = maxOf(steps, e.step),
            inputTokens = inputTokens + (e.inputTokens ?: 0),
            outputTokens = outputTokens + (e.outputTokens ?: 0),
            cachedTokens = cachedTokens + (e.cachedTokens ?: 0),
        )
        is RunEvent.Verifier -> copy(verdict = e.verdict)
        is RunEvent.TaskEnded -> copy(endedAtMs = e.atMs, outcome = e.outcome, answerOrReason = e.answerOrReason)
        is RunEvent.OutcomeJudged -> copy(judged = e.outcome)
        else -> this
    }
}
