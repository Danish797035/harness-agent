package com.meenai.mina.core.model

import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.random.Random

class GeminiWebModel(
    private var session: GeminiWebSession,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val reasoningEffort: String? = null,
    private val onSessionUpdated: ((GeminiWebSession) -> Unit)? = null,
) : StreamingChatModel {

    override val name: String get() = model

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): ModelReply {
        var collectedText = ""
        return completeStreaming(system, messages, tools) { delta ->
            collectedText += delta
        }
    }

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit
    ): ModelReply {
        val prompt = formatPrompt(system, messages, tools)
        val thinkingFlag = GeminiWebClient.thinkingFlagFor(reasoningEffort)
        val modelNumber = GeminiWebClient.modelNumberFor(model)
        val modelHexId = GeminiWebClient.modelHexIdFor(model)
        val uuid = UUID.randomUUID().toString().uppercase()

        val innerReqJson = GeminiWebClient.buildInnerRequestJson(prompt, modelNumber, thinkingFlag, uuid)
        val modelHeader = GeminiWebClient.buildModelHeader(modelHexId, capacity = 2, modelNumber, thinkingFlag, uuid)

        var currentSession = session
        var bl = currentSession.cfb2h.ifBlank { "boq_assistant-bard-web-server_20250101.00_p0" }
        var request = createPostRequest(currentSession, innerReqJson, modelHeader, uuid, bl)

        var response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw ModelError.Network(e)
        }

        try { if (response.code in 401..403 || response.code == 400) {
            val peek = runCatching { response.peekBody(500).string() }.getOrDefault("")
            val isSessionError = response.code in 401..403 ||
                peek.contains("af.httprm") || peek.contains("141") || peek.contains("\"er\"")

            if (isSessionError) {
                val refreshed = runCatching {
                    GeminiWebClient.fetchSessionFromCookies(http, currentSession.cookies)
                }.getOrNull()

                if (refreshed != null && refreshed.snlm0e.isNotBlank()) {
                    response.close()
                    session = refreshed
                    currentSession = refreshed
                    onSessionUpdated?.invoke(refreshed)
                    bl = refreshed.cfb2h.ifBlank { bl }
                    request = createPostRequest(currentSession, innerReqJson, modelHeader, uuid, bl)
                    response = try {
                        http.newCall(request).execute()
                    } catch (e: Exception) {
                        throw ModelError.Network(e)
                    }
                } else {
                    response.close()
                    throw ModelError.SignInExpired("Google Gemini")
                }
            }
        }

        if (response.code in 401..403) {
            throw ModelError.SignInExpired("Google Gemini")
        }
        if (response.code == 429) {
            throw ModelError.RateLimited(null)
        }
        if (response.code >= 500) {
            throw ModelError.Server(response.code)
        }
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            if (response.code == 400 && (body.contains("af.httprm") || body.contains("141") || body.contains("\"er\""))) {
                throw ModelError.SignInExpired("Google Gemini")
            }
            throw ModelError.BadResponse("Gemini Web returned HTTP ${response.code}: ${body.take(200)}")
        }

        val body = response.body ?: throw ModelError.BadResponse("Gemini Web returned empty body")

        var fullText = ""
        var fullThoughts = ""
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        var lastEmittedLength = 0

        try {
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith(")]}'") || trimmed.all { it.isDigit() } || trimmed.isEmpty()) {
                    line = reader.readLine()
                    continue
                }

                val parsedChunk = parseChunk(trimmed)
                if (parsedChunk != null) {
                    if (parsedChunk.thoughts.isNotEmpty() && parsedChunk.thoughts.length > fullThoughts.length) {
                        fullThoughts = parsedChunk.thoughts
                    }
                    if (parsedChunk.text.isNotEmpty() && parsedChunk.text.length >= fullText.length) {
                        fullText = parsedChunk.text
                        val visibleText = stripToolCalls(fullText)
                        if (visibleText.length > lastEmittedLength) {
                            if (visibleText.isNotEmpty()) {
                                onText(visibleText.substring(lastEmittedLength))
                            }
                            lastEmittedLength = visibleText.length
                        }
                    }
                }
                line = reader.readLine()
            }
        } catch (e: java.net.SocketTimeoutException) {
            if (fullText.isEmpty() && fullThoughts.isEmpty()) {
                throw ModelError.FirstTokenTimeout()
            } else {
                throw ModelError.StreamCut()
            }
        } catch (e: java.io.IOException) {
            throw ModelError.Network(e)
        } finally {
            reader.close() } finally { response.close()
        }

        val cleanText = cleanupText(fullText)
        val toolCalls = parseToolCalls(cleanText)

        val parts = mutableListOf<Part>()
        if (fullThoughts.isNotBlank()) {
            parts.add(Part.Thinking(fullThoughts.trim()))
        }

        val stopReason = if (toolCalls.isNotEmpty()) {
            parts.addAll(toolCalls)
            StopReason.ToolUse
        } else {
            parts.add(Part.Text(cleanText))
            StopReason.EndTurn
        }

        val usage = Usage(
            inputTokens = prompt.length / 4,
            outputTokens = (fullText.length + fullThoughts.length) / 4,
            reasoningTokens = fullThoughts.length / 4
        )

        return ModelReply(parts, stopReason, usage)
    }

    private fun createPostRequest(
        currentSession: GeminiWebSession,
        innerReqJson: String,
        modelHeader: String,
        uuid: String,
        bl: String
    ): Request {
        val reqId = Random.nextInt(10000, 99999)
        val url = "${GeminiWebClient.STREAM_GENERATE_ENDPOINT}?bl=$bl&hl=en&_reqid=$reqId&rt=c"

        val formBody = FormBody.Builder()
            .add("at", currentSession.snlm0e)
            .add("f.req", buildJsonArray {
                add(JsonPrimitive(null as String?))
                add(JsonPrimitive(innerReqJson))
            }.toString())
            .build()

        return Request.Builder()
            .url(url)
            .header("Host", "gemini.google.com")
            .header("Origin", "https://gemini.google.com")
            .header("Referer", "https://gemini.google.com/")
            .header("X-Same-Domain", "1")
            .header("User-Agent", GeminiWebClient.CHROME_USER_AGENT)
            .header("Cookie", currentSession.cookies)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .header(GeminiWebClient.MODEL_HEADER_KEY, modelHeader)
            .header("x-goog-ext-525005358-jspb", "[\"$uuid\",1]")
            .header("x-goog-ext-73010989-jspb", "[0]")
            .header("x-goog-ext-73010990-jspb", "[0,0,0]")
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
            }
            .post(formBody)
            .build()
    }

    private data class ParsedChunk(val text: String, val thoughts: String, val isCompleted: Boolean); private fun throwStreamError(raw: String): Nothing { val l = raw.lowercase(); if (l.contains("401") || l.contains("403") || l.contains("141") || l.contains("1013") || l.contains("auth") || l.contains("session") || l.contains("sign") || l.contains("expired") || l.contains("cookie")) throw ModelError.SignInExpired("Google Gemini"); throw ModelError.BadResponse("Gemini stream error: $raw") }

    private fun parseChunk(line: String): ParsedChunk? {
        val root = try {
            json.parseToJsonElement(line) as? JsonArray ?: return null
        } catch (_: Exception) {
            return null
        }

        for (item in root) {
            val itemArr = item as? JsonArray ?: continue
            val tag = (itemArr.getOrNull(0) as? JsonPrimitive)?.contentOrNull
            if (tag == "er") throwStreamError(itemArr.toString()); if (tag != "wrb.fr") continue

            val payloadStr = (itemArr.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: run { val err = itemArr.getOrNull(5); if (err != null && err.toString() != "null") throwStreamError(err.toString()); continue }
            val payload = try {
                json.parseToJsonElement(payloadStr) as? JsonArray ?: continue
            } catch (_: Exception) {
                continue
            }

            val candidates = payload.getOrNull(4) as? JsonArray ?: continue
            val firstCandidate = candidates.getOrNull(0) as? JsonArray ?: continue

            val text = ((firstCandidate.getOrNull(1) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val thoughts = (((firstCandidate.getOrNull(37) as? JsonArray)?.getOrNull(0) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val indicator = ((firstCandidate.getOrNull(8) as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: 0

            return ParsedChunk(text = text, thoughts = thoughts, isCompleted = indicator == 2)
        }
        return null
    }

    private fun stripToolCalls(text: String): String {
        var s = text.replace(Regex("""```(?:json)?\s*\{[\s\S]*?"tool_calls"[\s\S]*?\}\s*```"""), "")
            .replace(Regex("""```(?:json)?\s*\{[\s\S]*?"name"[\s\S]*?\}\s*```"""), "")
            .replace(Regex("""```(?:json)?[\s\S]*?```"""), "")
        val trimmed = s.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("```") || trimmed.startsWith("[") || trimmed.startsWith("]")) {
            if (trimmed.contains("\"tool_calls\"") || trimmed.contains("\"name\"") || trimmed.contains("\"arguments\"") || trimmed.contains("\"parameters\"")) {
                return ""
            }
            if (trimmed == "]" || trimmed == "}" || trimmed == "]\n}" || trimmed == "}\n]") {
                return ""
            }
        }
        return cleanupText(s)
    }

    private fun cleanupText(raw: String): String {
        return raw.replace(Regex("""^https?://googleusercontent\.com/card_content/\d+.*""", RegexOption.MULTILINE), "")
            .replace(Regex("""https?://googleusercontent\.com/(?:\w+/)+\d+\n*"""), "")
            .trim()
    }

    private fun parseToolCalls(text: String): List<Part.ToolCall> {
        val calls = mutableListOf<Part.ToolCall>()

        // 1. Check for ```json ... ``` blocks containing tool_calls
        val blockRegex = Regex("""```(?:json)?\s*(\{[\s\S]*?"tool_calls"[\s\S]*?\})\s*```""")
        val blockMatch = blockRegex.find(text)
        val candidateJson = blockMatch?.groupValues?.get(1) ?: run {
            // 2. Direct JSON object containing tool_calls
            if (text.contains("\"tool_calls\"")) {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start != -1 && end > start) text.substring(start, end + 1) else null
            } else null
        }

        if (candidateJson != null) {
            try {
                val root = json.parseToJsonElement(candidateJson).jsonObject
                val toolCallsArr = root["tool_calls"]?.jsonArray
                if (toolCallsArr != null) {
                    for (tc in toolCallsArr) {
                        val tcObj = tc.jsonObject
                        val fnName = tcObj["name"]?.jsonPrimitive?.contentOrNull
                            ?: tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                        val argsObj = tcObj["arguments"] ?: tcObj["function"]?.jsonObject?.get("arguments")
                        if (fnName != null && argsObj != null) {
                            val argsStr = if (argsObj is JsonPrimitive) argsObj.content else argsObj.toString()
                            calls.add(Part.ToolCall(
                                id = "call_${UUID.randomUUID().toString().take(8)}",
                                name = fnName,
                                argsJson = argsStr
                            ))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Check for single tool invocation format: {"name": "...", "arguments": {...}}
        if (calls.isEmpty() && text.contains("\"name\"") && (text.contains("\"arguments\"") || text.contains("\"parameters\""))) {
            try {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start != -1 && end > start) {
                    val rawObj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
                    val name = rawObj["name"]?.jsonPrimitive?.contentOrNull
                    val args = rawObj["arguments"] ?: rawObj["parameters"]
                    if (name != null && args != null) {
                        val argsStr = if (args is JsonPrimitive) args.content else args.toString()
                        calls.add(Part.ToolCall(
                            id = "call_${UUID.randomUUID().toString().take(8)}",
                            name = name,
                            argsJson = argsStr
                        ))
                    }
                }
            } catch (_: Exception) {}
        }

        return calls
    }

    private fun formatPrompt(system: String, messages: List<Message>, tools: List<ToolSpec>): String {
        val sb = StringBuilder()

        if (system.isNotBlank()) {
            sb.append("[System Instructions]\n")
            sb.append(system.trim())
            sb.append("\n\n")
        }

        if (tools.isNotEmpty()) {
            sb.append("[Available Tools]\n")
            sb.append("You have access to the following tools:\n\n")
            for (t in tools) {
                sb.append("Tool: ").append(t.name).append("\n")
                sb.append("Description: ").append(t.description).append("\n")
                sb.append("Parameters Schema: ").append(t.parametersJsonSchema).append("\n\n")
            }
            sb.append("CRITICAL INSTRUCTIONS FOR TOOL CALLS:\n")
            sb.append("When you need to call a tool, respond ONLY with a JSON block in this exact format:\n")
            sb.append("```json\n")
            sb.append("{\n")
            sb.append("  \"tool_calls\": [\n")
            sb.append("    {\n")
            sb.append("      \"name\": \"<tool_name>\",\n")
            sb.append("      \"arguments\": { <arguments matching parameter schema> }\n")
            sb.append("    }\n")
            sb.append("  ]\n")
            sb.append("}\n")
            sb.append("```\n")
            sb.append("Do not add conversational commentary or pleasantries when invoking a tool.\n")
            sb.append("CRITICAL: If the user's message is a greeting (e.g. 'hi', 'hello'), conversational chat, or general question that does not require reading or operating an app or phone screen right now, DO NOT invoke any tool. Answer the user directly and immediately in friendly conversational text.\n\n")
        }

        sb.append("[Conversation History]\n")
        for (m in messages) {
            when (m.role) {
                Role.System -> {
                    val txt = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                    if (txt.isNotBlank()) sb.append("[System: $txt]\n\n")
                }
                Role.User -> {
                    sb.append("User: ")
                    for (p in m.parts) {
                        when (p) {
                            is Part.Text -> sb.append(p.text).append("\n")
                            is Part.ToolResult -> sb.append("\n[Tool Result for ${p.callId}${if (p.isError) " (ERROR)" else ""}]:\n${p.content}\n")
                            is Part.Image -> sb.append("\n[Attached Image: ${p.width}x${p.height}]\n")
                            else -> {}
                        }
                    }
                    sb.append("\n")
                }
                Role.Assistant -> {
                    sb.append("Assistant: ")
                    for (p in m.parts) {
                        when (p) {
                            is Part.Text -> sb.append(p.text).append("\n")
                            is Part.Thinking -> sb.append("<thinking>${p.text}</thinking>\n")
                            is Part.ToolCall -> sb.append("\n```json\n{\"tool_calls\": [{\"name\": \"${p.name}\", \"arguments\": ${p.argsJson}}]}\n```\n")
                            else -> {}
                        }
                    }
                    sb.append("\n")
                }
                Role.Tool -> {
                    for (p in m.parts.filterIsInstance<Part.ToolResult>()) {
                        sb.append("[Tool Result for ${p.callId}${if (p.isError) " (ERROR)" else ""}]:\n${p.content}\n\n")
                    }
                }
            }
        }

        return sb.toString().trim()
    }
}
