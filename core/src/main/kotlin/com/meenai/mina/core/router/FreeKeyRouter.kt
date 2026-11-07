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
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.router.picker.AutoModelPicker
import com.meenai.mina.core.router.picker.CuratedTiers
import com.meenai.mina.core.router.picker.DifficultyRouter
import com.meenai.mina.core.router.picker.ModelCandidate
import com.meenai.mina.core.router.picker.ModelRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class RouterCandidate(
    val id: String,
    val provider: String,
    val model: ChatModel,
    val curatedRank: Int = 0,
    val isFree: Boolean = true,
    val isBackup: Boolean = provider.equals("modelscope", ignoreCase = true),
    val roles: Set<ModelRole> = emptySet(),
    val candidateInfo: ModelCandidate? = null,
) {
    fun toModelCandidate(): ModelCandidate {
        candidateInfo?.let { return it }
        val caps = model.capabilities
        return ModelCandidate(
            provider = provider,
            id = id,
            contextTokens = caps.contextTokens,
            supportsTools = caps.toolCalls,
            supportsImages = caps.vision,
            isFree = isFree,
            tierRank = curatedRank,
            parameterSizeB = CuratedTiers.extractParameterSizeB(id),
        )
    }
}

typealias AllLimitsExhaustedException = ModelError.AllLimitsExhausted

/**
 * Free Key Router manages a pool of free LLM models, transparently handling rate limits,
 * quota exhaustion, provider failures, and difficulty routing with seamless step-boundary failover.
 *
 * Key guarantees:
 * 1. Sticky model selection per task (does not switch without cause).
 * 2. Proactive avoidance when quota < 2 requests remaining.
 * 3. Step-boundary seamless switching (mid-stream drops are discarded and cleanly replayed).
 * 4. Tool calls from prior steps or failed partial generations are never double-executed.
 * 5. Max 6 switches per task limit with user-friendly cooldown duration message.
 * 6. ModelScope is lowest-priority backup (only used when all other providers in cooldown).
 * 7. Pinned model bypasses routing.
 * 8. StateFlow<RouterStatus> exposed for live UI tracking.
 */
class FreeKeyRouter(
    var candidates: List<RouterCandidate>,
    val cooldownStore: ModelCooldownStore = ModelCooldownStore(),
    val healthTracker: ModelHealthTracker = ModelHealthTracker(),
    val difficultyRouter: DifficultyRouter? = null,
    val maxSwitchesPerTask: Int = 6,
    val firstTokenTimeoutMs: Long = 30_000L,
    val switchBackoffMs: Long = 500L,
    val now: () -> Long = System::currentTimeMillis,
    val delayMs: suspend (Long) -> Unit = { delay(it) },
    /** When every candidate is cooling down, wait inline (user just sees loading) if the shortest cooldown is at most this long. */
    val maxInlineWaitMs: Long = 15_000L,
    val onModelSwitched: ((oldModel: String?, newModel: String, reason: String) -> Unit)? = null,
) : StreamingChatModel {

    var activeCandidate: RouterCandidate? = null
        private set

    var switchCountInTask: Int = 0
        private set

    var pinnedModelId: String? = difficultyRouter?.pinnedModelId
        private set

    private val _status = MutableStateFlow(RouterStatus())
    val status: StateFlow<RouterStatus> = _status.asStateFlow()

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
        if (current != null && !checkCachedQuota(current, isNewTask = true)) {
            activeCandidate = null
        }
        updateStatus(
            model = activeCandidate?.id,
            provider = activeCandidate?.provider,
            reason = "Task reset",
        )
    }

    /**
     * Initializes a new task with difficulty classification.
     */
    fun startTask(
        task: String,
        hasImage: Boolean = false,
        plannedSteps: Int? = null,
    ) {
        switchCountInTask = 0
        val difficulty = difficultyRouter?.startTask(task, hasImage, plannedSteps)
        val role = difficultyRouter?.getRole(hasImage) ?: if (hasImage) ModelRole.Vision else ModelRole.Main

        val current = activeCandidate
        if (current == null || !checkCachedQuota(current, isNewTask = true)) {
            activeCandidate = pickBestCandidate(role, isNewTask = true)
        }
        updateStatus(
            model = activeCandidate?.id,
            provider = activeCandidate?.provider,
            reason = "Task started (${difficulty ?: "Default"})",
        )
    }

    /**
     * Called before each step in the agent loop.
     * Evaluates difficulty router escalation, vision requirements, and cached quota.
     * Switches only at step boundaries if necessary.
     */
    fun prepareStep(step: Int, hasImages: Boolean = false) {
        val role = difficultyRouter?.getRole(hasImages) ?: if (hasImages) ModelRole.Vision else ModelRole.Main
        val current = activeCandidate

        val needsSwitch = current == null ||
            !checkCachedQuota(current, isNewTask = false) ||
            (hasImages && !current.model.capabilities.vision) ||
            (difficultyRouter?.isEscalated == true && current.roles.contains(ModelRole.Light) && !current.roles.contains(ModelRole.Main))

        if (needsSwitch) {
            val next = pickBestCandidate(role, isNewTask = false)
            if (next != null && next.id != current?.id) {
                val oldId = current?.id
                activeCandidate = next
                val reason = if (difficultyRouter?.isEscalated == true) "Escalated to Heavy" else "Step boundary check"
                onModelSwitched?.invoke(oldId, next.id, reason)
                updateStatus(
                    model = next.id,
                    provider = next.provider,
                    reason = reason,
                    switchEvent = RouterSwitchEvent(oldId, next.id, next.provider, reason, now()),
                )
            }
        }
    }

    /**
     * Records a step action signature (e.g. "click:{x:100,y:200}") to detect repeated actions
     * and escalate to Heavy if repeating.
     */
    fun recordStepAction(toolSignature: String) {
        difficultyRouter?.recordStep(toolSignature)
    }

    fun pinModel(modelId: String) {
        pinnedModelId = modelId
        val found = candidates.find { it.id == modelId }
        if (found != null) {
            activeCandidate = found
            updateStatus(
                model = found.id,
                provider = found.provider,
                reason = "Pinned model",
            )
        }
    }

    fun unpinModel() {
        pinnedModelId = null
    }

    fun updateCandidates(newCandidates: List<RouterCandidate>) {
        candidates = newCandidates
        val active = activeCandidate
        if (active != null && newCandidates.none { it.id == active.id }) {
            activeCandidate = null
        }
        updateStatus(
            model = activeCandidate?.id,
            provider = activeCandidate?.provider,
            reason = "Candidates updated",
        )
    }

    /**
     * Checks cached quota and cooldown status in-memory with zero network calls (< 50ms).
     * Skips providers/models in cooldown or with bad keys.
     */
    fun checkCachedQuota(candidate: RouterCandidate, isNewTask: Boolean = false): Boolean {
        if (cooldownStore.isKeyBad(candidate.provider)) return false
        val remainingCooldown = cooldownStore.getRemainingCooldownMs(candidate.id, now())
        if (remainingCooldown > 0L) return false
        return cooldownStore.isAvailable(candidate.id, candidate.provider, now(), isNewTask = isNewTask)
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
        val hasImages = messages.any { m -> m.parts.any { it is Part.Image } }
        var previousCandidate: RouterCandidate? = activeCandidate

        while (true) {
            if (switchCountInTask >= maxSwitchesPerTask) {
                val waitMin = calculateWaitMinutes()
                val msg = "Sab free limits khatam, $waitMin minute baad try karo"
                updateStatus(reason = msg)
                throw AllLimitsExhaustedException(msg, waitMin)
            }

            // Ensure active candidate
            val role = difficultyRouter?.getRole(hasImages) ?: if (hasImages) ModelRole.Vision else ModelRole.Main
            if (activeCandidate == null || !checkCachedQuota(activeCandidate!!, isNewTask = (switchCountInTask == 0))) {
                var next = pickBestCandidate(role, isNewTask = (switchCountInTask == 0))
                if (next == null) {
                    val shortestWait = shortestCooldownMs()
                    if (shortestWait in 1..maxInlineWaitMs) {
                        delayMs(shortestWait)
                        next = pickBestCandidate(role, isNewTask = (switchCountInTask == 0))
                    }
                }
                if (next == null) {
                    val waitMin = calculateWaitMinutes()
                    val msg = "Sab free limits khatam, $waitMin minute baad try karo"
                    updateStatus(reason = msg)
                    throw AllLimitsExhaustedException(msg, waitMin)
                }
                val prev = previousCandidate?.id
                activeCandidate = next
                if (prev != null && prev != next.id) {
                    val reason = "Switching candidate"
                    onModelSwitched?.invoke(prev, next.id, reason)
                    updateStatus(
                        model = next.id,
                        provider = next.provider,
                        reason = reason,
                        switchEvent = RouterSwitchEvent(prev, next.id, next.provider, reason, now()),
                    )
                }
                previousCandidate = next
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
                    difficultyRouter?.recordSuccess()
                    updateStatus(
                        model = currentCandidate.id,
                        provider = currentCandidate.provider,
                        reason = "Success",
                        usage = reply.usage,
                    )
                    return reply
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    healthTracker.recordFailure(currentCandidate.id)
                    difficultyRouter?.recordFailure()

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
                                    it.copy(parametersJsonSchema = SchemaSanitizer.sanitizeForGemini(it.parametersJsonSchema))
                                }
                                continue // retry once on same candidate with sanitized schema
                            } else {
                                // Already retried with sanitized schema, switch candidate
                                cooldownStore.markCooldown(currentCandidate.id, ModelCooldownStore.DEFAULT_COOLDOWN_MS, now())
                                onText("") // discard partial output
                                previousCandidate = currentCandidate
                                activeCandidate = null
                                switchCountInTask++
                                if (switchCountInTask >= maxSwitchesPerTask) {
                                    val waitMin = calculateWaitMinutes()
                                    val msg = "Sab free limits khatam, $waitMin minute baad try karo"
                                    updateStatus(reason = msg)
                                    throw AllLimitsExhaustedException(msg, waitMin)
                                }
                                delayMs(switchBackoffMs)
                                break
                            }
                        }
                        RouterAction.KEY_BAD -> {
                            cooldownStore.markBadKey(currentCandidate.provider)
                            onText("") // discard partial output
                            previousCandidate = currentCandidate
                            activeCandidate = null
                            switchCountInTask++
                            if (switchCountInTask >= maxSwitchesPerTask) {
                                val waitMin = calculateWaitMinutes()
                                val msg = "Sab free limits khatam, $waitMin minute baad try karo"
                                updateStatus(reason = msg)
                                throw AllLimitsExhaustedException(msg, waitMin)
                            }
                            delayMs(switchBackoffMs)
                            break
                        }
                        RouterAction.CONTEXT -> {
                            cooldownStore.markCooldown(currentCandidate.id, 10_000L, now())
                            onText("") // discard partial output
                            previousCandidate = currentCandidate
                            activeCandidate = null
                            switchCountInTask++
                            if (switchCountInTask >= maxSwitchesPerTask) {
                                val waitMin = calculateWaitMinutes()
                                val msg = "Sab free limits khatam, $waitMin minute baad try karo"
                                updateStatus(reason = msg)
                                throw AllLimitsExhaustedException(msg, waitMin)
                            }
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
                            onText("") // discard partial output
                            previousCandidate = currentCandidate
                            activeCandidate = null
                            switchCountInTask++
                            if (switchCountInTask >= maxSwitchesPerTask) {
                                val waitMin = calculateWaitMinutes()
                                val msg = "Sab free limits khatam, $waitMin minute baad try karo"
                                updateStatus(reason = msg)
                                throw AllLimitsExhaustedException(msg, waitMin)
                            }
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

    /**
     * Picks the best available candidate:
     * - Respects pinned model (bypasses routing).
     * - Uses cached quota check (< 50ms, zero network calls).
     * - ModelScope is lowest-priority backup (only picked if all primary providers in cooldown).
     * - Matches target role (Vision, Light, Main).
     * - Sorts by curated rank, health score, context tokens.
     */
    fun pickBestCandidate(targetRole: ModelRole = ModelRole.Main, isNewTask: Boolean = false): RouterCandidate? {
        val pinned = pinnedModelId ?: difficultyRouter?.pinnedModelId
        if (!pinned.isNullOrBlank()) {
            return candidates.find { it.id == pinned }
        }

        val available = candidates.filter { checkCachedQuota(it, isNewTask = isNewTask) }
        if (available.isEmpty()) return null

        // ModelScope is backup only: select ModelScope ONLY when all non-backup candidates are in cooldown
        val (primary, backup) = available.partition { !it.isBackup && !it.provider.equals("modelscope", ignoreCase = true) }
        val pool = if (primary.isNotEmpty()) primary else backup

        val roleFiltered = pool.filter { candidate ->
            when (targetRole) {
                ModelRole.Vision -> candidate.model.capabilities.vision || candidate.roles.contains(ModelRole.Vision)
                ModelRole.Light -> candidate.roles.contains(ModelRole.Light) ||
                    AutoModelPicker.isLightModel(candidate.toModelCandidate())
                ModelRole.Main -> true
            }
        }
        val targetPool = if (roleFiltered.isNotEmpty()) roleFiltered else pool

        return targetPool.sortedWith(
            compareByDescending<RouterCandidate> { it.curatedRank }
                .thenByDescending { healthTracker.score(it.id) }
                .thenByDescending { it.model.capabilities.contextTokens }
        ).firstOrNull()
    }

    private fun shortestCooldownMs(): Long =
        candidates.filter { !cooldownStore.isKeyBad(it.provider) }
            .map { cooldownStore.getRemainingCooldownMs(it.id, now()) }
            .filter { it > 0L }
            .minOrNull() ?: 0L

    private fun calculateWaitMinutes(): Int {
        val minWaitMs = candidates
            .map { cooldownStore.getRemainingCooldownMs(it.id, now()) }
            .filter { it > 0L }
            .minOrNull() ?: ModelCooldownStore.DEFAULT_COOLDOWN_MS
        return ceil(minWaitMs / 60_000.0).toInt().coerceAtLeast(1)
    }

    private fun updateStatus(
        model: String? = null,
        provider: String? = null,
        reason: String? = null,
        usage: Usage? = null,
        switchEvent: RouterSwitchEvent? = null,
    ) {
        val current = _status.value
        val newEvents = if (switchEvent != null) current.switchEvents + switchEvent else current.switchEvents
        _status.value = RouterStatus(
            currentModel = model ?: current.currentModel,
            provider = provider ?: current.provider,
            reason = reason ?: current.reason,
            usage = usage ?: current.usage,
            switchEvents = newEvents,
        )
    }
}
