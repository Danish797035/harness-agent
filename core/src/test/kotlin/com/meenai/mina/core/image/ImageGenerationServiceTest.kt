package com.meenai.mina.core.image

import com.meenai.mina.core.router.MagicubeLedger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ImageGenerationServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        MagicubeLedger.clear()
        // Default: 200 Magicube balance
        MagicubeLedger.syncBalance(200.0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        MagicubeLedger.clear()
    }

    @Test
    fun asyncFlowSucceedDownloadsImageAndDeductsMagicube() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val imageBytes = "FAKE_JPEG_IMAGE_BYTES".toByteArray()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/v1/images/generations") -> {
                        assertEquals("true", request.getHeader("X-ModelScope-Async-Mode"))
                        assertEquals("Bearer test-key", request.getHeader("Authorization"))
                        MockResponse().setResponseCode(200).setBody("""{"task_id": "test-task-1"}""")
                    }
                    path.contains("/v1/tasks/test-task-1") -> {
                        assertEquals("image_generation", request.getHeader("X-ModelScope-Task-Type"))
                        assertEquals("Bearer test-key", request.getHeader("Authorization"))
                        MockResponse().setResponseCode(200).setBody(
                            """{"task_status": "SUCCEED", "output_images": ["$serverUrl/images/out.jpg"]}"""
                        )
                    }
                    path.contains("/images/out.jpg") -> {
                        MockResponse().setResponseCode(200).setBody(okio.Buffer().write(imageBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 1L,
            maxTimeoutMs = 2000L,
        )

        val service = ImageGenerationService(
            modelScopeClient = client,
            getApiKey = { "test-key" },
        )

        val initialBalance = MagicubeLedger.totalBalance()
        assertEquals(200.0, initialBalance, 0.001)

        val result = service.generateImage("A cute cat in the garden")
        assertTrue("Result should be Success", result is ImageResult.Success)

        val success = result as ImageResult.Success
        assertEquals("Qwen/Qwen-Image-2.1", success.model)
        assertEquals(1.0, success.costMagicube, 0.001)
        assertTrue(success.bytes.contentEquals(imageBytes))

        // Balance should be deducted by 1.0 (Qwen-Image-2.1 cost is 1.0)
        assertEquals(199.0, MagicubeLedger.totalBalance(), 0.001)
    }

    @Test
    fun asyncFlowFailedSwitchesToNextModelSilently() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val imageBytes = "SECOND_MODEL_IMAGE".toByteArray()
        val submitCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/v1/images/generations") -> {
                        val count = submitCount.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("""{"task_id": "task-$count"}""")
                    }
                    path.contains("/v1/tasks/task-1") -> {
                        // First model fails
                        MockResponse().setResponseCode(200).setBody(
                            """{"task_status": "FAILED", "message": "Server temporarily overloaded"}"""
                        )
                    }
                    path.contains("/v1/tasks/task-2") -> {
                        // Second model succeeds
                        MockResponse().setResponseCode(200).setBody(
                            """{"task_status": "SUCCEED", "output_images": ["$serverUrl/images/second.jpg"]}"""
                        )
                    }
                    path.contains("/images/second.jpg") -> {
                        MockResponse().setResponseCode(200).setBody(okio.Buffer().write(imageBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 1L,
            maxTimeoutMs = 2000L,
        )

        val service = ImageGenerationService(
            modelScopeClient = client,
            getApiKey = { "test-key" },
        )

        val result = service.generateImage("A sunset over mountains")
        assertTrue("Result should be Success", result is ImageResult.Success)

        val success = result as ImageResult.Success
        // Should have silently switched to Qwen/Qwen-Image
        assertEquals("Qwen/Qwen-Image", success.model)
        assertEquals(2.0, success.costMagicube, 0.001)
        assertTrue(success.bytes.contentEquals(imageBytes))

        // First failed model should NOT be charged, only second model (2.0)
        assertEquals(198.0, MagicubeLedger.totalBalance(), 0.001)
    }

    @Test
    fun asyncFlowTimeoutSwitchesToNextModel() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val imageBytes = "TIMEOUT_FALLBACK_IMAGE".toByteArray()
        val submitCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/v1/images/generations") -> {
                        val count = submitCount.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("""{"task_id": "task-$count"}""")
                    }
                    path.contains("/v1/tasks/task-1") -> {
                        // First model hangs in RUNNING status
                        MockResponse().setResponseCode(200).setBody("""{"task_status": "RUNNING"}""")
                    }
                    path.contains("/v1/tasks/task-2") -> {
                        // Second model succeeds immediately
                        MockResponse().setResponseCode(200).setBody(
                            """{"task_status": "SUCCEED", "output_images": ["$serverUrl/images/fallback.jpg"]}"""
                        )
                    }
                    path.contains("/images/fallback.jpg") -> {
                        MockResponse().setResponseCode(200).setBody(okio.Buffer().write(imageBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 10L,
            maxTimeoutMs = 50L, // Very short timeout to trigger timeout switch quickly
        )

        val service = ImageGenerationService(
            modelScopeClient = client,
            getApiKey = { "test-key" },
        )

        val result = service.generateImage("A forest with sunlight")
        assertTrue("Result should be Success", result is ImageResult.Success)

        val success = result as ImageResult.Success
        assertEquals("Qwen/Qwen-Image", success.model)
        assertEquals(2.0, success.costMagicube, 0.001)
    }

    @Test
    fun magicubeBalanceBelowFiveRejectsWithoutCallingApi() = runBlocking {
        // Set balance below 5 (e.g. 4.0)
        MagicubeLedger.syncBalance(4.0)

        val serverUrl = server.url("/").toString().trimEnd('/')
        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 1L,
            maxTimeoutMs = 1000L,
        )

        val service = ImageGenerationService(
            modelScopeClient = client,
            getApiKey = { "test-key" },
        )

        val result = service.generateImage("A fast race car")
        assertTrue(result is ImageResult.Error)

        val error = result as ImageResult.Error
        assertEquals("Aaj ke Magicube kam hain", error.message)
        assertEquals("No requests must be made to server when balance < 5", 0, server.requestCount)
        assertEquals("Balance remains unchanged at 4.0", 4.0, MagicubeLedger.totalBalance(), 0.001)
    }

    @Test
    fun paidSwitchOffNeverPicksOpenRouterImageModel() {
        val serviceOff = ImageGenerationService(
            modelScopeClient = ModelScopeImageClient(),
            getApiKey = { "key" },
            isPaidAllowed = { false },
        )

        val candidatesOff = serviceOff.resolveCandidates(ImageRole.Image)
        assertTrue("Candidates should not be empty", candidatesOff.isNotEmpty())
        assertFalse(
            "Paid OpenRouter model should NOT be in candidate list when paid switch is OFF",
            candidatesOff.any { it.provider == ImageModelCatalog.PROVIDER_OPENROUTER || it.isPaid }
        )

        val serviceOn = ImageGenerationService(
            modelScopeClient = ModelScopeImageClient(),
            getApiKey = { "key" },
            isPaidAllowed = { true },
        )

        val candidatesOn = serviceOn.resolveCandidates(ImageRole.Image)
        assertTrue(
            "Paid OpenRouter model should be present when paid switch is ON",
            candidatesOn.any { it.provider == ImageModelCatalog.PROVIDER_OPENROUTER && it.isPaid }
        )
    }

    @Test
    fun noApiKeyOrBase64InLogs() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val secretApiKey = "sk-ultra-secret-test-key-999"
        val secretBase64 = "TOP_SECRET_USER_PHOTO_BASE64_PAYLOAD_XYZ"

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/v1/images/generations") -> {
                        MockResponse().setResponseCode(200).setBody("""{"task_id": "test-task-sec"}""")
                    }
                    path.contains("/v1/tasks/test-task-sec") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"task_status": "SUCCEED", "output_images": ["$serverUrl/images/edited.jpg"]}"""
                        )
                    }
                    path.contains("/images/edited.jpg") -> {
                        MockResponse().setResponseCode(200).setBody(okio.Buffer().write("EDITED_BYTES".toByteArray()))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val logLines = mutableListOf<String>()
        val logger: (String) -> Unit = { logLines.add(it) }

        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 1L,
            maxTimeoutMs = 1000L,
            logger = logger,
        )

        val service = ImageGenerationService(
            modelScopeClient = client,
            getApiKey = { secretApiKey },
            logger = logger,
        )

        val result = service.editImage("Change hair color to purple", secretBase64)
        assertTrue(result is ImageResult.Success)

        assertTrue("Logs must have been recorded", logLines.isNotEmpty())
        for (line in logLines) {
            assertFalse(
                "Log line must NOT contain secret API key: $line",
                line.contains(secretApiKey)
            )
            assertFalse(
                "Log line must NOT contain base64 image data: $line",
                line.contains(secretBase64)
            )
        }
    }

    @Test
    fun magicubeCostForModelMatchesSpecs() {
        assertEquals(1.0, MagicubeLedger.costForModel("Qwen/Qwen-Image-2.1"), 0.001)
        assertEquals(2.0, MagicubeLedger.costForModel("Qwen/Qwen-Image"), 0.001)
        assertEquals(2.0, MagicubeLedger.costForModel("Qwen/Qwen-Image-Edit"), 0.001)
    }

    @Test
    fun magicubeBalanceBelowFiveAllowsOpenRouterCandidate() = runBlocking {
        MagicubeLedger.syncBalance(2.0)
        val imageBytes = "OPENROUTER_IMAGE".toByteArray()

        val failingModelScopeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray =
                throw AssertionError("ModelScope should not be called when balance < 5")
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray =
                throw AssertionError("ModelScope should not be called when balance < 5")
        }

        val fakeOpenRouterClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray = imageBytes
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray = imageBytes
        }

        val service = ImageGenerationService(
            modelScopeClient = failingModelScopeClient,
            openRouterClient = fakeOpenRouterClient,
            getApiKey = { "or-key" },
            isPaidAllowed = { true },
        )

        val result = service.generateImage("cat")
        assertTrue(result is ImageResult.Success)
        val success = result as ImageResult.Success
        assertEquals(0.0, success.costMagicube, 0.001)
        assertTrue(success.bytes.contentEquals(imageBytes))
    }

    @Test
    fun transientPollErrorsRetriedBeforeSucceeding() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val imageBytes = "RETRY_SUCCESS_BYTES".toByteArray()
        val pollAttempts = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/v1/images/generations") -> {
                        MockResponse().setResponseCode(200).setBody("""{"task_id": "retry-task"}""")
                    }
                    path.contains("/v1/tasks/retry-task") -> {
                        val attempt = pollAttempts.incrementAndGet()
                        if (attempt <= 3) {
                            MockResponse().setResponseCode(502).setBody("Bad Gateway")
                        } else {
                            MockResponse().setResponseCode(200).setBody(
                                """{"task_status": "SUCCEED", "output_images": ["$serverUrl/images/retry.jpg"]}"""
                            )
                        }
                    }
                    path.contains("/images/retry.jpg") -> {
                        MockResponse().setResponseCode(200).setBody(okio.Buffer().write(imageBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 1L,
            maxTimeoutMs = 5000L,
        )

        val result = client.generate("test-key", "Qwen/Qwen-Image-2.1", "sunset")
        assertTrue("Task should succeed after 3 transient poll failures", result.contentEquals(imageBytes))
        assertEquals(4, pollAttempts.get())
    }

    @Test
    fun fiveConsecutivePollErrorsFailTask() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val pollAttempts = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/v1/images/generations") -> {
                        MockResponse().setResponseCode(200).setBody("""{"task_id": "fail-task"}""")
                    }
                    path.contains("/v1/tasks/fail-task") -> {
                        pollAttempts.incrementAndGet()
                        MockResponse().setResponseCode(500).setBody("Server Error")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = ModelScopeImageClient(
            baseUrl = serverUrl,
            http = OkHttpClient(),
            pollIntervalMs = 1L,
            maxTimeoutMs = 5000L,
        )

        var threw = false
        try {
            client.generate("test-key", "Qwen/Qwen-Image-2.1", "sunset")
        } catch (e: ImageClientException) {
            threw = true
        }
        assertTrue("Expected ImageClientException after 5 consecutive poll errors", threw)
        assertEquals(5, pollAttempts.get())
    }

    @Test
    fun cancellationExceptionIsRethrownWithoutFailover() = runBlocking {
        val calls = AtomicInteger(0)
        val cancellingClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                calls.incrementAndGet()
                throw kotlinx.coroutines.CancellationException("cancelled")
            }

            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray =
                throw kotlinx.coroutines.CancellationException("cancelled")
        }

        val service = ImageGenerationService(
            modelScopeClient = cancellingClient,
            getApiKey = { "test-key" },
        )

        var caughtCancellation = false
        try {
            service.generateImage("sunset")
        } catch (e: kotlinx.coroutines.CancellationException) {
            caughtCancellation = true
        }
        assertTrue("CancellationException must propagate immediately", caughtCancellation)
        assertEquals("Must not fail over to second candidate after cancellation", 1, calls.get())
    }
}
