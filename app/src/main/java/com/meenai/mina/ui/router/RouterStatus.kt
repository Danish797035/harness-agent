package com.meenai.mina.ui.router

import androidx.compose.runtime.staticCompositionLocalOf
import com.meenai.mina.core.router.FreeKeyRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Currently active model metadata for Router UI.
 */
data class ActiveModel(
    val id: String = "gemini-flash",
    val name: String = "Gemini Flash",
    val provider: String = "Gemini",
    val quotaRemaining: Int = 38,
    val quotaTotal: Int = 50,
    val reason: String = "Lowest latency and high quota remaining",
    val isPinned: Boolean = false,
    val cooldownUntilMs: Long = 0L,
)

/**
 * Per-provider usage statistics and cooldown state.
 */
data class ProviderUsage(
    val provider: String,
    val todayCount: Int,
    val remainingQuota: Int,
    val totalQuota: Int,
    val lastError: String? = null,
    val cooldownUntilMs: Long = 0L,
)

/**
 * Model switch event emitted when switching to another provider/model.
 */
data class SwitchEvent(
    val fromModel: String,
    val toModel: String,
    val fromProvider: String = "",
    val toProvider: String = "",
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun formatMessage(isHindi: Boolean = false): String {
        val target = if (toProvider.isNotBlank()) "$toModel ($toProvider)" else toModel
        return if (isHindi) {
            "$fromModel limit khatam, $target par switch"
        } else {
            "$fromModel limit reached, switched to $target"
        }
    }
}

/**
 * Public UI interface for Free Key Router status.
 */
interface RouterStatus {
    val current: StateFlow<ActiveModel>
    val usage: StateFlow<List<ProviderUsage>>
    val events: Flow<SwitchEvent>
    fun pinModel(modelId: String?)
}

/**
 * Real implementation of [RouterStatus] backed by core [FreeKeyRouter].
 */
class RealRouterStatus(
    val router: FreeKeyRouter,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RouterStatus {
    private val _current = MutableStateFlow(computeActiveModel())
    override val current: StateFlow<ActiveModel> = _current.asStateFlow()

    private val _usage = MutableStateFlow(computeUsage())
    override val usage: StateFlow<List<ProviderUsage>> = _usage.asStateFlow()

    private val _events = MutableSharedFlow<SwitchEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SwitchEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            var lastEventCount = router.status.value.switchEvents.size
            router.status.collect { status ->
                _current.value = computeActiveModel()
                _usage.value = computeUsage()

                val currentEvents = status.switchEvents
                if (currentEvents.size > lastEventCount) {
                    for (i in lastEventCount until currentEvents.size) {
                        val se = currentEvents[i]
                        _events.tryEmit(
                            SwitchEvent(
                                fromModel = formatModelName(se.oldModel ?: ""),
                                toModel = formatModelName(se.newModel),
                                toProvider = formatProvider(se.provider),
                                reason = se.reason,
                                timestamp = se.timestampMs,
                            ),
                        )
                    }
                    lastEventCount = currentEvents.size
                }
            }
        }
    }

    fun refresh() {
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
    }

    override fun pinModel(modelId: String?) {
        if (modelId.isNullOrBlank()) {
            router.unpinModel()
        } else {
            router.pinModel(modelId)
        }
        _current.value = computeActiveModel()
    }

    private fun computeActiveModel(): ActiveModel {
        val status = router.status.value
        val active = router.activeCandidate ?: router.candidates.firstOrNull()
        if (active == null) {
            return ActiveModel(
                id = "",
                name = "No model",
                provider = "",
                quotaRemaining = 0,
                quotaTotal = 0,
                reason = status.reason ?: "No candidate models available",
                isPinned = false,
                cooldownUntilMs = 0L,
            )
        }

        val id = status.currentModel ?: active.id
        val provider = status.provider ?: active.provider
        val isPinned = router.pinnedModelId != null && (router.pinnedModelId == id)
        val cooldownMs = router.cooldownStore.getRemainingCooldownMs(id)
        val cooldownUntil = if (cooldownMs > 0L) System.currentTimeMillis() + cooldownMs else 0L

        return ActiveModel(
            id = id,
            name = formatModelName(id),
            provider = formatProvider(provider),
            quotaRemaining = 38,
            quotaTotal = 50,
            reason = status.reason ?: (if (isPinned) "Pinned model" else "Optimal candidate"),
            isPinned = isPinned,
            cooldownUntilMs = cooldownUntil,
        )
    }

    private fun computeUsage(): List<ProviderUsage> {
        if (router.candidates.isEmpty()) return emptyList()
        val providers = router.candidates.map { it.provider }.distinct()
        return providers.map { p ->
            val providerModels = router.candidates.filter { it.provider.equals(p, ignoreCase = true) }
            val maxCooldown = providerModels.maxOfOrNull { router.cooldownStore.getRemainingCooldownMs(it.id) } ?: 0L
            val isBad = router.cooldownStore.isKeyBad(p)
            val error = if (isBad) "Invalid Key" else if (maxCooldown > 0L) "Rate limit active" else null
            ProviderUsage(
                provider = formatProvider(p),
                todayCount = 0,
                remainingQuota = if (maxCooldown > 0L) 0 else 50,
                totalQuota = 50,
                lastError = error,
                cooldownUntilMs = if (maxCooldown > 0L) System.currentTimeMillis() + maxCooldown else 0L,
            )
        }
    }

    private fun formatProvider(provider: String): String = when (provider.lowercase()) {
        "openrouter" -> "OpenRouter"
        "zai" -> "Z.ai"
        "modelscope" -> "ModelScope"
        "gemini" -> "Gemini"
        else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun formatModelName(id: String): String = when {
        id.isEmpty() -> ""
        id.contains("glm-4.7-flash", ignoreCase = true) -> "GLM-4.7 Flash"
        id.contains("glm-4.6v-flash", ignoreCase = true) -> "GLM-4.6V Flash"
        id.contains("gemini-2.0-flash", ignoreCase = true) -> "Gemini 2.0 Flash"
        id.contains("llama-3.3-70b", ignoreCase = true) -> "Llama 3.3 70B"
        id.contains("llama-3.1-8b", ignoreCase = true) -> "Llama 3.1 8B"
        id.contains("qwen-2.5-coder-32b", ignoreCase = true) -> "Qwen 2.5 Coder 32B"
        id.contains("Qwen2.5-72B", ignoreCase = true) -> "Qwen 2.5 72B"
        id.contains("Qwen2.5-7B", ignoreCase = true) -> "Qwen 2.5 7B"
        id.contains("Qwen2.5-VL-7B", ignoreCase = true) -> "Qwen 2.5 VL 7B"
        else -> id.substringAfterLast('/').removeSuffix(":free")
    }
}

/**
 * Fake implementation of [RouterStatus] for previews and unit tests.
 */
class FakeRouterStatus(
    initialModel: ActiveModel = ActiveModel(),
    initialUsage: List<ProviderUsage> = listOf(
        ProviderUsage(
            provider = "Gemini",
            todayCount = 12,
            remainingQuota = 38,
            totalQuota = 50,
            lastError = null,
            cooldownUntilMs = 0L,
        ),
        ProviderUsage(
            provider = "OpenRouter",
            todayCount = 5,
            remainingQuota = 45,
            totalQuota = 50,
            lastError = null,
            cooldownUntilMs = 0L,
        ),
    ),
) : RouterStatus {
    private val _current = MutableStateFlow(initialModel)
    override val current: StateFlow<ActiveModel> = _current.asStateFlow()

    private val _usage = MutableStateFlow(initialUsage)
    override val usage: StateFlow<List<ProviderUsage>> = _usage.asStateFlow()

    private val _events = MutableSharedFlow<SwitchEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SwitchEvent> = _events.asSharedFlow()

    var pinnedModelId: String? = null
        private set

    override fun pinModel(modelId: String?) {
        pinnedModelId = modelId
        _current.update {
            it.copy(isPinned = modelId != null && (modelId == it.id || modelId == it.name))
        }
    }

    fun emitSwitch(event: SwitchEvent) {
        _events.tryEmit(event)
    }

    fun updateCurrent(model: ActiveModel) {
        _current.value = model
    }

    fun updateUsage(usageList: List<ProviderUsage>) {
        _usage.value = usageList
    }
}

/**
 * Format chip text: "Model · provider · bacha quota" (e.g. "Gemini Flash · 38/50 aaj").
 * If the provider is already contained in the model name (e.g. "Gemini Flash" and "Gemini"),
 * the redundant provider segment is omitted.
 */
fun formatChipText(active: ActiveModel, todayLabel: String = "today"): String {
    val quotaPart = "${active.quotaRemaining}/${active.quotaTotal} $todayLabel"
    return if (active.provider.isBlank() || active.name.contains(active.provider, ignoreCase = true)) {
        "${active.name} · $quotaPart"
    } else {
        "${active.name} · ${active.provider} · $quotaPart"
    }
}

/**
 * Throttle a flow to emit at most once per [intervalMs] (e.g. 500ms = max 2 times/sec).
 */
fun <T> Flow<T>.throttleLatest(intervalMs: Long = 500L): Flow<T> = channelFlow {
    var lastEmitTime = 0L
    collect { value ->
        val now = System.currentTimeMillis()
        val waitTime = intervalMs - (now - lastEmitTime)
        if (waitTime > 0L) {
            delay(waitTime)
        }
        lastEmitTime = System.currentTimeMillis()
        send(value)
    }
}.conflate()

val LocalRouterStatus = staticCompositionLocalOf<RouterStatus> { FakeRouterStatus() }
