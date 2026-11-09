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

    @Test
    fun qwenImageAndImage21ExcludedFromAllRoles() {
        val picker = AutoModelPicker(apiClient = { "[]" })

        val qwenImage = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen-Image",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )

        val qwenImage21 = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen-Image-2.1",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )

        val qwenVL = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen2.5-VL-72B-Instruct",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
            totalParamsB = 72.0,
        )

        val candidates = listOf(qwenImage, qwenImage21, qwenVL)

        // Qwen-Image and Qwen-Image-2.1 must be excluded from all roles
        for (role in ModelRole.entries) {
            val filtered = picker.filterCandidates(candidates, role)
            assertFalse("Qwen-Image must not pass for $role", filtered.contains(qwenImage))
            assertFalse("Qwen-Image-2.1 must not pass for $role", filtered.contains(qwenImage21))
        }

        // Qwen2.5-VL must NOT be excluded
        val visionFiltered = picker.filterCandidates(candidates, ModelRole.Vision)
        assertTrue("Qwen2.5-VL must pass for Vision role", visionFiltered.contains(qwenVL))

        // Vision tag checks
        assertFalse(ChatModelFilter.supportsVision("Qwen/Qwen-Image"))
        assertFalse(ChatModelFilter.supportsVision("Qwen/Qwen-Image-2.1"))
        assertFalse(ChatModelFilter.supportsVision("Qwen-Image"))
        assertTrue(ChatModelFilter.supportsVision("Qwen2.5-VL"))
        assertTrue(ChatModelFilter.supportsVision("Qwen/Qwen2.5-VL-72B-Instruct"))
    }

    @Test
    fun supportsVision_strictTokenMatchingForVl() {
        // Must match on token boundaries
        assertTrue(ChatModelFilter.supportsVision("Qwen2.5-VL"))
        assertTrue(ChatModelFilter.supportsVision("Qwen/Qwen2.5-VL-72B-Instruct"))
        assertTrue(ChatModelFilter.supportsVision("qwen_vl_chat"))
        assertTrue(ChatModelFilter.supportsVision("vl-adapter"))
        assertTrue(ChatModelFilter.supportsVision("meta-llama/llama-3.2-11b-vision-instruct"))

        // InternVL and GLM-v vision models must be recognized
        assertTrue(ChatModelFilter.supportsVision("OpenGVLab/InternVL3.5-241B-A28B"))
        assertTrue(ChatModelFilter.supportsVision("OpenGVLab/InternVL3_5-241B-A28B"))
        assertTrue(ChatModelFilter.supportsVision("zai-org/glm-4.5v"))
        assertTrue(ChatModelFilter.supportsVision("glm-4v"))

        // Must NOT falsely match loose substring "vl" in regular words
        assertFalse(ChatModelFilter.supportsVision("evolve-70b"))
        assertFalse(ChatModelFilter.supportsVision("silver-14b"))
        assertFalse(ChatModelFilter.supportsVision("development-model"))
        assertFalse(ChatModelFilter.supportsVision("overlap-32b"))
    }

    @Test
    fun emptyRankings_picksMainLightVisionCodingLongSuccessfully() {
        // When model-rankings.json has "models": [], picker still picks all roles using live data
        val emptyRepo = ModelRankingsRepository(
            cacheStore = InMemoryModelRankingsCacheStore(),
            bundledResourceSupplier = {
                """{"updatedAt":"2026-11-08T00:00:00Z","version":1,"sources":[],"models":[]}"""
            }
        )

        val heavyMain = ModelCandidate(
            provider = "openrouter",
            id = "deepseek/deepseek-v3:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 671.0,
        )

        val codingModel = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen-2.5-coder-32b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 32.0,
        )

        val lightFast = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.1-8b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 8.0,
        )

        val visionModel = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen2.5-vl-72b-instruct:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
            totalParamsB = 72.0,
        )

        val longContextModel = ModelCandidate(
            provider = "openrouter",
            id = "google/gemini-2.0-flash-exp:free",
            contextTokens = 1048576,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 20.0,
        )

        val pool = listOf(heavyMain, codingModel, lightFast, visionModel, longContextModel)
        val picker = AutoModelPicker(apiClient = { "[]" }, rankingsRepository = emptyRepo)

        val pickedMain = picker.pickFromCandidates(pool, ModelRole.Main)
        assertNotNull(pickedMain)
        assertEquals("deepseek/deepseek-v3:free", pickedMain?.id)

        val pickedCoding = picker.pickFromCandidates(pool, ModelRole.Coding)
        assertNotNull(pickedCoding)
        assertEquals("qwen/qwen-2.5-coder-32b-instruct:free", pickedCoding?.id)

        val pickedLight = picker.pickFromCandidates(pool, ModelRole.Light)
        assertNotNull(pickedLight)
        assertEquals("meta-llama/llama-3.1-8b-instruct:free", pickedLight?.id)

        val pickedVision = picker.pickFromCandidates(pool, ModelRole.Vision)
        assertNotNull(pickedVision)
        assertEquals("qwen/qwen2.5-vl-72b-instruct:free", pickedVision?.id)

        val pickedLong = picker.pickFromCandidates(pool, ModelRole.Long)
        assertNotNull(pickedLong)
        assertEquals("google/gemini-2.0-flash-exp:free", pickedLong?.id)
    }
}
