package com.meenai.mina.core.gemini

import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.ModelError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import kotlin.random.Random

object GeminiWebClient {

    const val BASE_URL = "https://gemini.google.com"
    const val INIT_URL = "https://gemini.google.com/app"
    const val STREAM_GENERATE_ENDPOINT = "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
    const val BATCH_EXECUTE_ENDPOINT = "https://gemini.google.com/_/BardChatUi/data/batchexecute"

    const val CHROME_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val MODEL_HEADER_KEY = "x-goog-ext-525001261-jspb"

    val ACCESS_TOKEN_REGEX = Regex(""""SNlM0e":\s*"([^"]+)"""")
    val BUILD_LABEL_REGEX = Regex(""""cfb2h":\s*"([^"]+)"""")
    val SESSION_ID_REGEX = Regex(""""FdrFJe":\s*"([^"]+)"""")
    val EMAIL_REGEX = Regex(""""o3H74c":\s*"([^"]+)"""")

    val geminiWebHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
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
            signedIn = true
        )
    }

    suspend fun fetchSessionFromCookies(http: OkHttpClient, cookies: String): GeminiWebSession {
        val request = Request.Builder()
            .url(INIT_URL)
            .header("User-Agent", CHROME_USER_AGENT)
            .header("Cookie", cookies)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "https://accounts.google.com/")
            .get()
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ModelError.SignInExpired("Google Gemini (HTTP ${response.code})")
        }
        val html = response.body?.string().orEmpty()
        return extractSessionFromHtml(html, cookies)
            ?: throw ModelError.SignInExpired("Failed to extract SNlM0e token from Gemini page. Please sign in again.")
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
