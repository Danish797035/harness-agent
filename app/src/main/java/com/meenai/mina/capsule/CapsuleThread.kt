package com.meenai.mina.capsule

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.core.agent.Conversation
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.PACKAGE_NAME_RE
import com.meenai.mina.ui.StatusCopy
import com.meenai.mina.ui.answerParts
import com.meenai.mina.task.SteerNote
import com.meenai.mina.ui.steerLabel
import com.meenai.mina.ui.steersAroundDraft

/**
 * An item in the conversation card. [key] remains stable within the same conversation (by run record
 * ID when available, otherwise by this turn's position in the conversation): the list uses it to hold
 * its scroll position when loading earlier turns above or saving the active turn.
 */
sealed interface ThreadItem {
    val key: String

    /** What you said (original words and words inserted during the task): the small bubble on the right; [note] is the destination of the line below the inserted words ("Received, next step"), if not, it is null. */
    data class User(override val key: String, val text: String, val note: String? = null) : ThreadItem

    /** A line of small print: a line of results from the round of operations on an app or web page ("✓ WeChat · Done"), or a line of pure chatting that failed, stopped, and is still thinking about it. */
    data class Note(override val key: String, val text: String, val kind: CapsuleKind) : ThreadItem

    /**
     * The full text of Mina's answer (original Markdown, formatted in the card); [streaming] is still
     * being written. [cut] is the section interrupted by interruption (the same light color as the
     * conversation page, with the following line "Interrupted by your new message").
     */
    data class Answer(override val key: String, val text: String, val streaming: Boolean = false, val cut: Boolean = false) : ThreadItem
}

/** The dialogue cards go back and forth, with the old ones at the top and the new ones at the bottom; when [hasEarlier] goes up, there are earlier rounds that have not been loaded; [title] is the title (first sentence) of the dialogue. */
data class ChatThread(val items: List<ThreadItem>, val hasEarlier: Boolean = false, val title: String? = null) {
    /** Which one to stop at when opening it: put the latest sentence you said at the top of the visible area, and start reading from this question and answer; if there is nothing you said, start from the beginning. */
    val anchor: Int get() = items.indexOfLast { it is ThreadItem.User }.coerceAtLeast(0)
}

/** A round of conversation has been recorded, together with the apps and websites it has operated ([places], read from the running record; empty when it is pure chat and the record has been cleared). */
data class PastTurn(val turn: Turn, val places: List<String> = emptyList())

/**
 * How to arrange the back and forth of dialogue cards (pure logic): The rounds that have been recorded
 * in the dialogue (the same [com.meenai.mina.chat.ConversationStore] as the dialogue page) are in the
 * front, and the tasks that have not yet been recorded in the dialogue at the moment are in the back.
 * For the round where the app was used, only one line and answer are left, leaving no steps, pictures
 * and sources. First, only the nearest [PAGE] wheel is arranged, then roll to the top and then move
 * forward to arrange more [PAGE] wheels.
 */
object CapsuleThreads {
    const val PAGE = 10

    /**
     * [past] is all the rounds recorded in this conversation, ranking the last [shown] round; [live] is
     * the task that has not been recorded (see [liveTail]), [livePlaces] gives the apps and websites
     * they have operated; [summarizing] is the several runs (run numbers) that are still being written
     * after stopping.
     */
    fun build(
        past: List<PastTurn>, shown: Int, live: List<CardRun>, summarizing: Set<String> = emptySet(),
        livePlaces: (CardRun) -> List<String> = { emptyList() },
    ): ChatThread {
        val from = (past.size - shown).coerceAtLeast(0)
        val items = (from until past.size).flatMap { i -> pastItems(i, past[i], summarizing) } +
            live.flatMap { liveItems(it, livePlaces(it), summarizing) }
        val title = past.firstOrNull()?.turn?.userText ?: live.firstOrNull()?.run?.goal
        return ChatThread(items, hasEarlier = from > 0, title = title?.takeIf(String::isNotBlank))
    }

    /**
     * The conversation in the card ([conversationId]) has not yet been recorded as a conversation task:
     * the recorded round has the same running record number ([recorded]), use the recorded copy; if the
     * record ends before starting (cancelled in the queue), only the recorded copy will be used. The
     * ones that are finished come first (those that finish first are at the top), and the ones that are
     * in progress are last.
     */
    fun liveTail(runs: List<CardRun>, conversationId: String, recorded: Set<String>): List<CardRun> {
        val mine = runs.filter { r ->
            r.run.conversationId == conversationId && (r.run.runId?.let { it !in recorded } ?: r.kind.active)
        }
        val (active, ended) = mine.partition { it.kind.active }
        return ended.reversed() + active
    }

    /** The round of writing down: what you said (and what you interrupted), the line when you operated the app, the answer or the reason why it was not done; the things that were stopped are the explanations that were added after the stop. */
    fun pastItems(index: Int, t: PastTurn, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val turn = t.turn
        val base = turn.runId?.let { "r$it" } ?: "p$index"
        val kind = when (turn.status) {
            "succeeded", "unverified" -> CapsuleKind.Done
            "cancelled" -> CapsuleKind.Cancelled
            else -> CapsuleKind.Failed
        }
        val answer = when (kind) {
            CapsuleKind.Done -> answerParts(turn.outcome).first
            CapsuleKind.Failed -> turn.outcome
            else -> Conversation.stopSummary(turn).orEmpty()
        }
        // The original words are followed by the interjected words; the reply that has been said before
        // the interjection and was interrupted by it is ranked in front of it (same as the conversation
        // page)
        val users = listOfNotNull(turn.userText.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) +
            turn.steers.withIndex().filter { it.value.isNotBlank() }.flatMap { (i, text) ->
                listOfNotNull(
                    turn.finishedReplies.getOrNull(i)?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-f${i + 1}", it) },
                    turn.interruptedReplies.getOrNull(i)?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-c${i + 1}", it, cut = true) },
                    ThreadItem.User("$base-u${i + 1}", text),
                )
            }
        return users + ended(base, kind, t.places, answer, pending = kind == CapsuleKind.Cancelled && turn.runId in summarizing)
    }

    /** One thing that has not yet been recorded in the conversation: write down the answer as it goes on, wait for you to answer and agree with the question, and end it as written down. */
    fun liveItems(r: CardRun, places: List<String>, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val run = r.run
        val base = run.runId?.let { "r$it" } ?: "l${run.key}"
        // The original words, and then the words inserted in progress (with the destination, the same
        // statement as the conversation page); the reply that has been finished before the interruption
        // and was interrupted by it is ranked in front of it. Interruptions that have not yet been
        // handed to the model are queued after the answer being written (same as the dialogue page, see
        // steersAroundDraft)
        fun steer(n: SteerNote) = listOfNotNull(
            n.finished?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-f${n.id}", it) },
            n.interrupted?.takeIf(String::isNotBlank)?.let { ThreadItem.Answer("$base-c${n.id}", it, cut = true) },
            ThreadItem.User("$base-s${n.id}", n.text, steerLabel(n.state)),
        )
        val (joined, waiting) = steersAroundDraft(run.steers)
        val user = listOfNotNull(run.goal.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) + joined.flatMap(::steer)
        val rest = when (val s = run.state) {
            is TaskState.Succeeded -> ended(base, CapsuleKind.Done, places, s.answer)
            is TaskState.Failed -> ended(base, CapsuleKind.Failed, places, s.reason)
            TaskState.Cancelled -> ended(base, CapsuleKind.Cancelled, places, "", pending = run.runId in summarizing)
            else -> {
                val note = if (places.isNotEmpty()) ThreadItem.Note("$base-n", appLine(places, r.kind), r.kind) else null
                val answer = when (val c = CapsuleCards.content(r)) {
                    is CardContent.Question -> ThreadItem.Answer("$base-a", c.prompt)
                    is CardContent.Confirm -> ThreadItem.Answer("$base-a", c.title + "\n" + c.body)
                    is CardContent.Takeover -> ThreadItem.Answer("$base-a", listOf(c.need, c.body).filter(String::isNotBlank).joinToString("\n"))
                    is CardContent.Held -> ThreadItem.Note("$base-a", c.why, r.kind)
                    else -> run.exchange?.draft?.takeIf(String::isNotBlank)
                        ?.let { ThreadItem.Answer("$base-a", it, streaming = true) }
                        // Haven’t written the words yet: Tell me what you are doing at the moment (that
                        // line has already been said in the app)
                        ?: ThreadItem.Note("$base-a", r.line, r.kind).takeIf { note == null && r.line.isNotBlank() }
                }
                listOfNotNull(note, answer)
            }
        }
        return user + rest + waiting.flatMap(::steer)
    }

    /**
     * The ending round: the line when the app was operated (pure chat only has a line when it is not
     * completed or stopped), and then the full text of the answer. When [pending], the explanation after
     * stopping is still written: the line below that line is "organizing what has been done...".
     */
    private fun ended(base: String, kind: CapsuleKind, places: List<String>, answer: String, pending: Boolean = false): List<ThreadItem> {
        val line = when {
            places.isNotEmpty() -> appLine(places, kind)
            kind == CapsuleKind.Failed -> CapsuleCards.status(kind)
            kind == CapsuleKind.Cancelled -> CapsuleCards.status(kind)
            else -> null
        }
        val text = answer.trim()
        return listOfNotNull(
            line?.let { ThreadItem.Note("$base-n", it, kind) },
            if (pending && text.isEmpty()) ThreadItem.Note("$base-s", StatusCopy.STOP_SUMMARIZING, kind) else null,
            text.takeIf(String::isNotEmpty)?.let { ThreadItem.Answer("$base-a", it) },
        )
    }

    /** A line for the harvest of the round in which the App was operated: "✓ WeChat, Taobao · Done" "12306 · Not done"; a maximum of two places can be listed. */
    fun appLine(places: List<String>, kind: CapsuleKind): String {
        val where = places.take(2).joinToString(tr(R.string.list_sep)) + if (places.size > 2) tr(R.string.and_more) else ""
        val mark = if (kind == CapsuleKind.Done) "✓ " else ""
        return "$mark$where · ${CapsuleCards.status(kind)}"
    }

    /** Apps and websites that have been actually operated in this round (the objects of the activity are package names or "website domain names") are deduplicated in order; the package names are replaced with App names. */
    fun places(acts: List<RunEvent.Activity>, appLabel: (String) -> String): List<String> =
        acts.mapNotNull { a ->
            val t = a.target?.trim().orEmpty()
            when {
                t.startsWith(SITE_PREFIX) -> t.removePrefix(SITE_PREFIX).trim().ifEmpty { null }
                PACKAGE_NAME_RE.matches(t) -> appLabel(t)
                else -> null
            }
        }.distinct()

    /** The object on the screen of the web page in the running record is written as "website <domain name>". */
    private const val SITE_PREFIX = "Site "
}
