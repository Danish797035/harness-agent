package com.meenai.mina.settings

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.ModelEndpoint
import com.meenai.mina.core.model.ModelProtocol
import com.meenai.mina.core.model.PromptCaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsTest {
    private val base = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Explicit)

    @Test fun unknownCapabilitiesKeepPresetValues() {
        val o = ModelOptions.merge(listOf(ListedModel("m")), base).single()
        assertEquals(base, o.capabilities)
        assertFalse(o.toolCallsKnown)
        assertFalse(o.visionKnown)
        assertFalse(o.contextKnown)
    }

    @Test fun knownCapabilitiesOverridePresetButKeepCaching() {
        val o = ModelOptions.merge(listOf(ListedModel("m", toolCalls = false, vision = true, contextTokens = 1_000_000, reasoning = true)), base).single()
        assertEquals(Capabilities(false, true, 1_000_000, true, promptCaching = PromptCaching.Explicit), o.capabilities)
        assertTrue(o.toolCallsKnown && o.visionKnown && o.contextKnown)
        assertFalse(o.canBeMain)
    }

    @Test fun userSaidVisionWinsOverListingAndPreset() {
        assertTrue("没写 + 用户说能看", ModelOptions.seesImages(stored = false, userSaid = true, usedForVision = false))
        assertTrue("看图也用它", ModelOptions.seesImages(stored = false, userSaid = null, usedForVision = true))
        assertFalse("用户说不能看", ModelOptions.seesImages(stored = true, userSaid = false, usedForVision = true))
        assertFalse("列表说不能看,用户没改", ModelOptions.seesImages(stored = false, userSaid = null, usedForVision = false))
        val p = Provider("p", "P", ProviderConfig.Kind.OpenAiCompat, "https://relay.test/v1",
            models = listOf(ListedModel("a"), ListedModel("b", vision = false)), visionOverrides = mapOf("a" to true))
        val (a, b) = ModelOptions.ofProvider(p, emptyList())
        assertTrue(a.canSee)
        assertEquals(true, a.visionByUser)
        assertFalse(b.canSee)
        assertEquals(null, b.visionByUser)
    }

    @Test fun toolCapableFirstAndDefaultMainPrefersConfirmed() {
        val listed = listOf(ListedModel("embed", toolCalls = false), ListedModel("guess"), ListedModel("sure", toolCalls = true))
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("guess", "sure", "embed"), options.map { it.id })
        assertEquals("sure", ModelOptions.defaultMain(options))
        assertEquals("guess", ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("guess")), base)))
        assertEquals(null, ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("embed", toolCalls = false)), base)))
    }

    /** Start with your own (default model words), then use the interface that clearly states that you can call the tool, and follow the list order for the rest; do not try those that clearly state that you cannot call the tool, such as vector/speech/drawing. */
    @Test fun candidatesPreferTheProvidersOwnFamily() {
        val listed = listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true),
            ListedModel("text-embedding-v4"),
            ListedModel("gte-rerank-v2"),
            ListedModel("qwen3-tts-flash"),
            ListedModel("wanx-image", chat = false),
            ListedModel("qwen-max"),
            ListedModel("qwen-plus", toolCalls = true),
            ListedModel("Other/Chat", displayName = "Qwen inside"),
            ListedModel("qwen-coder", toolCalls = false),
            ListedModel("moonshot-v1"),
        )
        val options = ModelOptions.merge(listed, base)
        val kimi = ProviderPresets.all.single { it.label == "Kimi" }.familyTokens
        assertEquals(listOf("kimi", "moonshot"), kimi)
        assertEquals(
            listOf("moonshot-v1", "antdigital/Ling-3.1-flash", "qwen-plus", "qwen-max", "Other/Chat"),
            ModelOptions.candidates(options, kimi),
        )
        assertEquals("moonshot-v1", ModelOptions.defaultMain(options, kimi))
        // Can't recognize which company it is: The interface clearly states that the tools can be called
        // first, and the rest follow the order of the list.
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus", "qwen-max", "Other/Chat", "moonshot-v1"), ModelOptions.candidates(options))
        assertEquals(kimi, ModelOptions.familyOf("https://api.moonshot.cn/v1/"))
        assertEquals(emptyList<String>(), ModelOptions.familyOf("https://openrouter.ai/api/v1"))
    }

    private fun ids(listed: List<ListedModel>, tokens: List<String>) = ModelOptions.candidates(ModelOptions.merge(listed, base), tokens)

    /** Our own models are listed in order of newer version first, regardless of the order of interfaces; models of the same version are listed in order, and we do not comment on which one is better: flash, pro, or lite. */
    @Test fun ownModelsNewestVersionFirst() {
        assertEquals(
            listOf("x4", "x-3.1-pro", "x-3-flash", "x-3-pro", "x-2.5-flash", "x-2.0-flash-001"),
            ids(listOf("x-2.5-flash", "x-3-flash", "x-2.0-flash-001", "x-3.1-pro", "x4", "x-3-pro").map { ListedModel(it) }, listOf("x")),
        )
        // Small versions separated by horizontal lines, version numbers with letters; b / k at the end
        // is the parameter amount and context length, not counting the version
        assertEquals(
            listOf("claude-haiku-4-5-20251001", "claude-opus-4-1-20250805", "claude-3-5-haiku-20241022"),
            ids(listOf("claude-3-5-haiku-20241022", "claude-opus-4-1-20250805", "claude-haiku-4-5-20251001").map { ListedModel(it) }, listOf("claude")),
        )
        assertEquals(listOf("qwen3-max", "qwen2.5-72b-instruct"), ids(listOf(ListedModel("qwen2.5-72b-instruct"), ListedModel("qwen3-max")), listOf("qwen")))
    }

    /** The same version: the official version comes first, then the latest alias, and the preview and experimental ones come last; the undated version in the same file comes first, and then the newer version comes first. */
    @Test fun stableBeforePreviewAtTheSameVersion() {
        assertEquals(
            listOf("x-2.5-flash", "x-2.5-flash-2025-08-07", "x-2.5-flash-latest", "x-2.5-flash-preview-09-2025", "x-2.5-flash-exp-0827", "x-2.5-flash-preview-05-20"),
            ids(
                listOf("x-2.5-flash-exp-0827", "x-2.5-flash-preview-05-20", "x-2.5-flash-latest", "x-2.5-flash-preview-09-2025", "x-2.5-flash-2025-08-07", "x-2.5-flash")
                    .map { ListedModel(it) },
                listOf("x"),
            ),
        )
        // Even if the updated version is just a preview, it is still ranked ahead of the official
        // version of the old version.
        assertEquals(listOf("x-3-pro-preview", "x-2.5-pro"), ids(listOf(ListedModel("x-2.5-pro"), ListedModel("x-3-pro-preview")), listOf("x")))
    }

    /** The versions that cannot be read (aliases, etc.) are listed after the ones that can be read, and they are listed in order. */
    @Test fun unparsableVersionsAfterParsedOnes() {
        assertEquals(
            listOf("x-2-mini", "x-1.5", "x-flash-latest", "x-chat", "x-pro"),
            ids(listOf("x-flash-latest", "x-chat", "x-1.5", "x-pro", "x-2-mini").map { ListedModel(it) }, listOf("x")),
        )
    }

    /** The outer order remains unchanged: your own version comes first, and then the interface that clearly states that you can call the tool; versions are only compared in the same file of your own version, and those of other companies are in the order of the list. */
    @Test fun versionOrderStaysInsideTheOuterOrder() {
        val listed = listOf(
            ListedModel("other-9"),
            ListedModel("x-1", toolCalls = true),
            ListedModel("x-3"),
            ListedModel("x-2", toolCalls = true),
            ListedModel("other-1", toolCalls = true),
            ListedModel("other-5"),
        )
        assertEquals(listOf("x-2", "x-1", "x-3", "other-1", "other-9", "other-5"), ids(listed, listOf("x")))
        // No own words: as usual, not arranged by version
        assertEquals(listOf("x-1", "x-2", "other-1", "other-9", "x-3", "other-5"), ids(listed, emptyList()))
    }

    /** The old 2.5 interfaces are listed first: the latest one is tried first, and the vector model and gemma from other companies are not tried first. */
    @Test fun realisticGeminiListTriesTheNewestFirst() {
        val listed = listOf(
            ListedModel("gemini-2.5-flash", displayName = "Gemini 2.5 Flash"),
            ListedModel("gemini-2.5-pro", displayName = "Gemini 2.5 Pro"),
            ListedModel("gemini-2.0-flash", displayName = "Gemini 2.0 Flash"),
            ListedModel("gemini-2.0-flash-001", displayName = "Gemini 2.0 Flash 001"),
            ListedModel("gemini-2.0-flash-lite", displayName = "Gemini 2.0 Flash-Lite"),
            ListedModel("gemini-2.5-flash-preview-09-2025", displayName = "Gemini 2.5 Flash Preview Sep 2025"),
            ListedModel("gemini-flash-latest", displayName = "Gemini Flash Latest"),
            ListedModel("gemini-3-pro-preview", displayName = "Gemini 3 Pro Preview"),
            ListedModel("gemma-3-27b-it", displayName = "Gemma 3 27B"),
            ListedModel("gemini-3.1-pro", displayName = "Gemini 3.1 Pro"),
            ListedModel("gemini-3-flash", displayName = "Gemini 3 Flash"),
            ListedModel("gemini-embedding-001", displayName = "Gemini Embedding 001"),
        )
        val gemini = ProviderPresets.all.single { it.label == "Gemini" }.familyTokens
        assertEquals(
            listOf(
                "gemini-3.1-pro", "gemini-3-flash", "gemini-3-pro-preview", "gemini-2.5-flash", "gemini-2.5-pro",
                "gemini-2.5-flash-preview-09-2025", "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.0-flash-001",
                "gemini-flash-latest", "gemma-3-27b-it",
            ),
            ids(listed, gemini),
        )
        assertEquals("gemini-3.1-pro", ModelOptions.defaultMain(ModelOptions.merge(listed, base), gemini))
    }

    @Test fun nonChatModelsAreRecognised() {
        fun opt(id: String, chat: Boolean? = null) = ModelOptions.merge(listOf(ListedModel(id, chat = chat)), base).single()
        for (id in listOf("text-embedding-3-small", "bge-reranker", "whisper-1", "gpt-4o-mini-tts", "gpt-4o-transcribe", "dall-e-3",
            "gpt-image-1", "qwen-vl-ocr", "paraformer-realtime-v2", "omni-moderation-latest", "qwen3-asr-flash")) {
            assertTrue(id, ModelOptions.looksNonChat(opt(id)))
        }
        for (id in listOf("qwen-plus", "deepseek-chat", "gpt-5-mini", "claude-sonnet-4-5", "qwen-vl-max", "step-2-16k")) {
            assertFalse(id, ModelOptions.looksNonChat(opt(id)))
        }
        assertFalse("接口说是对话模型就照接口", ModelOptions.looksNonChat(opt("my-embed-chat", chat = true)))
        assertTrue(ModelOptions.looksNonChat(opt("x", chat = false)))
        // All are like other uses: there are no candidates, the default main model is returned to the
        // first one that can be used as the main model
        val only = ModelOptions.merge(listOf(ListedModel("text-embedding-v4")), base)
        assertEquals(emptyList<String>(), ModelOptions.candidates(only))
        assertEquals("text-embedding-v4", ModelOptions.defaultMain(only))
    }

    @Test fun modelsOnlyReachableByUnsupportedProtocolsAreNotOffered() {
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/openai/v1")
        val listed = listOf(
            ListedModel("realtime-only", toolCalls = true, unsupportedApis = listOf("realtime")),
            ListedModel("chat", toolCalls = true, endpoint = chat),
            ListedModel("responses", toolCalls = true, endpoint = ModelEndpoint(ModelProtocol.Responses, "https://gw.test/openai/v1")),
            ListedModel("plain"),
        )
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("chat", "responses", "plain"), options.map { it.id })
        assertEquals("chat", ModelOptions.defaultMain(options))
    }

    @Test fun openCodeGoPresetListsFromItsCatalogAddress() {
        val p = ProviderPresets.all.single { it.label == "OpenCode Go" }
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals("https://opencode.ai/zen/go/v1", p.baseUrl)
        assertEquals(p, ProviderPresets.forBaseUrl("https://opencode.ai/zen/go/v1/"))
    }

    @Test fun contextLabels() {
        assertEquals("64K", ModelOptions.contextLabel(64_000))
        assertEquals("128K", ModelOptions.contextLabel(131_072))
        assertEquals("200K", ModelOptions.contextLabel(200_000))
        assertEquals("1M", ModelOptions.contextLabel(1_048_576))
        assertEquals("2M", ModelOptions.contextLabel(2_000_000))
    }

    @Test fun sameProviderKeyIgnoresTrailingSlash() {
        assertEquals(
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "DeepSeek"),
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "DeepSeek "),
        )
        val local = ProviderConfig("3", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "local", base)
        assertEquals("192.168.1.5", ProviderConnection.displayName(local))
    }

    @Test fun providerOptionsAreRememberedListPlusSavedConfigs() {
        val p = Provider("p", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", listOf(ListedModel("a"), ListedModel("b")))
        val savedCaps = base.copy(vision = true)
        val configs = listOf(
            ProviderConfig("1", p.kind, p.baseUrl, "b", savedCaps, p.name, "p"),
            ProviderConfig("2", p.kind, p.baseUrl, "typed", base, p.name, "p"),
            ProviderConfig("3", p.kind, p.baseUrl, "other", base, p.name, "q"),
        )
        val options = ModelOptions.ofProvider(p, configs)
        assertEquals(listOf("a", "b", "typed"), options.map { it.id })
        assertTrue("已有配置的用配置里的能力", options.first { it.id == "b" }.capabilities.vision)
        assertEquals(emptyList<String>(), ModelOptions.ofProvider(p.copy(models = null), emptyList()).map { it.id })
    }

    @Test fun qwenImageEdit_noVisionCapabilityAndLooksNonChat() {
        val msBase = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false, promptCaching = PromptCaching.Implicit)
        val listed = listOf(
            ListedModel("Qwen/Qwen-Image-Edit"),
            ListedModel("MusePublic/Qwen-Image-Edit"),
            ListedModel("Qwen/Qwen2.5-VL-7B-Instruct"),
        )
        val options = ModelOptions.merge(listed, msBase)
        val edit1 = options.first { it.id == "Qwen/Qwen-Image-Edit" }
        val edit2 = options.first { it.id == "MusePublic/Qwen-Image-Edit" }
        val vl = options.first { it.id == "Qwen/Qwen2.5-VL-7B-Instruct" }

        assertFalse("Qwen-Image-Edit must NOT have vision capability", edit1.capabilities.vision)
        assertFalse("Qwen-Image-Edit must NOT have visionKnown", edit1.visionKnown)
        assertFalse("Qwen-Image-Edit cannot see images", edit1.canSee)
        assertTrue("Qwen-Image-Edit looks non-chat", ModelOptions.looksNonChat(edit1))

        assertFalse("MusePublic/Qwen-Image-Edit must NOT have vision capability", edit2.capabilities.vision)
        assertFalse("MusePublic/Qwen-Image-Edit must NOT have visionKnown", edit2.visionKnown)
        assertFalse("MusePublic/Qwen-Image-Edit cannot see images", edit2.canSee)
        assertTrue("MusePublic/Qwen-Image-Edit looks non-chat", ModelOptions.looksNonChat(edit2))

        assertTrue("VL model must have vision capability", vl.capabilities.vision)
        assertTrue("VL model can see", vl.canSee)
    }
}
