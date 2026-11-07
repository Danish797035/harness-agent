package com.meenai.mina.core.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * Sanitizes JSON schemas for LLM tool declarations.
 * Specifically handles provider restrictions such as Gemini rejecting
 * additionalProperties, $schema, $id, $defs, and complex anyOf constructs.
 */
object SchemaSanitizer {
    private val json = Json { ignoreUnknownKeys = true }

    private val GEMINI_FORBIDDEN_KEYWORDS = setOf(
        "additionalProperties",
        "\$schema",
        "\$id",
        "\$defs",
        "definitions",
        "title",
        "default",
    )

    /**
     * Sanitizes a JSON schema string for Gemini's OpenAPI-compatible subset.
     */
    fun sanitizeForGemini(schemaJson: String): String {
        return try {
            val element = json.parseToJsonElement(schemaJson)
            sanitizeElement(element, GEMINI_FORBIDDEN_KEYWORDS).toString()
        } catch (_: Exception) {
            schemaJson
        }
    }

    private val GENERAL_FORBIDDEN_KEYWORDS = setOf(
        "additionalProperties",
        "\$schema",
        "\$id",
        "\$defs",
        "definitions",
    )

    /**
     * General sanitization for retrying on 400 schema errors across any provider.
     */
    fun sanitizeGeneral(schemaJson: String): String {
        return try {
            val element = json.parseToJsonElement(schemaJson)
            sanitizeElement(element, GENERAL_FORBIDDEN_KEYWORDS).toString()
        } catch (_: Exception) {
            schemaJson
        }
    }

    fun sanitizeElement(element: JsonElement, forbidden: Set<String>): JsonElement {
        return when (element) {
            is JsonObject -> sanitizeObject(element, forbidden)
            is JsonArray -> buildJsonArray { element.forEach { add(sanitizeElement(it, forbidden)) } }
            else -> element
        }
    }

    private fun sanitizeObject(obj: JsonObject, forbidden: Set<String>): JsonObject {
        // Resolve anyOf / oneOf if present
        val anyOf = (obj["anyOf"] as? JsonArray) ?: (obj["oneOf"] as? JsonArray)
        val workingObj = if (anyOf != null) {
            val simplified = simplifyAnyOf(anyOf)
            if (simplified != null) {
                val base = obj.filterKeys { it != "anyOf" && it != "oneOf" && it !in forbidden }
                JsonObject(base + simplified)
            } else obj
        } else obj

        val result = mutableMapOf<String, JsonElement>()
        for ((key, value) in workingObj) {
            if (key in forbidden) continue
            when {
                key == "properties" && value is JsonObject -> {
                    val sanitizedProps = mutableMapOf<String, JsonElement>()
                    for ((propName, propSchema) in value) {
                        sanitizedProps[propName] = sanitizeElement(propSchema, forbidden)
                    }
                    result[key] = JsonObject(sanitizedProps)
                }
                key == "items" -> {
                    result[key] = sanitizeElement(value, forbidden)
                }
                else -> {
                    result[key] = sanitizeElement(value, forbidden)
                }
            }
        }
        return JsonObject(result)
    }

    private fun simplifyAnyOf(anyOf: JsonArray): Map<String, JsonElement>? {
        if (anyOf.isEmpty()) return null
        val items = anyOf.mapNotNull { it as? JsonObject }
        if (items.isEmpty()) return null

        if (items.size == 1) {
            return items.first()
        }

        // Check for [{"type": "string"}, {"type": "null"}] nullable pattern
        val nullItem = items.find { it["type"]?.let { t -> (t as? JsonPrimitive)?.contentOrNull == "null" } == true }
        val nonNullItems = items.filter { it["type"]?.let { t -> (t as? JsonPrimitive)?.contentOrNull != "null" } == true }

        if (nullItem != null && nonNullItems.size == 1) {
            val target = nonNullItems.first().toMutableMap()
            target["nullable"] = JsonPrimitive(true)
            return target
        }

        if (nonNullItems.isNotEmpty()) {
            return nonNullItems.first()
        }

        return items.first()
    }
}
