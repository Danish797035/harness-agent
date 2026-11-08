package com.meenai.mina.core.router.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNameNormalizerTest {

    @Test
    fun normalize_stripsProviderPrefixes() {
        assertEquals("llama-3.3-70b-instruct", ModelNameNormalizer.normalize("meta-llama/llama-3.3-70b-instruct"))
        assertEquals("deepseek-r1", ModelNameNormalizer.normalize("deepseek/deepseek-r1"))
        assertEquals("qwen2.5-72b-instruct", ModelNameNormalizer.normalize("Qwen/Qwen2.5-72B-Instruct"))
        assertEquals("glm-4-9b-chat", ModelNameNormalizer.normalize("zai/glm-4-9b-chat"))
        assertEquals("mistral-small-24b-instruct", ModelNameNormalizer.normalize("mistralai/mistral-small-24b-instruct"))
    }

    @Test
    fun normalize_stripsFreeAndBetaSuffixes() {
        assertEquals("llama-3.3-70b-instruct", ModelNameNormalizer.normalize("meta-llama/llama-3.3-70b-instruct:free"))
        assertEquals("deepseek-r1", ModelNameNormalizer.normalize("deepseek/deepseek-r1:free"))
        assertEquals("gemini-2.0-flash", ModelNameNormalizer.normalize("google/gemini-2.0-flash:beta"))
        assertEquals("qwen-2.5-72b", ModelNameNormalizer.normalize("qwen/qwen-2.5-72b-free"))
    }

    @Test
    fun normalize_handlesCaseAndSeparators() {
        assertEquals("qwen-2.5-72b-instruct", ModelNameNormalizer.normalize("QWEN/Qwen_2.5_72B_Instruct:FREE"))
        assertEquals("meta-llama-3-70b", ModelNameNormalizer.normalize("meta.llama/3.70b"))
    }

    @Test
    fun matches_evaluatesEquivalentNames() {
        assertTrue(ModelNameNormalizer.matches("meta-llama/llama-3.3-70b-instruct:free", "llama-3.3-70b-instruct"))
        assertTrue(ModelNameNormalizer.matches("deepseek/deepseek-r1:free", "deepseek-r1"))
        assertTrue(ModelNameNormalizer.matches("Qwen/Qwen2.5-72B-Instruct", "qwen-2.5-72b-instruct"))
        assertTrue(ModelNameNormalizer.matches("qwen/qwen-2.5-coder-32b-instruct:free", "qwen-2.5-coder-32b-instruct"))
    }

    @Test
    fun findMatch_locatesMatchingEntryOrNull() {
        val rankings = listOf(
            ModelRankingEntry(model = "deepseek-r1", overallScore = 96.5, codingScore = 97.2),
            ModelRankingEntry(model = "qwen-2.5-72b-instruct", overallScore = 93.5, codingScore = 91.0),
            ModelRankingEntry(model = "meta-llama-3.3-70b-instruct", overallScore = 92.5, codingScore = 89.0),
        )

        val match1 = ModelNameNormalizer.findMatch("deepseek/deepseek-r1:free", rankings)
        assertNotNull(match1)
        assertEquals("deepseek-r1", match1?.model)

        val match2 = ModelNameNormalizer.findMatch("meta-llama/llama-3.3-70b-instruct:free", rankings)
        assertNotNull(match2)
        assertEquals("meta-llama-3.3-70b-instruct", match2?.model)

        val matchUnknown = ModelNameNormalizer.findMatch("unknown-custom-model-v1", rankings)
        assertNull("Unmatched model must return null so live data only is used", matchUnknown)
    }
}
