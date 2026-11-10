package com.meenai.mina.ui.router

import androidx.compose.runtime.staticCompositionLocalOf
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.core.router.ModelHealthTracker
import com.meenai.mina.core.router.RouterUsageTracker
import com.meenai.mina.core.router.picker.CuratedTiers
import com.meenai.mina.core.router.preset.ZaiPreset
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

/**
 * Currently active model metadata for Router UI.
 */
data class ActiveModel(
    val id: String = "",
    val name: String = "No model",
    val provider: String = "",
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
)

enum class ModelStatusKind {
    Ready,
    Cooldown,
    Unsupported,
    BadKey,
    LowBalance;

    fun label(cooldownMin: Int = 0): String = when (this) {
        Ready -> "Ready"
        Cooldown -> "Cooldown $cooldownMin min"
        Unsupported -> "Unsupported"
        BadKey -> "Bad key"
        LowBalance -> "Low balance"
    }
}

/**
 * Per-model quota, usage line and status.
 */
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
)

/**
 * Per-provider usage statistics, models list and cooldown state.
 */
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
) : RouterStatus {

    private var cachedOpenRouterKeyInfo: OpenRouterKeyInfo? = null

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
        if (store != null) {
            scope.launch {
                store.providers.collect {
                    _current.value = computeActiveModel()
                    _usage.value = computeUsage()
                }
            }
        }
        MagicubeLedger.addListener {
            _current.value = computeActiveModel()
            _usage.value = computeUsage()
        }
    }

    override fun refresh() {
        _current.value = computeActiveModel()
        _usage.value = computeUsage()
        refreshOpenRouterKeyAsync()
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

    override fun pinModel(modelId: String?) {
        if (router.candidates.isEmpty()) {
            return
        }
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
            val fallback = getFallbackModel?.invoke()
                ?: store?.configFor(ModelRole.Main)?.let { cfg ->
                    val p = store.provider(cfg.providerId)
                    cfg.model to (p?.name ?: cfg.name)
                }
            if (fallback != null) {
                val (modelId, providerName) = fallback
                val used = RouterUsageTracker.getTodayCalls(modelId)
                val fallbackReason: String = if (!status.reason.isNullOrBlank() && status.reason != "No model available") {
                    status.reason!!
                } else {
                    "Configured main model"
                }
                return ActiveModel(
                    id = modelId,
                    name = formatModelName(modelId),
                    provider = formatProvider(providerName),
                    usedToday = used,
                    limit = null,
                    isEstimate = true,
                    quotaRemaining = 0,
                    quotaTotal = 0,
                    reason = fallbackReason,
                    isPinned = false,
                    canPin = false,
                    cooldownUntilMs = 0L,
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
                reason = "No model available",
                isPinned = false,
                canPin = false,
                cooldownUntilMs = 0L,
            )
        }

        val id = status.currentModel ?: active.id
        val provider = status.provider ?: active.provider
        val isPinned = router.pinnedModelId != null && (router.pinnedModelId == id)
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

        if (provider.equals("openrouter", ignoreCase = true) && cachedOpenRouterKeyInfo != null) {
            limit = cachedOpenRouterKeyInfo?.limit
            if (limit != null) {
                isEstimate = false
                usedToday = maxOf(usedToday, cachedOpenRouterKeyInfo?.usage ?: 0)
            }
        }

        val effectiveReason: String = if (status.reason.isNullOrBlank() || status.reason == "No model available") {
            router.determineReason(active)
        } else {
            status.reason!!
        }

        return ActiveModel(
            id = id,
            name = formatModelName(id),
            provider = formatProvider(provider),
            usedToday = usedToday,
            limit = limit,
            isEstimate = isEstimate,
            quotaRemaining = if (limit != null) (limit - usedToday).coerceAtLeast(0) else 0,
            quotaTotal = limit ?: 0,
            reason = effectiveReason,
            isPinned = isPinned,
            canPin = true,
            cooldownUntilMs = cooldownUntil,
            callStatus = callStatus,
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

        return providerEntries.map { (pName, providerObj) ->
            val providerKeyName = providerObj?.name ?: pName
            val isZai = providerObj?.let { AppFreeKeyRouter.isZaiProvider(it) } ?: pName.equals("zai", ignoreCase = true)
            val isModelScope = providerObj?.let { AppFreeKeyRouter.isModelScopeProvider(it) } ?: pName.equals("modelscope", ignoreCase = true)
            val isOpenRouter = providerObj?.let { AppFreeKeyRouter.isOpenRouterProvider(it) } ?: pName.equals("openrouter", ignoreCase = true)

            val providerId = providerObj?.id ?: pName
            val isBadKey = router.cooldownStore.isKeyBad(providerKeyName) ||
                router.cooldownStore.isKeyBad(providerId) ||
                (if (isZai) router.cooldownStore.isKeyBad("zai") else false) ||
                (if (isModelScope) router.cooldownStore.isKeyBad("modelscope") else false) ||
                (if (isOpenRouter) router.cooldownStore.isKeyBad("openrouter") else false)

            val modelIds = linkedSetOf<String>()

            // 1. Router candidates matching this provider
            router.candidates.filter {
                it.provider.equals(pName, ignoreCase = true) ||
                    it.provider.equals(providerId, ignoreCase = true) ||
                    (isZai && it.provider.equals("zai", ignoreCase = true)) ||
                    (isModelScope && it.provider.equals("modelscope", ignoreCase = true)) ||
                    (isOpenRouter && it.provider.equals("openrouter", ignoreCase = true))
            }.forEach { modelIds.add(it.id) }

            // 2. Models from provider object
            providerObj?.let { p ->
                val freeModels = p.models?.filter { it.id.endsWith(":free") }.orEmpty()
                if (isOpenRouter && freeModels.isNotEmpty()) {
                    freeModels.forEach { modelIds.add(it.id) }
                } else if (isModelScope && p.models?.isNotEmpty() == true) {
                    p.models.filter { AppFreeKeyRouter.isModelScopeChatModel(it) }.forEach { modelIds.add(it.id) }
                } else if (p.models?.isNotEmpty() == true && p.models.size <= 8) {
                    p.models.forEach { modelIds.add(it.id) }
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

            val lines = modelIds.map { mId ->
                var todayRequests = RouterUsageTracker.getTodayCalls(mId)
                val isUnsupported = router.cooldownStore.isUnsupported(mId)
                val cooldownMs = router.cooldownStore.getRemainingCooldownMs(mId)
                val isLowBalance = isModelScope && !MagicubeLedger.canAffordModelScope()

                val status = when {
                    isBadKey -> ModelStatusKind.BadKey
                    isUnsupported -> ModelStatusKind.Unsupported
                    isLowBalance -> ModelStatusKind.LowBalance
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

                val displayName = if (isModelScope) mId else formatModelName(mId)

                ModelQuotaLine(
                    modelId = mId,
                    modelName = displayName,
                    todayRequests = todayRequests,
                    limit = limit,
                    isEstimate = isEstimate,
                    status = status,
                    cooldownMinutes = cooldownMin,
                    costPerCall = costPerCall,
                    costInfo = costInfo,
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

            val magicubeBal = if (isModelScope && MagicubeLedger.isBalanceKnown) MagicubeLedger.totalBalance() else null

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
        id.contains("qwen-plus", ignoreCase = true) -> "Qwen Plus"
        id.contains("deepseek-v3", ignoreCase = true) -> "DeepSeek-V3"
        id.contains("deepseek-v4", ignoreCase = true) -> "DeepSeek-V4"
        id.contains("deepseek-r1", ignoreCase = true) -> "DeepSeek-R1"
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

    override fun refresh() {}

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
 * Format chip text: "Model · used/limit today" or "Model · provider · used/limit today".
 */
fun formatChipText(active: ActiveModel, todayLabel: String = "today"): String {
    if (active.name == "No model" || active.id.isEmpty()) {
        return "No model · 0/0 $todayLabel"
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
