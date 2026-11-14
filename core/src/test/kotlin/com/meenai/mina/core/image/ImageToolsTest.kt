package com.meenai.mina.core.image

import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.ToolConfig
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.EvidenceLog
import com.meenai.mina.core.visual.Shots
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
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
        val ctx = ToolContext(config = config, evidence = EvidenceLog(), tools = ToolIndex(emptyList()), scripts = null, shots = Shots())

        assertTrue("GenerateImageTool should be available when imageService is present", GenerateImageTool.available(ctx))

        val args = Args(GenerateImageTool.def, buildJsonObject { put("prompt", "A sunny beach") })
        val result = GenerateImageTool.execute(args, ctx)
        assertEquals(true, result.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(progressCalled)
        assertTrue(savedCalled)
        assertEquals("/fake/cache/img.jpg", ctx.lastGeneratedImagePath)
        assertFalse("GenerateImageTool must become unavailable once image is generated", GenerateImageTool.available(ctx))
    }

    @Test
    fun generateImageToolDoesNotRegenerateIfCalledAgainInSameTask() = runBlocking {
        var generateCallCount = 0
        val fakeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                generateCallCount++
                return "FAKE_BYTES".toByteArray()
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray = ByteArray(0)
        }
        val service = ImageGenerationService(fakeClient, getApiKey = { "key" })
        val config = ToolConfig(
            imageService = service,
            onImageSaved = { _, _, _ -> "/fake/cache/first.jpg" },
        )
        val ctx = ToolContext(config = config, evidence = EvidenceLog(), tools = ToolIndex(emptyList()), scripts = null, shots = Shots())

        val args = Args(GenerateImageTool.def, buildJsonObject { put("prompt", "a mountain") })
        val res1 = GenerateImageTool.execute(args, ctx)
        assertEquals(true, res1.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, generateCallCount)
        assertEquals("/fake/cache/first.jpg", ctx.lastGeneratedImagePath)
        assertFalse(GenerateImageTool.available(ctx))

        // Call again in same task context
        val res2 = GenerateImageTool.execute(args, ctx)
        assertEquals(true, res2.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("/fake/cache/first.jpg", res2.content["filePath"]?.jsonPrimitive?.content)
        assertEquals(1, generateCallCount) // Must NOT make a second generate call!
    }

    @Test
    fun generateImageToolPassesRequestedModel() = runBlocking {
        var requestedModelUsed: String? = null
        val fakeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                requestedModelUsed = model
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

        val config = ToolConfig(imageService = service)
        val ctx = ToolContext(config = config, evidence = EvidenceLog(), tools = ToolIndex(emptyList()), scripts = null, shots = Shots())

        // Explicit model argument
        val argsExplicit = Args(GenerateImageTool.def, buildJsonObject {
            put("prompt", "a mountain landscape")
            put("model", "Qwen/Qwen-Image-2.1")
        })
        val res1 = GenerateImageTool.execute(argsExplicit, ctx)
        assertEquals(true, res1.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Qwen/Qwen-Image-2.1", requestedModelUsed)

        // Model detected in prompt
        val argsPrompt = Args(GenerateImageTool.def, buildJsonObject {
            put("prompt", "generate a cat with qwen-image-2.1")
        })
        val res2 = GenerateImageTool.execute(argsPrompt, ctx)
        assertEquals(true, res2.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("Qwen/Qwen-Image-2.1", requestedModelUsed)
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
        val ctx = ToolContext(config = config, evidence = EvidenceLog(), tools = ToolIndex(emptyList()), scripts = null, shots = Shots())

        val videoArgs = Args(GenerateImageTool.def, buildJsonObject { put("prompt", "make a video of fireworks") })
        val videoRes = GenerateImageTool.execute(videoArgs, ctx)
        assertEquals(false, videoRes.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("video_not_supported", videoRes.content["error"]?.jsonPrimitive?.content)
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
        val ctx = ToolContext(config = config, evidence = EvidenceLog(), tools = ToolIndex(emptyList()), scripts = null, shots = Shots())

        val args = Args(EditImageTool.def, buildJsonObject { put("prompt", "change background") })
        val result = EditImageTool.execute(args, ctx)
        assertEquals(false, result.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(result.content["message"]?.jsonPrimitive?.content?.contains("Sensitive ya blocked app") == true)
    }
}
