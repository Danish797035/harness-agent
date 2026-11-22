package com.meenai.mina.core.gemini

import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.net.NetProxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.random.Random

object GeminiWebClient {

    const val BASE_URL = "https://gemini.google.com"
    const val INIT_URL = "https://gemini.google.com/app"
    const val STREAM_GENERATE_ENDPOINT = "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
    const val BATCH_EXECUTE_ENDPOINT = "https://gemini.google.com/_/BardChatUi/data/batchexecute"
    /** Gemini 网页 Usage limits 页的 GraphQL 执行端点(和网页发的是同一个请求)。 */
    const val GRAPHQL_ENDPOINT = "https://gemini.google.com/_/BardChatUi/graphql/schemas/GEMINI_WEB_GRAPHQL/executeQuery"
    /** UsagePageQuery 的持久化查询 id(网页打包时固定)。 */
    const val USAGE_QUERY_ID = "2/yL2v1aYbA346hkaeglNZmTMJfO37mMT1xFprjCS/Pj4="

    const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val MODEL_HEADER_KEY = "x-goog-ext-525001261-jspb"

    val ACCESS_TOKEN_REGEX = Regex(""""SNlM0e":\s*"([^"]+)"""")
    val BUILD_LABEL_REGEX = Regex(""""cfb2h":\s*"([^"]+)"""")
    val SESSION_ID_REGEX = Regex(""""FdrFJe":\s*"([^"]+)"""")
    val EMAIL_REGEX = Regex(""""o3H74c":\s*"([^"]+)"""")
    val SERVER_TOKEN_REGEX = Regex(""""PI9WOb":\s*"([^"]+)"""")
    val OXYGEN_URL_REGEX = Regex(""""dSupif":\s*"([^"]+)"""")
    val AUTH_USER_REGEX = Regex(""""QrtxK":\s*"?([^",}]+)"?""")
    val MENDEL_FLAGS_REGEX = Regex(""""GruApd":\s*"([^"]+)"""")

    val geminiWebHttp: OkHttpClient by lazy {
        // Same proxy/DNS rules as the other model clients: a signed-in Google
        // session is useless when the request cannot leave the device.
        NetProxy.shared.client(
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .cookieJar(cookieJar)
        )
    }

    /** Rotated Google cookies; see [GeminiCookieJar]. */
    internal val cookieJar = GeminiCookieJar()

    fun seedCookies(header: String) = cookieJar.seed(header)

    fun clearCookies() = cookieJar.clear()

    /** Cookies as they stand now, including values Google rotated after login. */
    fun cookieSnapshot(): String = cookieJar.snapshot()

    /** One `Cookie:` header value: no line breaks, no empty `;;`, no trailing `;`. */
    fun sanitizeCookieHeader(raw: String): String = raw
        .replace(Regex("[\\r\\n]+"), ";")
        .split(';')
        .map { it.replace(Regex("[\\t ]+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("; ")

    /**
     * Accepts both a full cookie header and a bare `__Secure-1PSID` value (what
     * the manual sign-in box asks for). A raw value can contain `=`, so it must
     * not be mistaken for `name=value`.
     */
    fun normalizeCookieHeader(raw: String): String {
        val text = raw.trim().trim('"')
        if (text.isEmpty()) return ""
        val single = sanitizeCookieHeader(text)
        return if (single.contains("__Secure-1PSID") || single.contains(';')) single
        else sanitizeCookieHeader("__Secure-1PSID=$single")
    }

    val DEFAULT_MODELS: List<ListedModel> = listOf(
        ListedModel(
            id = "gemini-4-argon",
            displayName = "Gemini 4 Argon (Code and analysis)",
            toolCalls = true,
            vision = true,
            contextTokens = 1_000_000,
            reasoning = true,
            reasoningEfforts = listOf("low", "medium", "high"),
            chat = true
        ),
        ListedModel(
            id = "gemini-4-hydrogen",
            displayName = "Gemini 4 Hydrogen (Everyday help)",
            toolCalls = true,
            vision = true,
            contextTokens = 1_000_000,
            reasoning = true,
            reasoningEfforts = listOf("low", "medium", "high"),
            chat = true
        ),
        ListedModel(
            id = "gemini-3.1-pro",
            displayName = "Gemini 3.1 Pro (Detailed research)",
            toolCalls = true,
            vision = true,
            contextTokens = 2_000_000,
            reasoning = true,
            reasoningEfforts = listOf("low", "medium", "high"),
            chat = true
        ),
        ListedModel(
            id = "gemini-3.5-flash-lite",
            displayName = "Gemini 3.5 Flash-Lite (Quick answers)",
            toolCalls = true,
            vision = true,
            contextTokens = 1_000_000,
            reasoning = false,
            chat = true
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun extractSessionFromHtml(html: String, cookies: String): GeminiWebSession? {
        val snlm0e = ACCESS_TOKEN_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
        val cfb2h = BUILD_LABEL_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        val fdrfje = SESSION_ID_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        val email = EMAIL_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        return GeminiWebSession(
            cookies = cookies,
            snlm0e = snlm0e,
            cfb2h = cfb2h,
            fdrfje = fdrfje,
            email = email,
            serverToken = SERVER_TOKEN_REGEX.find(html)?.groupValues?.get(1).orEmpty(),
            oxygenUrl = OXYGEN_URL_REGEX.find(html)?.groupValues?.get(1).orEmpty(),
            authUserIndex = AUTH_USER_REGEX.find(html)?.groupValues?.get(1)?.trim()?.trim('"').orEmpty(),
            mendelFlags = MENDEL_FLAGS_REGEX.find(html)?.groupValues?.get(1).orEmpty(),
            signedIn = true
        )
    }

    suspend fun fetchSessionFromCookies(http: OkHttpClient, cookies: String): GeminiWebSession {
        val header = normalizeCookieHeader(cookies)
        val request = Request.Builder()
            .url(INIT_URL)
            .header("User-Agent", CHROME_USER_AGENT)
            .header("Cookie", header)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "https://accounts.google.com/")
            .get()
            .build()

        val response = http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                throw ModelError.SignInExpired("Google Gemini (HTTP ${it.code})")
            }
            val html = it.body?.string().orEmpty()
            return extractSessionFromHtml(html, header)
                ?: throw ModelError.SignInExpired("Failed to extract SNlM0e token from Gemini page. Please sign in again.")
        }
    }

    /**
     * 读取 Gemini 账号自己的用量:和网页 Usage limits 页发同一个 GraphQL 请求
     * (`GEMINI_WEB_GRAPHQL/executeQuery` + UsagePageQuery 的持久化 id)。
     *
     * 网页那边用 SNlM0e 当 XSRF token,body 是 `{querySignature, variables}`,
     * 回复是一个 Relay 数组。这里照做;401/403 时用 Cookie 重新取一次 /app 换新 token 再试一次。
     * 任何一步失败都返回 usage = null,调用方退回本机计数。
     */
    suspend fun fetchUsage(http: OkHttpClient, session: GeminiWebSession): GeminiUsageFetch {
        var current = session
        // 和聊天共用 cookie jar;只在它是空的时候种一次,免得把聊天刚轮换的新 cookie 覆盖回旧的
        if (cookieSnapshot().isBlank()) seedCookies(current.cookies)
        if (!current.signedIn) return GeminiUsageFetch(null, current)

        var attempt = requestUsage(http, current)
        if (attempt.usage == null && attempt.unauthorized) {
            val refreshed = runCatching {
                fetchSessionFromCookies(http, cookieSnapshot().ifBlank { current.cookies })
            }.getOrNull()
            if (refreshed != null) {
                current = refreshed.copy(email = current.email.ifBlank { refreshed.email })
                attempt = requestUsage(http, current)
            }
        }

        val latest = cookieSnapshot()
        val next = if (latest.isNotBlank() && latest != current.cookies) {
            current.copy(cookies = latest, savedAtMs = System.currentTimeMillis())
        } else {
            current
        }
        return GeminiUsageFetch(attempt.usage, next)
    }

    /** 发一次 UsagePageQuery;unauthorized 表示 HTTP 401/403,值得换 token 再试。 */
    private fun requestUsage(http: OkHttpClient, session: GeminiWebSession): UsageAttempt {
        val payload = buildJsonObject {
            put("querySignature", USAGE_QUERY_ID)
            putJsonObject("variables") {
                putJsonObject("g1Request") {}
                putJsonObject("workspaceRequest") {}
            }
        }.toString()
        val builder = Request.Builder()
            .url(GRAPHQL_ENDPOINT)
            .header("User-Agent", CHROME_USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/app")
            .header("X-Framework-XSRF-Token", session.snlm0e)
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (session.authUserIndex.isNotBlank()) builder.header("X-Goog-AuthUser", session.authUserIndex)
        val response = runCatching { http.newCall(builder.build()).execute() }.getOrNull()
            ?: return UsageAttempt(null, false)
        return response.use {
            val unauthorized = it.code == 401 || it.code == 403
            if (!it.isSuccessful) return@use UsageAttempt(null, unauthorized)
            UsageAttempt(GeminiUsageParser.parse(it.body?.string().orEmpty()), false)
        }
    }

    fun modelNumberFor(model: String): Int = when {
        model.contains("argon", ignoreCase = true) -> 3
        model.contains("pro", ignoreCase = true) -> 3
        model.contains("lite", ignoreCase = true) -> 6
        model.contains("hydrogen", ignoreCase = true) -> 1
        model.contains("flash", ignoreCase = true) -> 1
        else -> 3
    }

    fun modelHexIdFor(model: String): String = when {
        model.contains("argon", ignoreCase = true) -> "e6fa609c3fa255c0"
        model.contains("pro", ignoreCase = true) -> "e6fa609c3fa255c0"
        model.contains("lite", ignoreCase = true) -> "8c46e95b1a07cecc"
        model.contains("hydrogen", ignoreCase = true) -> "56fdd199312815e2"
        model.contains("flash", ignoreCase = true) -> "56fdd199312815e2"
        else -> "e6fa609c3fa255c0"
    }

    fun thinkingFlagFor(effort: String?): Int = when (effort?.lowercase()) {
        "high" -> 2
        "medium", "standard" -> 1
        "low", "fast" -> 1
        "off", "disabled" -> 1
        else -> 1
    }

    fun buildModelHeader(
        modelId: String,
        capacity: Int = 2,
        modelNumber: Int,
        thinkingFlag: Int,
        uuid: String
    ): String {
        return """[1,null,null,null,"$modelId",null,null,0,[4,5,6,8],null,null,$capacity,null,null,$modelNumber,$thinkingFlag,"$uuid"]"""
    }

    fun buildModelHeader(
        modelId: String,
        capacity: Int = 2,
        modelNumber: Int,
        extendedThinking: Boolean,
        uuid: String
    ): String = buildModelHeader(modelId, capacity, modelNumber, if (extendedThinking) 2 else 1, uuid)

    fun buildInnerRequestJson(
        prompt: String,
        modelNumber: Int,
        thinkingFlag: Int,
        uuid: String
    ): String {
        val elements = MutableList<JsonElement?>(81) { null }
        // 0: message content
        elements[0] = buildJsonArray {
            add(JsonPrimitive(prompt))
            add(JsonPrimitive(0))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(0))
        }
        // 1: language
        elements[1] = buildJsonArray { add(JsonPrimitive("en")) }
        // 2: metadata - conversation context (10 elements expected by BardFrontendService)
        elements[2] = buildJsonArray {
            add(JsonPrimitive(""))
            add(JsonPrimitive(""))
            add(JsonPrimitive(""))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(""))
        }
        elements[6] = buildJsonArray { add(JsonPrimitive(1)) }
        elements[7] = JsonPrimitive(1) // streaming
        elements[10] = JsonPrimitive(1)
        elements[11] = JsonPrimitive(0)
        elements[17] = buildJsonArray { add(buildJsonArray { add(JsonPrimitive(0)) }) }
        elements[18] = JsonPrimitive(0)
        elements[27] = JsonPrimitive(1)
        elements[30] = buildJsonArray { add(JsonPrimitive(4)) }
        elements[41] = buildJsonArray { add(JsonPrimitive(1)) }
        elements[53] = JsonPrimitive(0)
        elements[59] = JsonPrimitive(uuid)
        elements[61] = buildJsonArray { }
        elements[68] = JsonPrimitive(1)
        elements[79] = JsonPrimitive(modelNumber)
        elements[80] = JsonPrimitive(thinkingFlag)

        val array = buildJsonArray {
            for (el in elements) {
                if (el != null) add(el) else add(JsonPrimitive(null as String?))
            }
        }
        return array.toString()
    }

    fun buildInnerRequestJson(
        prompt: String,
        modelNumber: Int,
        extendedThinking: Boolean,
        uuid: String
    ): String = buildInnerRequestJson(prompt, modelNumber, if (extendedThinking) 2 else 1, uuid)
}

/** 一次用量读取的结果:[usage] 为空表示这次没读到,[session] 可能是刷新过的新会话。 */
data class GeminiUsageFetch(val usage: GeminiUsage?, val session: GeminiWebSession)

/** 单次 GraphQL 尝试:没读到时 [unauthorized] 说明该换 XSRF token 重试。 */
private data class UsageAttempt(val usage: GeminiUsage?, val unauthorized: Boolean)
