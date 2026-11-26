package com.meenai.mina.core.agent

import com.meenai.mina.core.trace.RunTrace
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.RouterStatus
import com.meenai.mina.core.router.picker.TaskDifficulty
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.observation.tag
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.PromptVersion
import com.meenai.mina.core.tool.parseArgs
import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionOutcome
import com.meenai.mina.core.action.ActionRecorder
import com.meenai.mina.core.action.OutwardPacing
import com.meenai.mina.core.action.ApprovalSettings
import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.core.action.FollowUp
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.action.Presented
import com.meenai.mina.core.action.Refusals
import com.meenai.mina.core.access.AppAccessDenied
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.gate.ConfirmationGate
import com.meenai.mina.core.knowledge.ChannelAdvisor
import com.meenai.mina.core.knowledge.ChannelFacts
import com.meenai.mina.core.knowledge.KnowledgeEntry
import com.meenai.mina.core.knowledge.KnowledgeStore
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.MemoryContext
import com.meenai.mina.core.memory.ProfileContext
import com.meenai.mina.core.memory.ProfileEntry
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.knowledge.toolsNote
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.run.RunEventBus
import com.meenai.mina.core.run.RunInbox
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.run.RunProvenance
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.Interaction
import com.meenai.mina.core.run.LocalInteraction
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.tool.HandBack
import com.meenai.mina.core.tool.HandBackOutcome
import com.meenai.mina.core.tool.Outcome
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.replay.RouteMatcher
import com.meenai.mina.core.verify.Verifier
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId
import com.meenai.mina.core.userscreen.UserScreenSource
import com.meenai.mina.core.general.HISTORY_READ
import com.meenai.mina.core.general.VIEW_USER_SCREEN

/** Another thing to look at after finishing the batch: the content and the picture are at the same time. */
private fun batchViewNote(content: String) = Feedback.format("batch_view_note", "screen" to content)
private val MEMORY_VERBS = setOf(Verb.MemorySearch, Verb.MemorySave)
/** When discussing history, you can still check local memory and skill data, and records from previous rounds; these calls will not open the app or website. */
private val DISCUSSION_LOOKUP_VERBS = setOf(Verb.MemorySearch, Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.HistoryRead)

/**
 * Drive the "model ↔ tool" cycle and only do scheduling: ask the model ([ModelCaller]) → hand over the
 * execution entry reminders ([ActionExecutor]) → decides, suggestions and endings according to the
 * strategy ([TurnPolicy], [StopRules], [StuckAdvice], [NoticePolicy]) → write into history. Ending: The
 * reply without tool call is the final answer to the user (for verification, see [AnswerReview], the
 * source is found in the evidence account book by the code); the text written with the call is the
 * progress description ([TaskState.Running.narration]). If it cannot be done, you need to ask the user,
 * or the user must do it by hand, and each will go to end_not_done, ask_user, and hand_to_user. Only
 * empty replies (or replies truncated by the output limit) will be reminded, and only once. Only the
 * first tool call is executed in each round, [gate] (suspended when the preconditions of the capability
 * are not met, such as mobile phone lock screen) is passed before the write operation, and the guard
 * conclusion is put into the guard field of the tool result. Before the gate is suspended, use
 * setWaiting to report the reason ([TaskState.Held] given by the capability), and pass null when
 * releasing; the status will be displayed to the outside during the waiting period, and Running will be
 * restored after the end. The running process is handed over to [sink] (desensitized, large field
 * truncated) as [RunEvent], and the saved screen is handed over as [RunSink.media].
 */
class AgentLoop(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val basePrompt: String,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val gate: suspend (setWaiting: (TaskState?) -> Unit) -> Unit = {},
    private val knowledge: KnowledgeStore? = null,
    sink: RunSink = RunSink.NONE,
    /** The installed App (package name, display name) is only used to select reference materials according to the task text; if it cannot be obtained, it will be processed as an empty list. */
    private val installedApps: suspend () -> List<Pair<String, String>> = { emptyList() },
    /** Before the final answer is handed over, independent verification is only requested based on risk (see [AnswerReview]); when it is null, only literal verification is done (the product path must be given). */
    private val verifier: Verifier? = null,
    /** Payment confirmation level; the independent execution path passes null and does not provide request_confirmation to the model. */
    private val confirmations: ConfirmationGate? = ConfirmationGate(),
    /** Ask / Autonomous (if you switch to Autonomous halfway, follow it from the next step); Default is Ask. */
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    /** The time limit for waiting for the user to confirm the response, then press disagree; if it is null, keep waiting (testing). */
    confirmTimeoutMs: Long? = null,
    /** Mina's own package name: write operations acting on it are not performed. */
    private val selfPackage: String? = null,
    /** The time limit for waiting for the user to answer the follow-up question (ask_user and the ambiguous question before starting work); the timing is based on the clock of the coroutine scheduler, and virtual time is used in the test. */
    inputTimeoutMs: Long = DEFAULT_INPUT_TIMEOUT_MS,
    /** Facts for selecting channels; if given, inject a line of "available channels" at the beginning of the task. */
    private val channelFacts: (suspend () -> ChannelFacts)? = null,
    /** Speed-up switch; fully off by default. */
    val flags: SpeedFlags = SpeedFlags.OFF,
    /** The clock used for event time and elapsed time; replace it with a fake clock in the test. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Division of labor between large and small models; only used when flags.lightSteps is opened. */
    router: ModelRouter? = null,
    /** Independent intent judgment when the tool is used for the first time in the UI dialog; null retains the original behavior of the evaluation and other entries. */
    intentJudge: ChatModel? = null,
    /** Route matching for experience replay; only used when flags.replay is turned on. */
    routes: RouteMatcher? = null,
    /** Skill directory (including personal skills); all skills are listed at the beginning of the task, and several App/site knowledge indexes are given according to the target. No index is given when it is null or [recommendSkills] is turned off. */
    private val skills: GuideCatalog? = null,
    private val recommendSkills: Boolean = true,
    /** memory; when null (or master switch off), no retrieval, no injection, no memory_* facilities are provided. */
    private val memory: MemoryStore? = null,
    /** The local time in the runtime state is written according to this time zone. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Retry when the model call encounters a temporary failure (current limiting, 5xx, network). */
    retry: RetryPolicy = RetryPolicy(),
    /** Replies without calls are accepted and asked once before the final answer (see [BeforeAccept]); the default is accepted. The last round after the number of steps is used up is not asked. */
    private val beforeAccept: suspend (answer: String) -> BeforeAccept = { BeforeAccept.Accept },
    private val interaction: Interaction = LocalInteraction,
    private val profileSnapshot: List<ProfileEntry> = emptyList(),
    private val autonomous: Boolean = false,
    /** The interactive interface enables visible text flow; evaluation and background calls maintain the original request format. */
    private val streamReplies: Boolean = false,
    /** The screen sent by the user in this conversation (see [UserScreenTurns]); if not, the line with the screen is given to the model as it is. */
    private val userScreens: UserScreenSource? = null,
    /** The evaluation can retain the old closing verification; the interactive product observes the results from the execution process, and the closing only sorts out the sources. */
    private val reviewFinalAnswer: Boolean = true,
    /** The interval between two consecutive external actions (send, submit) on the same website or App; the default is not equal. */
    private val outwardPacing: OutwardPacing = OutwardPacing.Off,
    private val trace: RunTrace? = null,
    val freeKeyRouter: FreeKeyRouter? = null,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        // Only when playback is turned on will the target element and anchor point be fetched before
        // writing.
        tools.captureTargets = flags.replay
    }

    /** Running records: written in order of cause and effect (see [RecordOrder]). */
    val events = RunEventBus().also { bus ->
        bus.subscribe(sink)
        trace?.let { bus.subscribe(it) }
    }
    private val sink = RecordOrder(events).also { order ->
        // Each observation entered into the evidence ledger is entered into the run log exactly as it is
        // (followed by the result of the call in which it was read).
        tools.observer = { record { order.observed(RunEvent.Observed.of(it)) } }
    }

    private val _activeModelName = MutableStateFlow<String?>(null)
    /** Name/ID of the model currently answering the active request. */
    val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

    private val caller = ModelCaller(model, tools, router, flags, retry, ::emit, ::now, autonomous, trace) { usingName ->
        _activeModelName.value = activeRouter?.status?.value?.currentModel ?: activeRouter?.activeCandidate?.id ?: usingName
    }
    private val intentGate = intentJudge?.let { IntentGate(it, model.takeUnless { main -> main === it }) }
    private val routeMatcher = routes
    /** Context generator for this task (shell, budget, given references). */
    private var ctx = ContextBuilder()
    /** The screen sent by the user in this mission (built at the start). */
    private var screens: UserScreenTurns? = null
    /** This time the original words look like when given to the model (replaced with instructions when using the screen, see [UserScreenTurns]); it is null before it starts. Determine the outcome and retrieve memory by clicking it to read. */
    @Volatile var taskText: String? = null
        private set
    /**
     * The words he typed in the user's most recent sentence (original sentence at the beginning or an
     * interruption) are shown in [ownWords]; when there is no typing in the interruption (only the
     * screen is sent and only words are selected), the previous sentence is used. Press it for language
     * prompts and language checks for answers; [UserWords.said] (also recognize what he quoted from
     * himself) for citations for memory and skills.
     */
    private var latestWords = ""

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private val activeRouter: FreeKeyRouter? get() = freeKeyRouter ?: (model as? FreeKeyRouter)
    val routerStatus: StateFlow<RouterStatus>? get() = activeRouter?.status

    @Volatile private var usedMemory: List<String> = emptyList()
    /** The entry number listed in the memory block of this task; read after the task is completed. */
    val usedMemoryIds: List<String> get() = usedMemory

    /** The ability groups really used in this mission (recorded in this round and pre-expanded at the beginning of the next round); read after the mission is over. */
    val usedGroups: Set<String> get() = tools.used

    /** What the user said this time (used for citation verification of memory_save and skill_save); collected as soon as it is given, only in memory, and cleared at the beginning of each task. */
    private val utterances get() = tools.utterances

    private val user = UserExchange(tools, confirmations, confirmTimeoutMs, inputTimeoutMs, { utterances }, { _state.value = it }, ::emit, ::now)
    private val replay = routes?.let { RouteReplay(it, tools, ::awaitGate, { r -> gateState = r; _state.value = r }, ::emit, clock) }

    @Volatile private var job: Job? = null
    /** The interface state to be restored after locking the screen/taking away the level and waiting (current step, batch or playback). */
    @Volatile private var gateState: TaskState = TaskState.Idle
    @Volatile private var cancelRequested = false
    /** The step at which the request is interrupted is removed; later late streaming text is no longer displayed. */
    @Volatile private var interruptedStep = 0
    /** What the users said in the previous rounds of this dialogue is only for the verifier to understand and follow up; it does not contain anything about the work model. */
    private var earlierGoals: List<String> = emptyList()
    /** The ending of this run; the route count will be used after the ending is determined ([noteOutcome]). */
    @Volatile private var lastEnd: TaskState? = null
    private val review = AnswerReview(tools, verifier, ::emit, ::now)
    /** The screen number saved in each step of this task (step → number), click the source of the answer to attach the screen. */
    private val media = mutableMapOf<Int, MutableList<Int>>()
    /** The answers to this task have been returned once because they were not correct during verification; if they are not correct again, they will be marked as unconfirmed and handed over, and will not be returned again. */
    private var gapsNudged = false
    private var provenance = RunProvenance()
    val activeAsk: Delivery.Ask? get() = user.activeAsk

    /** New entrance: Interruption and control in the same conversation are entered via [inbox]; the old entrance continues to use the same execution path. */
    suspend fun run(spec: RunSpec, inbox: RunInbox): TaskState = runInternal(spec.goal, spec.priorTurns, inbox, spec.provenance, spec.threadId)

    suspend fun run(task: String, priorTurns: List<Turn> = emptyList()): TaskState = runInternal(task, priorTurns, null, RunProvenance(), "local")

    private suspend fun runInternal(task: String, priorTurns: List<Turn>, inbox: RunInbox?, runProvenance: RunProvenance, threadId: String): TaskState {
        cancelRequested = false
        provenance = runProvenance
        user.begin(threadId, runProvenance, interaction)
        inbox?.bind(object : RunInbox.Handler {
            override suspend fun answer(text: String, askId: String?) = user.answerInput(text, askId)
            override suspend fun confirm(choice: ConfirmChoice, askId: String?) = user.answerConfirmation(choice, askId)
            override suspend fun handBack(result: HandBack, askId: String?) = user.resumeAfterTakeover(result, askId)
            override fun cancel() = this@AgentLoop.cancel()
        })
        caller.reset(basePrompt)
        activeRouter?.resetTask()
        _activeModelName.value = activeRouter?.status?.value?.currentModel ?: activeRouter?.activeCandidate?.id ?: model.name
        // Agree is only valid for one mission
        confirmations?.reset()
        utterances?.clear()
        emit(RunEvent.TaskStarted(now(), Redactor.text(task), model.name,
            origin = runProvenance.origin.takeIf { it != RunOrigin.Ui }?.name?.lowercase()))
        var end: TaskState? = null
        replay?.reset()
        lastEnd = null
        try {
            end = runLoop(task, priorTurns, inbox)
            lastEnd = end
            return end
        } catch (e: CancellationException) {
            end = TaskState.Cancelled
            throw e
        } finally {
            _activeModelName.value = null
            emit(ended(end))
            inbox?.close()
        }
    }

    private suspend fun runLoop(task: String, priorTurns: List<Turn>, inbox: RunInbox?): TaskState {
        val end = try {
            coroutineScope {
                val routerWatch = activeRouter?.status?.let { s ->
                    launch { s.collect { rs -> rs.currentModel?.let { _activeModelName.value = it } } }
                }
                // The intervening signal is installed in the entire running context: the wait will be
                // terminated early without losing anything if it is removed (see waitOrSteer)
                val body = async(inbox?.signal ?: EmptyCoroutineContext) { loop(task, priorTurns, inbox) }
                job = body
                if (cancelRequested) body.cancel()
                try {
                    body.await()
                } finally {
                    routerWatch?.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (!cancelRequested) {
                _state.value = TaskState.Cancelled
                throw e
            }
            TaskState.Cancelled
        } finally {
            job = null
            user.clear()
            confirmations?.reset()
        }
        _state.value = end
        return end
    }

    /** [gate] before the write operation: The waiting reason is displayed externally during the suspension period, and [gateState] is restored after the completion. */
    private suspend fun awaitGate() {
        try {
            gate { waiting ->
                _state.value = waiting ?: gateState
                if (waiting != null) emit(waitingEvent(waiting))
            }
        } finally {
            _state.value = gateState
        }
    }

    /**
     * The answer handed in is called after passing the ending ([OutcomeJudge]): replaying the completed
     * route will only be recorded as a success when it is completed ([LearnGate]). Failed and canceled
     * tasks are not judged and do not need to be called; if they are not called, success will not be
     * recorded.
     */
    suspend fun noteOutcome(outcome: RunOutcome) {
        val end = lastEnd ?: return
        replay?.noteResult(end, outcome)
    }

    /** Hands back the outcome of the handover; the outcome is handed to the model as the result of the hand_to_user tool. */
    suspend fun resumeAfterTakeover(result: HandBack) = user.resumeAfterTakeover(result)

    /** The old return method: it is always considered complete; when [note] is [USER_FINISHED_NOTE], it means that the user said it has been completed by himself. */
    suspend fun resumeAfterTakeover(note: String) =
        resumeAfterTakeover(HandBack(HandBackOutcome.complete, userFinished = note == USER_FINISHED_NOTE))

    /** Answer follow-up questions; return false when there are no waiting questions (timed out, answered), and the interface will tell the user that the answer is late. */
    suspend fun answerInput(text: String): Boolean = user.answerInput(text)

    suspend fun answerConfirmation(approved: Boolean) = answerConfirmation(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny)

    suspend fun answerConfirmation(choice: ConfirmChoice) = user.answerConfirmation(choice)

    fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    private suspend fun hasAttachedImageInput(raw: String): Boolean {
        val parsedScreens = com.meenai.mina.core.userscreen.UserScreenMessage.parse(raw).screens
        if (parsedScreens.isNotEmpty() && userScreens != null) {
            val hasScreenImage = parsedScreens.any { id ->
                try {
                    val s = userScreens.screen(id)
                    s != null && !s.blocked && userScreens.image(id) != null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
            if (hasScreenImage) return true
        }
        return AttachmentLines.parse(raw).files.any { (path, _) ->
            path.substringAfterLast('.', "").lowercase() in IMAGE_ATTACHMENT_EXTS
        }
    }

    private suspend fun loop(raw: String, rawPrior: List<Turn>, inbox: RunInbox?): TaskState {
        // If the opening message or prior turns carry an unblocked user screenshot or image attachment,
        // ensure FreeKeyRouter switches to a vision-capable candidate BEFORE UserScreenTurns
        // decides whether to attach Part.Image.
        val priorHasImage = rawPrior.any { t ->
            hasAttachedImageInput(t.userText) || t.steers.any { hasAttachedImageInput(it) }
        }
        val rawHasImage = hasAttachedImageInput(raw) || priorHasImage
        if (activeRouter != null && rawHasImage) {
            activeRouter?.startTask(raw, hasImage = true, charCount = raw.length)
        }
        // The screen sent by the user: this sentence is for the whole (that sentence, screenshot,
        // details), and the harvest in the previous rounds is one row; the replaced sentence is used
        // below.
        val canUseViewUserScreen = tools.vision != null && tools.definitions().any { it.name == VIEW_USER_SCREEN }
        val shots = UserScreenTurns(
            source = userScreens,
            zone = zone,
            seesImages = { activeRouter?.capabilities?.vision ?: tools.seesImages },
            canAskVision = { !(activeRouter?.capabilities?.vision ?: tools.seesImages) && canUseViewUserScreen },
            image = { tools.modelImage(it, null) },
            seen = tools::noteUserScreen,
        ).also { screens = it }
        user.answerText = shots::asText
        val opening = shots.render(raw)
        val task = opening?.said ?: raw
        taskText = task
        val hasOpeningImage = rawHasImage || opening?.images?.isNotEmpty() == true
        val routerDifficulty = activeRouter?.startTask(task, hasImage = hasOpeningImage, charCount = task.length)
        if (routerDifficulty == TaskDifficulty.Heavy && activeRouter?.pinnedModelId == null) {
            emit(RunEvent.State(now(), "difficulty_classified", "Mushkil task hai — behtar model use kar raha hoon"))
        }
        latestWords = ownWords(raw, opening)
        val priorTurns = shots.collapse(rawPrior)
        earlierGoals = priorTurns.map { it.userText }
        // The previous rounds are only for reference and placed before this task.
        val context = Conversation.context(priorTurns)
        utterances?.add(UserWords.said(opening?.words ?: raw, tools))
        // Memory block: after the dialogue above and before the original task text; the items used are
        // recorded by the code
        usedMemory = emptyList()
        // If the memory file was not read out last time (the key library is temporarily unavailable),
        // read it again; if it still cannot be read, then it will be read without memory this time. Use
        // Auto for the product path: if the total amount of memory can be loaded, inject the full amount
        // (without missing the search), and if it cannot be loaded, return to search.
        val block = memory?.takeIf { it.reloadIfNeeded() }?.let {
            MemoryContext.build(it.items.value, task, priorTurns.lastOrNull()?.userText, tools.legacy::rewrite,
                mode = MemoryContext.Mode.Auto)
        }
        if (block?.message != null) {
            usedMemory = block.usedIds
            memory?.markUsed(block.usedIds)
            emit(RunEvent.MemoryUsed(now(), block.usedIds))
        }
        // The start is pre-expanded: the groups used in the last round, and the groups that will
        // definitely be used this time (see ToolDispatcher.preload)
        tools.preload(task, priorTurns.lastOrNull()?.groups.orEmpty()).forEach { emit(RunEvent.State(now(), "tools_preloaded", it)) }
        var openGroups = tools.expanded
        val batchSpec = BatchRunner.spec(tools.batchable()).takeIf { flags.batchSteps }
        fun keep(s: ToolSpec): Boolean {
            val verb = tools.verb(s.name)
            return (confirmations != null || verb != Verb.RequestConfirmation) && (memory != null || verb !in MEMORY_VERBS)
        }
        // The prompt word version is calculated as each group is expanded: the same version of prompt
        // words does not differ depending on which groups are expanded this time.
        val everything = tools.offeredNow(listOfNotNull(batchSpec), all = true).filter(::keep)
        emit(RunEvent.PromptVersion(now(), PromptVersion.of(SystemPrompt.assemble(basePrompt, tools.promptSections(everything, all = true)), everything)))
        var specs = emptyList<ToolSpec>()
        // Before each question about the model: when a new group is expanded, the tool and system prompt
        // words change; if not expanded, the words remain unchanged.
        fun refreshOffer() {
            val offer = tools.offeredNow(listOfNotNull(batchSpec)).filter(::keep)
            if (offer == specs) return
            specs = offer
            caller.systemPrompt = SystemPrompt.assemble(basePrompt, tools.promptSections(specs))
            (tools.expanded - openGroups).forEach { emit(RunEvent.State(now(), "tools_opened", it)) }
            openGroups = tools.expanded
        }
        refreshOffer()
        val guards = OperationGuards(maxSteps)
        val recorder = ActionRecorder(sink, clock, saveScreen = { id, png -> sink.media(id, png) })
        val executor = ActionExecutor(tools, guards, confirmations, gate = ::awaitGate, recorder = recorder, offScreen = tools.offScreenGuard(), approvals = approvals,
            provenance = provenance,
            selfPackage = selfPackage, autonomous = autonomous, pacing = outwardPacing)
        var emptyNudged = false
        var imagePromptNudged = false
        var emptyReplySwitches = 0
        // Only after adjusting the tool can you have materials that can be verified; giving the user
        // payment is the same as releasing the external action, and the answer needs to be verified
        // separately.
        var acted = false
        var paymentHandedOver = false
        // The user has answered the follow-up question and has not done anything else: At this time, he
        // must submit his answer and be reminded once to do so (see [owedFollowUp])
        var answered: String? = null
        var followUpNudged = false
        var repeatNudged = false
        var resultReadNudged = false
        var languageNudged = false
        // The reply that was missed into text when the tool was called was just returned once (see
        // [LeakedToolCall]); it will be calculated again after adjusting the tool normally.
        var leakNudged = false
        fun facts() = ReviewFacts(acted, paymentHandedOver || recorder.outwardActs > 0, recorder.lastChangeMark?.let { !tools.readSince(it) } == true)
        media.clear()
        gapsNudged = false
        interruptedStep = 0
        var note = ""
        // All contexts other than the user’s original words are generated through it: unified shell,
        // unified budget
        ctx = ContextBuilder(zone = zone)
        val installed = try { installedApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val labels = installed.toMap()
        val matched = knowledge?.matchTask(task, Int.MAX_VALUE, installed).orEmpty()
        val index = if (skills != null && recommendSkills) GuideRecommender.index(skills, task) else null
        val start = ctx.start(
            task, priorTurns = context?.let { (it.parts.single() as Part.Text).text }?.let { prior ->
                // Say something when you can find out what you have read in previous rounds (only when
                // there is a demerit running round and history_read is provided)
                if (tools.definitions().any { it.name == HISTORY_READ }) Feedback.format("history_hint", "turns" to prior) else prior
            },
            memory = listOfNotNull(ProfileContext.render(profileSnapshot), block?.message).takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            channel = if (knowledge != null) channelLine(matched) else null,
            knowledge = matched, knowledgeNote = toolsNote(tools.scriptsOffered()), withLinks = flags.deepLinks,
            skillsIndex = index,
            taskImages = opening?.images.orEmpty(), screens = opening?.details.orEmpty(),
        )
        val history = start.messages.toMutableList()
        if (routerDifficulty == TaskDifficulty.Heavy && activeRouter?.pinnedModelId == null) {
            history += ctx.notice("task_difficulty", "Mushkil task hai — behtar model use kar raha hoon")
        }
        start.knowledge.filter { it.trial }.forEach { emit(RunEvent.State(now(), "knowledge_trial_used", it.name)) }
        // Links are only given when open_link is available, and only those copies that are really given
        // to the model are released.
        if (flags.deepLinks) start.knowledge.forEach { if (it.links.isNotEmpty()) tools.allowLinks(it.links) }
        // Each site on the web page only searches for knowledge once by domain name when entering for
        // the first time.
        val visitedSites = mutableSetOf<String>()

        // Where the task is at the moment (App, site...); it is given by the capability side according
        // to the result of each call
        var place: Place? = null
        var stuck = StuckState()
        var lastProgressVersion = guards.progressVersion
        fun stuckAdvice(verdicts: List<GuardVerdict>, typed: String?): String? {
            val version = guards.progressVersion
            val (next, advice) = StuckAdvice.next(stuck, StuckInput(verdicts, typed, place?.name, version != lastProgressVersion))
            lastProgressVersion = version
            stuck = next
            return advice
        }
        val track = StepTrack()
        var intent: IntentGate.Intent? = null
        var intentAssessed = false
        // It is only judged once per run; if it cannot be judged (the call fails, the reply is not one
        // of the three words), it is null, and the main model follows its own decision.
        suspend fun assessIntent(): IntentGate.Intent? {
            intentAssessed = true
            // Only the screen is sent, no words are spoken: there is no words to judge, and the rules of
            // guessing the intention of the screen are handed over to the main model.
            val result = if (opening != null && opening.words.isEmpty()) null else intentGate?.assess(task, priorTurns)
            emit(RunEvent.State(now(), "intent_gate", result?.name ?: "unavailable"))
            return result
        }
        // Before calling the model for the first time: if there is a suitable route, follow it without
        // adjusting the model; return the model after completing it or if it is not correct. The method
        // of only recording the route and only putting it on the mobile phone: do not play back the
        // tasks that are called on the web page.
        if (flags.replay && replay != null && !task.trimStart().startsWith(WEB_CHANNEL_PREFIX) && !task.trimStart().startsWith("用网页")) {
            val reviewRoute = provenance.origin == RunOrigin.Ui && intentGate != null && routeMatcher?.candidates(task)?.isNotEmpty() == true
            if (reviewRoute) intent = assessIntent()
            if (!reviewRoute || intent == IntentGate.Intent.Read || intent == IntentGate.Intent.Act) {
                replay.run(task, history, executor, ctx, track)?.let { place = it; acted = true }
            }
        }

        for (step in 1..maxSteps) {
            appendSteers(history, inbox)
            if (maxSteps != Int.MAX_VALUE) TurnPolicy.beforeStep(TurnSnapshot(step, maxSteps))?.let { history += ctx.notice(it.source, it.text) }
            appendRuntimeState(history, place, labels, step)
            _state.value = TaskState.Running(step, note)
            refreshOffer()
            val stepHasImages = history.any { m -> m.parts.any { it is Part.Image } }
            val prevCandidate = activeRouter?.activeCandidate?.id
            activeRouter?.prepareStep(step, stepHasImages)
            val nextCandidate = activeRouter?.activeCandidate?.id
            if (nextCandidate != null && nextCandidate != prevCandidate) {
                val switchReason = if (activeRouter?.difficultyRouter?.isEscalated == true) {
                    "Ye model jawab nahi de paya — behtar model pe switch kiya"
                } else {
                    "Switched to $nextCandidate"
                }
                emit(RunEvent.State(now(), "model_switched", "Switched to $nextCandidate — $switchReason"))
                history += ctx.notice("model_switch", switchReason)
            }
            var asked = now()
            val routed = try {
                routedOrRedirect(inbox, history, specs, track, step, { asked = it }, previewFor(step), thinkingFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return StopRules.modelFailed(e)
            }
            if (routed == null) {
                keepInterrupted(history, inbox, step)
                continue
            }
            var (reply, used) = routed
            reply = com.meenai.mina.core.model.LeakedToolMarkup.repair(reply)
            val proposed = reply.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (provenance.origin == RunOrigin.Ui && intentGate != null && proposed != null &&
                tools.verb(proposed.name) !in DISCUSSION_LOOKUP_VERBS) {
                if (!intentAssessed) intent = assessIntent()
                if (intent == IntentGate.Intent.Discuss) {
                    // The proposed call has not yet been written into the history and has not been
                    // executed; ask again, only giving a chance for a direct answer.
                    val discussion = history + ctx.notice("conversation_intent",
                        "The user is discussing or reviewing this time. Answer this question directly, without reading or operating apps, websites or other outside content.")
                    reply = try { caller.ask(discussion, emptyList(), step, using = model, onText = previewFor(step), onThinking = thinkingFor(step)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { return StopRules.modelFailed(e) }
                    reply = com.meenai.mina.core.model.LeakedToolMarkup.repair(reply)
                    used = model
                }
            }
            val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            history += Message(Role.Assistant, reply.parts, Author.Mina).also { caller.noteReply(it, used, reply.usage) }
            val calls = reply.parts.filterIsInstance<Part.ToolCall>()
            note = text.ifEmpty { calls.firstOrNull()?.name ?: note }
            val tool = calls.firstOrNull()?.name
            val firstParsedArgs = calls.firstOrNull()?.let { parseArgs(it.argsJson) }
            val reason = calls.firstOrNull()?.let { firstParsedArgs?.let { parsed -> tools.callReason(it.name, parsed) } }
            val actionTarget = firstParsedArgs?.let(::safeActionTarget)
            val replyThinking = reply.parts.filterIsInstance<Part.Thinking>().joinToString("\n") { it.text }.trim().takeIf(String::isNotEmpty)
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            // The words written with the call are progress instructions. The real-time line on the
            // interface uses it first.
            val narration = text.takeIf { calls.isNotEmpty() && it.isNotEmpty() }
            _state.value = TaskState.Running(step, note, tool, reason = reason, narration = narration,
                preview = text.takeIf { streamReplies && calls.isEmpty() && it.isNotEmpty() },
                thinking = replyThinking, actionTarget = actionTarget)
            // The reply with the call will be recorded after the execution is completed: it depends on
            // whether the port reports a password box.
            fun recordReply(argsJson: String?) = emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(text)), tool, argsJson,
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs,
                if (caller.recordRoles) used.name else null, if (caller.recordRoles) caller.roleOf(used) else null, retryWaitMs = retryWaitMs,
                repliedAtMs = repliedAtMs,
            ))

            if (calls.isEmpty()) {
                track.after(null, ok = false, guard = false, changed = null, recovery = false, app = null)
                recordReply(null)
                // When the model gives the final answer, it first accepts comments from users on the
                // way, and then decides whether to hand over the answer.
                if (keepFinished(history, inbox)) continue
                // Empty replies and half-sentences cut off by the output limit are not answers: remind
                // me once, and it will be over if you do this again
                if (text.isEmpty() || reply.stopReason == StopReason.MaxTokens) {
                    val n = TurnPolicy.afterEmpty(emptyNudged, truncated = text.isNotEmpty())
                    if (n != null) {
                        emptyNudged = true
                        history += ctx.notice(n.source, n.text)
                        continue
                    }
                    val router = activeRouter
                    if (router != null && emptyReplySwitches < 2) {
                        val currentModelName = _activeModelName.value ?: router.activeCandidate?.id
                        val next = router.failoverEmptyReply(currentModelName, reason = "Empty reply from previous model", hasImages = stepHasImages)
                        if (next != null) {
                            emptyReplySwitches++
                            emptyNudged = false
                            _activeModelName.value = next.id
                            if (history.isNotEmpty() && history.last().role == Role.Assistant) {
                                history.removeAt(history.lastIndex)
                            }
                            val switchMsg = "Switched to ${next.id} — Empty reply from previous model"
                            emit(RunEvent.State(now(), "model_switched", switchMsg))
                            history += ctx.notice("model_switch", switchMsg)
                            continue
                        }
                    }
                    return StopRules.noAnswer()
                }
                emptyNudged = false
                // The tool call is leaked as text (the server does not parse it into a call): This is
                // not an answer. Return once and call it again through the tool interface; then it ends
                // again without giving the original mark to the user.
                val leaked = LeakedToolCall.find(text, everything.map { it.name })
                if (leaked != null) {
                    if (leakNudged) return StopRules.toolCallAsText()
                    leakNudged = true
                    emit(RunEvent.State(now(), "tool_call_as_text", RunEvent.clip(leaked)))
                    TurnPolicy.leakedToolCall(leaked).let { history += ctx.notice(it.source, it.text) }
                    continue
                }
                // The user just agreed or answered something but did nothing afterwards: first remind
                // him to do it or explain why he did not do it; then check as usual.
                val owed = owedFollowUp(answered).takeIf { !followUpNudged }
                if (owed != null) {
                    followUpNudged = true
                    TurnPolicy.followUp(owed).let { history += ctx.notice(it.source, it.text) }
                    continue
                }
                // The tool has been used, but "send it again" is taken as the answer: the user's words
                // are already in the conversation, return it and let him continue.
                if (!repeatNudged && acted && RepeatRequest.matches(text)) {
                    repeatNudged = true
                    history += ctx.notice("repeat_request", Feedback["repeat_request_nudge"])
                    continue
                }
                if (step == 1 && !imagePromptNudged && tools.declared("generate_image") != null &&
                    com.meenai.mina.core.image.ImageIntentRouter.isVisualPrompt(task)) {
                    imagePromptNudged = true
                    history += ctx.notice("visual_prompt_intent",
                        "The user provided an image generation prompt. Do not critique, review, or discuss the prompt text; call generate_image immediately with the user's prompt description.")
                    continue
                }
                val before = beforeAccept(text)
                if (before is BeforeAccept.Continue) {
                    history += ctx.notice(Feedback["before_accept_source"], before.note)
                    continue
                }
                val currentFacts = facts()
                if (!reviewFinalAnswer && currentFacts.unreadChange && !resultReadNudged) {
                    resultReadNudged = true
                    history += ctx.notice("operation_result", "The result of the last action has not been read yet. Look at the current screen first to confirm the actual state, then answer; if it cannot be read, say so plainly.")
                    continue
                }
                // The text of the answer is not in the language of the user's latest sentence: return it
                // to rewrite the entire paragraph; after rewriting, you still do not hand it over in the
                // same way.
                if (!languageNudged) {
                    val mismatch = ReplyLanguage.mismatch(latestWords, text)
                    if (mismatch != null) {
                        languageNudged = true
                        TurnPolicy.language(mismatch).let { history += ctx.notice(it.source, it.text) }
                        continue
                    }
                }
                // The reply without a call is the final answer; the product path only organizes the
                // source.
                val result = conclude(task, text, step, currentFacts, history)
                if (result != null) {
                    // There are also interjections before handing over (when checking and sorting out
                    // the sources): don’t finish it, take it with you and continue working on it; if
                    // not, close the inbox, and the next words will be the next thing
                    if (inbox?.closeUnlessSteered() == false) { keepFinished(history, inbox); continue }
                    return result
                }
                continue
            }
            emptyNudged = false
            leakNudged = false
            acted = true
            // After the answer, he did something else: that answer has a sequel.
            answered = null

            val first = calls.first()
            trace?.toolStarted(step, first.name, first.id, now())
            val skipped = calls.drop(1).map {
                Part.ToolResult(it.id, buildJsonObject {
                    put("ok", false)
                    put("error", "parallel_tool_call_skipped")
                    put("message", Feedback["parallel_call_skipped"])
                }.toString(), isError = true)
            }
            val args = parseArgs(first.argsJson)

            // The step that the user just agreed to must be done separately: steps in the batch do not
            // have consent, and if they are mixed in, they will be blocked by the level. The consent may
            // also be used or invalidated by other steps.
            if (((flags.batchSteps && first.name == DO_STEPS_TOOL) || tools.composite(first.name)) && confirmations?.hasGrant == true) {
                val content = buildJsonObject {
                    put("ok", false); put("error", "approved_step_first"); put("message", APPROVED_STEP_FIRST); put("recoverable", true)
                }
                history += Message(Role.Tool, listOf(Part.ToolResult(first.id, content.toString(), isError = true)) + skipped, Author.Mina)
                recordReply(if (first.name == DO_STEPS_TOOL) BatchRunner.redactArgs(first.argsJson) else Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                recorder.result(step, first.name, tools.verb(first.name), content, JsonObject(emptyMap()), null, null, 0, "model", first.id)
                track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                continue
            }
            if (flags.batchSteps && first.name == DO_STEPS_TOOL) {
                activeRouter?.recordStepAction("batch:${first.argsJson}")
                when (val end = batch(first, skipped, history, specs, guards, executor, track, step, note, tool, reason, narration, recordReply = ::recordReply,
                    app = { place = it }, steerWaiting = { inbox?.hasSteers() == true },
                    noteVerdicts = { s, sub, v ->
                        // Just like single steps, stuck reminders and repeated input of the same
                        // paragraph will be counted.
                        val typed = StuckAdvice.typedText(tools.verb(s.tool), parseArgs(s.argsJson), sub?.content)
                        stuckAdvice(v, typed)
                    })) {
                    null -> continue
                    else -> return end
                }
            }

            val verb = tools.verb(first.name)
            val offScreen = tools.offScreen(first.name)
            val running = TaskState.Running(step, note, tool, reason = reason, narration = narration)
            gateState = running
            var advice: String? = null
            var waitsForUser = false
            val action = executor.run(first, Origin.Model(step), object : FollowUp {
                override suspend fun resolve(exec: ToolExecution): ToolExecution? {
                    var e = exec
                    (e.outcome as? Outcome.Confirm)?.let { e = user.confirm(it, step, running) }
                    // If you need to wait for the user's response (questioning, taking over): write down
                    // the reply before waiting, cancel the task while waiting, and keep it in the
                    // running record.
                    waitsForUser = e.outcome is Outcome.Input || e.outcome is Outcome.Takeover
                    if (waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                    // The answer to the question (or timeout) is the tool result of this call, and the
                    // call is paired with the result.
                    (e.outcome as? Outcome.Input)?.let { o ->
                        e = user.input(o, running)
                        if (e.content["ok"] == JsonPrimitive(true)) answered = o.prompt
                    }
                    // When the handover is completed, the result of the handover is the tool result of
                    // this call; canceling the task ends here
                    (e.outcome as? Outcome.Takeover)?.let { e = user.takeover(it, running) ?: return null }
                    return e
                }

                override suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented {
                    val e = exec
                    val a = stuckAdvice(verdicts, StuckAdvice.typedText(verb, args, e.content))
                    advice = a
                    val content = if (verdicts.isEmpty() && advice == null) e.content
                    else JsonObject(e.content +
                        (if (verdicts.isEmpty()) emptyMap() else mapOf("guard" to JsonArray(verdicts.map { it.toJson() }))) +
                        (advice?.let { mapOf("advice" to JsonPrimitive(it)) } ?: emptyMap()))
                    val isError = (e.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
                    history += Message(Role.Tool, listOf(Part.ToolResult(first.id, content.toString(), isError, e.observations.map { it.tag })) + skipped, Author.Mina)
                    // Only when the port clearly says "not a password box" will the original text be
                    // recorded.
                    val secret = (e.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
                    if (!waitsForUser) recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secret))
                    return Presented(e, content)
                }

                override fun refused(content: JsonObject) {
                    history += Message(Role.Tool, listOf(Part.ToolResult(first.id, content.toString(), isError = true)) + skipped, Author.Mina)
                    recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                }
            })
            val ran = when (action) {
                is ActionOutcome.Cancelled -> return TaskState.Cancelled
                is ActionOutcome.Refused -> {
                    guards.stop?.let {
                        recordReply(Redactor.args(tools.verb(first.name), first.argsJson, secretField = true))
                        return StopRules.guard(it)
                    }
                    // The application permission denial has been handed over to the model as a tool
                    // result by the execution portal: the task is not ended, and the model is allowed to
                    // change routes or explain to the user.
                    if (AppAccessDenied.isAppAccessCode(action.code)) {
                        track.after(tools.verb(first.name), ok = false, guard = false, changed = null, recovery = false, app = null)
                        continue
                    }
                    // The single step of the model will only be stopped by the guard, blocked by the
                    // confirmation level, or not executed due to the effect on Mina herself; other
                    // rejections indicate that the execution entrance does not match here.
                    check(action.hit != null || action.code == Refusals.OWN_APP) { "unexpected refusal ${action.code} for ${first.name}" }
                    // Stopped by the confirmation level: it will not be counted as a failed circuit
                    // breaker, and will be handed over to the model to go through the confirmation
                    // process.
                    track.after(tools.verb(first.name), ok = false, guard = action.before.isNotEmpty(), changed = null, recovery = false, app = null)
                    continue
                }
                is ActionOutcome.Ran -> action
            }
            val exec = ran.exec
            activeRouter?.recordStepAction("${first.name}:${first.argsJson}")
            if (verb == Verb.HandToUser && (args["reason"] as? JsonPrimitive)?.contentOrNull == PAYMENT) paymentHandedOver = true
            val isError = (exec.content["ok"] as? JsonPrimitive)?.booleanOrNull == false
            noteResultEvents(verb, exec.content)
            exec.place?.let { place = it }
            track.after(tools.verb(first.name), !isError, ran.verdicts.isNotEmpty() || advice != null, exec.changed, false, exec.place?.name)
            val picture = ran.picture
            val shotId = ran.shotId
            if (picture != null && shotId != null) {
                record { sink.media(shotId, picture) }
                media.getOrPut(step) { mutableListOf() } += shotId
                emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(place)))
            }

            val stopNow = guards.stop ?: executor.offScreenStop
            val end = StopRules.afterAction(exec.outcome, stopNow)
            if (end != null) {
                // When the model itself said it couldn't be done (end_not_done), the user just added:
                // Don't finish, just keep doing it with this sentence in mind; the guard will stop it
                // and it will still end.
                if (stopNow == null && exec.outcome is Outcome.Failure && inbox?.closeUnlessSteered() == false) {
                    appendSteers(history, inbox)
                    continue
                }
                return end
            }

            if (exec.images.isNotEmpty()) {
                // The image file read is not a screen image and does not have a screenshotId.
                history += Message(Role.User, listOf(Part.Text(NoticePolicy.imageNote(verb, offScreen))) +
                    exec.images.mapIndexed { i, img -> tools.modelImage(img, if (offScreen) null else exec.imageIds.getOrNull(i)) }, Author.Mina)
            }
            for (lookup in NoticePolicy.knowledgeAfter(verb, exec.place, !isError)) when (lookup) {
                is KnowledgeLookup.App -> knowledge?.byPackage(lookup.pkg).let { injectKnowledge(history, it) }
                // The same knowledge will not be injected repeatedly
                is KnowledgeLookup.Site -> if (visitedSites.add(lookup.host)) knowledge?.byDomain(lookup.host).let { injectKnowledge(history, it) }
            }
        }
        appendSteers(history, inbox)
        refreshOffer()
        return finalTurn(task, history, specs, maxSteps + 1, labels, place, recorder, facts(), inbox)
    }

    /** Things recorded separately in the running record according to the results: reported obstacles (for evaluation, attributed according to the barrier category; those with incorrect parameters and rejections are not counted), personal skills seen, and memories saved. */
    private fun noteResultEvents(verb: Verb?, content: JsonObject) {
        if (verb == Verb.HandleObstacle && content["ok"] == JsonPrimitive(true)) {
            (content["kind"] as? JsonPrimitive)?.contentOrNull?.let { emit(RunEvent.Barrier(now(), it)) }
        }
        GuideRecommender.viewedPersonal(verb, content)?.let { emit(RunEvent.SkillUsed(now(), it, "viewed")) }
        if (verb == Verb.MemorySave && content["ok"] == JsonPrimitive(true)) emit(RunEvent.MemorySaved(now(),
            content["id"]?.jsonPrimitive?.content ?: "", content["kind"]?.jsonPrimitive?.content ?: "", content["status"]?.jsonPrimitive?.content ?: ""))
    }

    /** The reference materials of the front-end App/site are added to the history (certainty, no guessing based on keywords); all skill starts have been listed, so there is no need to add them here. */
    private fun injectKnowledge(history: MutableList<Message>, e: KnowledgeEntry?) {
        ctx.knowledge(e, toolsNote(tools.scriptsOffered()), flags.deepLinks)?.let { m ->
            if (flags.deepLinks && e!!.links.isNotEmpty()) tools.allowLinks(e.links)
            history += m
            if (e?.trial == true) emit(RunEvent.State(now(), "knowledge_trial_used", e.name))
        }
    }

    /**
     * The runtime status of this step is connected to the end of the last message of this step (the one
     * that has not yet been sent), and is cut and folded together with this step; the messages that have
     * been sent will not be changed, and the request prefix will only increase gradually. When the last
     * one is an assistant message (which does not happen in the normal process), add a separate one. The
     * version on the web page is read from the version actually used by the browser's current tab.
     */
    private suspend fun appendRuntimeState(history: MutableList<Message>, place: Place?, labels: Map<String, String>, step: Int) {
        val mode = if (place?.kind == PlaceKind.Site) tools.placeMode() else null
        val state = ctx.runtimeState(place, place?.name?.let(labels::get), mode, step, maxSteps, now(), latestWords)
        val last = history.lastOrNull()
        if (last == null || last.role == Role.Assistant) history += Message(Role.User, listOf(state), Author.Mina)
        else history[history.lastIndex] = last.copy(parts = last.parts + state)
    }

    /**
     * In the user's sentence, the words he typed this time: the description of the screen, the page
     * description of the browser page, the quoted paragraph, and the attachment line were removed one
     * after another (see [UserWords.typed]; remove all of them when they are included). When none of
     * them are included, it is the original words; when only the screen is sent, only a paragraph is
     * quoted, only the words are selected, and only the attachment is included, it is empty. The
     * sentence given to the model has a fixed English description and interrupt mark, and may be
     * followed by a runtime status. It cannot be used to judge the language used by the user.
     */
    private fun ownWords(raw: String, shown: UserScreenTurns.Rendered?): String = UserWords.typed(shown?.words ?: raw, tools)

    private suspend fun channelLine(matched: List<KnowledgeEntry>): String? {
        val facts = channelFacts ?: return null
        val f = try { facts() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        return ChannelAdvisor.line(matched, tools.skillCatalog, f)
    }

    /**
     * Interjections are independent lines of real users; they are only appended between steps and never
     * overwrite messages already sent to the model. The interjection takes the user's current screen and
     * changes it to the original sentence at the beginning: the sentence is accompanied by a screenshot,
     * followed by the details of the screen.
     */
    private suspend fun appendSteers(history: MutableList<Message>, inbox: RunInbox?): Boolean =
        appendSteers(history, inbox?.takeSteers().orEmpty())

    private suspend fun appendSteers(history: MutableList<Message>, updates: List<RunInbox.UserSteer>): Boolean {
        updates.forEach { update ->
            if (activeRouter != null && hasAttachedImageInput(update.text)) {
                activeRouter?.prepareStep(0, hasImages = true)
            }
            val shown = screens?.render(update.text)
            val words = ownWords(update.text, shown)
            if (words.isNotBlank()) latestWords = words
            history += Message(Role.User, listOf(Part.Text("$STEER_MARKER\n${shown?.said ?: update.text}")) + shown?.images.orEmpty(), Author.User)
            shown?.details?.forEach { (source, body) -> history += ctx.userScreen(source, body) }
            utterances?.add(UserWords.said(shown?.words ?: update.text, tools))
            emit(RunEvent.State(now(), "steer_received", RunEvent.clip(Redactor.text(update.messageId))))
        }
        return updates.isNotEmpty()
    }

    /**
     * The reply without the call has been recorded in the history and displayed. At this time, there is
     * an interruption: connect the interruption and continue (return true). Keep the reply on the
     * screen: the real-time text on the interface will be cleared as soon as the next step starts, so
     * send `reply_finished_before_steer` and hang it above the comment. If there is no interruption, it
     * returns false and does nothing.
     */
    private suspend fun keepFinished(history: MutableList<Message>, inbox: RunInbox?): Boolean {
        val steers = inbox?.takeSteers().orEmpty()
        if (steers.isEmpty()) return false
        val current = _state.value as? TaskState.Running
        val shown = current?.preview?.trim()
        if (current?.preview != null) _state.value = current.copy(preview = null)
        // No truncation: This is a complete answer, and the interface should display the full text as if
        // there were no interruptions (the length is inherently constrained by the upper limit of the
        // model output)
        if (!shown.isNullOrEmpty()) emit(RunEvent.State(now(), "reply_finished_before_steer", Redactor.text(shown)))
        appendSteers(history, steers)
        return true
    }

    /** Interrupting only cancels the waiting model request (this step will not be written into the history, and the next step will be repeated with the interruption); the tool call will be completed by itself after entering the execution entrance. */
    private suspend fun routedOrRedirect(
        inbox: RunInbox?, history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)?,
        onThinking: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel>? {
        if (inbox == null) return caller.routed(history, specs, track, step, restarted, onText, onThinking)
        return coroutineScope {
            val pending = async { caller.routed(history, specs, track, step, restarted, onText, onThinking) }
            select<Pair<ModelReply, ChatModel>?> {
                pending.onAwait { it }
                inbox.redirects.onReceive {
                    pending.cancelAndJoin()
                    null
                }
            }
        }
    }

    /**
     * Interrupting cancels the request at step [step]: the text that the user has already seen (preview
     * on the interface) is recorded in history as a text-only reply, followed by the interjection, and
     * what the model sees next is the same as what the user sees. Remember nothing when you don’t see
     * the words or interrupt. The thinking content and partial tool calls are not in the preview. The
     * words on the interface are cleared here, and the `reply_interrupted` event is hung above the
     * interruption.
     */
    private suspend fun keepInterrupted(history: MutableList<Message>, inbox: RunInbox?, step: Int) {
        val steers = inbox?.takeSteers().orEmpty()
        interruptedStep = step
        val current = _state.value as? TaskState.Running
        val partial = current?.takeIf { it.step == step }?.preview?.trim()
        if (current?.preview != null) _state.value = current.copy(preview = null)
        if (!partial.isNullOrEmpty() && steers.isNotEmpty()) {
            history += Message(Role.Assistant, listOf(Part.Text(partial)), Author.Mina)
            emit(RunEvent.State(now(), "reply_interrupted", RunEvent.clip(Redactor.text(partial))))
        }
        appendSteers(history, steers)
    }

    private fun previewFor(step: Int): ((String) -> Unit)? = if (!streamReplies) null else { preview ->
        val current = _state.value as? TaskState.Running
        // After removing it, the network thread may send a paragraph: Don't display it again, so as not
        // to have more on the screen than in the history.
        if (current?.step == step && interruptedStep != step) _state.value = current.copy(preview = preview.takeIf(String::isNotEmpty))
    }

    private fun thinkingFor(step: Int): ((String) -> Unit)? = if (!streamReplies) null else { thought ->
        val current = _state.value as? TaskState.Running
        if (current?.step == step && interruptedStep != step) {
            _state.value = current.copy(thinking = thought.takeIf(String::isNotEmpty))
        }
    }

    private fun safeActionTarget(args: JsonObject): String? {
        val raw = listOf("packageName", "package", "app", "url", "query", "path")
            .firstNotNullOfOrNull { key ->
                (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            } ?: return null
        return Redactor.text(raw).take(60).takeIf(String::isNotEmpty)
    }

    /**
     * Execute do_steps once: the entire batch counts as one step for the upper limit of the number of
     * steps, and each sub-step counts as one action for the guard and running records (recorded by
     * [BatchRunner]). Let the next step return to the main model when the batch is stopped. Returning
     * non-null indicates that the task is complete.
     */
    private suspend fun batch(
        first: Part.ToolCall, skipped: List<Part.ToolResult>, history: MutableList<Message>, specs: List<ToolSpec>, guards: OperationGuards,
        executor: ActionExecutor, track: StepTrack, step: Int, note: String, tool: String?, reason: String?, narration: String?,
        recordReply: (String?) -> Unit, app: (Place) -> Unit, steerWaiting: () -> Boolean,
        noteVerdicts: (BatchStep, ToolExecution?, List<GuardVerdict>) -> String?,
    ): TaskState? {
        // Tools in a closed group can also be put into a batch (execute as usual and expand its group
        // like a single step)
        val (parsed, problem) = BatchRunner.parse(first.argsJson, tools.definitions().map { it.name }.toSet(), tools.batchable())
        // A batch is only made on one screen: a whole batch with two screens mixed is not made.
        val mixed = parsed?.let { BatchRunner.mixedSurface(it, tools::surfaceOf) }
        val steps = parsed.takeIf { mixed == null }
        val running = TaskState.Running(step, note, tool, steps?.let { RunProgress.Batch(it.size) }, reason, narration)
        if (steps != null) _state.value = running
        val started = now()
        var runner: BatchRunner? = null
        val exec = if (steps == null) ToolExecution(buildJsonObject {
            put("ok", false); put("error", if (mixed != null) "mixed_surface" else "invalid_arguments"); put("message", mixed ?: problem); put("recoverable", true)
        }) else {
            gateState = running
            runner = BatchRunner(tools, guards, executor, noteVerdicts = noteVerdicts, steerWaiting = steerWaiting)
            runner.run(steps, first.id, step)
        }
        val durationMs = now() - started
        val isError = exec.content["ok"] != JsonPrimitive(true)
        history += Message(Role.Tool, listOf(Part.ToolResult(first.id, exec.content.toString(), isError, exec.observations.map { it.tag })) + skipped, Author.Mina)
        // After the batch is finished (or stopped), give the model a copy of the current element list;
        // only if the element information is insufficient, the screen will be attached.
        if (steps != null && ranAnyStep(exec.content)) {
            tools.lookNow()?.let { v ->
                history += Message(Role.User, listOfNotNull(Part.Text(batchViewNote(v.content.toString())), v.image?.let { tools.modelImage(it, v.imageId) }), Author.Mina)
            }
        }
        recordReply(BatchRunner.redactArgs(first.argsJson))
        val picture = exec.frame
        val shotId = picture?.let { executor.recorder.nextShot() }
        // After the sub-step, the time for waiting for the interface according to the conditions is
        // recorded separately, and the waiting time is included in the summary.
        emit(RunEvent.ToolResult(now(), step, first.name, RunEvent.clipResult(Redactor.text(exec.content.toString())), exec.changed, shotId, durationMs,
            waitMs = runner?.waitedMs ?: 0, callId = first.id))
        val currentPlace = exec.place
        currentPlace?.let(app)
        // It must be confirmed that it is not a failure (it does not enter the failure circuit like a
        // single step is blocked), but it is also handed over to the main model. The batch that is
        // stopped for new interruption is also not a failure.
        val confirm = exec.content["error"] == JsonPrimitive("confirmation_required") || exec.content["reason"] == JsonPrimitive(BatchRunner.NEW_MESSAGE)
        track.after(tools.verb(first.name), !isError || confirm, runner?.verdicts?.isNotEmpty() == true || exec.content["advice"] != null, exec.changed, false, currentPlace?.name)
        // Batch stop (check failed, need to confirm): next step is to hand over to the main model
        if (isError) track.markRecovery()
        if (picture != null && shotId != null) {
            record { sink.media(shotId, picture) }
            media.getOrPut(step) { mutableListOf() } += shotId
            emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(currentPlace)))
        }
        return guards.stop?.let(StopRules::guard)
    }

    /** At least one substep in the result of do_steps was actually executed (unexecuted stop items are marked with executed=false). */
    private fun ranAnyStep(content: JsonObject): Boolean =
        (content["steps"] as? JsonArray)?.any { (it as? JsonObject)?.get("executed") != JsonPrimitive(false) } == true

    /**
     * The operation that the user agreed to but has not yet used, or the follow-up question that was
     * answered ([answered] is that question, and if he did something else after that, it is null): the
     * following that he still owes before answering the handover; none of them are null.
     */
    private fun owedFollowUp(answered: String?): String? {
        confirmations?.granted?.let { r ->
            val what = listOfNotNull(r.action.name, r.target.takeIf(String::isNotBlank), r.content.takeIf(String::isNotBlank)?.let { "「$it」" }).joinToString(" ")
            return Feedback.format("followup_approved", "action" to what)
        }
        return answered?.let { Feedback.format("followup_answered", "prompt" to it.take(200)) }
    }

    /**
     * The reply in step [step] did not adjust the tool, and the text [answer] is the final answer: Check
     * by risk ([AnswerReview]), the source is found in the code. If the verification is not correct: if
     * it can be returned ([history] is not null, and this task has not been returned yet), remind the
     * model once, return null and continue; otherwise, hand it over as usual, and mark the unconfirmed
     * parts as unconfirmed. [facts] is the task status to be checked (see [ReviewFacts]).
     */
    private suspend fun conclude(task: String, answer: String, step: Int, facts: ReviewFacts, history: MutableList<Message>?): TaskState? {
        if (!reviewFinalAnswer) {
            val sources = review.sourcesOnly(task, answer, media)
            if (sources.isNotEmpty()) emit(RunEvent.Sources(now(), sources))
            return TaskState.Succeeded(answer, verified = false, sources = sources, reviewed = false)
        }
        val r = review.review(task, answer, step, facts, earlierGoals, media) {
            _state.value = TaskState.Running(step, VERIFYING_NOTE, reason = VERIFYING_NOTE,
                preview = (_state.value as? TaskState.Running)?.preview)
        }
        val missing = r.unconfirmed
        if (missing.isNotEmpty() && history != null && !gapsNudged) {
            gapsNudged = true
            TurnPolicy.unconfirmed(r.gaps, r.objections).let { history += ctx.notice(it.source, it.text) }
            return null
        }
        if (missing.isNotEmpty()) emit(RunEvent.Unconfirmed(now(), step, missing.map(::detail)))
        if (r.sources.isNotEmpty()) emit(RunEvent.Sources(now(), r.sources))
        return if (missing.isEmpty()) TaskState.Succeeded(answer, r.verified, r.note, r.sources, reviewed = r.reviewed)
        else TaskState.Succeeded(answer, verified = false, note = UNCONFIRMED_NOTE + " " + missing.joinToString(", "), sources = r.sources, unconfirmed = missing)
    }

    /**
     * After the number of steps is used up, the last round is given, and the only tool left is
     * end_not_done: answer in text when you are done (still check, but no longer return, the ones that
     * are not correct are marked as unconfirmed), if you can't do it, tell the truth about what you
     * checked. Nothing is given in this round (or it is truncated) and the number of steps is still used
     * up.
     */
    private suspend fun finalTurn(
        task: String, history: MutableList<Message>, specs: List<ToolSpec>, step: Int, labels: Map<String, String>, place: Place?, recorder: ActionRecorder,
        facts: ReviewFacts, inbox: RunInbox?,
    ): TaskState {
        TurnPolicy.finalTurn().let { history += ctx.notice(it.source, it.text) }
        repeat(3) { attempt ->
            appendSteers(history, inbox)
            appendRuntimeState(history, place, labels, step)
            _state.value = TaskState.Running(step, "")
            val asked = now()
            val reply = try {
                caller.ask(history, TurnPolicy.finalTools(specs, tools::verb), step, onText = previewFor(step))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return StopRules.stepLimit(maxSteps)
            }
            val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
            history += Message(Role.Assistant, reply.parts, Author.Mina)
            val calls = reply.parts.filterIsInstance<Part.ToolCall>()
            val call = calls.firstOrNull { tools.verb(it.name) in TurnPolicy.FINAL_VERBS }
            val retryWaitMs = caller.lastRetryWaitMs
            val repliedAtMs = now()
            val latencyMs = repliedAtMs - asked - retryWaitMs
            emit(RunEvent.ModelReply(
                now(), step, RunEvent.clip(Redactor.text(text)), call?.name, call?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = false) },
                reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs, retryWaitMs = retryWaitMs,
                repliedAtMs = repliedAtMs,
            ))
            // The closing model may also receive an interpolation while being generated. Repeat the
            // answer twice at most, and pass the rest to the next task.
            if (attempt < 2 && keepFinished(history, inbox)) return@repeat
            if (call == null) {
                if (calls.isNotEmpty() || text.isEmpty() || reply.stopReason == StopReason.MaxTokens) return StopRules.stepLimit(maxSteps)
                return conclude(task, text, step, facts, history = null)!!
            }
            val started = now()
            // All that's left is to give up and not change the phone
            val exec = tools.executeRead(call)
            recorder.result(step, call.name, tools.verb(call.name), exec.content, exec.record, null, null, now() - started, "model", call.id)
            return StopRules.afterFinalTurn(exec.outcome, maxSteps)
        }
        return StopRules.stepLimit(maxSteps)
    }

    companion object {
        const val STEER_MARKER = "<user_interjection>"
        const val DEFAULT_MAX_STEPS = 60
        /** Normal tasks do not have a fixed step cutoff; explicit step budgets are only used for evaluation and for subtasks with specified upper bounds. */
        const val UNLIMITED_STEPS = Int.MAX_VALUE
        /** Instructions for this step on the interface during verification. */
        const val VERIFYING_NOTE = "Verifying results"
        val APPROVED_STEP_FIRST: String get() = Feedback["approved_step_first"]
        /** In the answer, there are several points that are still not correct after returning once. The ending explanation starts like this (press [TaskState.Succeeded.unconfirmed] on the interface to mark them one by one). */
        const val UNCONFIRMED_NOTE = "Unconfirmed:"
        /** hand_to_user The reason why hand_to_user is handed over to the user for payment; payment is irreversible, please check the answer separately. */
        private const val PAYMENT = "payment"
        const val DEFAULT_INPUT_TIMEOUT_MS = 15 * 60_000L
        /** Return the reminder in the tool results after takeover (click it to confirm the interface test). */
        val HANDBACK_NOTE: String get() = UserExchange.HANDBACK_NOTE

        /** The user clicked "I finished it myself". */
        val USER_FINISHED_NOTE: String get() = Feedback["user_finished_note"]

        private val IMAGE_ATTACHMENT_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    }

    private fun now() = clock()

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private inline fun record(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Recording failure does not affect the task
        }
    }

    private fun emit(e: RunEvent) = record { sink.event(e) }

    private fun ended(end: TaskState?): RunEvent.TaskEnded = when (end) {
        // Verified but not verified (including those marked as unconfirmed): recorded as unverified,
        // which is the same as reviews and conversation records.
        is TaskState.Succeeded -> RunEvent.TaskEnded(now(), if (end.doubtful) "unverified" else "succeeded", Redactor.text(end.answer))
        is TaskState.Failed -> RunEvent.TaskEnded(now(), "failed", Redactor.text(end.reason), end.kind.name.lowercase())
        TaskState.Cancelled -> RunEvent.TaskEnded(now(), "cancelled", "Cancelled")
        else -> RunEvent.TaskEnded(now(), "error", "Task abnormally interrupted")
    }

    private fun waitingEvent(s: TaskState): RunEvent.State = when (s) {
        is TaskState.Held -> RunEvent.State(now(), s.reason.code, detail(s.reason.why))
        else -> RunEvent.State(now(), s::class.simpleName.orEmpty(), "")
    }
}

/** The statement that the user named and visited the webpage (also added before the target when evaluating mandatory webpage channels). */
private const val WEB_CHANNEL_PREFIX = "Use web"
