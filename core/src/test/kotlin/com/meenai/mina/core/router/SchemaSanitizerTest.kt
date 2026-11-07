package com.meenai.mina.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaSanitizerTest {

    @Test
    fun stripsForbiddenKeywordsForGemini() {
        val input = """
            {
                "${'$'}schema": "http://json-schema.org/draft-07/schema#",
                "${'$'}id": "https://example.com/tool.schema.json",
                "title": "MyTool",
                "type": "object",
                "additionalProperties": false,
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "search query",
                        "default": "hello"
                    },
                    "additionalProperties": {
                        "type": "boolean",
                        "description": "should not remove property name even if it matches forbidden keyword"
                    }
                },
                "required": ["query"]
            }
        """.trimIndent()

        val sanitized = SchemaSanitizer.sanitizeForGemini(input)
        assertFalse(sanitized.contains("${'$'}schema"))
        assertFalse(sanitized.contains("${'$'}id"))
        assertFalse(sanitized.contains("\"title\""))
        assertFalse(sanitized.contains("\"default\""))
        assertFalse(sanitized.contains("\"additionalProperties\":false"))
        assertFalse(sanitized.contains("\"additionalProperties\": false"))
        // Property named additionalProperties inside properties should still be kept
        assertTrue(sanitized.contains("\"additionalProperties\":{") || sanitized.contains("\"additionalProperties\": {"))
        assertTrue(sanitized.contains("\"query\""))
    }

    @Test
    fun simplifiesAnyOfNullable() {
        val input = """
            {
                "type": "object",
                "properties": {
                    "tag": {
                        "anyOf": [
                            {"type": "string"},
                            {"type": "null"}
                        ]
                    }
                }
            }
        """.trimIndent()

        val sanitized = SchemaSanitizer.sanitizeForGemini(input)
        assertFalse(sanitized.contains("anyOf"))
        assertTrue(sanitized.contains("\"type\":\"string\"") || sanitized.contains("\"type\": \"string\""))
        assertTrue(sanitized.contains("\"nullable\":true") || sanitized.contains("\"nullable\": true"))
    }

    @Test
    fun simplifiesSingleItemAnyOf() {
        val input = """
            {
                "type": "object",
                "properties": {
                    "count": {
                        "anyOf": [
                            {"type": "integer"}
                        ]
                    }
                }
            }
        """.trimIndent()

        val sanitized = SchemaSanitizer.sanitizeForGemini(input)
        assertFalse(sanitized.contains("anyOf"))
        assertTrue(sanitized.contains("\"type\":\"integer\"") || sanitized.contains("\"type\": \"integer\""))
    }

    @Test
    fun stripsDefinitionsAndDefs() {
        val input = """
            {
                "type": "object",
                "${'$'}defs": {
                    "Sub": {"type": "string"}
                },
                "definitions": {
                    "OldSub": {"type": "integer"}
                },
                "properties": {
                    "name": {"type": "string"}
                }
            }
        """.trimIndent()

        val sanitized = SchemaSanitizer.sanitizeForGemini(input)
        assertFalse(sanitized.contains("${'$'}defs"))
        assertFalse(sanitized.contains("definitions"))
        assertTrue(sanitized.contains("\"name\""))
    }
}
