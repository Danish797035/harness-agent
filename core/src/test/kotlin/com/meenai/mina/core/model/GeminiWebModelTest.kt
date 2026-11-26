package com.meenai.mina.core.model

import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.source
import okio.buffer
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

    @Test
    fun staleBuildLabelIsDetectedFromItsDate() {
        val now = 1_766_000_000_000L // any fixed instant
        val old = "boq_gemini-web-uiserver_20200101.01_p0"
        val fresh = "boq_gemini-web-uiserver_" +
            java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".01_p0"

        assertTrue(GeminiWebClient.isStaleBuildLabel(old, now))
        assertFalse(GeminiWebClient.isStaleBuildLabel(fresh, now))
        // Not a dated label: never force a refresh on a value we do not understand.
        assertFalse(GeminiWebClient.isStaleBuildLabel("test-bl", now))
        assertFalse(GeminiWebClient.isStaleBuildLabel("", now))
    }

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
    fun resolvesAllDefaultModelsCorrectly() {
        val dummySession = GeminiWebSession(cookies = "k=v", snlm0e = "token")
        val (argonHex, argonNum) = GeminiWebClient.resolveModel("gemini-4-argon", dummySession)
        assertEquals("e6fa609c3fa255c0", argonHex)
        assertEquals(3, argonNum)

        val (hydrogenHex, hydrogenNum) = GeminiWebClient.resolveModel("gemini-4-hydrogen", dummySession)
        assertEquals("56fdd199312815e2", hydrogenHex)
        assertEquals(1, hydrogenNum)

        val (proHex, proNum) = GeminiWebClient.resolveModel("gemini-3.1-pro", dummySession)
        assertEquals("e6fa609c3fa255c0", proHex)
        assertEquals(3, proNum)

        val (liteHex, liteNum) = GeminiWebClient.resolveModel("gemini-3.5-flash-lite", dummySession)
        assertEquals("8c46e95b1a07cecc", liteHex)
        assertEquals(6, liteNum)
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
    fun thoughtsOnlyReplyIsRetriedAsGuest() = runTest {
        GeminiWebClient.clearCookies()
        fun payload(text: String, thoughts: String?): String {
            val candidate = buildJsonArray {
                add(JsonPrimitive("rc_3"))
                add(buildJsonArray { add(JsonPrimitive(text)) })
                for (i in 2..36) add(JsonPrimitive(null as String?))
                add(buildJsonArray { add(buildJsonArray { add(JsonPrimitive(thoughts)) }) })
            }
            return buildJsonArray {
                repeat(4) { add(JsonPrimitive(null as String?)) }
                add(buildJsonArray { add(candidate) })
            }.toString()
        }

        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(payload("", "only thinking"))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(payload("Real answer", null))))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertTrue(reply.parts.any { it == Part.Text("Real answer") })
        assertTrue(server.requestCount >= 2)
    }

    @Test
    fun aSessionMarkedGuestSkipsTheAccountAttempt() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("Guest"))))

        val model = GeminiWebModel(
            session = session.copy(guestMode = true),
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Guest"), reply.parts[0])
        val request = server.takeRequest()
        assertFalse(request.getHeader("Cookie").orEmpty().contains("SID"))
        assertEquals("at=", request.body.readUtf8().substringBefore("&f.req="))
    }

    @Test
    fun aSilentAccountStreamFallsBackToGuest() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val good = makeStreamData(textPayload("Guest answer")).toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val n = calls.incrementAndGet()
            val body = if (n == 1) StreamBody(StallStream(ByteArray(0))) else good.toResponseBody(null)
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }.build()

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = client,
            firstTokenTimeoutMs = 300,
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Guest answer"), reply.parts[0])
        assertEquals(2, calls.get())
    }

    @Test
    fun aStreamThatStallsAfterOneFrameFallsBackToGuest() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val emptyFrame = makeStreamData(textPayload("")).toByteArray()
        val good = makeStreamData(textPayload("Guest answer")).toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val n = calls.incrementAndGet()
            val body = if (n == 1) StreamBody(StallStream(emptyFrame)) else good.toResponseBody(null)
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }.build()

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = client,
            firstTokenTimeoutMs = 5_000,
            idleTimeoutMs = 300,
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Guest answer"), reply.parts[0])
        assertEquals(2, calls.get())
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

        var saved: GeminiWebSession? = null
        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
            onSessionUpdated = { saved = it },
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Guest answer"), reply.parts[0])
        assertEquals(true, saved?.guestMode)
        val account = server.takeRequest()
        assertTrue(account.getHeader("Cookie").orEmpty().contains("__Secure-1PSID"))
        server.takeRequest() // the page re-read
        val guest = server.takeRequest()
        assertFalse(guest.getHeader("Cookie").orEmpty().contains("SID"))
        assertEquals("at=", guest.body.readUtf8().substringBefore("&f.req="))
    }

    @Test(expected = ModelError.BadResponse::class)
    fun aQuotaStreamErrorDoesNotFallBackToGuest() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                ")]}'\n\n[[" + "\"er\",null,null,null,null,1037,null,null,null,3,\"usage limit exceeded\"" + "]]"
            )
        )

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        model.complete("sys", userMsg, emptyList())
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
    fun jsonObjectAnswerIsNotTreatedAsBracketJunk() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("""{"answer":42}"""))))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("""{"answer":42}"""), reply.parts.first())
    }

    @Test
    fun malformedCandidateSlotDoesNotKillTheStream() = runTest {
        val badCandidate = buildJsonArray {
            add(JsonPrimitive("rc_bad"))
            // Slot 1 must be an array of text, but Google sent an object.
            add(buildJsonArray { add(buildJsonObject { put("unexpected", JsonPrimitive(true)) }) })
            for (i in 2..7) add(JsonPrimitive(null as String?))
            add(buildJsonArray { add(buildJsonObject { put("indicator", JsonPrimitive(1)) }) })
            for (i in 9..36) add(JsonPrimitive(null as String?))
            // Slot 37 must be an array of arrays of text, but the inner value is an object.
            add(buildJsonArray { add(buildJsonArray { add(buildJsonObject { put("thought", JsonPrimitive("x")) }) }) })
        }
        val badPayload = buildJsonArray {
            repeat(4) { add(JsonPrimitive(null as String?)) }
            add(buildJsonArray { add(badCandidate) })
        }.toString()

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(makeStreamData(badPayload) + makeStreamData(textPayload("Safe answer")))
        )

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient()
        )

        val reply = model.complete("sys", userMsg, emptyList())

        assertEquals(Part.Text("Safe answer"), reply.parts.first())
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

    @Test
    fun completeStreamingWithThinkingStreamsLiveThoughtsBeforeVisibleAnswer() = runTest {
        fun chunkPayload(text: String, thoughts: String): String {
            val candidate = buildJsonArray {
                add(JsonPrimitive("rc_think"))
                add(buildJsonArray { add(JsonPrimitive(text)) })
                for (i in 2..36) add(JsonPrimitive(null as String?))
                add(buildJsonArray { add(buildJsonArray { add(JsonPrimitive(thoughts)) }) })
            }
            return buildJsonArray {
                repeat(4) { add(JsonPrimitive(null as String?)) }
                add(buildJsonArray { add(candidate) })
            }.toString()
        }

        val combinedBody = makeStreamData(chunkPayload("", "Checking YouTube app...")) +
            makeStreamData(chunkPayload("Opening YouTube now!", "Checking YouTube app..."))
        server.enqueue(MockResponse().setResponseCode(200).setBody(combinedBody))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )

        val seenThoughts = mutableListOf<String>()
        val seenText = mutableListOf<String>()
        val reply = model.completeStreamingWithThinking(
            system = "sys",
            messages = userMsg,
            tools = emptyList(),
            onText = { if (it.isNotEmpty()) seenText += it },
            onThinking = { if (it.isNotEmpty()) seenThoughts += it },
        )

        assertTrue(seenThoughts.contains("Checking YouTube app..."))
        assertEquals(listOf("Opening YouTube now!"), seenText)
        assertTrue(reply.parts.any { it is Part.Thinking && it.text == "Checking YouTube app..." })
        assertTrue(reply.parts.any { it is Part.Text && it.text == "Opening YouTube now!" })
    }

    @Test
    fun signedInImageMessageUploadsToContentPushAndAttachesPath() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("/contrib_service/ttl_1d/test_crop.jpg"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("That is Yo Yo Honey Singh."))))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val imgMsg = listOf(
            Message(
                Role.User,
                listOf(Part.Image(b64, "image/jpeg", 200, 200), Part.Text("Who is this?")),
                Author.User,
            )
        )

        val reply = model.complete("sys", imgMsg, emptyList())
        assertEquals(Part.Text("That is Yo Yo Honey Singh."), reply.parts.first { it is Part.Text })

        val uploadReq = server.takeRequest()
        assertEquals("feeds/mcudyrk2a4khkz", uploadReq.getHeader("Push-ID"))
        val genReq = server.takeRequest()
        val bodyDecoded = java.net.URLDecoder.decode(genReq.body.readUtf8(), "UTF-8")
        assertTrue("StreamGenerate payload must contain uploaded image path", bodyDecoded.contains("/contrib_service/ttl_1d/test_crop.jpg"))
    }

    @Test
    fun failedImageUploadThrowsWithHttpStatusAndDisablesVisionCapability() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden upload"))
        // Session refresh attempt returns signed-out page so refresh does not succeed.
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Sign in</body></html>"))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        assertTrue(model.capabilities.vision)

        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val imgMsg = listOf(
            Message(
                Role.User,
                listOf(Part.Image(b64, "image/jpeg", 200, 200), Part.Text("What is this?")),
                Author.User,
            )
        )

        val err = try {
            model.complete("sys", imgMsg, emptyList())
            throw AssertionError("Expected BadResponse when image upload fails")
        } catch (e: ModelError.BadResponse) {
            e
        }

        assertTrue("Error must include HTTP status: ${err.message}", err.message!!.contains("403"))
        assertFalse("Vision capability must become false after upload failure", model.capabilities.vision)
        // Only the failed upload + refresh GET should occur; StreamGenerate must NOT be called without the image.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun imageTurnInGuestSessionUploadsImageAndNeverDropsToTextOnlyGuest() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("/contrib_service/ttl_1d/from_guest_flagged.jpg"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("This is a cat."))))

        val model = GeminiWebModel(
            session = session.copy(guestMode = true),
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val imgMsg = listOf(
            Message(
                Role.User,
                listOf(Part.Image(b64, "image/jpeg", 200, 200), Part.Text("What is this?")),
                Author.User,
            )
        )

        val reply = model.complete("sys", imgMsg, emptyList())
        assertEquals(Part.Text("This is a cat."), reply.parts.first { it is Part.Text })
        server.takeRequest() // upload
        val genReq = server.takeRequest()
        val bodyDecoded = java.net.URLDecoder.decode(genReq.body.readUtf8(), "UTF-8")
        assertTrue(bodyDecoded.contains("/contrib_service/ttl_1d/from_guest_flagged.jpg"))
        assertTrue(model.capabilities.vision)
    }

    @Test(expected = ModelError.BadResponse::class)
    fun imageStreamFailureDoesNotFallBackToTextOnlyGuest() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("/contrib_service/ttl_1d/ok_upload.jpg"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""[["er",null,null,null,null,400]]"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Sign in</body></html>"))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val imgMsg = listOf(
            Message(
                Role.User,
                listOf(Part.Image(b64, "image/jpeg", 200, 200), Part.Text("What is this?")),
                Author.User,
            )
        )

        model.complete("sys", imgMsg, emptyList())
    }

    @Test
    fun failedGeminiWebImageUploadTriggersRouterFailoverToRealVisionModel() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden upload"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Sign in</body></html>"))

        val geminiWeb = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        var backupCalls = 0
        val backupVision = object : ChatModel {
            override val name = "openrouter/vision-backup"
            override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                backupCalls++
                return ModelReply(listOf(Part.Text("Saw the screenshot on backup")), StopReason.EndTurn, null)
            }
        }

        val router = com.meenai.mina.core.router.FreeKeyRouter(
            listOf(
                com.meenai.mina.core.router.RouterCandidate(
                    "gemini-4-hydrogen", "gemini_web", geminiWeb, curatedRank = 92,
                    roles = setOf(
                        com.meenai.mina.core.router.picker.ModelRole.Main,
                        com.meenai.mina.core.router.picker.ModelRole.Vision,
                    ),
                ),
                com.meenai.mina.core.router.RouterCandidate(
                    "openrouter/vision-backup", "openrouter", backupVision, curatedRank = 50,
                    roles = setOf(
                        com.meenai.mina.core.router.picker.ModelRole.Main,
                        com.meenai.mina.core.router.picker.ModelRole.Vision,
                    ),
                ),
            ),
            delayMs = {},
            preferences = object : com.meenai.mina.core.router.RouterPreferences {
                override fun getManualRole(role: String): String? =
                    if (role.equals("vision", ignoreCase = true)) "gemini-4-hydrogen" else null
            },
        )

        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val imgMsg = listOf(
            Message(
                Role.User,
                listOf(Part.Image(b64, "image/jpeg", 200, 200), Part.Text("What is this?")),
                Author.User,
            )
        )

        val reply = router.complete("sys", imgMsg, emptyList())
        assertEquals(Part.Text("Saw the screenshot on backup"), reply.parts.first())
        assertEquals(1, backupCalls)
        assertEquals("openrouter/vision-backup", router.activeCandidate?.id)
        assertFalse(geminiWeb.capabilities.vision)
        // Subsequent vision pick must skip the blocked GeminiWebModel even if manual Vision role still names it.
        assertEquals(
            "openrouter/vision-backup",
            router.pickBestCandidate(com.meenai.mina.core.router.picker.ModelRole.Vision)?.id,
        )
    }

    @Test
    fun nonEmptyThinkingSetsEmittedAnyAndPreventsMidStreamGuestReset() = runTest {
        GeminiWebClient.clearCookies()
        fun chunk(text: String, thoughts: String): String {
            val candidate = buildJsonArray {
                add(JsonPrimitive("rc_mid"))
                add(buildJsonArray { add(JsonPrimitive(text)) })
                for (i in 2..36) add(JsonPrimitive(null as String?))
                add(buildJsonArray { add(buildJsonArray { add(JsonPrimitive(thoughts)) }) })
            }
            return buildJsonArray {
                repeat(4) { add(JsonPrimitive(null as String?)) }
                add(buildJsonArray { add(candidate) })
            }.toString()
        }

        // Emits non-empty thinking, then encounters an explicit stream error ("er") mid-stream.
        val erFrame = """[["er",null,null,null,null,"midstream failure"]]"""
        val partialThoughtThenError = makeStreamData(chunk("", "Thinking step 1...")) +
            "${erFrame.length}\n$erFrame\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(partialThoughtThenError))
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(textPayload("Should not switch mid-thought"))))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        val receivedThinking = mutableListOf<String>()
        try {
            model.completeStreamingWithThinking(
                system = "sys",
                messages = userMsg,
                tools = emptyList(),
                onText = {},
                onThinking = { receivedThinking += it },
            )
            fail("Expected BadResponse after mid-stream error once thinking already emitted")
        } catch (_: ModelError.BadResponse) {
            // Expected: non-empty onThinking marked emittedAny = true, preventing silent guest reset
        }
        assertEquals(1, server.requestCount)
        assertTrue(receivedThinking.any { it.contains("Thinking step 1") })
    }

    @Test
    fun streamingCodeBlockIsNotSuppressedWhileToolCallFenceIsStripped() = runTest {
        fun chunk(text: String): String {
            val candidate = buildJsonArray {
                add(JsonPrimitive("rc_code"))
                add(buildJsonArray { add(JsonPrimitive(text)) })
            }
            return buildJsonArray {
                repeat(4) { add(JsonPrimitive(null as String?)) }
                add(buildJsonArray { add(candidate) })
            }.toString()
        }

        val codeSample = "```kotlin\nfun hello() = \"world\"\n```"
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(chunk(codeSample))))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-hydrogen",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(jar = GeminiWebClient.cookieJar),
        )
        val streamed = mutableListOf<String>()
        val reply = model.completeStreamingWithThinking("sys", userMsg, emptyList(), { streamed += it }, {})
        assertTrue("Leading code block must remain visible in streamed output", streamed.any { it.contains("fun hello()") })
        assertEquals(codeSample, (reply.parts.first { it is Part.Text } as Part.Text).text)
    }

    @Test
    fun uploadImageUsesLatestRotatedCookieSnapshot() {
        GeminiWebClient.clearCookies()
        GeminiWebClient.seedCookies("__Secure-1PSID=orig_sid; __Secure-1PSIDTS=stale_ts")
        // Simulate server rotating __Secure-1PSIDTS into the cookie jar
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "__Secure-1PSIDTS=rotated_ts; Path=/; Secure")
                .setBody("ok")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("/contrib_service/ttl_1d/uploaded.jpg"))

        val client = redirectingClient(jar = GeminiWebClient.cookieJar)
        client.newCall(okhttp3.Request.Builder().url("https://gemini.google.com/rotate").build()).execute().close()
        server.takeRequest()

        val staleSession = session.copy(cookies = "__Secure-1PSID=orig_sid; __Secure-1PSIDTS=stale_ts")
        val path = GeminiWebClient.uploadImage(client, staleSession, byteArrayOf(1, 2, 3), "image/jpeg", "test.jpg")
        assertEquals("/contrib_service/ttl_1d/uploaded.jpg", path)

        val uploadReq = server.takeRequest()
        assertTrue(uploadReq.getHeader("Cookie").orEmpty().contains("rotated_ts"))
    }

    @Test
    fun thinkingPreferenceDynamicallySetsThinkingFlagInHeaderAndPayload() = runTest {
        GeminiWebClient.clearCookies()
        val replyPayload = buildJsonArray {
            repeat(4) { add(JsonPrimitive(null as String?)) }
            add(buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive("rc_1"))
                    add(buildJsonArray { add(JsonPrimitive("Fast or Thinking answer")) })
                    for (i in 2..36) add(JsonPrimitive(null as String?))
                    add(buildJsonArray {
                        add(buildJsonArray { add(JsonPrimitive("Hidden reasoning step")) })
                    })
                })
            })
        }.toString()

        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(replyPayload)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(makeStreamData(replyPayload)))

        val model = GeminiWebModel(
            session = session,
            model = "gemini-4-argon",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = true),
            http = redirectingClient(),
        )
        val chatMessages = listOf(Message(Role.User, listOf(Part.Text("hi there")), Author.User))

        fun parseFlags(req: okhttp3.mockwebserver.RecordedRequest): Pair<String, String> {
            val header = Json.parseToJsonElement(req.getHeader("X-Goog-Ext-525001261-Jspb")!!).jsonArray
            val headerFlag = header[header.size - 2].jsonPrimitive.content
            val bodyStr = req.body.readUtf8()
            val fReqEncoded = bodyStr.split("&").first { it.startsWith("f.req=") }.removePrefix("f.req=")
            val fReq = java.net.URLDecoder.decode(fReqEncoded, "UTF-8")
            val outer = Json.parseToJsonElement(fReq).jsonArray
            val inner = Json.parseToJsonElement(outer[1].jsonPrimitive.content).jsonArray
            val bodyFlag = inner[80].jsonPrimitive.content
            return headerFlag to bodyFlag
        }

        // Fast mode: thinkingFlag = 0 even on conversational prompts, live thinking skipped
        val fastThoughts = mutableListOf<String>()
        withContext(ThinkingPreference(false)) {
            model.completeStreamingWithThinking("sys", chatMessages, emptyList(), {}, { if (it.isNotEmpty()) fastThoughts += it })
        }
        val (fastHeaderFlag, fastBodyFlag) = parseFlags(server.takeRequest())
        assertEquals("0", fastHeaderFlag)
        assertEquals("0", fastBodyFlag)
        assertTrue(fastThoughts.isEmpty())

        // Thinking mode: thinkingFlag = 1 in header and elements[80], live thinking streamed
        val deepThoughts = mutableListOf<String>()
        withContext(ThinkingPreference(true)) {
            model.completeStreamingWithThinking("sys", chatMessages, emptyList(), {}, { if (it.isNotEmpty()) deepThoughts += it })
        }
        val (thinkHeaderFlag, thinkBodyFlag) = parseFlags(server.takeRequest())
        assertEquals("1", thinkHeaderFlag)
        assertEquals("1", thinkBodyFlag)
        assertTrue(deepThoughts.any { it.contains("Hidden reasoning step") })
    }

    /** A response body that sends [prefix] and then holds the connection open. */
    private class StreamBody(private val input: java.io.InputStream) : okhttp3.ResponseBody() {
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source(): okio.BufferedSource = input.source().buffer()
    }

    private class StallStream(private val prefix: ByteArray) : java.io.InputStream() {
        private var pos = 0
        private val lock = Object()
        private var closed = false

        override fun read(): Int = synchronized(lock) {
            if (pos < prefix.size) return prefix[pos++].toInt() and 0xFF
            while (!closed) lock.wait()
            throw java.io.IOException("closed")
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }
}
