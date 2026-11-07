package com.meenai.mina.ui.router

import androidx.compose.runtime.staticCompositionLocalOf
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
