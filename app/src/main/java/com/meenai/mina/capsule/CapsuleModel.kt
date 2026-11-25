package com.meenai.mina.capsule

import com.meenai.mina.R
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.core.agent.RunProgress
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.task.SteerNote
import com.meenai.mina.ui.Exchange

/** Choose one of the three options for "Suspended Capsule" in the settings; save it as a name, and when it is unrecognizable (never set it up, old version), the default is "Always show" (you can double-click to send this screen at any time). */
enum class CapsuleMode {
    Off, DuringTasks, Always;

    companion object {
        val DEFAULT = Always
        fun parse(name: String?): CapsuleMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * What a task looks like at the moment: number ([TaskRun.key]), dialogue location, status, and step to
 * take over; the card also needs its target [goal] and what the question and answer looks like at the
 * moment [exchange] (steps, what you are asked to do, results, and the same push method as the dialogue
 * page). can only hand over one App at a time); when it is not in the team or it is not known,
 * [queueAhead] is -1. At this time, it is calculated according to the order in which it starts to wait
 * for takeover. [runId] is its running record (the round recorded in the conversation has the same
 * number; it is null when the record has not been started). [onScreen] means that it is operating a
 * screen at the moment: it has its own virtual screen ([com.meenai.mina.task.TaskRun.displayId] is not
 * -1), or it is using the built-in browser ([com.meenai.mina.task.TaskRun.webOpen]); the card selects a
 * task card or a dialogue card accordingly. [steers] is the intervening words and destination
 * ([com.meenai.mina.task.TaskRun.steers]), and the back and forth of the dialogue card is followed by
 * the original words.
 */
data class CapsuleRun(
    val key: String,
    val conversationId: String,
    val state: TaskState,
    val stage: Takeover.Stage = Takeover.Stage.None,
    val goal: String = "",
    val exchange: Exchange? = null,
    val queueAhead: Int = -1,
    val queueBehind: Int = 0,
    val runId: String? = null,
    val onScreen: Boolean = false,
    val steers: List<SteerNote> = emptyList(),
)

/**
 * All inputs that determine what the capsule displays: each task ([runs], including those that have just
 * ended and the status is final), switches, whether Mina's own interface is in the foreground, the
 * screen is on and unlocked, the accessibility service is running and the overlay can be drawn, whether
 * the card is expanded ([cardOpen]), and the page left running in the background after the task is
 * completed ([kept]).
 */
data class CapsuleInputs(
    val runs: List<CapsuleRun>,
    val mode: CapsuleMode,
    val minaInForeground: Boolean,
    val screenInteractive: Boolean,
    val serviceAvailable: Boolean,
    val cardOpen: Boolean = false,
    val kept: KeptPage? = null,
)

/** The status of the capsules is arranged in order from high to low priority ([ordinal], the smaller ones are displayed first); [Idle] is the icon with no tasks when "always displayed". */
enum class CapsuleKind {
    HandedOver, NeedTakeover, NeedConfirm, NeedAnswer,
    /** It requires you to move your hands on the phone (such as selecting one in the system's selection box), and it will automatically continue after you move. */
    NeedStep,
    Held, Running, Done, Failed, Cancelled,
    /** There is no task running, and the page left after the task is running in the background ("Background web page is running", green dot). */
    KeptPage,
    Idle;

    /** I want you to deal with it (the capsule turns orange). The App has been handed over to you ([HandedOver]) is the one at the top and is not included. */
    val needsYou: Boolean get() = this == NeedTakeover || this == NeedConfirm || this == NeedAnswer || this == NeedStep

    /** It’s not over yet (running, waiting for you, etc.); [KeptPage] and [Idle] do not count. */
    val active: Boolean get() = this != Done && this != Failed && this != Cancelled && this != KeptPage && this != Idle
}

sealed interface CapsuleView {
    data object Hidden : CapsuleView

    /**
     * Display the capsule (or the item "You are processing"): [text] is the line of words ("N pieces in
     * total" have been spelled out), [detail] is only available when [CapsuleKind.HandedOver] is, and is
     * the small words below the item. [count] is the number of tasks in progress, and [needCount] is the
     * number of items you need to process. [runKey]/[conversationId] is what the capsule said. It is
     * usually a Mina icon (the status is drawn on the circle and corner mark), and when it is [pill], it
     * expands into a capsule with this line of text (when there is something you need to deal with and
     * it has just been completed). [widthEpoch] When the capsule remains unchanged, it will only become
     * wider but not narrower. If it changes (changes state or changes one thing), the width will be
     * re-set.
     */
    data class Shown(
        val kind: CapsuleKind,
        val text: String,
        val detail: String? = null,
        val count: Int = 0,
        val needCount: Int = 0,
        val runKey: String? = null,
        val conversationId: String? = null,
        val pill: Boolean = false,
        val widthEpoch: Int = 0,
    ) : CapsuleView
}

/**
 * The pure logic of the capsule: every time the input changes (or reaches [nextChangeAt]), adjust
 * [update] to get what should be displayed at this moment. rule:
 * - - Not displayed: the switch is off, accessibility services are unavailable, Mina's own interface is
 * in the foreground, the screen is off or locked;
 * - - Only the most important thing is displayed at a time, and the priority is shown in [CapsuleKind];
 * when there are several things, spell "N items in total", and those that need you to deal with will
 * turn orange: "1 item needs you to deal with · 2 items in total";
 * - - Usually it is an icon: when running, etc., it only changes on the circle and corner icon, and does
 * not expand after changing one step;
 * - - When there is something for you to deal with and the user has not seen it in the card, it expands
 * into a capsule and stays there until the card is opened or the thing no longer requires you;
 * The few things you see when you open the card (the same thing, the same waiting) are recorded as seen.
 * After you put them away, they return to one (orange), and they are expanded when new ones come;
 * - - Done / Not Done / Stopped First expand [PILL_MS], then retract one with the result icon, stop
 * [LINGER_MS] in total and then disappear (return to the icon when "always displayed");
 * What is seen in the card is directly one; when the card is unfolded, the clock is stopped during this
 * period: the result the user is looking at will not be taken away, and the remaining time will be
 * continued after putting away the card; what disappears after stopping will not come back (it does not
 * count if the caller still lists it); when the dialogue is deleted ([forget]), the completed task
 * disappears immediately;
 * - - There are no tasks and the results are stopped. However, when the page left after the task is
 * still running in the background, it expands to "Background webpage running" and remains stopped (it is
 * still not displayed when the switch is "Never show".
 * Notifications only);
 * - - When changing sentences within the same thing and the same state, pause at least [MIN_SENTENCE_MS]
 * for each sentence, leaving only the last sentence during this period; change immediately when changing
 * states or changing one thing.
 * Which card to choose in which order? See [cardRuns]. [reasonLabel] Replace the reason for takeover
 * (login, etc.) with a name that can be seen by others. If it is not recognized, it will be null. The
 * time is given by the caller, and a fake clock is used in the test.
 */
class CapsuleModel(private val reasonLabel: (String) -> String? = { null }) {
    private class Ended(val run: CapsuleRun, val at: Long)

    /** The moment one thing is waiting for: the same thing, the same state, and the same takeover stage count as one. */
    private data class Mark(val key: String, val state: TaskState, val stage: Takeover.Stage)

    /** The time when each task first appears: the same time is arranged in order, and the time is displayed on the card. */
    private val firstSeen = HashMap<String, Long>()
    private val ended = HashMap<String, Ended>()
    /** Ended tasks that are removed after stopping or deleting the dialogue: [ended] will not be recorded if the caller is still listed; [ended] will be forgotten if the caller is no longer listed. */
    private val dropped = HashSet<String>()
    /** For tasks that are waiting to be taken over or have already been taken over, follow the order in which you started waiting: only one task will be handed in at a time on the main screen, and the one in front will be processed first. */
    private val takeoverSince = LinkedHashMap<String, Long>()
    /** What the user has seen in the card requires you to process it and the result: no longer expands into a capsule. */
    private val seen = HashSet<Mark>()
    /** The moment when the card is expanded; tasks that end while the card is expanded will not count down. */
    private var frozenSince: Long? = null
    private var lastRuns: List<CapsuleRun> = emptyList()

    private var shownIdentity: Triple<CapsuleKind, String?, Boolean>? = null
    private var shownText: String? = null
    private var shownAt = 0L
    private var epoch = 0

    /** When the input does not change, the time when the output will change next time (waiting for the sentence to be changed, shrinking and disappearing after the completion); if not, it is null. */
    var nextChangeAt: Long? = null
        private set

    fun update(inputs: CapsuleInputs, now: Long): CapsuleView {
        nextChangeAt = null
        lastRuns = inputs.runs
        val keys = inputs.runs.map { it.key }.toSet()
        inputs.runs.forEach { firstSeen.getOrPut(it.key) { now } }
        if (inputs.cardOpen) {
            if (frozenSince == null) frozenSince = now
        } else frozenSince?.let { since ->
            // Put away the card: the time during which it is expanded does not count, the completed task
            // will continue with the remaining time
            val paused = now - since
            ended.replaceAll { _, e -> Ended(e.run, e.at + paused) }
            frozenSince = null
        }
        dropped.retainAll(keys)
        inputs.runs.filter { it.state.ended() && it.key !in dropped }.forEach { ended.getOrPut(it.key) { Ended(it, frozenSince ?: now) } }
        if (frozenSince == null) drop(ended.filterValues { now - it.at >= LINGER_MS }.keys)
        firstSeen.keys.retainAll(keys + ended.keys)
        val waiting = inputs.runs.filter { it.state is TaskState.WaitingTakeover && it.stage != Takeover.Stage.None }.map { it.key }
        takeoverSince.keys.retainAll(waiting.toSet())
        waiting.forEach { takeoverSince.getOrPut(it) { now } }
        // Only what you have seen remains; the card is unfolding and requires you to deal with it at
        // this moment, and the results are all considered seen.
        val marks = needMarks(inputs.runs) + ended.values.map { it.run.mark() }
        seen.retainAll(marks)
        if (inputs.cardOpen) seen += marks

        if (inputs.mode == CapsuleMode.Off || !inputs.serviceAvailable || inputs.minaInForeground || !inputs.screenInteractive) {
            // When it appears again, the latest sentence will be displayed directly without continuing
            // the previous throttling.
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        val candidate = activeView(inputs.runs) ?: endedView(now) ?: keptView(inputs.kept) ?: idleView(inputs.mode) ?: run {
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        return throttle(candidate, now)
    }

    /** Dialogue [conversationId] deleted: Its completed tasks will be removed from the capsule and card immediately, and the ongoing tasks will remain as before. */
    fun forget(conversationId: String) = drop(ended.filterValues { it.run.conversationId == conversationId }.keys)

    private fun drop(keys: Set<String>) {
        ended.keys.removeAll(keys)
        dropped += keys
    }

    private fun needMarks(runs: List<CapsuleRun>): Set<Mark> =
        runs.filter { it.state != TaskState.Idle && !it.state.ended() && describe(it).kind.needsYou }.map { it.mark() }.toSet()

    private fun CapsuleRun.mark() = Mark(key, state, stage)

    private fun activeView(runs: List<CapsuleRun>): CapsuleView.Shown? {
        val active = runs.filter { it.state != TaskState.Idle && !it.state.ended() }
        if (active.isEmpty()) return null
        val described = active.map { it to describe(it) }
        // Among the equally important things, let’s talk about the one at the front of the takeover team
        // first, and then let’s talk about the one that started first.
        val (top, line) = described.minWith(order)
        val count = active.size
        val needCount = described.count { it.second.kind.needsYou }
        var detail: String? = null
        val text = when {
            line.kind == CapsuleKind.HandedOver -> {
                val behind = spot(top)?.second ?: described.count { it.first !== top && it.second.kind == CapsuleKind.NeedTakeover }
                detail = if (behind > 0) trPlural(R.plurals.capsule_behind, behind)
                    else listOfNotNull(line.reason, tr(R.string.capsule_then_tap_right)).joinToString(" · ")
                line.text
            }
            count >= 2 && needCount > 0 -> tr(R.string.capsule_need_of_total, needCount, count)
            count >= 2 -> tr(R.string.capsule_of_total, line.text, count)
            else -> line.text
        }
        // There are things you haven't seen in the card yet that you need to deal with: unfold them into
        // capsules and keep them parked.
        val pill = line.kind.needsYou && described.any { (r, l) -> l.kind.needsYou && r.mark() !in seen }
        return CapsuleView.Shown(line.kind, text, detail, count, needCount, top.key, top.conversationId, pill = pill)
    }

    /** The one that ended recently: first unfolded into a capsule, and then retracted into a capsule with the result corner mark; what was seen in the card was directly a capsule; the watch was stopped while the card was unfolded. */
    private fun endedView(now: Long): CapsuleView.Shown? {
        val last = ended.values.maxByOrNull { it.at } ?: return null
        val age = (frozenSince ?: now) - last.at
        val pill = age < PILL_MS && last.run.mark() !in seen
        if (frozenSince == null) later(last.at + if (pill) PILL_MS else LINGER_MS)
        val (kind, text) = endedLine(last.run.state)
        return CapsuleView.Shown(kind, text, runKey = last.run.key, conversationId = last.run.conversationId, pill = pill)
    }

    private fun endedLine(s: TaskState): Pair<CapsuleKind, String> = when (s) {
        is TaskState.Succeeded -> CapsuleKind.Done to tr(R.string.capsule_done_tap)
        is TaskState.Failed -> CapsuleKind.Failed to tr(R.string.capsule_failed_tap)
        else -> CapsuleKind.Cancelled to tr(R.string.capsule_stopped)
    }

    /**
     * There are several things to choose from the card (for the last input of [update], see
     * [CapsuleCards.of]): the ongoing ones are arranged in order of importance (the equally important
     * ones that started first are in front), and the ones that have not disappeared are the ones that
     * have been completed (the most recently completed ones are in front). The time when the belt starts
     * when running, etc.; the number of pieces of belts that are waiting to be taken over are arranged
     * before and after it.
     */
    fun cardRuns(): List<CardRun> {
        val active = lastRuns.filter { it.state != TaskState.Idle && !it.state.ended() }
            .map { it to describe(it) }
            .sortedWith(order)
        val activeKeys = active.map { it.first.key }.toSet()
        return active.map { (r, line) ->
            val (ahead, behind) = spot(r) ?: (0 to 0)
            val timed = line.kind == CapsuleKind.Running || line.kind == CapsuleKind.Held
            CardRun(r, line.kind, line.text, startedAt = if (timed) firstSeen[r.key] else null, ahead = ahead, behind = behind)
        } + ended.values.filter { it.run.key !in activeKeys }.sortedByDescending { it.at }.map { e ->
            val (kind, text) = endedLine(e.run.state)
            CardRun(e.run, kind, text)
        }
    }

    /**
     * How a single thing looks in a card (without [update], not in order): Used when there is only one
     * thing followed elsewhere (the dialogue panel of that page on the browser page). The finished one
     * is said to be finished; the queue waiting to be taken over uses its own queue position.
     */
    fun cardRunOf(r: CapsuleRun): CardRun {
        if (r.state.ended()) {
            val (kind, text) = endedLine(r.state)
            return CardRun(r, kind, text)
        }
        val line = describe(r)
        return CardRun(r, line.kind, line.text, ahead = r.queueAhead.coerceAtLeast(0), behind = r.queueBehind)
    }

    /** The most important thing goes first; the equally important thing, the person at the front of the takeover team goes first, and then in the order of starting. */
    private val order = compareBy<Pair<CapsuleRun, Line>>(
        { it.second.kind.ordinal }, { spot(it.first)?.first ?: Int.MAX_VALUE }, { firstSeen[it.first.key] ?: 0L },
    )

    /** There are several items before and after the takeover queue: if there is a real queue position, use it, if not, press start and wait for the takeover sequence; if not, it will be null. */
    private fun spot(r: CapsuleRun): Pair<Int, Int>? {
        if (r.queueAhead >= 0) return r.queueAhead to r.queueBehind
        val queue = takeoverSince.keys.toList()
        val i = queue.indexOf(r.key)
        return if (i < 0) null else i to queue.size - 1 - i
    }

    /** The page left running in the background: expanded into a capsule that talks about the conversation it came from. */
    private fun keptView(kept: KeptPage?): CapsuleView.Shown? =
        kept?.let { CapsuleView.Shown(CapsuleKind.KeptPage, KEPT, conversationId = it.conversationId, pill = true) }

    private fun idleView(mode: CapsuleMode): CapsuleView.Shown? =
        if (mode == CapsuleMode.Always) CapsuleView.Shown(CapsuleKind.Idle, "") else null

    /**
     * For the same thing and in the same situation, change sentences: If it is less than
     * [MIN_SENTENCE_MS] from the previous sentence, keep the previous sentence first, and then change it
     * to the latest one at that time. Expanding and retracting also counts as changing a section, and
     * the width of the capsule is re-set.
     */
    private fun throttle(v: CapsuleView.Shown, now: Long): CapsuleView.Shown {
        val identity = Triple(v.kind, v.runKey, v.pill)
        if (identity != shownIdentity) {
            shownIdentity = identity
            shownText = v.text
            shownAt = now
            epoch++
            return v.copy(widthEpoch = epoch)
        }
        if (v.text != shownText) {
            if (now - shownAt >= MIN_SENTENCE_MS) {
                shownText = v.text
                shownAt = now
            } else {
                later(shownAt + MIN_SENTENCE_MS)
            }
        }
        return v.copy(text = shownText ?: v.text, widthEpoch = epoch)
    }

    private fun later(at: Long) {
        nextChangeAt = nextChangeAt?.coerceAtMost(at) ?: at
    }

    private class Line(val kind: CapsuleKind, val text: String, val reason: String? = null)

    private fun describe(r: CapsuleRun): Line = when (val s = r.state) {
        is TaskState.WaitingTakeover -> when (r.stage) {
            Takeover.Stage.HandedOver -> Line(CapsuleKind.HandedOver, HANDED_OVER, reasonLabel(s.reason))
            Takeover.Stage.Waiting -> Line(
                CapsuleKind.NeedTakeover,
                tr(R.string.capsule_needs_you, reasonLabel(s.reason) ?: oneLine(s.message).ifEmpty { tr(R.string.capsule_handle_it) }),
            )
            // The short section that has been handed back and the cycle has not yet gone down
            Takeover.Stage.None -> Line(CapsuleKind.Running, CONTINUING)
        }
        is TaskState.WaitingConfirmation -> Line(CapsuleKind.NeedConfirm, tr(R.string.capsule_needs_ok, ConfirmationCopy.actionLabel(s.request.action)))
        is TaskState.WaitingInput -> Line(CapsuleKind.NeedAnswer, tr(R.string.capsule_asks, oneLine(s.prompt)))
        is TaskState.Held -> if (s.reason.needsYou) Line(CapsuleKind.NeedStep, tr(R.string.capsule_needs_you, oneLine(s.reason.why).ifEmpty { tr(R.string.capsule_handle_it) }))
        else Line(CapsuleKind.Held, oneLine(s.reason.why).let { if (it.isEmpty()) tr(R.string.capsule_hold) else tr(R.string.capsule_hold_why, it) })
        is TaskState.Running -> Line(CapsuleKind.Running, runningText(s))
        else -> Line(CapsuleKind.Running, THINKING)
    }

    companion object {
        const val MIN_SENTENCE_MS = 1_500L
        /** How long does it take to expand into capsules after processing? */
        const val PILL_MS = 4_000L
        /** How long will it take to pause after finishing (the expanded section plus the retracted section with a corner mark). */
        const val LINGER_MS = 10_000L
        val HANDED_OVER: String get() = tr(R.string.capsule_you_handle)
        val THINKING: String get() = tr(R.string.capsule_thinking)
        val PREPARING: String get() = tr(R.string.runtime_preparing)
        val CONTINUING: String get() = tr(R.string.capsule_continuing)
        val KEPT: String get() = tr(R.string.kept_capsule)

        /** What we are doing at the moment: playback and batch execution of writing progress; otherwise, if we first use the model to write with the call, and then use the intention in the call, there is no fixed way. */
        fun runningText(s: TaskState.Running): String {
            s.progress?.let { return progressText(it) }
            val said = listOfNotNull(s.narration, s.reason).map(::oneLine).firstOrNull { it.isNotEmpty() }
            return said ?: if (s.step == 0) PREPARING else THINKING
        }

        fun progressText(p: RunProgress): String = when (p) {
            is RunProgress.Replay -> tr(R.string.capsule_replay, p.step, p.total)
            is RunProgress.Batch -> trPlural(R.plurals.capsule_batch, p.steps)
            is RunProgress.WaitingForApp -> tr(R.string.progress_waiting_app, p.app)
        }

        /** The words written by the model are pressed into one line. */
        private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

        private fun TaskState.ended() = this is TaskState.Succeeded || this is TaskState.Failed || this == TaskState.Cancelled
    }
}

/**
 * The transparency of the capsule: if an icon does not touch it, [IDLE_MS] will fade to [BALL] (the
 * circle and corner mark remain solid color and are managed by the interface); if it is touched or the
 * status changes, it will be restored immediately (the caller will restart the time from that moment).
 * When it is unfolded into a capsule, when it is asked to be handled by you, the line "you handle it" is
 * not weak.
 */
object CapsuleFade {
    const val IDLE_MS = 3_000L
    const val BALL = 0.4f

    /** [idleFor] is how long it has been since the last encounter or status change. */
    fun alpha(kind: CapsuleKind, needCount: Int, pill: Boolean, idleFor: Long): Float = when {
        pill || kind.needsYou || needCount > 0 || kind == CapsuleKind.HandedOver -> 1f
        idleFor < IDLE_MS -> 1f
        else -> BALL
    }
}
