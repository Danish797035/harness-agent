package com.meenai.mina.task

import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.R
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.chat.ConversationTitler
import com.meenai.mina.memory.MemoryRuntime
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.Conversation
import com.meenai.mina.core.model.ModelSession
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.trigger.ConditionOutcome
import com.meenai.mina.core.run.ChannelPeer
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.run.DeliveryState
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.SendOutcome
import com.meenai.mina.core.run.ChannelInteraction
import com.meenai.mina.core.run.RunProvenance
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.verify.Verifier
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.takeover.TakeoverActivity
import com.meenai.mina.ui.MainActivity
import com.meenai.mina.ui.ScreenCopy
import com.meenai.mina.ui.StatusCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import com.meenai.mina.core.cap.web.KeptPageClose

/**
 * This notification is sent and shines in the notification bar, but does not sound, vibrate, or pop up
 * the banner: Use the system's group reminder rules (only let the group summary be reminded, this is a
 * normal notification in the group, there is no summary in the group), and the system's own "silent
 * notification" is the same method; no channel change, no new channel. It is available from Android 8
 * onwards and can be used on all systems supported by Mina.
 */
internal fun Notification.Builder.alertQuietly(): Notification.Builder =
    setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)

private const val QUIET_GROUP = "com.meenai.mina.quiet"

/**
 * Foreground service: Run tasks on the Agent virtual screen. The status and execution of a single task
 * are at [TaskRun], and running tasks are registered at [TaskRegistry]. When the switch parallelTasks is
 * turned off, only one will be run at a time, and tasks handed over from other conversations will be
 * queued behind (no matter where they are handed in, they will not be lost); when turned on, tasks from
 * different conversations can be run at the same time, each occupying a virtual screen. The upper limit
 * is [ParallelCapability], and the excess is queued. Each task has a set of notifications (the number
 * and button request code are separated by [TaskRun.slot], and the button carries the conversation
 * number). The permanent notification at the front desk is the progress notification of one of the
 * tasks. When there are several tasks at the same time, it indicates "n tasks in progress". The screen
 * is always on when the task is running ([KeepAwake], system settings and brightness are not changed);
 * the target App is muted during the task (the original mode is persisted and restored at the next
 * startup after abnormal exit). The gate of AgentLoop is implemented by [TaskGate]: the screen is turned
 * off/the screen is locked waiting to be unlocked, and the App is moved to the home screen or the mobile
 * operation assistant is disconnected, and it is paused until the user clicks "Continue". When the Agent
 * requests to take over or asks a question, it will send another eye-catching notification. For the
 * takeover process, see [Takeover].
 *
 * The front-end service type using specialUse:dataSync has a daily limit of 6 hours in Android 15 and
 * the semantics are inconsistent; specialUse has no runtime restrictions and only needs to indicate the
 * subtype in the manifest.
 */
class TaskService : Service() {
    internal val taskState: TaskCenterState get() = (application as com.meenai.mina.HarnessApp).taskState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The switch read at the last start; when it is off, the upper limit of the registry is always 1. */
    @Volatile private var parallel = false
    private val center by lazy {
        TaskCenter<TaskRun>(::limit, { it.conversationId }, { it.key }, (application as com.meenai.mina.HarnessApp).runStore,
            (application as com.meenai.mina.HarnessApp).resources) { c, g ->
            TaskRun(c, g, this, scope, freeSlot(), parallel)
        }
    }
    private val outbox get() = (application as com.meenai.mina.HarnessApp).outbox
    private val localChannel by lazy { LocalChannel { _, delivery ->
        if (delivery is Delivery.Ask) {
            center.byConversation(delivery.threadId)?.let(::refreshNotifications)
            SendOutcome.Delivered
        } else {
            val resultId = if (delivery is Delivery.Result && center.byConversation(delivery.threadId)?.parallel == false) ID_RESULT
                else nextResultId()
            notifyDelivery(this, delivery, resultId)
        }
    } }
    /** The permanent notification at the front desk belongs to which task; keep it after the end until it is changed to another task. */
    @Volatile private var foreground: TaskRun? = null
    private val notifyLock = Any()
    @Volatile private var lastStartId = 0

    internal val clipLock get() = center.clipLock
    internal val takeoverQueue get() = center.takeoverQueue
    /** Always-on screen lock shared by several tasks. */
    internal val wakeLock by lazy { SharedWakeLock(ScreenWakeLock(this)) }

    private fun limit(): ParallelLimit {
        if (!parallel) return ParallelLimit(1, null)
        val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        return ParallelCapability.detect(Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(this).parallelMax(), taskState.secondDisplayFailed)
            .also { taskState._parallelLimit.value = it }
    }

    /** The smallest slot in the registry that no one uses; only called within the registry's lock. */
    private fun freeSlot(): Int {
        val used = center.runs().map { it.slot }.toSet()
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    internal fun reservePackage(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.reserve(pkg, runKey, stillUsed)
    internal fun packageFree(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.appFree(pkg, runKey, stillUsed)
    internal suspend fun awaitPackage(pkg: String, runKey: String, timeoutMs: Long, free: suspend () -> Boolean) =
        center.awaitApp(pkg, runKey, timeoutMs, free)

    /** If this conversation is empty, start it on the spot; if there are any tasks, keep them and hand them over at the end ([onRunEnded]). Check and stay in the same lock. */
    internal fun deferMessage(threadId: String, text: String) {
        if (center.deferOrStart(threadId, text)) start(this, text, threadId)
    }

    internal fun interaction(threadId: String) = ChannelInteraction(outbox, localChannel,
        ChannelPeer(LocalChannel.ID, "phone-owner", threadId))

    /** The page left in the background after the task is completed (see [com.meenai.mina.cap.web.PageKeeper]): When left, the service will not return, and the foreground notification will be replaced by it. */
    private val keeper get() = (application as com.meenai.mina.HarnessApp).pageKeeper

    internal fun otherRunHasDisplay(run: TaskRun) = center.running().any { it !== run && it.displayId.value >= 0 }
    internal fun hasActiveRuns() = center.running().isNotEmpty()

    private fun publishRuns() {
        val r = center.runs()
        taskState._runs.value = r
        taskState._activeConversations.value = r.map { it.conversationId }
    }

    private fun id(base: Int, run: TaskRun) = base + SLOT_STRIDE * run.slot

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // The page that was left changed: the notification was changed; when it was turned off and there
        // was no task, it exited the front desk and stopped the service. Just follow "keep → changed /
        // turn off": the "no" at the beginning does not count, so as not to rush to stop the service
        // with the task that is starting.
        scope.launch {
            var had = false
            keeper.page.collect { p ->
                if (p == null && !had) return@collect
                had = p != null
                onKeptPage(p)
            }
        }
        // Unfinished runs are processed when the app starts; only one step is added here (only done once
        // in the process), and the run running at the moment is excluded.
        (application as com.meenai.mina.HarnessApp).runRecovery.recover(center.runs().map { RunId(it.key) }.toSet())
    }

    private fun waitKeyOf(intent: Intent) = intent.getIntExtra(EXTRA_WAIT_KEY, -1).takeIf { it >= 0 }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // The notification button carries the task number and only acts on that task (it will not take
        // effect after it has ended); the button in the application acts on the displayed task
        val run = if (intent?.hasExtra(EXTRA_RUN) == true) intent.getStringExtra(EXTRA_RUN)?.let(center::byKey) else taskState.shownRun
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                messageId = intent.getStringExtra(EXTRA_MESSAGE_ID))
            ACTION_START_TRIGGER -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
                triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY),
                triggerActionGoal = intent.getStringExtra(EXTRA_TRIGGER_ACTION),
                triggerThreadId = intent.getStringExtra(EXTRA_TRIGGER_THREAD))
            ACTION_START_MCP -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                provenance = RunProvenance(RunOrigin.Mcp,
                    Attendance.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ATTENDANCE) } ?: Attendance.Unattended),
                mcpExternalId = intent.getStringExtra(EXTRA_MCP_ID))
            ACTION_CANCEL_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id -> center.runs().firstOrNull { it.mcpExternalId == id } }
                ?.let { r -> if (center.cancelIfQueued(r)) cancelQueued(r) else r.cancel() }
            ACTION_CANCEL -> if (run != null && center.cancelIfQueued(run)) cancelQueued(run) else run?.cancel()
            ACTION_RESUME -> run?.resume()
            ACTION_PAUSE -> run?.pause()
            ACTION_TAKE_OVER -> run?.takeOver(waitKeyOf(intent))
            ACTION_HAND_BACK -> run?.handBack(waitKeyOf(intent))
            ACTION_FINISHED_MYSELF -> run?.finishedMyself(waitKeyOf(intent))
            ACTION_SKIP -> run?.skip(waitKeyOf(intent))
            ACTION_PUT_ASIDE -> run?.putAside(waitKeyOf(intent))
            ACTION_ANSWER -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_ANSWER)
                if (!text.isNullOrBlank()) run?.answer(text.trim(), ::answerTooLate)
            }
            ACTION_CLOSE_KEPT_PAGE -> keeper.close(KeptPageClose.User)
            ACTION_APPROVE, ACTION_DECLINE -> {
                val key = if (intent.hasExtra(ConfirmationNotifier.EXTRA_REQUEST)) intent.getIntExtra(ConfirmationNotifier.EXTRA_REQUEST, 0) else null
                run?.confirm(if (intent.action == ACTION_APPROVE) ConfirmChoice.Once else ConfirmChoice.Deny, key)
            }
        }
        if (!taskState._busy.value && keeper.page.value == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun begin(newGoal: String, requestedConversation: String?,
        provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
        triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null, messageId: String? = null) {
        parallel = FeatureFlags.get(this).speedFlags().parallelTasks
        // When a task that is being executed or queued in the same conversation receives a new message,
        // it is handed to its Inbox.
        requestedConversation?.let(center::byConversation)?.let { existing ->
            if (provenance.origin == RunOrigin.Ui) {
                if (messageId != null) existing.steer(newGoal, messageId) else existing.steer(newGoal)
                keepForeground()
                return
            }
        }
        // The task had already been completed when I interrupted: as the next thing in this
        // conversation, do not throw it away because the service is still being completed.
        if (messageId != null && requestedConversation != null) {
            deferMessage(requestedConversation, newGoal)
            keepForeground()
            return
        }
        if (triggerKey != null && center.runs().any { it.triggerWindowKey == triggerKey &&
                it.conditionCheck == (triggerActionGoal != null) }) {
            keepForeground()
            return
        }
        // The task of other dialogues is not lost when it is running or closed in parallel (or has
        // reached the upper limit): the registry puts it at the back of the queue and releases it when
        // the task ends. The conversation page waits for the service to be free before handing it over.
        // However, between the time the search is completed and the time it is handed over, another one
        // may be started elsewhere (timing, computer), and it is also queued here.
        val store = ConversationStore.shared(this)
        val conversationId = requestedConversation ?: store.newConversation()
        // Tasks given to the computer on a regular basis will not be lost: when there are tasks in this
        // dialogue, they will be queued behind it.
        val queueHere = provenance.origin == RunOrigin.Trigger || provenance.origin == RunOrigin.Mcp
        when (val r = center.tryStart(conversationId, newGoal, queueInConversation = queueHere)) {
            StartResult.AlreadyRunningInConversation -> keepForeground()
            is StartResult.Queued -> {
                nameConversation(store, conversationId, newGoal, provenance)
                r.run.provenance = provenance
                r.run.triggerWindowKey = triggerKey
                r.run.triggerActionGoal = triggerActionGoal
                r.run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.meenai.mina.core.run.RunId(r.run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey))
                }
                r.run.mcpExternalId = mcpExternalId
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // Register first and then display: When the interface receives its status, it is already
                // in the task list (dialogue, original words)
                publishRuns()
                // Clear success criteria, run number, and display queue
                if (center.running().none { it.conversationId == conversationId }) r.run.show()
                r.run.queue(r.position)
                keepForeground()
            }
            is StartResult.Started -> {
                nameConversation(store, conversationId, newGoal, provenance)
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // Clear the success criteria and run number, and set the status to "Preparing"
                val run = r.run
                run.provenance = provenance
                run.triggerWindowKey = triggerKey
                run.triggerActionGoal = triggerActionGoal
                run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.meenai.mina.core.run.RunId(run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey))
                }
                run.mcpExternalId = mcpExternalId
                // Register first and then display: When the interface receives its status, it is already
                // in the task list (dialogue, original words)
                publishRuns()
                run.show()
                val fg = foreground
                if (fg != null && fg !== run && fg in center.running()) keepForeground()
                else {
                    foreground = run
                    startInForeground(run, progressNotification(run, run.state.value))
                }
                launch(run, store)
            }
        }
    }

    /** The first sentence handed in by the user (or computer) in a new conversation: The background asks the model to write down a title (only once, otherwise it will not be remembered). */
    private fun nameConversation(store: ConversationStore, id: String, first: String, provenance: RunProvenance) {
        if (provenance.origin != RunOrigin.Ui && provenance.origin != RunOrigin.Mcp) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val env = TaskEnvironment.of(this@TaskService)
                // The title also belongs to this conversation, and uses the same conversation number as
                // the task in the conversation.
                val session = ModelSession.of(id)
                ConversationTitler.ensure(store, id, first) {
                    listOfNotNull(env.lightModelFactory(this@TaskService, session), env.modelFactory(this@TaskService, session))
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Enter the foreground again when you are already in the foreground (or about to end it): use the progress notification of the task in the foreground. */
    private fun keepForeground() {
        val fg = foreground
        if (fg != null) startInForeground(fg, progressNotification(fg, fg.state.value))
        else startInForeground(null, preparingNotification())
    }

    private fun launch(run: TaskRun, store: ConversationStore) {
        run.job = scope.launch {
            center.markRunning(run)
            val result = try {
                run.execute(runCatching { store.turns(run.conversationId) }.getOrDefault(emptyList()).map(::withKeptState))
            } catch (e: CancellationException) {
                TaskState.Cancelled
            } catch (e: RemoteException) {
                run.shellDied = true
                TaskState.Failed(SHELL_DIED, FailKind.Environment)
            } catch (e: com.meenai.mina.core.access.AppAccessDenied) {
                val reason = when (e.code) {
                    "app_blocked" -> tr(R.string.task_app_blocked, e.packageName ?: tr(R.string.task_app_this_app))
                    "app_access_unavailable" -> tr(R.string.task_app_access_unavailable)
                    "app_access_protected" -> tr(R.string.task_app_protected)
                    else -> tr(R.string.task_app_unverified)
                }
                TaskState.Failed(reason, FailKind.Environment)
            } catch (e: Exception) {
                TaskState.Failed(tr(R.string.task_error, e.message ?: e::class.simpleName), FailKind.Environment)
            }
            // The shell process is gone, and the virtual screen and the App on it are gone together; the
            // task cannot be continued.
            val end = if (run.shellDied && result !is TaskState.Succeeded) TaskState.Failed(SHELL_DIED, FailKind.Environment) else result
            // Stopped: The interface shows that it has stopped on the spot, and the instructions will be
            // added later (register "organizing" first, and when the interface receives the final state,
            // you will know that there are still more words to come)
            val draft = if (end == TaskState.Cancelled) run.draftText() else null
            val stopRunId = run.runId.value?.takeIf { end == TaskState.Cancelled && !run.conditionCheck && run.hasStopMaterial(draft) }
            stopRunId?.let { id -> taskState._summarizing.update { it + id } }
            run.end(end)
            if (!run.conditionCheck) recordTurn(store, run.conversationId, run.goal, end, run.runId.value, run.sharedFiles.value, run.joinedSteers(), run.usedGroups, run.joinedInterrupted(), run.joinedFinished())
            stopRunId?.let { summarizeStop(run, it, draft, store) }
            if (run.conditionCheck) {
                val outcome = if (end is TaskState.Succeeded) ConditionOutcome.parse(end.answer)
                    else ConditionOutcome.Error((end as? TaskState.Failed)?.reason ?: tr(R.string.task_condition_interrupted))
                TriggerRuntime.checked(this@TaskService, run.triggerWindowKey.orEmpty(), run.triggerActionGoal.orEmpty(),
                    run.triggerThreadId.orEmpty(), outcome)
                if (outcome is ConditionOutcome.Error) {
                    val failure = TaskState.Failed(tr(R.string.task_check_unfinished, outcome.reason), FailKind.Environment)
                    recordTurn(store, run.conversationId, run.goal, failure, run.runId.value)
                    runCatching { notifyResult(run, failure, "condition:${run.key}") }
                        .onFailure { Log.w(TAG, "condition failure notification failed", it) }
                }
                center.markDelivered(run)
                onRunEnded(run, store)
                return@launch
            }
            val deliveryId = "result:${run.key}"
            center.markResult(run, describe(end).second, deliveryId, successful = end is TaskState.Succeeded)
            run.clearTakeoverStage()
            synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_ATTENTION, run)) }
            // Send the result notification first, and then learn in the background (retrieve memory,
            // memorize skill results, draft skills); use process-level scope, service exit is also
            // completed
            var delivered = false
            val app = applicationContext
            runCatching {
                RunLearning.launchAfter(MemoryRuntime.of(this@TaskService).scope, {
                    delivered = notifyResult(run, end, deliveryId) == DeliveryState.Delivered
                    // The result of the scheduled task is recorded as the ending on the spot (the same
                    // as before). If it is judged as not completed, it will be re-recorded; it will
                    // always be recorded without relying on the service.
                    if (delivered) run.triggerWindowKey?.let { key ->
                        runCatching {
                            if (end is TaskState.Succeeded) TriggerRuntime.delivered(app, key)
                            else TriggerRuntime.actionFailed(app, key, describe(end).second)
                        }.onFailure { Log.w(TAG, "could not record trigger result", it) }
                    }
                }) {
                    // After handing over: the answer will be determined according to the user's
                    // requirements (recorded in the running record and this round of dialogue), and only
                    // completed studies will be taken.
                    val judged = runCatching { run.settleOutcome(end, store) }.onFailure { Log.w(TAG, "outcome judging failed", it) }.getOrNull()
                    // Those who answer truthfully that it was not completed or only partially completed
                    // will be counted as failed as if they gave up; those who failed to complete will be
                    // counted as handed over.
                    val notDone = judged?.outcome == RunOutcome.Partial || judged?.outcome == RunOutcome.NotPossible
                    if (delivered && notDone && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                        runCatching { TriggerRuntime.actionFailed(app, key, judged?.reason?.ifBlank { null } ?: describe(end).second) }
                            .onFailure { Log.w(TAG, "could not downgrade trigger result", it) }
                    }
                    if (run.provenance.origin == RunOrigin.Ui) runCatching { run.learnAfter(end, judged?.outcome, store) }
                        .onFailure { Log.w(TAG, "learning after run failed", it) }
                }
            }.onFailure { Log.w(TAG, "result notification failed", it) }
            if (delivered) center.markDelivered(run)
            run.mcpExternalId?.let { (application as com.meenai.mina.HarnessApp).remoteTasks.ended(it, end, run.steers.value) }
            onRunEnded(run, store)
        }
    }

    /**
     * After finishing, write an explanation and record it as the answer for this round (the ending is
     * still cancelled). Use process-level scope to write in the background: without blocking the ending,
     * next thing, and click stop again, the service exit is also completed. If it is written well or
     * cannot be written, it will be removed from "Organizing".
     */
    private fun summarizeStop(run: TaskRun, runId: String, draft: String?, store: ConversationStore) {
        MemoryRuntime.of(this).scope.launch {
            try {
                val text = runCatching { run.stopSummary(draft) }.onFailure { Log.w(TAG, "stop summary failed", it) }.getOrNull()
                if (text != null) withContext(Dispatchers.IO) {
                    runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = text) } }
                        .onFailure { Log.w(TAG, "could not save stop summary", it) }
                }
            } finally {
                taskState._summarizing.update { it - runId }
            }
        }
    }

    /** After the tasks are completed: exit the front desk when they are all over; if there are other tasks, hand them over to the front desk and release the queued tasks. */
    private fun onRunEnded(run: TaskRun, store: ConversationStore) {
        val wasShown = taskState.shownRun === run
        val started = center.onEnded(run)
        publishRuns()
        if (wasShown) started.firstOrNull { it.conversationId == run.conversationId }?.show()
        // Start the conversation as soon as it becomes available, without waiting for other dialogue
        // tasks.
        val ready = center.takeReady()
        if (center.runs().isEmpty()) {
            if (ready.isNotEmpty()) {
                ready.forEach { (threadId, message) -> begin(message, threadId) }
                return
            }
            // There is still one page running in the background: the foreground notification is replaced
            // by it, and the service does not return (the process is supported by it); when it is turned
            // off, it exits with [onKeptPage]
            val kept = keeper.page.value
            if (kept != null) {
                synchronized(notifyLock) {
                    startForeground(ID_KEPT, keptNotification(kept))
                    getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run))
                }
                foreground = null
                taskState._busy.value = false
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Get the number first and then release it: the new task that arrives after you release it
            // will bring an updated number, and the stopSelf here will not stop it.
            val id = lastStartId
            taskState._busy.value = false
            stopSelf(id)
            return
        }
        // First change it to "Preparing": the released task may happen to be taken over by the front
        // desk, and the notification cannot still say queuing.
        started.forEach { it.dequeue() }
        if (foreground === run) center.running().firstOrNull()?.let { next ->
            foreground = next
            startInForeground(next, progressNotification(next, next.state.value))
        }
        synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run)) }
        started.forEach {
            launch(it, store)
            refreshNotifications(it)
        }
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
        center.running().forEach(::refreshNotifications)
        ready.forEach { (threadId, message) -> begin(message, threadId) }
    }

    /** The task in the queue is canceled (has been removed from the queue): it has not started and is directly recorded as canceled. */
    private fun cancelQueued(run: TaskRun) {
        run.handoffUnconsumed(cancelled = true)
        run.end(TaskState.Cancelled)
        recordTurn(ConversationStore.shared(this), run.conversationId, run.goal, TaskState.Cancelled, null)
        run.mcpExternalId?.let { (application as com.meenai.mina.HarnessApp).remoteTasks.ended(it, TaskState.Cancelled, run.steers.value) }
        publishRuns()
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    }

    /**
     * The page that remains has changed: if it is still there, send (or change) its permanent
     * notification - when there are no tasks running, it will be the foreground notification; if it is
     * turned off, the notification will be removed. If there are no tasks, it will exit the foreground
     * and stop the service.
     */
    private fun onKeptPage(page: com.meenai.mina.cap.web.KeptPage?) {
        val nm = getSystemService(NotificationManager::class.java)
        synchronized(notifyLock) {
            val idle = center.runs().isEmpty()
            if (page != null) {
                if (idle && foreground == null) startForeground(ID_KEPT, keptNotification(page)) else nm.notify(ID_KEPT, keptNotification(page))
                return
            }
            nm.cancel(ID_KEPT)
            if (!idle || taskState._busy.value) return
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    /** Permanent notifications for the page left in the background: site, how long is left until automatic closing (countdown), buttons "Close web page" and "Open conversation". */
    private fun keptNotification(page: com.meenai.mina.cap.web.KeptPage): Notification {
        val close = PendingIntent.getService(
            this, REQUEST_CLOSE_KEPT, Intent(this, TaskService::class.java).setAction(ACTION_CLOSE_KEPT_PAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.kept_notif_title))
            .setContentText(page.host)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The time will count down to the moment when it automatically turns off (the notification
            // text will not expire)
            .setWhen(page.closesAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(openApp(page.conversationId))
            .addAction(Notification.Action.Builder(null, tr(R.string.kept_close), close).build())
            .addAction(Notification.Action.Builder(null, tr(R.string.kept_open_chat), openApp(page.conversationId)).build())
            .build()
    }

    /**
     * In the above article for the model, is the page of the round where the page was left still open at
     * the moment: if it is marked as "keep" but is no longer there (Mina quit midway), it will be
     * counted as closed.
     */
    private fun withKeptState(t: Turn): Turn =
        if (t.keptPage != null && t.keptPageClosed == null && keeper.page.value?.runId != t.runId) t.copy(keptPageClosed = KeptPageClose.AppExit.wire) else t

    /** A round will be recorded at the end of the mission, and you can bring it with you for reference in subsequent messages. Failure to write to disk does not affect the task results. */
    private fun recordTurn(store: ConversationStore, id: String, goal: String, end: TaskState, runId: String?, files: List<SharedFile> = emptyList(),
        steers: List<String> = emptyList(), groups: List<String> = emptyList(), interrupted: List<String> = emptyList(),
        finished: List<String> = emptyList()) {
        val turn = when (end) {
            // Those that have not been verified (there are no verifiable values in chats and answers)
            // will be recorded as succeeded as those that have been verified; those that have been
            // verified but failed to be verified will be recorded as unverified
            is TaskState.Succeeded -> if (end.doubtful) Turn(goal, end.answer, "unverified", end.note, unconfirmed = end.unconfirmed) else Turn(goal, end.answer, "succeeded")
            is TaskState.Failed -> Turn(goal, end.reason, "failed")
            TaskState.Cancelled -> Turn(goal, Conversation.CANCELLED_OUTCOME, "cancelled")
            else -> return
        }.copy(runId = runId, steers = steers, groups = groups, interruptedReplies = interrupted, finishedReplies = finished)
            // This round left a page: write down the sites (already closed along with the reasons), and
            // the dialogue page will be displayed accordingly in that round.
            .let { t -> keeper.recordFor(runId)?.let { (host, closed) -> t.copy(keptPage = host, keptPageClosed = closed) } ?: t }
        runCatching { store.append(id, turn, files) }.onFailure { Log.w(TAG, "could not save conversation turn", it) }
    }

    /**
     * Redraw the notification as it currently stands. Multiple coroutines will be called, serially, and
     * get the latest state in the lock: otherwise the party holding the old state may send it out again
     * after the new state has removed the notification.
     */
    internal fun refreshNotifications(run: TaskRun) {
        synchronized(notifyLock) {
            val shown = run.shownState() ?: return
            if (!shown.isActive() || center.isQueued(run)) return
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(id(ID_PROGRESS, run), progressNotification(run, shown))
            val attention = attentionNotification(run, shown)
            if (attention != null) nm.notify(id(ID_ATTENTION, run), attention) else nm.cancel(id(ID_ATTENTION, run))
        }
    }

    /** The short section that has been handed back and the loop has not yet gone down: the takeover button is no longer displayed. */
    private fun awaitingUser(run: TaskRun, state: TaskState) =
        state !is TaskState.WaitingTakeover || run.takeoverStage.value != Takeover.Stage.None

    /** Only hand over one app at a time on the home screen: do not give the takeover button when taking over is queued behind other tasks. */
    private fun takeoverFirst(run: TaskRun) = run.takeover?.let(takeoverQueue::isFirst) ?: true

    /** Use the progress notification number of [run] to enter the front desk; when there is no task object, use the first slot. */
    private fun startInForeground(run: TaskRun?, n: Notification) {
        val nid = run?.let { id(ID_PROGRESS, it) } ?: ID_PROGRESS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(nid, n)
        }
    }

    private fun progressNotification(run: TaskRun, state: TaskState): Notification {
        val (_, text) = describe(state) { ConfirmationNotifier.appLabel(this, it) }
        val warning = run.muteFailedFor?.let { "\n" + tr(R.string.task_may_make_sound) }.orEmpty() +
            (if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) "\n$TAKEOVER_QUEUED" else "")
        val content = com.meenai.mina.core.general.MarkdownSanitizer.strip(text + warning)
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.task_notif_title, ScreenCopy.plain(run.goal)))
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        val active = center.running().size
        if (active >= 2) b.setSubText(trPlural(R.plurals.task_active, active))
        when (state) {
            is TaskState.WaitingTakeover ->
                if (awaitingUser(run, state) && takeoverFirst(run)) takeoverActions(run).forEach(b::addAction)
                else b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
            else -> {
                if (state is TaskState.Held && state.reason.resumable) b.addAction(action(run, tr(R.string.chat_continue), ACTION_RESUME, 1))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
        }
        return b.build()
    }

    /** When you are busy but there is no task target yet (the finishing is interrupted, etc.), you have to go to the front desk immediately. */
    private fun preparingNotification(): Notification = Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Harness Agent")
        .setContentText(PREPARING)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun answerTooLate() = Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, getString(R.string.input_too_late, INPUT_TIMEOUT_MINUTES), Toast.LENGTH_LONG).show()
    }

    /**
     * Another eye-catching notification is sent when waiting for takeover or answer; there is no other
     * status. The floating capsule still pops up when it is displayed on the bright screen, but quietly
     * enters the notification bar (the capsule has turned orange, see [alertQuietly]); it still pops up
     * when the screen is locked, the capsule is closed, or the drawing cannot be drawn.
     */
    private fun attentionNotification(run: TaskRun, state: TaskState): Notification? {
        if (!awaitingUser(run, state)) return null
        val quiet = (application as com.meenai.mina.HarnessApp).capsule.showing.value
        if (state is TaskState.WaitingConfirmation) {
            return ConfirmationNotifier.build(
                this, CHANNEL_ATTENTION, state, TaskService::class.java, ACTION_APPROVE, ACTION_DECLINE, openApp(),
                codeOffset = SLOT_STRIDE * run.slot, runExtra = EXTRA_RUN to run.key, quiet = quiet,
            )
        }
        if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) return null
        val (title, text) = when (state) {
            is TaskState.WaitingTakeover -> {
                val extra = when (run.takeover?.problem?.value) {
                    Takeover.Problem.MoveToMainFailed -> getString(R.string.takeover_move_to_main_failed)
                    Takeover.Problem.WebBusy -> getString(R.string.takeover_web_busy)
                    Takeover.Problem.MoveBackFailed -> getString(R.string.takeover_move_back_failed)
                    null -> if (run.takeoverStage.value == Takeover.Stage.HandedOver) getString(R.string.takeover_handed_over_hint) else null
                }
                val payment = StatusCopy.paymentLines(state.item, state.amount, state.address)
                val behind = run.takeover?.let(takeoverQueue::behind) ?: 0
                val waiting = if (behind > 0) trPlural(R.plurals.task_behind, behind) else null
                takeoverTitle(state.reason) to listOfNotNull(state.message, payment, extra, waiting).joinToString("\n")
            }
            is TaskState.WaitingInput -> getString(R.string.input_title) to
                "${state.prompt}\n${getString(R.string.no_answer_note, INPUT_TIMEOUT_MINUTES)}"
            else -> return null
        }
        val b = Notification.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true) // Changing the button during the same wait will no longer ring; remove it when leaving the wait, and it will remind you again next time
            .setContentIntent(openApp())
        if (quiet) b.alertQuietly()
        when (state) {
            is TaskState.WaitingTakeover -> takeoverActions(run).forEach(b::addAction)
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                // Android notifications can display up to 3 buttons, so it is "Reply" + the first two
                // options; all options are put into the shortcut options of reply (RemoteInput choices),
                // and the additional options can be selected there.
                state.options.orEmpty().take(2).forEachIndexed { i, o -> b.addAction(answerAction(run, o, i)) }
            }
            else -> Unit
        }
        return b.build()
    }

    private fun takeoverTitle(reason: String): String {
        val label = when (reason) {
            "captcha" -> R.string.takeover_reason_captcha
            "login" -> R.string.takeover_reason_login
            "access_blocked" -> R.string.takeover_reason_access_blocked
            "payment" -> R.string.takeover_reason_payment
            "decision" -> R.string.takeover_reason_decision
            else -> return getString(R.string.takeover_title)
        }
        return getString(R.string.takeover_title_reason, getString(label))
    }

    private fun takeoverActions(run: TaskRun): List<Notification.Action> {
        val cancel = action(run, getString(R.string.action_cancel_task), ACTION_CANCEL, 2)
        return if (run.takeoverStage.value == Takeover.Stage.HandedOver) listOf(
            takeoverAction(run, getString(R.string.action_hand_back), ACTION_HAND_BACK, 4),
            takeoverAction(run, getString(R.string.action_finished_myself), ACTION_FINISHED_MYSELF, 5),
            cancel,
        ) else listOf(
            takeoverAction(run, getString(R.string.action_take_over), ACTION_TAKE_OVER, 3),
            takeoverAction(run, getString(R.string.action_skip), ACTION_SKIP, 7),
            cancel,
        )
    }

    /** Through an activity that does not display the interface: the notification bar will be closed only when the activity is started from the notification. */
    private fun takeoverAction(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getActivity(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RUN, run.key)
                .apply { run.takeover?.let { putExtra(EXTRA_WAIT_KEY, it.waitKey) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun replyAction(run: TaskRun, options: List<String>?): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.reply_hint))
            .apply { if (!options.isNullOrEmpty()) setChoices(options.toTypedArray()) }
            .build()
        // The reply content must be filled in by the system into the Intent, and only the variable
        // PendingIntent can be used (the Intent is explicit)
        val pi = PendingIntent.getService(
            this, 6 + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(R.string.action_reply), pi).addRemoteInput(input).build()
    }

    private fun answerAction(run: TaskRun, option: String, index: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, 10 + index + SLOT_STRIDE * run.slot,
            Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, option).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, option, pi).build()
    }

    private suspend fun notifyResult(run: TaskRun, end: TaskState, deliveryId: String): DeliveryState {
        val (title, text) = describe(end)
        val peer = ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
        val delivered = outbox.deliver(localChannel, peer, Delivery.Result(deliveryId, run.conversationId, text, title = title))
        if (delivered.state != DeliveryState.Delivered) Log.w(TAG, "result delivery ${delivered.state}: $deliveryId")
        return delivered.state
    }

    private fun action(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(action).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun openApp(threadId: String? = null) = openApp(this, threadId)

    override fun onDestroy() {
        scope.cancel()
        // If the service is gone, the process cannot be sustained: close the remaining page and note it
        // down in the conversation.
        keeper.close(KeptPageClose.AppExit)
        getSystemService(NotificationManager::class.java).cancel(ID_KEPT)
        if (center.runs().isNotEmpty()) {
            // Take care of the aftermath on the spot after the standard interruption (timing window,
            // supplementary delivery, notification, writing into the dialog): the process is still there
            // and will not wait for the next startup; using the App-level scope, the service can be
            // completed even if the service is gone.
            center.store.interruptUnfinished()
            val app = application as com.meenai.mina.HarnessApp
            center.runs().forEach { r -> r.mcpExternalId?.let { app.remoteTasks.ended(it, TaskState.Failed(tr(R.string.task_interrupted), FailKind.Environment), r.steers.value) } }
            (application as com.meenai.mina.HarnessApp).runRecovery.settleInterrupted()
            taskState._state.value = TaskState.Failed(tr(R.string.task_interrupted), FailKind.Environment)
            taskState._runs.value = emptyList()
            taskState._activeConversations.value = emptyList()
            taskState.shownRun = null
        }
        taskState._busy.value = false // When the ending is cancelled, new tasks cannot be blocked forever.
        super.onDestroy()
    }

    companion object {
        /** Create a notification channel; repeated creation has no side effects. The recovery when the app is started is also used, so it does not depend on the service instance. */
        internal fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val names = mapOf(CHANNEL_PROGRESS to tr(R.string.channel_progress), CHANNEL_RESULT to tr(R.string.channel_result), CHANNEL_ATTENTION to tr(R.string.channel_attention))
            // The old result channel has been built at a lower level, and the App cannot be adjusted to
            // a higher level; change the ID to a new one, and delete the old one to avoid adding another
            // item in the settings.
            nm.deleteNotificationChannel(OLD_CHANNEL_RESULT)
            CHANNEL_IMPORTANCE.forEach { (id, importance) -> nm.createNotificationChannel(NotificationChannel(id, names.getValue(id), importance)) }
        }

        internal fun openApp(context: Context, threadId: String? = null): PendingIntent = PendingIntent.getActivity(
            context, threadId?.hashCode() ?: 0, openAppIntent(context, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Open Mina; go directly to that dialogue when giving [threadId]. Notifications and suspension capsules are shared. */
        internal fun openAppIntent(context: Context, threadId: String? = null): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (threadId != null) putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId) }

        /** The next number of the result notification in parallel (used in a loop). */
        internal fun nextResultId() = ID_RESULT_FIRST + resultCount.getAndIncrement() % RESULT_IDS

        /** The results and prompts are sent as notifications (shared in the service and used for recovery when the App is started); [resultId] is the number of the result notification. */
        internal fun notifyDelivery(context: Context, delivery: Delivery, resultId: Int): SendOutcome {
            val nm = context.getSystemService(NotificationManager::class.java)
            when (delivery) {
                is Delivery.Result -> {
                    val cleanTitle = com.meenai.mina.core.general.MarkdownSanitizer.strip(delivery.title)
                    val cleanText = com.meenai.mina.core.general.MarkdownSanitizer.strip(delivery.text)
                    nm.notify(resultId, Notification.Builder(context, CHANNEL_RESULT)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(cleanTitle)
                        .setContentText(cleanText)
                        .setStyle(Notification.BigTextStyle().bigText(cleanText))
                        .setContentIntent(openApp(context, delivery.threadId))
                        .setAutoCancel(true)
                        .build())
                }
                is Delivery.Notice -> {
                    val cleanText = com.meenai.mina.core.general.MarkdownSanitizer.strip(delivery.text)
                    nm.notify(nextResultId(), Notification.Builder(context, CHANNEL_RESULT)
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Harness Agent")
                        .setContentText(cleanText).setContentIntent(openApp(context, delivery.threadId)).setAutoCancel(true).build())
                }
                is Delivery.Ask, is Delivery.Progress -> {}
            }
            return SendOutcome.Delivered
        }

        /** The time limit for asking questions and waiting for answers: handed over to AgentLoop, the notification and the number of minutes written on the card also come from here. */
        const val INPUT_TIMEOUT_MS = AgentLoop.DEFAULT_INPUT_TIMEOUT_MS
        const val INPUT_TIMEOUT_MINUTES = INPUT_TIMEOUT_MS / 60_000
        const val ACTION_START = "com.meenai.mina.task.START"
        const val ACTION_START_TRIGGER = "com.meenai.mina.task.START_TRIGGER"
        const val ACTION_START_MCP = "com.meenai.mina.task.START_MCP"
        const val ACTION_CANCEL_MCP = "com.meenai.mina.task.CANCEL_MCP"
        const val ACTION_CANCEL = "com.meenai.mina.task.CANCEL"
        const val ACTION_RESUME = "com.meenai.mina.task.RESUME"
        const val ACTION_PAUSE = "com.meenai.mina.task.PAUSE"
        const val ACTION_TAKE_OVER = "com.meenai.mina.task.TAKE_OVER"
        const val ACTION_HAND_BACK = "com.meenai.mina.task.HAND_BACK"
        const val ACTION_FINISHED_MYSELF = "com.meenai.mina.task.FINISHED_MYSELF"
        const val ACTION_SKIP = "com.meenai.mina.task.SKIP"
        /** Press the system return key on the web page takeover interface: leave it alone for now and return to wait for takeover. */
        const val ACTION_PUT_ASIDE = "com.meenai.mina.task.PUT_ASIDE"
        /** To take over the "number of times to wait" with the notification button, see [Takeover.waitKey]. */
        const val EXTRA_WAIT_KEY = "com.meenai.mina.task.WAIT_KEY"
        const val ACTION_ANSWER = "com.meenai.mina.task.ANSWER"
        const val ACTION_APPROVE = "com.meenai.mina.task.APPROVE"
        /** Close the page (button on the notification) that remains in the background after the task is completed. */
        const val ACTION_CLOSE_KEPT_PAGE = "com.meenai.mina.task.CLOSE_KEPT_PAGE"
        const val ACTION_DECLINE = "com.meenai.mina.task.DECLINE"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val EXTRA_TRIGGER_KEY = "trigger_key"
        private const val EXTRA_TRIGGER_ACTION = "trigger_action"
        private const val EXTRA_TRIGGER_THREAD = "trigger_thread"
        private const val EXTRA_MCP_ID = "mcp_id"
        private const val EXTRA_ATTENDANCE = "attendance"
        private const val EXTRA_ANSWER = "answer"
        /** The number of the task to which the notification button belongs ([TaskRun.key]). */
        const val EXTRA_RUN = "com.meenai.mina.task.RUN"
        /** Wait for up to this long for the user to answer and confirm, and then press Disagree (the task will not remain pending when not next to the phone, and it will never automatically agree). */
        const val CONFIRM_TIMEOUT_MS = 15 * 60_000L
        private const val KEY_REPLY = "reply"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_PROGRESS = "task_progress"
        const val CHANNEL_RESULT = "task_result_v2"
        internal const val OLD_CHANNEL_RESULT = "task_result"
        /** The level of each notification channel when it is established; "need you" (takeover, question, confirmation) and results should pop up, and the progress should be kept quiet. If the user lowers the value later, the App cannot change it back. */
        internal val CHANNEL_IMPORTANCE = mapOf(
            CHANNEL_PROGRESS to NotificationManager.IMPORTANCE_LOW,
            CHANNEL_RESULT to NotificationManager.IMPORTANCE_HIGH,
            CHANNEL_ATTENTION to NotificationManager.IMPORTANCE_HIGH,
        )
        private const val ID_PROGRESS = 1
        internal const val ID_RESULT = 2
        private const val ID_ATTENTION = 3
        /** The resident notification of the page left in the background (there is only one page globally, regardless of slot). */
        private const val ID_KEPT = 4
        private const val REQUEST_CLOSE_KEPT = 99
        /** The task notification number and request code of the nth slot are increased by n × it; the first slot is the same as when only one task is run. */
        private const val SLOT_STRIDE = 100
        /** The number segment of the result notification in parallel is outside the slot number; it is used cyclically, and up to this many pieces can be left at the same time. */
        private const val ID_RESULT_FIRST = 10_000
        private const val RESULT_IDS = 1_000
        private val resultCount = AtomicInteger()
        private val TAKEOVER_QUEUED: String get() = tr(R.string.task_takeover_queued)
        private const val TAG = "MinaTask"
        val TOO_OLD: String get() = tr(R.string.task_too_old)
        internal val PREPARING: String get() = tr(R.string.runtime_preparing)
        val SHELL_DIED: String get() = tr(R.string.task_shell_died)

        /** Which of the loop's states can be written out by publishing. */
        internal fun loopMayPublish(state: TaskState) = state.isActive()

        /** Starts a quest; other dialogue quests are queued behind it when running but cannot be run at the same time. [conversationId] is empty to open a new conversation (see [conversationId] for number). */
        /** [messageId] If this is the case when the task is in progress, the interface will follow the direction it goes with this sentence. */
        fun start(context: Context, goal: String, conversationId: String? = null, messageId: String? = null) {
            context.startForegroundService(
                Intent(context, TaskService::class.java).setAction(ACTION_START).putExtra(EXTRA_GOAL, goal)
                    .apply { if (conversationId != null) putExtra(EXTRA_CONVERSATION, conversationId) }
                    .apply { if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId) },
            )
        }

        fun startTrigger(context: Context, goal: String, threadId: String, windowKey: String, conditionGoal: String?) {
            val checking = conditionGoal != null
            val runGoal = if (checking) "$conditionGoal\nRead-only check. At the end, output only JSON: {\"met\":true or false,\"evidence\":\"short supporting evidence\"}. If you cannot check, report failure; do not guess." else goal
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_TRIGGER)
                .putExtra(EXTRA_GOAL, runGoal).putExtra(EXTRA_CONVERSATION, threadId)
                .putExtra(EXTRA_TRIGGER_KEY, windowKey).putExtra(EXTRA_TRIGGER_THREAD, threadId)
                .apply { if (checking) putExtra(EXTRA_TRIGGER_ACTION, goal) })
        }

        /** The task handed over by the computer ([RemoteTaskHub]): recorded in the dialogue [conversation], the source is recorded as MCP. */
        fun startMcp(context: Context, goal: String, conversation: String, externalId: String, attendance: Attendance) {
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_MCP)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, conversation)
                .putExtra(EXTRA_MCP_ID, externalId).putExtra(EXTRA_ATTENDANCE, attendance.name))
        }

        fun cancelMcp(context: Context, externalId: String) {
            val state = (context.applicationContext as com.meenai.mina.HarnessApp).taskState
            if (state.runs.value.none { it.mcpExternalId == externalId }) return
            context.startService(Intent(context, TaskService::class.java).setAction(ACTION_CANCEL_MCP)
                .putExtra(EXTRA_MCP_ID, externalId))
        }

        /** [runKey] See [send]; the button in the app carries the task number of that conversation on the screen and will not fall to other tasks. */
        fun cancel(context: Context, runKey: String? = null) = send(context, ACTION_CANCEL, runKey = runKey)

        /** If the user clicks "Continue": the removed App will be moved back to Mina's screen. */
        fun resume(context: Context, runKey: String? = null) = send(context, ACTION_RESUME, runKey = runKey)

        /** The user pressed pause: Stop before changing the screen next and wait for "continue". */
        fun pause(context: Context, runKey: String? = null) = send(context, ACTION_PAUSE, runKey = runKey)

        /** "I'll handle it": Move the app to the home screen and restore its sound. */
        fun takeOver(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_TAKE_OVER, waitKey, runKey)

        /** "Handed": Move back to the virtual screen, mute the sound again, and the Agent will read the screen to verify before continuing. */
        fun handBack(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_HAND_BACK, waitKey, runKey)

        /** "I finished it myself": Move back to the virtual screen, the Agent only verifies the result and ends. */
        fun finishedMyself(context: Context, waitKey: Int? = null, runKey: String? = null) =
            send(context, ACTION_FINISHED_MYSELF, waitKey, runKey)

        /** "Skip": This step is not processed. The Agent will change the method or report truthfully after receiving "Return did not occur". */
        fun skip(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_SKIP, waitKey, runKey)

        fun putAside(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_PUT_ASIDE, waitKey, runKey)

        /** Answer the Agent's question (ask_user). */
        fun answer(context: Context, text: String, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, text)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** The declaration with approval number [key] ([ConfirmationNotifier.requestKey]) will not count if it has been changed to another declaration. */
        fun approve(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_APPROVE, key, runKey)

        /** Disagree: Agent will no longer try this operation and end after reporting the status quo. */
        fun decline(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_DECLINE, key, runKey)

        private fun confirm(context: Context, action: String, key: Int, runKey: String?) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).putExtra(ConfirmationNotifier.EXTRA_REQUEST, key)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** The status of the task at this moment; when the task is no longer available, it is Idle (the action is invalid). When [runKey] is empty, it is the task being displayed. */
        private fun stateFor(context: Context, runKey: String?): TaskState {
            val state = (context.applicationContext as com.meenai.mina.HarnessApp).taskState
            return if (runKey == null) state.state.value else state.runs.value.firstOrNull { it.key == runKey }?.state?.value ?: TaskState.Idle
        }

        /** When [runKey] is empty, it acts on the task being displayed, otherwise it only acts on the task. */
        private fun send(context: Context, action: String, waitKey: Int? = null, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).apply {
                    if (waitKey != null) putExtra(EXTRA_WAIT_KEY, waitKey)
                    if (runKey != null) putExtra(EXTRA_RUN, runKey)
                },
            )
        }

        internal fun TaskState.isActive() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        internal fun localizeVerificationNote(note: String): String = when {
            note.startsWith(AgentLoop.UNCONFIRMED_NOTE) -> tr(R.string.chat_unconfirmed_prefix) + " " + note.removePrefix(AgentLoop.UNCONFIRMED_NOTE).trim()
            note.startsWith("Unconfirmed:") -> tr(R.string.chat_unconfirmed_prefix) + " " + note.removePrefix("Unconfirmed:").trim()
            note == Verifier.PROTECTED -> tr(R.string.unverified_protected)
            note == Verifier.TIMEOUT -> tr(R.string.unverified_timeout)
            note == Verifier.UNPARSED -> tr(R.string.unverified_unparsed)
            note.startsWith(Verifier.ERROR_PREFIX) -> tr(R.string.unverified_error_prefix) + " " + note.removePrefix(Verifier.ERROR_PREFIX).trim()
            note == Verifier.NOT_IN_READS -> tr(R.string.unverified_not_in_reads)
            note == Verifier.NOT_EVERY_CRITERION -> tr(R.string.unverified_not_every_criterion)
            note == Verifier.FAIL_WITHOUT_REASON -> tr(R.string.unverified_fail_without_reason)
            note == Verifier.FOLLOW_UP_UNCLEAR -> tr(R.string.unverified_follow_up_unclear)
            note == Verifier.NOTHING_READ -> tr(R.string.unverified_nothing_read)
            note == Verifier.TRUNCATED -> tr(R.string.unverified_truncated)
            note == Verifier.VERIFYING -> tr(R.string.unverified_verifying)
            else -> note
        }

        /** The title and text of the notification; [appLabel] Replace the package name with the App name. */
        internal fun describe(state: TaskState, appLabel: (String) -> String = { it }): Pair<String, String> = when (state) {
            TaskState.Idle -> "Harness Agent" to ""
            is TaskState.Running -> tr(R.string.task_running) to (state.progress?.let(StatusCopy::progress) ?: when {
                state.step == 0 -> localizeVerificationNote(state.note)
                else -> tr(R.string.task_step_note, state.step, localizeVerificationNote(state.note)).trimEnd(':', ' ')
            })
            is TaskState.Held -> when {
                state.reason == TaskGate.LOCKED -> tr(R.string.task_wait_unlock) to tr(R.string.task_wait_unlock_body)
                state.reason.queued -> tr(R.string.chat_queued) to state.reason.why
                state.reason.resumable -> tr(R.string.chat_paused) to state.reason.why
                else -> tr(R.string.chat_waiting_for_you) to state.reason.why
            }
            is TaskState.WaitingTakeover -> tr(R.string.takeover_title) to state.message
            is TaskState.WaitingInput -> tr(R.string.input_title) to state.prompt
            is TaskState.WaitingConfirmation -> ConfirmationCopy.title(state.request) to ConfirmationCopy.body(state.request, appLabel(state.request.app))
            is TaskState.Succeeded ->
                if (!state.doubtful) tr(R.string.task_done) to state.answer
                else tr(R.string.chat_unverified) to (state.note?.let { tr(R.string.task_reason, localizeVerificationNote(it)) + "\n" }.orEmpty() + state.answer)
            is TaskState.Failed -> tr(R.string.task_not_done) to state.reason
            TaskState.Cancelled -> tr(R.string.task_stopped) to tr(R.string.task_stopped_body)
        }
    }
}
