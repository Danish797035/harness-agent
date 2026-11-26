package com.meenai.mina.task

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PlacePace
import kotlin.random.Random
import com.meenai.mina.core.action.OutwardPacing
import com.meenai.mina.core.cap.phone.OperatingPace
import com.meenai.mina.core.usage.RunUsageTally
import com.meenai.mina.core.shell.AccessibilityRepair
import com.meenai.mina.core.shell.HelperRestart
import com.meenai.mina.ui.StatusCopy
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.cap.sandbox.AndroidSandbox
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.ui.image.GeneratedImageUi
import com.meenai.mina.image.ImageStorageManager
import com.meenai.mina.core.image.ImageIntentRouter
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.meenai.mina.core.agent.AppLaunchShortcut
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.meenai.mina.BuildConfig
import com.meenai.mina.cap.phone.a11y.AndroidScreen
import com.meenai.mina.cap.phone.a11y.AppCopyReader
import com.meenai.mina.cap.phone.a11y.LazyPhoneScreen
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.cap.phone.a11y.PhoneUnavailable
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.a11y.launchableApps
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.HarnessApp
import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.SystemPrompt
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.ModelRouter
import com.meenai.mina.core.memory.MemoryExtractor
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ModelSession
import com.meenai.mina.core.skills.SkillDrafter
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.memory.MemoryRuntime
import com.meenai.mina.settings.MemorySwitches
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.replay.RouteMatcher
import com.meenai.mina.core.replay.RouteRecorder
import com.meenai.mina.replay.FileRouteStore
import com.meenai.mina.core.agent.JudgedOutcome
import com.meenai.mina.core.agent.LearnGate
import com.meenai.mina.core.agent.OutcomeJudge
import com.meenai.mina.core.agent.StopSummary
import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.ui.ToolLabels
import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.RunProgress
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.run.RunProvenance
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.child.ChildAgentRunner
import com.meenai.mina.core.run.RunInbox
import com.meenai.mina.core.run.RunInput
import com.meenai.mina.core.run.InputReceipt
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.knowledge.KnowledgeStore
import com.meenai.mina.core.knowledge.AppKnowledgeDrafter
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.resource.ResourceScope
import com.meenai.mina.core.tool.HandBack
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.cap.phone.display.LazyAgentDisplay
import com.meenai.mina.record.RunHandle
import com.meenai.mina.record.RunStore
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.RunApproval
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.takeover.HandoverActivity
import com.meenai.mina.takeover.HandoverActivityCounter
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.takeover.TakeoverPort
import com.meenai.mina.takeover.stoppedAt
import com.meenai.mina.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import com.meenai.mina.core.knowledge.ChannelFacts
import androidx.webkit.WebViewCompat
import com.meenai.mina.cap.web.LazyWebPort
import com.meenai.mina.cap.web.WebHandover
import com.meenai.mina.cap.web.WebSites
import com.meenai.mina.cap.web.WebStack
import com.meenai.mina.cap.web.KeptOrigin
import com.meenai.mina.cap.web.WebTab
import com.meenai.mina.browser.PageLoans
import com.meenai.mina.browser.UserBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.assembly.legacyNames
import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.cap.web.WEB

/**
 * All statuses of a task: virtual screen, loop, level, takeover, mute, monitoring. [TaskService] only
 * cares about the service life cycle, intent distribution and notification. This task's own stream is
 * only synchronously written to the service's external stream when it is a "displayed" task
 * ([service.taskState.shownRun]); the writing of other tasks (in parallel), or the old task replaced by
 * the new task only falls on its own part, and will not overwrite the one being displayed. [scope] is
 * the scope of the service: the bypass coroutine is hung below it and is not canceled along with the
 * task coroutine. It is canceled by [execute] at the end. [slot] determines its notification number and
 * button request code; when [parallel] is true (switch parallelTasks), check whether other tasks are in
 * use before opening the App.
 */
class TaskRun(
    val conversationId: String,
    val goal: String,
    private val service: TaskService,
    private val scope: CoroutineScope,
    val slot: Int = 0,
    val parallel: Boolean = false,
) {
    internal var provenance = RunProvenance()
    internal var triggerWindowKey: String? = null
    internal var triggerActionGoal: String? = null
    internal var triggerThreadId: String? = null
    internal var mcpExternalId: String? = null
    internal val conditionCheck get() = triggerActionGoal != null
    @Volatile private var children: ChildAgentRunner? = null
    /** Your own copy; when it is displayed, it is written to the service's external copy simultaneously. [show] is copied in full. */
    /** The app provides model sources, evaluation hooks, and screen screen switches. */
    private val env = TaskEnvironment.of(service)
    /** The external conversation number of this dialogue: all the models built in this run (main, lightweight, picture viewing, and bypasses such as verification, intention judgment, stop explanation, etc.) use this. */
    private val session = ModelSession.of(conversationId)
    /** The mobile phone services provided by the App (shared shell connection, "go to settings" prompt), memory, experience route, and recovery after abnormal exit. */
    private val phoneServices = PhoneServices.of(service)
    private val memoryRuntime = MemoryRuntime.of(service)
    private val routeStore = FileRouteStore.shared(service)
    private val recovery = TaskRecovery.of(service)
    /** The measured model requests (including bypass calls) in this run; the interface reads a snapshot of it in real time. */
    private val usageTally = com.meenai.mina.core.usage.RunUsageTally()

    private inner class Mirrored<T>(initial: T, private val shown: MutableStateFlow<T>) {
        private val own = MutableStateFlow(initial)
        val flow: StateFlow<T> = own.asStateFlow()
        var value: T
            get() = own.value
            set(v) {
                synchronized(service.taskState.shownLock) {
                    own.value = v
                    if (service.taskState.shownRun === this@TaskRun) shown.value = v
                }
            }
        fun copyOut() { shown.value = own.value }
    }

    /** Mute and other recovery records are separated according to it, and several tasks are recorded separately. */
    val key: String = UUID.randomUUID().toString()
    private val inbox = RunInbox()

    private val _runId = Mirrored<String?>(null, service.taskState._runId)
    private val _sharedFiles = Mirrored<List<SharedFile>>(emptyList(), service.taskState._sharedFiles)
    private val _generatedImages = Mirrored<List<GeneratedImageUi>>(emptyList(), service.taskState._generatedImages)
    private val _state = Mirrored<TaskState>(TaskState.Running(0, TaskService.PREPARING), service.taskState._state)
    private val _activeModel = Mirrored<String?>(null, service.taskState._activeModel)
    /** The same status carries the conversation number and is written together with [_state]: the interface will only collect the status of that conversation on the screen. */
    private val _tagged = Mirrored<Pair<String, TaskState>?>(conversationId to _state.value, service.taskState._shownTask)
    private val _takeoverStage = Mirrored(Takeover.Stage.None, service.taskState._takeoverStage)
    private val _frames = Mirrored<Bitmap?>(null, service.taskState._frames)
    private val _activities = Mirrored<List<RunEvent.Activity>>(emptyList(), service.taskState._activities)
    /** The activity only appends; the read-only view of the first n items is published, and the existing ones are not copied for each new item. */
    private val activityLog = ArrayList<RunEvent.Activity>()
    private fun logView(n: Int): List<RunEvent.Activity> = object : AbstractList<RunEvent.Activity>() {
        override val size = n
        override fun get(index: Int): RunEvent.Activity = synchronized(activityLog) { activityLog[index] }
    }
    private val _steers = Mirrored<List<SteerNote>>(emptyList(), service.taskState._steers)
    private val _displayId = Mirrored(-1, env.agentDisplayId)
    /** The App has been opened on the virtual screen: Picture-in-Picture appears accordingly (only a blank screen is created, and there is no picture when the App has not been opened). */
    private val _appShown = Mirrored(false, env.agentAppShown)
    /** Using the built-in browser: picture-in-picture changes to display the web page. */
    private val _webOpen = Mirrored(false, env.webScreenOpen)

    val state: StateFlow<TaskState> = _state.flow
    val activeModel: StateFlow<String?> = _activeModel.flow
    val runId: StateFlow<String?> = _runId.flow
    /** The file exported by this task; saved into the conversation with this round at the end. */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.flow
    val generatedImages: StateFlow<List<GeneratedImageUi>> = _generatedImages.flow
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.flow
    val frames: StateFlow<Bitmap?> = _frames.flow
    /** The activities from this task to this moment (actually done things); the cards of the suspended capsule are taken according to the task, not just the one on the display. */
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.flow
    /** The virtual screen number of this task; the screen is created when system commands, event tools, or the first time you open the app are used. It is -1 before and after it is released. */
    val displayId: StateFlow<Int> = _displayId.flow
    /** The built-in browser is being used at the moment (the interface of the tool is a web page); it is useless and will be false after the task is completed. */
    val webOpen: StateFlow<Boolean> = _webOpen.flow
    /** The words inserted during this task and their whereabouts; the dialogue cards of the suspended capsule are obtained according to the task. */
    val steers: StateFlow<List<SteerNote>> = _steers.flow
    // Write the one with the conversation number first: the number read when receiving the status
    // without a number is already for this task (the interface will not connect the status of other
    // conversations to the screen accordingly)
    private val mirrored = listOf(_runId, _sharedFiles, _generatedImages, _tagged, _state, _activeModel, _takeoverStage, _frames, _displayId, _appShown, _webOpen, _activities, _steers)

    private fun setState(s: TaskState) = synchronized(service.taskState.shownLock) {
        _tagged.value = conversationId to s
        _state.value = s
    }

    /** Let the interface display this task: become the displayed task, and copy its own items to the external flow of the service. */
    fun show() = synchronized(service.taskState.shownLock) {
        service.taskState.shownRun = this
        mirrored.forEach { it.copyOut() }
    }

    /** The coroutine running [execute] is filled in after the service is started. */
    @Volatile var job: Job? = null
        internal set(v) {
            field = v
            if (cancelRequested) v?.cancel()
        }
    @Volatile private var cancelRequested = false
    /** The user clicks stop: the interruptions that have not yet been handed over to the model are invalidated and will not be regarded as the next thing. */
    @Volatile private var stopPressed = false
    @Volatile private var loop: AgentLoop? = null
    val activeAsk: Delivery.Ask? get() = loop?.activeAsk
    @Volatile private var gate: TaskGate? = null
    @Volatile var takeover: Takeover? = null
        private set
    @Volatile var muteFailedFor: String? = null
        private set
    @Volatile private var awake: KeepAwake? = null
    /** The shell process is gone, and the virtual screen and the App on it are gone together; the task cannot be continued. */
    @Volatile var shellDied = false
        internal set
    /** Release and closing are mutually exclusive; no state will be written after [ended], and late release cannot overwrite the final state or retake the lock. */
    private val publishLock = Any()
    private var ended = false

    fun cancel() {
        stopPressed = true
        children?.cancelAll(RunId(key))
        val running = loop
        val t = takeover
        when {
            t != null -> scope.launch { t.cancel() }
            running != null -> inbox.cancel()
            else -> {
                // Cancellation when the coroutine has just been released from the team and has not yet
                // been hung up: Cancel immediately when hung up
                cancelRequested = true
                job?.cancel()
            }
        }
    }

    fun resume() {
        val g = gate ?: return
        val wasOnPage = g.userOnPagePending
        g.resume()
        // The kind of pause that the user operates on the page: the loop may not have reached the level
        // yet (still thinking about it), and it will be redisplayed according to its current state, and
        // it will no longer say pause.
        if (wasOnPage) loop?.state?.value?.takeIf { it is TaskState.Running }?.let(::publish)
    }

    /** The user presses pause: after the step of waiting for the model to be completed, it stops before the next action of changing the screen; pause is immediately displayed on the interface. */
    fun pause() {
        val g = gate ?: return
        g.pause()
        loop?.state?.value?.takeIf { it is TaskState.Running }?.let(::publish)
    }
    fun takeOver(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handOver(waitKey) } } }
    fun handBack(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handBack(waitKey) } } }
    fun finishedMyself(waitKey: Int?) { takeover?.let { t -> scope.launch { t.finishedMyself(waitKey) } } }
    fun skip(waitKey: Int?) { takeover?.let { t -> scope.launch { t.skip(waitKey) } } }
    fun putAside(waitKey: Int?) { takeover?.let { t -> scope.launch { t.putAside(waitKey) } } }

    /** When answering a question; when it is no longer waiting (answer after timeout or has already been answered), [tooLate] is called to tell the user that this answer is useless. */
    fun answer(text: String, tooLate: () -> Unit) {
        if (loop == null) return
        if (_state.value is TaskState.WaitingInput) scope.launch {
            if (inbox.send(RunInput.Answer(text)) != InputReceipt.Accepted) tooLate()
        }
        else tooLate()
    }

    /**
     * New original words of the same conversation while the task is in progress will be handed over to
     * the next model step; the preparation phase will also be queued in Inbox first. [messageId] is the
     * number given to this sentence by the interface, and the interface marks its destination
     * accordingly (see [SteerNote]).
     */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) {
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value + SteerNote(messageId, text, SteerState.Pending)
            // After accepting it in the inbox, the user will be informed immediately: The request for
            // the model at this moment has been withdrawn, and the next step will be with this sentence
            // in mind (change it to added when it is handed over to the model)
            if (inbox.steer(messageId, text) == InputReceipt.Accepted) markSteers(setOf(messageId), SteerState.Received, from = SteerState.Pending)
            else {
                if (stopPressed) markSteers(setOf(messageId), SteerState.Cancelled)
                else {
                    markSteers(setOf(messageId), SteerState.Next)
                    service.deferMessage(conversationId, text)
                }
            }
        }
    }

    /** Remote append only belongs to the current task; it is mutually exclusive with status publishing, and the inbox is atomically checked to see whether it has ended/cancelled. */
    fun steerRemote(text: String): String? = synchronized(publishLock) delivery@{
        if (ended || stopPressed || mcpExternalId == null || _state.value !is TaskState.Running) return@delivery null
        if (loop?.state?.value?.let { it !is TaskState.Running } == true) return@delivery null
        val id = UUID.randomUUID().toString()
        synchronized(service.taskState.shownLock) {
            // Remember the message first, and then wake up the model; consumption events may occur
            // immediately, and you cannot change Joined back to Received.
            _steers.value = _steers.value + SteerNote(id, text, SteerState.Received, remote = true)
            if (inbox.steerCurrent(id, text) != InputReceipt.Accepted) {
                _steers.value = _steers.value.filterNot { it.id == id }
                return@delivery null
            }
            id
        }
    }

    /** Undo the sentence that was inserted on the mobile phone and the model has not been taken away; the sentence that has been taken away, ended or added by the computer (inbox rejection) returns false, and the destination remains unchanged. */
    fun withdrawSteer(messageId: String): Boolean = synchronized(service.taskState.shownLock) {
        if (!inbox.withdrawSteer(messageId)) return@synchronized false
        markSteers(setOf(messageId), SteerState.Withdrawn)
        true
    }

    /** Chatting on the mobile phone can be transferred to the next task; remote chatting can only be used for the current task, and any unconsumed items will be invalidated, and the source and permissions will not be changed. */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed) {
        val left = inbox.handoffUnconsumed()
        val (discard, next) = left.partition { cancelled || it.currentTaskOnly }
        markSteers(discard.map { it.messageId }.toSet(), SteerState.Cancelled)
        markSteers(next.map { it.messageId }.toSet(), SteerState.Next)
        next.forEach { service.deferMessage(conversationId, it.text) }
    }

    /** Interruptions that have been given this task are saved into the dialogue with this round. */
    /** The ability groups (mobile phone, web page, sandbox) that were really used in this mission will be recorded in this round, and the next round will start in advance. */
    @Volatile var usedGroups: List<String> = emptyList()
        private set

    fun joinedSteers(): List<String> = joined().map { it.text }

    /** One-to-one correspondence with [joinedSteers]: the reply that is interrupted by each interruption is an empty string. */
    fun joinedInterrupted(): List<String> = joined().map { it.interrupted.orEmpty() }

    /** Corresponds one-to-one with [joinedSteers]: each interjection is given to the reply that has been finished before the model, and the missing part is an empty string. */
    fun joinedFinished(): List<String> = joined().map { it.finished.orEmpty() }

    private fun joined() = _steers.value.filter { it.state == SteerState.Joined }

    /** The reply to which the interjection was removed will be attached to the first sentence of this batch of interjections when it is handed over to the model. */
    private val steerJoin = SteerJoin()

    private fun joinSteer(id: String) {
        synchronized(service.taskState.shownLock) { _steers.value = steerJoin.joined(_steers.value, id) }
    }

    /** Change the sentences [ids] to [to]; when giving [from], only change the ones that are still in that state (accepting and handing over the model may be reversed, and will not be changed back). */
    private fun markSteers(ids: Set<String>, to: SteerState, from: SteerState? = null) {
        if (ids.isEmpty()) return
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value.map { if (it.id in ids && (from == null || it.state == from)) it.copy(state = to) else it }
        }
    }

    /** The interface in the running event should be the same: the actual thing done, and the intervention has been handed over to the model. */
    private val liveEvents = object : RunSink {
        override fun event(e: RunEvent) {
            when {
                e is RunEvent.Activity -> synchronized(service.taskState.shownLock) {
                    val n = synchronized(activityLog) { activityLog += e; activityLog.size }
                    _activities.value = logView(n)
                }
                e is RunEvent.State && e.name == "reply_interrupted" -> steerJoin.interrupted(e.detail)
                e is RunEvent.State && e.name == "reply_finished_before_steer" -> steerJoin.finished(e.detail)
                e is RunEvent.State && e.name == "steer_received" -> joinSteer(e.detail)
            }
        }
    }

    /** The notification and card buttons all carry the declaration [key] they belong to; clicking on old notifications and old cards that have been replaced does not count. */
    fun confirm(choice: ConfirmChoice, key: Int?) {
        val running = loop
        val waiting = _state.value as? TaskState.WaitingConfirmation
        if (running != null && waiting != null && key == ConfirmationNotifier.requestKey(waiting)) {
            scope.launch { inbox.send(RunInput.Confirm(choice)) }
        }
    }

    /**
     * The final state of the task: [execute] is written after the completion, without [publish].
     * Regardless of whether it is you who is displayed, you should first record this run number under
     * your own conversation name ([TaskService.endedRunIds]). When the interface receives the end
     * status, press the dialogue button to retrieve it; when it is being displayed, also write
     * [TaskService.endedRunId].
     */
    fun end(end: TaskState) = synchronized(service.taskState.shownLock) {
        service.taskState._endedRunIds.value = service.taskState._endedRunIds.value + (conversationId to _runId.value)
        if (service.taskState.shownRun === this) service.taskState._endedRunId.value = _runId.value
        setState(end)
    }

    /** Queuing when the parallel upper limit is exceeded; [dequeue] when released and started to run. */
    fun queue(position: Int) { setState(TaskState.Held(GateReason.queued(trPlural(R.plurals.queue_ahead, position)))) }
    fun dequeue() { setState(TaskState.Running(0, TaskService.PREPARING)) }

    /** The sandbox of this task: one copy per task (the export amount is calculated based on the task), and the workspace belongs to this conversation. */
    private val sandbox by lazy { AndroidSandbox(service, SandboxState.get(service), onShared = ::addShared) }

    /** The sandbox exported a file (share_file or `mina share`). */
    fun addShared(f: SharedFile) = synchronized(service.taskState.shownLock) { _sharedFiles.value = _sharedFiles.value + f }

    fun updateGeneratedImage(img: GeneratedImageUi) = synchronized(service.taskState.shownLock) {
        val list = _generatedImages.value.toMutableList()
        val idx = list.indexOfFirst { it.prompt == img.prompt }
        if (idx >= 0) {
            val existing = list[idx]
            list[idx] = img.copy(
                filePath = img.filePath ?: existing.filePath,
                model = if (img.model.isNotBlank()) img.model else existing.model
            )
        } else {
            list.add(img)
        }
        _generatedImages.value = list
    }

    fun clearTakeoverStage() { _takeoverStage.value = Takeover.Stage.None }

    /** The status to be displayed at this moment; it is empty when it has ended, and the notification will not be redrawn. */
    fun shownState(): TaskState? = synchronized(publishLock) { if (ended) null else _state.value }

    /** Because the model fails and the main model has not passed the test at that store, you are prompted to go to that store to "retest" (see [RetestHint]). When testing the main model, the configuration in the settings is not checked. */
    private fun withRetestHint(end: TaskState): TaskState {
        if (end !is TaskState.Failed || env.modelFactory !== TaskEnvironment.defaultModelFactory) return end
        return RetestHint.apply(end, ProviderStore.get(service).untestedMainProvider()) { tr(R.string.fail_model_untested, it) }
    }

    suspend fun execute(priorTurns: List<Turn>): TaskState {
        // The phone side (built-in assistant, accessibility, virtual screen) is only connected when
        // launching_app for the first time: tasks that only use web pages and sandboxes do not require
        // them
        var connection: com.meenai.mina.cap.phone.shell.ShellBinding? = null
        var phone: PhoneSide? = null
        // Virtual screens, web page displays, etc.: return them immediately after they are built, and
        // release them together at the end; there is no need to "record the variables after they are
        // built", and they will not be missed if they are canceled in the middle.
        val resources = ResourceScope()
        var watcher: DeviceStateWatcher? = null
        var mute: AudioMute? = null
        // When the task under the model is completed, the destination of each App (on the mobile phone
        // port); at the end, hand it over to the built-in assistant
        var afterTask: com.meenai.mina.core.uistate.AfterTaskMarks? = null
        var shellDeath: IBinder.DeathRecipient? = null
        var shellBinder: IBinder? = null
        val traces = (service.application as HarnessApp).detailedTraces
        val traceTicket = traces.ticket()
        var record: RunHandle? = null
        var runStore: RunStore? = null
        // The measured model requests (including bypass calls) in this run will be recorded in the run
        // record at the end.
        val tally = usageTally
        tally.onChange = { scope.launch { publishUsage() } }
        // It will be created only when the URL is opened for the first time; if it has not been created
        // before, there is no need to close it.
        var webStack: WebStack? = null
        // The current interface (mobile phone/web page) depends on the distributor; the source of the
        // picture-in-picture picture is switched with it.
        var dispatcher: ToolDispatcher? = null
        var mcpEvents: AutoCloseable? = null
        var liveFeed: AutoCloseable? = null
        // Inquiry / Autonomy: Take a copy when starting work, and change it when the switch is changed
        // in the developer options; return it at the end
        val approvalControl = (service.application as HarnessApp).approvalControl
        var approval: RunApproval? = null
        // Bypass coroutine during the task (assistant connection status, mute and removal monitoring,
        // status release), canceled when the task ends
        val side = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "task side job failed", e) },
        )
        sideScope = side
        try {
            if (provenance.origin == RunOrigin.Ui &&
                !conditionCheck &&
                env.modelFactory === TaskEnvironment.defaultModelFactory &&
                env.evalHooks.maxSteps == null
            ) {
                val installed = withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) }
                val matched = AppLaunchShortcut.match(goal, installed)
                if (matched != null) {
                    val (pkg, label) = matched
                    val launchIntent = withContext(Dispatchers.IO) {
                        service.packageManager.getLaunchIntentForPackage(pkg)
                    }?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (launchIntent != null) {
                        val startedAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) { service.startActivity(launchIntent) }
                        val doneMsg = "Opened $label."
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val runs = RunStore(service).also { runStore = it }
                                val r = runs.open(goal).also { record = it; _runId.value = it.runId }
                                r.event(RunEvent.TaskStarted(startedAt, goal, "local/package-manager"))
                                r.event(RunEvent.TaskEnded(System.currentTimeMillis(), "succeeded", doneMsg))
                            }.onFailure { Log.w(TAG, "fast app launch run record failed", it) }
                        }
                        return TaskState.Succeeded(doneMsg, verified = true, reviewed = false)
                    }
                }
            }
            val model = env.modelFactory(service, session) ?: return TaskState.Failed(tr(R.string.fail_no_main_model), FailKind.Environment)
            _activeModel.value = model.name
            // The user asks a page on the browser page: Which page is lent to this task as the current
            // web page label (see PageLoans); it can only be borrowed when the page is still open when
            // running.
            val pageLoan = if (provenance.origin == RunOrigin.Ui) withContext(Dispatchers.Main) { UserBrowser.existing()?.claim(conversationId, key) } else null
            val light = env.lightModelFactory(service, session)
            val vision = env.visionModelFactory(service, session)

            val (knowledge, skills) = withContext(Dispatchers.IO) {
                // The user's additions to an App/site are placed in the private directories
                // personal/apps and personal/sites
                val userNotes = KnowledgeStore.loadNotes(File(service.filesDir, "personal"))
                // In the self-study app knowledge, the tool name written before the name was changed to
                // the current one
                val learnedNotes = legacyNames().let { legacy -> memoryRuntime.appKnowledge().notes().mapValues { it.value.copy(text = legacy.rewrite(it.value.text)) } }
                KnowledgeStore(KnowledgeStore.loadBundled(), userNotes, learnedNotes) to GuideCatalog.loadBundled()
            }
            val appAccess = (service.application as HarnessApp).appAccess
            // Application access boundary: The tool and preview screen share one copy (built when
            // connecting to the mobile port below). When the window does not change, the verification
            // result is directly used. The preview does not need to ask the window every frame.
            var accessGuard: com.meenai.mina.core.uistate.AppAccessScreenPort? = null
            suspend fun previewAllowed(): Boolean {
                val guard = accessGuard ?: return false
                return try {
                    guard.checkAppAccess()
                    true
                } catch (e: CancellationException) { throw e }
                catch (_: com.meenai.mina.core.access.AppAccessDenied) { false }
            }
            val metrics = mainScreenMetrics()
            // The rhythm of operation in the App (Settings › While working); evaluation and real machine
            // acceptance are covered by hooks
            val pace = env.evalHooks.operatingPace ?: FeatureFlags.get(service).operatingPace()
            // Whether the assistant is connected; it is considered connected until it is connected to
            // the phone (do not look at it in the web page and sandbox steps)
            val shellUp = MutableStateFlow(true)
            val states = DeviceStateWatcher(service) { id -> phone?.shell?.topTaskId(id) ?: -1 }.also { watcher = it }

            /** Connect the phone: Connect the built-in assistant, wait for accessibility, and prepare the virtual screen (built until the first launch_app); if anything is missing, it will report phone_unavailable. */
            suspend fun connectPhone(): AndroidScreen {
                // The version requirement is for mobile operation only; old systems can still chat, use
                // web pages and sandboxes, and should not be directed to pair with unsupported
                // assistants.
                if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) throw PhoneUnavailable(TaskService.TOO_OLD)
                val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
                val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
                    ?: run {
                        phoneServices.setupPrompt.request()
                        // The assistant that was started using only USB commands stopped: tell the truth
                        // and run the command again, and prevent people from looking for wireless
                        // debugging
                        throw PhoneUnavailable(
                            if (phoneServices.shellControl.restart == HelperRestart.UsbCommand) NO_PHONE_HELPER_USB else NO_PHONE_HELPER,
                        )
                    }
                // The service is stuck at "open but not running" and is automatically restarting. Wait
                // for a while, fix it and continue working on it. Give up and report the error as usual.
                val a11y = AccessibilityRepair.awaitService(ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
                    ?: throw PhoneUnavailable(NO_ACCESSIBILITY)
                recovery.beginRun(shell, key)
                // Only the tasks of using the mobile phone will depend on the life and death of the
                // shell: the shell is gone, and the virtual screen is gone together with the App on it.
                shellDeath = IBinder.DeathRecipient {
                    shellDied = true
                    val running = loop
                    if (running != null) running.cancel() else job?.cancel()
                }.also { shell.asBinder().linkToDeath(it, 0) }
                shellBinder = shell.asBinder()
                side.launch { conn.shell.map { it != null }.collect { shellUp.value = it } }
                val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                    suspend fun create() = resources.acquire(Dispatchers.IO, AgentDisplay::release) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) }
                    try {
                        create()
                    } catch (e: Exception) {
                        if (e is CancellationException || e !is IllegalStateException && e !is RemoteException) throw e
                        if (!parallel || !service.otherRunHasDisplay(this@TaskRun)) throw e
                        // The second screen cannot be built in the shell: there will be no parallelism
                        // during this run; this task is queued at the back and will be built after all
                        // the screens of other tasks have been released.
                        service.taskState.markSecondDisplayFailed()
                        publish(TaskState.Held(GateReason.queued(WAITING_DISPLAY)))
                        while (service.otherRunHasDisplay(this@TaskRun)) delay(DISPLAY_RETRY_MS)
                        create()
                    }.also { d ->
                        _displayId.value = d.displayId
                        side.launch { d.frames.collect { if (dispatcher?.surface != WEB) _frames.value = if (previewAllowed()) it else null } }
                        side.launch { appAccess.state.collect { if (dispatcher?.surface != WEB && !previewAllowed()) _frames.value = null } }
                    }
                }
                val audio = recovery.audioMute(shell, key).also { mute = it }
                val muter = appMuter(shell, audio)
                // Occupy the name in the registration: the share in another user is occupied separately,
                // and the original share is not blocking each other; if the occupied task still uses it,
                // ask the built-in assistant
                fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
                fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
                    runCatching { if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user) }.getOrDefault(-1) >= 0
                }
                val screen = AndroidScreen(
                    a11y, shell, agentDisplay, { recovery.markClipHeld(it) },
                    clipLock = service.clipLock, checkOtherTasks = parallel,
                    reserve = { pkg -> service.reservePackage(pkg, key, stillUsed(pkg, null)) },
                    reserveCopy = { pkg, user -> service.reservePackage(claimKey(pkg, user), key, stillUsed(pkg, user)) },
                    appFree = { pkg, user -> service.packageFree(claimKey(pkg, user), key, stillUsed(pkg, user)) },
                    // The model needs to wait: When waiting, the interface says "Waiting for another
                    // task to finish using an app" (it is still working, it does not need you to deal
                    // with it), and after waiting, put back the original sentence
                    awaitApp = { pkg, user, timeoutMs, free ->
                        waitingForApp(ConfirmationNotifier.appLabel(service, pkg)) { service.awaitPackage(claimKey(pkg, user), key, timeoutMs, free) }
                    },
                    clipSaved = { original, ours -> recovery.saveClip(original, ours) },
                    handedOver = { handoverRef?.handedOver == true },
                    // Type out paragraphs; App knowledge says that apps that limit browsing speed will
                    // make typing slower.
                    typing = { pkg -> pace.typing(PlacePace.of(pkg?.let(skills::appPace))) },
                    // Quiet before starting, and there will be no sound for the first few seconds;
                    // restore if startup is refused.
                    beforeOpen = muter::beforeOpen,
                    notOpened = muter::notOpened,
                    appAccess = appAccess,
                )
                val side0 = PhoneSide(shell, a11y, screen, agentDisplay, muter)
                phone = side0
                watchTarget(side0, states)
                return screen
            }
            val phoneScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) } },
                // Another user's App needs to ask the built-in assistant: the assistant will only ask if
                // it is already connected, and will not be connected for it.
                appCopies = {
                    phoneServices.hub.shell.value?.let { s ->
                        withContext(Dispatchers.IO) { runCatching { AppCopyReader.read(s, launchableApps(service.packageManager, service.packageName)) }.getOrNull() }
                    }.orEmpty()
                }) { connectPhone() }
            val accessedPhone = com.meenai.mina.core.uistate.AppAccessScreenPort(
                phoneScreen, appAccess,
                packages = { phone?.screen?.accessPackages().orEmpty() },
                protectedUi = { appAccess.sensitiveUi }, ownPackage = service.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() },
            ).also { accessGuard = it }
            afterTask = phoneScreen.afterTask
            // After moving back to the virtual screen, wait for the window to appear, and the Agent will
            // read the screen next.
            suspend fun moveTask(taskId: Int, to: Int): Boolean {
                val p = phone ?: return false
                val moved = withContext(Dispatchers.IO) { runCatching { p.shell.moveTask(taskId, to) }.getOrDefault(false) }
                if (moved && to == p.display.current?.displayId) p.screen.awaitContent()
                return moved
            }
            // The app to be moved back was originally on the virtual screen, and the screen must have
            // been built; without a screen, there is nothing to move back.
            suspend fun moveBackToAgent(taskId: Int): Boolean {
                val id = phone?.display?.current?.displayId ?: return false
                return moveTask(taskId, id)
            }
            val taskGate = TaskGate(states.locked, shellUp) {
                val t = phone?.screen?.target?.value
                t != null && t.taskId >= 0 && moveBackToAgent(t.taskId)
            }
            gate = taskGate
            // If the user turns off the always-on light "while working", the wake lock will not be
            // taken.
            awake = if (FeatureFlags.get(service).keepScreenOn()) KeepAwake(service.wakeLock.lease(), side) else null
            // If the record cannot be opened, it will not be recorded and the task will continue to run.
            val sink: RunSink = withContext(Dispatchers.IO) {
                runCatching {
                    val runs = RunStore(service).also { runStore = it }
                    runs.open(goal).also { record = it; _runId.value = it.runId }
                }.onFailure { Log.w(TAG, "run record unavailable, running without it", it) }.getOrNull()
            } ?: RunSink.NONE
            val trace = record?.takeIf { traceTicket != null }?.let { run ->
                resources.acquire(Dispatchers.IO, traces.store::finish) { traces.start(traceTicket, run.runId) }
            }
            // What apps are installed: Read each task once, if you can’t read it, just ignore it; use it
            // as reference material.
            val apps = try { phoneScreen.listApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val speed = FeatureFlags.get(service).speedFlags()
            // Memory and personal skills: the same copy in the process; no memory is given when the
            // master switch is turned off (no retrieval, no injection, no extraction, no memory tool)
            val switches = memoryRuntime.settings().switches.value.let { sw ->
                sw.copy(learnSkills = env.evalHooks.learnSkills ?: sw.learnSkills, autoRemember = env.evalHooks.rememberMemory ?: sw.autoRemember)
            }
            val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
            val profileSnapshot = if (switches.useMemory) withContext(Dispatchers.IO) {
                runCatching { memoryRuntime.profile().snapshot() }.onFailure { Log.w(TAG, "profile unavailable", it) }.getOrDefault(emptyList())
            } else emptyList()
            // What the user said this time: memorized quotations and quotations of skills written
            // according to user requirements are all checked accordingly (only in memory, the task
            // starts to be cleared)
            val utterances = UserUtterances()
            // Personal skills accumulated before the name change: the old tool names inside are replaced
            // with the current ones when loading
            val catalog = GuideCatalog.withPersonal(skills, memoryRuntime.skills(), legacyNames()::rewrite)
            // Built-in browser: Each task has its own background web page display, tag pool and script
            // engine, which are built when the URL is opened for the first time (not built for tasks
            // that only use mobile phones); web tools are not provided when there is no WebView in the
            // system
            val webInstalled = runCatching { WebViewCompat.getCurrentWebViewPackage(service) != null }.getOrDefault(false)
            val web = if (!webInstalled) null else LazyWebPort {
                val stack = resources.acquire(Dispatchers.Main, WebStack::close) { WebStack.create(
                        service, key, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                        // Download and upload the workspace of this conversation: just a folder in the
                        // App's private directory, and does not require the sandbox to be installed.
                        workspace = SandboxDirs(service).let { d -> WebStack.Workspace(d.workspace(conversationId), d.gate, d.tree) },
                    ) }
                webStack = stack
                // The page marked by the model with web_keep_open will be left in the background when
                // the task is completed, and will be recorded under the name of this round (the round
                // will be displayed below in the dialogue, and a sentence will be added if it is closed)
                stack.keepOrigin = { KeptOrigin(conversationId, _runId.value, goal) }
                side.launch {
                    // In the web interface, picture-in-picture displays the screen displayed on the web
                    // page; when changing the interface, first clear the previous screen; how to display
                    // it, see WebPreview
                    var last: String? = null
                    while (true) {
                        delay(WEB_FRAME_MS)
                        val surface = dispatcher?.surface
                        if (surface != last) {
                            _frames.value = null
                            last = surface
                        }
                        // When the current tab is the borrowed page, it is right in front of the user,
                        // and the screen is not displayed just like during takeover.
                        val show = WebPreview.of(onWeb = surface == WEB, hasTab = stack.hasVisibleTab, handedOver = stack.handedOver || stack.onBorrowedTab)
                        when (show) {
                            WebPreview.Frames -> withContext(Dispatchers.IO) { stack.host.latestFrame() }?.let { _frames.value = it }
                            WebPreview.Hidden, WebPreview.NoPage -> _frames.value = null
                            WebPreview.Off -> Unit
                        }
                        _webOpen.value = show.open
                    }
                }
                stack.screen
            }
            if (conditionCheck && !webInstalled) return TaskState.Failed(tr(R.string.fail_no_web), FailKind.Environment)
            // Borrowed page: Create a web page at the beginning and borrow it as the current label; it
            // will be paused when the user touches it (it does not count when operating on the mobile
            // phone screen at the moment)
            if (pageLoan != null && web != null && web.prepare()) webStack?.let { stack ->
                withContext(Dispatchers.Main) {
                    stack.borrowedTouches = { pageLoan.touches }
                    UserBrowser.existing()?.loans?.bind(pageLoan, object : PageLoans.Borrower<WebTab> {
                        override fun take(tab: WebTab) = stack.borrow(tab)
                        override fun giveBack(tab: WebTab) = stack.giveBack(tab)
                        override fun swap(prev: WebTab, next: WebTab) = stack.swapBorrowed(prev, next)
                        override fun adopt(tab: WebTab) = stack.adopt(tab)
                        override fun touched() {
                            if (stack.onBorrowedTab && !(phoneScreen.acquired && dispatcher?.surface != WEB)) userOnPage()
                        }
                    })
                }
            }
            // The screen sent by the user in this conversation (saved in the workspace along with the
            // conversation)
            val userScreens = (service.application as HarnessApp).userScreens.source(conversationId)
            val runSpec = RunSpec(RunId(key), goal, conversationId,
                priorTurns,
                provenance, if (conditionCheck) setOf("web", "sandbox") else null, readOnly = conditionCheck)
            val runApproval = approvalControl.start(remote = provenance.origin == RunOrigin.Mcp).also { approval = it }
            val childRunner = if (provenance.origin == RunOrigin.Ui && web != null) ChildAgentRunner(
                create = { childSpec ->
                    val childTools = toolDispatcher(accessedPhone, model.capabilities.vision,
                        web = web, webPacing = { pace.webPacing() }, webPlacePace = { PlacePace.of(catalog.sitePace(it)) },
                        webIdleScroll = { IdleScroll(Random.Default) },
                        appPacing = { pace.appPacing() }, appPace = { PlacePace.of(catalog.appPace(it)) }, appIdleScroll = { IdleScroll(Random.Default) },
                        sandbox = sandbox, workspace = { conversationId }, runSpec = childSpec,
                        conversationId = conversationId, inlineScreen = speed.inlineScreen,
                        adaptiveVision = speed.adaptiveVision,
                        visionCheck = { model.capabilities.vision })
                    AgentLoop(model, childTools, SystemPrompt.identity(), maxSteps = childSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                        // The subtask follows the inquiry/autonomous; it also passes the level when
                        // asked, and the step in the subtask that costs money will not be done.
                        confirmations = runApproval.gate(), approvals = runApproval, autonomous = runApproval.startedAutonomous,
                        reviewFinalAnswer = false, flags = speed, outwardPacing = OutwardPacing.human())
                },
                store = (service.application as HarnessApp).childRuns,
                ownerAlive = { job?.isActive == true },
            ).also { children = it } else null
            val tools = toolDispatcher(accessedPhone, model.capabilities.vision, catalog, recordFrames = true, vision = vision?.let { VisionService(it, BitmapImageOps) },
                // Only when Mina is allowed to change herself in the settings, settings_list and
                // settings_set are sent to the model.
                selfConfig = if (FeatureFlags.get(service).selfConfig()) {
                    com.meenai.mina.settings.SelfConfigImpl(FeatureFlags.get(service))
                } else null,
                stableRefs = speed.inlineScreen, inlineScreen = speed.inlineScreen,
                adaptiveVision = speed.adaptiveVision,
                deepLinks = speed.deepLinks, memory = memory, profile = if (memory != null) memoryRuntime.profile() else null,
                utterances = utterances, conversationId = conversationId,
                web = web,
                // View the same website page by page: make up the pause before changing the page, and
                // open it in seconds (fast/standard/like the operating rhythm in the settings)
                webPacing = { pace.webPacing() },
                // When staying, gently scroll the page a few times like a human would
                webIdleScroll = { IdleScroll(Random.Default) },
                // Step by step operation in the app: pause for a while between actions, apps that limit
                // browsing speed will pause longer, and there is an upper limit on the number of screens
                // (according to the operating rhythm in the settings)
                appPacing = { pace.appPacing() },
                appIdleScroll = { IdleScroll(Random.Default) },
                // Upload only recognizes ordinary files in the workspace of this conversation (the same
                // set of path rules as the sandbox); the skill directory and outside the workspace are
                // all out of bounds
                workspacePath = web?.let { { p: String -> sandbox.uploadCheck(conversationId, p) } },
                // Workspace = Conversation: Questions in the same conversation will continue to use the
                // files of the previous round, and different conversations will not be visible to each
                // other.
                sandbox = sandbox, workspace = { conversationId },
                onSandboxCommand = { sandboxNote(StatusCopy.sandboxCommand(it, sandboxOnline())) },
                onSandboxProgress = { sandboxNote(StatusCopy.sandboxProgress(it, sandboxOnline())) },
                onSandboxPreparing = { sandboxNote(StatusCopy.RUNTIME_PREPARING) },
                onSandboxScript = { sandboxNote(StatusCopy.sandboxScript(sandboxOnline())) },
                imageOps = BitmapImageOps, runSpec = runSpec,
                userScreens = userScreens,
                scheduleTrigger = if (provenance.origin == RunOrigin.Ui) { trigger -> TriggerRuntime.schedule(service, trigger) } else null,
                scheduleAdmin = if (provenance.origin == RunOrigin.Ui) LocalScheduleAdmin(service) else null,
                childHost = childRunner,
                // Write or rewrite personal skills when requested by the user (put them in the same
                // place as those collected by self-study, and you can see them immediately in the
                // settings)
                personalSkills = memoryRuntime.skills(),
                // The running records of previous rounds of this conversation (read-only; history_read
                // only recognizes the rounds of this conversation in runSpec)
                runRecords = RunStore(service).reader(),
                imageService = (service.application as HarnessApp).imageService,
                onImageProgress = { isGenerating, prompt, modelName ->
                    val cleanPrompt = if (prompt.contains("previous failure", ignoreCase = true) || prompt.contains("HTTP 413", ignoreCase = true) || prompt.contains("504 timeout", ignoreCase = true)) {
                        _generatedImages.flow.value.lastOrNull()?.prompt?.takeIf { it.isNotBlank() } ?: prompt
                    } else {
                        prompt
                    }
                    val existingPath = _generatedImages.flow.value.find { it.prompt == cleanPrompt }?.filePath
                        ?: _generatedImages.flow.value.lastOrNull()?.filePath
                    updateGeneratedImage(GeneratedImageUi(
                        filePath = existingPath,
                        isGenerating = isGenerating,
                        prompt = cleanPrompt,
                        model = modelName
                    ))
                },
                onImageSaved = { bytes, prompt, modelName ->
                    val cleanPrompt = if (prompt.contains("previous failure", ignoreCase = true) || prompt.contains("HTTP 413", ignoreCase = true) || prompt.contains("504 timeout", ignoreCase = true)) {
                        _generatedImages.flow.value.lastOrNull()?.prompt?.takeIf { it.isNotBlank() } ?: prompt
                    } else {
                        prompt
                    }
                    val file = ImageStorageManager.saveToPrivateCache(service, bytes)
                    updateGeneratedImage(GeneratedImageUi(
                        filePath = file.absolutePath,
                        isGenerating = false,
                        prompt = cleanPrompt,
                        model = modelName
                    ))
                    file.absolutePath
                },
                visionCheck = { model.capabilities.vision },
                isSensitivePackage = { pkg ->
                    if (pkg.isNullOrBlank()) false
                    else com.meenai.mina.capsule.CapsuleSecurityPolicy.isSensitiveApp(pkg, (service.application as HarnessApp).appAccess.current())
                })
            dispatcher = tools
            val agent = AgentLoop(
                model, tools,
                SystemPrompt.identity(),
                // If the assistant is disconnected or moved, it will only be checked when a mobile phone
                // is used in this step.
                gate = { w ->
                    if (phoneScreen.acquired && dispatcher?.surface != WEB) phone?.screen?.let { awaitUserPick(it, taskGate, w) }
                    taskGate.pass({ phoneScreen.acquired && dispatcher?.surface != WEB }, w)
                }, knowledge = knowledge, sink = sink, trace = trace, installedApps = { apps },
                // Facts to use when choosing channels: Which apps are installed, which websites have
                // been logged into Mina, and whether there is a built-in browser
                channelFacts = {
                    val registry = WebSites.registry(service)
                    ChannelFacts(
                        installed = apps.map { it.first }.toSet(),
                        loggedInSites = withContext(Dispatchers.IO) { registry.list().filter { it.loggedInByUser }.map { it.host }.toSet() },
                        webAvailable = web != null,
                        challengedSites = withContext(Dispatchers.IO) { registry.challengedSites() },
                    )
                },
                maxSteps = env.evalHooks.maxSteps ?: AgentLoop.UNLIMITED_STEPS,
                reviewFinalAnswer = false,
                inputTimeoutMs = TaskService.INPUT_TIMEOUT_MS,
                // Inquiry / Autonomy: When inquiring, you only have to ask first to spend money (there
                // is a confirmation level and request_confirmation); when you are autonomous, you don’t
                // even ask about payment. Ask when starting work, change to independent in the middle,
                // and no longer ask from the next step; ask autonomously when starting, change to asking
                // and leave it to the next task
                confirmations = runApproval.gate(),
                approvals = runApproval,
                autonomous = runApproval.startedAutonomous,
                confirmTimeoutMs = TaskService.CONFIRM_TIMEOUT_MS,
                selfPackage = service.packageName,
                flags = speed,
                // Only takes effect when lightSteps is turned on and the lightweight model can be
                // adjusted with tools
                router = if (model is FreeKeyRouter) null else light?.let { ModelRouter(model, it) },
                intentJudge = if (provenance.origin == RunOrigin.Ui) light ?: model else null,
                // Route selection is an auxiliary judgment: use a lightweight model, not a main model
                routes = if (speed.replay) RouteMatcher(light ?: model, routeStore, knowledge) else null,
                skills = catalog,
                memory = memory,
                interaction = service.interaction(conversationId),
                profileSnapshot = profileSnapshot,
                streamReplies = true,
                userScreens = userScreens,
                // When contacting and submitting to others on the same website or app: there will be a
                // random period of time between the two attempts.
                outwardPacing = OutwardPacing.human(),
                freeKeyRouter = (service.application as? HarnessApp)?.freeKeyRouter,
            )
            loop = agent
            liveFeed = agent.events.subscribe(liveEvents)
            mcpExternalId?.let { mcpEvents = (service.application as HarnessApp).remoteTasks.attach(it, agent.events) }
            kit = LearnKit(switches, memory, utterances, tools, agent, catalog, knowledge, model, light, runStore, agent.flags.replay, priorTurns)
            suspend fun <T> io(fallback: T, call: () -> T): T =
                withContext(Dispatchers.IO) { runCatching(call).getOrDefault(fallback) }
            // Only subscribe to the accessibility events of the home screen during the takeover period:
            // no one subscribes to them normally, and the service does not copy the events.
            var counter: HandoverActivityCounter? = null
            var counting: Job? = null
            val handover = Takeover(object : TakeoverPort {
                override suspend fun agentTopTask() = phone?.let { p -> p.display.current?.let { d -> io(-1) { p.shell.topTaskId(d.displayId) } } } ?: -1
                override suspend fun targetTask() = phone?.screen?.target?.value?.taskId ?: -1
                override fun targetPackage() = phone?.screen?.target?.value?.packageName
                override suspend fun packageOf(taskId: Int) = phone?.let { p -> io(null) { p.shell.packageOfTask(taskId) } }
                override suspend fun displayOf(taskId: Int) = phone?.let { p -> io(-1) { p.shell.displayOfTask(taskId) } } ?: -1
                override suspend fun moveToMain(taskId: Int) = moveTask(taskId, Display.DEFAULT_DISPLAY)
                override suspend fun moveBack(taskId: Int) = moveBackToAgent(taskId)
                override fun setSound(packageName: String, on: Boolean) {
                    val p = phone ?: return
                    // If the target App is handed over, it will be counted according to the target's
                    // share; otherwise, it will be calculated according to the original share.
                    val user = p.screen.target.value?.takeIf { it.packageName == packageName }?.let { copyUser(it.userId) }
                    if (on) {
                        if (!p.audio.release(packageName, user)) Log.w(TAG, "could not restore sound of $packageName for takeover")
                    } else {
                        p.audio.opened(packageName, user)
                    }
                }
                override fun watchInput(packageName: String) {
                    counting?.cancel()
                    val c = HandoverActivityCounter(packageName).also { counter = it }
                    // Subscribe now: The first action that happens next cannot be missed
                    val a11y = phone?.a11y ?: return
                    counting = side.launch(start = CoroutineStart.UNDISPATCHED) {
                        a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                            c.onEvent(Display.DEFAULT_DISPLAY, e.packageName, e.eventType, e.className, e.eventTime, e.windowId to e.source?.hashCode())
                        }
                    }
                }
                override suspend fun handoverActivity(): HandoverActivity? {
                    val c = counter ?: return null
                    val pm = service.packageManager
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull()
                    return HandoverActivity(c.inputCount, stoppedAt(phone?.a11y?.let { mainWindowTitle(it, c.packageName) }, label, c.lastActivity))
                }
                override fun stopWatching() {
                    counting?.cancel()
                    counting = null
                    counter = null
                }
                override suspend fun resume(result: HandBack) { inbox.send(RunInput.HandBack(result)) }
                override fun cancel() { inbox.cancel() }

                // Web interface: take the current tab (the same page) to the takeover interface of the
                // home screen; the mobile interface returns null and moves the task
                override suspend fun showWeb(reason: String, key: Int): Boolean? {
                    // If you have not opened the web page, you will not be on the web interface.
                    val w = webStack ?: return null
                    if (tools.surface != WEB) return null
                    val message = takeoverMessage(_state.value)
                    return withContext(Dispatchers.Main) {
                        w.showForTakeover(reason, message, key, WebHandover.taskActions(service, key, this@TaskRun.key)) { takeover?.waitKey }
                    }
                }

                override fun webBusy() = WebHandover.loginOpen

                override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? =
                    webStack?.let { w -> withContext(Dispatchers.Main) { w.returnFromTakeover(loggedIn) } }
            }, side, queue = service.takeoverQueue)
            takeover = handover
            handoverRef = handover

            side.launch {
                agent.state.collect {
                    if (it == TaskState.Idle) return@collect
                    if (it is TaskState.Running && it.preview != null && (gate?.movedAwayPending == true || gate?.userOnPagePending == true)) return@collect
                    val previous = _state.value as? TaskState.Running
                    if (it is TaskState.Running && previous != null && previous.preview != it.preview &&
                        previous.copy(preview = it.preview) == it) {
                        // Text increments only refresh the chat; notifications, takeovers, and device
                        // wakeups do not need to be rerun for every token.
                        synchronized(publishLock) { if (!ended) setState(it) }
                        return@collect
                    }
                    handover.onState(it)
                    publish(it)
                    env.evalHooks.answerFor(it)?.let { answer -> agent.answerInput(answer) }
                    env.evalHooks.decisionFor(it)?.let { approved -> agent.answerConfirmation(approved) }
                }
            }
            side.launch {
                agent.activeModelName.collect { name ->
                    if (!name.isNullOrBlank()) _activeModel.value = name
                }
            }
            // When the acceptance of other tasks enters and exits the queue, the acceptance notification
            // of this task must be changed accordingly (it will be queued to the front, or there will be
            // one more at the end)
            if (parallel) side.launch { service.takeoverQueue.changes.collect { service.refreshNotifications(this@TaskRun) } }
            side.launch {
                combine(handover.stage, handover.problem) { stage, _ -> stage }.collect {
                    _takeoverStage.value = it
                    service.refreshNotifications(this@TaskRun)
                }
            }
            if (ImageIntentRouter.isVideoRequest(goal)) {
                return TaskState.Succeeded(ImageIntentRouter.VIDEO_NOT_SUPPORTED_MSG, verified = false, reviewed = false)
            }
            val runResult = withContext(tally) { agent.run(runSpec, inbox) }.also { usedGroups = agent.usedGroups.toList() }.let(::withRetestHint)
            return if (runResult is TaskState.Failed && _generatedImages.flow.value.any { !it.filePath.isNullOrBlank() }) {
                TaskState.Succeeded("", verified = false, reviewed = false)
            } else {
                runResult
            }
        } finally {
            handoffUnconsumed()
            side.cancel()
            _activeModel.value = null
            _frames.value = null
            _webOpen.value = false
            synchronized(publishLock) { ended = true }
            usageTally.onChange = null
            awake?.close()
            awake = null
            shellDeath?.let { d -> runCatching { shellBinder?.unlinkToDeath(d, 0) } }
            loop = null
            children = null
            mcpEvents?.close()
            liveFeed?.close()
            approval?.let { approvalControl.end(it) }
            gate = null
            takeover = null
            handoverRef = null
            sideScope = null
            // Return the borrowed page to the browser page (before closing the web page)
            withContext(Dispatchers.Main + NonCancellable) { runCatching { UserBrowser.existing()?.loans?.end(key) } }
            withContext(Dispatchers.IO + NonCancellable) {
                // If there is an error in the previous step, the screen and web page display will also
                // be displayed (in the reverse order of creation)
                try {
                    // First decide where to keep the apps on the Mina screen (move the remaining apps to
                    // the back of the home screen and turn off the ones that are making sounds), release
                    // the screen, and then restore to mute: the remaining ones will not suddenly make
                    // sounds.
                    val apps = phone?.takeIf { !shellDied }?.let { p ->
                        p.display.current?.let { d -> AppsAfterTaskEnd.finish(p.shell, d.displayId, afterTask) }
                    }
                    record?.let { r ->
                        apps?.let { r.event(RunEvent.State(System.currentTimeMillis(), "apps_after_task", it.detail)) }
                        r.event(tally.event(System.currentTimeMillis()))
                        r.close()
                        if (BuildConfig.DEBUG) runStore?.mirrorToExternal(r.runId)
                    }
                    // When recovering an error, you must also remove the "running" registration,
                    // otherwise this record will never be restored.
                    try { mute?.restoreAll() } finally { recovery.endRun(key) }
                    watcher?.close()
                } finally {
                    resources.close()
                }
            }
            // Clear the screen after it is released and before the end of the external report: the
            // interface sees that there is no screen when the task ends.
            _displayId.value = -1
            _appShown.value = false
            if (connection != null) phoneServices.sharedShell.release()
        }
    }

    /** Mobile phone side: Only tasks that have been used on mobile phones are available. */
    private class PhoneSide(
        val shell: com.meenai.mina.shell.IHarnessShell, val a11y: ScreenAccessibility, val screen: AndroidScreen,
        val display: LazyAgentDisplay, val audio: AppMuter,
    )

    /**
     * The mute rules for this task (see [AppMuter]): Mina herself will not mute; system packages that do
     * not have a desktop entrance will not mute (system interfaces, selection boxes, etc.); things
     * visible to the user on the home screen will not mute; notes that are not muted, the task progress
     * prompt may sound.
     */
    private fun appMuter(shell: com.meenai.mina.shell.IHarnessShell, audio: AudioMute): AppMuter {
        val pm = service.packageManager
        val own = service.packageName
        fun system(pkg: String) = runCatching {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrElse { runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false) }
        return AppMuter(
            audio,
            enabled = { FeatureFlags.get(service).muteTarget() },
            mutable = { pkg -> pkg != own && (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null || !system(pkg)) },
            onMainScreen = { pkg, user -> runCatching { shell.visibleOnMainScreen(pkg, user ?: AppCopyReader.myUser) }.getOrDefault(true) },
            failed = { pkg ->
                muteFailedFor = pkg
                publish(_state.value)
            },
        )
    }

    /** The user to whom the task belongs → Which copy to mute: the original copy (or cannot be read) is null, and the one in the other user copy is that user number. */
    private fun copyUser(userId: Int): Int? = userId.takeIf { it >= 0 && it != AppCopyReader.myUser }

    @Volatile private var handoverRef: Takeover? = null
    @Volatile private var sideScope: CoroutineScope? = null

    /**
     * The system asks the user on the home screen which one to open (see [AndroidScreen.userPick]): stop
     * here, ask the user to select the capsule on the screen, and then automatically continue after
     * selecting the app; the selected App needs to be handled by the user personally and remains on the
     * home screen, and the user is asked to continue after processing. Return directly without waiting.
     */
    private suspend fun awaitUserPick(screen: AndroidScreen, gate: TaskGate, setWaiting: (TaskState?) -> Unit) {
        val pick = screen.userPick.value ?: return
        setWaiting(TaskState.Held(TaskGate.userStep(TaskGate.pickCopy(pick.app))))
        val r = screen.awaitUserPick() as? AndroidScreen.PickResult.NeedsUser ?: return
        gate.pauseForUser(TaskGate.finishCopy(r.app), setWaiting)
        screen.afterUserStep(r)
    }

    /**
     * Mute the target app after changing it (mostly it has been muted before starting, here are the ones
     * that have not been muted), and keep an eye on whether it has been moved to the home screen by the
     * user. When another App pops up on Mina's screen (clicked from the target App and another one is on
     * top), it will be muted when it is first seen; to mute it, press "Package + User" and the other app
     * will be muted alone. What is on the screen during user acceptance belongs to the user and remains
     * unchanged.
     */
    private fun watchTarget(p: PhoneSide, states: DeviceStateWatcher) {
        val side = sideScope ?: return
        side.launch(Dispatchers.IO) {
            p.screen.foregroundApps().collect { t ->
                if (handoverRef?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                // The window becomes very dense: there is at least a while between the two times, and
                // the changes in the middle are synthesized once
                delay(FOREGROUND_GAP_MS)
            }
        }
        side.launch(Dispatchers.IO) {
            p.screen.target.filterNotNull().collectLatest { t ->
                _appShown.value = true
                if (handoverRef?.handedOver != true) p.audio.opened(t.packageName, copyUser(t.userId))
                // If there is a target App, it means launch_app has already built the screen.
                val displayId = p.display.current?.displayId
                if (t.taskId >= 0 && displayId != null) {
                    states.taskMovedAway(displayId, t.taskId).collect {
                        // After the user clicks "I will handle it", the App should be on the home screen
                        if (handoverRef?.handedOver == true) return@collect
                        gate?.onTaskMovedAway()
                        publish(_state.value) // Display is paused immediately, without waiting for the next tool that changes the screen.
                    }
                }
            }
        }
    }

    /**
     * The user touches the page assigned to this task on the browser page: it is paused immediately
     * while working, stopped before the next tool that will change the screen, and the user clicks
     * "Continue" to continue (see [TaskGate.onUserOnPage]). Waiting for the user to answer, taking over,
     * and other states remain unchanged. main thread.
     */
    private fun userOnPage() {
        if (_state.value !is TaskState.Running) return
        val g = gate ?: return
        g.onUserOnPage()
        publish(_state.value)
    }

    /** After the task is completed, you will determine the outcome and learn things you need to use; fill in [execute]. */
    private class LearnKit(
        val switches: MemorySwitches, val memory: MemoryStore?, val utterances: UserUtterances?, val dispatcher: ToolDispatcher,
        val agent: AgentLoop, val catalog: GuideCatalog, val knowledge: KnowledgeStore,
        val model: ChatModel, val light: ChatModel?, val runStore: RunStore?,
        val routesOn: Boolean, val priorTurns: List<Turn>,
    )
    @Volatile private var kit: LearnKit? = null

    /** The text that was being written when stopped (the visible text that the model requested has flowed out); it is taken before the end, and the state is changed to the final state after the end. */
    fun draftText(): String? = (_state.value as? TaskState.Running)?.preview?.takeIf(String::isNotBlank)

    /** Is there anything to explain after stopping: I have done at least one thing, or I have already written something when I stopped. */
    fun hasStopMaterial(draft: String?): Boolean = synchronized(activityLog) { activityLog.isNotEmpty() } || !draft.isNullOrBlank()

    /**
     * After the user stops: Write a few lines to explain what has been done this time (see
     * [StopSummary]), only read the running record, and no longer operate anything. The lightweight
     * model is given priority, and the main model is not used. If there is no model, error, or timeout,
     * just press the record to fight directly. null if there is nothing to explain.
     */
    suspend fun stopSummary(draft: String?): String? {
        val k = kit
        val runId = _runId.value
        val recorded = withContext(Dispatchers.IO) { runId?.let { id -> runCatching { k?.runStore?.events(id) }.getOrNull() } }
        val events = recorded?.takeIf { e -> e.any { it is RunEvent.Activity } } ?: synchronized(activityLog) { activityLog.toList() }
        if (!StopSummary.worthSaying(events, draft)) return null
        // It stops before the model is opened (preparation stage): Press settings to get it now
        val (main, light) = if (k != null) k.model to k.light else withContext(Dispatchers.IO) {
            runCatching { env.modelFactory(service, session) }.getOrNull() to runCatching { env.lightModelFactory(service, session) }.getOrNull()
        }
        val labels = ToolLabels(knownTools())
        return StopSummary(light ?: main, main).summarize(k?.agent?.taskText ?: goal, events, draft, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * After the result is handed over to the user, it is called (without delaying or changing the
     * handed-over answer): the answer is determined according to the user's requirements
     * ([OutcomeJudge], lightweight model is preferred), and recorded in the running record and this
     * round of dialogue; only the route that is completed ([LearnGate]) is recorded, and the replayed
     * route is recorded as successful. If there is no judgment for giving up, failure, or cancellation
     * (the ending is already clear), null will be returned; if the judgment is not successful, it is
     * [RunOutcome.Unknown].
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? {
        val k = kit ?: return null
        if (end !is TaskState.Succeeded) return null
        val judged = OutcomeJudge(k.light ?: k.model, k.model).judge(k.agent.taskText ?: goal, end.answer, k.priorTurns)
        val runId = _runId.value
        if (runId != null) withContext(Dispatchers.IO) {
            k.runStore?.append(runId, judged.event(System.currentTimeMillis()))
            if (BuildConfig.DEBUG) k.runStore?.mirrorToExternal(runId)
            runCatching { conversations.updateTurn(conversationId, runId) { it.copy(result = judged.outcome.wire) } }
                .onFailure { Log.w(TAG, "could not save run outcome", it) }
        }
        k.agent.noteOutcome(judged.outcome)
        // Only those that are completed will be recorded as routes; they will be taken from the running
        // events placed on the disk.
        if (runId != null && LearnGate.shouldSaveRoute(k.routesOn, end, judged.outcome, runId, goal)) k.runStore?.let { s ->
            saveRoute(goal, withContext(Dispatchers.IO) { s.events(runId) }, k.dispatcher.index)
        }
        return judged
    }

    /**
     * Called after the result notification is sent: write down the results of the personal skills used,
     * retrieve memories, draft skills, and the prompts are hung on the result card of this round and
     * saved in the dialogue. It runs in the process-level scope and will not be interrupted when the
     * service exits.
     */
    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) {
        val k = kit ?: return
        val runId = _runId.value
        val learning = RunLearning(
            k.switches, k.memory, memoryRuntime.skills(), k.catalog,
            extractor = k.memory?.let { MemoryExtractor(k.light ?: k.model) },
            drafter = SkillDrafter(k.model),
            appDrafter = AppKnowledgeDrafter(k.light ?: k.model),
            appDrafts = memoryRuntime.appKnowledge(),
            ids = { UUID.randomUUID().toString() },
            autoApprove = env.evalHooks.autoApproveSkillDrafts,
            yieldToForeground = { while (service.hasActiveRuns()) delay(500) },
        )
        val notes = learning.learn(RunAftermath(
            conversationId, runId, k.agent.taskText ?: goal, end, k.utterances.takeIf { k.memory != null }?.all.orEmpty(), k.dispatcher.savedChanges, k.agent.usedMemoryIds,
            k.dispatcher.definitions().map { it.name }.toSet(), k.dispatcher.index,
            events = { withContext(Dispatchers.IO) { runId?.let { k.runStore?.events(it) }.orEmpty() } },
            origin = provenance.origin,
            knowledge = k.knowledge,
            outcome = outcome ?: RunOutcome.Unknown,
        ))
        if (runId == null || notes.memoryNotes.isEmpty() && notes.usedMemory.isEmpty() && notes.skillNotes.isEmpty()) return
        memoryRuntime.publish(notes)
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.updateTurn(conversationId, runId) { it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes) }
            }.onFailure { Log.w(TAG, "could not save memory notes", it) }
        }
    }

    /** Successful runs are recorded as routes; failed runs (should not be recorded, disk writing failed) will not affect the task outcome. */
    private suspend fun saveRoute(goal: String, events: List<RunEvent>, tools: ToolIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val route = RouteRecorder.record(goal, events, System.currentTimeMillis(), tools) ?: return@runCatching
            val versions = route.packages.mapNotNull { p ->
                runCatching { service.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()?.let { p to it }
            }.toMap()
            routeStore.putReplacing(route.copy(appVersions = versions))
        }.onFailure { Log.w(TAG, "route not saved", it) }
    }

    /** The title of the top window of this package on the Home screen; windows are arranged from top to bottom. */
    private fun mainWindowTitle(a11y: ScreenAccessibility, packageName: String): String? = runCatching {
        a11y.windowsOn(Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { w ->
            w.title?.toString()?.takeIf { it.isNotBlank() && w.root?.packageName?.toString() == packageName }
        }
    }.getOrNull()

    /** The instructions for the sandbox step are written on the task card (note for the current step). */
    private fun sandboxNote(text: String) {
        val s = _state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    private fun sandboxOnline() = (sandbox.status() as? SandboxStatus.Ready)?.network == true

    /**
     * During the period of waiting for another task to be released [app]: For external progress, write
     * "Waiting for another task to be completed [app]", it is still Running, not paused, and does not
     * require you to handle it; after waiting (released, unable to wait, stopped), put back the sentence
     * before waiting. Don't change it until you are no longer working (for example, it has stopped and
     * asked you to handle it).
     */
    private suspend fun <T> waitingForApp(app: String, wait: suspend () -> T): T {
        val before = _state.value as? TaskState.Running ?: return wait()
        val waiting = before.copy(progress = RunProgress.WaitingForApp(app))
        publish(waiting)
        try {
            return wait()
        } finally {
            if (_state.value == waiting) publish(before)
        }
    }

    /** The model usage has changed: push the real-time usage to the interface (only while the task is still running). */
    private fun publishUsage() {
        val s = _state.value
        if (s is TaskState.Running) publish(s)
    }

    private fun publish(state: TaskState) {
        // After being moved and before being blocked by the gate, the loop may still be reasoning: it
        // will first display the pause to the outside world. The final state reported by the loop itself
        // will not be sent out: the final state is only written by [end] after remembering the run
        // number, otherwise the interface will receive the end first and the number will not be
        // obtained.
        if (!TaskService.loopMayPublish(state)) return
        val shown = when {
            state is TaskState.Running && gate?.movedAwayPending == true -> TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY))
            // The user operates on the borrowed page: pause is also displayed before reaching the level.
            state is TaskState.Running && gate?.userOnPagePending == true -> TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))
            // The user pressed pause
            state is TaskState.Running && gate?.userPausedPending == true -> TaskState.Held(TaskGate.paused(TaskGate.USER_PAUSED))
            // Real-time usage: every time the model is called and every time the context is written
            // down, it is updated.
            state is TaskState.Running -> {
                val u = usageTally.snapshot()
                if (u.calls == 0 && u.contextTokens == 0) state else state.copy(usage = u)
            }
            else -> state
        }
        synchronized(publishLock) {
            if (ended) return
            setState(shown)
            awake?.onState(state)
        }
        service.refreshNotifications(this)
    }

    @Suppress("DEPRECATION")
    private fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** Instructions at the top of the web page takeover interface: If there is a reason for the takeover, please bring the product, amount, and address when making payment. */
    private fun takeoverMessage(state: TaskState): String? {
        val w = state as? TaskState.WaitingTakeover ?: return null
        return listOfNotNull(
            w.message.takeIf { it.isNotBlank() },
            w.item?.let { tr(R.string.payment_item, it) },
            w.amount?.let { tr(R.string.payment_amount, it) },
            w.address?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }
    }

    private companion object {
        /** The interval between picture-in-picture images in the web interface. */
        const val WEB_FRAME_MS = 500L
        const val SHELL_WAIT_MS = 15_000L
        const val DISPLAY_RETRY_MS = 1_000L
        /** It takes at least this long between rereads of the front-end app on Mina's screen. */
        const val FOREGROUND_GAP_MS = 300L
        val WAITING_DISPLAY: String get() = tr(R.string.task_waiting_display)
        const val A11Y_WAIT_MS = 5_000L
        val NO_PHONE_HELPER: String get() = tr(R.string.task_no_phone_helper)
        val NO_PHONE_HELPER_USB: String get() = tr(R.string.task_no_phone_helper_usb)
        val NO_ACCESSIBILITY: String get() = tr(R.string.task_no_a11y)
        const val TAG = "MinaTask"
    }
}
