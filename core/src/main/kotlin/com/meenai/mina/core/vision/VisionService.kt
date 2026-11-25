package com.meenai.mina.core.vision

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role

/**
 * Let the visual model look at the picture and answer a question. It is only built when there is a model
 * that can view images in the settings; the caller is responsible for not passing in the images of
 * protected pages and payment pages.
 */
class VisionService(private val model: ChatModel, val ops: ImageOps) {
    /** The positioning accuracy of this model has been verified; if it has not been verified, find_visually has additional supporting evidence for the points that can only be obtained by looking at the picture frame. */
    val groundingVerified: Boolean get() = model.capabilities.groundingVerified

    /** A call without tools; failure throws [ModelError]. */
    suspend fun ask(system: String, text: String, images: List<ByteArray>): String {
        val parts = listOf(Part.Text(text)) + images.map { ModelImage.part(it, ops) }
        val reply = model.complete(system, listOf(Message(Role.User, parts, Author.Mina)), emptyList())
        val answer = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        if (answer.isBlank()) throw ModelError.BadResponse("Vision model returned an empty response")
        return answer
    }
}
