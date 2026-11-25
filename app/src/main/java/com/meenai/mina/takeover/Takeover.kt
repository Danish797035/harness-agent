package com.meenai.mina.takeover

import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.tool.HandBack
import com.meenai.mina.core.tool.HandBackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Take over the device capabilities and Agent actions to be used; implemented by the task service according to the current task. -1 when the task number is invalid. */
interface TakeoverPort {
    /** Agent The top task on the virtual screen. */
    suspend fun agentTopTask(): Int
    /** The task and package opened by launch_app. */
    suspend fun targetTask(): Int
    fun targetPackage(): String?
    suspend fun packageOf(taskId: Int): String?
    /** The screen where the task is located; the task is no longer there (the page is closed after completing it) is -1. */
    suspend fun displayOf(taskId: Int): Int
    suspend fun moveToMain(taskId: Int): Boolean
    suspend fun moveBack(taskId: Int): Boolean
    /** Turn the app's sound on or off. */
    fun setSound(packageName: String, on: Boolean)
    /** Start counting the user's operations on the package on the home screen; called when the App is handed over to the user. */
    fun watchInput(packageName: String)
    /** The operation and page being counted at this moment; it is null when not counting. */
    suspend fun handoverActivity(): HandoverActivity?
    fun stopWatching()
    /** Return the end of the takeover to the Agent loop. */
    suspend fun resume(result: HandBack)
    fun cancel()

    /**
     * When the current interface is a web page, take the current tab (the same page) to the home screen
     * and give it to the user: true if successful, false if failed; if the web interface is not null,
     * follow the old path of moving tasks. [key] is the waiting number this time, and the button on the
     * web page takeover interface carries it.
     */
    suspend fun showWeb(reason: String, key: Int): Boolean? = null

    /** Put the web page tag back into the background, return the number of operations and page title during the takeover; when [loggedIn], record the current website as "user logged in". */
    suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? = null

    /** The web interface on the home screen is currently occupied (the login page of the settings page is open): [showWeb]. When it fails, the user will be told to "finish there first" instead of "the move failed". */
    fun webBusy(): Boolean = false
}

/**
 * Only one App is handed over to the user on the home screen at a time: when several tasks are requested
 * to be taken over at the same time, they are queued in order, and only the one at the top can be
 * "handled by me". The queued [Takeover]; [changes] of each task changes every time it enters and exits
 * the queue, and the task service redraws the notification accordingly. Those at the back of the queue
 * will not count the waiting time limit, and the waiting time will only start when the queue reaches the
 * front (see [Takeover.reachedFront]).
 */
class TakeoverQueue {
    private val waiting = mutableListOf<Takeover>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Synchronized fun join(owner: Takeover) {
        if (waiting.none { it === owner }) { waiting += owner; _changes.value++ }
    }

    fun leave(owner: Takeover) {
        val front = synchronized(this) {
            val wasFirst = waiting.firstOrNull() === owner
            if (!waiting.removeAll { it === owner }) return
            _changes.value++
            waiting.firstOrNull()?.takeIf { wasFirst }
        }
        front?.reachedFront()
    }

    /** Those that are not in the team (not waiting to take over) are also considered the front: they will not block others, nor will they be blocked by others. */
    @Synchronized fun isFirst(owner: Any): Boolean = waiting.indexOfFirst { it === owner } <= 0

    /** [owner] There are still several tasks waiting for the user to process; not in the queue is -1. */
    @Synchronized fun ahead(owner: Any): Int = waiting.indexOfFirst { it === owner }

    /** There are several tasks waiting for the user to process after [owner]. */
    @Synchronized fun behind(owner: Any): Int {
        val i = waiting.indexOfFirst { it === owner }
        return if (i < 0) 0 else waiting.size - 1 - i
    }
}

/**
 * The state machine of the takeover: Agent requests to take over → Wait → "I will handle it" and move
 * the top task of the virtual screen to the home screen → "Processed" / "I have done it myself" Move it
 * back and return it to the Agent; "Skip" and return it directly without processing (the App will be
 * moved back to the home screen first); You can "Cancel the task" at any time, and the App will stay
 * where it is when cancelled. When no one clicks "I will handle it", the handover will time out after
 * [TAKEOVER_WAIT_TIMEOUT_MS], and the App will not move; it will no longer time after taking over. The
 * timing runs on [scope], and the [now] measurement takes over the duration; the test is replaced by
 * virtual time. When [queue] is given and other tasks are queued for taking over, those at the back
 * cannot be "handled by me".
 */
class Takeover(
    private val port: TakeoverPort,
    private val scope: CoroutineScope,
    private val queue: TakeoverQueue? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Stage { None, Waiting, HandedOver }
    enum class Problem { MoveToMainFailed, MoveBackFailed, WebBusy }

    private val _stage = MutableStateFlow(Stage.None)
    val stage: StateFlow<Stage> = _stage.asStateFlow()
    private val _problem = MutableStateFlow<Problem?>(null)
    /** The reason for the failure of the latest move. It will be cleared after the next success. */
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    /** The App is in the user's hand (home screen): At this time, it is not on the virtual screen intentionally and is not considered "taken away by the user". */
    val handedOver: Boolean get() = _stage.value == Stage.HandedOver

    private val mutex = Mutex()
    /** The current wait, and the one that has been handed back; the same state cannot be re-entered when it is released again before the cycle goes down. */
    @Volatile private var current: TaskState.WaitingTakeover? = null
    @Volatile private var answered: TaskState.WaitingTakeover? = null
    /** How many times to wait; the notification button carries it, and if the clicked one is left over from the last wait, it will not count. */
    @Volatile var waitKey = 0
        private set
    /** What is handed over to the user is the web page tab (not the task of moving it to the home screen). */
    private var handedWeb = false
    /** Move the task and its package to the home screen. */
    private var handed = -1
    private var handedPackage: String? = null
    private var handedAt = 0L
    private var timer: Job? = null

    fun onState(state: TaskState) {
        when (state) {
            is TaskState.WaitingTakeover -> if (state !== answered && _stage.value == Stage.None) {
                current = state
                queue?.join(this)
                waitKey++
                _problem.value = null
                _stage.value = Stage.Waiting
                // Do not time it when it is queued behind the takeover of other tasks: the user has not
                // had time to look at it yet
                if (queue == null || queue.isFirst(this)) armTimer(state)
            }
            // When waiting for takeover, the loop stops there and no other status will be released; if
            // other status is released, it will be returned and then go down, or the task will end.
            else -> {
                stopTimer()
                if (_stage.value == Stage.HandedOver) port.stopWatching()
                current = null
                answered = null
                handedWeb = false
                queue?.leave(this)
                handed = -1
                handedPackage = null
                _stage.value = Stage.None
                _problem.value = null
            }
        }
    }

    /** "I'll handle it." */
    suspend fun handOver(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.Waiting || !keyMatches(key)) return false
        if (queue != null && !queue.isFirst(this)) return false
        // Mark first, then move: Monitors polling for "moved" may see this as soon as the move is
        // completed.
        _stage.value = Stage.HandedOver
        // Web interface: hold the same tab in front of your eyes without moving tasks or sound
        when (port.showWeb(current?.reason ?: "other", waitKey)) {
            true -> {
                handedWeb = true
                stopTimer()
                handedAt = now()
                _problem.value = null
                return true
            }
            false -> {
                _stage.value = Stage.Waiting
                _problem.value = if (port.webBusy()) Problem.WebBusy else Problem.MoveToMainFailed
                return false
            }
            null -> Unit
        }
        // Move the task at the top of the virtual screen: App often opens the login and payment pages in
        // another task
        val id = port.agentTopTask().takeIf { it >= 0 } ?: port.targetTask()
        if (id >= 0) {
            if (!port.moveToMain(id)) {
                _stage.value = Stage.Waiting
                _problem.value = Problem.MoveToMainFailed
                return false
            }
            handed = id
            handedPackage = port.packageOf(id) ?: port.targetPackage()
        }
        stopTimer()
        handedAt = now()
        _problem.value = null
        handedPackage?.let {
            port.setSound(it, true)
            port.watchInput(it)
        }
        true
    }

    /** "Processed": Agent reads the screen to verify before continuing. */
    suspend fun handBack(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete), key)

    /** "I finished it myself": Agent no longer operates, only verifies the results and ends. */
    suspend fun finishedMyself(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete, userFinished = true), key)

    /** "Skip": No processing, telling the Agent that no handover has occurred; if the App is moved back to the home screen first, it will remain in its original state if it cannot be moved back. */
    suspend fun skip(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.skipped), key)

    /**
     * Press the system return key on the web page takeover interface = "Put it aside first": the label
     * will be put back into the background, and the task will return to waiting for takeover (the
     * notification is still there, you can click "I will handle it" again), and the wait will be timed
     * as usual. Only valid if already taken over.
     */
    suspend fun putAside(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.HandedOver || !handedWeb || !keyMatches(key)) return false
        port.returnWeb(loggedIn = false)
        handedWeb = false
        _stage.value = Stage.Waiting
        current?.let(::armTimer)
        true
    }

    /** "Cancel task": If the App is on the home screen, it will stay on the home screen and will not be taken away from the user's eyes; the web page label will be returned to the background (the task label will then be closed). */
    suspend fun cancel() = mutex.withLock {
        stopTimer()
        if (handedWeb) {
            port.returnWeb(loggedIn = false)
            handedWeb = false
        }
        if (_stage.value == Stage.HandedOver) port.stopWatching()
        handed = -1
        handedPackage = null
        _stage.value = Stage.None
        _problem.value = null
        queue?.leave(this)
        port.cancel()
    }

    /** If the key (button in the application) is not brought, it will always be recognized; if it is brought, it must be the current waiting time. */
    private fun keyMatches(key: Int?) = key == null || key == waitKey

    private suspend fun giveBack(result: HandBack, key: Int?): Boolean = mutex.withLock {
        if (!keyMatches(key)) false else giveBackLocked(result)
    }

    private suspend fun giveBackLocked(given: HandBack): Boolean {
        val from = _stage.value
        if (from == Stage.None) return false
        var result = given
        if (from == Stage.HandedOver && handedWeb) {
            // Submitting (completed or done by myself) will be counted as the user has logged in to this
            // website; skipping will not be counted.
            val done = result.outcome == HandBackOutcome.complete
            val activity = port.returnWeb(loggedIn = done)
            if (done) result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            handedWeb = false
        } else if (from == Stage.HandedOver) {
            // You should check which page you are on before moving back: it will no longer be on the
            // home screen after moving back.
            if (result.outcome == HandBackOutcome.complete) {
                val activity = port.handoverActivity()
                result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            }
            // The handed-in task may have been completed and closed by itself (no need to move it if it
            // is no longer there); if the launch_app task is still on the home screen, it will also be
            // moved back.
            val target = port.targetTask()
            for (id in listOf(handed, target).filter { it >= 0 }.distinct()) {
                if (port.displayOf(id) == MAIN_DISPLAY && !port.moveBack(id)) {
                    _problem.value = Problem.MoveBackFailed
                    return false
                }
            }
            port.stopWatching()
            setOfNotNull(handedPackage, port.targetPackage()).forEach { port.setSound(it, false) }
            handed = -1
            handedPackage = null
        }
        stopTimer()
        _problem.value = null
        _stage.value = Stage.None
        answered = current
        queue?.leave(this)
        port.resume(result)
        return true
    }

    /** After the previous takeover is processed, it’s this one’s turn: the waiting time limit starts from now on. */
    internal fun reachedFront() {
        val waiting = current
        if (_stage.value == Stage.Waiting && waiting != null && timer == null) armTimer(waiting)
    }

    private fun armTimer(state: TaskState.WaitingTakeover) {
        timer?.cancel()
        timer = scope.launch {
            delay(TAKEOVER_WAIT_TIMEOUT_MS)
            mutex.withLock {
                // If you fail to move to the home screen and return to wait, it will time out as usual;
                // if you have taken over or changed to the next wait, it will not count.
                if (_stage.value == Stage.Waiting && current === state) {
                    timer = null // When returning, you cannot cancel the self you are returning.
                    giveBackLocked(HandBack(HandBackOutcome.timeout))
                }
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    companion object {
        private const val MAIN_DISPLAY = 0
        /** The time limit for waiting for the user to click "I will handle it"; there will be no time limit after taking over. */
        const val TAKEOVER_WAIT_TIMEOUT_MS = 15 * 60_000L
    }
}
