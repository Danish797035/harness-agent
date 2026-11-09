package com.meenai.mina.core.image

import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.ToolConfig
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.verify.EvidenceLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageToolsTest {

    @Before
    fun setUp() {
        MagicubeLedger.clear()
        MagicubeLedger.syncBalance(100.0)
    }

    @After
    fun tearDown() {
        MagicubeLedger.clear()
    }

    @Test
    fun generateImageToolExecutesAndInvokesCallbacks() = runBlocking {
        var progressCalled = false
        var savedCalled = false

        val fakeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                return "FAKE_IMAGE_BYTES".toByteArray()
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                return "FAKE_IMAGE_BYTES".toByteArray()
            }
        }

        val service = ImageGenerationService(
            modelScopeClient = fakeClient,
            getApiKey = { "key" },
        )

        val config = ToolConfig(
            imageService = service,
            onImageProgress = { _, _, _ -> progressCalled = true },
            onImageSaved = { _, _, _ ->
                savedCalled = true
                "/fake/cache/img.jpg"
            },
        )
        val ctx = ToolContext(config = config, evidenceLog = EvidenceLog(), tools = ToolIndex(emptyList()))

        assertTrue("GenerateImageTool should be available when imageService is present", GenerateImageTool.available(ctx))

        val result = GenerateImageTool.execute(Args(mapOf("prompt" to "A sunny beach")), ctx)
        assertTrue("Execution should succeed: ${result.text}", result.isOk)
        assertTrue(progressCalled)
        assertTrue(savedCalled)
    }

    @Test
    fun generateImageToolRejectsEmptyPromptOrVideo() = runBlocking {
        val fakeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?) =
                "BYTES".toByteArray()
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String) =
                "BYTES".toByteArray()
        }
        val service = ImageGenerationService(fakeClient, getApiKey = { "key" })
        val config = ToolConfig(imageService = service)
        val ctx = ToolContext(config = config, evidenceLog = EvidenceLog(), tools = ToolIndex(emptyList()))

        val emptyRes = GenerateImageTool.execute(Args(mapOf("prompt" to "")), ctx)
        assertFalse("Empty prompt must fail", emptyRes.isOk)

        val videoRes = GenerateImageTool.execute(Args(mapOf("prompt" to "make a video of fireworks")), ctx)
        assertFalse("Video request must fail", videoRes.isOk)
    }

    @Test
    fun editImageToolRejectsSensitiveAppPhoto() = runBlocking {
        val fakeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?) =
                "BYTES".toByteArray()
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String) =
                "BYTES".toByteArray()
        }
        val service = ImageGenerationService(fakeClient, getApiKey = { "key" })

        val config = ToolConfig(
            imageService = service,
            attachedPhoto = AttachedPhoto(
                base64 = "BASE64_DATA",
                source = PhotoSource.UserAttachment,
                sourcePackage = "com.secret.bank",
            ),
            isSensitivePackage = { pkg -> pkg == "com.secret.bank" },
        )
        val ctx = ToolContext(config = config, evidenceLog = EvidenceLog(), tools = ToolIndex(emptyList()))

        val result = EditImageTool.execute(Args(mapOf("prompt" to "change background")), ctx)
        assertFalse("Sensitive app photo must be rejected", result.isOk)
        assertTrue(result.text.contains("Sensitive ya blocked app"))
    }
}
