package com.meenai.mina.capsule

import com.meenai.mina.R
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import com.meenai.mina.ui.StatusCopy

/**
 * One thing in the card: [kind]/[line] is consistent with the thing on the capsule; [startedAt] is there
 * when running and waiting for conditions (the time is displayed); [ahead]/[behind] is there are several
 * things in front of and behind it in the takeover team (only one App is handed in at a time on the main
 * screen).
 */
data class CardRun(
    val run: CapsuleRun,
    val kind: CapsuleKind,
    val line: String,
    val startedAt: Long? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

/**
 * What does the card show at the moment: The card only says one thing, not a list (how to pick
 * [CapsuleCards.of]). The thing that is operating the App or web page at the moment is the task card
 * ([Detail]), otherwise it is the conversation card ([Chat]) of that conversation; when there is no task
 * (the switch is "always displayed"), it is also the conversation card without tasks.
 */
sealed interface CardView {
    /** Task card: small screen, steps, things you need to do and buttons. */
    data class Detail(val run: CardRun, val content: CardContent) : CardView

    /**
     * Dialogue Card: [conversationId] The back and forth of that dialogue (see [ChatThread]). When [run]
     * is used, it is the dialogue where the thing is located, and [content] is what it looks like at the
     * moment (the button is fixed below the dialogue when you are asked to answer or agree). When there
     * is no task, it is an idle card: it follows the conversation the user is currently in ([current]),
     * and when there is no record, it follows the latest one ([title] is its title); when [fresh], it is
     * a new conversation; when there is no conversation, [conversationId] is null, and only a new
     * conversation can be opened.
     */
    data class Chat(
        val conversationId: String?,
        val run: CardRun? = null,
        val content: CardContent? = null,
        val title: String? = null,
        val fresh: Boolean = false,
        /**
         * The user is currently in this conversation in the App (see [CapsuleIdle]): the other
         * conversation has ended and is still hanging, so don’t grab the card. The card at the bottom of
         * the page "Continue recent conversations" is false.
         */
        val current: Boolean = false,
    ) : CardView {
        /** Which conversation is listed in the card, and which conversation will be entered if the message is sent: null when there is a new conversation on an idle card (a new one will be opened by the caller). */
        val target: String? get() = if (fresh) null else conversationId
    }

    /** The page left running in the background: real-time screen, how long it takes to close automatically, which conversation it comes from, "open conversation" and "close web page". There is no input box. */
    data class Kept(val page: KeptPage) : CardView
}

/** To whom should the words in the input box at the bottom of the card be given: interrupt running tasks, answer Mina's questions, continue the same conversation, and start talking when you are free. */
enum class CardInputKind { Steer, Answer, Continue, Start }

data class CardInput(val hint: String, val kind: CardInputKind)

/** The contents placed according to the status in the details; buttons are converted into existing actions of the task service. */
sealed interface CardContent {
    /** While working: the steps that have been done (at most [CapsuleCards.STEPS] steps), the last step is being done at the moment; it stops on the right side of the input box. */
    data class Working(val steps: List<String>) : CardContent

    /**
     * Want you to take over: [need] is the sentence "Need you:...", [body] is why (if written in the
     * model, add the product, amount, and address when paying); when it is ranked behind other
     * takeovers, [canTakeOver] is false, and [queueLine] indicates the order.
     */
    data class Takeover(val need: String, val body: String, val queueLine: String?, val canTakeOver: Boolean) : CardContent

    /** You are asked to agree to a money-spending operation (you can only agree to this once): the same set of words and number [key] as the confirmation card on the conversation page. */
    data class Confirm(val title: String, val body: String, val key: Int) : CardContent

    /** Mina asks you: If you give [options], it will be listed as a button; if there are no options, type the answer in Mina (the input in the card will be done next). */
    data class Question(val prompt: String, val options: List<String>) : CardContent

    /** Wait for conditions: why stop; give "continue" when [resumable]. */
    data class Held(val why: String, val resumable: Boolean) : CardContent

    /** Done/Not Done/Stopped: The answer is in the back and forth of the dialogue card. */
    data class Result(val kind: CapsuleKind) : CardContent
}

/** The pure logic of the card content: which item to choose, task card or dialogue card, what to put in each state, and which steps to take. */
object CapsuleCards {
    /** Take a few steps at most during work (just to limit the memory): the section in the card is as high as the small screen. Scroll up to see the old steps that cannot be placed. */
    const val STEPS = 50

    val QUEUED_AHEAD: String get() = tr(R.string.card_queued_ahead)

    /**
     * Which item is displayed when expanded (only one item, no list, and not switched in the card; if
     * there are other things at the same time, go to Mina's conversation page to see): First, the task
     * of the conversation ([follow]) that was just sent in the card (the priority in progress), so that
     * the user can see the answer in the card; otherwise, according to the order of [runs], take the
     * first item - [CapsuleModel.cardRuns] The ones that have been arranged for you to process (take
     * over, agree, answer) are first, then the ones in progress, and finally the ones that have just
     * ended and have not disappeared; when there are none, the page left after the task is still running
     * in the background ([kept]) is its card, and the remaining ones are [idle]. Whether the selected
     * item is a task card or a dialogue card is shown in [taskCard].
     *
     * When [idle] is the conversation ([CardView.Chat.current]) that the user is in at the moment, the
     * card will not be grabbed for results that have ended and are still pending in other conversations:
     * otherwise, if you open a new conversation on the conversation page and click on the capsule, you
     * will still see the result card of the previous conversation.
     */
    fun of(runs: List<CardRun>, follow: String? = null, idle: CardView.Chat? = null, kept: KeptPage? = null): CardView? {
        val pick = follow?.let { f ->
            runs.firstOrNull { it.run.conversationId == f && it.kind.active } ?: runs.firstOrNull { it.run.conversationId == f }
        } ?: runs.firstOrNull()
        // Only remove the item that "has ended in other conversations and is still hanging"; if there is
        // no item left, the page that remains will still be its card (see "kept")
        val elsewhere = idle?.current == true && pick != null && !pick.kind.active && pick.run.conversationId != idle.conversationId
        if (elsewhere) return idle
        pick ?: return kept?.let(CardView::Kept) ?: idle
        return if (taskCard(pick)) CardView.Detail(pick, content(pick))
            else CardView.Chat(pick.run.conversationId, pick, content(pick))
    }

    /**
     * Press the current status, do not press the process: those that have not yet ended and are
     * currently operating apps or web pages ([CapsuleRun.onScreen]) are task cards; others (pure chats,
     * those that have ended, including those who have operated apps and responded to conversations after
     * completing tasks) are conversation cards.
     */
    fun taskCard(r: CardRun): Boolean = r.kind.active && r.run.onScreen

    val HINT_STEER: String get() = tr(R.string.chat_placeholder_busy)
    val HINT_ANSWER: String get() = tr(R.string.card_hint_answer)
    val HINT_CONTINUE: String get() = tr(R.string.card_hint_continue)
    val HINT_START: String get() = tr(R.string.chat_empty_prompt)

    /** Is there an input box at the bottom of the card? Who should I hand it to? When I want you to take over or when I want your consent (deal with that matter first); the free card is the beginning. */
    fun input(card: CardView): CardInput? = when (card) {
        is CardView.Chat -> card.content?.let(::inputFor) ?: CardInput(HINT_START, CardInputKind.Start).takeIf { card.run == null }
        is CardView.Detail -> inputFor(card.content)
        is CardView.Kept -> null
    }

    private fun inputFor(content: CardContent): CardInput? = when (content) {
        is CardContent.Working, is CardContent.Held -> CardInput(HINT_STEER, CardInputKind.Steer)
        is CardContent.Question -> CardInput(HINT_ANSWER, CardInputKind.Answer)
        is CardContent.Result -> CardInput(HINT_CONTINUE, CardInputKind.Continue)
        is CardContent.Takeover, is CardContent.Confirm -> null
    }

    /**
     * The words in the card input box (together with the screenshot) enter which dialogue: the task card
     * and the dialogue card with the task are the dialogues where the thing is; the idle card is the
     * dialogue it follows, and it is null when a new dialogue is selected or there is no dialogue yet (a
     * new one is opened by the caller).
     */
    fun conversationOf(card: CardView): String? = when (card) {
        is CardView.Detail -> card.run.run.conversationId
        is CardView.Chat -> card.run?.run?.conversationId ?: card.target
        is CardView.Kept -> card.page.conversationId
    }

    /** When the task is still in progress (working, Mina asks you, etc.), which item should the stop key on the right side of the input box stop? Otherwise, it is null. */
    fun stopKey(card: CardView): String? {
        val (run, content) = when (card) {
            is CardView.Detail -> card.run to card.content
            is CardView.Chat -> (card.run ?: return null) to (card.content ?: return null)
            is CardView.Kept -> return null
        }
        val live = content is CardContent.Working || content is CardContent.Question || content is CardContent.Held
        return run.run.key.takeIf { live }
    }

    fun content(r: CardRun): CardContent {
        val ex = r.run.exchange
        return when (val s = r.run.state) {
            is TaskState.WaitingTakeover -> CardContent.Takeover(
                need = r.line,
                body = listOfNotNull(s.message.trim().ifEmpty { null }, StatusCopy.paymentLines(s.item, s.amount, s.address)).joinToString("\n"),
                queueLine = when {
                    r.ahead > 0 -> QUEUED_AHEAD
                    r.behind > 0 -> trPlural(R.plurals.capsule_behind, r.behind)
                    else -> null
                },
                canTakeOver = r.ahead == 0 && r.kind == CapsuleKind.NeedTakeover,
            )
            is TaskState.WaitingConfirmation -> (ex?.status as? ExchangeStatus.NeedsConfirmation)
                ?.let { CardContent.Confirm(it.title, it.body, it.key) }
                // Before this Q&A was launched (just started following), the package name was used
                // instead of the app name.
                ?: CardContent.Confirm(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, s.request.app),
                    ConfirmationNotifier.requestKey(s),
                )
            is TaskState.WaitingInput -> CardContent.Question(s.prompt.trim(), s.options.orEmpty().filter(String::isNotBlank))
            is TaskState.Held -> CardContent.Held(s.reason.why.trim().ifEmpty { tr(R.string.capsule_hold) }, s.reason.resumable)
            is TaskState.Succeeded -> CardContent.Result(CapsuleKind.Done)
            is TaskState.Failed -> CardContent.Result(CapsuleKind.Failed)
            TaskState.Cancelled -> CardContent.Result(CapsuleKind.Cancelled)
            else -> CardContent.Working(steps(ex, r.line))
        }
    }

    /** The most recent [STEPS] step (oldest first): what was actually done (the same statement as the steps on the dialogue page), the sentence you are doing at the moment is different from the last step, and comes after. */
    fun steps(ex: Exchange?, line: String): List<String> {
        val done = ex?.steps.orEmpty().map { it.text }
        val all = if (line.isNotBlank() && done.lastOrNull() != line) done + line else done
        return all.takeLast(STEPS)
    }

    /** The small print on the top of the card says what status the thing is in. */
    fun status(kind: CapsuleKind): String = when (kind) {
        CapsuleKind.HandedOver -> tr(R.string.capsule_handed_over)
        CapsuleKind.NeedTakeover -> tr(R.string.takeover_title)
        CapsuleKind.NeedConfirm -> tr(R.string.chat_wait_approval)
        CapsuleKind.NeedAnswer -> tr(R.string.chat_wait_answer)
        CapsuleKind.NeedStep -> tr(R.string.takeover_title)
        CapsuleKind.Held -> tr(R.string.capsule_hold)
        CapsuleKind.Running -> tr(R.string.capsule_running)
        CapsuleKind.Done -> tr(R.string.capsule_done)
        CapsuleKind.Failed -> tr(R.string.capsule_failed)
        CapsuleKind.Cancelled -> tr(R.string.capsule_stopped)
        CapsuleKind.KeptPage -> tr(R.string.kept_capsule)
        CapsuleKind.Idle -> "Harness Agent"
    }

    /** Whether to place a small virtual screen image next to this kind of content (it also needs to be a real picture): when working, asking you to take over, and waiting for conditions. */
    fun showsScreen(content: CardContent): Boolean =
        content is CardContent.Working || content is CardContent.Takeover || content is CardContent.Held
}
