package com.meenai.mina.task

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.run.Channel
import com.meenai.mina.core.run.ChannelPeer
import com.meenai.mina.core.run.Delivery
import com.meenai.mina.core.run.DeliveryState
import com.meenai.mina.core.run.Outbox
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunPhase
import com.meenai.mina.core.run.RunStore
import com.meenai.mina.core.run.StoredRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** When restoring, you need to tell a few things about timing triggers; transfer to [TriggerRuntime] in the product. */
interface TriggerOutcomes {
    fun interrupted(key: String, reason: String)
    fun delivered(key: String)
    fun actionFailed(key: String, reason: String)
}

/**
 * The aftermath of "interrupted operation": resend existing replies, do not resend if the status is
 * unknown, otherwise inform "interrupted operation" and write it into the conversation. Two entrances:
 * [recover] (App startup and task service startup will be called, only done once in the process, and the
 * latter will do nothing; App starts before any service in the process, so no new runs of this process
 * will be misjudged when called for the first time) after the process is restarted, and
 * [settleInterrupted] when the task service is destroyed and the process is still there. **Never
 * continue running from the middle**: The screen state cannot be restored.
 *
 * There is no separate field for "Notified". Please refer to the delivery ledger: If there is no reply,
 * the prompt of `interrupted:<Run>` will be recorded. If the status is unknown or the delivery failed,
 * the corresponding prompt will be recorded; if there is a record, it will not be processed. Runs where
 * the reply is delivered are directly converted to Ended. Therefore, both entrances and multiple calls
 * are only informed once.
 */
class RunRecovery(
    private val store: RunStore,
    private val outbox: Outbox,
    private val channel: Channel,
    private val triggers: TriggerOutcomes,
    private val conversations: ConversationStore,
    private val scope: CoroutineScope,
) {
    private val done = AtomicBoolean(false)
    private val lock = Mutex()

    /** The marking is done synchronously here (newly opened runs will not be touched); the aftermath will be done slowly in [scope]. Returns the run marked as interrupted. If it is not the first call, it will be empty. */
    fun recover(live: Set<RunId> = emptySet()): List<StoredRun> {
        if (!done.compareAndSet(false, true)) return emptyList()
        val interrupted = store.interruptUnfinished(live)
        settleInterrupted()
        return interrupted
    }

    /**
     * Clean up all operations that have been interrupted and have not been notified. When the task
     * service is destroyed, it is called on the spot (the process is still there, and the user does not
     * have to wait until the next startup to know). [recover] at startup will also be scanned to catch
     * those that were killed on the spot before they were completed.
     */
    fun settleInterrupted() {
        scope.launch {
            lock.withLock {
                store.all().filter { it.phase == RunPhase.Interrupted && !told(it) }
                    .forEach { run -> runCatching { settle(run) } }
            }
        }
    }

    private fun noticeIds(run: StoredRun) =
        if (run.reply != null && run.deliveryId != null) listOf("uncertain:${run.deliveryId}", "failed:${run.deliveryId}")
        else listOf("interrupted:${run.id.value}")

    private fun told(run: StoredRun) = noticeIds(run).any { outbox.get(it) != null }

    private suspend fun settle(run: StoredRun) {
        run.triggerWindowKey?.let { triggers.interrupted(it, "run interrupted before the result was confirmed") }
        val peer = ChannelPeer(LocalChannel.ID, "phone-owner", run.threadId)
        val deliveryId = run.deliveryId
        val reply = run.reply
        if (reply != null && deliveryId != null) {
            val prior = outbox.get(deliveryId)
            val state = when (prior?.state) {
                null, DeliveryState.Pending -> outbox.deliver(channel, peer,
                    prior?.delivery ?: Delivery.Result(deliveryId, run.threadId, reply, restored = true, title = tr(R.string.recovered_result))).state
                DeliveryState.Attempting -> outbox.deliver(channel, peer, prior.delivery).state
                else -> prior.state
            }
            when (state) {
                DeliveryState.Delivered -> finish(run, reply)
                DeliveryState.Failed -> outbox.deliver(channel, peer, Delivery.Notice("failed:$deliveryId", run.threadId,
                    tr(R.string.recover_delivery_failed)))
                else -> outbox.deliver(channel, peer, Delivery.Notice("uncertain:$deliveryId", run.threadId,
                    tr(R.string.recover_maybe_delivered)))
            }
        } else {
            val turnId = "interrupted:${run.id.value}"
            if (conversations.turns(run.threadId).none { it.runId == turnId }) {
                conversations.append(run.threadId, Turn(run.goal, tr(R.string.recover_interrupted_turn), "failed", runId = turnId))
            }
            outbox.deliver(channel, peer, Delivery.Notice(turnId, run.threadId,
                tr(R.string.recover_interrupted, run.goal.take(40))))
        }
    }

    private fun finish(run: StoredRun, reply: String) {
        store.save(run.copy(phase = RunPhase.Ended, roundInProgress = false))
        run.triggerWindowKey?.let { key ->
            if (run.runSucceeded == false) triggers.actionFailed(key, reply) else triggers.delivered(key)
        }
    }
}
