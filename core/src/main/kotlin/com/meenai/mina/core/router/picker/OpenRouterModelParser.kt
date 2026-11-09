package com.meenai.mina.core.router.picker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Parser for OpenRouter `/api/v1/models` JSON responses.
 */
class OpenRouterModelParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Parses the response body from `/api/v1/models` into a list of [ModelCandidate].
     */
    fun parse(jsonString: String): List<ModelCandidate> {
        val root = try {
            json.parseToJsonElement(jsonString).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val data = (root["data"] ?: root["models"]) as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseModel(obj)
        }
    }

    private fun parseModel(obj: JsonObject): ModelCandidate? {
        val id = (obj.str("id") ?: obj.str("slug"))?.takeIf { it.isNotBlank() } ?: return null
        val name = (obj.str("name") ?: obj.str("display_name")).orEmpty()
        val description = obj.str("description").orEmpty()

        // Filter out non-chat models, image-gen, and image-edit models
        if (ChatModelFilter.isExcluded(id, name)) {
            return null
        }

        val contextTokens = obj.int("context_length")
            ?: obj.obj("top_provider")?.int("context_length")
            ?: obj.int("context_window")
            ?: obj.int("max_context_length")
            ?: obj.obj("limit")?.int("context")
            ?: 0

        val maxOutputTokens = obj.obj("top_provider")?.int("max_completion_tokens")
            ?: obj.obj("per_request_limits")?.int("max_completion_tokens")
            ?: obj.int("max_output")

        val supportedParams = (obj["supported_parameters"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        val caps = obj.obj("capabilities")
        val supportsTools = supportedParams?.contains("tools") == true ||
            caps?.obj("tools")?.bool("supported") == true ||
            caps?.bool("function_calling") == true ||
            caps?.bool("tool_calls") == true ||
            obj.bool("tools") == true

        val arch = obj.obj("architecture")
        val inputModalities = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        val modality = arch?.str("modality")
        val modalIn = ((obj.obj("modalities")?.get("input") ?: obj["input_modalities"]) as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }

        val rawImages = inputModalities?.any { it == "image" || it.startsWith("image/") } == true ||
            modalIn?.any { it == "image" || it.startsWith("image/") } == true ||
            modality?.contains("image") == true ||
            caps?.bool("vision") == true ||
            caps?.obj("image_input")?.bool("supported") == true ||
            obj.bool("supports_image_in") == true ||
            id.contains("vision", ignoreCase = true)

        val supportsImages = ChatModelFilter.supportsVision(id, rawImages)

        val supportsReasoning = supportedParams?.contains("reasoning") == true ||
            caps?.bool("reasoning") == true ||
            id.contains("reasoner", ignoreCase = true) ||
            id.contains("-r1", ignoreCase = true) ||
            id.contains("thinking", ignoreCase = true)

        val pricing = obj.obj("pricing")
        val promptPrice = pricing?.double("prompt")
        val completionPrice = pricing?.double("completion")
        val isFreePricing = promptPrice != null && completionPrice != null && promptPrice == 0.0 && completionPrice == 0.0
        val isFree = id.endsWith(":free") || isFreePricing

        val releaseDateEpoch = obj.long("created")
            ?: obj.long("created_at")
            ?: 0L

        val size = ModelSizeParser.parse(id, name, description)

        return ModelCandidate(
            provider = "openrouter",
            id = id,
            contextTokens = contextTokens,
            supportsTools = supportsTools,
            supportsImages = supportsImages,
            isFree = isFree,
            tierRank = Int.MAX_VALUE,
            releaseDateEpoch = releaseDateEpoch,
            parameterSizeB = size.totalParamsB ?: 0.0,
            totalParamsB = size.totalParamsB,
            activeParamsB = size.activeParamsB,
            maxOutputTokens = maxOutputTokens,
            supportsReasoning = supportsReasoning,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
    private fun JsonObject.double(k: String): Double? {
        val prim = this[k] as? JsonPrimitive ?: return null
        return prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull()
    }
}
