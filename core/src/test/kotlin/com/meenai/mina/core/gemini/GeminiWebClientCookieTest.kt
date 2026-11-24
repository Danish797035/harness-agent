package com.meenai.mina.core.gemini

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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
}
