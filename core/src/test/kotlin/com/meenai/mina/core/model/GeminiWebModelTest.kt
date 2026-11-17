package com.meenai.mina.core.model

import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiWebModelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val session = GeminiWebSession(
        cookies = "__Secure-1PSID=test-sid; __Secure-1PSIDTS=test-ts",
        snlm0e = "test-token-snlm0e",
        cfb2h = "test-bl",
        fdrfje = "test-sid",
        email = "pro_user@gmail.com"
    )

    private val userMsg = listOf(Message(Role.User, listOf(Part.Text("hello")), Author.User))

    @Test
    fun defaultModelsIncludeAllProPlanModels() {
        val models = GeminiWebClient.DEFAULT_MODELS
        assertEquals(4, models.size)
        val ids = models.map { it.id }
        assertTrue(ids.contains("gemini-4-argon"))
        assertTrue(ids.contains("gemini-4-hydrogen"))
        assertTrue(ids.contains("gemini-3.1-pro"))
        assertTrue(ids.contains("gemini-3.5-flash-lite"))
    }

    @Test
    fun thinkingFlagForMapsLevelsCorrectly() {
        assertEquals(2, GeminiWebClient.thinkingFlagFor("high"))
        assertEquals(1, GeminiWebClient.thinkingFlagFor("medium"))
        assertEquals(1, GeminiWebClient.thinkingFlagFor("standard"))
        assertEquals(1, GeminiWebClient.thinkingFlagFor("low"))
        assertEquals(0, GeminiWebClient.thinkingFlagFor("off"))
    }

    @Test
    fun geminiWebHttpConfiguredWith300sTimeout() {
        val client = GeminiWebClient.geminiWebHttp
        assertEquals(300, client.readTimeoutMillis / 1000)
        assertEquals(15, client.connectTimeoutMillis / 1000)
    }

    @Test
    fun extractSessionFromHtmlExtractsAllTokens() {
        val html = """
            <html>
            <script>
            window.WIZ_global_data = {
                "SNlM0e": "extracted_snlm0e_abc123",
                "cfb2h": "extracted_bl_456",
                "FdrFJe": "extracted_fdrfje_789",
                "o3H74c": "testuser@gmail.com"
            };
            </script>
            </html>
        """.trimIndent()

        val parsed = GeminiWebClient.extractSessionFromHtml(html, "cookie_123=val")
        assertNotNull(parsed)
        assertEquals("extracted_snlm0e_abc123", parsed?.snlm0e)
        assertEquals("extracted_bl_456", parsed?.cfb2h)
        assertEquals("extracted_fdrfje_789", parsed?.fdrfje)
        assertEquals("testuser@gmail.com", parsed?.email)
        assertEquals("cookie_123=val", parsed?.cookies)
    }

    private fun makeStreamData(payloadJson: String): String {
        val streamJson = buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive("wrb.fr"))
                add(JsonPrimitive(null as String?))
                add(JsonPrimitive(payloadJson))
            })
        }.toString()
        return ")]}'\n120\n$streamJson\n"
    }

    @Test
    fun completeParsesStreamingFramesAndEmitsText() = runTest {
        val payload = buildJsonArray {
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive("rc_1"))
                    add(buildJsonArray {
                        add(JsonPrimitive("Hello from Gemini 4 Argon!"))
                    })
                })
            })
        }.toString()

        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(payload)))

        // Intercept endpoint by custom OkHttpClient that redirects to mock server
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.url("/").host)
                    .port(server.url("/").port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = mockClient
        )

        var streamedDelta = ""
        val reply = model.completeStreaming("sys", userMsg, emptyList()) { delta ->
            streamedDelta += delta
        }

        assertEquals("Hello from Gemini 4 Argon!", streamedDelta)
        assertEquals(1, reply.parts.size)
        assertEquals(Part.Text("Hello from Gemini 4 Argon!"), reply.parts[0])
        assertEquals(StopReason.EndTurn, reply.stopReason)
    }

    @Test
    fun completeParsesToolCallsProperly() = runTest {
        val toolText = "```json\n{\n  \"tool_calls\": [\n    {\n      \"name\": \"get_time\",\n      \"arguments\": {}\n    }\n  ]\n}\n```"
        val payload = buildJsonArray {
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive("rc_2"))
                    add(buildJsonArray {
                        add(JsonPrimitive(toolText))
                    })
                })
            })
        }.toString()

        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(payload)))

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.url("/").host)
                    .port(server.url("/").port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = mockClient
        )

        val reply = model.complete("sys", userMsg, listOf(ToolSpec("get_time", "Returns time", "{}")))
        assertEquals(1, reply.parts.size)
        assertTrue(reply.parts[0] is Part.ToolCall)
        val call = reply.parts[0] as Part.ToolCall
        assertEquals("get_time", call.name)
        assertEquals("{}", call.argsJson)
        assertEquals(StopReason.ToolUse, reply.stopReason)
    }

    @Test
    fun completeCapturesThinkingWhenPresent() = runTest {
        val candidateArray = buildJsonArray {
            add(JsonPrimitive("rc_3"))
            add(buildJsonArray { add(JsonPrimitive("Final answer")) })
            for (i in 2..36) {
                add(JsonPrimitive(null as String?))
            }
            add(buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive("Step-by-step thinking"))
                })
            })
        }
        val payload = buildJsonArray {
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(buildJsonArray {
                add(candidateArray)
            })
        }.toString()

        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(payload)))

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.url("/").host)
                    .port(server.url("/").port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = mockClient,
            reasoningEffort = "high"
        )

        val reply = model.complete("sys", userMsg, emptyList())
        assertEquals(2, reply.parts.size)
        assertEquals(Part.Thinking("Step-by-step thinking"), reply.parts[0])
        assertEquals(Part.Text("Final answer"), reply.parts[1])
    }

    @Test(expected = ModelError.SignInExpired::class)
    fun http401ThrowsSignInExpired() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.url("/").host)
                    .port(server.url("/").port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = mockClient
        )

        model.complete("sys", userMsg, emptyList())
    }
}
