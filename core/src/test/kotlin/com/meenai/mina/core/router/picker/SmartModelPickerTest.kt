package com.meenai.mina.core.router.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartModelPickerTest {

    @Test
    fun binaNaamListKeSabseBadaToolWalaFreeModelMain() {
        val picker = AutoModelPicker(apiClient = { "[]" })

        val giantNoTools = ModelCandidate(
            provider = "custom",
            id = "custom-provider/unlisted-405b-no-tools:free",
            contextTokens = 65536,
            supportsTools = false, // Tools nahi hai -> Main nahi ban sakta
            supportsImages = false,
            isFree = true,
            totalParamsB = 405.0,
        )

        val flagship70b = ModelCandidate(
            provider = "custom",
            id = "custom-provider/unlisted-70b-with-tools:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 70.0,
        )

        val compact8b = ModelCandidate(
            provider = "custom",
            id = "custom-provider/unlisted-8b-with-tools:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 8.0,
        )

        val tiny3b = ModelCandidate(
            provider = "custom",
            id = "custom-provider/unlisted-3b-with-tools:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 3.0,
        )

        val pool = listOf(compact8b, tiny3b, giantNoTools, flagship70b)

        // Bina kisi hardcoded curated name list ke, sabse bada tool-enabled free model Main chuna jana chahiye
        val chosenMain = picker.pickFromCandidates(pool, ModelRole.Main)
        assertNotNull(chosenMain)
        assertEquals("custom-provider/unlisted-70b-with-tools:free", chosenMain!!.id)
    }

    @Test
    fun filterOutImageEditAndGenFromAllRoles() {
        val picker = AutoModelPicker(apiClient = { "[]" })

        val imageEditModel = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen-Image-Edit",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true, // Declared vision, but is an image-edit model
            isFree = true,
        )

        val musePublicEdit = ModelCandidate(
            provider = "modelscope",
            id = "MusePublic/Qwen-Image-Edit",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )

        val fluxModel = ModelCandidate(
            provider = "openrouter",
            id = "black-forest-labs/flux-schnell:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )

        val t2iModel = ModelCandidate(
            provider = "openrouter",
            id = "t2i-adapter-sdxl:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )

        val validChat = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.3-70b-instruct:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 70.0,
        )

        val validVision = ModelCandidate(
            provider = "openrouter",
            id = "google/gemini-2.0-flash-exp:free",
            contextTokens = 1048576,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )

        val candidates = listOf(imageEditModel, musePublicEdit, fluxModel, t2iModel, validChat, validVision)

        // None of the image-edit / image-gen models should pass filtering for ANY role
        for (role in ModelRole.entries) {
            val filtered = picker.filterCandidates(candidates, role)
            assertFalse("imageEditModel must not pass for $role", filtered.contains(imageEditModel))
            assertFalse("musePublicEdit must not pass for $role", filtered.contains(musePublicEdit))
            assertFalse("fluxModel must not pass for $role", filtered.contains(fluxModel))
            assertFalse("t2iModel must not pass for $role", filtered.contains(t2iModel))
        }

        // Vision tag should be stripped from image-edit and image-gen models
        assertFalse(ChatModelFilter.supportsVision("Qwen/Qwen-Image-Edit"))
        assertFalse(ChatModelFilter.supportsVision("MusePublic/Qwen-Image-Edit"))
        assertFalse(ChatModelFilter.supportsVision("black-forest-labs/flux-schnell"))
        assertFalse(ChatModelFilter.supportsVision("t2i-adapter-sdxl"))
        assertTrue(ChatModelFilter.supportsVision("Qwen/Qwen2.5-VL-7B-Instruct"))
        assertTrue(ChatModelFilter.supportsVision("google/gemini-2.0-flash-exp", declaredVision = true))
    }

    @Test
    fun rankedListHeavySeLightTak() {
        val picker = AutoModelPicker(apiClient = { "[]" })

        val m397 = ModelCandidate(
            provider = "openrouter",
            id = "deepseek/deepseek-v3-397b-a17b:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 397.0,
            activeParamsB = 17.0,
        )

        val m70 = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.3-70b-instruct:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 70.0,
        )

        val m32 = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen-2.5-coder-32b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 32.0,
        )

        val m8 = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.1-8b-instruct:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 8.0,
        )

        val m3 = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.2-3b-instruct:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 3.0,
        )

        val pool = listOf(m8, m397, m3, m70, m32)
        val ranked = picker.getFullRankedList(pool)

        assertEquals(5, ranked.size)
        assertEquals(m397.id, ranked[0].id)
        assertEquals(m70.id, ranked[1].id)
        assertEquals(m32.id, ranked[2].id)
        assertEquals(m8.id, ranked[3].id)
        assertEquals(m3.id, ranked[4].id)
    }

    @Test
    fun unknownSizeDoesNotSinkToZero() {
        val unknownBigCtx = ModelCandidate(
            provider = "openrouter",
            id = "google/gemini-2.0-flash-exp:free",
            contextTokens = 1048576,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
            totalParamsB = null, // Unknown size
            supportsReasoning = true,
        )

        val tinyHalfB = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen-2.5-0.5b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 0.5,
        )

        val small1B = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.2-1b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 1.0,
        )

        val pool = listOf(tinyHalfB, unknownBigCtx, small1B)
        val picker = AutoModelPicker(apiClient = { "[]" })
        val ranked = picker.getFullRankedList(pool)

        // The unknown size frontier model with 1M context must rank above 0.5B and 1B models!
        assertEquals(unknownBigCtx.id, ranked[0].id)
    }
}
