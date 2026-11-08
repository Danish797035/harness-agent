package com.meenai.mina.core.image

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ImageIntentRouterTest {

    private fun fakeChatModel(
        name: String = "fake-model",
        onComplete: (system: String, messages: List<Message>, tools: List<ToolSpec>) -> ModelReply,
    ): ChatModel = object : ChatModel {
        override val name: String = name
        override val capabilities: Capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)

        override suspend fun complete(
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
        ): ModelReply = onComplete(system, messages, tools)
    }

    @Test
    fun promptEkBilliKiPhotoBanaoCallsGenerateImageTool() = runBlocking {
        val router = ImageIntentRouter()
        val modelCalled = AtomicBoolean(false)

        val lightModel = fakeChatModel("light-qwen") { system, messages, tools ->
            modelCalled.set(true)
            assertTrue("Tools must include generate_image", tools.any { it.name == ImageTools.GENERATE_IMAGE_NAME })
            assertTrue("Tools must include edit_image", tools.any { it.name == ImageTools.EDIT_IMAGE_NAME })

            ModelReply(
                parts = listOf(
                    Part.ToolCall(
                        id = "call-1",
                        name = "generate_image",
                        argsJson = """{"prompt": "A cute fluffy cat playing in a sunny garden", "size": "1024*1024"}"""
                    )
                ),
                usage = Usage(10, 20),
                stopReason = StopReason.ToolUse,
            )
        }

        val result = router.route(
            userText = "ek billi ki photo banao",
            lightModel = lightModel,
            mainModel = null,
        )

        assertTrue(modelCalled.get())
        assertTrue("Result should be Generate action", result is ImageIntentAction.Generate)

        val generate = result as ImageIntentAction.Generate
        assertEquals("A cute fluffy cat playing in a sunny garden", generate.prompt)
        assertEquals("1024*1024", generate.size)
    }

    @Test
    fun photoPlusBackgroundNeelaKaroCallsEditImageTool() = runBlocking {
        val router = ImageIntentRouter()
        val fakePhoto = AttachedPhoto(
            base64 = "BASE64_USER_ATTACHED_FAMILY_PHOTO",
            source = PhotoSource.UserAttachment,
        )

        val lightModel = fakeChatModel("light-qwen") { _, _, _ ->
            ModelReply(
                parts = listOf(
                    Part.ToolCall(
                        id = "call-2",
                        name = "edit_image",
                        argsJson = """{"prompt": "Change the background color to blue"}"""
                    )
                ),
                usage = Usage(15, 25),
                stopReason = StopReason.ToolUse,
            )
        }

        val result = router.route(
            userText = "background neela karo",
            attachedPhoto = fakePhoto,
            lightModel = lightModel,
            mainModel = null,
        )

        assertTrue("Result should be Edit action", result is ImageIntentAction.Edit)
        val edit = result as ImageIntentAction.Edit
        assertEquals("Change the background color to blue", edit.prompt)
        assertEquals("BASE64_USER_ATTACHED_FAMILY_PHOTO", edit.imageBase64)
    }

    @Test
    fun screenCapturePhotoRejectedForEdit() = runBlocking {
        val router = ImageIntentRouter()
        val screenCapturePhoto = AttachedPhoto(
            base64 = "BASE64_SCREEN_CAPTURE",
            source = PhotoSource.ScreenCapture,
        )

        val lightModel = fakeChatModel { _, _, _ ->
            ModelReply(
                parts = listOf(
                    Part.ToolCall(
                        id = "call-3",
                        name = "edit_image",
                        argsJson = """{"prompt": "Highlight this text"}"""
                    )
                ),
                usage = Usage(10, 10),
                stopReason = StopReason.ToolUse,
            )
        }

        val result = router.route(
            userText = "isko edit karo",
            attachedPhoto = screenCapturePhoto,
            lightModel = lightModel,
            mainModel = null,
        )

        assertTrue("Screen capture photo must be rejected", result is ImageIntentAction.SecurityRejected)
    }

    @Test
    fun sensitiveAppPhotoRejectedForEdit() = runBlocking {
        val router = ImageIntentRouter()
        val sensitivePhoto = AttachedPhoto(
            base64 = "BASE64_BANK_STATEMENT",
            source = PhotoSource.UserAttachment,
            sourcePackage = "com.phonepe.app",
        )

        val lightModel = fakeChatModel { _, _, _ ->
            ModelReply(
                parts = listOf(
                    Part.ToolCall(
                        id = "call-4",
                        name = "edit_image",
                        argsJson = """{"prompt": "Enhance quality"}"""
                    )
                ),
                usage = Usage(10, 10),
                stopReason = StopReason.ToolUse,
            )
        }

        val result = router.route(
            userText = "photo edit karo",
            attachedPhoto = sensitivePhoto,
            lightModel = lightModel,
            mainModel = null,
            isSensitivePackage = { pkg -> pkg?.contains("phonepe") == true },
        )

        assertTrue("Sensitive app photo must be rejected", result is ImageIntentAction.SecurityRejected)
    }

    @Test
    fun videoBanaoReturnsVideoNotSupportedWithoutToolCall() = runBlocking {
        val router = ImageIntentRouter()
        val toolCalled = AtomicBoolean(false)

        val lightModel = fakeChatModel { _, _, _ ->
            toolCalled.set(true)
            throw AssertionError("Model should NOT be called for video requests!")
        }

        val result = router.route(
            userText = "video banao",
            lightModel = lightModel,
            mainModel = null,
        )

        assertTrue("Result should be VideoNotSupported", result is ImageIntentAction.VideoNotSupported)
        val video = result as ImageIntentAction.VideoNotSupported
        assertEquals("Video abhi support nahi hai", video.message)
        assertTrue("No model/tool should be invoked", !toolCalled.get())
    }

    @Test
    fun fallbackToMainModelWhenLightModelIsNull() = runBlocking {
        val router = ImageIntentRouter()
        val mainCalled = AtomicBoolean(false)

        val mainModel = fakeChatModel("main-qwen") { _, _, _ ->
            mainCalled.set(true)
            ModelReply(
                parts = listOf(
                    Part.ToolCall(
                        id = "call-5",
                        name = "generate_image",
                        argsJson = """{"prompt": "A flying car in cyber city"}"""
                    )
                ),
                usage = Usage(10, 10),
                stopReason = StopReason.ToolUse,
            )
        }

        val result = router.route(
            userText = "flying car ki image banao",
            lightModel = null,
            mainModel = mainModel,
        )

        assertTrue("Main model should be called as fallback", mainCalled.get())
        assertTrue("Result should be Generate", result is ImageIntentAction.Generate)
    }
}
