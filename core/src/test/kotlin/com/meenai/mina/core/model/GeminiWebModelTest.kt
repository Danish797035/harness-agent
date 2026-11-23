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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertEquals(1, GeminiWebClient.thinkingFlagFor("off"))
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
                "o3H74c": "testuser@gmail.com",
                "PI9WOb": "CAMS_server_token",
                "dSupif": "https://geminiweb-pa.clients6.google.com",
                "QrtxK": 0,
                "GruApd": "mendel_flags_value"
            };
            </script>
            </html>
        """.trimIndent()

        val parsed = GeminiWebClient.extractSessionFromHtml(html, "__Secure-1PSID=cookie_123; __Secure-1PSIDTS=ts_456")
        assertNotNull(parsed)
        assertEquals("extracted_snlm0e_abc123", parsed?.snlm0e)
        assertEquals("extracted_bl_456", parsed?.cfb2h)
        assertEquals("extracted_fdrfje_789", parsed?.fdrfje)
        assertEquals("testuser@gmail.com", parsed?.email)
        assertEquals("CAMS_server_token", parsed?.serverToken)
        assertEquals("https://geminiweb-pa.clients6.google.com", parsed?.oxygenUrl)
        assertEquals("0", parsed?.authUserIndex)
        assertEquals("mendel_flags_value", parsed?.mendelFlags)
        assertEquals("__Secure-1PSID=cookie_123; __Secure-1PSIDTS=ts_456", parsed?.cookies)
    }

    @Test
    fun extractSessionFromHtmlAcceptsAPageWithoutSnlm0e() {
        val html = """<script>window.WIZ_global_data = {"cfb2h":"bl_only","FdrFJe":"sid_only"};</script>"""

        val parsed = GeminiWebClient.extractSessionFromHtml(html, "__Secure-1PSID=sid_value")

        assertNotNull(parsed)
        assertEquals("", parsed?.snlm0e)
        assertEquals("bl_only", parsed?.cfb2h)
        assertEquals("sid_only", parsed?.fdrfje)
    }

    @Test
    fun extractSessionFromHtmlRejectsAGuestPage() {
        val html = """<script>window.WIZ_global_data = {"cfb2h":"bl","FdrFJe":"sid"};</script>"""

        assertNull(GeminiWebClient.extractSessionFromHtml(html, "NID=guest; COMPASS=guest"))
    }

    @Test
    fun parseProbeResponseReadsStatusCapacityAndModels() {
        val model = buildJsonArray {
            add(JsonPrimitive("cf41b0e0dd7d53e5"))
            add(JsonPrimitive("Auto"))
            add(JsonPrimitive("Adapts to your needs"))
            repeat(8) { add(JsonPrimitive(null as String?)) }
            add(JsonPrimitive("Auto"))            // 11: display name
            add(JsonPrimitive("Adapts to your needs")) // 12: description
            repeat(4) { add(JsonPrimitive(null as String?)) }
            add(JsonPrimitive(6))                 // 17: model number
            add(JsonPrimitive(null as String?))   // 18
            add(JsonPrimitive("Auto"))            // 19
        }
        val inner = buildJsonArray {
            add(JsonPrimitive(6))
            add(buildJsonArray { add(JsonPrimitive(false)) })
            repeat(12) { add(JsonPrimitive(null as String?)) }
            add(JsonPrimitive(1000))              // 14: status
            add(buildJsonArray { add(model) })    // 15: models
            add(buildJsonArray { add(JsonPrimitive(8)); add(JsonPrimitive(38)) }) // 16: tiers
            add(buildJsonArray { add(JsonPrimitive(2)); add(JsonPrimitive(140)) }) // 17: capabilities
        }
        val body = buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive("wrb.fr"))
                add(JsonPrimitive("otAQ7b"))
                add(JsonPrimitive(inner.toString()))
            })
        }

        val probe = GeminiWebClient.parseProbeResponse(")]}'\n120\n$body\n")

        assertTrue(probe.signedIn)
        assertEquals(1, probe.models.size)
        assertEquals("cf41b0e0dd7d53e5", probe.models[0].id)
        assertEquals(6, probe.models[0].modelNumber)
        assertEquals(2, probe.capacity)
        assertEquals(12, probe.capacityField)
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
    fun fallsBackToGuestWhenTheAccountRequestIsRejected() = runTest {
        GeminiWebClient.clearCookies()
        // 1: the account request is rejected the way Google rejects a stale session.
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""[["er",null,null,null,null,400,null,null,null,3],["af.httprm",7,"-1",95]]""")
        )
        // 2: the re-read page is a signed-out page, so there is nothing to refresh.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Sign in</body></html>"))
        // 3: the guest retry.
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("Guest answer"))))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Guest answer"), reply.parts[0])
        val account = server.takeRequest()
        assertTrue(account.getHeader("Cookie").orEmpty().contains("__Secure-1PSID"))
        server.takeRequest() // the page re-read
        val guest = server.takeRequest()
        assertFalse(guest.getHeader("Cookie").orEmpty().contains("SID"))
        assertEquals("at=", guest.body.readUtf8().substringBefore("&f.req="))
    }

    @Test(expected = ModelError.RateLimited::class)
    fun rateLimitDoesNotFallBackToGuest() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        model.complete("sys", userMsg, emptyList())
    }

    @Test
    fun completeFetchesAMissingSnlm0eTokenFromTheLoggedInPage() = runTest {
        GeminiWebClient.clearCookies()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<script>{"SNlM0e":"fetched-token","cfb2h":"bl2","FdrFJe":"sid2"}</script>""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("Token refreshed"))))

        var saved: GeminiWebSession? = null
        val model = GeminiWebModel(
            session = session.copy(snlm0e = ""),
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
            onSessionUpdated = { saved = it }
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Token refreshed"), reply.parts[0])
        assertEquals("fetched-token", saved?.snlm0e)
        server.takeRequest() // the page fetch that reads SNlM0e
        val chat = server.takeRequest()
        assertTrue(decodedBody(chat.body.readUtf8()).contains("at=fetched-token"))
    }

    @Test
    fun unreadableStreamFailsWithTheRawFramesInsteadOfSilence() = runTest {
        // The account attempt, then the one guest retry: both unreadable.
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(")]}'\n\n[[\"wrb.fr\",null,null,\"not-json\",null,null,null,\"generic\"]]")
            )
        }

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        try {
            model.complete("sys", userMsg, emptyList())
            fail("Expected BadResponse")
        } catch (e: ModelError.BadResponse) {
            assertTrue(e.message!!.contains("no readable reply"))
            assertTrue(e.message!!.contains("wrb.fr"))
        }
    }

    @Test
    fun refreshKeepsTheCapacityFoundAtLogin() = runTest {
        GeminiWebClient.clearCookies()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""<script>{"cfb2h":"bl2","FdrFJe":"sid2"}</script>""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("ok"))))

        var saved: GeminiWebSession? = null
        val model = GeminiWebModel(
            session = session.copy(cfb2h = "", capacity = 4, capacityField = 12),
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
            onSessionUpdated = { saved = it }
        )

        model.complete("sys", userMsg, emptyList())

        assertNotNull(saved)
        assertEquals(4, saved!!.capacity)
        assertEquals(12, saved!!.capacityField)
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
        // The client re-reads the logged-in page once before giving up; this reply
        // is a signed-out page, so the session is still a real sign-in expiry.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Sign in</body></html>"))
        // The single guest retry is rejected too: there is nothing left to try.
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

    @Test
    fun toolCallJsonDoesNotLeakIntoOnTextStream() = runTest {
        val toolChunk = """
            )]}'
            120
            [["wrb.fr",null,"[null,null,null,null,[[null,[\"```json\\n{\\n  \\\"tool_calls\\\": [\\n    {\\n      \\\"name\\\": \\\"view_user_screen\\\",\\n      \\\"arguments\\\": {}\\n    }\\n  ]\\n}\\n```\"],null,null,null,null,null,null,null,[2]]]]"]]
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(toolChunk))

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

        var streamed = ""
        val reply = model.completeStreaming("sys", userMsg, emptyList()) { text ->
            streamed = text
        }

        assertTrue("Tool call JSON must not leak into streamed preview text", streamed.isEmpty() || !streamed.contains("tool_calls"))
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(1, reply.parts.filterIsInstance<Part.ToolCall>().size)
        assertEquals("view_user_screen", reply.parts.filterIsInstance<Part.ToolCall>().first().name)
    }

    @Test
    fun partialToolCallStartDoesNotLeakIntoStream() = runTest {
        val partialChunk = """
            )]}'
            60
            [["wrb.fr",null,"[null,null,null,null,[[null,[\"{\\n  \\\"tool\"],null,null,null,null,null,null,null,[1]]]]"]]
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(partialChunk))

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

        var streamed = ""
        model.completeStreaming("sys", userMsg, emptyList()) { text ->
            streamed = text
        }

        assertTrue("Partial tool JSON chunk starting with '{' must not stream to preview", streamed.isEmpty())
    }

    @Test
    fun strayClosingBracketsSanitizedFromTextReply() = runTest {
        val bracketChunk = """
            )]}'
            40
            [["wrb.fr",null,"[null,null,null,null,[[null,[\"]\\n}\"],null,null,null,null,null,null,null,[2]]]]"]]
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(bracketChunk))

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

        val greetingMsg = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
        val reply = model.complete("sys", greetingMsg, emptyList())

        val textParts = reply.parts.filterIsInstance<Part.Text>()
        assertTrue("Stray closing brackets must not be returned as raw text", textParts.none { it.text.contains("]\n}") })
        assertTrue("Greetings should receive friendly fallback when model emits bracket junk", textParts.any { it.text.contains("Hello!") })
    }

    @Test
    fun conversationalGreetingOmitsToolsFromPromptForFastReply() = runTest {
        val payload = buildJsonArray {
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(JsonPrimitive(null as String?))
            add(buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive("rc_1"))
                    add(buildJsonArray {
                        add(JsonPrimitive("Hello! How can I help you today?"))
                    })
                })
            })
        }.toString()

        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(payload)))

        var recordedBody = ""
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val copy = original.newBuilder().build()
                val buffer = okio.Buffer()
                copy.body?.writeTo(buffer)
                recordedBody = buffer.readUtf8()

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

        val greetingMsg = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
        val tools = listOf(ToolSpec("phone_tap", "Tap on phone screen", "{}"))
        val reply = model.complete("sys", greetingMsg, tools)

        assertTrue(reply.parts.any { it is Part.Text && it.text.contains("Hello!") })
        // Verify tools were NOT sent in the prompt for greeting. The request body
        // is form-encoded, so decode it before looking for the literal marker.
        assertTrue(
            "Greeting prompt should omit [Available Tools] for fast 1-second response",
            !decodedBody(recordedBody).contains("[Available Tools]")
        )
    }

    /** Request bodies are form-encoded; decode before asserting on prompt text. */
    private fun decodedBody(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun textPayload(text: String): String = buildJsonArray {
        add(JsonPrimitive(null as String?))
        add(JsonPrimitive(null as String?))
        add(JsonPrimitive(null as String?))
        add(JsonPrimitive(null as String?))
        add(buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive("rc_1"))
                add(buildJsonArray { add(JsonPrimitive(text)) })
            })
        })
    }.toString()

    private fun redirectingClient(record: ((String) -> Unit)? = null, jar: okhttp3.CookieJar? = null): OkHttpClient =
        OkHttpClient.Builder()
            .apply { if (jar != null) cookieJar(jar) }
            .addInterceptor { chain ->
                val original = chain.request()
                if (record != null) {
                    val buffer = okio.Buffer()
                    original.newBuilder().build().body?.writeTo(buffer)
                    record(buffer.readUtf8())
                }
                val newUrl = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.url("/").host)
                    .port(server.url("/").port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

    @Test
    fun shortRequestThatIsNotAGreetingStillGetsTools() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("ok"))))

        var recordedBody = ""
        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(record = { recordedBody = it })
        )

        val msg = listOf(Message(Role.User, listOf(Part.Text("weather batao")), Author.User))
        model.complete("sys", msg, listOf(ToolSpec("phone_tap", "Tap on phone screen", "{}")))

        assertTrue(
            "A short real request must keep its tools",
            decodedBody(recordedBody).contains("[Available Tools]")
        )
    }

    @Test
    fun everyFrameOnOneLineIsRead() = runTest {
        val line = buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive("wrb.fr"))
                add(JsonPrimitive(null as String?))
                add(JsonPrimitive(textPayload("Hello")))
            })
            add(buildJsonArray {
                add(JsonPrimitive("wrb.fr"))
                add(JsonPrimitive(null as String?))
                add(JsonPrimitive(textPayload("Hello world")))
            })
        }.toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'\n120\n$line\n"))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        val reply = model.complete("sys", userMsg, emptyList())
        assertEquals("Hello world", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }

    @Test
    fun rotatedCookiesAreSavedBackToTheSession() = runTest {
        GeminiWebClient.clearCookies()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "__Secure-1PSIDTS=rotated; Path=/; Secure; HttpOnly")
                .setBody(makeStreamData(textPayload("ok")))
        )

        var saved: GeminiWebSession? = null
        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
            onSessionUpdated = { saved = it }
        )

        model.complete("sys", userMsg, emptyList())

        assertNotNull("A rotated cookie must be written back", saved)
        assertTrue(saved!!.cookies.contains("__Secure-1PSIDTS=rotated"))
        assertTrue(saved!!.cookies.contains("__Secure-1PSID=test-sid"))
    }

    @Test
    fun requestCarriesSessionFsidUserAgentAndAuthUser() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("ok"))))

        val model = GeminiWebModel(
            session = session.copy(
                fdrfje = "sid-123",
                authUserIndex = "2",
                userAgent = "TestUA/1.0",
            ),
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        model.complete("sys", userMsg, emptyList())

        val request = server.takeRequest()
        assertTrue("f.sid must come from the login page", request.path.orEmpty().contains("f.sid=sid-123"))
        assertEquals("2", request.getHeader("X-Goog-AuthUser"))
        assertEquals("TestUA/1.0", request.getHeader("User-Agent"))
    }

    @Test(expected = ModelError.BadResponse::class)
    fun aSecond400AfterRefreshIsNotReportedAsExpiredSignIn() = runTest {
        val error = """[["er",null,null,null,null,400,null,null,null,3],["af.httprm",7,"-1",95]]"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(error))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """<script>window.WIZ_global_data = {"SNlM0e":"fresh-token","cfb2h":"bl","FdrFJe":"sid"};</script>"""
            )
        )
        // The refreshed account request fails again, and so does the guest retry.
        server.enqueue(MockResponse().setResponseCode(400).setBody(error))
        server.enqueue(MockResponse().setResponseCode(400).setBody(error))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        model.complete("sys", userMsg, emptyList())
    }

    @Test
    fun signedOutSessionClearsEveryCredential() {
        val out = session.copy(userAgent = "TestUA/1.0", capacity = 4, capacityField = 13).signedOut()
        assertFalse(out.signedIn)
        assertEquals("", out.cookies)
        assertEquals("", out.snlm0e)
        assertEquals("", out.cfb2h)
        assertEquals("", out.fdrfje)
        assertEquals("", out.email)
        assertEquals("", out.userAgent)
        assertEquals(2, out.capacity)
        assertEquals(12, out.capacityField)
    }

    @Test
    fun normalizeCookieHeaderAcceptsARawOnePsidValue() {
        assertEquals("__Secure-1PSID=abc.def==", GeminiWebClient.normalizeCookieHeader("abc.def=="))
    }

    @Test
    fun normalizeCookieHeaderKeepsAFullHeaderAndRemovesLineBreaks() {
        assertEquals(
            "__Secure-1PSID=a; __Secure-1PSIDTS=b",
            GeminiWebClient.normalizeCookieHeader("__Secure-1PSID=a;\n__Secure-1PSIDTS=b")
        )
    }
}
