package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Common to all adapters: The error content of HTTP 400 / 403 / 404 says "this model is not valid",
 * rather than identifying which one it is when the Key or the request itself is incorrect. Each
 * company's error reporting format and wording are different. Here we only check whether there are any
 * of these types of statements in the content (regardless of capitalization):
 * - - Calling tools is not supported ("does not support tools" "function calling is not supported" "does
 * not support tools"...) → [ModelError.ToolsUnsupported];
 * - - This model cannot use the current interface (the service provider requires another set of
 * interface shapes, such as "Model... must be called via /messages",
 * "unsupported_model"..., see [ENDPOINT_MISMATCH]) → [ModelError.ModelNotAvailable], bring the original
 * words of the server: Just change a model or change the interface type, handle it the same as "This
 * account can't use it", and then try the next one;
 * - - This account cannot use this model ("not activated" "not authorized to access this model" "does
 * not have access to the model"
 * "Model.AccessDenied" "Not activated" "No permission"...), or this model has been offline ("no longer
 * available" "has been retired" "model... is deprecated" "Offline"...) → [ModelError.ModelNotAvailable],
 * bring the original words of the server;
 * - - There is no such model ("model_not_found" "model not found" "model does not exist" "not found for
 * API version" "model does not exist"...)
 * → [ModelError.ModelNotFound], bring the original words ([jsonDetail]) in JSON. If 404 is not mentioned
 * in the first two categories, the caller will still handle it as the model cannot be found (also bring
 * [jsonDetail]); if it is not recognized, it will be null, and the caller will handle it as the status
 * code. 401 is always a Key problem, don’t look at it.
 *
 * HTTP 429 is generally a traffic limit or the quota is used up, which is handled by the caller; only
 * when the content says that the quota of this account on this model is zero ("limit: 0" "doesn't have a
 * free quota tier"..., see [QUOTA_ZERO]), this model is considered unusable, and
 * [ModelError.ModelNotAvailable]: It won't be better if you wait as long as you want, so you need to
 * change the model.
 */
object ModelAccessErrors {
    private val json = Json { ignoreUnknownKeys = true }

    private val TOOLS_UNSUPPORTED = listOf(
        "does not support tools", "does not support tool", "doesn't support tools", "tools is not supported", "tools are not supported",
        "tool use is not supported", "tool calling is not supported", "function calling is not supported",
        "does not support function calling", "不支持工具", "不支持函数调用", "不支持 function call",
    )

    private val NOT_AVAILABLE = listOf(
        "not activated", "not been activated", "model.accessdenied", "model access denied", "accessdenied.unpurchased",
        "not authorized to access this model", "not authorized to use this model", "not authorized to access the model",
        "do not have access to the model", "does not have access to the model", "does not have access to model",
        "do not have access to model", "no access to model", "no permission to access the model", "no permission to use the model",
        "model_not_available", "model is not available", "model not available", "not available for your account",
        "has no provider supported", "no provider supported", "provider not supported",
        "未开通", "没有开通", "尚未开通", "无权限", "没有权限使用", "无权访问", "无权使用",
        // 模型已经下线
        "no longer available", "has been retired", "has been discontinued", "has been shut down",
        "已下线", "已停用", "已下架", "已停止服务",
    )

    /**
     * Saying "this model cannot use this interface": A service provider separates the models according
     * to the interface shape (for example, if a certain series of models is only hung on `/messages`,
     * calling it on the OpenAI compatible path will always result in 400 "must be called via
     * /provider/v1/messages"). It will work if you change the interface type or the following model.
     * This is not a problem with the key, address or network. When this model doesn't work, try the next
     * one. It only counts when the error reported refers to the model (see [aboutTheModel]): "Your API
     * key is not supported for this endpoint" refers to the Key, and is still handed over to the caller
     * for processing according to the status code (403 is a Key problem, and the entire save is still
     * blocked).
     */
    private val ENDPOINT_MISMATCH = listOf(
        "unsupported_model",
        "must be called via",
        "only supported in v1/",
        "not supported for this endpoint",
        "unsupported for this endpoint",
    )

    /** Saying "model... has been deprecated/offline": Just saying "deprecated" may also mean that a certain parameter has been deprecated. It must be mentioned in conjunction with model. */
    private val MODEL_RETIRED = Regex("""\bmodels?\b[^;,\n]{0,80}?\b(?:is|has been|was)\s+(?:deprecated|retired)|\bdeprecated\s+models?\b""")

    /** 429 says that the account's quota on this model is zero: `limit: 0`, `"quotaValue": "0"` is counted, or it is clearly stated that there is no free quota. */
    private val QUOTA_ZERO = Regex(
        """\blimit\s*:\s*0(?![\d.])|"quotavalue"\s*:\s*"?0"?\s*[,}]|(?:doesn't|does not|don't|do not) have (?:a |any )?free quota tier|has no free quota tier""",
    )

    private val NOT_FOUND = listOf(
        "model_not_found", "model not found", "model does not exist", "model not exist", "no such model", "unknown model",
        "not found for api version", "模型不存在", "找不到模型",
    )

    /** [model] is the requested model name. */
    fun of(status: Int, body: String, model: String): ModelError? {
        val lower = body.lowercase()
        if (status == 429) return if (QUOTA_ZERO.containsMatchIn(lower)) ModelError.ModelNotAvailable(model, detail(body)) else null
        if (status != 400 && status != 403 && status != 404) return null
        return when {
            TOOLS_UNSUPPORTED.any { it in lower } -> ModelError.ToolsUnsupported(model)
            ENDPOINT_MISMATCH.any { it in lower } && aboutTheModel(lower, body, model) -> ModelError.ModelNotAvailable(model, detail(body))
            NOT_AVAILABLE.any { it in lower } || MODEL_RETIRED.containsMatchIn(lower) -> ModelError.ModelNotAvailable(model, detail(body))
            status != 404 && NOT_FOUND.any { it in lower } -> ModelError.ModelNotFound(model, jsonDetail(body))
            else -> null
        }
    }

    /** The error reported refers to the model itself: model (including `unsupported_model`, `"param":"model"`) is mentioned, or the requested model name is directly clicked in the original words. */
    private fun aboutTheModel(lower: String, body: String, model: String): Boolean =
        "model" in lower || (model.isNotBlank() && model.lowercase() in (said(body) ?: body).lowercase())

    /** The original words from the server: ([said]) in JSON, either JSON or the entire paragraph; if it is too long, it is truncated ([clip]). */
    internal fun detail(body: String): String = clip(said(body) ?: body)

    /** Same as [detail], but as long as the original words in JSON are not JSON (the HTML page and empty content returned by the reverse proxy when the address is incorrect) will be empty. */
    internal fun jsonDetail(body: String): String = said(body)?.let(::clip).orEmpty()

    /** Remove the leading and trailing blanks; truncate it if it exceeds [DETAIL_MAX] characters, and add "..." at the end to see that there is more behind it. */
    internal fun clip(text: String): String =
        text.trim().let { if (it.length <= DETAIL_MAX) it else it.take(DETAIL_MAX).trimEnd() + "…" }

    /** None of the `error.message`, `error` (when it is a string), `message`, `msg`, `detail` in the JSON object (some services return an array, take the first one) is null. */
    private fun said(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val root = parsed as? JsonObject ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject
        val err = root?.get("error")
        return (err as? JsonObject)?.str("message") ?: (err as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: root?.str("message") ?: root?.str("msg") ?: root?.str("detail")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private const val DETAIL_MAX = 160
}
