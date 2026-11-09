package com.meenai.mina.core.image

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.vision.ImageOps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class PhotoSource {
    UserAttachment,
    ScreenCapture,
}

data class AttachedPhoto(
    val base64: String,
    val source: PhotoSource,
    val sourcePackage: String? = null,
)

sealed class ImageIntentAction {
    data class Generate(val prompt: String, val size: String? = null) : ImageIntentAction()
    data class Edit(val prompt: String, val imageBase64: String) : ImageIntentAction()
    data class VideoNotSupported(val message: String = "Video abhi support nahi hai") : ImageIntentAction()
    data class None(val reply: String) : ImageIntentAction()
    data class SecurityRejected(val reason: String) : ImageIntentAction()
}

object ImageTools {
    const val GENERATE_IMAGE_NAME = "generate_image"
    const val EDIT_IMAGE_NAME = "edit_image"

    val GENERATE_IMAGE_SPEC = ToolSpec(
        name = GENERATE_IMAGE_NAME,
        description = "Generate a new image from a text description.",
        parametersJsonSchema = """{
            "type": "object",
            "properties": {
                "prompt": { "type": "string", "description": "Detailed text description of the image to generate" },
                "size": { "type": "string", "description": "Optional image dimensions, e.g. 1024*1024" }
            },
            "required": ["prompt"],
            "additionalProperties": false
        }""".trimIndent()
    )

    val EDIT_IMAGE_SPEC = ToolSpec(
        name = EDIT_IMAGE_NAME,
        description = "Edit an existing user-attached image.",
        parametersJsonSchema = """{
            "type": "object",
            "properties": {
                "prompt": { "type": "string", "description": "Detailed instructions on edits/changes to apply to the image" },
                "image": { "type": "string", "description": "Optional base64 image or reference identifier" }
            },
            "required": ["prompt"],
            "additionalProperties": false
        }""".trimIndent()
    )

    val ALL_SPECS = listOf(GENERATE_IMAGE_SPEC, EDIT_IMAGE_SPEC)

    const val ROUTER_SYSTEM_PROMPT = """
You are an AI assistant that handles visual media requests.
1. If the user asks to generate, create, draw, or make a new image or photo, call `generate_image` with a descriptive English prompt and optional size (e.g. 1024*1024).
2. If the user has provided a photo and asks to edit, alter, or modify it, call `edit_image` with the requested edits in `prompt`.
3. If the user asks for a video or animation (e.g. "video banao", "make video"), do NOT call any tool; simply say "Video abhi support nahi hai".
4. You do not generate raw images yourself, only call the appropriate tool.
"""
}

class ImageIntentRouter(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    companion object {
        const val VIDEO_NOT_SUPPORTED_MSG = "Video abhi support nahi hai"

        fun isVideoRequest(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("video banao") ||
                   lower.contains("create video") ||
                   lower.contains("make video") ||
                   lower.contains("generate video") ||
                   (lower.contains("video") && (lower.contains("banao") || lower.contains("banaye") || lower.contains("chahiye") || lower.contains("generate") || lower.contains("create") || lower.contains("make")))
        }
    }

    suspend fun route(
        userText: String,
        attachedPhoto: AttachedPhoto? = null,
        lightModel: ChatModel?,
        mainModel: ChatModel?,
        isSensitivePackage: (String?) -> Boolean = { false },
        imageOps: ImageOps? = null,
    ): ImageIntentAction {
        // 1. Direct video rejection check
        if (isVideoRequest(userText)) {
            return ImageIntentAction.VideoNotSupported(VIDEO_NOT_SUPPORTED_MSG)
        }

        // 2. Select model: Light model if set, else fallback to Main model
        val model = lightModel ?: mainModel
            ?: return ImageIntentAction.None("Koi AI model set nahi hai")

        // 3. Prepare user message
        val promptContent = buildString {
            append(userText)
            if (attachedPhoto != null) {
                append("\n[User attached an image/photo]")
            }
        }
        val userMsg = Message(Role.User, listOf(Part.Text(promptContent)), Author.User)

        // 4. Call model with image tool specs
        val reply = try {
            model.complete(
                system = ImageTools.ROUTER_SYSTEM_PROMPT,
                messages = listOf(userMsg),
                tools = ImageTools.ALL_SPECS,
            )
        } catch (t: Throwable) {
            return ImageIntentAction.None("Model error: ${t.message}")
        }

        // 5. Check if model replied about video
        val textReply = reply.parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }
        if (isVideoRequest(userText) || isVideoRequest(textReply) || textReply.contains("Video abhi support nahi hai")) {
            return ImageIntentAction.VideoNotSupported(VIDEO_NOT_SUPPORTED_MSG)
        }

        // 6. Inspect tool calls
        val toolCall = reply.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
        if (toolCall == null) {
            return ImageIntentAction.None(textReply)
        }

        return when (toolCall.name) {
            ImageTools.GENERATE_IMAGE_NAME -> {
                val args = parseArgs(toolCall.argsJson)
                val prompt = args["prompt"]?.jsonPrimitive?.content.orEmpty()
                val size = args["size"]?.jsonPrimitive?.content
                ImageIntentAction.Generate(prompt.ifEmpty { userText }, size)
            }
            ImageTools.EDIT_IMAGE_NAME -> {
                val args = parseArgs(toolCall.argsJson)
                val prompt = args["prompt"]?.jsonPrimitive?.content.orEmpty()

                // Security check for edit photo:
                // Rule 6: Edit ke liye photo sirf jo user khud attach kare.
                // Screen capture ya blocked/sensitive app ki photo kabhi edit model ko na jaye.
                if (attachedPhoto == null) {
                    return ImageIntentAction.None("Edit ke liye photo attach karein.")
                }

                if (attachedPhoto.source != PhotoSource.UserAttachment) {
                    return ImageIntentAction.SecurityRejected("Screen capture edit ke liye allow nahi hai.")
                }

                if (isSensitivePackage(attachedPhoto.sourcePackage)) {
                    return ImageIntentAction.SecurityRejected("Sensitive ya blocked app ki photo edit ke liye allow nahi hai.")
                }

                // Photo 2048 px tak chhota karke base64
                val finalBase64 = resizePhotoIfNeeded(attachedPhoto.base64, imageOps)
                ImageIntentAction.Edit(prompt.ifEmpty { userText }, finalBase64)
            }
            else -> ImageIntentAction.None(textReply)
        }
    }

    private fun resizePhotoIfNeeded(base64: String, imageOps: ImageOps?): String {
        if (imageOps == null) return base64
        return try {
            val bytes = java.util.Base64.getDecoder().decode(base64.removePrefix("data:image/jpeg;base64,").removePrefix("data:image/png;base64,"))
            val (width, height) = imageOps.size(bytes)
            if (width > 2048 || height > 2048) {
                val compressed = imageOps.toJpeg(bytes, maxSide = 2048, quality = 85)
                java.util.Base64.getEncoder().encodeToString(compressed)
            } else {
                base64
            }
        } catch (_: Throwable) {
            base64
        }
    }

    private fun parseArgs(argumentsJson: String): Map<String, kotlinx.serialization.json.JsonElement> {
        return try {
            json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Throwable) {
            emptyMap()
        }
    }
}
