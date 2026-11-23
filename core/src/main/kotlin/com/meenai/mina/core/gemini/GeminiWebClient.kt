package com.meenai.mina.core.gemini

import com.meenai.mina.core.diag.ChatTrace
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

    const val CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36"

    /** Desktop Chrome UA: cookies copied from a PC browser are bound to a desktop UA. */
    const val DESKTOP_CHROME_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    /** The UA a saved session was created with; old sessions fall back to the current one. */
    fun userAgentFor(session: GeminiWebSession): String =
        session.userAgent.ifBlank { CHROME_USER_AGENT }

    /** RPC id of GetUserStatus: a free account check that also lists the account's models. */
    const val USER_STATUS_RPC = "otAQ7b"

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

    fun extractSessionFromHtml(
        html: String,
        cookies: String,
        userAgent: String = CHROME_USER_AGENT,
    ): GeminiWebSession? {
        // A signed-in session is proven by the __Secure-1PSID cookie plus a real
        // Gemini page. The SNlM0e token is only an optimisation: newer builds can
        // keep it in the app bundle, and the chat endpoint accepts an empty `at`
        // for a cookie-authenticated session.
        if (!cookies.contains("__Secure-1PSID")) return null
        val snlm0e = ACCESS_TOKEN_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }.orEmpty()
        val cfb2h = BUILD_LABEL_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        if (cfb2h.isBlank()) return null
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
            userAgent = userAgent,
            signedIn = true
        )
    }

    suspend fun fetchSessionFromCookies(
        http: OkHttpClient,
        cookies: String,
        userAgent: String = CHROME_USER_AGENT,
    ): GeminiWebSession {
        val header = normalizeCookieHeader(cookies)
        val request = Request.Builder()
            .url(INIT_URL)
            .header("User-Agent", userAgent)
            .header("Cookie", header)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "https://accounts.google.com/")
            .get()
            .build()

        val response = http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                throw ModelError.SignInExpired("Google Gemini (HTTP ${it.code} from gemini.google.com)")
            }
            val html = it.body?.string().orEmpty()
            return extractSessionFromHtml(html, header, userAgent)
                ?: throw ModelError.SignInExpired(
                    if (html.contains("accounts.google.com/ServiceLogin") || html.contains("\"RID\"")) {
                        "Google Gemini (cookies are not signed in)"
                    } else {
                        "Google Gemini (Gemini page had no session: cookies may be bound to another browser)"
                    }
                )
        }
    }

    /**
     * Asks Google whether these cookies are really signed in (`GetUserStatus`).
     * This is the same RPC the Gemini web app runs on load: it needs no chat
     * quota, and returns the account status plus the model list of the account.
     */
    suspend fun probeSession(http: OkHttpClient, session: GeminiWebSession): GeminiProbe {
        val reqId = Random.nextInt(10000, 99999)
        val url = buildString {
            append(BATCH_EXECUTE_ENDPOINT)
            append("?rpcids=").append(USER_STATUS_RPC)
            append("&hl=en&_reqid=").append(reqId)
            append("&rt=c&source-path=/app")
            if (session.cfb2h.isNotBlank()) append("&bl=").append(session.cfb2h)
            if (session.fdrfje.isNotBlank()) append("&f.sid=").append(session.fdrfje)
        }
        val uuid = UUID.randomUUID().toString().uppercase()
        val batchHeader =
            """[1,null,null,null,null,null,null,null,[4,5,6,8],null,null,null,null,null,null,null,"$uuid"]"""
        val body = FormBody.Builder()
            .add("at", session.snlm0e)
            .add("f.req", """[[["$USER_STATUS_RPC","[]",null,"generic"]]]""")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/")
            .header("X-Same-Domain", "1")
            .header("User-Agent", userAgentFor(session))
            .header("Cookie", sanitizeCookieHeader(session.cookies))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .header(MODEL_HEADER_KEY, batchHeader)
            .header("x-goog-ext-73010989-jspb", "[0]")
            .post(body)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ModelError.Network(e)
        }
        response.use {
            if (!it.isSuccessful) {
                ChatTrace.record("gemini probe http=${it.code}")
                throw ModelError.BadResponse("Gemini account check returned HTTP ${it.code}")
            }
            val body = it.body?.string().orEmpty()
            val probe = parseProbeResponse(body)
            ChatTrace.record(
                "gemini probe status=${probe.status} models=${probe.models.size} " +
                    "capacity=${probe.capacity}/${probe.capacityField} " +
                    "at=${if (session.snlm0e.isNotBlank()) "yes" else "no"} raw=${body.take(160)}"
            )
            return probe
        }
    }

    /** Parses the anti-XSSI framed `batchexecute` reply of [USER_STATUS_RPC]. */
    fun parseProbeResponse(body: String): GeminiProbe {
        val text = body.substringAfter(")]}'", body).trim()
        // batchexecute frames the JSON with byte-length lines; the payload itself
        // is one array per line. Try every array-shaped line, then the whole body.
        val candidates = text.lines().map { it.trim() }.filter { it.startsWith("[") } + text
        val payloadText = candidates.firstNotNullOfOrNull { candidate ->
            val outer = runCatching { Json.parseToJsonElement(candidate).jsonArray }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            outer.firstNotNullOfOrNull { entry ->
                val arr = entry as? JsonArray ?: return@firstNotNullOfOrNull null
                if (arr.getOrNull(0).asText() == "wrb.fr" && arr.getOrNull(1).asText() == USER_STATUS_RPC) {
                    arr.getOrNull(2).asText()
                } else {
                    null
                }
            }
        } ?: throw ModelError.BadResponse("Gemini account check returned no account status")

        val payload = runCatching { Json.parseToJsonElement(payloadText).jsonArray }.getOrNull()
            ?: throw ModelError.BadResponse("Gemini account check returned no account status")
        val status = payload.getOrNull(14).asInt() ?: 1016
        val tierFlags = payload.getOrNull(16) as? JsonArray
        val capabilityFlags = payload.getOrNull(17) as? JsonArray
        val (capacity, capacityField) = capacityFor(tierFlags, capabilityFlags)
        val models = (payload.getOrNull(15) as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonArray ?: return@mapNotNull null
            val id = item.getOrNull(0).asText() ?: return@mapNotNull null
            val name = item.getOrNull(1).asText().orEmpty()
            val displayName = item.getOrNull(11).asText()
                ?: item.getOrNull(19).asText()
                ?: name
            val description = item.getOrNull(12).asText()
                ?: item.getOrNull(2).asText().orEmpty()
            val modelNumber = item.getOrNull(17).asInt()
                ?: item.getOrNull(9).asInt()
                ?: 1
            GeminiAccountModel(
                id = id,
                name = name,
                displayName = displayName,
                description = description,
                modelNumber = modelNumber,
                capacity = capacity,
                capacityField = capacityField,
            )
        }
        return GeminiProbe(
            status = status,
            models = models,
            capacity = capacity,
            capacityField = capacityField,
            raw = body,
        )
    }

    private fun capacityFor(tierFlags: JsonArray?, capabilityFlags: JsonArray?): Pair<Int, Int> {
        fun has(flags: JsonArray?, value: Int) = flags.orEmpty().any { it.asInt() == value }
        return when {
            has(tierFlags, 21) -> 1 to 13
            has(tierFlags, 22) -> 2 to 13
            has(capabilityFlags, 115) -> 4 to 12
            has(tierFlags, 16) || has(capabilityFlags, 106) -> 3 to 12
            has(tierFlags, 8) || has(capabilityFlags, 19) -> 2 to 12
            else -> 1 to 12
        }
    }

    private fun JsonElement?.asInt(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private fun JsonElement?.asText(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
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
        // 聊天客户端读超时是 5 分钟;用量页不能卡这么久,单独给它 30 秒
        val client = http.newBuilder()
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var refreshedOnce = false
        if (current.snlm0e.isBlank()) {
            refreshedOnce = true
            val refreshed = runCatching {
                fetchSessionFromCookies(
                    client,
                    cookieSnapshot().ifBlank { current.cookies },
                    userAgentFor(current),
                )
            }.getOrNull()
            if (refreshed != null) current = refreshed.copy(email = current.email.ifBlank { refreshed.email })
        }
        var attempt = requestUsage(client, current)
        // 网页没登录时 HTTP 也是 200,只是 viewer 为 null;换一次新令牌再试
        if (attempt.usage == null && attempt.needsRefresh && !refreshedOnce) {
            val refreshed = runCatching {
                fetchSessionFromCookies(
                    client,
                    cookieSnapshot().ifBlank { current.cookies },
                    userAgentFor(current),
                )
            }.getOrNull()
            if (refreshed != null) {
                current = refreshed.copy(email = current.email.ifBlank { refreshed.email })
                attempt = requestUsage(client, current)
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

    /** 发一次 UsagePageQuery;[needsRefresh] 表示登录态/令牌可能过期,值得换新会话再试。 */
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
            .header("User-Agent", userAgentFor(session))
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
            val body = it.body?.string().orEmpty()
            UsageAttempt(
                usage = GeminiUsageParser.parse(body),
                needsRefresh = GeminiUsageParser.needsFreshSession(body),
            )
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

    /**
     * Hex id + model number for the configured model. For a model whose name is
     * not one of our four known ones (stale or renamed model ids, e.g. Google's
     * own "Auto" style entries), fall back to the account's default model from
     * the login probe instead of guessing a hardcoded id that Google may reject.
     */
    fun resolveModel(model: String, session: GeminiWebSession): Pair<String, Int> {
        val known = listOf("argon", "hydrogen", "pro", "lite", "flash").any {
            model.contains(it, ignoreCase = true)
        }
        if (!known && session.defaultModelId.isNotBlank()) {
            return session.defaultModelId to session.defaultModelNumber.coerceAtLeast(1)
        }
        return modelHexIdFor(model) to modelNumberFor(model)
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
        uuid: String,
        capacityField: Int = 12,
    ): String {
        // Field 13 stores the tier as a nested `null,<capacity>` pair; field 12
        // is the plain number every account has used so far.
        val capacityTail = if (capacityField == 13) "null,$capacity" else "$capacity"
        return """[1,null,null,null,"$modelId",null,null,0,[4,5,6,8],null,null,$capacityTail,null,null,$modelNumber,$thinkingFlag,"$uuid"]"""
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

/** 单次 GraphQL 尝试:没读到时 [needsRefresh] 说明该换 XSRF token 重试。 */
private data class UsageAttempt(val usage: GeminiUsage?, val needsRefresh: Boolean)

/** One model the signed-in Google account may actually use. */
data class GeminiAccountModel(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val modelNumber: Int,
    val capacity: Int,
    val capacityField: Int,
)

/** Result of the `GetUserStatus` account check. [status] 1000 means signed in. */
data class GeminiProbe(
    val status: Int,
    val models: List<GeminiAccountModel>,
    val capacity: Int,
    val capacityField: Int,
    val raw: String,
) {
    val signedIn: Boolean get() = status == 1000

    /** A short, honest explanation the UI can show for every known status code. */
    fun statusMessage(): String = when (status) {
        1000 -> "Google confirmed this Gemini session."
        1014 -> "Gemini access is temporarily unavailable for this account."
        1016 -> "Google says this session is not signed in. Please sign in again."
        1021 -> "Google rejected this account for Gemini."
        1033 -> "This account did not pass Google's trust check for Gemini."
        1040, 1042 -> "Please accept the latest Gemini Terms of Service in a browser, then sign in again."
        1054 -> "Gemini access is blocked by a parent or guardian."
        1057 -> "A parent or guardian must approve Gemini for this account."
        1060 -> "Gemini is not available in this account's country or region."
        else -> "Gemini account check returned status $status."
    }
}

