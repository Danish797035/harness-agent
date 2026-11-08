package com.meenai.mina.capsule.circle

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import java.util.Base64

object CircleToAskSender {

    private const val SYSTEM_PROMPT =
        "You are an AI assistant helping the user understand a circled or highlighted area of their screen. Provide a clear, helpful, and concise answer."

    suspend fun askAboutCrop(
        model: ChatModel,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        question: String,
    ): ModelReply {
        val base64Data = Base64.getEncoder().encodeToString(jpeg)
        val imagePart = Part.Image(
            pngBase64 = base64Data,
            mime = "image/jpeg",
            width = width,
            height = height,
        )

        val promptText = if (question.isNotBlank()) question else "What is in this circled area?"
        val textPart = Part.Text(promptText)

        val message = Message(
            role = Role.User,
            parts = listOf(imagePart, textPart),
            author = Author.User,
        )

        return model.complete(SYSTEM_PROMPT, listOf(message), emptyList())
    }

    fun textOf(reply: ModelReply): String =
        reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
}
