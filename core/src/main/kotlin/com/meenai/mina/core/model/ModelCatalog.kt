package com.meenai.mina.core.model

import com.meenai.mina.core.net.ProxyFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A model listed by the service provider. If the capability field is null, it means that the interface
 * is not provided, and the caller will use its own initial value. I won’t guess here.
 *
 * Some service providers use different interface protocols for models under one Key, and the protocol
 * and address (`api` table) are written for each model in the list. [endpoint] is the one that Mina will
 * say. When calling this model, select the adapter and address according to it; when it is null, select
 * the interface type of the service provider. [unsupportedApis] is a protocol name that is clearly
 * stated, but Mina cannot say it: such a model call must fail and will not be selected (see [usable]).
 * [chat] is an interface, but it does not say it is a dialogue model (`type` is chat / embedding /
 * rerank..., or whether there is text in the output modal); it is null if it is not said.
 */
data class ListedModel(
    val id: String,
    val displayName: String = "",
    val toolCalls: Boolean? = null,
    val vision: Boolean? = null,
    val contextTokens: Int? = null,
    val reasoning: Boolean? = null,
    val endpoint: ModelEndpoint? = null,
    val unsupportedApis: List<String> = emptyList(),
    val chat: Boolean? = null,
    /** The thinking gear clearly given by the service provider; null means unknown, and the empty list means there is no optional gear. Keep the order of service providers. */
    val reasoningEfforts: List<String>? = null,
    /**
     * The service provider specifies the thinking methods accepted by this model (those supported in the
     * Anthropic list `capabilities.thinking.types`, such as `adaptive`, `enabled`, `disabled`); null
     * means not mentioned.
     */
    val thinkingTypes: List<String>? = null,
    /**
     * The interface specifies which protocols this model can use (some aggregation services are given as
     * `supported_endpoints`, such as `/chat/completions`, `/messages`); null means not mentioned.
     * Calling with an interface type that is not among them must fail, see [ModelProtocol.ofPath].
     */
    val protocols: List<ModelProtocol>? = null,
) {
    val usable: Boolean get() = endpoint != null || unsupportedApis.isEmpty()
}

/** `api` in the model list represents the recognized interface protocol; [wire] is the key name in the table. */
enum class ModelProtocol(val wire: String) {
    /** OpenAI Chat Completions, called by [OpenAiCompatModel]. */
    ChatCompletions("chat-completions"),
    /** Anthropic Messages, called by [AnthropicModel]. */
    Messages("messages"),
    /** Gemini generateContent, called by [GeminiModel]. */
    GenerateContent("generate-content"),
    /** OpenAI Responses, called by [ResponsesModel]. Ranked last: the same model has also written the first few, so the first few are still used. */
    Responses("responses");

    companion object {
        fun of(wire: String?): ModelProtocol? = entries.firstOrNull { it.wire == wire }

        /**
         * Identify the protocol by endpoint path: Some aggregation services use `supported_endpoints` to
         * indicate which endpoints each model can reach (`/chat/completions`, `/messages`, `/responses`,
         * `/v1beta/models/{model}:generateContent`), and only write the path but not the protocol name. Not
         * recognized is null.
         *
         * Only the `…:generateContent` Gemini generation endpoint is recognized: the bare `/models` (or
         * `/v1/models`, `/provider/v1/models`) is the endpoint of the column model. Being listed does
         * not mean that it can be adjusted. If it is determined as "can be generated", the model that
         * should not be tried will be tested once, so it is better not to recognize it.
         */
        fun ofPath(path: String?): ModelProtocol? {
            val p = path?.trim()?.trimEnd('/')?.lowercase() ?: return null
            return when {
                p.endsWith("/chat/completions") -> ChatCompletions
                p.endsWith("/messages") -> Messages
                p.endsWith("/responses") -> Responses
                p.endsWith(":generatecontent") -> GenerateContent
                else -> null
            }
        }
    }
}

/**
 * A model individually specifies the interface: the protocol, the address given in the list ([baseUrl],
 * which is the root of this protocol, such as `…/anthropic/v1`), and the field in which the compatible
 * interface puts the thinking content ([reasoningField], such as `reasoning_content`; it is not said to
 * be null).
 */
data class ModelEndpoint(val protocol: ModelProtocol, val baseUrl: String, val reasoningField: String? = null) {
    /**
     * Base URL passed to the adapter. The adapter itself connects to the version path: [AnthropicModel]
     * connects to `/v1/messages`, [GeminiModel] connects to `/v1beta/models/…`, so the paragraph at the
     * end of the list address is removed to avoid duplication; Chat Completions only connects to
     * `/chat/completions`, and Responses only connects to `/responses`. Use them as they are.
     */
    val adapterBaseUrl: String
        get() {
            val url = baseUrl.trim().trimEnd('/')
            return when (protocol) {
                ModelProtocol.ChatCompletions, ModelProtocol.Responses -> url
                ModelProtocol.Messages -> url.removeSuffix("/v1")
                ModelProtocol.GenerateContent -> url.removeSuffix("/v1beta")
            }
        }
}

/** Why the column model failed; [text] is the reason for people to see. */
enum class ListFailure(val text: String) {
    BadUrl("接口地址不对,要以 http:// 或 https:// 开头"),
    Auth("API Key 无效或没有权限"),
    Quota("账户额度已用完"),
    RateLimited("请求太频繁,请稍后再试"),
    Network("连不上这个地址,请检查网络和接口地址"),
    ProxyUnreachable("连不上网络代理,或代理没有转发这个请求;请检查设置 › 网络代理"),
    ProxyAuth("网络代理拒绝了账号或密码,请检查设置 › 网络代理"),
    NotSupported("这个服务不提供模型列表"),
    Server("服务暂时出错,请稍后再试"),
    BadResponse("返回的内容看不懂,请检查接口地址和接口类型"),
    ;

    companion object {
        /** The request was not sent: report proxy failures as such and treat anything else as unreachable. */
        fun of(e: Throwable): ListFailure = when ((e as? ModelError.Proxy)?.failure) {
            ProxyFailure.Unreachable -> ProxyUnreachable
            ProxyFailure.AuthFailed -> ProxyAuth
            null -> Network
        }
    }
}

sealed interface ModelListResult {
    data class Ok(val models: List<ListedModel>) : ModelListResult
    data class Failed(val reason: ListFailure, val detail: String = "") : ModelListResult {
        val message: String get() = if (detail.isEmpty()) reason.text else "${reason.text}($detail)"
    }
}

/**
 * Use the public "list model" interface to get the available models:
 * - - OpenAI compatible: `GET {base}/models`(Bearer). Only return the id truthfully; with
 * `context_length`,
 * `architecture.input_modalities`, `supported_parameters` and other fields (some aggregation services
 * will provide) the ability to fill in only. There are also other ways of writing:
 * `capabilities.tools.supported`, `capabilities.reasoning.supported`, `limit.context`,
 * `modalities.input` (you can see the picture if you have `image/…`), and the `api` table that gives
 * addresses according to the protocol (see [ListedModel.endpoint]). What is listed using the ChatGPT
 * account is the `models` table: the name is in `slug`, the display name is in `display_name`, only
 * `visibility` is left as `list`, and the order of the server is maintained; `input_modalities`,
 * `context_window`, and `supported_reasoning_levels` are filled in accordingly.
 * - - Anthropic: `GET {base}/v1/models`, press `after_id` to turn the page; `max_input_tokens` is the
 * context,
 * `capabilities.image_input` / `capabilities.thinking` is about looking at pictures and reasoning. Does
 * not give tool call information. The thinking gears are filled in according to `capabilities.effort`
 * and `capabilities.thinking.types` one by one (see [anthropicEfforts]).
 * - - Gemini: `GET {base}/v1beta/models`, press `pageToken` to turn the page; only `generateContent` is
 * left,
 * `inputTokenLimit` is context, `thinking` is inference. It does not provide tool calling and image
 * viewing information, nor does it provide thinking gear.
 */
class ModelCatalog(private val http: OkHttpClient) {
    // Headers of each method: the headers carried by each request (see [ClientHeaders]); the Key header
    // is added here.
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun openAiCompat(baseUrl: String, apiKey: String?, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val url = endpoint(baseUrl, "/models") ?: return@run badUrl()
        val req = Request.Builder().url(url).get().withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }.build()
        val root = fetch(req).getOrElse { return@run it.asFailure() }
        val data = (root["data"] ?: root["models"]) as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
        ModelListResult.Ok(data.mapNotNull { (it as? JsonObject)?.let(::openAiModel) }.distinctBy { it.id })
    }

    suspend fun anthropic(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("limit", "1000")
                .apply { if (after != null) addQueryParameter("after_id", after) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers)
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["data"] as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
            all += data.mapNotNull { (it as? JsonObject)?.let(::anthropicModel) }
            after = root.str("last_id")
            if (root.bool("has_more") != true || after == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    suspend fun gemini(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1beta/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("pageSize", "1000")
                .apply { if (token != null) addQueryParameter("pageToken", token) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers).header("x-goog-api-key", apiKey).build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["models"] as? JsonArray ?: JsonArray(emptyList())
            all += data.mapNotNull { (it as? JsonObject)?.let(::geminiModel) }
            token = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    private fun openAiModel(o: JsonObject): ListedModel? {
        // When using the ChatGPT account list model (`models` table), the name is in `slug`, the display
        // name is in `display_name`, and only `visibility` is left in the list
        o.str("visibility")?.let { if (it != "list") return null }
        val id = (o.str("id") ?: o.str("slug"))?.takeIf { it.isNotBlank() } ?: return null
        val arch = o["architecture"] as? JsonObject
        val inputs = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val modality = arch?.str("modality")?.substringBefore("->")
        val outputs = ((arch?.get("output_modalities") ?: o.obj("modalities")?.get("output")) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: arch?.str("modality")?.takeIf { "->" in it }?.substringAfter("->")?.split('+')
        val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val caps = o["capabilities"] as? JsonObject
        val modalIn = ((o.obj("modalities")?.get("input") ?: o["input_modalities"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val reasoningLevels = (o["supported_reasoning_levels"] ?: caps?.obj("reasoning")?.get("effort")) as? JsonArray
        val efforts = reasoningLevels?.mapNotNull { level ->
            (((level as? JsonObject)?.get("effort") ?: level) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct()
        val api = o.obj("api")
        // Some aggregation services do not provide the `api` table and use `supported_endpoints`
        // instead. Which endpoints can be used to write this model?
        val protocols = (o["supported_endpoints"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ModelProtocol::ofPath) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        // A model may write several protocols: Take the first one in the order of Chat Completions,
        // Messages, generateContent, and Responses
        val endpoint = api?.let { a ->
            ModelProtocol.entries.firstNotNullOfOrNull { p ->
                val e = a.obj(p.wire) ?: return@firstNotNullOfOrNull null
                val url = e.str("base_url")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                ModelEndpoint(p, url, e.obj("compatibility")?.str("reasoning_field"))
            }
        }
        return ListedModel(
            id = id,
            displayName = (o.str("name") ?: o.str("display_name")).orEmpty(),
            toolCalls = params?.let { "tools" in it } ?: caps?.obj("tools")?.bool("supported")
                ?: caps?.bool("function_calling") ?: caps?.bool("tool_calls"),
            vision = inputs?.let { "image" in it } ?: modalIn?.let { m -> m.any { it == "image" || it.startsWith("image/") } }
                ?: modality?.let { "image" in it } ?: o.bool("supports_image_in") ?: caps?.bool("vision"),
            contextTokens = o.int("context_length") ?: o.int("context_window") ?: o.int("max_context_length")
                ?: o.obj("limit")?.int("context"),
            reasoning = params?.let { "reasoning" in it || "include_reasoning" in it } ?: caps?.obj("reasoning")?.bool("supported")
                ?: o.bool("supports_reasoning") ?: reasoningLevels?.let { it.isNotEmpty() },
            endpoint = endpoint,
            reasoningEfforts = efforts,
            unsupportedApis = if (endpoint == null) api?.keys?.toList().orEmpty() else emptyList(),
            protocols = protocols,
            chat = when (o.str("type")?.lowercase()) {
                in CHAT_TYPES -> true
                in NON_CHAT_TYPES -> false
                // Those that only produce pictures and sounds are not dialogue models; those that
                // produce text are not separately recorded.
                else -> outputs?.takeIf { "text" !in it }?.let { false }
            },
        )
    }

    private fun anthropicModel(o: JsonObject): ListedModel? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val caps = o["capabilities"] as? JsonObject
        val thinking = caps?.obj("thinking")
        val types = thinking?.obj("types")?.supportedKeys()
        return ListedModel(
            id = id,
            displayName = o.str("display_name").orEmpty(),
            vision = (caps?.get("image_input") as? JsonObject)?.bool("supported"),
            contextTokens = o.int("max_input_tokens"),
            reasoning = thinking?.bool("supported"),
            reasoningEfforts = types?.let { anthropicEfforts(caps?.obj("effort"), it) },
            thinkingTypes = types,
        )
    }

    /**
     * The optional thinking gears of the Anthropic model: only given when it accepts adaptive thinking
     * (`adaptive`, the gear is `output_config.effort`), the gears are the supported levels marked in
     * `capabilities.effort`; it also accepts turning off thinking (`disabled`), with a gear `none` in
     * front. Only those who think based on budget (`enabled` + `budget_tokens`) or regardless of grade
     * are accepted. There is no optional grade.
     */
    private fun anthropicEfforts(effort: JsonObject?, types: List<String>): List<String> {
        val levels = effort?.takeIf { it.bool("supported") == true }?.supportedKeys().orEmpty()
            .sortedBy { EFFORT_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: EFFORT_ORDER.size }
        if ("adaptive" !in types || levels.isEmpty()) return emptyList()
        return listOfNotNull("none".takeIf { "disabled" in types }) + levels
    }

    /** `{"a":{"supported":true},"b":{"supported":false},"supported":true}` is marked with supported subkeys: `[a]`. */
    private fun JsonObject.supportedKeys(): List<String> = keys.filter { (this[it] as? JsonObject)?.bool("supported") == true }

    private fun geminiModel(o: JsonObject): ListedModel? {
        val id = o.str("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods != null && "generateContent" !in methods) return null
        return ListedModel(
            id = id,
            displayName = o.str("displayName").orEmpty(),
            contextTokens = o.int("inputTokenLimit"),
            reasoning = o.bool("thinking"),
        )
    }

    private class Failure(val result: ModelListResult.Failed) : Exception(result.message)

    private fun Throwable.asFailure(): ModelListResult.Failed =
        (this as? Failure)?.result ?: ModelListResult.Failed(ListFailure.Network)

    private suspend fun fetch(req: Request): Result<JsonObject> {
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Failure(ModelListResult.Failed(ListFailure.of(e))))
        }
        if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
        val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (e: Exception) { null }
            ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
        return Result.success(root)
    }

    private fun failureFor(status: Int, body: String): ModelListResult.Failed = when {
        status == 401 || status == 403 -> ModelListResult.Failed(ListFailure.Auth)
        // Gemini returns 400 INVALID_ARGUMENT for invalid Key, the reason is written in the content
        status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) -> ModelListResult.Failed(ListFailure.Auth)
        status == 402 || body.contains("insufficient_quota") -> ModelListResult.Failed(ListFailure.Quota)
        status == 429 -> ModelListResult.Failed(ListFailure.RateLimited)
        status == 404 || status == 405 || status == 501 -> ModelListResult.Failed(ListFailure.NotSupported)
        status in 500..599 -> ModelListResult.Failed(ListFailure.Server, "HTTP $status")
        else -> ModelListResult.Failed(ListFailure.BadResponse, "HTTP $status")
    }

    private fun badUrl() = ModelListResult.Failed(ListFailure.BadUrl)

    private fun endpoint(baseUrl: String, path: String) = (baseUrl.trim().trimEnd('/') + path).toHttpUrlOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private companion object {
        /** Page turning upper limit: prevent the server from always giving the next page. */
        const val MAX_PAGES = 10
        /** Anthropic's `capabilities.effort` are arranged alphabetically; the interfaces are arranged from shallower to deeper, with those you don't recognize last. */
        val EFFORT_ORDER = listOf("low", "medium", "high", "xhigh", "max")
        /** The way to write `type` in the list: it is a dialogue model, and it is not (vector, rearrangement, drawing, speech, review). */
        val CHAT_TYPES = setOf("chat", "llm", "language", "text", "vlm", "code")
        val NON_CHAT_TYPES = setOf(
            "embedding", "embeddings", "rerank", "reranker", "image", "images", "video", "audio", "tts", "stt", "asr",
            "speech", "transcription", "moderation",
        )
    }
}
