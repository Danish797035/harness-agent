package com.meenai.mina.core.router

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConverterTest {

    private val tools = listOf(
        ToolSpec("search", "search web", """{"type":"object","properties":{"q":{"type":"string"}}}""")
    )

    @Test
    fun normalizesMissingToolCallIdsDeterministically() {
        val messages = listOf(
            Message(Role.User, listOf(Part.Text("find files")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("", "search", """{"q":"test"}""")), Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("", "found 3 files", false)), Author.Mina),
        )

        val normalized = ProviderConverter.normalizeToolCallIds(messages)
        val call = normalized[1].parts[0] as Part.ToolCall
        val result = normalized[2].parts[0] as Part.ToolResult

        assertTrue(call.id.isNotEmpty())
        assertEquals(call.id, result.callId)
        assertTrue(call.id.startsWith("call_1_0_search"))
    }

    @Test
    fun stripsForeignThinkingAcrossProviders() {
        val messages = listOf(
            Message(Role.Assistant, listOf(
                Part.Thinking("internal thoughts", opaque = "gemini-sig-123"),
                Part.Text("Here is the answer"),
            ), Author.Mina),
        )

        // Foreign provider (null or mismatch): thinking stripped
        val stripped = ProviderConverter.stripForeignThinking(messages, preserveOpaqueFor = null)
        assertEquals(1, stripped[0].parts.size)
        assertTrue(stripped[0].parts[0] is Part.Text)

        // Same provider with matching prefix: preserved
        val preserved = ProviderConverter.stripForeignThinking(messages, preserveOpaqueFor = "gemini-sig")
        assertEquals(2, preserved[0].parts.size)
        assertTrue(preserved[0].parts[0] is Part.Thinking)
    }

    @Test
    fun convertsToOpenAiPayloadWithToolCalls() {
        val messages = listOf(
            Message(Role.User, listOf(Part.Text("hi")), Author.User),
            Message(Role.Assistant, listOf(
                Part.Thinking("thinking block"),
                Part.ToolCall("call_abc", "search", """{"q":"weather"}""")
            ), Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("call_abc", "sunny", false)), Author.Mina),
        )

        val payload = ProviderConverter.toOpenAiPayload("System prompt", messages, tools, "test-model")
        assertEquals("test-model", payload["model"].toString().trim('"'))
        val msgs = payload["messages"] as kotlinx.serialization.json.JsonArray
        assertEquals(4, msgs.size) // system, user, assistant with tool_calls, tool result

        val assistantMsg = msgs[2] as kotlinx.serialization.json.JsonObject
        val toolCalls = assistantMsg["tool_calls"] as kotlinx.serialization.json.JsonArray
        val firstCall = toolCalls[0] as kotlinx.serialization.json.JsonObject
        assertEquals("call_abc", firstCall["id"].toString().trim('"'))

        val toolMsg = msgs[3] as kotlinx.serialization.json.JsonObject
        assertEquals("tool", toolMsg["role"].toString().trim('"'))
        assertEquals("call_abc", toolMsg["tool_call_id"].toString().trim('"'))
    }

    @Test
    fun convertsToGeminiPayloadWithFunctionCalls() {
        val messages = listOf(
            Message(Role.User, listOf(Part.Text("search weather")), Author.User),
            Message(Role.Assistant, listOf(
                Part.ToolCall("c1", "search", """{"q":"Tokyo"}""")
            ), Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "rainy", false)), Author.Mina),
        )

        val payload = ProviderConverter.toGeminiPayload("Sys", messages, tools)
        assertNotNull(payload["systemInstruction"])
        val contents = payload["contents"] as kotlinx.serialization.json.JsonArray
        assertTrue(contents.size >= 2)

        val modelTurn = contents.find { (it as kotlinx.serialization.json.JsonObject)["role"].toString().trim('"') == "model" }
        assertNotNull(modelTurn)
        val parts = (modelTurn as kotlinx.serialization.json.JsonObject)["parts"] as kotlinx.serialization.json.JsonArray
        val fc = (parts[0] as kotlinx.serialization.json.JsonObject)["functionCall"] as kotlinx.serialization.json.JsonObject
        assertEquals("search", fc["name"].toString().trim('"'))

        val userTurn = contents.last() as kotlinx.serialization.json.JsonObject
        val userParts = userTurn["parts"] as kotlinx.serialization.json.JsonArray
        val fr = (userParts[0] as kotlinx.serialization.json.JsonObject)["functionResponse"] as kotlinx.serialization.json.JsonObject
        assertEquals("search", fr["name"].toString().trim('"'))
    }

    @Test
    fun convertsToAnthropicPayloadWithToolUse() {
        val messages = listOf(
            Message(Role.User, listOf(Part.Text("test")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("c99", "search", """{"q":"Paris"}""")), Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("c99", "clear", false)), Author.Mina),
        )

        val payload = ProviderConverter.toAnthropicPayload("System info", messages, tools, "claude-3-5-sonnet")
        assertEquals("claude-3-5-sonnet", payload["model"].toString().trim('"'))
        val msgs = payload["messages"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, msgs.size)
    }

    @Test
    fun parsesOpenAiResponseToModelReply() {
        val response = """
            {
                "choices": [{
                    "message": {
                        "role": "assistant",
                        "content": "Searching now...",
                        "tool_calls": [{
                            "id": "call_123",
                            "type": "function",
                            "function": {
                                "name": "search",
                                "arguments": "{\"q\":\"Berlin\"}"
                            }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }],
                "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 30
                }
            }
        """.trimIndent()

        val reply = ProviderConverter.parseOpenAiResponse(response)
        assertEquals(2, reply.parts.size)
        assertEquals(Part.Text("Searching now..."), reply.parts[0])
        val tc = reply.parts[1] as Part.ToolCall
        assertEquals("call_123", tc.id)
        assertEquals("search", tc.name)
        assertEquals("""{"q":"Berlin"}""", tc.argsJson)
        assertEquals(com.meenai.mina.core.model.StopReason.ToolUse, reply.stopReason)
        assertEquals(120, reply.usage?.inputTokens)
        assertEquals(30, reply.usage?.outputTokens)
    }
}
