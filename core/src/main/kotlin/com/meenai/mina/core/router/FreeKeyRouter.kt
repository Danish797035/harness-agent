package com.meenai.mina.core.router

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class RouterCandidate(
    val id: String,
    val provider: String,
    val model: ChatModel,
    val curatedRank: Int = 0,
    val isFree: Boolean = true,
)

typealias AllLimitsExhaustedException = ModelError.AllLimitsExhausted

/**
 * Free Key Router manages a pool of free LLM models, transparently handling rate limits,
 * quota exhaustion, and provider failures with seamless step-boundary failover.
 *
 * Key guarantees:
 * 1. Sticky model selection per task (does not switch without cause).
 * 2. Proactive avoidance when quota < 2 requests remaining.
 * 3. Step-boundary seamless switching (mid-stream drops are discarded and cleanly replayed).
 * 4. Context compaction when falling back to models with smaller context windows.
 * 5. Max 6 switches per task limit with user-friendly cooldown duration message.
 */
class FreeKeyRouter(
    val candidates: List<RouterCandidate>,
    val cooldownStore: ModelCooldownStore = ModelCooldownStore(),
    val healthTracker: ModelHealthTracker = ModelHealthTracker(),
    val maxSwitchesPerTask: Int = 6,
    val firstTokenTimeoutMs: Long = 30_000L,
    val switchBackoffMs: Long = 500L,
    val now: () -> Long = System::currentTimeMillis,
    val delayMs: suspend (Long) -> Unit = { delay(it) },
    val onModelSwitched: ((oldModel: String?, newModel: String, reason: String) -> Unit)? = null,
) : StreamingChatModel {

    var activeCandidate: RouterCandidate? = null
        private set

    var switchCountInTask: Int = 0
        private set

    override val name: String
        get() = activeCandidate?.id ?: candidates.firstOrNull()?.id ?: "FreeKeyRouter"

    override val capabilities: Capabilities
        get() = activeCandidate?.model?.capabilities ?: candidates.firstOrNull()?.model?.capabilities ?: Capabilities(
            toolCalls = true,
            vision = true,
            contextTokens = 64_000,
            reasoning = false,
            promptCaching = PromptCaching.Implicit,
        )

    /**
     * Resets task-specific switch count. Keeps the active candidate if still healthy and available.
     */
    fun resetTask() {
        switchCountInTask = 0
        val current = activeCandidate
        if (current != null && !cooldownStore.isAvailable(current.id, current.provider, now(), isNewTask = true)) {
            activeCandidate = null
        }
    }

    fun pinModel(modelId: String) {
        val found = candidates.find { it.id == modelId }
        if (found != null) {
            activeCandidate = found
        }
    }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
    ): ModelReply = completeStreaming(system, messages, tools, onText = {})

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply {
        var lastError: Throwable? = null

        while (true) {
            if (switchCountInTask >= maxSwitchesPerTask) {
                val waitMin = calculateWaitMinutes()
                throw AllLimitsExhaustedException("Sab free limits khatam, $waitMin minute baad try karo", waitMin)
            }

            // Ensure active candidate
            if (activeCandidate == null || !cooldownStore.isAvailable(activeCandidate!!.id, activeCandidate!!.provider, now())) {
                val next = pickBestCandidate(isNewTask = (switchCountInTask == 0))
                if (next == null) {
                    val waitMin = calculateWaitMinutes()
                    throw AllLimitsExhaustedException("Sab free limits khatam, $waitMin minute baad try karo", waitMin)
                }
                val prev = activeCandidate?.id
                activeCandidate = next
                if (prev != null && prev != next.id) {
                    onModelSwitched?.invoke(prev, next.id, "switch")
                }
            }

            val currentCandidate = activeCandidate!!
            val beganTime = now()

            // Prepare history & tools for target model
            val contextLimit = currentCandidate.model.capabilities.contextTokens
            val compactedMessages = HistoryCompactor.compact(messages, contextLimit)
            val cleanMessages = ProviderConverter.stripForeignThinking(compactedMessages, preserveOpaqueFor = currentCandidate.id)

            val isGemini = currentCandidate.provider.equals("gemini", ignoreCase = true) ||
                currentCandidate.id.contains("gemini", ignoreCase = true)
            var preparedTools = if (isGemini) {
                tools.map { it.copy(parametersJsonSchema = SchemaSanitizer.sanitizeForGemini(it.parametersJsonSchema)) }
            } else {
                tools
            }

            var schemaFixAttempted = false

            while (true) {
                try {
                    val reply = executeWithWatchdog(currentCandidate.model, system, cleanMessages, preparedTools, onText)
                    val latency = now() - beganTime
                    healthTracker.recordSuccess(currentCandidate.id, latency)
                    return reply
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    healthTracker.recordFailure(currentCandidate.id)

                    val action = RouterErrorClassifier.classify(e)

                    when (action) {
                        RouterAction.STOP -> {
                            // Safety or refusal: do not switch, report to user
                            throw e
                        }
                        RouterAction.FIX_THEN_SWITCH -> {
                            if (!schemaFixAttempted) {
                                schemaFixAttempted = true
                                preparedTools = preparedTools.map {
                                    it.copy(parametersJsonSchema = SchemaSanitizer.sanitizeGeneral(it.parametersJsonSchema))
                                }
                                continue // retry once on same candidate with sanitized schema
                            } else {
                                // Already retried with sanitized schema, switch candidate
                                cooldownStore.markCooldown(currentCandidate.id, ModelCooldownStore.DEFAULT_COOLDOWN_MS, now())
                                onText("")
                                activeCandidate = null
                                switchCountInTask++
                                delayMs(switchBackoffMs)
                                break
                            }
                        }
                        RouterAction.KEY_BAD -> {
                            cooldownStore.markBadKey(currentCandidate.provider)
                            onText("")
                            activeCandidate = null
                            switchCountInTask++
                            delayMs(switchBackoffMs)
                            break
                        }
                        RouterAction.CONTEXT -> {
                            // Pick candidate with larger context if possible, or compact further
                            cooldownStore.markCooldown(currentCandidate.id, 10_000L, now())
                            onText("")
                            activeCandidate = null
                            switchCountInTask++
                            delayMs(switchBackoffMs)
                            break
                        }
                        RouterAction.SWITCH -> {
                            val isQuota = (e is ModelError.QuotaExceeded) ||
                                (e.message?.contains("insufficient_quota", ignoreCase = true) == true) ||
                                (e.message?.contains("quota", ignoreCase = true) == true)
                            if (isQuota) {
                                cooldownStore.markDailyQuotaExhausted(currentCandidate.id, currentCandidate.provider, now())
                            } else {
                                val retryAfter = (e as? ModelError.RateLimited)?.retryAfterMs ?: ModelCooldownStore.DEFAULT_COOLDOWN_MS
                                cooldownStore.markCooldown(currentCandidate.id, retryAfter, now())
                            }
                            onText("")
                            activeCandidate = null
                            switchCountInTask++
                            delayMs(switchBackoffMs)
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun executeWithWatchdog(
        model: ChatModel,
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply {
        if (firstTokenTimeoutMs <= 0L) {
            return if (model is StreamingChatModel) {
                model.completeStreaming(system, messages, tools, onText)
            } else {
                model.complete(system, messages, tools).also { reply ->
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
                }
            }
        }

        return coroutineScope {
            val firstTokenArrived = AtomicBoolean(false)
            val watchdog = launch {
                delay(firstTokenTimeoutMs)
                if (!firstTokenArrived.get()) {
                    throw FirstTokenTimeoutException("First token did not arrive within ${firstTokenTimeoutMs}ms")
                }
            }

            try {
                if (model is StreamingChatModel) {
                    model.completeStreaming(system, messages, tools) { chunk ->
                        if (chunk.isNotEmpty() && !firstTokenArrived.getAndSet(true)) {
                            watchdog.cancel()
                        }
                        onText(chunk)
                    }
                } else {
                    val reply = model.complete(system, messages, tools)
                    firstTokenArrived.set(true)
                    watchdog.cancel()
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
                    reply
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun pickBestCandidate(isNewTask: Boolean): RouterCandidate? {
        return candidates
            .filter { cooldownStore.isAvailable(it.id, it.provider, now(), isNewTask = isNewTask) }
            .sortedWith(
                compareByDescending<RouterCandidate> { it.curatedRank }
                    .thenByDescending { healthTracker.score(it.id) }
                    .thenByDescending { it.model.capabilities.contextTokens }
            )
            .firstOrNull()
    }

    private fun calculateWaitMinutes(): Int {
        val minWaitMs = candidates
            .map { cooldownStore.getRemainingCooldownMs(it.id, now()) }
            .filter { it > 0L }
            .minOrNull() ?: ModelCooldownStore.DEFAULT_COOLDOWN_MS
        return ceil(minWaitMs / 60_000.0).toInt().coerceAtLeast(1)
    }
}
