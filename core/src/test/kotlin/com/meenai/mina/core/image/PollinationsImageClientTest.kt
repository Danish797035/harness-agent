package com.meenai.mina.core.image

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PollinationsImageClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun generateEncodesSpacesWithPercent20PassesRequestedModelAndVerifiesImageContentType() = runBlocking {
        val imgBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(imgBytes))
        )

        val client = PollinationsImageClient(
            http = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        val out = client.generate("k", "turbo", "cute orange cat", null)
        assertArrayEquals(imgBytes, out)

        val req = server.takeRequest()
        assertTrue("Path must use %20 instead of +", req.path!!.startsWith("/prompt/cute%20orange%20cat?"))
        assertFalse("Path must not use + for spaces", req.path!!.substringBefore('?').contains('+'))
        assertEquals("turbo", req.requestUrl?.queryParameter("model"))
    }

    @Test(expected = ImageClientException::class)
    fun generateRejectsNonImageContentType() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html>rate limit</html>")
        )
        val client = PollinationsImageClient(
            http = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        client.generate("k", "flux-pollinations", "cat", null)
        Unit
    }

    @Test(expected = UnsupportedOperationException::class)
    fun editThrowsUnsupportedOperationException() = runBlocking {
        val client = PollinationsImageClient(http = OkHttpClient())
        client.edit("k", "flux-pollinations", "add glasses", "AQID")
        Unit
    }
}
