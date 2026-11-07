package com.meenai.mina.core.router

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
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

enum class ProviderType {
    OpenAi,
    Gemini,
    Anthropic
}

/**
 * Handles round-trip conversion between Mina's provider-neutral [Message] format
 * and specific LLM provider wire formats (OpenAI-compatible, Gemini, Anthropic).
 * Guarantees stable deterministic tool call IDs and strips reasoning blocks
 * across provider switches.
 */
object ProviderConverter {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ensures all tool calls across the conversation have deterministic, stable IDs.
     * Maps corresponding tool results to matching IDs.
     */
    fun normalizeToolCallIds(messages: List<Message>): List<Message> {
        val idMapping = mutableMapOf<String, String>()
        val pendingBlank = ArrayDeque<String>()
        var toolCallSeq = 0
        return messages.mapIndexed { msgIdx, msg ->
            var callIdx = 0
            val newParts = msg.parts.map { part ->
                when {
                    msg.role == Role.Assistant && part is Part.ToolCall -> {
                        val keep = part.id.isNotBlank() && !part.id.startsWith("transient-")
                        val stableId = if (keep) part.id else "call_${msgIdx}_${callIdx}_${part.name}_${toolCallSeq++}"
                        if (part.id.isNotBlank()) idMapping[part.id] = stableId else pendingBlank.addLast(stableId)
                        callIdx++
                        part.copy(id = stableId)
                    }
                    part is Part.ToolResult -> {
                        val mappedId = if (part.callId.isBlank()) pendingBlank.removeFirstOrNull() ?: part.callId
                            else idMapping[part.callId] ?: part.callId
                        part.copy(callId = mappedId)
                    }
                    else -> part
                }
            }
            msg.copy(parts = newParts)
        }
    }

    /**
     * Strips Thinking/reasoning parts unless preserveForProvider is specified and matches.
     */
    fun stripForeignThinking(messages: List<Message>, preserveOpaqueFor: String? = null): List<Message> {
        return messages.map { msg ->
            if (msg.role != Role.Assistant) return@map msg
            val filtered = msg.parts.mapNotNull { part ->
                when {
                    part is Part.Thinking -> {
                        if (preserveOpaqueFor != null && part.opaque != null && part.opaque.startsWith(preserveOpaqueFor)) {
                            part
                        } else null
                    }
                    part is Part.ToolCall && preserveOpaqueFor == null -> part.copy(opaque = null)
                    else -> part
                }
            }
            msg.copy(parts = filtered)
        }
    }

    /**
     * Converts messages to OpenAI-compatible Chat Completions payload.
     */
    fun toOpenAiPayload(
        systemPrompt: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        model: String,
        stream: Boolean = false,
        temperature: Double? = null,
    ): JsonObject {
        val normalized = stripForeignThinking(normalizeToolCallIds(messages))
        val wireMessages = mutableListOf<JsonObject>()

        if (systemPrompt.isNotBlank()) {
            wireMessages.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        for (msg in normalized) {
            when (msg.role) {
                Role.System -> {
                    val text = msg.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                    if (text.isNotBlank()) {
                        wireMessages.add(buildJsonObject {
                            put("role", "system")
                            put("content", text)
                        })
                    }
                }
                Role.Assistant -> {
                    val text = msg.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                    val toolCalls = msg.parts.filterIsInstance<Part.ToolCall>()
                    wireMessages.add(buildJsonObject {
                        put("role", "assistant")
                        if (text.isNotEmpty()) put("content", text) else if (toolCalls.isEmpty()) put("content", "")
                        if (toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                toolCalls.forEach { tc ->
                                    add(buildJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", tc.name)
                                            put("arguments", tc.argsJson)
                                        })
                                    })
                                }
                            })
                        }
                    })
                }
                Role.Tool -> {
                    msg.parts.filterIsInstance<Part.ToolResult>().forEach { tr ->
                        wireMessages.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tr.callId)
                            put("content", tr.content)
                        })
                    }
                }
                Role.User -> {
                    val toolResults = msg.parts.filterIsInstance<Part.ToolResult>()
                    toolResults.forEach { tr ->
                        wireMessages.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tr.callId)
                            put("content", tr.content)
                        })
                    }
                    val nonTool = msg.parts.filter { it !is Part.ToolResult }
                    if (nonTool.isNotEmpty()) {
                        val hasImages = nonTool.any { it is Part.Image }
                        wireMessages.add(buildJsonObject {
                            put("role", "user")
                            if (!hasImages) {
                                put("content", nonTool.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
                            } else {
                                put("content", buildJsonArray {
                                    nonTool.forEach { part ->
                                        when (part) {
                                            is Part.Text -> add(buildJsonObject {
                                                put("type", "text")
                                                put("text", part.text)
                                            })
                                            is Part.Image -> add(buildJsonObject {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject {
                                                    put("url", "data:${part.mime};base64,${part.pngBase64}")
                                                })
                                            })
                                            else -> {}
                                        }
                                    }
                                })
                            }
                        })
                    }
                }
            }
        }

        return buildJsonObject {
            put("model", model)
            put("messages", JsonArray(wireMessages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (_: Exception) { JsonObject(emptyMap()) }
                                put("parameters", schema)
                            })
                        })
                    }
                })
            }
            if (stream) put("stream", true)
            temperature?.let { put("temperature", it) }
        }
    }

    /**
     * Converts messages to Gemini generateContent wire payload.
     */
    fun toGeminiPayload(
        systemPrompt: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        temperature: Double? = null,
    ): JsonObject {
        val normalized = stripForeignThinking(normalizeToolCallIds(messages))
        val nameById = mutableMapOf<String, String>()
        normalized.forEach { m ->
            m.parts.filterIsInstance<Part.ToolCall>().forEach { nameById[it.id] = it.name }
        }

        val allSystem = (listOf(systemPrompt) + normalized.filter { it.role == Role.System }
            .flatMap { it.parts.filterIsInstance<Part.Text>() }.map { it.text })
            .filter { it.isNotBlank() }.joinToString("\n\n")

        val mergedContents = mutableListOf<Pair<String, MutableList<JsonObject>>>()

        for (m in normalized) {
            if (m.role == Role.System) continue
            val isModel = m.role == Role.Assistant
            val role = if (isModel) "model" else "user"
            val parts = mutableListOf<JsonObject>()

            if (isModel) {
                m.parts.forEach { part ->
                    when (part) {
                        is Part.Text -> if (part.text.isNotEmpty()) parts.add(buildJsonObject { put("text", part.text) })
                        is Part.ToolCall -> parts.add(buildJsonObject {
                            part.opaque?.let { put("thoughtSignature", it) }
                            put("functionCall", buildJsonObject {
                                put("name", part.name)
                                put("args", try { json.parseToJsonElement(part.argsJson).jsonObject } catch (_: Exception) { JsonObject(emptyMap()) })
                            })
                        })
                        else -> {}
                    }
                }
            } else {
                m.parts.filterIsInstance<Part.ToolResult>().forEach { tr ->
                    val funcName = nameById[tr.callId] ?: tr.callId
                    parts.add(buildJsonObject {
                        put("functionResponse", buildJsonObject {
                            put("name", funcName)
                            put("response", buildJsonObject {
                                put("content", tr.content)
                                if (tr.isError) put("is_error", true)
                            })
                        })
                    })
                }
                m.parts.forEach { part ->
                    when (part) {
                        is Part.Text -> if (part.text.isNotEmpty()) parts.add(buildJsonObject { put("text", part.text) })
                        is Part.Image -> parts.add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", part.mime)
                                put("data", part.pngBase64)
                            })
                        })
                        else -> {}
                    }
                }
            }

            if (parts.isNotEmpty()) {
                if (mergedContents.lastOrNull()?.first == role) {
                    mergedContents.last().second.addAll(parts)
                } else {
                    mergedContents.add(role to parts)
                }
            }
        }

        return buildJsonObject {
            if (allSystem.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", allSystem) })
                    })
                })
            }
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            tools.forEach { t ->
                                add(buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    val sanitized = SchemaSanitizer.sanitizeForGemini(t.parametersJsonSchema)
                                    val schema = try { json.parseToJsonElement(sanitized) } catch (_: Exception) { JsonObject(emptyMap()) }
                                    val hasProps = (schema as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
                                    if (hasProps) put("parameters", schema)
                                })
                            }
                        })
                    })
                })
            }
            put("contents", buildJsonArray {
                mergedContents.forEach { (role, partsList) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("parts", JsonArray(partsList))
                    })
                }
            })
            if (temperature != null) {
                put("generationConfig", buildJsonObject {
                    put("temperature", temperature)
                })
            }
        }
    }

    /**
     * Converts messages to Anthropic Messages wire payload.
     */
    fun toAnthropicPayload(
        systemPrompt: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        model: String,
        maxTokens: Int = 4096,
        temperature: Double? = null,
    ): JsonObject {
        val normalized = stripForeignThinking(normalizeToolCallIds(messages))
        val allSystem = (listOf(systemPrompt) + normalized.filter { it.role == Role.System }
            .flatMap { it.parts.filterIsInstance<Part.Text>() }.map { it.text })
            .filter { it.isNotBlank() }.joinToString("\n\n")

        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()

        for (m in normalized) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "assistant" else "user"
            val parts = mutableListOf<JsonObject>()

            if (m.role == Role.Assistant) {
                m.parts.forEach { part ->
                    when (part) {
                        is Part.Text -> if (part.text.isNotEmpty()) parts.add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                        is Part.ToolCall -> parts.add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", part.id)
                            put("name", part.name)
                            put("input", try { json.parseToJsonElement(part.argsJson).jsonObject } catch (_: Exception) { JsonObject(emptyMap()) })
                        })
                        else -> {}
                    }
                }
            } else {
                m.parts.filterIsInstance<Part.ToolResult>().forEach { tr ->
                    parts.add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", tr.callId)
                        put("content", tr.content)
                        if (tr.isError) put("is_error", true)
                    })
                }
                m.parts.forEach { part ->
                    when (part) {
                        is Part.Text -> if (part.text.isNotEmpty()) parts.add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                        is Part.Image -> parts.add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", part.mime)
                                put("data", part.pngBase64)
                            })
                        })
                        else -> {}
                    }
                }
            }

            if (parts.isNotEmpty()) {
                if (merged.lastOrNull()?.first == role) {
                    merged.last().second.addAll(parts)
                } else {
                    merged.add(role to parts)
                }
            }
        }

        return buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (allSystem.isNotBlank()) put("system", allSystem)
            put("messages", buildJsonArray {
                merged.forEach { (role, partsList) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", JsonArray(partsList))
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (_: Exception) { JsonObject(emptyMap()) }
                            put("input_schema", schema)
                        })
                    }
                })
            }
            temperature?.let { put("temperature", it) }
        }
    }

    /**
     * Parses standard OpenAI Chat Completions JSON response into ModelReply.
     */
    fun parseOpenAiResponse(responseJson: String): ModelReply {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw IllegalArgumentException("Missing choices in response")
        val message = choice["message"]?.jsonObject ?: throw IllegalArgumentException("Missing message in choice")
        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull

        val parts = mutableListOf<Part>()
        (message["content"] as? JsonPrimitive)?.contentOrNull?.let { text ->
            if (text.isNotEmpty()) parts.add(Part.Text(text))
        }

        message["tool_calls"]?.jsonArray?.forEach { tcElement ->
            val tc = tcElement.jsonObject
            val id = (tc["id"] as? JsonPrimitive)?.contentOrNull ?: "call_${parts.size}"
            val fn = tc["function"]?.jsonObject ?: return@forEach
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val args = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
            parts.add(Part.ToolCall(id, name, args))
        }

        val stop = when (finishReason) {
            "stop" -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn
            "tool_calls", "function_call" -> StopReason.ToolUse
            "length" -> StopReason.MaxTokens
            else -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn
        }

        val usage = root["usage"]?.jsonObject?.let { u ->
            val prompt = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            val comp = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
            Usage(prompt, comp)
        }

        return ModelReply(parts, stop, usage)
    }
}
