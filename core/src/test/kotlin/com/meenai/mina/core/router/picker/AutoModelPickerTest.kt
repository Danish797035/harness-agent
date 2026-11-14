package com.meenai.mina.core.router.picker

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AutoModelPickerTest {

    private val sampleOpenRouterJson = """
        {
          "data": [
            {
              "id": "meta-llama/llama-3.3-70b-instruct:free",
              "name": "Meta: Llama 3.3 70B Instruct (free)",
              "created": 1733443200,
              "context_length": 131072,
              "architecture": {
                "input_modalities": ["text"],
                "modality": "text->text"
              },
              "pricing": {
                "prompt": "0",
                "completion": "0"
              },
              "supported_parameters": ["tools", "temperature"]
            },
            {
              "id": "meta-llama/llama-3.1-8b-instruct:free",
              "name": "Meta: Llama 3.1 8B Instruct (free)",
              "created": 1721692800,
              "context_length": 131072,
              "architecture": {
                "input_modalities": ["text"],
                "modality": "text->text"
              },
              "pricing": {
                "prompt": "0",
                "completion": "0"
              },
              "supported_parameters": ["tools"]
            },
            {
              "id": "meta-llama/llama-3.2-11b-vision-instruct:free",
              "name": "Meta: Llama 3.2 11B Vision Instruct (free)",
              "created": 1727222400,
              "context_length": 131072,
              "architecture": {
                "input_modalities": ["text", "image"],
                "modality": "text+image->text"
              },
              "pricing": {
                "prompt": "0",
                "completion": "0"
              },
              "supported_parameters": ["tools"]
            },
            {
              "id": "google/gemini-2.0-flash-exp:free",
              "name": "Google: Gemini 2.0 Flash Exp (free)",
              "created": 1734000000,
              "context_length": 1048576,
              "architecture": {
                "input_modalities": ["text", "image"],
                "modality": "text+image->text"
              },
              "pricing": {
                "prompt": "0",
                "completion": "0"
              },
              "supported_parameters": ["tools"]
            },
            {
              "id": "paid-org/expensive-model",
              "name": "Paid Model",
              "created": 1730000000,
              "context_length": 131072,
              "pricing": {
                "prompt": "0.005",
                "completion": "0.015"
              },
              "supported_parameters": ["tools"]
            },
            {
              "id": "no-tools/model:free",
              "name": "No Tools Free Model",
              "created": 1730000000,
              "context_length": 65536,
              "pricing": {
                "prompt": "0",
                "completion": "0"
              },
              "supported_parameters": ["temperature"]
            },
            {
              "id": "small-ctx/model:free",
              "name": "Small Context Free Model",
              "created": 1730000000,
              "context_length": 8192,
              "pricing": {
                "prompt": "0",
                "completion": "0"
              },
              "supported_parameters": ["tools"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun filterFreeToolsAndContext() {
        val parser = OpenRouterModelParser()
        val all = parser.parse(sampleOpenRouterJson)
        val picker = AutoModelPicker(apiClient = { sampleOpenRouterJson })

        // 1. Paid models should be filtered out
        val paid = all.find { it.id == "paid-org/expensive-model" }
        assertNotNull(paid)
        assertFalse(paid!!.isFree)
        assertFalse(picker.filterCandidates(listOf(paid), ModelRole.Main).contains(paid))

        // 2. Models without tools should be filtered out
        val noTools = all.find { it.id == "no-tools/model:free" }
        assertNotNull(noTools)
        assertFalse(noTools!!.supportsTools)
        assertFalse(picker.filterCandidates(listOf(noTools), ModelRole.Main).contains(noTools))

        // 3. Models with context < 32k should be filtered out
        val smallCtx = all.find { it.id == "small-ctx/model:free" }
        assertNotNull(smallCtx)
        assertTrue(smallCtx!!.contextTokens < 32_000)
        assertFalse(picker.filterCandidates(listOf(smallCtx), ModelRole.Main).contains(smallCtx))

        // 4. Free with tools and >= 32k context should pass for Main role
        val llama70b = all.find { it.id == "meta-llama/llama-3.3-70b-instruct:free" }
        assertNotNull(llama70b)
        assertTrue(llama70b!!.isFree)
        assertTrue(llama70b.supportsTools)
        assertTrue(llama70b.contextTokens >= 32_000)
        assertTrue(picker.filterCandidates(listOf(llama70b), ModelRole.Main).contains(llama70b))

        // 5. Vision role requires image support
        val visionPassed = picker.filterCandidates(all, ModelRole.Vision)
        assertTrue(visionPassed.all { it.supportsImages })
        assertFalse(visionPassed.any { it.id == "meta-llama/llama-3.3-70b-instruct:free" }) // text only
        assertTrue(visionPassed.any { it.id == "meta-llama/llama-3.2-11b-vision-instruct:free" }) // vision
        assertTrue(visionPassed.any { it.id == "google/gemini-2.0-flash-exp:free" }) // vision
    }

    @Test
    fun rankingOrderCuratedAndRoles() {
        val parser = OpenRouterModelParser()
        val candidates = parser.parse(sampleOpenRouterJson)
        val picker = AutoModelPicker(apiClient = { sampleOpenRouterJson })

        // Main role picks the heaviest / top frontier model (sabse heavy)
        val mainModel = picker.pickFromCandidates(candidates, ModelRole.Main)
        assertNotNull(mainModel)
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", mainModel!!.id)

        // Light role picks the fast / lightweight model (tez)
        val lightModel = picker.pickFromCandidates(candidates, ModelRole.Light)
        assertNotNull(lightModel)
        assertEquals("meta-llama/llama-3.1-8b-instruct:free", lightModel!!.id)

        // Vision role picks the best image model
        val visionModel = picker.pickFromCandidates(candidates, ModelRole.Vision)
        assertNotNull(visionModel)
        assertTrue(visionModel!!.supportsImages)
        assertEquals("google/gemini-2.0-flash-exp:free", visionModel.id)
    }

    @Test
    fun rankingOrderHeuristicFallbackForNonCurated() {
        val picker = AutoModelPicker(apiClient = { "" })

        // 1. Bada model test: 70B beats 8B when neither is curated
        val uncurated70b = ModelCandidate(
            provider = "custom",
            id = "custom-frontier/unlisted-70b-model",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = Int.MAX_VALUE,
            releaseDateEpoch = 1700000000L,
            parameterSizeB = 70.0,
        )
        val uncurated8b = ModelCandidate(
            provider = "custom",
            id = "custom-frontier/unlisted-8b-model",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = Int.MAX_VALUE,
            releaseDateEpoch = 1700000000L,
            parameterSizeB = 8.0,
        )

        val rankedBySize = picker.rankCandidates(listOf(uncurated8b, uncurated70b), ModelRole.Main)
        assertEquals(uncurated70b.id, rankedBySize.first().id)

        // 2. Bada context test: 128k context beats 32k context when parameter sizes match
        val uncuratedLargeCtx = uncurated70b.copy(id = "model-large-ctx", contextTokens = 131072)
        val uncuratedSmallCtx = uncurated70b.copy(id = "model-small-ctx", contextTokens = 32768)

        val rankedByContext = picker.rankCandidates(listOf(uncuratedSmallCtx, uncuratedLargeCtx), ModelRole.Main)
        assertEquals("model-large-ctx", rankedByContext.first().id)

        // 3. Naya release test: newer release date beats older when size and context match
        val uncuratedNewer = uncurated70b.copy(id = "model-newer", releaseDateEpoch = 1735000000L)
        val uncuratedOlder = uncurated70b.copy(id = "model-older", releaseDateEpoch = 1700000000L)

        val rankedByDate = picker.rankCandidates(listOf(uncuratedOlder, uncuratedNewer), ModelRole.Main)
        assertEquals("model-newer", rankedByDate.first().id)
    }

    @Test
    fun pinRespectDoesNotChangeModel() {
        val parser = OpenRouterModelParser()
        val candidates = parser.parse(sampleOpenRouterJson)
        val picker = AutoModelPicker(apiClient = { sampleOpenRouterJson })

        // When pinned to an 8B model, Main role MUST return the pinned 8B model, not 70B or Gemini
        val pinned = picker.pickFromCandidates(
            candidates = candidates,
            role = ModelRole.Main,
            pinnedModelId = "meta-llama/llama-3.1-8b-instruct:free",
        )
        assertNotNull(pinned)
        assertEquals("meta-llama/llama-3.1-8b-instruct:free", pinned!!.id)

        // When pinned to a custom model not in the list, it still respects the pin
        val customPinned = picker.pickFromCandidates(
            candidates = candidates,
            role = ModelRole.Vision,
            pinnedModelId = "my-special-private-model",
        )
        assertNotNull(customPinned)
        assertEquals("my-special-private-model", customPinned!!.id)
    }

    @Test
    fun cacheOnceADayAndOfflineFallback() = runTest {
        var networkCalls = 0
        var shouldThrowNetworkError = false

        val fakeClient = OpenRouterApiClient {
            if (shouldThrowNetworkError) {
                throw IOException("Network unreachable (offline)")
            }
            networkCalls++
            sampleOpenRouterJson
        }

        var simulatedTimeMs = 1_000_000L
        val clock = Clock { simulatedTimeMs }
        val cacheStore = InMemoryModelCacheStore()

        val picker = AutoModelPicker(
            apiClient = fakeClient,
            cacheStore = cacheStore,
            clock = clock,
        )

        // 1. First fetch: hits network and populates cache
        val firstFetch = picker.getCandidates()
        assertEquals(1, networkCalls)
        assertTrue(firstFetch.isNotEmpty())

        // 2. Fetch within 24 hours: uses cache, no network call
        simulatedTimeMs += 10 * 60 * 60 * 1000L // +10 hours
        val secondFetch = picker.getCandidates()
        assertEquals(1, networkCalls) // still 1!
        assertEquals(firstFetch.size, secondFetch.size)

        // 3. Cache expired (> 24 hours) but network is offline: falls back to cached data!
        simulatedTimeMs += 15 * 60 * 60 * 1000L // now +25 hours total
        shouldThrowNetworkError = true

        val offlineFetch = picker.getCandidates()
        assertTrue(offlineFetch.isNotEmpty())
        assertEquals(firstFetch.size, offlineFetch.size)
        assertEquals(firstFetch.map { it.id }, offlineFetch.map { it.id })

        // 4. Network restored after 24h: refreshes cache
        shouldThrowNetworkError = false
        val onlineFetch = picker.getCandidates()
        assertEquals(2, networkCalls) // network called again
        assertTrue(onlineFetch.isNotEmpty())
    }

    @Test
    fun healthScoreHookDemotesUnhealthyModel() {
        val parser = OpenRouterModelParser()
        val candidates = parser.parse(sampleOpenRouterJson)

        // Normally, meta-llama/llama-3.3-70b-instruct:free is #1 for Main role (sabse heavy)
        val healthyPicker = AutoModelPicker(
            apiClient = { sampleOpenRouterJson },
            healthSource = HealthSource.DEFAULT,
        )
        val defaultPick = healthyPicker.pickFromCandidates(candidates, ModelRole.Main)
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", defaultPick!!.id)

        // If llama-3.3-70b has health score 0.0 (unhealthy / down), picker falls back to next healthy model
        val customHealth = HealthSource { modelId ->
            if (modelId == "meta-llama/llama-3.3-70b-instruct:free") 0.0 else 1.0
        }
        val degradedPick = healthyPicker.pickFromCandidates(
            candidates = candidates,
            role = ModelRole.Main,
            activeHealthSource = customHealth,
        )
        assertNotNull(degradedPick)
        assertEquals("google/gemini-2.0-flash-exp:free", degradedPick!!.id)
    }

    @Test
    fun sub3bTinyModelsFilteredOut() {
        val tinyLfm = ModelCandidate(
            provider = "openrouter",
            id = "liquid/lfm-3-1.7b:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = Int.MAX_VALUE,
            parameterSizeB = 1.7,
            totalParamsB = 1.7,
        )
        val tiny05b = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen-2.5-0.5b-instruct:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = Int.MAX_VALUE,
            parameterSizeB = 0.5,
            totalParamsB = 0.5,
        )
        val valid8b = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.1-8b-instruct:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = 17,
            parameterSizeB = 8.0,
            totalParamsB = 8.0,
        )
        val picker = AutoModelPicker(apiClient = { "" })
        val filtered = picker.filterCandidates(listOf(tinyLfm, tiny05b, valid8b), ModelRole.Light)
        assertEquals(listOf(valid8b), filtered)
        assertFalse(AutoModelPicker.isLightModel(tinyLfm))
        assertFalse(AutoModelPicker.isLightModel(tiny05b))
        assertTrue(AutoModelPicker.isLightModel(valid8b))
    }
}
