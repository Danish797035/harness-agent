package com.meenai.mina.core.router.picker

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.router.ModelCooldownStore
import com.meenai.mina.core.router.RouterCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallProbeAndUnifiedPoolTest {

    // --- Fix 1b: Tool-Call Round-Trip Probe Tests ---

    @Test
    fun probeSuccessWhenModelReturnsToolCall() = runBlocking {
        val modelWithTools = object : ChatModel {
            override val capabilities: Capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Implicit)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                return ModelReply(
                    parts = listOf(Part.ToolCall("call_1", "probe_test_tool", """{"test_arg":"ping"}""")),
                    stopReason = StopReason.ToolUse,
                    usage = null,
                )
            }
        }

        val cooldownStore = ModelCooldownStore()
        val probeService = ModelProbeService()

        val result = probeService.probeToolCalling(
            model = modelWithTools,
            modelId = "model-with-tools",
            provider = "test-provider",
            cooldownStore = cooldownStore,
        )

        assertTrue("Probe should pass when model returns tool call", result.passed)
        assertTrue("supportsTools should be true", result.supportsTools)
        assertFalse("Model should not be marked failed today", probeService.isFailedToday("model-with-tools"))
        assertEquals(0L, cooldownStore.getRemainingCooldownMs("model-with-tools", System.currentTimeMillis()))
    }

    @Test
    fun probeFailureWhenModelReturnsPlaintextInsteadOfToolCall() = runBlocking {
        val modelWithoutTools = object : ChatModel {
            override val capabilities: Capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Implicit)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                return ModelReply(
                    parts = listOf(Part.Text("I cannot call tools, I am just a text model")),
                    stopReason = StopReason.EndTurn,
                    usage = null,
                )
            }
        }

        val cooldownStore = ModelCooldownStore()
        val probeService = ModelProbeService()

        val result = probeService.probeToolCalling(
            model = modelWithoutTools,
            modelId = "model-without-tools",
            provider = "test-provider",
            cooldownStore = cooldownStore,
        )

        assertFalse("Probe should fail when model does not return tool call", result.passed)
        assertFalse("supportsTools should be false", result.supportsTools)
        assertTrue("Model should be marked failed today", probeService.isFailedToday("model-without-tools"))
        assertTrue("Cooldown should be applied to failing model", cooldownStore.getRemainingCooldownMs("model-without-tools", System.currentTimeMillis()) > 0L)
    }

    @Test
    fun probeFailureWhenModelThrowsError() = runBlocking {
        val errorModel = object : ChatModel {
            override val capabilities: Capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Implicit)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                throw ModelError.ToolsUnsupported("Tools not supported by upstream API")
            }
        }

        val cooldownStore = ModelCooldownStore()
        val probeService = ModelProbeService()

        val result = probeService.probeToolCalling(
            model = errorModel,
            modelId = "error-model",
            provider = "test-provider",
            cooldownStore = cooldownStore,
        )

        assertFalse("Probe should fail on exception", result.passed)
        assertFalse("supportsTools should be false", result.supportsTools)
        assertTrue("Model should be marked failed today", probeService.isFailedToday("error-model"))
        assertTrue("Cooldown should be applied on error", cooldownStore.getRemainingCooldownMs("error-model", System.currentTimeMillis()) > 0L)
    }

    @Test
    fun autoModelPickerFiltersOutModelsFailingToolCallProbe() = runBlocking {
        val probeService = ModelProbeService()
        // Record passing model
        probeService.recordProbeResult(
            ProbeResult(
                modelId = "valid-model",
                provider = "test",
                passed = true,
                supportsTools = true,
            )
        )
        // Record failing model
        probeService.recordProbeResult(
            ProbeResult(
                modelId = "failing-model",
                provider = "test",
                passed = false,
                supportsTools = false,
            )
        )

        val picker = AutoModelPicker(
            apiClient = object : OpenRouterApiClient {
                override suspend fun fetchModelsJson(): String = "[]"
            },
            probeService = probeService,
        )

        val candidates = listOf(
            ModelCandidate("test", "valid-model", contextTokens = 64_000, supportsTools = true, isFree = true, parameterSizeB = 70.0),
            ModelCandidate("test", "failing-model", contextTokens = 64_000, supportsTools = true, isFree = true, parameterSizeB = 70.0),
        )

        val filtered = picker.filterCandidates(candidates, ModelRole.Main)
        assertEquals(1, filtered.size)
        assertEquals("valid-model", filtered.first().id)
    }

    // --- Fix 1c: Unified-Pool Picker Tests ---

    @Test
    fun pickerReturnsCandidatesFromAtLeast3ProvidersWithHeavyModels() = runBlocking {
        val picker = AutoModelPicker(
            apiClient = object : OpenRouterApiClient {
                override suspend fun fetchModelsJson(): String = "[]"
            }
        )

        val candidates = picker.getCandidates()

        // 1. Kam se kam 3 distinct providers
        val providers = candidates.map { it.provider.lowercase() }.distinct()
        assertTrue("Picker must return candidates from at least 3 providers, got: $providers", providers.size >= 3)

        // 2. Heavy models ZAROOR hon
        val candidateIds = candidates.map { it.id }
        assertTrue("Gemini 2.5 Flash must be present in pool", candidateIds.any { it.contains("gemini-2.5-flash") })
        assertTrue("Groq Llama 3.3 70B must be present in pool", candidateIds.any { it.contains("llama-3.3-70b") })
        assertTrue("Z.ai GLM-4.7-Flash must be present in pool", candidateIds.any { it.contains("glm-4.7-flash") })

        // 3. Fast/light model present
        assertTrue("Gemini 3.1 Flash Lite must be present in pool", candidateIds.any { it.contains("gemini-3.1-flash-lite") })

        // 4. Kimi K2 must NOT be present (account has only k3 and k3.1-pro)
        assertFalse("Kimi K2 must NOT be present in pool", candidateIds.any { it.contains("kimi", ignoreCase = true) && it.contains("k2", ignoreCase = true) })
    }

    @Test
    fun mainRoleRanksHeavyFrontierModelsAtTop() = runBlocking {
        val picker = AutoModelPicker(
            apiClient = object : OpenRouterApiClient {
                override suspend fun fetchModelsJson(): String = "[]"
            }
        )

        val candidates = picker.getCandidates()
        val filtered = picker.filterCandidates(candidates, ModelRole.Main)
        val ranked = picker.rankCandidates(filtered, ModelRole.Main)

        assertTrue("Main role should rank heavy capable models at top", ranked.isNotEmpty())
        val top = ranked.first()
        val topParams = top.totalParamsB ?: top.parameterSizeB
        assertTrue("Top model parameter size should be heavy (>= 70B)", topParams >= 70.0)
    }

    @Test
    fun lightRoleRanksFastLiteModels() = runBlocking {
        val picker = AutoModelPicker(
            apiClient = object : OpenRouterApiClient {
                override suspend fun fetchModelsJson(): String = "[]"
            }
        )

        val candidates = picker.getCandidates()
        val filtered = picker.filterCandidates(candidates, ModelRole.Light)
        val ranked = picker.rankCandidates(filtered, ModelRole.Light)

        assertTrue("Light role should have models", ranked.isNotEmpty())
        val top = ranked.first()
        val isLight = AutoModelPicker.isLightModel(top)
        assertTrue("Top model in light role must be a light model", isLight)
    }
}
