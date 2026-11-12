package com.meenai.mina.core.router.preset

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.OpenAiCompatModel
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.router.RouterCandidate
import com.meenai.mina.core.router.picker.ModelCandidate
import com.meenai.mina.core.router.picker.ModelRole
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient

/**
 * Provider interface to resolve API keys without hardcoding or logging.
 */
fun interface ApiKeyProvider {
    fun getApiKey(provider: String): String?
}

/**
 * Z.ai preset:
 * - Base URL: https://api.z.ai/api/paas/v4
 * - Models: glm-4.7-flash (Main/Light), glm-4.6v-flash (Vision)
 * - Concurrency: 1
 */
object ZaiPreset {
    const val PROVIDER_ID = "zai"
    const val BASE_URL = "https://api.z.ai/api/paas/v4"
    const val MODEL_FLASH = "glm-4.7-flash"
    const val MODEL_VISION = "glm-4.6v-flash"

    private val sharedSemaphore = Semaphore(1)

    fun createCandidates(
        apiKey: String,
        http: OkHttpClient = OkHttpClient(),
        semaphore: Semaphore = sharedSemaphore,
        dynamicModels: List<ModelCandidate>? = null,
    ): List<RouterCandidate> {
        val candidatesList = dynamicModels?.ifEmpty { null }
        if (candidatesList != null) {
            return candidatesList.map { candidate ->
                val chatModel = ConcurrencyLimitedChatModel(
                    OpenAiCompatModel(
                        baseUrl = BASE_URL,
                        apiKey = apiKey,
                        model = candidate.id,
                        capabilities = Capabilities(
                            toolCalls = candidate.supportsTools,
                            vision = candidate.supportsImages,
                            contextTokens = candidate.contextTokens,
                            reasoning = candidate.supportsReasoning,
                            promptCaching = PromptCaching.Implicit,
                        ),
                        http = http,
                    ),
                    semaphore = semaphore,
                )
                val roles = buildSet {
                    if (candidate.supportsImages) add(ModelRole.Vision)
                    if (candidate.parameterSizeB in 0.1..10.0) add(ModelRole.Light) else add(ModelRole.Main)
                    if (candidate.id.contains("coder", ignoreCase = true) || candidate.id.contains("code", ignoreCase = true)) {
                        add(ModelRole.Coding)
                    }
                }
                RouterCandidate(
                    id = candidate.id,
                    provider = PROVIDER_ID,
                    model = chatModel,
                    curatedRank = candidate.tierRank,
                    isFree = true,
                    isBackup = false,
                    roles = roles,
                    candidateInfo = candidate,
                )
            }
        }

        val flashModel = ConcurrencyLimitedChatModel(
            OpenAiCompatModel(
                baseUrl = BASE_URL,
                apiKey = apiKey,
                model = MODEL_FLASH,
                capabilities = Capabilities(
                    toolCalls = true,
                    vision = false,
                    contextTokens = 128_000,
                    reasoning = false,
                    promptCaching = PromptCaching.Implicit,
                ),
                http = http,
            ),
            semaphore = semaphore,
        )

        val visionModel = ConcurrencyLimitedChatModel(
            OpenAiCompatModel(
                baseUrl = BASE_URL,
                apiKey = apiKey,
                model = MODEL_VISION,
                capabilities = Capabilities(
                    toolCalls = true,
                    vision = true,
                    contextTokens = 128_000,
                    reasoning = false,
                    promptCaching = PromptCaching.Implicit,
                ),
                http = http,
            ),
            semaphore = semaphore,
        )

        return listOf(
            RouterCandidate(
                id = MODEL_FLASH,
                provider = PROVIDER_ID,
                model = flashModel,
                curatedRank = 50,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Main, ModelRole.Light),
            ),
            RouterCandidate(
                id = MODEL_VISION,
                provider = PROVIDER_ID,
                model = visionModel,
                curatedRank = 45,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Vision),
            ),
        )
    }

    suspend fun createLiveCandidates(
        apiKey: String,
        http: OkHttpClient = OkHttpClient(),
        semaphore: Semaphore = sharedSemaphore,
        apiClient: ZaiApiClient = HttpZaiApiClient(BASE_URL, http),
    ): List<RouterCandidate> {
        val models = apiClient.listModels(apiKey)
        return createCandidates(apiKey, http, semaphore, dynamicModels = models)
    }
}

/**
 * ModelScope preset (international):
 * - Base URL: https://api-inference.modelscope.ai/v1
 * - Lowest priority (backup only: credits used per call, so only when all others in cooldown)
 * - Dynamic model list from /v1/models (never hardcoded)
 */
object ModelScopePreset {
    const val PROVIDER_ID = "modelscope"
    const val BASE_URL = "https://api-inference.modelscope.ai/v1"

    suspend fun createCandidates(
        apiKey: String,
        http: OkHttpClient = OkHttpClient(),
        apiClient: ModelScopeApiClient = HttpModelScopeApiClient(BASE_URL, http),
    ): List<RouterCandidate> {
        val models = apiClient.listModels(apiKey)
        return models.map { candidate ->
            val chatModel = OpenAiCompatModel(
                baseUrl = BASE_URL,
                apiKey = apiKey,
                model = candidate.id,
                capabilities = Capabilities(
                    toolCalls = candidate.supportsTools,
                    vision = candidate.supportsImages,
                    contextTokens = candidate.contextTokens,
                    reasoning = false,
                    promptCaching = PromptCaching.Implicit,
                ),
                http = http,
            )
            val roles = buildSet {
                if (candidate.supportsImages) add(ModelRole.Vision)
                if (candidate.parameterSizeB in 0.1..10.0) add(ModelRole.Light) else add(ModelRole.Main)
                if (candidate.id.contains("coder", ignoreCase = true) || candidate.id.contains("code", ignoreCase = true)) {
                    add(ModelRole.Coding)
                }
            }
            RouterCandidate(
                id = candidate.id,
                provider = PROVIDER_ID,
                model = chatModel,
                curatedRank = candidate.tierRank,
                isFree = true,
                isBackup = true,
                roles = roles,
                candidateInfo = candidate,
            )
        }
    }
}
