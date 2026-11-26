package com.meenai.mina.core.gemini

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiWebClientCookieTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchSessionFromCookiesPersistsCookiesRotatedByGoogle() = runTest {
        val html = """<script>window.WIZ_global_data = {"SNlM0e":"at","cfb2h":"bl","FdrFJe":"sid"};</script>"""
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "__Secure-1PSIDTS=rotated-ts; Path=/; Secure; HttpOnly")
                .addHeader("Set-Cookie", "NID=rotated-nid; Path=/; Secure")
                .setBody(html)
        )

        val client = OkHttpClient.Builder()
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

        val session = GeminiWebClient.fetchSessionFromCookies(
            client,
            "__Secure-1PSID=test-sid; __Secure-1PSIDTS=old-ts",
        )

        assertTrue(session.cookies.contains("__Secure-1PSID=test-sid"))
        assertTrue(session.cookies.contains("__Secure-1PSIDTS=rotated-ts"))
        assertTrue(session.cookies.contains("NID=rotated-nid"))
        assertFalse(session.cookies.contains("__Secure-1PSIDTS=old-ts"))
    }

    @Test
    fun mergeSetCookieHeaderKeepsUnrelatedCookiesAndRemovesExpiredOnes() {
        val merged = GeminiWebClient.mergeSetCookieHeader(
            "__Secure-1PSID=sid; __Secure-1PSIDTS=old; NID=nid",
            listOf(
                "__Secure-1PSIDTS=new; Path=/; Secure",
                "NID=gone; Max-Age=0; Path=/",
            ),
        )

        assertTrue(merged.contains("__Secure-1PSID=sid"))
        assertTrue(merged.contains("__Secure-1PSIDTS=new"))
        assertFalse(merged.contains("__Secure-1PSIDTS=old"))
        assertFalse(merged.contains("NID="))
    }

    @Test
    fun warmUpSessionRefreshesMissingSnlm0eAndValidatesSignedInProbe() = runTest {
        GeminiWebClient.clearCookies()
        val html = """<script>window.WIZ_global_data = {"SNlM0e":"fresh-at","cfb2h":"boq_gemini-web-uiserver_20261125.01_p0","FdrFJe":"sid"};</script>"""
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "__Secure-1PSIDTS=rotated-ts; Path=/; Secure")
                .setBody(html)
        )
        val innerProbe = """[null,null,null,null,null,null,null,null,null,null,null,null,null,null,1000,[["56fdd199312815e2","Hydrogen","Gemini 4 Hydrogen","Everyday",null,null,null,null,null,1,null,"Gemini 4 Hydrogen","Everyday",null,null,null,null,1]],[16],[]]"""
        val escapedInner = innerProbe.replace("\"", "\\\"")
        val probeBody = ")]}'\n[[\"wrb.fr\",\"otAQ7b\",\"$escapedInner\"]]\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))

        val client = OkHttpClient.Builder()
            .cookieJar(GeminiWebClient.cookieJar)
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

        val initial = GeminiWebSession(
            cookies = "__Secure-1PSID=test-sid; __Secure-1PSIDTS=old-ts",
            snlm0e = "",
            cfb2h = "",
            signedIn = true,
        )
        val outcome = GeminiWebClient.warmUpSession(client, initial)
        assertTrue("Expected Ready outcome, got $outcome", outcome is GeminiWarmupOutcome.Ready)
        val ready = outcome as GeminiWarmupOutcome.Ready
        assertEquals("fresh-at", ready.session.snlm0e)
        assertTrue(ready.session.cookies.contains("__Secure-1PSIDTS=rotated-ts"))
        assertEquals("56fdd199312815e2", ready.session.defaultModelId)
    }

    @Test
    fun warmUpSessionReportsSignInExpiredWhenProbeIs1016AndRefreshFails() = runTest {
        GeminiWebClient.clearCookies()
        val innerProbe = """[null,null,null,null,null,null,null,null,null,null,null,null,null,null,1016,[]]"""
        val escapedInner = innerProbe.replace("\"", "\\\"")
        val probeBody = ")]}'\n[[\"wrb.fr\",\"otAQ7b\",\"$escapedInner\"]]\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(probeBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>accounts.google.com/ServiceLogin</html>"))

        val client = OkHttpClient.Builder()
            .cookieJar(GeminiWebClient.cookieJar)
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

        val initial = GeminiWebSession(
            cookies = "__Secure-1PSID=expired-sid",
            snlm0e = "old-at",
            cfb2h = "test-bl",
            signedIn = true,
        )
        val outcome = GeminiWebClient.warmUpSession(client, initial)
        assertTrue("Expected SignInExpired outcome, got $outcome", outcome is GeminiWarmupOutcome.SignInExpired)
    }

    @Test
    fun warmUpSessionUsesLiveCookieSnapshotInsteadOfStaleSessionCookies() = runTest {
        GeminiWebClient.clearCookies()
        GeminiWebClient.seedCookies("__Secure-1PSID=live-sid; __Secure-1PSIDTS=rotated-live-ts")

        val innerProbe = """[null,null,null,null,null,null,null,null,null,null,null,null,null,null,1000,[["56fdd199312815e2","Hydrogen","Gemini 4 Hydrogen","Everyday",null,null,null,null,null,1,null,"Gemini 4 Hydrogen","Everyday",null,null,null,null,1]],[16],[]]"""
        val escapedInner = innerProbe.replace("\"", "\\\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody(")]}'\n[[\"wrb.fr\",\"otAQ7b\",\"$escapedInner\"]]\n"))

        val client = OkHttpClient.Builder()
            .cookieJar(GeminiWebClient.cookieJar)
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

        val staleSession = GeminiWebSession(
            cookies = "__Secure-1PSID=stale-sid; __Secure-1PSIDTS=stale-ts",
            snlm0e = "live-at",
            cfb2h = "boq_gemini-web-uiserver_20991125.01_p0",
            signedIn = true,
        )
        val outcome = GeminiWebClient.warmUpSession(client, staleSession)
        assertTrue("Expected Ready outcome, got $outcome", outcome is GeminiWarmupOutcome.Ready)
        val ready = outcome as GeminiWarmupOutcome.Ready
        assertTrue(ready.session.cookies.contains("__Secure-1PSIDTS=rotated-live-ts"))
        assertFalse(ready.session.cookies.contains("stale-ts"))

        val recorded = server.takeRequest()
        val cookieHeader = recorded.getHeader("Cookie").orEmpty()
        assertTrue("Probe request should send live cookieSnapshot, got: $cookieHeader", cookieHeader.contains("__Secure-1PSIDTS=rotated-live-ts"))
        assertFalse("Probe request must not send stale cookie, got: $cookieHeader", cookieHeader.contains("stale-ts"))
    }

    @Test
    fun warmUpSessionDoesNotEmitSignInExpiredWhenSignedInPageHasSnlm0eDespiteProbe1016() = runTest {
        GeminiWebClient.clearCookies()
        val html = """<script>window.WIZ_global_data = {"SNlM0e":"fresh-at","cfb2h":"boq_gemini-web-uiserver_20991125.01_p0","FdrFJe":"sid"};</script>"""
        val inner1016 = """[null,null,null,null,null,null,null,null,null,null,null,null,null,null,1016,[]]"""
        val escaped1016 = inner1016.replace("\"", "\\\"")
        val probe1016Body = ")]}'\n[[\"wrb.fr\",\"otAQ7b\",\"$escaped1016\"]]\n"
        // First probe returns 1016, refresh fetches signed-in /app HTML with fresh SNlM0e, second probe still lags with 1016
        server.enqueue(MockResponse().setResponseCode(200).setBody(probe1016Body))
        server.enqueue(MockResponse().setResponseCode(200).setBody(html))
        server.enqueue(MockResponse().setResponseCode(200).setBody(probe1016Body))

        val client = OkHttpClient.Builder()
            .cookieJar(GeminiWebClient.cookieJar)
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

        val initial = GeminiWebSession(
            cookies = "__Secure-1PSID=active-sid; __Secure-1PSIDTS=active-ts",
            snlm0e = "old-at",
            cfb2h = "boq_gemini-web-uiserver_20991125.01_p0",
            signedIn = true,
        )
        val outcome = GeminiWebClient.warmUpSession(client, initial)
        assertTrue("Active signed-in session with SNlM0e must stay Ready instead of SignInExpired, got $outcome", outcome is GeminiWarmupOutcome.Ready)
        assertEquals("fresh-at", (outcome as GeminiWarmupOutcome.Ready).session.snlm0e)
    }
}
