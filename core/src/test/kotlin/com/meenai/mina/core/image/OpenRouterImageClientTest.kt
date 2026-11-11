package com.meenai.mina.core.image

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class OpenRouterImageClientTest {

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
    fun generateSendsModalitiesAndParsesDataUrl() = runBlocking {
        val rawBytes = "GENERATED_IMAGE_DATA".toByteArray()
        val base64Data = Base64.getEncoder().encodeToString(rawBytes)
        val responseJson = """
            {
              "id": "gen-123",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "data:image/png;base64,$base64Data"
                  }
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val client = OpenRouterImageClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            http = OkHttpClient(),
        )

        val result = client.generate("test-key", "google/imagen-3", "a red cat", null)
        assertArrayEquals(rawBytes, result)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val bodyStr = recorded.body.readUtf8()
        assertTrue("Must specify image modality", bodyStr.contains("\"modalities\":[\"image\",\"text\"]"))
        assertTrue("Must contain model name", bodyStr.contains("\"model\":\"google/imagen-3\""))
        assertTrue("Must contain prompt", bodyStr.contains("a red cat"))
    }

    @Test
    fun generateParsesImagesArray() = runBlocking {
        val rawBytes = "IMAGE_FROM_IMAGES_ARRAY".toByteArray()
        val base64Data = Base64.getEncoder().encodeToString(rawBytes)
        val responseJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Here is your image",
                    "images": [
                      {
                        "type": "image_url",
                        "image_url": {
                          "url": "data:image/jpeg;base64,$base64Data"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val client = OpenRouterImageClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            http = OkHttpClient(),
        )

        val result = client.generate("test-key", "stabilityai/sdxl", "sunset", null)
        assertArrayEquals(rawBytes, result)
    }

    @Test
    fun editSendsInputImageAndPrompt() = runBlocking {
        val inputBytes = "ORIGINAL_IMAGE".toByteArray()
        val inputBase64 = Base64.getEncoder().encodeToString(inputBytes)

        val outputBytes = "EDITED_IMAGE".toByteArray()
        val outputBase64 = Base64.getEncoder().encodeToString(outputBytes)

        val responseJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "data:image/png;base64,$outputBase64"
                  }
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

        val client = OpenRouterImageClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            http = OkHttpClient(),
        )

        val result = client.edit("test-key", "google/imagen-3", "add sunglasses", inputBase64)
        assertArrayEquals(outputBytes, result)

        val recorded = server.takeRequest()
        val bodyStr = recorded.body.readUtf8()
        assertTrue("Must contain input image_url", bodyStr.contains("data:image/png;base64,$inputBase64"))
        assertTrue("Must contain edit prompt", bodyStr.contains("add sunglasses"))
    }

    @Test
    fun throwsOnOpenRouterError() = runBlocking {
        val errorJson = """
            {
              "error": {
                "message": "Insufficient credits",
                "code": 402
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(402).setBody(errorJson))

        val client = OpenRouterImageClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            http = OkHttpClient(),
        )

        try {
            client.generate("test-key", "google/imagen-3", "test", null)
            fail("Expected ImageClientException")
        } catch (e: ImageClientException) {
            assertTrue(e.message?.contains("Insufficient credits") == true || e.message?.contains("402") == true)
        }
    }
}
