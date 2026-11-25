package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.i18n.uiLocale
import com.meenai.mina.core.assembly.legacyNames
import com.meenai.mina.core.tool.LegacyNames
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.chat.CurrentChat
import com.meenai.mina.chat.DeleteImpact
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.ui.image.GeneratedImageUi
import com.meenai.mina.cap.sandbox.ImportResult
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.AttachmentLines
import com.meenai.mina.core.agent.Conversation
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.task.TaskGate
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.memory.MemoryItem
import com.meenai.mina.core.memory.MemoryNote
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.skills.SkillNote
import com.meenai.mina.memory.RunNotes
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.Problem
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.task.SteerNote
import com.meenai.mina.task.SteerState
import com.meenai.mina.task.unconsumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.userscreen.UserScreenMessage

/** What the interface can do for the task service; it is implemented by TaskService on the real machine, and is replaced by recording calls in the test. */
interface TaskActions {
    fun start(goal: String, conversationId: String)
    /** The words inserted into its dialogue while the task is in progress; [messageId] is used to follow the direction of this sentence. */
    fun steer(conversationId: String, messageId: String, text: String) = start(text, conversationId)
    fun withdrawSteer(conversationId: String, messageId: String): Boolean = false
    fun cancel()
    fun pause() {}
    fun resume()
    fun takeOver()
    fun handBack()
    fun finishedMyself()
    fun skip()
    fun answer(text: String)
    /** [key] is the declaration to which the card belongs. */
    fun approve(key: Int)
    fun decline(key: Int)
    /** In parallel tasks, let the service display the task of this conversation (just display: the external status flow follows it). */
    fun show(conversationId: String) {}

    // During parallel tasks, the button in the application brings an on-screen dialogue and only acts on
    // the task of that dialogue; it will be invalidated if it no longer has a task. The default is the
    // same as the version without dialogue.
    fun cancelIn(conversationId: String) = cancel()
    fun pauseIn(conversationId: String) = pause()
    fun resumeIn(conversationId: String) = resume()
    fun takeOverIn(conversationId: String) = takeOver()
    fun handBackIn(conversationId: String) = handBack()
    fun finishedMyselfIn(conversationId: String) = finishedMyself()
    fun skipIn(conversationId: String) = skip()
    fun answerIn(conversationId: String, text: String) = answer(text)
    fun approveIn(conversationId: String, key: Int) = approve(key)
    fun declineIn(conversationId: String, key: Int) = decline(key)
}

enum class StepState { Ok, Run, Wait, Dim }

/**
 * An action in one step: what was done ([label], the interface term of the tool, not the tool name),
 * what was done, and what the result was (the effect in the running record). When you click on this
 * step, list them one by one - the title of one line is a summary. If you want to see what it clicked
 * and typed, look here.
 */
data class StepAction(val label: String, val target: String?, val effect: String?, val failed: Boolean)

/**
 * A line in the execution process comes from what was actually done (activity in the running event):
 * [title] is what was done (the reading class was merged into "viewed the WeChat chat list"), [reason]
 * is the intention written by the model for that step (empty when not written), [subtitle] is the
 * explanation when it was unsuccessful, and is usually empty.
 */
data class StepRow(val step: Int, val title: String, val subtitle: String, val state: StepState, val reason: String = "") {
    /** The explanation of this step on the interface: the model's own words, if not, use the tool's explanation. */
    val text: String get() = reason.ifEmpty { title }
}

sealed interface ExchangeStatus {
    data object Working : ExchangeStatus
    data object WaitingUnlock : ExchangeStatus
    /** [item]/[amount]/[address] are listed separately on the card when payment is taken over. */
    data class NeedsTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : ExchangeStatus
    data class NeedsInput(val prompt: String, val options: List<String>?) : ExchangeStatus
    /**
     * Wait for the user to agree to an operation that costs money (you can only agree this time);
     * [title] "Requires your consent: <action>", [body] One field per line (App, object, complete content,
     * amount). [key] is the number of this declaration. The button carries it and clicking on the old
     * card does not count.
     */
    data class NeedsConfirmation(val title: String, val body: String, val key: Int) : ExchangeStatus
    /** Stop and wait; click "Continue" at [resumable] and continue, otherwise it will automatically continue after the conditions are met ("Continue" will not be given on the card). */
    data class Paused(val why: String, val resumable: Boolean = true) : ExchangeStatus
    /** It has not started yet and is in the start queue; it will start automatically when it is your turn and can only be canceled. */
    data class Queued(val why: String) : ExchangeStatus
    /**
     * [verified] is false, which means it has been completed but failed to be verified, and [note] is
     * the reason; there is no green check at this time. [unconfirmed] are a few places in the answer
     * that the source has not been found after checking. They are listed one by one on the card.
     * [reviewed] is false, which means nothing is checked (there is no checkable value in the chat or
     * answer): no mark is marked. [evidence] only has the "Based on:" section written by the model
     * itself in the old record; the new answer source is taken from the running record (see
     * [evidenceSource]).
     */
    data class Done(
        val answer: String, val evidence: String?, val verified: Boolean = true, val note: String? = null,
        val unconfirmed: List<String> = emptyList(), val reviewed: Boolean = true,
    ) : ExchangeStatus {
        val unverifiedLine: String? get() = when {
            unconfirmed.isNotEmpty() -> tr(R.string.chat_unconfirmed, unconfirmed.joinToString(tr(R.string.list_sep)))
            verified || !reviewed -> null
            else -> note?.let { tr(R.string.chat_unverified_because, it) } ?: tr(R.string.chat_unverified)
        }
    }
    data class Failed(val reason: String) : ExchangeStatus
    /**
     * The user stopped. [summary] is the explanation added after stopping (what has been done and what
     * has not been done), which is regarded as the answer for this round; it is null when it has not
     * been written yet and there is nothing to explain. [summarizing] The explanation is still being
     * written ("organizing what has been done...").
     */
    data class Cancelled(val summary: String? = null, val summarizing: Boolean = false) : ExchangeStatus
}

/**
 * One question and one answer: the user’s words, execution process, current status or result. [live]
 * indicates the task (with steps) that was run after opening the app this time. [runId] is the running
 * record that can be viewed after the task is completed; it is null when running or there is no record.
 */
data class Exchange(
    val goal: String,
    val steps: List<StepRow>,
    val status: ExchangeStatus,
    val live: Boolean,
    val runId: String? = null,
    /** Only the status is displayed when the model request is in progress, and waiting is not disguised as an execution step. */
    val waitingForModel: Boolean = false,
    /** The model usage up to this moment (number of calls, token, and context remaining) is updated in real time. */
    val usage: com.meenai.mina.core.usage.RunUsage? = null,
    /** Visible text that the model has been given for this request; may become a progress note or reply before completion. */
    val draft: String? = null,
    /** Memory prompts under the result card (remembered / updated / forgotten / do you want me to do this in the future). */
    val memoryNotes: List<MemoryNote> = emptyList(),
    /** The memory entry number used this time (recorded by the code, not the model readme). */
    val usedMemory: List<String> = emptyList(),
    /** Skill tips under the result card (learned/deactivated if it doesn’t work recently). */
    val skillNotes: List<SkillNote> = emptyList(),
    /** The files exported to "Download/Mina" in this round are displayed as file cards. */
    val files: List<SharedFile> = emptyList(),
    val generatedImages: List<GeneratedImageUi> = emptyList(),
    /** The time (in milliseconds) when the app started to follow after opening it this time; it is 0 if it is not known. */
    val startedAt: Long = 0L,
    /** The time calculated at the end; it is null when it has not ended or the start time is not known. */
    val elapsedMs: Long? = null,
    /** What has actually been done up to this point (running event); [steps] is derived from it. */
    val activities: List<RunEvent.Activity> = emptyList(),
    /** If the model writes words for each step (progress description or calling intention), press the step number. */
    val notes: Map<Int, String> = emptyMap(),
    /** What are you doing at the moment: If you are doing this step of the model, or the progress of doing several steps without going through the model; if not, it is empty. */
    val line: String = "",
    /** "Opening..." is a kind of statement when the tool has been selected and the action has not been recorded; give way to it when the actual steps are available. */
    val liveAction: String? = null,
    /** While the task is in progress, here are the user's comments and where they go. */
    val steers: List<SteerNote> = emptyList(),
    /** The page of the site that will be left running in the background after this round (see [Turn.keptPage]); null if not left. */
    val keptPage: String? = null,
    /** The reason why the page was closed later ([Turn.keptPageClosed]); it is still open or not known, which is null. */
    val keptPageClosed: String? = null,
    /** The actual actions performed at each step (by step number): The title is a summary, click on a step to see the details here. */
    val stepActions: Map<Int, List<StepAction>> = emptyMap(),
    /** The unique number used by the interface for the LazyColumn stable key. */
    val id: String = runId ?: java.util.UUID.randomUUID().toString(),
    /** Model name/ID currently answering this live exchange. */
    val activeModel: String? = null,
)

/** A line of progress at runtime: What to wait for when waiting for you; the model request only gives a neutral state when it is in transit; otherwise, if it is this step, nothing is the most recent thing. */
fun liveLine(ex: Exchange): String? {
    when (ex.status) {
        is ExchangeStatus.NeedsTakeover -> return tr(R.string.takeover_title)
        is ExchangeStatus.NeedsInput -> return tr(R.string.chat_wait_answer)
        is ExchangeStatus.NeedsConfirmation -> return tr(R.string.chat_wait_approval)
        is ExchangeStatus.Queued -> return tr(R.string.chat_queued)
        else -> Unit
    }
    if (ex.waitingForModel) return tr(R.string.chat_working)
    return ex.line.ifEmpty { null } ?: ex.steps.lastOrNull()?.text ?: ex.liveAction
}

/** The small line below the interjection; don’t write it when you haven’t gone yet. */
fun steerLabel(state: SteerState): String? = when (state) {
    SteerState.Pending, SteerState.Withdrawn -> null
    SteerState.Received -> tr(R.string.steer_received)
    SteerState.Joined -> tr(R.string.steer_joined)
    SteerState.Next -> tr(R.string.steer_next)
    SteerState.Cancelled -> tr(R.string.steer_cancelled)
}

/**
 * Chime in on whether you can get your edits back. The one inserted into the floating capsule will bring
 * the screen, and the one inserted with the words selected on the browser page will bring the page
 * description. When retrieved, the input box begins with `[[screen:…]]`. The line or lines of the page
 * description are the original text. If you delete it, you will lose the screen and page. If you modify
 * it, you will not be able to recognize the page. These two types are not allowed to be edited.
 */
internal fun editableSteer(text: String): Boolean = !UserScreenMessage.has(text) && AttachedPage.parse(text).page == null

/** The line that is put away after the end: "Did 12 steps · 1 minute and 20 seconds"; if you don't know the time, just say the number of steps. */
fun stepsSummary(ex: Exchange): String =
    trPlural(R.plurals.chat_steps_done, ex.steps.size) + ex.elapsedMs?.let { " · " + RunTimelineViewModel.duration(it) }.orEmpty()

/** The memory and skill prompt buttons on the result card are implemented by memory storage and personal skills on the real machine (reading and writing are in the background thread), and are replaced by record calls in the test. */
interface LearningActions {
    suspend fun undo(token: String): Boolean
    suspend fun approve(pendingId: String): Boolean
    /** When the revocation token has expired (restarted), "Do not" directly delete the method to be approved. */
    suspend fun forget(memoryId: String): Boolean
    fun viewSkill(draftId: String)
    suspend fun restoreSkill(name: String)
    suspend fun deleteSkill(name: String)
    /** Prompt changed (remove that line after revoking or approving): Save the conversation and notify the interface. */
    suspend fun save(notes: RunNotes)
}

/**
 * Select the attachment that is waiting to be sent with the next sentence: content address ([uri]), name
 * and size; copy it into the conversation workspace when sending. For [photo], there are thumbnails
 * above the input box, and the rest are small file cards.
 */
data class PendingAttachment(val uri: String, val name: String, val size: Long, val photo: Boolean = AttachmentMessage.isPhoto(name))

enum class PipMode { Running, NeedsYou, Done }

/**
 * Picture-in-picture only displays the picture: the status only depends on the border light (green is
 * working, orange breathing requires you to deal with it), and what you are asked to do is in the
 * dialogue card. [closed]: The user has turned off the small screen for this task, leaving only a small
 * button in the lower right corner (click it to open it again; it turns orange when you want to handle
 * it). Just turn off this task, and the next task will still have a small screen.
 */
data class PipUi(
    val visible: Boolean = false,
    val mode: PipMode = PipMode.Running,
    val expanded: Boolean = false,
    val closed: Boolean = false,
)

enum class ComposerMode {
    /** If there is no task, it will be a new task if it is sent out. */
    Idle,
    /** The task is running: if you send it out, give it to this task, and stop is another button. */
    Busy,
    /** The Agent is waiting for a reply, and it will be answered when it is sent. */
    Answer,
}

data class ChatUi(
    val conversationId: String? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** The condition that was missing when the last transmission was blocked; empty means it was not blocked. */
    val blocked: List<Problem>? = null,
    val pip: PipUi = PipUi(),
    val composer: ComposerMode = ComposerMode.Idle,
    val takeoverStage: Takeover.Stage = Takeover.Stage.None,
    /** There is an attachment button next to the input box only when the sandbox is ready. */
    val attachEnabled: Boolean = false,
    val attachments: List<PendingAttachment> = emptyList(),
    /** The attachment is being copied into the workspace (while sending). */
    val importing: Boolean = false,
    /** Confiscated attachments (too large or cannot be read); no prompt if empty. */
    val rejectedAttachments: List<String> = emptyList(),
    /** The number that exceeds the number that can be carried at one time ([AttachTray.LIMIT]) and is not added; if it is 0, no prompt will be given. */
    val overLimit: Int = 0,
    /** The quote to be sent with the next sentence above the input box (long press the message to select "Quote"); if not, it is null. Cleared when changing conversations. */
    val quote: QuoteMessage.Quote? = null,
) {
    /** The task is currently being operated on the virtual screen in the background (the prompt line above the input box): the screen is open, working, not waiting for you or to be unlocked. */
    val operatingPhone: Boolean
        get() = pip.visible && pip.mode == PipMode.Running && exchanges.lastOrNull()?.status == ExchangeStatus.Working
}

/**
 * The status of the chat interface. The step list comes from the actual things (activities) done in the
 * running event; if the progress row is the step of the model, only the status will be displayed while
 * waiting for the model. If the task is sent in the runtime input box, it will be handed over to this
 * task (interruption). Only one instance is used for the entire process, and the steps of the running
 * task are still there when the interface is closed and reopened.
 */
class ChatViewModel(
    private val scope: CoroutineScope,
    taskState: StateFlow<TaskState>,
    private val takeoverStage: StateFlow<Takeover.Stage>,
    private val actions: TaskActions,
    private val store: ConversationStore,
    /**
     * The service still has tasks (the previous task of this dialogue is still finishing, or the task
     * started elsewhere is running); when the parallel is closed, the new task will be handed over to
     * the service after it is completed, so as to avoid the new sentence of this dialogue being regarded
     * as an interruption of the previous task. Under the message while waiting, it says "Wait until the
     * previous task is completed before starting."
     */
    private val serviceBusy: StateFlow<Boolean> = MutableStateFlow(false),
    /** Change the package name to the App display name (used to confirm the card). */
    private val appLabel: (String) -> String = { it },
    /**
     * Does the current task have a virtual screen? The screen is not created until you open the app for
     * the first time. Tasks that have not been created (pure conversations, answering without a mobile
     * phone) do not appear in picture-in-picture, and questions and answers and confirmations are all in
     * the chat card.
     */
    private val screenOpen: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * The running record number of the task that just ended is written by the service before reporting
     * the end; when the end status is received, it is linked to the question and answer, and there is a
     * "view process" on the card. After that, it doesn't change the one that has ended.
     */
    private val endedRunId: StateFlow<String?> = MutableStateFlow(null),
    /**
     * The running record number of the task currently running in the conversation (it is available at
     * the beginning of the task; it is null if it has not yet been assigned). Take it by conversation,
     * the same as [endedRunIds]. Do not look at the "displayed tasks" external to the service: in
     * parallel, it may be from other conversations. Hang it up when running, and the "Running Process"
     * can be opened on the spot.
     */
    private val liveRunId: (conversationId: String) -> String? = { null },
    /** The running record number of the most recently completed task in each conversation; if given, take it according to the conversation where the card is located, not the one above (it only refers to the displayed task). */
    private val endedRunIds: StateFlow<Map<String, String?>>? = null,
    /** Switch parallelTasks: When turned on, tasks in different conversations can run at the same time, and the interface only follows the tasks of this conversation on the screen. The interface is not reloaded while following the task. */
    private val parallelFlag: () -> Boolean = { false },
    /** Tasks in service display: (dialogue, status), write both together; use it instead of [taskState] in parallel, and the status of other dialogues will not be included. */
    private val shownTask: StateFlow<Pair<String, TaskState>?> = MutableStateFlow(null),
    /** The dialog containing the running or queued task. */
    private val activeConversations: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    /** The original words of the task that the dialogue is running; used when switching to and continuing to display. */
    private val runGoal: (String) -> String? = { null },
    /** The conversation is about the status of the task being run (regardless of who the service is showing at the moment); switch to it and continue to remember the steps of the question and answer. */
    private val runState: (String) -> StateFlow<TaskState>? = { null },
    /** The memory and skill tips added after the mission are completed are based on the run number. */
    private val runNotes: StateFlow<Map<String, RunNotes>> = MutableStateFlow(emptyMap()),
    private val learning: LearningActions? = null,
    /** The memory entry at the moment (read from encrypted storage); the text of the prompt and the "used" content are fetched from here, and the content is not stored in the conversation file. */
    private val memoryItems: StateFlow<List<MemoryItem>> = MutableStateFlow(emptyList()),
    /** Whether the sandbox is ready. */
    private val sandboxReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** File exported by the current task; cleared by the service when the task starts. */
    private val sharedFiles: StateFlow<List<SharedFile>> = MutableStateFlow(emptyList()),
    private val generatedImages: StateFlow<List<GeneratedImageUi>> = MutableStateFlow(emptyList()),
    /** Copies the selected file (content URI string) into inbox/ in the conversation workspace. */
    private val importAttachments: suspend (conversationId: String, uris: List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
    /** Read out the name, size and whether it is a photo of the selected file (content URI string); the work of reading the disk is placed in the background thread by the caller. */
    private val describeAttachments: suspend (uris: List<String>) -> List<PendingAttachment> = { uris -> uris.map { PendingAttachment(it, it.substringAfterLast('/'), 0L) } },
    /** The description of the tool in the step row. */
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    /** What the task in display actually does (the activities in the running event). */
    private val activities: StateFlow<List<RunEvent.Activity>> = MutableStateFlow(emptyList()),
    /** Displays the interrupts received and destinations for the task in question. */
    private val steers: StateFlow<List<SteerNote>> = MutableStateFlow(emptyList()),
    /** After stopping, the several runs (run numbers) that the instructions are being written are displayed; under that run, "organizing what has been done..." is displayed. */
    private val summarizing: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** The clock to calculate the elapsed time in milliseconds. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** What happens if you delete a conversation (see `ConversationDeleter.impact`). */
    private val deletionImpact: (String) -> DeleteImpact = { DeleteImpact(runningTask = false, schedules = 0) },
    /** Deletes a conversation and everything belonging to it (see `ConversationDeleter.delete`). */
    private val removeConversation: suspend (String) -> Unit = {},
    /** The page left running in the background after the task is completed (at most one page globally); the conversation page displays a line below the round it came from, and the top column says "1 web page is maintained in the background". */
    val keptPage: StateFlow<KeptPage?> = MutableStateFlow(null),
    /** Close the remaining page (the user clicked "Close" on that row). */
    private val closeKept: () -> Unit = {},
    /** Model name/ID currently answering the shown running task. */
    private val runningModel: StateFlow<String?> = MutableStateFlow(null),
) {
    /** The user closes the page left in the background on the conversation page. */
    fun closeKeptPage() = closeKept()

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** Conversations that have been deleted (or are being deleted) will no longer be listed in the sidebar on the spot until the deletion is completed. */
    private val _deleted = MutableStateFlow<Set<String>>(emptySet())
    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    /** It hasn’t been deleted yet. */
    private val deleting = mutableSetOf<String>()

    /** In addition to this dialogue on the screen, there are dialogues in which tasks are running or queued ("Another n tasks in progress" at the top). */
    val otherRunning: StateFlow<List<String>> = combine(activeConversations, _ui.map { it.conversationId }.distinctUntilChanged()) { active, current ->
        active.filter { it != current }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The conversation that was cut away during parallelization while the task is still running: the
     * questions and answers (original words, standards, steps, files) and whether the screen is open or
     * not are kept. The background continues to update the steps according to the status of its own
     * task, and the conversation is continued as it was after switching back. Discarded at the end of
     * the mission (that round is saved into the conversation by the service).
     */
    private class Parked(val exchange: Exchange, val screenSeen: Boolean, val pipClosed: Boolean = false)
    private val parked = mutableMapOf<String, Parked>()
    private val parkedJobs = mutableMapOf<String, Job>()

    /** Pause the question and answer question that is being followed by this conversation on the screen (called before switching to another conversation). */
    private fun parkLive() {
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val live = ui.exchanges.getOrNull(liveIndex) ?: return
        if (!live.status.active() || id !in activeConversations.value) return
        parked[id] = Parked(live, screenSeen, pipClosed)
        parkedJobs.remove(id)?.cancel()
        val flow = runState(id) ?: return
        parkedJobs[id] = scope.launch {
            flow.collect { s ->
                if (!s.active()) {
                    if (s != TaskState.Idle) { parked.remove(id); parkedJobs.remove(id)?.cancel() }
                    return@collect
                }
                parked[id]?.let { p -> parked[id] = Parked(reduce(p.exchange, s, appLabel, toolLabels::label), p.screenSeen, p.pipClosed) }
            }
        }
    }

    private fun unpark(id: String): Parked? {
        parkedJobs.remove(id)?.cancel()
        return parked.remove(id)
    }

    /** The switch is reread when there is no task, and it is also reread when starting a task and changing dialogues ([refreshParallel]): Here, changing the switch in the middle of the following task does not change the behavior of this round. */
    private var parallelOn = parallelFlag()
    private fun parallel(): Boolean {
        if (liveIndex < 0 && activeConversations.value.isEmpty()) parallelOn = parallelFlag()
        return parallelOn
    }

    /**
     * Reread switch when starting a task or changing conversations (this interface is not in the task):
     * The switch just changed in the settings will take effect without rebuilding the interface, which
     * is consistent with the reread switch every time the service starts a task. Other dialogue tasks
     * are still running, and they press their respective start switches in the service.
     */
    private fun refreshParallel() {
        if (liveIndex < 0) parallelOn = parallelFlag()
    }

    /** The position of the task being tracked in exchanges; -1 means none. */
    private var liveIndex = -1
    private var lingerJob: Job? = null
    /** It is queued behind the end of the service and has not yet been handed over to the beginning of the service; if you click stop during this period, it will be directly invalidated. */
    private var pendingStart: Job? = null
    /** The task being tracked is opened across the screen; once it is opened, picture-in-picture will be displayed until the end of the task (including the few seconds after completion). */
    private var screenSeen = false
    /** The user has turned off the small screen of the task being tracked (cleared when changing tasks, see [PipUi.closed]). */
    private var pipClosed = false

    init {
        // Even when the switch is off, follow the dialogue in the status bar: Tasks from other dialogues
        // (suspended capsules, scheduled tasks) are not connected to this dialogue on the screen.
        scope.launch { taskState.collect { s -> if (!parallel()) shownTask.value?.let { onShown(it.first, s) } ?: onState(s) } }
        scope.launch { shownTask.collect { t -> if (t != null && parallel()) onShown(t.first, t.second) } }
        // No matter who saves the dialogue on the screen (suspended capsule, timed task, here), please
        // fill in the saved one.
        scope.launch {
            store.changes.collect { id ->
                if (id == _ui.value.conversationId) syncSaved(id)
                // Someone has saved the deleted conversation again: List it again
                if (id in _deleted.value && id !in deleting && store.turns(id).isNotEmpty()) _deleted.update { it - id }
            }
        }
        // In parallel, only the takeover progress of the task of this dialogue on the screen is
        // collected.
        scope.launch {
            takeoverStage.collect { s -> if (ownTask()) _ui.update { it.copy(takeoverStage = s) } }
        }
        // Only recognize the newly listed criteria when the task is in progress: the old value at the
        // time of subscription may be that of the previous task. Is there a screen and status that is
        // unique to each task? In parallel, only the task of this dialogue on the screen is accepted.
        scope.launch { screenOpen.collect { if (it && ownTask()) onScreenOpened() } }
        scope.launch { sandboxReady.collect { r -> _ui.update { it.copy(attachEnabled = r) } } }
        scope.launch { sharedFiles.collect { if (ownTask()) onSharedFiles(it) } }
        scope.launch { generatedImages.collect { if (ownTask()) onGeneratedImages(it) } }
        scope.launch { runNotes.collect { notes -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withNotes(it, notes) }) } } }
        scope.launch { activities.collect { if (ownTask()) onActivities(it) } }
        scope.launch { steers.collect { if (ownTask()) onSteers(it) } }
        // The task is registered into the task list (the original words can be retrieved) may be later
        // than its first state: the question and answer followed at that time will be supplemented with
        // the original words.
        scope.launch { activeConversations.collect { fillGoal() } }
        scope.launch { summarizing.collect { set -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withSummarizing(it, set) }) } } }
        scope.launch { runningModel.collect { if (ownTask()) onRunningModel(it) } }
    }

    private fun onRunningModel(modelId: String?) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val ex = ui.exchanges.getOrNull(liveIndex) ?: return@update ui
            if (!ex.status.active() || ex.activeModel == modelId) return@update ui
            val exchanges = ui.exchanges.toMutableList()
            exchanges[liveIndex] = ex.copy(activeModel = modelId)
            ui.copy(exchanges = exchanges)
        }
    }

    /** At the end of the round: when the explanation is still being written, the mark "under preparation" will be marked; if it is written, it will be removed if it cannot be written. */
    private fun withSummarizing(ex: Exchange, set: Set<String> = summarizing.value): Exchange {
        val st = ex.status as? ExchangeStatus.Cancelled ?: return ex
        val on = st.summary == null && ex.runId != null && ex.runId in set
        return if (st.summarizing == on) ex else ex.copy(status = st.copy(summarizing = on))
    }

    private fun onActivities(list: List<RunEvent.Activity>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) {
                val ex = exchanges[liveIndex].copy(activities = list)
                exchanges[liveIndex] = ex.copy(
                    steps = rows(ex, toolLabels::label, appLabel),
                    stepActions = actionsOf(list, appLabel, toolLabels::label),
                )
            }
            ui.copy(exchanges = exchanges)
        }
    }

    /**
     * The direction of the interruption is changed according to the number to the question and answer
     * where it is located (it may no longer be the one following the task after the task is completed).
     * If you insert the task elsewhere (suspended capsule) into the task you are following, if it is not
     * here yet, hang it under the question and answer you are following.
     */
    private fun onSteers(list: List<SteerNote>) {
        val byId = list.associateBy { it.id }
        _ui.update { ui ->
            val shown = ui.exchanges.flatMap { ex -> ex.steers.map { it.id } }.toSet()
            val added = list.filter { it.id !in shown }
            ui.copy(exchanges = ui.exchanges.mapIndexed { i, ex ->
                val updated = if (ex.steers.none { it.id in byId }) ex
                    else ex.copy(steers = ex.steers.map { n -> byId[n.id]?.let { n.copy(state = it.state, interrupted = it.interrupted ?: n.interrupted, finished = it.finished ?: n.finished) } ?: n })
                if (i == liveIndex && added.isNotEmpty() && ex.status.active()) updated.copy(steers = updated.steers + added) else updated
            })
        }
    }

    /** Check whether the external items of the service (receiver progress, whether there is a screen, exported files) are the tasks of the dialogue on the screen at the moment: see who is displayed in parallel. */
    private fun ownTask() = !parallel() || shownTask.value?.first == _ui.value.conversationId

    private fun withNotes(ex: Exchange, notes: Map<String, RunNotes>): Exchange {
        val n = ex.runId?.let(notes::get) ?: return ex
        return ex.copy(memoryNotes = n.memoryNotes, usedMemory = n.usedMemory, skillNotes = n.skillNotes)
    }

    /** Prompt this line of text, the content is taken from the memory at the moment; if the item has been deleted, write "a deleted memory". */
    fun noteText(note: MemoryNote): String = note.text(memoryItems.value.find { it.id == note.memoryId })

    /** Undo this memory change; only remove "Undo" when the token has expired (restarted). */
    fun undoNote(runId: String, note: MemoryNote) {
        val token = note.undoToken ?: return
        scope.launch {
            val ok = learning?.undo(token) ?: false
            editNotes(runId) { ex -> ex.copy(memoryNotes = if (ok) ex.memoryNotes - note else ex.memoryNotes.map { if (it == note) it.copy(undoToken = null) else it }) }
        }
    }

    /** Practice: "Good" approval. */
    fun approveNote(runId: String, note: MemoryNote) {
        val id = note.pendingId ?: return
        scope.launch {
            learning?.approve(id)
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** Method: "Don't" undo this new addition; if the token is gone, just delete it. */
    fun rejectNote(runId: String, note: MemoryNote) {
        scope.launch {
            val undone = note.undoToken?.let { learning?.undo(it) } ?: false
            if (!undone) note.pendingId?.let { learning?.forget(it) }
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** The content when "n memories used" is clicked; deleted ones are not listed. */
    fun usedMemoryTexts(ids: List<String>): List<String> {
        val byId = memoryItems.value.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.content }
    }

    fun viewSkill(note: SkillNote) { note.draftId?.let { learning?.viewSkill(it) } }

    fun restoreSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.restoreSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    fun deleteSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.deleteSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    private suspend fun editNotes(runId: String, change: (Exchange) -> Exchange) {
        var saved: Exchange? = null
        _ui.update { ui ->
            ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it).also { e -> saved = e } else it })
        }
        val ex = saved ?: return
        val conversation = _ui.value.conversationId ?: return
        learning?.save(RunNotes(conversation, runId, ex.memoryNotes, ex.usedMemory, ex.skillNotes))
    }

    private fun onSharedFiles(files: List<SharedFile>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(files = files)
            ui.copy(exchanges = exchanges)
        }
    }

    private fun onGeneratedImages(images: List<GeneratedImageUi>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(generatedImages = images)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onGeneratedImageUpdated(img: GeneratedImageUi) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) {
                val current = exchanges[liveIndex].generatedImages.toMutableList()
                val idx = current.indexOfFirst { it.prompt == img.prompt }
                if (idx >= 0) {
                    current[idx] = img
                } else {
                    current.add(img)
                }
                exchanges[liveIndex] = exchanges[liveIndex].copy(generatedImages = current)
            }
            ui.copy(exchanges = exchanges)
        }
    }

    private fun canAttach(ui: ChatUi) = ui.attachEnabled && ui.composer != ComposerMode.Answer && !ui.importing

    /** Files obtained from the camera, photo album, and file selector: are collected when the sandbox is ready; read out the name and size, hang them above the input box, and send them with the next sentence (the task is used as an interruption when running). */
    fun attach(uris: List<String>) {
        if (uris.isEmpty() || !canAttach(_ui.value)) return
        scope.launch {
            val items = try { describeAttachments(uris) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            _ui.update { ui ->
                if (!canAttach(ui)) return@update ui
                val (all, dropped) = AttachTray.add(ui.attachments, items)
                ui.copy(attachments = all, overLimit = dropped, rejectedAttachments = emptyList())
            }
        }
    }

    /** Click on the most recent photo in the panel: select or cancel; return false if it is full. */
    fun toggleAttachment(item: PendingAttachment): Boolean {
        val ui = _ui.value
        if (!canAttach(ui)) return true
        val t = AttachTray.toggle(ui.attachments, item)
        _ui.update { it.copy(attachments = t.items) }
        return !t.full
    }

    fun removeAttachment(uri: String) = _ui.update { ui -> ui.copy(attachments = ui.attachments.filterNot { it.uri == uri }) }

    fun dismissRejected() = _ui.update { it.copy(rejectedAttachments = emptyList(), overLimit = 0) }

    private fun onScreenOpened() {
        if (liveIndex < 0 || screenSeen) return
        screenSeen = true
        _ui.update { ui ->
            val live = ui.exchanges.getOrNull(liveIndex)
            if (live != null && live.status.active()) ui.copy(pip = gated(ui.pip.copy(visible = true))) else ui
        }
    }

    /**
     * When to give the small screen: the screen must be opened (the other fields will be calculated as
     * usual and used directly when the screen is opened). When the user closes the small screen of this
     * task, only the small button is left, and it no longer automatically expands (it also does not pop
     * up when the takeover starts, the things you want to deal with are in the conversation card, and
     * the small button turns orange).
     */
    private fun gated(p: PipUi) = when {
        !screenSeen -> p.copy(visible = false, expanded = false, closed = false)
        pipClosed -> p.copy(expanded = false, closed = true)
        else -> p.copy(closed = false)
    }


    /** Long press the message and select "Quote": the quoted content is placed above the input box and sent with the next sentence (new tasks, interruptions, and answers are all included). Empty ones don’t lead. */
    fun quote(from: QuoteMessage.From, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) _ui.update { it.copy(quote = QuoteMessage.Quote(from, t)) }
    }

    fun clearQuote() = _ui.update { it.copy(quote = null) }

    /** Send the words in the input box; if there are quotes, the quotes are written in front of the words (see [QuoteMessage]), and they are cleared after sending. Returning false means it has not been sent (the conditions are not met, or the task is running). */
    fun send(text: String, check: Prerequisites.Check): Boolean {
        refreshParallel()
        val ui = _ui.value
        if (ui.importing) return false
        val sent = sendQuoted(text.trim(), ui, check)
        if (sent) _ui.update { it.copy(quote = null) }
        return sent
    }

    private fun sendQuoted(words: String, ui: ChatUi, check: Prerequisites.Check): Boolean {
        // The attachment follows the new task, and the task follows the interjection when running; it
        // does not follow when answering questions.
        if (ui.composer != ComposerMode.Answer && ui.attachments.isNotEmpty()) {
            return sendWithAttachments(QuoteMessage.compose(ui.quote, words), ui.attachments, check, steerToTask = ui.composer == ComposerMode.Busy)
        }
        // Only quotes and no words will be posted.
        if (words.isEmpty()) return false
        val goal = QuoteMessage.compose(ui.quote, words)
        when (ui.composer) {
            ComposerMode.Answer -> {
                act({ actions.answer(goal) }) { actions.answerIn(it, goal) }
                return true
            }
            ComposerMode.Busy -> return steer(goal, check)
            ComposerMode.Idle -> Unit
        }
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        begin(goal, ui.conversationId ?: store.newConversation())
        return true
    }

    /**
     * Send with attachments: First copy the attachments into the inbox/ of the conversation workspace
     * (the top of the input box displays Adding), and then write each attachment as a line "Attachment:
     * <path> (<size>)" followed by the message. If a new task is not opened while running the task, the task
     * will be handed over to it as an interruption when running ([steerToTask]). Those under Confiscated
     * are listed above the input box; if there are no words or attachments, they will not be sent. If
     * the conversation is changed during the copying process, or the status of the task changes, it will
     * not be sent (the copied file will remain in the workspace of the original conversation).
     */
    private fun sendWithAttachments(text: String, items: List<PendingAttachment>, check: Prerequisites.Check, steerToTask: Boolean = false): Boolean {
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val id = _ui.value.conversationId ?: store.newConversation()
        _ui.update { it.copy(conversationId = id, importing = true, rejectedAttachments = emptyList(), overLimit = 0) }
        scope.launch {
            val r = try { importAttachments(id, items.map { it.uri }) } catch (e: CancellationException) { throw e } catch (e: Exception) { ImportResult(emptyList(), items.map { it.name }) }
            val goal = withAttachments(text, r.added)
            _ui.update { it.copy(importing = false, attachments = emptyList(), rejectedAttachments = r.rejected) }
            val now = _ui.value
            if (goal.isEmpty() || now.conversationId != id) return@launch
            if (steerToTask) {
                if (now.composer == ComposerMode.Busy) steer(goal, check)
            } else if (now.composer == ComposerMode.Idle) {
                begin(goal, id)
            }
        }
        return true
    }

    /** Start a new task [goal] in the conversation [id]: Add a question and answer to the interface and hand it over to the service (it is closed in parallel and queued behind the end when the service is still busy). */
    private fun begin(goal: String, id: String) {
        lingerJob?.cancel()
        // The number of this dialogue has been determined: it will be followed after the capsule (newly
        // opened dialogues will not have numbers until this moment)
        markCurrent(CurrentChat.Open(id))
        // When the previous task ends, the screen is removed first and then the end is reported. What
        // you read here is what it looked like before the new task started; when parallel, it is the
        // screen of other dialogue tasks, and the new task has no screen yet. When a new message starts,
        // the screen of the last run cannot be inherited; after the current screen is actually built,
        // the small window is opened by screenOpen.
        screenSeen = false
        pipClosed = false
        _ui.update {
            liveIndex = it.exchanges.size
            val currentImages = if (ownTask()) generatedImages.value else emptyList()
            it.copy(
                conversationId = id,
                exchanges = it.exchanges + Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, startedAt = clock(), generatedImages = currentImages),
                blocked = null,
                attachments = emptyList(),
                pip = gated(PipUi(visible = true)),
                composer = ComposerMode.Busy,
            )
        }
        // When the service is busy in parallel (other dialogue tasks are running), it is directly handed
        // over to it.
        if (serviceBusy.value && !parallel()) {
            // During the waiting period, clearly state what you are waiting for so that it does not look
            // like it is stuck; after it is handed over to the service, the status of the task will be
            // written next.
            _ui.update { ui ->
                val exchanges = ui.exchanges.toMutableList()
                exchanges[liveIndex] = exchanges[liveIndex].copy(line = tr(R.string.chat_wait_previous_task))
                ui.copy(exchanges = exchanges)
            }
            // When handing it over, another place (timing, computer) may happen to start one first: the
            // service will queue it behind that one and not lose it.
            pendingStart = scope.launch {
                serviceBusy.first { !it }
                pendingStart = null
                actions.start(goal, id)
                // If you insert words while waiting: start the task of the original words first and then
                // hand it over to it
                held.toList().also { held.clear() }.forEach { n -> actions.steer(id, n.id, n.text) }
            }
        } else {
            actions.start(goal, id)
        }
    }

    /**
     * If the task is sent while running: hang it under the question and answer you are following and
     * hand it over to this task; its whereabouts will be reported back by the service ([steers]). It may
     * become the next thing, so it goes through the same checks as starting a new task. Keep the task
     * before it is handed over to the service ([pendingStart]), and then send it after it is started.
     */
    private fun steer(text: String, check: Prerequisites.Check): Boolean {
        val id = _ui.value.conversationId ?: return false
        if (liveIndex !in _ui.value.exchanges.indices) return false
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val note = SteerNote(java.util.UUID.randomUUID().toString(), text, SteerState.Pending)
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            exchanges[liveIndex] = exchanges[liveIndex].let { it.copy(steers = it.steers + note) }
            ui.copy(exchanges = exchanges)
        }
        if (pendingStart?.isActive == true) held += note else actions.steer(id, note.id, text)
        return true
    }

    fun withdrawSteer(messageId: String): Boolean {
        val ui = _ui.value
        val id = ui.conversationId ?: return false
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return false
        if (!note.state.unconsumed || note.remote) return false
        if (!held.removeAll { it.id == messageId } && !actions.withdrawSteer(id, messageId)) return false
        _ui.update { now -> now.copy(exchanges = now.exchanges.map { ex ->
            ex.copy(steers = ex.steers.map { if (it.id == messageId) it.copy(state = SteerState.Withdrawn) else it })
        }) }
        return true
    }

    fun editSteer(messageId: String): String? {
        val ui = _ui.value
        if (ui.quote != null || ui.attachments.isNotEmpty() || ui.importing) return null
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return null
        if (!editableSteer(note.text) || !withdrawSteer(messageId)) return null
        val parsed = QuoteMessage.parse(note.text)
        _ui.update { it.copy(quote = parsed.quote) }
        return parsed.words
    }

    /** The words inserted when the task of the original words has not been handed over to the service. */
    private val held = mutableListOf<SteerNote>()

    /**
     * Only the status of the task of this dialogue on the screen is closed; when the interface first
     * comes up and there is no dialogue, it is connected to the running one (along with the saved rounds
     * of that dialogue). When the service changes to display another conversation task (one that has
     * just started elsewhere), let the service display return while the task being followed here is
     * still running, so as not to stop it in the middle.
     */
    private fun onShown(conversationId: String, s: TaskState) {
        // The task of deleted dialogue is stopping: not being received on the screen
        if (conversationId in _deleted.value) return
        val current = _ui.value.conversationId
        when {
            current == null -> {
                if (!s.active()) return
                _ui.update { it.copy(conversationId = conversationId, exchanges = saved(conversationId)) }
            }
            current != conversationId -> {
                val live = _ui.value.exchanges.getOrNull(liveIndex)
                if (live != null && live.status.active() && current in activeConversations.value && pendingStart?.isActive != true) actions.show(current)
                return
            }
        }
        onState(s)
    }

    /**
     * Switch to a certain conversation (you can also switch when the task is running in parallel): load
     * its history; if it has a task running, let the service display that task and connect its current
     * status. When the switch is turned off, it is the same as [openConversation].
     */
    fun switchTo(conversationId: String) {
        refreshParallel()
        if (!parallel()) return openConversation(conversationId)
        lingerJob?.cancel()
        if (conversationId != _ui.value.conversationId) parkLive()
        val past = saved(conversationId)
        liveIndex = -1
        // Conversations without tasks do not take over the progress of other tasks.
        _ui.update { ChatUi(conversationId = conversationId, exchanges = past, takeoverStage = Takeover.Stage.None) }
        markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { unpark(conversationId); return }
        follow(conversationId, unpark(conversationId))
    }

    /**
     * Pick up the task that this conversation on the screen is running from (no matter where it started:
     * here, suspension capsule, timed task). When following up, arrange the question and answer first
     * (cut the [p] that was parked when leaving, or start a new one according to the original word of
     * the task), then let the service display it, and complete it according to the various items
     * currently exposed by the service.
     */
    private fun follow(conversationId: String, p: Parked? = null) {
        val fresh = liveIndex < 0
        if (fresh) {
            // First lay out the questions and answers of this conversation, and then let the service
            // display it: as soon as the service changes the display, the status may be sent in on the
            // spot (onShown). At that time, the questions and answers must already be there, otherwise
            // another one without the original words will be created.
            val live = p?.exchange ?: Exchange(runGoal(conversationId).orEmpty(), emptyList(), ExchangeStatus.Working, live = true, startedAt = clock(), generatedImages = if (ownTask()) generatedImages.value else emptyList(), activeModel = if (ownTask()) runningModel.value else null)
            screenSeen = p?.screenSeen ?: false
            pipClosed = p?.pipClosed ?: false
            _ui.update {
                liveIndex = it.exchanges.size
                it.copy(exchanges = it.exchanges + live, composer = ComposerMode.Busy, pip = gated(PipUi(visible = true)))
            }
        }
        actions.show(conversationId)
        val shown = shownTask.value?.takeIf { it.first == conversationId } ?: return
        // The question and answer you are following does not use the completed status in the service to
        // end it (that may be the previous task)
        if (!fresh && !shown.second.active()) return
        // These external services are all related to this task at this moment: click them to complete
        // (the status flow is only sent when it changes, and it does not necessarily need to be sent
        // again when it is switched)
        if (!screenSeen && screenOpen.value) screenSeen = true
        _ui.update { it.copy(takeoverStage = takeoverStage.value) }
        onSharedFiles(sharedFiles.value)
        onGeneratedImages(generatedImages.value)
        onActivities(activities.value)
        onSteers(steers.value)
        onRunningModel(runningModel.value)
        onState(shown.second)
    }

    /**
     * When the conversation page becomes visible again (returning to the foreground or coming back from
     * another page): Save the turn of this conversation on the screen elsewhere to make up for it; this
     * conversation has a task running (starting from a suspended capsule or a scheduled task) but it is
     * not being followed here, or the service display is replaced by another task, just pick it up.
     */
    fun refresh() {
        refreshParallel()
        val id = _ui.value.conversationId ?: return
        syncSaved(id)
        if (id !in activeConversations.value || pendingStart?.isActive == true) return
        // If the switch is turned off and you are not changing the dialogue during the task, you have to
        // wait until the task ends: at that time, the task of the dialogue on the screen is the only one
        // running in the service.
        follow(id, if (parallel()) unpark(id) else null)
    }

    /** The saved rounds of this conversation, together with the exported files of each round and the memory and skill tips at the moment. */
    private fun saved(conversationId: String): List<Exchange> =
        runCatching { store.records(conversationId) }.getOrDefault(emptyList()).map { (t, files) -> withSummarizing(withNotes(fromTurn(t).copy(files = files), runNotes.value)) }

    /**
     * The dialogue in the storage has changed (a round was saved after running elsewhere, and a certain
     * round was changed): Press the saved one on the screen to rearrange. The rounds that have been
     * followed here are identified by running numbers (if there is no number, use the original words),
     * use the one here (with steps and time), do not repeat; the ones that have not been saved are
     * continued; the questions and answers that are being followed are left at the end.
     */
    private fun syncSaved(conversationId: String) {
        val stored = saved(conversationId)
        _ui.update { ui ->
            if (ui.conversationId != conversationId) return@update ui
            val live = ui.exchanges.getOrNull(liveIndex)
            val tracked = ui.exchanges.filterIndexed { i, ex -> ex.live && i != liveIndex }.toMutableList()
            // From the most recent to the first: When the same original sentence is said several times,
            // the most recent one is used.
            val merged = stored.asReversed().map { s ->
                val mine = tracked.lastOrNull { if (s.runId != null) it.runId == s.runId else it.runId == null && it.goal == s.goal }
                    ?: return@map s
                tracked.remove(mine)
                // Accounts made after the stop are recorded in the saved copy.
                val m = if (mine.status is ExchangeStatus.Cancelled && s.status is ExchangeStatus.Cancelled && s.status.summary != null) mine.copy(status = s.status) else mine
                // The record of the left page (the site left and the reason why it was later closed) is
                // only in the saved copy.
                val k = if (s.keptPage != null) m.copy(keptPage = s.keptPage, keptPageClosed = s.keptPageClosed) else m
                if (k.goal.isEmpty()) k.copy(goal = s.goal) else k
            }.asReversed()
            val exchanges = merged + tracked + listOfNotNull(live)
            if (exchanges == ui.exchanges) return@update ui
            liveIndex = if (live != null) exchanges.lastIndex else -1
            ui.copy(exchanges = exchanges)
        }
    }

    fun dismissBlocked() = _ui.update { it.copy(blocked = null) }

    /** Stop: The task is invalidated together with the interrupts that have not been handed over to it (interruptions are no longer regarded as the next thing). */
    fun cancel() {
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].let { ex ->
                ex.copy(steers = ex.steers.map { if (it.state.unconsumed) it.copy(state = SteerState.Cancelled) else it })
            }
            ui.copy(exchanges = exchanges)
        }
        val pending = pendingStart
        if (pending == null || !pending.isActive) {
            // If this conversation has a task in the service (running or queued), just stop it: a stop
            // without a number will be counted as the task in the service display. If this task is still
            // queued behind a task started elsewhere, that task will be stopped.
            val id = _ui.value.conversationId
            if (id != null && id in activeConversations.value) actions.cancelIn(id) else act({ actions.cancel() }) { actions.cancelIn(it) }
            return
        }
        // The task has not yet been handed over to the service: cancel this start; the last task being
        // completed in the service is not touched.
        pending.cancel()
        pendingStart = null
        held.clear()
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(status = ExchangeStatus.Cancelled())
            ui.copy(exchanges = exchanges, pip = PipUi(), composer = ComposerMode.Idle)
        }
        liveIndex = -1
    }
    fun resume() = act({ actions.resume() }) { actions.resumeIn(it) }
    fun pause() = act({ actions.pause() }) { actions.pauseIn(it) }
    fun takeOver() = act({ actions.takeOver() }) { actions.takeOverIn(it) }
    fun handBack() = act({ actions.handBack() }) { actions.handBackIn(it) }
    fun finishedMyself() = act({ actions.finishedMyself() }) { actions.finishedMyselfIn(it) }
    fun skip() = act({ actions.skip() }) { actions.skipIn(it) }
    fun answer(text: String) = act({ actions.answer(text) }) { actions.answerIn(it, text) }
    fun approve(key: Int) = act({ actions.approve(key) }) { actions.approveIn(it, key) }
    fun decline(key: Int) = act({ actions.decline(key) }) { actions.declineIn(it, key) }

    /**
     * Buttons in the application: When the switch is turned off, it acts on the task (M1) in the service
     * display; in parallel, it brings the dialogue on the screen at the moment when it is pressed, and
     * only acts on its task. This dialogue will not be sent without a task.
     */
    private inline fun act(serial: () -> Unit, scoped: (String) -> Unit) {
        if (!parallel()) return serial()
        val id = _ui.value.conversationId ?: return
        if (id in activeConversations.value) scoped(id)
    }

    fun expandPip() = _ui.update { if (it.pip.visible) it.copy(pip = it.pip.copy(expanded = true)) else it }
    /** When you want to deal with it, click Picture-in-Picture: put it away and scroll from the interface to the card you want to deal with. */
    fun attendPip() = collapsePip()
    fun collapsePip() = _ui.update { it.copy(pip = it.pip.copy(expanded = false)) }

    /** The user closes the small screen of this task: only the small button in the lower right corner is left, and the task will no longer appear before the end of the task. */
    fun closePip() {
        if (liveIndex < 0) return
        pipClosed = true
        _ui.update { it.copy(pip = gated(it.pip)) }
    }

    /** Click the small button: Open the small screen of this task again. */
    fun reopenPip() {
        pipClosed = false
        _ui.update { it.copy(pip = gated(it.pip)) }
    }

    /** Cut to a certain dialogue; the task will not cut while running. */
    fun openConversation(id: String) {
        refreshParallel()
        if (parallel()) return switchTo(id)
        if (busy()) return
        val past = saved(id)
        liveIndex = -1
        _ui.update { ChatUi(conversationId = id, exchanges = past, takeoverStage = it.takeoverStage, attachEnabled = it.attachEnabled) }
        markCurrent(CurrentChat.Open(id))
        // The task of this conversation started elsewhere (suspended capsule, timed task): open it and
        // pick it up
        if (id in activeConversations.value) follow(id)
    }

    /**
     * Tell the floating capsule which conversation the user is in at the moment ([CurrentChat]): the
     * capsule's free card follows it, instead of guessing based on who wrote the last conversation file.
     * Failure to write will not affect the interface.
     */
    private fun markCurrent(current: CurrentChat) {
        runCatching { store.setCurrent(current) }
    }

    fun newConversation() {
        refreshParallel()
        if (parallel()) {
            // Other tasks may still be running: give the new conversation a number first, so that its
            // status will not be regarded as this conversation.
            lingerJob?.cancel()
            parkLive()
            liveIndex = -1
            _ui.update { ChatUi(conversationId = store.newConversation(), takeoverStage = Takeover.Stage.None, attachEnabled = it.attachEnabled) }
            // Haven't spoken yet: There is a new dialogue card on the capsule (the number will be
            // determined after it is announced, see begin)
            markCurrent(CurrentChat.Fresh)
            return
        }
        if (busy()) return
        liveIndex = -1
        _ui.update { ChatUi(takeoverStage = it.takeoverStage, attachEnabled = it.attachEnabled) }
        markCurrent(CurrentChat.Fresh)
    }

    /** What will happen if you delete [id]: The confirmation pop-up window in the sidebar will indicate that the task will be stopped and the scheduled task will be deleted. */
    fun deleteImpact(id: String): DeleteImpact = deletionImpact(id)

    /**
     * Delete dialogue [id] (user confirmed on sidebar): Remove it from the sidebar on the spot; replace
     * the dialogue on the screen with a new empty dialogue. Stop tasks, delete scheduled tasks, and
     * delete files in the background (see `ConversationDeleter`).
     */
    fun deleteConversation(id: String) {
        if (!deleting.add(id)) return
        _deleted.update { it + id }
        if (id == _ui.value.conversationId) startOver(id) else unpark(id)
        scope.launch {
            try { removeConversation(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { } finally { deleting -= id }
        }
    }

    /** The user renamed the conversation [id] in the sidebar: to [title](remove the blanks at both ends; do not change the empty ones). The title created later will not cover it up again. */
    fun renameConversation(id: String, title: String) {
        if (id in _deleted.value) return
        runCatching { store.rename(id, title) }
    }

    /** Pin or unpin conversation [id] in the sidebar. */
    fun setPinned(id: String, pinned: Boolean) {
        if (id in _deleted.value) return
        runCatching { store.setPinned(id, pinned, clock()) }
    }

    /** The dialogue on the screen was deleted: it no longer related to its tasks (those that had not yet been handed over to the service were immediately discarded) and replaced with a new empty dialogue. */
    private fun startOver(id: String) {
        lingerJob?.cancel()
        pendingStart?.cancel()
        pendingStart = null
        held.clear()
        unpark(id)
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        val fresh = if (parallel()) store.newConversation() else null
        _ui.update { ChatUi(conversationId = fresh, takeoverStage = Takeover.Stage.None, attachEnabled = it.attachEnabled) }
        // A new empty dialogue was replaced on the screen: The capsule no longer continues the deleted
        // one.
        markCurrent(CurrentChat.Fresh)
    }

    fun busy() = _ui.value.composer != ComposerMode.Idle

    /** The app or website you are currently operating (the most recent thing that has an object); use the subtitle "Operating..." in the top column. If not, null. */
    fun livePlace(ex: Exchange): String? =
        ex.activities.lastOrNull { !it.target.isNullOrBlank() }?.target?.let { where(it, appLabel) }

    /** The original words of the task that the conversation is running on; use this as the title of the one in the "In Progress" sidebar that has not yet been saved in the conversation list. */
    fun runningGoal(conversationId: String): String? = runGoal(conversationId)

    /** Can you switch to another conversation: You can switch to another conversation at any time during parallel operation, otherwise you have to wait for the task of this conversation to end. */
    fun canSwitch() = parallel() || !busy()

    private fun onState(s: TaskState) {
        if (liveIndex < 0) {
            // The task does not start here (for example, it was running before the interface was
            // rebuilt): it is then displayed, but there is no user sentence
            if (!s.active()) return
            screenSeen = screenOpen.value
            pipClosed = false
            _ui.update {
                liveIndex = it.exchanges.size
                // Original words and files are taken according to the conversation: this status belongs
                // to the task of the conversation on the screen.
                val goal = it.conversationId?.let(runGoal).orEmpty()
                val files = if (ownTask()) sharedFiles.value else emptyList()
                val images = if (ownTask()) generatedImages.value else emptyList()
                val model = if (ownTask()) runningModel.value else null
                val ex = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, files = files, generatedImages = images, startedAt = clock(), activeModel = model)
                it.copy(exchanges = it.exchanges + ex)
            }
        }
        _ui.update { ui ->
            val old = ui.exchanges[liveIndex]
            val ex = reduce(withGoal(old, ui.conversationId), s, appLabel, toolLabels::label).let {
                if (it.status.active() || !old.status.active()) {
                    // When running, just hang up the task number of this dialogue, and the "running
                    // process" will be available on the spot; after the end, continue to the next one.
                    val live = if (it.status.active() && it.runId == null) ui.conversationId?.let(liveRunId) else null
                    val withRunId = if (live != null) it.copy(runId = live) else it
                    if (withRunId.status.active() && withRunId.activeModel == null && ownTask()) {
                        runningModel.value?.let { m -> withRunId.copy(activeModel = m) } ?: withRunId
                    } else {
                        withRunId
                    }
                }
                // Interruptions at the end that have not been handed over to the task: the service
                // treats it as the next thing in the conversation
                else withSummarizing(withNotes(it.copy(runId = endedRunFor(ui.conversationId), elapsedMs = elapsedOf(old),
                    steers = it.steers.map { n -> if (n.state.unconsumed) n.copy(state = SteerState.Next) else n }, activeModel = null), runNotes.value))
            }
            val exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = ex }
            ui.copy(
                exchanges = exchanges,
                pip = gated(pipFor(ui.pip, old.status, ex)),
                composer = when {
                    ex.status is ExchangeStatus.NeedsInput -> ComposerMode.Answer
                    ex.status.active() -> ComposerMode.Busy
                    else -> ComposerMode.Idle
                },
            )
        }
        if (!s.active() && s != TaskState.Idle) {
            liveIndex = -1
            // This round may have been saved in the conversation and added to the screen: press the run
            // number and merge it into one
            _ui.value.conversationId?.let(::syncSaved)
            if (s is TaskState.Succeeded) {
                lingerJob = scope.launch {
                    delay(DONE_LINGER_MS)
                    _ui.update { it.copy(pip = it.pip.copy(visible = false, expanded = false)) }
                }
            }
        }
    }

    /** The question and answer you are following does not have the original words (it has not yet registered when taking up the task started elsewhere): press the task of this dialogue on the screen to complete it. */
    private fun fillGoal() = _ui.update { ui ->
        val ex = ui.exchanges.getOrNull(liveIndex) ?: return@update ui
        val filled = withGoal(ex, ui.conversationId)
        if (filled === ex) ui else ui.copy(exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = filled })
    }

    private fun withGoal(ex: Exchange, conversationId: String?): Exchange {
        if (ex.goal.isNotEmpty() || !ex.status.active()) return ex
        return conversationId?.let(runGoal)?.takeIf(String::isNotEmpty)?.let { ex.copy(goal = it) } ?: ex
    }

    private fun elapsedOf(ex: Exchange): Long? = ex.startedAt.takeIf { it > 0 }?.let { (clock() - it).coerceAtLeast(0) }

    private fun endedRunFor(conversationId: String?): String? {
        // When there is no dialogue number (to connect to a task that was running before the interface
        // was rebuilt), only the one on display can be used.
        val byConversation = endedRunIds ?: return endedRunId.value
        return if (conversationId == null) endedRunId.value else byConversation.value[conversationId]
    }

    private fun pipFor(old: PipUi, before: ExchangeStatus, ex: Exchange): PipUi {
        val st = ex.status
        return when (st) {
            is ExchangeStatus.Done -> PipUi(true, PipMode.Done, false)
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> PipUi()
            else -> {
                val needs = st.needsUser()
                // It will only automatically expand at the beginning of the takeover (the input box and
                // card button should remain in front of you when asking questions and pausing); it will
                // collapse when the takeover ends; it will no longer pop up after the user collapses it
                // in the same takeover.
                val takeover = st is ExchangeStatus.NeedsTakeover
                val wasTakeover = before is ExchangeStatus.NeedsTakeover
                val expanded = when {
                    takeover && !wasTakeover -> true
                    !takeover && wasTakeover -> false
                    else -> old.expanded
                }
                PipUi(true, if (needs) PipMode.NeedsYou else PipMode.Running, expanded)
            }
        }
    }

    companion object {
        const val DONE_LINGER_MS = 3_000L


        private fun TaskState.active() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.active() = when (this) {
            is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.needsUser() =
            this is ExchangeStatus.NeedsTakeover || this is ExchangeStatus.NeedsInput || this is ExchangeStatus.Paused ||
                this is ExchangeStatus.NeedsConfirmation

        internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
            state(ex, s, appLabel).let {
                it.copy(steps = rows(it, toolLabel, appLabel), stepActions = actionsOf(it.activities, appLabel, toolLabel))
            }

        /** Action details organized by step number: listed when a step is clicked. */
        private fun actionsOf(activities: List<RunEvent.Activity>, appLabel: (String) -> String, toolLabel: (String) -> String) =
            activities.groupBy { it.step }.mapValues { (_, list) ->
                // Use the interface description of the tool (the same as the running process page) and
                // do not expose the tool name to the user.
                list.map { a -> StepAction(toolLabel(a.label), a.target?.let { where(it, appLabel) }, a.effect, a.failed) }
            }

        private fun state(ex: Exchange, s: TaskState, appLabel: (String) -> String): Exchange = when (s) {
            TaskState.Idle -> ex
            is TaskState.Running -> {
                val progress = s.progress?.let(StatusCopy::progress)
                // If "What are you doing" is written first using the model callback, the intention in
                // the call will not be used again; if the action has not been recorded yet, add
                // "Opening..."
                val said = (s.narration ?: s.reason)?.takeIf(String::isNotBlank)
                val waiting = progress == null && s.tool == null && said == null
                val label = if (waiting) "" else progress ?: said.orEmpty()
                val liveAction = if (label.isEmpty() && s.tool != null) {
                    ChatCopy.liveActionLabel(
                        s.tool, s.note, isHindi = ChatCopy.isHindiLocale(uiLocale().toLanguageTag()),
                    )
                } else null
                ex.copy(
                    status = ExchangeStatus.Working, waitingForModel = waiting, draft = s.preview,
                    usage = s.usage,
                    line = label,
                    liveAction = liveAction,
                    notes = if (s.step > 0 && said != null) ex.notes + (s.step to said) else ex.notes,
                )
            }
            // Preconditions for waiting for abilities: Automatically continue when the screen is locked;
            // queue to start; others who can click continue will be continued.
            is TaskState.Held -> ex.copy(
                status = when {
                    s.reason == TaskGate.LOCKED -> ExchangeStatus.WaitingUnlock
                    s.reason.queued -> ExchangeStatus.Queued(s.reason.why)
                    else -> ExchangeStatus.Paused(s.reason.why, s.reason.resumable)
                },
                waitingForModel = false, draft = null,
            )
            is TaskState.WaitingTakeover ->
                ex.copy(status = ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address), waitingForModel = false, draft = null)
            is TaskState.WaitingInput -> ex.copy(status = ExchangeStatus.NeedsInput(s.prompt, s.options), waitingForModel = false, draft = null)
            is TaskState.WaitingConfirmation -> ex.copy(
                waitingForModel = false,
                draft = null,
                status = ExchangeStatus.NeedsConfirmation(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
                    ConfirmationNotifier.requestKey(s),
                ),
            )
            // The answer is displayed as it is (there is no longer a basis section written by the
            // model); the source is given by the code written down in the running record.
            is TaskState.Succeeded -> ex.copy(status = ExchangeStatus.Done(s.answer.trim(), null, s.verified, s.note, s.unconfirmed, s.reviewed),
                waitingForModel = false, draft = null, line = "")
            is TaskState.Failed -> ex.copy(status = ExchangeStatus.Failed(s.reason), waitingForModel = false, draft = null, line = "")
            TaskState.Cancelled -> ex.copy(status = ExchangeStatus.Cancelled(), waitingForModel = false, draft = null, line = "")
        }

        /**
         * Step list: One line for each thing actually done. The reading class only appears when you know
         * where you are reading, and the same place is read consecutively into one line ("Looked at the
         * chat list of WeChat"); the action is written as "Click · WeChat", and if the model writes the
         * words in that step, it will be used; if it fails, it will just say that it was not successful,
         * and no error code will be displayed. The status of the last line follows this question and
         * answer: running, waiting for you, finished.
         */
        internal fun rows(ex: Exchange, toolLabel: (String) -> String, appLabel: (String) -> String): List<StepRow> {
            val out = mutableListOf<StepRow>()
            val said = mutableSetOf<Int>()
            for (a in ex.activities) {
                val where = a.target?.let { where(it, appLabel) }
                if (a.kind == "observe") {
                    // If you read it successfully but don’t know where you read it: not listed; if you
                    // didn’t read it successfully, it will still be listed.
                    if (where == null && !a.failed) continue
                    val text = if (where == null) toolLabel(a.label)
                        else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
                    val subtitle = if (a.failed) tr(R.string.step_failed) else ""
                    val last = out.lastOrNull()
                    if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
                } else {
                    val reason = if (said.add(a.step)) ex.notes[a.step].orEmpty() else ""
                    val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
                    out += StepRow(a.step, title, if (a.failed) tr(R.string.step_failed) else "", StepState.Ok, reason)
                }
            }
            if (out.isEmpty()) return out
            val last = when (ex.status) {
                ExchangeStatus.Working -> StepState.Run
                is ExchangeStatus.Done -> StepState.Ok
                is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> StepState.Dim
                else -> StepState.Wait
            }
            out[out.lastIndex] = out.last().copy(state = last)
            return out
        }

        /** The description of the event object for people to see: replace the package name with the app name, and leave only the domain name in the "website domain name". */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX)
            com.meenai.mina.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }

        private fun fromTurn(t: Turn): Exchange {
            val status = when (t.status) {
                "succeeded" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e) }
                "unverified" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e, verified = false, note = t.note, unconfirmed = t.unconfirmed) }
                "cancelled" -> ExchangeStatus.Cancelled(Conversation.stopSummary(t))
                else -> ExchangeStatus.Failed(t.outcome)
            }
            return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId,
                memoryNotes = t.memoryNotes, usedMemory = t.usedMemory, skillNotes = t.skillNotes,
                keptPage = t.keptPage, keptPageClosed = t.keptPageClosed,
                steers = t.steers.mapIndexed { i, text ->
                    SteerNote("turn-$i", text, SteerState.Joined, t.interruptedReplies.getOrNull(i)?.takeIf(String::isNotEmpty),
                        t.finishedReplies.getOrNull(i)?.takeIf(String::isNotEmpty))
                },
                id = t.runId ?: java.util.UUID.randomUUID().toString())
        }


        /** At the end of the message, each attachment has one line: "Attachment:/workspace/inbox/<name>(<size>)"; [files] is the (path, number of bytes) copied into the workspace. */
        internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String =
            AttachmentLines.compose(text, files.map { (p, n) -> p to sizeLabel(n) })

        fun sizeLabel(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * The answers and evidence text on the result card are only taken from here. The new answer does not
 * have a basis section (the source is found in the evidence ledger by the code, see [evidenceSource]);
 * in the old running records and conversation files, the "basis:" section written by the model itself at
 * the end is still removed. [evidence] Just use it as soon as you give it.
 */
fun answerParts(answer: String, evidence: String? = null): Pair<String, String?> =
    evidence?.trim()?.takeIf { it.isNotEmpty() }?.let { answer.trim() to it } ?: splitEvidence(answer)

/**
 * Source of answer: App or website ([place], null if unknown), related screen number, and step-by-step
 * explanation ([items], "Step 3 · 12306 · Train Query"). Take it from the running record: if there is a
 * source (sources event) recorded by the code, use it; for old records, use the last step where the
 * screen was saved.
 */
data class EvidenceSource(val place: String?, val screenshots: List<Int>, val items: List<String> = emptyList())

/** See [EvidenceSource];[appLabel]. Replace the package name with the App name, and the website and URL will display the domain name. */
fun evidenceSource(events: List<RunEvent>, appLabel: (String) -> String = { it }): EvidenceSource {
    events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { list ->
        fun where(p: String?) = p?.let { placeLabel(it, appLabel) }
        val items = list.map { s -> listOfNotNull(tr(R.string.step_number, s.step), where(s.place), s.title).joinToString(" · ") }
        return EvidenceSource(list.asReversed().firstNotNullOfOrNull { where(it.place) }, list.flatMap { it.mediaIds }.distinct(), items)
    }
    val shots = events.filterIsInstance<RunEvent.Media>()
    val lastStep = shots.maxOfOrNull { it.step } ?: return EvidenceSource(null, emptyList())
    val final = shots.filter { it.step == lastStep }
    val place = shots.lastOrNull { it.place != null }?.place?.let {
        when {
            it.startsWith(SITE_PREFIX) -> it.removePrefix(SITE_PREFIX)
            it.startsWith("网站 ") -> it.removePrefix("网站 ")
            else -> appLabel(it)
        }
    }
    return EvidenceSource(place, final.map { it.mediaId }.distinct())
}

/** The "Place" on the web page in the operation record is written as "Site <domain name>". */
private const val SITE_PREFIX = "Site "

/** In the source: "Site domain name" and the URL display domain name, the package name is replaced with the App name, and the other (commands, files) remain unchanged. */
private fun placeLabel(place: String, appLabel: (String) -> String): String = when {
    place.startsWith(SITE_PREFIX) -> place.removePrefix(SITE_PREFIX)
    place.startsWith("网站 ") -> place.removePrefix("网站 ")
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.meenai.mina.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}

/** The "Source" line: "Source · 12306 · 2 screenshots"; omit that paragraph if there is no place or screenshots. */
fun sourceLabel(source: EvidenceSource?): String = listOfNotNull(
    tr(R.string.source_label), source?.place, source?.screenshots?.size?.takeIf { it > 0 }?.let { trPlural(R.plurals.screenshots, it) },
).joinToString(" · ")

/**
 * Break the answer into "conclusion" and "basis". In the old running records and conversation files, the
 * last line of the answer begins with "Basis:"; the model may not necessarily follow this, so it also
 * recognizes "evidence/source" and the situation where it is written at the end of the sentence, and
 * everything after that is considered the basis. If you don't recognize it, the entire paragraph will be
 * the conclusion, and you won't put "source" below the answer.
 */
fun splitEvidence(answer: String): Pair<String, String?> {
    val m = EVIDENCE.find(answer) ?: return answer.trim() to null
    val evidence = answer.substring(m.range.last + 1).trim()
    val main = answer.substring(0, m.range.first + m.groupValues[1].length).trim()
    if (evidence.isEmpty() || main.isEmpty()) return answer.trim() to null
    return main to evidence
}

// After the punctuation at the beginning of a paragraph or at the end of a sentence, markdown’s **, #, -
// and other prefixes can be used
private val EVIDENCE = Regex("""(^|\n|[。.!！?？]\s*)[\s*#>\-]*\**(?:依据|证据|来源)\**\s*[:：]\**""")

/**
 * What the tool says on the interface: The label (Chinese) in the tool declaration is replaced with the
 * corresponding resource according to the interface language; the tool name is displayed as it is
 * without label. The old name in the running record before the name change is identified as [legacy] as
 * the current tool.
 */
class ToolLabels(private val tools: ToolIndex, private val legacy: LegacyNames = legacyNames()) {
    fun label(tool: String): String {
        val declared = (tools[tool] ?: legacy.resolve(tool)?.let { tools[it] })?.label ?: return tool
        return TOOL_LABELS[declared]?.let { tr(it) } ?: declared
    }

    companion object {
        /** The label in the tool declaration → interface text; the Chinese resource is exactly the same as the label (ToolLabelsTest checks that each tool has it). */
        val TOOL_LABELS: Map<String, Int> = mapOf(
            "List installed apps" to R.string.tool_list_apps,
            "Open app" to R.string.tool_open_app,
            "Share text to app" to R.string.tool_share_text,
            "Set alarm" to R.string.tool_set_alarm,
            "Set timer" to R.string.tool_set_timer,
            "Show alarms" to R.string.tool_show_alarms,
            "Add calendar event" to R.string.tool_add_calendar_event,
            "Turn picker wheel" to R.string.tool_set_wheel,
            "Scroll to find" to R.string.tool_scroll_find,
            "Wait for screen condition" to R.string.tool_wait_condition,
            "Wait for screen event" to R.string.tool_wait_event,
            "Extract screen data" to R.string.tool_extract,
            "Check for dialogs" to R.string.tool_check_dialog,
            "Multi-touch gesture" to R.string.tool_gesture,
            "System command" to R.string.tool_shell,
            "Find on screen" to R.string.tool_find_element,
            "Collect across screens" to R.string.tool_collect,
            "Several phone actions" to R.string.tool_phone_run,
            "Work with files" to R.string.tool_process_files,
            "Run built-in script" to R.string.tool_run_script,
            "Read file" to R.string.tool_read_file,
            "Write file" to R.string.tool_write_file,
            "Search files" to R.string.tool_search_files,
            "Export file" to R.string.tool_export_file,
            "Open web page" to R.string.tool_open_page,
            "Switch tab" to R.string.tool_switch_tab,
            "Switch page version" to R.string.tool_switch_version,
            "Keep page open" to R.string.tool_keep_page,
            "Choose whether the app stays open afterwards" to R.string.tool_app_after_task,
            "Read web page" to R.string.tool_read_page,
            "Check downloads" to R.string.tool_downloads,
            "Check page network data" to R.string.tool_network,
            "Wait for page change" to R.string.tool_wait_page,
            "Read page data" to R.string.tool_read_script,
            "Run site command" to R.string.tool_site_command,
            "Upload file" to R.string.tool_upload,
            "Hit an obstacle" to R.string.tool_obstacle,
            "Look up app/site knowledge" to R.string.tool_find_knowledge,
            "Look up skills" to R.string.tool_find_skill,
            "Read app/site knowledge" to R.string.tool_read_knowledge,
            "Read skill" to R.string.tool_read_skill,
            "Save skill" to R.string.tool_save_skill,
            "Read settings" to R.string.tool_list_settings,
            "Change a setting" to R.string.tool_set_setting,
            "Explain why it didn’t work" to R.string.tool_explain_failure,
            "Ask you a question" to R.string.tool_ask_user,
            "Ask you to take over" to R.string.tool_takeover,
            "Ask for your OK" to R.string.tool_confirm,
            "Wait" to R.string.tool_wait,
            "Check screen" to R.string.tool_diagnose,
            "Back" to R.string.tool_back,
            "Press key" to R.string.tool_press_key,
            "Look at screen" to R.string.tool_observe,
            "Read screen elements" to R.string.tool_list_elements,
            "Screenshot" to R.string.tool_screenshot,
            "Scroll" to R.string.tool_scroll,
            "Swipe" to R.string.tool_swipe,
            "Tap" to R.string.tool_tap,
            "Type text" to R.string.tool_type,
            "Clear field" to R.string.tool_clear,
        )
    }
}
