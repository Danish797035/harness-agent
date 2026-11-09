package com.meenai.mina.capsule.live

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import java.util.Base64

object LiveShareFrameSender {

    private const val SYSTEM_PROMPT =
        "You are an AI assistant observing live screen share to assist with the user's ongoing task."

    suspend fun sendToVisionModel(
        model: ChatModel,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        currentTask: String?,
    ): ModelReply {
        val base64Data = Base64.getEncoder().encodeToString(jpeg)
        val imagePart = Part.Image(
            pngBase64 = base64Data,
            mime = "image/jpeg",
            width = width,
            height = height,
        )

        val promptText = if (!currentTask.isNullOrBlank()) {
            "Current task: $currentTask\nHere is the updated screen."
        } else {
            "Here is the updated screen."
        }
        val textPart = Part.Text(promptText)

        val message = Message(
            role = Role.User,
            parts = listOf(imagePart, textPart),
            author = Author.User,
        )

        return model.complete(SYSTEM_PROMPT, listOf(message), emptyList())
    }
}
