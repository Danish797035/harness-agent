package com.meenai.mina.core.router.picker

import com.meenai.mina.core.router.preset.HttpModelScopeApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests running AutoModelPicker on real OpenRouter and ModelScope fixtures (captured 2026-11-08).
 *
 * Verifies:
 * - Main role picks nvidia/nemotron-3-ultra-550b-a55b:free (largest parameter capacity, tools, 1M context)
 * - Vision role picks dots-studio/dots-3-note-preview:free or nvidia/nemotron-3.5-super-vl:free
 * - Light role picks a small free model with tools (liquid/lfm-*), NOT Main
 * - Coding role picks cohere/north-mini-code:free
 * - Excluded models (openrouter/free, google/lyria-*, Qwen-Image, Qwen-Image-Edit) never appear in any role
 * - Size-less models have size unknown (null), NOT 0
 * - In ModelScope fixture, Qwen/Qwen3.5-397B-A17B is top-ranked
 * - OpenGVLab/InternVL3.5-241B-A28B and glm-4.5v recognized as vision models; evolve-70b, silver-14b rejected
 */
class RealFixturesPickerTest {

    private fun loadResource(path: String): String {
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing test resource fixture: $path"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun openRouterFixture_picksExpectedRolesFromReal2026Data() {
        val json = loadResource("/fixtures/openrouter-models-2026-11-08.json")
        val parser = OpenRouterModelParser()
        val candidates = parser.parse(json)
        assertTrue("Fixture must yield parsed models", candidates.isNotEmpty())

        val picker = AutoModelPicker(apiClient = { json })

        // 1. Main: nvidia/nemotron-3-ultra-550b-a55b:free (sabse bada, tools, 1M context)
        val mainPick = picker.pickFromCandidates(candidates, ModelRole.Main)
        assertNotNull("Main pick must not be null", mainPick)
        assertEquals(
            "Main must be the largest tool-enabled free model",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            mainPick?.id,
        )
        assertEquals(550.0, mainPick?.totalParamsB!!, 0.001)
        assertEquals(55.0, mainPick?.activeParamsB!!, 0.001)
        assertEquals(1_000_000, mainPick?.contextTokens)
        assertTrue(mainPick?.supportsTools == true)

        // 2. Vision: dots-studio/dots-3-note-preview:free or nvidia/nemotron-3.5-super-vl:free
        val visionPick = picker.pickFromCandidates(candidates, ModelRole.Vision)
        assertNotNull("Vision pick must not be null", visionPick)
        assertTrue(
            "Vision pick must be one of the top vision frontier models (dots-3-note-preview or nemotron-3.5-super-vl), but was ${visionPick?.id}",
            visionPick?.id == "dots-studio/dots-3-note-preview:free" ||
                visionPick?.id == "nvidia/nemotron-3.5-super-vl:free",
        )
        assertTrue(visionPick?.supportsImages == true)

        // 3. Light: small free model with tools (liquid/lfm-*), NOT Main
        val lightPick = picker.pickFromCandidates(candidates, ModelRole.Light)
        assertNotNull("Light pick must not be null", lightPick)
        assertTrue(
            "Light pick must be a compact model (liquid/lfm-*), but was ${lightPick?.id}",
            lightPick?.id?.startsWith("liquid/lfm-") == true,
        )
        assertTrue(
            "Light model must NOT be Main (${mainPick?.id})",
            lightPick?.id != mainPick?.id,
        )
        assertTrue(
            "Light model parameter size must be <= 10B",
            (lightPick?.totalParamsB ?: 0.0) <= 10.0,
        )
        assertTrue(lightPick?.supportsTools == true)

        // 4. Coding: cohere/north-mini-code:free
        val codingPick = picker.pickFromCandidates(candidates, ModelRole.Coding)
        assertNotNull("Coding pick must not be null", codingPick)
        assertEquals(
            "Coding pick must be cohere/north-mini-code:free",
            "cohere/north-mini-code:free",
            codingPick?.id,
        )
        assertTrue(codingPick?.supportsTools == true)

        // 5. Long: model with massive context tokens (>= 1M)
        val longPick = picker.pickFromCandidates(candidates, ModelRole.Long)
        assertNotNull("Long pick must not be null", longPick)
        assertTrue(
            "Long pick must have >= 1,000,000 context tokens, but had ${longPick?.contextTokens}",
            (longPick?.contextTokens ?: 0) >= 1_000_000,
        )
    }

    @Test
    fun openRouterFixture_excludedModelsNeverAppearInAnyRole() {
        val json = loadResource("/fixtures/openrouter-models-2026-11-08.json")
        val parser = OpenRouterModelParser()
        val candidates = parser.parse(json)

        // Injected candidates to test exclusion coverage alongside fixture
        val qwenImage = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen-Image",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )
        val qwenImageEdit = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen-Image-Edit",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
        )
        val fullPool = candidates + listOf(qwenImage, qwenImageEdit)

        val picker = AutoModelPicker(apiClient = { json })

        val disallowed = setOf(
            "openrouter/free",
            "google/lyria-3-clip-preview",
            "google/lyria-3-pro-preview",
            "Qwen/Qwen-Image",
            "Qwen/Qwen-Image-Edit",
            "Qwen-Image",
            "Qwen-Image-Edit",
        )

        for (role in ModelRole.entries) {
            val picked = picker.pickFromCandidates(fullPool, role)
            if (picked != null) {
                assertFalse(
                    "Model ${picked.id} is excluded and must NEVER appear in role $role",
                    disallowed.contains(picked.id),
                )
            }
        }

        // Direct filter verification
        assertTrue("openrouter/free must be excluded", ChatModelFilter.isExcluded("openrouter/free"))
        assertTrue("google/lyria-3-clip-preview must be excluded", ChatModelFilter.isExcluded("google/lyria-3-clip-preview"))
        assertTrue("google/lyria-3-pro-preview must be excluded", ChatModelFilter.isExcluded("google/lyria-3-pro-preview"))
        assertTrue("Qwen-Image must be excluded", ChatModelFilter.isExcluded("Qwen-Image"))
        assertTrue("Qwen-Image-Edit must be excluded", ChatModelFilter.isExcluded("Qwen-Image-Edit"))
        assertTrue("ERNIE-4.5-0.3B-PT must be excluded", ChatModelFilter.isExcluded("ERNIE-4.5-0.3B-PT"))
        assertTrue("meta-llama/Llama-3-8B-Base must be excluded", ChatModelFilter.isExcluded("meta-llama/Llama-3-8B-Base"))
    }

    @Test
    fun openRouterFixture_sizeLessModelIsUnknownNotZero() {
        val json = loadResource("/fixtures/openrouter-models-2026-11-08.json")
        val parser = OpenRouterModelParser()
        val candidates = parser.parse(json)

        val crouton = candidates.firstOrNull { it.id == "stealth/crouton-alpha" }
        assertNotNull("stealth/crouton-alpha should be parsed from fixture", crouton)
        assertNull(
            "Model with unknown size must have totalParamsB == null (0 nahi)",
            crouton?.totalParamsB,
        )
        assertNull(
            "Model with unknown size must have activeParamsB == null",
            crouton?.activeParamsB,
        )
        assertEquals("Unknown", crouton?.sizeLabel)
    }

    @Test
    fun modelScopeFixture_qwen35_397bIsTopRanked() {
        val json = loadResource("/fixtures/modelscope-models-2026-11-08.json")
        val client = HttpModelScopeApiClient()
        val candidates = client.parseModelsJson(json)
        assertTrue("ModelScope fixture must yield models", candidates.isNotEmpty())

        // Image-edit models must be excluded
        assertFalse(candidates.any { it.id == "Qwen/Qwen-Image-Edit" })
        assertFalse(candidates.any { it.id == "MusePublic/Qwen-Image-Edit" })

        // Top ranked model must be Qwen/Qwen3.5-397B-A17B
        val ranked = candidates.sortedWith(ModelScorer.HEAVY_TO_LIGHT_COMPARATOR)
        val top = ranked.first()
        assertEquals(
            "In ModelScope fixture, Qwen/Qwen3.5-397B-A17B must be top-ranked",
            "Qwen/Qwen3.5-397B-A17B",
            top.id,
        )
        assertEquals(397.0, top.totalParamsB!!, 0.001)
        assertEquals(17.0, top.activeParamsB!!, 0.001)

        // OpenGVLab/InternVL3_5-241B-A28B must be recognized with vision and 241B/28B MoE
        val internVL = candidates.firstOrNull { it.id == "OpenGVLab/InternVL3_5-241B-A28B" }
        assertNotNull("OpenGVLab/InternVL3_5-241B-A28B must be present in parsed models", internVL)
        assertTrue("InternVL must be recognized as supporting images", internVL!!.supportsImages)
        assertEquals(241.0, internVL.totalParamsB!!, 0.001)
        assertEquals(28.0, internVL.activeParamsB!!, 0.001)
    }

    @Test
    fun visionTokenMatching_internVlAndGlm45vRecognized_evolveAndSilverRejected() {
        // InternVL names (both dot and underscore versions)
        assertTrue(ChatModelFilter.supportsVision("OpenGVLab/InternVL3.5-241B-A28B"))
        assertTrue(ChatModelFilter.supportsVision("OpenGVLab/InternVL3_5-241B-A28B"))
        assertTrue(ChatModelFilter.supportsVision("InternVL-Chat-V1-5"))

        // GLM vision names (with version number followed by v)
        assertTrue(ChatModelFilter.supportsVision("zai-org/glm-4.5v"))
        assertTrue(ChatModelFilter.supportsVision("glm-4v"))
        assertTrue(ChatModelFilter.supportsVision("THUDM/glm-4v-9b"))

        // Regular models that must NEVER be recognized as vision models
        assertFalse("evolve-70b is NOT a vision model", ChatModelFilter.supportsVision("evolve-70b"))
        assertFalse("silver-14b is NOT a vision model", ChatModelFilter.supportsVision("silver-14b"))
        assertFalse("development-model is NOT a vision model", ChatModelFilter.supportsVision("development-model"))
        assertFalse("overlap-32b is NOT a vision model", ChatModelFilter.supportsVision("overlap-32b"))
        assertFalse("meta-llama/llama-3.1-8b-instruct", ChatModelFilter.supportsVision("meta-llama/llama-3.1-8b-instruct"))
        assertFalse("deepseek-ai/DeepSeek-V3", ChatModelFilter.supportsVision("deepseek-ai/DeepSeek-V3"))
    }
}
