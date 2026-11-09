package com.meenai.mina.image

import com.meenai.mina.core.image.AttachedPhoto
import com.meenai.mina.core.image.EditImageTool
import com.meenai.mina.core.image.GenerateImageTool
import com.meenai.mina.core.image.ImageGenerationService
import com.meenai.mina.core.image.ImageIntentRouter
import com.meenai.mina.core.image.ImageResult
import com.meenai.mina.core.image.PhotoSource
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.ToolConfig
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.ui.ChatCopy
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import com.meenai.mina.ui.exchangeItemKeys
import com.meenai.mina.ui.image.GeneratedImageUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageChatWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun toolCallGeneratesImageCardProgressAndResult() = runTest {
        val root = tmp.newFolder("image_cache")
        val progressUpdates = mutableListOf<GeneratedImageUi>()
        var savedFilePath: String? = null

        val fakeService = object : ImageGenerationService() {
            override suspend fun generateImage(prompt: String, size: String?): ImageResult {
                val fakeBytes = "FAKED_PNG_BYTES".toByteArray()
                return ImageResult.Success(
                    bytes = fakeBytes,
                    contentType = "image/jpeg",
                    model = "Qwen/Qwen-Image-2.1",
                    costMagicube = 1.0,
                )
            }
        }

        val onProgress: (Boolean, String, String) -> Unit = { isGenerating, prompt, model ->
            progressUpdates += GeneratedImageUi(
                filePath = null,
                isGenerating = isGenerating,
                prompt = prompt,
                model = model,
            )
        }

        val onSaved: (ByteArray, String, String) -> String = { bytes, prompt, model ->
            val savedFile = ImageStorageManager.saveToPrivateCache(root, bytes)
            savedFilePath = savedFile.absolutePath
            progressUpdates += GeneratedImageUi(
                filePath = savedFilePath,
                isGenerating = false,
                prompt = prompt,
                model = model,
            )
            savedFile.absolutePath
        }

        val config = ToolConfig(
            imageService = fakeService,
            onImageProgress = onProgress,
            onImageSaved = onSaved,
        )
        val ctx = ToolContext(config = config, evidenceLog = com.meenai.mina.core.verify.EvidenceLog(), tools = ToolIndex(emptyList()))

        assertTrue("GenerateImageTool should be available when imageService is set", GenerateImageTool.available(ctx))

        val exec = GenerateImageTool.execute(Args(mapOf("prompt" to "A beautiful mountain")), ctx)
        assertTrue("Tool execution must succeed: ${exec.text}", exec.isOk)

        // Progress updates: first isGenerating = true, then isGenerating = false with filePath
        assertTrue(progressUpdates.size >= 2)
        assertTrue("First progress should indicate generating", progressUpdates.first().isGenerating)
        assertFalse("Final update should indicate finished", progressUpdates.last().isGenerating)
        assertNotNull("File path should be populated", progressUpdates.last().filePath)

        // Verify Chat UI integration: exchangeItemKeys generates img key
        val ex = Exchange(
            goal = "A beautiful mountain",
            steps = emptyList(),
            status = ExchangeStatus.Done("Image ban gayi", null),
            live = false,
            generatedImages = listOf(progressUpdates.last()),
        )
        val keys = exchangeItemKeys(ex)
        val imgKey = keys.firstOrNull { it.first == "img:${ex.id}-0" }
        assertNotNull("Chat exchange must contain img:\${ex.id}-0 key", imgKey)
        assertEquals("generated_image", imgKey?.second)
    }

    @Test
    fun purgeOnAppStartRemovesOldCacheAndKeepsFresh() {
        val root = tmp.newFolder("app_start_purge")
        val nowMs = 1_700_000_000_000L
        val tenDaysAgo = nowMs - (10L * 24 * 60 * 60 * 1000L)
        val twoDaysAgo = nowMs - (2L * 24 * 60 * 60 * 1000L)

        val oldImg = ImageStorageManager.saveToPrivateCache(root, "OLD_DATA".toByteArray())
        oldImg.setLastModified(tenDaysAgo)

        val freshImg = ImageStorageManager.saveToPrivateCache(root, "FRESH_DATA".toByteArray())
        freshImg.setLastModified(twoDaysAgo)

        val deletedCount = ImageStorageManager.cleanOldCache(root, nowMs)
        assertEquals(1, deletedCount)
        assertFalse("File older than 7 days should be purged", oldImg.exists())
        assertTrue("File newer than 7 days should be retained", freshImg.exists())
    }

    @Test
    fun videoRequestReturnsVideoNotSupportedReply() {
        assertTrue(ImageIntentRouter.isVideoRequest("ek billi ka video banao"))
        assertTrue(ImageIntentRouter.isVideoRequest("make a 5 second video"))
        assertTrue(ImageIntentRouter.isVideoRequest("generate video of dancing robot"))
        assertFalse(ImageIntentRouter.isVideoRequest("ek billi ki photo banao"))
        assertFalse(ImageIntentRouter.isVideoRequest("draw a portrait of cat"))

        assertEquals("Video abhi support nahi hai.", ImageIntentRouter.VIDEO_NOT_SUPPORTED_MSG)
    }

    @Test
    fun sensitivePackagePhotoIsBlockedForEdit() = runTest {
        val fakeService = object : ImageGenerationService() {
            override suspend fun editImage(prompt: String, imageBase64: String): ImageResult {
                return ImageResult.Success("FAKE_BYTES".toByteArray(), "image/jpeg", "Qwen/Qwen-Image-Edit", 2.0)
            }
        }

        val config = ToolConfig(
            imageService = fakeService,
            attachedPhoto = AttachedPhoto(
                base64 = "BASE64_SENSITIVE",
                source = PhotoSource.UserAttachment,
                sourcePackage = "com.banking.app",
            ),
            isSensitivePackage = { pkg -> pkg == "com.banking.app" },
        )
        val ctx = ToolContext(config = config, evidenceLog = com.meenai.mina.core.verify.EvidenceLog(), tools = ToolIndex(emptyList()))

        val exec = EditImageTool.execute(Args(mapOf("prompt" to "make it blue")), ctx)
        assertFalse("Should fail for sensitive app photo", exec.isOk)
        assertTrue("Failure message should mention sensitive app", exec.text.contains("Sensitive ya blocked app"))
    }
}
