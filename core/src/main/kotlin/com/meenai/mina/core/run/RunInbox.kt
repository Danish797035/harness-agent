package com.meenai.mina.core.run

import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.core.tool.HandBack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** The only entrance for input during operation; the user's original words are queued indefinitely and retained verbatim, and the stop signal is delivered directly. */
class RunInbox {
    data class UserSteer(val messageId: String, val text: String, val currentTaskOnly: Boolean = false)

    internal interface Handler {
        suspend fun answer(text: String, askId: String?): Boolean
        suspend fun confirm(choice: ConfirmChoice, askId: String?): Boolean
        suspend fun handBack(result: HandBack, askId: String?): Boolean
        fun cancel()
    }

    private val lock = Any()
    private val steers = ArrayDeque<UserSteer>()
    @Volatile private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var stopRequested = false
    private var remaining: List<UserSteer> = emptyList()
    /** Each interrupt wakes up a waiting model request (cancel it and ask again with this sentence); executing tools are not affected. */
    internal val redirects = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow(false)
    /** There is an interjection that has not yet been handed over to the model: the waiting will end early without losing anything if it is removed (see [waitOrSteer]). */
    val signal = SteerSignal(pending)

    internal fun bind(to: Handler) {
        handler = to
        if (stopRequested) to.cancel()
    }

    val wasCancelled: Boolean get() = stopRequested

    fun cancel(): InputReceipt {
        val to = synchronized(lock) {
            if (closed) return InputReceipt.Closed
            stopRequested = true
            handler
        }
        to?.cancel()
        return InputReceipt.Accepted
    }

    /** Remote append only belongs to the current task; it is mutually exclusive with close and cancel. If rejected, it will not be transferred to the next task. */
    fun steerCurrent(messageId: String, text: String): InputReceipt = synchronized(lock) {
        if (closed || stopRequested) InputReceipt.Closed
        else enqueue(UserSteer(messageId, text, currentTaskOnly = true))
    }

    suspend fun send(input: RunInput): InputReceipt = when (input) {
        is RunInput.Steer -> steer(input.messageId, input.text)
        is RunInput.Redirect -> steer(input.messageId, input.text)
        RunInput.Cancel -> cancel()
        is RunInput.Answer -> if (closed) InputReceipt.Closed else
            if (handler?.answer(input.text, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.Confirm -> if (closed) InputReceipt.Closed else
            if (handler?.confirm(input.choice, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.HandBack -> if (closed) InputReceipt.Closed else
            if (handler?.handBack(input.result, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
    }

    fun steer(id: String, text: String): InputReceipt = synchronized(lock) {
        if (closed) InputReceipt.NextTask else enqueue(UserSteer(id, text))
    }

    private fun enqueue(steer: UserSteer): InputReceipt {
        steers += steer
        pending.value = true
        redirects.trySend(Unit)
        return InputReceipt.Accepted
    }

    /** Only withdraw messages that are still in the inbox or inserted on the phone; and hand over to the model and close are mutually exclusive. ([UserSteer.currentTaskOnly]) added by the computer cannot be withdrawn and is determined by the computer. */
    fun withdrawSteer(id: String): Boolean = synchronized(lock) {
        val i = steers.indexOfFirst { it.messageId == id }
        if (closed || i < 0 || steers[i].currentTaskOnly) return@synchronized false
        steers.removeAt(i)
        pending.value = steers.isNotEmpty()
        if (steers.isEmpty()) while (redirects.tryReceive().isSuccess) Unit
        true
    }

    /** Are there any interruptions that have not yet been given to the model? This is only ignored (batch execution stops accordingly between sub-steps). */
    fun hasSteers(): Boolean = synchronized(lock) { steers.isNotEmpty() }

    /** Only taken before a model call or after a batch of tools has completed; clear redirect wakeups that are no longer needed. */
    internal fun takeSteers(): List<UserSteer> = synchronized(lock) {
        val out = steers.toList()
        steers.clear()
        pending.value = false
        while (redirects.tryReceive().isSuccess) Unit
        out
    }

    /** After finishing, any unconsumed comments are handed over to the host as the next task. */
    internal fun close(): List<UserSteer> = synchronized(lock) {
        if (closed) return@synchronized remaining
        closed = true
        handler = null
        redirects.close()
        pending.value = false
        remaining = steers.toList().also { steers.clear() }
        remaining
    }

    /**
     * Called when the answer is to be handed over and the end is complete: if there are still
     * interruptions that have not been handed over to the model, close them (return false, and the
     * caller will continue with it); if there are no interruptions, close them (return true), and
     * subsequent interruptions will become the next task. Watching and being locked up in the same lock,
     * there is no way to say anything in between.
     */
    internal fun closeUnlessSteered(): Boolean = synchronized(lock) {
        if (steers.isNotEmpty()) return@synchronized false
        close()
        true
    }

    fun nextTaskMessages(): List<UserSteer> = synchronized(lock) { remaining.toList() }

    /** When the host ends or cancels a task that has not yet started, the unconsumed user's original words will be handed over to the next task. */
    fun handoffUnconsumed(): List<UserSteer> = synchronized(lock) {
        close()
        remaining.also { remaining = emptyList() }
    }
}
