package com.meenai.mina.ui.router

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.meenai.mina.core.gemini.GeminiUsage
import com.meenai.mina.core.gemini.GeminiUsageFetch
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.core.router.ModelHealthTracker
import com.meenai.mina.core.router.RouterUsageTracker
import com.meenai.mina.core.router.picker.ChatModelFilter
import com.meenai.mina.core.router.picker.CuratedTiers
import com.meenai.mina.core.router.preset.ZaiPreset
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.math.ceil

enum class ModelCallStatus {
    Untested,
    Success,
    Failure,
}

/** Live startup/session connection state for the active model/provider pill. */
enum class ConnectionStatus {
    Idle,
    Connecting,
    Connected,
    SignInRequired,
    Error,
}

/** Result produced by background connection warm-up on app launch or reconnect. */
data class ConnectionWarmupResult(
    val status: ConnectionStatus,
    val detail: String? = null,
    val geminiUsage: GeminiUsage? = null,
    val openRouterInfo: OpenRouterKeyInfo? = null,
)

/** Real-time remaining quota snapshot for the active model (0.0%–100.0%). */
@Immutable
data class QuotaSnapshot(
    val remainingPercent: Double = 100.0,
    val isExactServerQuota: Boolean = false,
    val windowLabel: String = "",
    val tierLabel: String = "",
    val usedCount: Int = 0,
    val limitCount: Int? = null,
    val resetsAtEpochMs: Long? = null,
    val weeklyRemainingPercent: Double? = null,
    val weeklyResetsAtEpochMs: Long? = null,
) {
    val formattedPercent: String
        get() = formatPercent(remainingPercent)

    companion object {
        fun formatPercent(pct: Double): String =
            String.format(java.util.Locale.US, "%.1f%%", pct.coerceIn(0.0, 100.0))
    }
}

/**
 * Computes real-time remaining quota % (0.0–100.0) from exact provider usage when available,
 * falling back to the configured daily/tier window limit when no server limit is reported.
 */
object QuotaCalculator {
    const val PRO_PLAN_DEFAULT_LIMIT = 100
    const val ULTRA_PLAN_DEFAULT_LIMIT = 200
    const val FREE_TIER_DEFAULT_LIMIT = 50

    fun compute(
        active: ActiveModel,
        geminiUsage: GeminiUsage? = null,
        geminiUsageCallsAtFetch: Int = 0,
        todayProviderCalls: Int = active.usedToday,
        magicubeBalance: Double? = null,
        todayMagicubeSpent: Double = 0.0,
        configuredDailyBudget: Int = PRO_PLAN_DEFAULT_LIMIT,
        geminiCapacity: Int = 2,
    ): QuotaSnapshot {
        if (active.id.isBlank() || active.name == "No model") {
            return QuotaSnapshot(remainingPercent = 0.0, usedCount = 0, limitCount = 0)
        }
        val budget = configuredDailyBudget.coerceAtLeast(1)
        if (active.isExactQuota && geminiUsage == null && magicubeBalance == null) {
            return QuotaSnapshot(
                remainingPercent = active.remainingPercent.coerceIn(0.0, 100.0),
                isExactServerQuota = true,
                windowLabel = active.windowLabel.ifBlank { "Quota" },
                tierLabel = active.tierLabel.ifBlank { active.provider },
                usedCount = active.usedToday,
                limitCount = active.limit ?: active.quotaTotal.takeIf { it > 0 },
                resetsAtEpochMs = active.resetsAtEpochMs,
                weeklyRemainingPercent = active.weeklyRemainingPercent,
                weeklyResetsAtEpochMs = active.weeklyResetsAtEpochMs,
            )
        }
        val isGeminiWeb = active.provider.equals("Google Gemini", ignoreCase = true) ||
            active.provider.equals("gemini_web", ignoreCase = true)

        if (isGeminiWeb) {
            val tierUpper = geminiUsage?.tier?.uppercase().orEmpty()
            val (tierLabel, tierLimit) = when {
                "ULTRA" in tierUpper -> "Ultra Plan" to maxOf(budget, ULTRA_PLAN_DEFAULT_LIMIT)
                "PRO" in tierUpper || geminiCapacity >= 2 -> "Pro Plan" to budget
                else -> "Free" to minOf(budget, FREE_TIER_DEFAULT_LIMIT).coerceAtLeast(1)
            }
            val daily = geminiUsage?.daily
            val weekly = geminiUsage?.weekly
            if (daily != null) {
                val baseRem = (100.0 - daily.fractionUsed * 100.0).coerceIn(0.0, 100.0)
                val deltaCalls = (todayProviderCalls - geminiUsageCallsAtFetch).coerceAtLeast(0)
                val deltaPct = (deltaCalls * 100.0) / tierLimit
                val remaining = (baseRem - deltaPct).coerceIn(0.0, 100.0)
                val weeklyRem = weekly?.let { (100.0 - it.fractionUsed * 100.0).coerceIn(0.0, 100.0) }
                return QuotaSnapshot(
                    remainingPercent = remaining,
                    isExactServerQuota = true,
                    windowLabel = "5h window",
                    tierLabel = tierLabel,
                    usedCount = todayProviderCalls,
                    limitCount = null,
                    resetsAtEpochMs = daily.resetsAtEpochMs,
                    weeklyRemainingPercent = weeklyRem,
                    weeklyResetsAtEpochMs = weekly?.resetsAtEpochMs,
                )
            }
            if (weekly != null) {
                val baseRem = (100.0 - weekly.fractionUsed * 100.0).coerceIn(0.0, 100.0)
                val deltaCalls = (todayProviderCalls - geminiUsageCallsAtFetch).coerceAtLeast(0)
                val deltaPct = (deltaCalls * 100.0) / tierLimit
                val remaining = (baseRem - deltaPct).coerceIn(0.0, 100.0)
                return QuotaSnapshot(
                    remainingPercent = remaining,
                    isExactServerQuota = true,
                    windowLabel = "Weekly",
                    tierLabel = tierLabel,
                    usedCount = todayProviderCalls,
                    limitCount = null,
                    resetsAtEpochMs = weekly.resetsAtEpochMs,
                    weeklyRemainingPercent = remaining,
                    weeklyResetsAtEpochMs = weekly.resetsAtEpochMs,
                )
            }
            val remaining = (((tierLimit - active.usedToday).coerceAtLeast(0)) * 100.0 / tierLimit).coerceIn(0.0, 100.0)
            return QuotaSnapshot(
                remainingPercent = remaining,
                isExactServerQuota = false,
                windowLabel = "Daily window",
                tierLabel = tierLabel,
                usedCount = active.usedToday,
                limitCount = tierLimit,
            )
        }

        val explicitLimit = active.limit?.takeIf { it > 0 } ?: active.quotaTotal.takeIf { it > 0 }
        if (explicitLimit != null) {
            val remaining = (((explicitLimit - active.usedToday).coerceAtLeast(0)) * 100.0 / explicitLimit).coerceIn(0.0, 100.0)
            return QuotaSnapshot(
                remainingPercent = remaining,
                isExactServerQuota = !active.isEstimate,
                windowLabel = "Daily limit",
                tierLabel = active.provider,
                usedCount = active.usedToday,
                limitCount = explicitLimit,
            )
        }

        if (active.provider.equals("ModelScope", ignoreCase = true) && magicubeBalance != null) {
            val totalCapacity = (magicubeBalance + todayMagicubeSpent).coerceAtLeast(1.0)
            val remaining = ((magicubeBalance / totalCapacity) * 100.0).coerceIn(0.0, 100.0)
            return QuotaSnapshot(
                remainingPercent = remaining,
                isExactServerQuota = true,
                windowLabel = "Magicube",
                tierLabel = "ModelScope",
                usedCount = active.usedToday,
            )
        }

        val remaining = (((budget - active.usedToday).coerceAtLeast(0)) * 100.0 / budget).coerceIn(0.0, 100.0)
        return QuotaSnapshot(
            remainingPercent = remaining,
            isExactServerQuota = false,
            windowLabel = "Daily budget",
            tierLabel = active.provider.ifBlank { "Free" },
            usedCount = active.usedToday,
            limitCount = budget,
        )
    }
}

/**
 * Currently active model metadata for Router UI.
 */
@Immutable
data class ActiveModel(
    val id: String = "gemini-flash",
    val name: String = "Gemini Flash",
    val provider: String = "Gemini",
    val usedToday: Int = 0,
    val limit: Int? = null,
    val isEstimate: Boolean = false,
    val quotaRemaining: Int = 0,
    val quotaTotal: Int = 0,
    val reason: String = "Optimal free candidate",
    val isPinned: Boolean = false,
    val canPin: Boolean = true,
    val cooldownUntilMs: Long = 0L,
    val callStatus: ModelCallStatus = ModelCallStatus.Untested,
    val remainingPercent: Double = 100.0,
    val isExactQuota: Boolean = false,
    val tierLabel: String = "",
    val windowLabel: String = "",
    val resetsAtEpochMs: Long? = null,
    val weeklyRemainingPercent: Double? = null,
    val weeklyResetsAtEpochMs: Long? = null,
)

enum class ModelStatusKind {
    Ready,
    Cooldown,
    Unsupported,
    BadKey,
    NoBalance;

    fun label(cooldownMin: Int = 0): String = when (this) {
        Ready -> "Ready"
        Cooldown -> "Cooldown $cooldownMin min"
        Unsupported -> "Unsupported"
        BadKey -> "Bad key"
        NoBalance -> "No balance"
    }
}

enum class QuotaState {
    Ready,
    Low,
    Exhausted;
}

/**
 * Per-model quota, usage line and status.
 */
@Immutable
data class ModelQuotaLine(
    val modelId: String,
    val modelName: String,
    val todayRequests: Int,
    val limit: Int? = null,
    val isEstimate: Boolean = false,
    val status: ModelStatusKind = ModelStatusKind.Ready,
    val cooldownMinutes: Int = 0,
    val costPerCall: Double? = null,
    val costInfo: String? = null,
    val provider: String = "",
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val contextTokens: Int = 32_000,
    val isFree: Boolean = true,
    val quotaState: QuotaState = QuotaState.Ready,
    val exhaustedReason: String? = null,
    val rank: Int? = null,
    val remainingPercent: Double? = null,
)

/**
 * Per-provider usage statistics, models list and cooldown state.
 */
@Immutable
data class ProviderUsage(
    val provider: String,
    val providerId: String = "",
    val isBadKey: Boolean = false,
    val concurrency: Int? = null,
    val totalCostToday: Double? = null,
    val magicubeBalance: Double? = null,
    val models: List<ModelQuotaLine> = emptyList(),
    // Backward compatibility fields
    val todayCount: Int = 0,
    val remainingQuota: Int = 0,
    val totalQuota: Int = 0,
    val lastError: String? = null,
    val cooldownUntilMs: Long = 0L,
)

data class OpenRouterKeyInfo(
    val usage: Int,
    val limit: Int?,
    val isFreeTier: Boolean,
)

interface OpenRouterKeyClient {
    suspend fun getKeyInfo(apiKey: String): OpenRouterKeyInfo?
}

class HttpOpenRouterKeyClient(
    val http: OkHttpClient = OkHttpClient(),
) : OpenRouterKeyClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getKeyInfo(apiKey: String): OpenRouterKeyInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/key")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                parseKeyJson(body, response)
            }
        }.getOrNull()
    }

    fun parseKeyJson(body: String, response: Response? = null): OpenRouterKeyInfo? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject
            val usage = data?.get("usage")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
            val limitDouble = data?.get("limit")?.jsonPrimitive?.doubleOrNull
            val limit = limitDouble?.toInt()
            val isFreeTier = data?.get("is_free_tier")?.jsonPrimitive?.booleanOrNull ?: false

            val headerLimit = response?.header("x-ratelimit-limit-requests")?.toIntOrNull()
                ?: response?.header("x-ratelimit-limit")?.toIntOrNull()

            OpenRouterKeyInfo(
                usage = usage,
                limit = headerLimit ?: limit,
                isFreeTier = isFreeTier,
            )
        }.getOrNull()
    }
}

fun modelScopeMagicubeCost(modelId: String): Double = MagicubeLedger.costForModel(modelId)

/**
 * Model switch event emitted when switching to another provider/model.
 */
@Immutable
data class SwitchEvent(
    val fromModel: String,
    val toModel: String,
    val fromProvider: String = "",
    val toProvider: String = "",
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun formatMessage(isHindi: Boolean = false): String {
        if (reason.contains("Model slow tha", ignoreCase = true)) {
            return reason
        }
        val target = if (toProvider.isNotBlank()) "$toModel ($toProvider)" else toModel
        val detail = failureDetail()
        if (detail != null) {
            // A real failure (HTTP error, bad reply, dropped stream) is the useful
            // part: "limit reached" was wrong for every non-quota switch.
            return if (isHindi) {
                "$fromModel: $detail — $target par switch"
            } else {
                "$fromModel: $detail — switched to $target"
            }
        }
        return if (isHindi) {
            "$fromModel limit khatam, $target par switch"
        } else {
            "$fromModel limit reached, switched to $target"
        }
    }

    /** The failure line behind this switch, or null for a routine switch. */
    fun failureDetail(): String? {
        val text = reason.trim()
        if (text.isEmpty()) return null
        val routine = listOf(
            "switching candidate",
            "optimal free candidate",
            "step boundary check",
            "escalated to",
            "model slow tha",
        )
        return text.takeIf { candidate -> routine.none { candidate.contains(it, ignoreCase = true) } }
    }
}

/**
 * Public UI interface for Free Key Router status.
 */
interface RouterStatus {
    val current: StateFlow<ActiveModel>
    val usage: StateFlow<List<ProviderUsage>>
    val events: Flow<SwitchEvent>
    val switchHistory: List<SwitchEvent> get() = emptyList()
    val connectionStatus: StateFlow<ConnectionStatus> get() = MutableStateFlow(ConnectionStatus.Connected)
    val connectionDetail: StateFlow<String?> get() = MutableStateFlow(null)
    val thinkingEffort: StateFlow<String?> get() = MutableStateFlow(null)
    fun pinModel(modelId: String?)
    fun selectModel(modelId: String, providerId: String? = null) {
        pinModel(modelId)
    }
    fun setThinkingEffort(effort: String?) {}
    fun setConnectionStatus(status: ConnectionStatus, detail: String? = null) {}
    fun setGeminiUsage(usage: GeminiUsage?, callsAtFetch: Int = RouterUsageTracker.getTodayProviderCalls("gemini_web")) {}
    fun refresh()
}

/**
 * Real implementation of [RouterStatus] backed by core [FreeKeyRouter].
 */
class RealRouterStatus(
    val router: FreeKeyRouter,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val store: ProviderStore? = null,
    val getFallbackModel: (() -> Pair<String, String>?)? = null,
    var openRouterKeyClient: OpenRouterKeyClient = HttpOpenRouterKeyClient(),
    val preferencesStore: ModelPreferencesStore? = null,
    val dailyBudgetProvider: () -> Int = { QuotaCalculator.PRO_PLAN_DEFAULT_LIMIT },
    val geminiUsageFetcher: suspend (GeminiWebSession) -> GeminiUsageFetch? = { session ->
        runCatching { GeminiWebClient.fetchUsage(GeminiWebClient.geminiWebHttp, session) }.getOrNull()
    },
) : RouterStatus {

    private var cachedOpenRouterKeyInfo: OpenRouterKeyInfo? = null
    private var cachedGeminiUsage: GeminiUsage? = null
    private var geminiCallsAtFetch: Int = 0

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.Idle)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionDetail = MutableStateFlow<String?>(null)
    override val connectionDetail: StateFlow<String?> = _connectionDetail.asStateFlow()

    private val _thinkingEffort = MutableStateFlow(
        preferencesStore?.getThinkingEffort() ?: store?.roles?.value?.reasoningEffort(ModelRole.Main)
    )
    override val thinkingEffort: StateFlow<String?> = _thinkingEffort.asStateFlow()

    private val _current = MutableStateFlow(computeActiveModel())
    override val current: StateFlow<ActiveModel> = _current.asStateFlow()

    private val _usage = MutableStateFlow(computeUsage())
    override val usage: StateFlow<List<ProviderUsage>> = _usage.asStateFlow()

    private val _events = MutableSharedFlow<SwitchEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SwitchEvent> = _events.asSharedFlow()

    private val _history = mutableListOf<SwitchEvent>()
    override val switchHistory: List<SwitchEvent> get() = synchronized(_history) { _history.toList() }

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
                        val event = SwitchEvent(
                            fromModel = formatModelName(se.oldModel ?: ""),
                            toModel = formatModelName(se.newModel),
                            toProvider = formatProvider(se.provider),
                            reason = se.reason,
                            timestamp = se.timestampMs,
                        )
                        synchronized(_history) {
                            _history.add(event)
                            if (_history.size > 20) _history.removeAt(0)
                        }
                        _events.tryEmit(event)
                    }
                    lastEventCount = currentEvents.size
                }
            }
        }
        if (store != null) {
            scope.launch {
                store.providers.collect {
                    _current.value = computeActiveModel()
                    _usage.value = computeUsage()
                }
            }
            scope.launch {
                store.roles.collect { roles ->
                    if (preferencesStore == null) {
                        _thinkingEffort.value = roles.reasoningEffort(ModelRole.Main)
                    }
                    _current.value = computeActiveModel()
                    _usage.value = computeUsage()
                }
            }
        }
        if (preferencesStore != null) {
            scope.launch {
                preferencesStore.thinkingEffort.collect { effort ->
                    _thinkingEffort.value = effort
                }
            }
        }
        scope.launch {
            var first = true
            RouterUsageTracker.updates.collect {
                if (first) {
                    first = false
                    return@collect
                }
                _current.value = computeActiveModel()
                _usage.value = computeUsage()
                refreshServerQuotasAsync()
            }
        }
    }

    override fun refresh() {
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
        refreshServerQuotasAsync()
    }

    private fun refreshServerQuotasAsync() {
        refreshOpenRouterKeyAsync()
        refreshGeminiUsageAsync()
    }

    fun refreshGeminiUsageAsync() {
        val s = store ?: return
        val webProvider = s.geminiWebProvider() ?: return
        val session = s.geminiWebSession(webProvider.id) ?: return
        scope.launch(Dispatchers.IO) {
            val fetched = geminiUsageFetcher(session) ?: return@launch
            if (fetched.session != session) {
                s.updateGeminiWebSession(webProvider.id, fetched.session)
            }
            if (fetched.usage != null) {
                setGeminiUsage(
                    usage = fetched.usage,
                    callsAtFetch = RouterUsageTracker.getTodayProviderCalls("gemini_web"),
                )
            }
        }
    }

    fun refreshOpenRouterKeyAsync() {
        val s = store ?: return
        val orKey = AppFreeKeyRouter.resolveOpenRouterKey(s) ?: return
        scope.launch {
            val info = openRouterKeyClient.getKeyInfo(orKey)
            if (info != null) {
                cachedOpenRouterKeyInfo = info
                _current.value = computeActiveModel()
                _usage.value = computeUsage()
            }
        }
    }

    fun setOpenRouterKeyInfo(info: OpenRouterKeyInfo) {
        cachedOpenRouterKeyInfo = info
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
    }

    override fun setGeminiUsage(usage: GeminiUsage?, callsAtFetch: Int) {
        cachedGeminiUsage = usage
        geminiCallsAtFetch = callsAtFetch
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
    }

    override fun setConnectionStatus(status: ConnectionStatus, detail: String?) {
        _connectionStatus.value = status
        _connectionDetail.value = detail
    }

    override fun setThinkingEffort(effort: String?) {
        val normalized = effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        preferencesStore?.setThinkingEffort(normalized)
        _thinkingEffort.value = normalized
        val storeMapped = when (normalized) {
            "fast" -> "low"
            else -> normalized
        }
        store?.setReasoningEffort(ModelRole.Main, storeMapped)
    }

    override fun selectModel(modelId: String, providerId: String?) {
        if (modelId.isBlank()) {
            pinModel(null)
            return
        }
        val matchedCandidate = router.candidates.firstOrNull { cand ->
            cand.id.equals(modelId, ignoreCase = true) && (providerId == null || run {
                val p = store?.providers?.value?.firstOrNull { it.id == providerId }
                p == null || AppFreeKeyRouter.providerMatches(p, cand.provider)
            })
        } ?: router.candidates.firstOrNull { it.id.equals(modelId, ignoreCase = true) }

        if (matchedCandidate != null) {
            router.pinModel(matchedCandidate.id)
        } else {
            router.pinModel(modelId)
        }

        store?.let { s ->
            val targetProvider = providerId?.let { s.provider(it) }
                ?: matchedCandidate?.let { cand ->
                    s.providers.value.firstOrNull { AppFreeKeyRouter.providerMatches(it, cand.provider) }
                }
            if (targetProvider != null) {
                val listed = targetProvider.models?.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
                val preset = ProviderPresets.forBaseUrl(targetProvider.baseUrl) ?: ProviderPresets.forHost(targetProvider.baseUrl)
                val caps = matchedCandidate?.model?.capabilities ?: Capabilities(
                    toolCalls = listed?.toolCalls ?: preset?.capabilities?.toolCalls ?: true,
                    vision = listed?.vision ?: preset?.capabilities?.vision ?: ChatModelFilter.supportsVision(modelId, false),
                    contextTokens = listed?.contextTokens ?: preset?.capabilities?.contextTokens ?: 128_000,
                    reasoning = listed?.reasoning ?: preset?.capabilities?.reasoning ?: false,
                    promptCaching = preset?.capabilities?.promptCaching ?: PromptCaching.Implicit,
                )
                s.use(ModelRole.Main, targetProvider.id, matchedCandidate?.id ?: modelId, caps)
            }
        }
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
    }

    override fun pinModel(modelId: String?) {
        if (router.candidates.isEmpty()) {
            return
        }
        if (modelId.isNullOrBlank()) {
            router.unpinModel()
        } else {
            val candidate = router.candidates.find { it.id.equals(modelId, ignoreCase = true) }
            router.pinModel(candidate?.id ?: modelId)
            store?.let { s ->
                if (candidate != null) {
                    val p = s.providers.value.firstOrNull { prov ->
                        AppFreeKeyRouter.providerMatches(prov, candidate.provider)
                    }
                    if (p != null) {
                        s.use(ModelRole.Main, p.id, candidate.id, candidate.model.capabilities)
                    }
                }
            }
        }
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
    }

    private fun computeActiveModel(): ActiveModel {
        val status = router.status.value
        val rawMainModelId = store?.configFor(ModelRole.Main)?.model
            ?: router.preferences.getManualRole(ModelRole.Main.name)
        val mainModelId = if (rawMainModelId?.contains("carbon", ignoreCase = true) == true) "gemini-4-argon" else rawMainModelId
        val configuredActive = if (!mainModelId.isNullOrBlank()) {
            router.candidates.firstOrNull { it.id.equals(mainModelId, ignoreCase = true) }
        } else null
        val active = router.activeCandidate ?: configuredActive ?: router.candidates.firstOrNull()
        val budget = dailyBudgetProvider().coerceAtLeast(1)
        val geminiWebCap = store?.geminiWebProvider()?.let { store.geminiWebSession(it.id)?.capacity } ?: 2
        if (active == null) {
            val fallback = getFallbackModel?.invoke()
                ?: store?.configFor(ModelRole.Main)?.let { cfg ->
                    val p = store.provider(cfg.providerId)
                    cfg.model to (p?.name ?: cfg.name)
                }
            if (fallback != null) {
                val (rawModelId, providerName) = fallback
                val modelId = if (rawModelId.contains("carbon", ignoreCase = true)) "gemini-4-argon" else rawModelId
                val used = RouterUsageTracker.getTodayCalls(modelId)
                val isGeminiWebFallback = providerName.equals("Google Gemini", ignoreCase = true) ||
                    providerName.equals("gemini_web", ignoreCase = true)
                val base = ActiveModel(
                    id = modelId,
                    name = formatModelName(modelId),
                    provider = formatProvider(providerName),
                    usedToday = used,
                    limit = null,
                    isEstimate = !isGeminiWebFallback,
                    quotaRemaining = 0,
                    quotaTotal = 0,
                    reason = status.reason ?: if (isGeminiWebFallback) "Google One AI Premium (Pro Plan)" else "Configured main model",
                    isPinned = false,
                    canPin = false,
                    cooldownUntilMs = 0L,
                )
                val snap = QuotaCalculator.compute(
                    active = base,
                    geminiUsage = if (isGeminiWebFallback) cachedGeminiUsage else null,
                    geminiUsageCallsAtFetch = geminiCallsAtFetch,
                    todayProviderCalls = if (isGeminiWebFallback) RouterUsageTracker.getTodayProviderCalls("gemini_web") else used,
                    configuredDailyBudget = budget,
                    geminiCapacity = geminiWebCap,
                )
                return base.copy(
                    remainingPercent = snap.remainingPercent,
                    isExactQuota = snap.isExactServerQuota,
                    tierLabel = snap.tierLabel,
                    windowLabel = snap.windowLabel,
                    resetsAtEpochMs = snap.resetsAtEpochMs,
                    weeklyRemainingPercent = snap.weeklyRemainingPercent,
                    weeklyResetsAtEpochMs = snap.weeklyResetsAtEpochMs,
                )
            }
            return ActiveModel(
                id = "",
                name = "No model",
                provider = "",
                usedToday = 0,
                limit = null,
                isEstimate = false,
                quotaRemaining = 0,
                quotaTotal = 0,
                reason = status.reason ?: "No candidate models available",
                isPinned = false,
                canPin = false,
                cooldownUntilMs = 0L,
                remainingPercent = 0.0,
            )
        }

        val rawId = status.currentModel ?: active.id
        val id = if (rawId.contains("carbon", ignoreCase = true)) "gemini-4-argon" else rawId
        val provider = status.provider ?: active.provider
        val isPinned = router.pinnedModelId != null && (router.pinnedModelId.equals(id, ignoreCase = true))
        val cooldownMs = router.cooldownStore.getRemainingCooldownMs(id)
        val cooldownUntil = if (cooldownMs > 0L) System.currentTimeMillis() + cooldownMs else 0L

        val callOutcome = router.healthTracker.getLastCallOutcome(id)
        val callStatus = when (callOutcome) {
            ModelHealthTracker.CallOutcome.Success -> ModelCallStatus.Success
            ModelHealthTracker.CallOutcome.Failure -> ModelCallStatus.Failure
            ModelHealthTracker.CallOutcome.None -> ModelCallStatus.Untested
        }

        var usedToday = RouterUsageTracker.getTodayCalls(id)
        var limit: Int? = null
        var isEstimate = true

        val isGeminiWeb = provider.equals("gemini_web", ignoreCase = true) ||
            provider.equals("Google Gemini", ignoreCase = true)
        if (isGeminiWeb) {
            isEstimate = false
        } else if (provider.equals("openrouter", ignoreCase = true) && cachedOpenRouterKeyInfo != null) {
            limit = cachedOpenRouterKeyInfo?.limit
            if (limit != null) {
                isEstimate = false
                usedToday = maxOf(usedToday, cachedOpenRouterKeyInfo?.usage ?: 0)
            }
        }

        val base = ActiveModel(
            id = id,
            name = formatModelName(id),
            provider = formatProvider(provider),
            usedToday = usedToday,
            limit = limit,
            isEstimate = isEstimate,
            quotaRemaining = if (limit != null) (limit - usedToday).coerceAtLeast(0) else 0,
            quotaTotal = limit ?: 0,
            reason = status.reason ?: (if (isPinned) "Pinned model" else "Optimal free candidate"),
            isPinned = isPinned,
            canPin = true,
            cooldownUntilMs = cooldownUntil,
            callStatus = callStatus,
        )
        val isModelScope = provider.equals("modelscope", ignoreCase = true)
        val todayMsSpent = if (isModelScope) {
            RouterUsageTracker.getTodayProviderCalls("modelscope") * modelScopeMagicubeCost(id)
        } else 0.0
        val snap = QuotaCalculator.compute(
            active = base,
            geminiUsage = if (isGeminiWeb) cachedGeminiUsage else null,
            geminiUsageCallsAtFetch = geminiCallsAtFetch,
            todayProviderCalls = if (isGeminiWeb) RouterUsageTracker.getTodayProviderCalls("gemini_web") else usedToday,
            magicubeBalance = if (isModelScope) MagicubeLedger.totalBalance() else null,
            todayMagicubeSpent = todayMsSpent,
            configuredDailyBudget = budget,
            geminiCapacity = geminiWebCap,
        )
        return base.copy(
            remainingPercent = snap.remainingPercent,
            isExactQuota = snap.isExactServerQuota,
            tierLabel = snap.tierLabel,
            windowLabel = snap.windowLabel,
            resetsAtEpochMs = snap.resetsAtEpochMs,
            weeklyRemainingPercent = snap.weeklyRemainingPercent,
            weeklyResetsAtEpochMs = snap.weeklyResetsAtEpochMs,
        )
    }

    private fun computeUsage(): List<ProviderUsage> {
        val savedProviders = store?.providers?.value.orEmpty()
        val providerEntries = if (savedProviders.isNotEmpty()) {
            savedProviders.map { it.name to it }
        } else {
            router.candidates.map { it.provider }.distinct().map { p ->
                p to null
            }
        }

        if (providerEntries.isEmpty()) return emptyList()
        val budget = dailyBudgetProvider().coerceAtLeast(1)
        val geminiWebCap = store?.geminiWebProvider()?.let { store.geminiWebSession(it.id)?.capacity } ?: 2

        return providerEntries.map { (pName, providerObj) ->
            val providerKeyName = providerObj?.name ?: pName
            val isZai = providerObj?.let { AppFreeKeyRouter.isZaiProvider(it) } ?: pName.equals("zai", ignoreCase = true)
            val isModelScope = providerObj?.let { AppFreeKeyRouter.isModelScopeProvider(it) } ?: pName.equals("modelscope", ignoreCase = true)
            val isOpenRouter = providerObj?.let { AppFreeKeyRouter.isOpenRouterProvider(it) } ?: pName.equals("openrouter", ignoreCase = true)
            val isGeminiWeb = providerObj?.let { AppFreeKeyRouter.isGeminiWebProvider(it) }
                ?: pName.equals("gemini_web", ignoreCase = true)
                || pName.equals("Google Gemini", ignoreCase = true)

            val providerId = providerObj?.id ?: pName
            val isBadKey = router.cooldownStore.isKeyBad(providerKeyName) ||
                router.cooldownStore.isKeyBad(providerId) ||
                (if (isZai) router.cooldownStore.isKeyBad("zai") else false) ||
                (if (isModelScope) router.cooldownStore.isKeyBad("modelscope") else false) ||
                (if (isOpenRouter) router.cooldownStore.isKeyBad("openrouter") else false) ||
                (if (isGeminiWeb) router.cooldownStore.isKeyBad("gemini_web") else false)

            val modelIds = linkedSetOf<String>()

            // 1. Router candidates matching this provider
            router.candidates.filter {
                if (providerObj != null) AppFreeKeyRouter.providerMatches(providerObj, it.provider)
                else it.provider.equals(pName, ignoreCase = true) ||
                    it.provider.equals(providerId, ignoreCase = true) ||
                    (isZai && it.provider.equals("zai", ignoreCase = true)) ||
                    (isModelScope && it.provider.equals("modelscope", ignoreCase = true)) ||
                    (isOpenRouter && it.provider.equals("openrouter", ignoreCase = true)) ||
                    (isGeminiWeb && it.provider.equals("gemini_web", ignoreCase = true))
            }.forEach { modelIds.add(it.id) }

            if (isGeminiWeb) {
                com.meenai.mina.core.gemini.GeminiWebClient.DEFAULT_MODELS.forEach { modelIds.add(it.id) }
            }

            // 2. Models from provider object
            providerObj?.let { p ->
                val freeModels = p.models?.filter { it.id.endsWith(":free") }.orEmpty()
                if (isOpenRouter && freeModels.isNotEmpty()) {
                    freeModels.forEach { modelIds.add(it.id) }
                } else if (isModelScope && p.models?.isNotEmpty() == true) {
                    p.models.filter { AppFreeKeyRouter.isModelScopeChatModel(it) }.forEach { modelIds.add(it.id) }
                } else if (p.models?.isNotEmpty() == true) {
                    p.models.filter { it.chat != false && it.usable && !ChatModelFilter.isExcluded(it.id) }
                        .take(24)
                        .forEach { modelIds.add(it.id) }
                }

                p.typedModels.forEach { modelIds.add(it) }
                store?.configs?.value?.filter { it.providerId == p.id }?.forEach { modelIds.add(it.model) }
            }

            // 3. Fallbacks if still empty
            if (modelIds.isEmpty()) {
                if (isZai) {
                    modelIds.add(ZaiPreset.MODEL_FLASH)
                    modelIds.add(ZaiPreset.MODEL_VISION)
                } else if (isModelScope) {
                    if (RouterUsageTracker.getTodayCalls("Qwen/Qwen2.5-72B-Instruct") > 0) {
                        modelIds.add("Qwen/Qwen2.5-72B-Instruct")
                    }
                    modelIds.add("Qwen/Qwen2.5-7B-Instruct")
                    modelIds.add("Qwen/Qwen2.5-VL-7B-Instruct")
                } else if (isOpenRouter) {
                    modelIds.add("google/gemini-2.0-flash-exp:free")
                    modelIds.add("meta-llama/llama-3.3-70b-instruct:free")
                    modelIds.add("meta-llama/llama-3.1-8b-instruct:free")
                    modelIds.add("qwen/qwen-2.5-coder-32b-instruct:free")
                }
            }

            val magicubeBal = if (isModelScope) MagicubeLedger.totalBalance() else null

            val lines = modelIds.map { mId ->
                var todayRequests = RouterUsageTracker.getTodayCalls(mId)
                val isUnsupported = router.cooldownStore.isUnsupported(mId)
                val cooldownMs = router.cooldownStore.getRemainingCooldownMs(mId)
                val candidate = router.candidates.find { it.id.equals(mId, ignoreCase = true) }
                val hasBalance = router.cooldownStore.hasBalance(providerKeyName) &&
                    router.cooldownStore.hasBalance(providerId) &&
                    router.cooldownStore.hasBalance(pName) &&
                    (candidate?.let { router.cooldownStore.hasBalance(it.provider) } ?: true)

                val status = when {
                    isBadKey -> ModelStatusKind.BadKey
                    !hasBalance -> ModelStatusKind.NoBalance
                    isUnsupported -> ModelStatusKind.Unsupported
                    cooldownMs > 0L -> ModelStatusKind.Cooldown
                    else -> ModelStatusKind.Ready
                }
                val cooldownMin = if (cooldownMs > 0L) ceil(cooldownMs / 60000.0).toInt().coerceAtLeast(1) else 0

                var limit: Int? = null
                var isEstimate = true
                if (isOpenRouter && cachedOpenRouterKeyInfo != null) {
                    limit = cachedOpenRouterKeyInfo?.limit
                    if (limit != null) {
                        isEstimate = false
                        todayRequests = maxOf(todayRequests, cachedOpenRouterKeyInfo?.usage ?: 0)
                    }
                }

                val costPerCall = if (isModelScope) modelScopeMagicubeCost(mId) else null
                val costInfo = if (costPerCall != null) "~$costPerCall Magicube/call" else null

                val listed = providerObj?.models?.find { it.id.equals(mId, ignoreCase = true) }

                val supportsVision = candidate?.model?.capabilities?.vision
                    ?: (listed?.vision == true)
                    ?: ChatModelFilter.supportsVision(mId, false)
                val supportsTools = candidate?.model?.capabilities?.toolCalls
                    ?: listed?.toolCalls
                    ?: true
                val contextTokens = candidate?.model?.capabilities?.contextTokens
                    ?: listed?.contextTokens
                    ?: 32_000
                val isFree = candidate?.isFree
                    ?: (mId.endsWith(":free") || isModelScope || isZai)

                val (quotaState, exhaustedReason) = if (isGeminiWeb && !isBadKey) {
                    QuotaState.Ready to null
                } else {
                    ModelPickerLogic.computeQuotaState(
                        isBadKey = isBadKey,
                        isUnsupported = isUnsupported,
                        cooldownMs = cooldownMs,
                        limit = limit,
                        todayRequests = todayRequests,
                        isModelScope = isModelScope,
                        magicubeBalance = magicubeBal,
                        costPerCall = costPerCall,
                        hasBalance = hasBalance,
                    )
                }

                val lineSnap = QuotaCalculator.compute(
                    active = ActiveModel(
                        id = mId,
                        name = formatModelName(mId),
                        provider = if (isGeminiWeb) "Google Gemini" else formatProvider(providerKeyName),
                        usedToday = todayRequests,
                        limit = limit,
                        isEstimate = if (isGeminiWeb) false else isEstimate,
                    ),
                    geminiUsage = if (isGeminiWeb) cachedGeminiUsage else null,
                    geminiUsageCallsAtFetch = geminiCallsAtFetch,
                    todayProviderCalls = if (isGeminiWeb) RouterUsageTracker.getTodayProviderCalls("gemini_web") else todayRequests,
                    magicubeBalance = magicubeBal,
                    todayMagicubeSpent = if (isModelScope) (costPerCall ?: 0.0) * todayRequests else 0.0,
                    configuredDailyBudget = budget,
                    geminiCapacity = geminiWebCap,
                )

                ModelQuotaLine(
                    modelId = mId,
                    modelName = formatModelName(mId),
                    todayRequests = todayRequests,
                    limit = limit,
                    isEstimate = if (isGeminiWeb) false else isEstimate,
                    status = if (isGeminiWeb && !isBadKey) ModelStatusKind.Ready else status,
                    cooldownMinutes = cooldownMin,
                    costPerCall = costPerCall,
                    costInfo = if (isGeminiWeb) (lineSnap.tierLabel.ifBlank { "Pro Plan" }) else costInfo,
                    provider = formatProvider(providerKeyName),
                    supportsVision = supportsVision,
                    supportsTools = supportsTools,
                    contextTokens = contextTokens,
                    isFree = if (isGeminiWeb) true else isFree,
                    quotaState = quotaState,
                    exhaustedReason = exhaustedReason,
                    rank = runCatching { AppFreeKeyRouter.getRankingsRepository().findRankingIndex(mId) }.getOrNull(),
                    remainingPercent = lineSnap.remainingPercent,
                )
            }

            val totalCostToday = if (isModelScope) {
                lines.sumOf { (it.costPerCall ?: 0.0) * it.todayRequests }
            } else null

            val maxCooldown = lines.maxOfOrNull {
                router.cooldownStore.getRemainingCooldownMs(it.modelId)
            } ?: 0L

            val todayTotal = lines.sumOf { it.todayRequests }
            val providerLimit = if (isOpenRouter && cachedOpenRouterKeyInfo?.limit != null) cachedOpenRouterKeyInfo?.limit else null
            val remQuota = if (providerLimit != null) (providerLimit - todayTotal).coerceAtLeast(0) else 0
            val totQuota = providerLimit ?: 0

            ProviderUsage(
                provider = formatProvider(providerKeyName),
                providerId = providerId,
                isBadKey = isBadKey,
                concurrency = if (isZai) 1 else null,
                totalCostToday = totalCostToday,
                magicubeBalance = magicubeBal,
                models = lines,
                todayCount = todayTotal,
                remainingQuota = remQuota,
                totalQuota = totQuota,
                lastError = if (isBadKey) "Invalid Key" else if (maxCooldown > 0L) "Rate limit active" else null,
                cooldownUntilMs = if (maxCooldown > 0L) System.currentTimeMillis() + maxCooldown else 0L,
            )
        }
    }

    private fun formatProvider(provider: String): String = when (provider.lowercase()) {
        "gemini_web" -> "Google Gemini"
        "openrouter" -> "OpenRouter"
        "zai" -> "Z.ai"
        "modelscope" -> "ModelScope"
        "gemini" -> "Gemini"
        "anthropic" -> "Claude"
        "openai" -> "OpenAI"
        "chatgpt" -> "ChatGPT"
        "xai" -> "xAI"
        else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun formatModelName(id: String): String = formatRouterModelName(id)
}

/** Formats a raw model ID into a concise display name for chip and wait line. */
internal fun formatRouterModelName(id: String): String = when {
    id.isEmpty() -> ""
    id.contains("gemini-4-argon", ignoreCase = true) -> "Gemini 4 Argon"
    id.contains("gemini-4-hydrogen", ignoreCase = true) -> "Gemini 4 Hydrogen"
    id.contains("gemini-3.1-pro", ignoreCase = true) -> "Gemini 3.1 Pro"
    id.contains("gemini-3.5-flash-lite", ignoreCase = true) -> "Gemini 3.5 Flash-Lite"
    id.contains("glm-4.7-flash", ignoreCase = true) -> "GLM-4.7 Flash"
    id.contains("glm-4.6v-flash", ignoreCase = true) -> "GLM-4.6V Flash"
    id.contains("gemini-2.0-flash", ignoreCase = true) -> "Gemini 2.0 Flash"
    id.contains("llama-3.3-70b", ignoreCase = true) -> "Llama 3.3 70B"
    id.contains("llama-3.1-8b", ignoreCase = true) -> "Llama 3.1 8B"
    id.contains("qwen-2.5-coder-32b", ignoreCase = true) -> "Qwen 2.5 Coder 32B"
    id.contains("Qwen2.5-72B", ignoreCase = true) -> "Qwen 2.5 72B"
    id.contains("Qwen2.5-7B", ignoreCase = true) -> "Qwen 2.5 7B"
    id.contains("Qwen2.5-VL-7B", ignoreCase = true) -> "Qwen 2.5 VL 7B"
    id.contains("qwen3.5", ignoreCase = true) -> "Qwen3.5"
    id.contains("qwen-plus", ignoreCase = true) -> "Qwen Plus"
    id.contains("deepseek-v3", ignoreCase = true) -> "DeepSeek-V3"
    id.contains("deepseek-v4", ignoreCase = true) -> "DeepSeek-V4"
    id.contains("deepseek-r1", ignoreCase = true) -> "DeepSeek-R1"
    else -> id.substringAfterLast('/').removeSuffix(":free")
}

/**
 * Single source of truth for the model displayed in both [RouterChip] and the wait line.
 * - While a task is active, always shows the actual model answering the task (never an estimate/fallback model).
 * - When no task is active, shows [routerModel] (the router's current pick).
 */
fun resolveDisplayModel(
    routerModel: ActiveModel,
    runningModelId: String?,
    isTaskRunning: Boolean,
    usages: List<ProviderUsage> = emptyList(),
): ActiveModel {
    if (!isTaskRunning) return routerModel
    val liveId = runningModelId?.takeIf { it.isNotBlank() }
    if (liveId == null) {
        if (routerModel.isEstimate || routerModel.reason == "Configured main model") {
            return ActiveModel(
                id = "",
                name = "No model",
                provider = "",
                usedToday = 0,
                limit = null,
                isEstimate = false,
                quotaRemaining = 0,
                quotaTotal = 0,
                reason = routerModel.reason,
                isPinned = false,
                canPin = false,
                cooldownUntilMs = 0L,
            )
        }
        return routerModel
    }
    if (routerModel.id.equals(liveId, ignoreCase = true)) {
        return if (routerModel.isEstimate) routerModel.copy(isEstimate = false) else routerModel
    }
    val matchedProvider = usages.firstOrNull { u ->
        u.models.any { it.modelId.equals(liveId, ignoreCase = true) }
    }
    val matchedLine = matchedProvider?.models?.firstOrNull {
        it.modelId.equals(liveId, ignoreCase = true)
    }
    val usedToday = matchedLine?.todayRequests ?: RouterUsageTracker.getTodayCalls(liveId)
    val limit = matchedLine?.limit
    val fallbackPct = matchedLine?.remainingPercent ?: QuotaCalculator.compute(
        ActiveModel(
            id = liveId,
            name = formatRouterModelName(liveId),
            provider = matchedProvider?.provider ?: "",
            usedToday = usedToday,
            limit = limit,
            isEstimate = false,
        )
    ).remainingPercent
    return ActiveModel(
        id = liveId,
        name = formatRouterModelName(liveId),
        provider = matchedProvider?.provider ?: "",
        usedToday = usedToday,
        limit = limit,
        isEstimate = false,
        quotaRemaining = if (limit != null) (limit - usedToday).coerceAtLeast(0) else 0,
        quotaTotal = limit ?: 0,
        reason = "Active task model",
        isPinned = false,
        canPin = routerModel.canPin,
        cooldownUntilMs = 0L,
        callStatus = ModelCallStatus.Untested,
        remainingPercent = fallbackPct,
        isExactQuota = matchedLine?.let { !it.isEstimate } ?: false,
        tierLabel = matchedLine?.costInfo ?: matchedProvider?.provider.orEmpty(),
    )
}

/** Extracts the display name for the wait line from the same [ActiveModel] shown by [RouterChip]. */
fun waitModelName(activeModel: ActiveModel): String =
    if (activeModel.name != "No model" && activeModel.id.isNotBlank()) activeModel.name else ""

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

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.Connected)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionDetail = MutableStateFlow<String?>(null)
    override val connectionDetail: StateFlow<String?> = _connectionDetail.asStateFlow()

    private val _thinkingEffort = MutableStateFlow<String?>(null)
    override val thinkingEffort: StateFlow<String?> = _thinkingEffort.asStateFlow()

    private val _events = MutableSharedFlow<SwitchEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SwitchEvent> = _events.asSharedFlow()

    private val _history = mutableListOf<SwitchEvent>()
    override val switchHistory: List<SwitchEvent> get() = _history.toList()

    var pinnedModelId: String? = null
        private set

    override fun pinModel(modelId: String?) {
        pinnedModelId = modelId
        _current.update {
            it.copy(isPinned = modelId != null && (modelId == it.id || modelId == it.name))
        }
    }

    override fun setThinkingEffort(effort: String?) {
        _thinkingEffort.value = effort
    }

    override fun setConnectionStatus(status: ConnectionStatus, detail: String?) {
        _connectionStatus.value = status
        _connectionDetail.value = detail
    }

    override fun setGeminiUsage(usage: GeminiUsage?, callsAtFetch: Int) {
        if (usage == null) return
        val rem = usage.daily?.let { (100.0 - it.fractionUsed * 100.0).coerceIn(0.0, 100.0) }
        val weeklyRem = usage.weekly?.let { (100.0 - it.fractionUsed * 100.0).coerceIn(0.0, 100.0) }
        _current.update { cur ->
            cur.copy(
                remainingPercent = rem ?: cur.remainingPercent,
                isExactQuota = rem != null || cur.isExactQuota,
                windowLabel = if (rem != null) "5h window" else cur.windowLabel,
                resetsAtEpochMs = usage.daily?.resetsAtEpochMs ?: cur.resetsAtEpochMs,
                weeklyRemainingPercent = weeklyRem ?: cur.weeklyRemainingPercent,
                weeklyResetsAtEpochMs = usage.weekly?.resetsAtEpochMs ?: cur.weeklyResetsAtEpochMs,
            )
        }
    }

    override fun refresh() {}

    fun emitSwitch(event: SwitchEvent) {
        _history.add(event)
        _events.tryEmit(event)
    }

    fun setCurrentModel(model: ActiveModel) {
        _current.value = model
    }

    fun updateCurrent(model: ActiveModel) {
        _current.value = model
    }

    fun updateUsage(usageList: List<ProviderUsage>) {
        _usage.value = usageList
    }
}

/**
 * Format chip text: "Model · used/limit today" or "Model · provider · used/limit today".
 */
fun formatChipText(active: ActiveModel, todayLabel: String = "today"): String {
    if (active.name == "No model" || active.id.isEmpty()) {
        return "No model · 0/0 $todayLabel"
    }

    val isGeminiWeb = active.provider.equals("Google Gemini", ignoreCase = true) ||
        active.provider.equals("gemini_web", ignoreCase = true)

    if (isGeminiWeb) {
        val proPlan = "Pro Plan"
        val count = if (active.usedToday > 0) "${active.usedToday} $todayLabel · " else ""
        return "${active.name} · $count$proPlan"
    }

    val quotaPart = when {
        active.limit != null && active.limit > 0 -> "${active.usedToday}/${active.limit} $todayLabel"
        active.isEstimate -> {
            val estLabel = if (todayLabel.equals("aaj", ignoreCase = true)) "anumaan" else "estimate"
            "${active.usedToday} $todayLabel ($estLabel)"
        }
        active.quotaTotal > 0 -> {
            val count = if (active.usedToday > 0) active.usedToday else active.quotaRemaining
            "$count/${active.quotaTotal} $todayLabel"
        }
        else -> "${active.usedToday} $todayLabel"
    }

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
