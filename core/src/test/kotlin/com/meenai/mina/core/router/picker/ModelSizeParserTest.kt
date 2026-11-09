package com.meenai.mina.core.router.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSizeParserTest {

    @Test
    fun parseMoeActiveNotation_397B_A17B() {
        val parsed = ModelSizeParser.parse("deepseek/deepseek-v3-397b-a17b")
        assertTrue(parsed.isKnown)
        assertEquals(397.0, parsed.totalParamsB!!, 0.001)
        assertEquals(17.0, parsed.activeParamsB!!, 0.001)
        assertEquals("397B-A17B", parsed.sizeLabel)

        val parsed671 = ModelSizeParser.parse("deepseek-ai/DeepSeek-R1-671B-A37B")
        assertTrue(parsed671.isKnown)
        assertEquals(671.0, parsed671.totalParamsB!!, 0.001)
        assertEquals(37.0, parsed671.activeParamsB!!, 0.001)
        assertEquals("671B-A37B", parsed671.sizeLabel)
    }

    @Test
    fun parseMoeMultiplierNotation_8x7B() {
        val parsed = ModelSizeParser.parse("mistralai/mixtral-8x7b-instruct")
        assertTrue(parsed.isKnown)
        assertEquals(56.0, parsed.totalParamsB!!, 0.001)
        assertEquals(14.0, parsed.activeParamsB!!, 0.001)
        assertEquals("56B-A14B", parsed.sizeLabel)
    }

    @Test
    fun parseStandardBillionsNotation() {
        val p70 = ModelSizeParser.parse("meta-llama/llama-3.3-70b-instruct:free")
        assertTrue(p70.isKnown)
        assertEquals(70.0, p70.totalParamsB!!, 0.001)
        assertEquals("70B", p70.sizeLabel)

        val p32 = ModelSizeParser.parse("qwen/qwen-2.5-coder-32b-instruct")
        assertTrue(p32.isKnown)
        assertEquals(32.0, p32.totalParamsB!!, 0.001)
        assertEquals("32B", p32.sizeLabel)

        val p8 = ModelSizeParser.parse("meta-llama/llama-3.1-8b-instruct")
        assertTrue(p8.isKnown)
        assertEquals(8.0, p8.totalParamsB!!, 0.001)
        assertEquals("8B", p8.sizeLabel)

        val p05 = ModelSizeParser.parse("qwen/qwen-2.5-0.5b-instruct")
        assertTrue(p05.isKnown)
        assertEquals(0.5, p05.totalParamsB!!, 0.001)
        assertEquals("0.5B", p05.sizeLabel)
    }

    @Test
    fun parseStandardMillionsNotation() {
        val p350m = ModelSizeParser.parse("facebook/opt-350m")
        assertTrue(p350m.isKnown)
        assertEquals(0.35, p350m.totalParamsB!!, 0.001)
        assertEquals("0.35B", p350m.sizeLabel)
    }

    @Test
    fun parseProseMoeNotation() {
        // "with 12B active parameters out of 120B total" (nemotron-3.5-super-vl)
        val pNemotron = ModelSizeParser.parse(
            modelId = "nvidia/nemotron-3.5-super-vl:free",
            description = "NVIDIA Nemotron 3.5 Super VL is an open multimodal mixture-of-experts model from NVIDIA, with 12B active parameters out of 120B total."
        )
        assertTrue(pNemotron.isKnown)
        assertEquals(120.0, pNemotron.totalParamsB!!, 0.001)
        assertEquals(12.0, pNemotron.activeParamsB!!, 0.001)
        assertEquals("120B-A12B", pNemotron.sizeLabel)

        // "with 16B active parameters out of 280B total" (dots-3-note-preview)
        val pDots = ModelSizeParser.parse(
            modelId = "dots-studio/dots-3-note-preview:free",
            description = "Dots3-Note Preview is an open-weight mixture-of-experts model from Dots Studio, with 16B active parameters out of 280B total."
        )
        assertTrue(pDots.isKnown)
        assertEquals(280.0, pDots.totalParamsB!!, 0.001)
        assertEquals(16.0, pDots.activeParamsB!!, 0.001)
        assertEquals("280B-A16B", pDots.sizeLabel)

        // "29B total parameters with 4B active" (xing4.0-29b-a4b)
        val pXing = ModelSizeParser.parse(
            modelId = "xingchen-agi/xing4.0-29b-a4b:free",
            description = "Xing is a 29B total parameters with 4B active MoE model."
        )
        assertTrue(pXing.isKnown)
        assertEquals(29.0, pXing.totalParamsB!!, 0.001)
        assertEquals(4.0, pXing.activeParamsB!!, 0.001)
        assertEquals("29B-A4B", pXing.sizeLabel)
    }

    @Test
    fun sizeLessModelIsUnknown_notZero() {
        val parsed = ModelSizeParser.parse("google/gemini-2.0-flash-exp:free")
        assertFalse("Size na mile to known nahi hona chahiye", parsed.isKnown)
        assertNull("Size na mile to totalParamsB null hona chahiye (0 nahi)", parsed.totalParamsB)
        assertNull("activeParamsB null hona chahiye", parsed.activeParamsB)
        assertEquals("Unknown", parsed.sizeLabel)

        val candidate = ModelCandidate(
            provider = "openrouter",
            id = "google/gemini-2.0-flash-exp:free",
            contextTokens = 1048576,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
            totalParamsB = parsed.totalParamsB,
            activeParamsB = parsed.activeParamsB,
        )
        assertNull(candidate.totalParamsB)
        assertEquals("Unknown", candidate.sizeLabel)
    }
}
