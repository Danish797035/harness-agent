package com.meenai.mina.task

import android.graphics.Bitmap
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.ui.image.GeneratedImageUi
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.remote.RemoteSteer
import com.meenai.mina.takeover.Takeover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * If the user interrupts in the same conversation while the task is in progress: [Pending] has just been
 * sent and has not yet been sent to the task, [Received] has been accepted in the inbox of the task
 * (processed in the next step), [Joined] has been handed over to the model of the current task, [Next]
 * has ended the task, as the next thing, [Cancelled] has stopped or the remote interjection that was not
 * used at the end of the current task, [Withdrawn] has been withdrawn by the user.
 */
enum class SteerState { Pending, Received, Joined, Next, Cancelled, Withdrawn }

/** Interjections that have not yet been given to the model (when the task ends here, it will be regarded as the next thing, and when it is stopped, it will be discarded). */
val SteerState.unconsumed: Boolean get() = this == SteerState.Pending || this == SteerState.Received

/**
 * [interrupted] is the word that the user has seen in the reply after the interruption was removed;
 * [finished] is the reply that was displayed before the interruption was handed over to the model. Both
 * are displayed above the inset; null when not present.
 */
data class SteerNote(
    val id: String, val text: String, val state: SteerState, val interrupted: String? = null, val finished: String? = null,
    val remote: Boolean = false,
)

/** The computer only sees the destination of the message added by itself, without the words or text inserted on the mobile phone; the remote interrupt cannot be undone (`RunInbox.withdrawSteer`), [SteerState.Withdrawn] is just for exhaustion, so report it as useless. */
internal fun remoteSteers(notes: List<SteerNote>, finished: Boolean = false): List<RemoteSteer> = notes.filter { it.remote }.map {
    RemoteSteer(it.id, when (it.state) {
        SteerState.Pending, SteerState.Received -> if (finished) "not_applied" else "received"
        SteerState.Joined -> "joined"
        SteerState.Next, SteerState.Cancelled, SteerState.Withdrawn -> "not_applied"
    })
}

/**
 * In the running event, the interrupts are delivered to the model in the order: `reply_interrupted`
 * ([interrupted]) or `reply_finished_before_steer` ([finished]) comes first, followed by this batch of
 * interrupts one by one, `steer_received`, and that paragraph is only linked to the first sentence
 * ([joined]).
 */
class SteerJoin {
    @Volatile private var cut: String? = null
    @Volatile private var done: String? = null

    fun interrupted(text: String) { cut = text }

    fun finished(text: String) { done = text }

    /** The sentence [id] has been given to the model: mark it as [SteerState.Joined], and hang it if you have the word you just wrote down (it will be cleared after use). */
    fun joined(notes: List<SteerNote>, id: String): List<SteerNote> {
        val c = cut.also { cut = null }
        val d = done.also { done = null }
        return notes.map {
            if (it.id == id) it.copy(state = SteerState.Joined, interrupted = c ?: it.interrupted, finished = d ?: it.finished) else it
        }
    }
}

/** The display status of TaskCenter in the process is held by the App container, and the Service only writes to its host. */
class TaskCenterState {
    val shownLock = Any()
    @Volatile var shownRun: TaskRun? = null

    val _runs = MutableStateFlow<List<TaskRun>>(emptyList())
    val runs: StateFlow<List<TaskRun>> = _runs.asStateFlow()
    val _activeConversations = MutableStateFlow<List<String>>(emptyList())
    val activeConversations: StateFlow<List<String>> = _activeConversations.asStateFlow()
    val _shownTask = MutableStateFlow<Pair<String, TaskState>?>(null)
    val shownTask: StateFlow<Pair<String, TaskState>?> = _shownTask.asStateFlow()
    fun run(conversationId: String): TaskRun? = _runs.value.firstOrNull { it.conversationId == conversationId }
    fun show(conversationId: String) { run(conversationId)?.show() }

    @Volatile var secondDisplayFailed = false
    val _parallelLimit = MutableStateFlow(ParallelLimit(1, null))
    val parallelLimit: StateFlow<ParallelLimit> = _parallelLimit.asStateFlow()
    fun markSecondDisplayFailed() {
        secondDisplayFailed = true
        _parallelLimit.value = ParallelLimit(1, ParallelCapability.SECOND_DISPLAY_FAILED)
    }

    val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()
    val _activeModel = MutableStateFlow<String?>(null)
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()
    val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val _takeoverStage = MutableStateFlow(Takeover.Stage.None)
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.asStateFlow()
    val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()
    val _runId = MutableStateFlow<String?>(null)
    val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()
    val _generatedImages = MutableStateFlow<List<GeneratedImageUi>>(emptyList())
    val generatedImages: StateFlow<List<GeneratedImageUi>> = _generatedImages.asStateFlow()
    val _endedRunId = MutableStateFlow<String?>(null)
    val endedRunId: StateFlow<String?> = _endedRunId.asStateFlow()
    val _endedRunIds = MutableStateFlow<Map<String, String?>>(emptyMap())
    val endedRunIds: StateFlow<Map<String, String?>> = _endedRunIds.asStateFlow()
    /** Displays the activities of the task up to this moment (actually done, from running events). */
    val _activities = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.asStateFlow()
    /** Displays the interrupts received by the task in question and where they went. */
    val _steers = MutableStateFlow<List<SteerNote>>(emptyList())
    val steers: StateFlow<List<SteerNote>> = _steers.asStateFlow()
    /** The several runs (run numbers) in which instructions are being written after stopping; write them down in the dialog, or delete them if they cannot be written. */
    val _summarizing = MutableStateFlow<Set<String>>(emptySet())
    val summarizing: StateFlow<Set<String>> = _summarizing.asStateFlow()
    val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
}
