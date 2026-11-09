package com.meenai.mina.core.router.picker

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.core.router.RouterCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Picker4cTest {

    @Before
    fun setUp() {
        MagicubeLedger.detachStorage()
        MagicubeLedger.clear()
        MagicubeLedger.recordDailyLogin()
    }

    private fun dummyChatModel(caps: Capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Implicit)): ChatModel {
        return object : ChatModel {
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
        }
    }

    @Test
    fun codingTaskPeCodingScoreWalaModel() {
        val task = "write a python script to parse apache access logs and output json"
        val role = TaskDifficultyClassifier.classifyToRole(task)
        assertEquals(ModelRole.Coding, role)

        val cache = InMemoryModelRankingsCacheStore()
        cache.save(
            CachedRankings(
                timestampMs = System.currentTimeMillis(),
                data = ModelRankingsData(
                    models = listOf(
                        ModelRankingEntry(
                            model = "qwen-2.5-coder-32b-instruct",
                            overallScore = 80.0,
                            codingScore = 95.0,
                            toolUseScore = 82.0,
                            visionScore = 50.0,
                            speedScore = 75.0,
                        ),
                        ModelRankingEntry(
                            model = "llama-3.3-70b-instruct",
                            overallScore = 88.0,
                            codingScore = 72.0,
                            toolUseScore = 85.0,
                            visionScore = 50.0,
                            speedScore = 70.0,
                        ),
                    ),
                ),
            ),
        )
        val repo = ModelRankingsRepository(cacheStore = cache, bundledResourceSupplier = { null })

        val coderModel = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen-2.5-coder-32b-instruct:free",
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 32.0,
        )

        val flagshipModel = ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.3-70b-instruct:free",
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 70.0,
        )

        val picker = AutoModelPicker(
            apiClient = { "[]" },
            rankingsRepository = repo,
        )

        val pickedCoding = picker.pickFromCandidates(listOf(flagshipModel, coderModel), ModelRole.Coding)
        assertEquals(
            "Coding task par coding-score wala model chuna jana chahiye",
            "qwen/qwen-2.5-coder-32b-instruct:free",
            pickedCoding?.id,
        )

        val pickedMain = picker.pickFromCandidates(listOf(flagshipModel, coderModel), ModelRole.Main)
        assertEquals(
            "Main role par higher overall/capacity flagship model chuna jana chahiye",
            "meta-llama/llama-3.3-70b-instruct:free",
            pickedMain?.id,
        )
    }

    @Test
    fun probeFailWalaBahar() {
        val probeService = ModelProbeService()
        val failingModelId = "broken/failing-model:free"
        val healthyModelId = "healthy/working-model:free"

        probeService.recordProbeResult(
            ProbeResult(
                modelId = failingModelId,
                provider = "openrouter",
                passed = false,
                errorMessage = "HTTP 400: no provider supported",
            )
        )
        probeService.recordProbeResult(
            ProbeResult(
                modelId = healthyModelId,
                provider = "openrouter",
                passed = true,
                firstTokenMs = 1200L,
            )
        )

        assertTrue(probeService.isFailedToday(failingModelId))
        assertFalse(probeService.isFailedToday(healthyModelId))

        val failingCandidate = ModelCandidate(
            provider = "openrouter",
            id = failingModelId,
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 70.0,
        )
        val healthyCandidate = ModelCandidate(
            provider = "openrouter",
            id = healthyModelId,
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 32.0,
        )

        val picker = AutoModelPicker(
            apiClient = { "[]" },
            probeService = probeService,
        )

        val filtered = picker.filterCandidates(listOf(failingCandidate, healthyCandidate), ModelRole.Main)
        assertFalse("Probe fail hone wala candidate filter mein bahar hona chahiye", filtered.any { it.id == failingModelId })
        assertTrue("Healthy candidate filter mein rehna chahiye", filtered.any { it.id == healthyModelId })

        val picked = picker.pickFromCandidates(listOf(failingCandidate, healthyCandidate), ModelRole.Main)
        assertEquals(healthyModelId, picked?.id)

        // Router quota check also excludes probe failed models
        val router = FreeKeyRouter(
            candidates = listOf(
                RouterCandidate(failingModelId, "openrouter", dummyChatModel(), isFree = true),
                RouterCandidate(healthyModelId, "openrouter", dummyChatModel(), isFree = true),
            ),
            probeService = probeService,
        )
        assertFalse(router.checkCachedQuota(RouterCandidate(failingModelId, "openrouter", dummyChatModel(), isFree = true)))
        assertTrue(router.checkCachedQuota(RouterCandidate(healthyModelId, "openrouter", dummyChatModel(), isFree = true)))
    }

    @Test
    fun hysteresis_5PercentPeNaBadle_15PercentPeBadle() {
        val statsTracker = ModelStatsTracker()

        // 5% improvement does NOT switch
        val currentScore = 100.0
        val candidate5Pct = 105.0
        assertFalse(
            "5% improvement par switch nahi hona chahiye (hysteresis)",
            statsTracker.shouldSwitch(currentScore, candidate5Pct, thresholdPercent = 10.0),
        )

        // 15% improvement DOES switch
        val candidate15Pct = 115.0
        assertTrue(
            "15% improvement par switch hona chahiye",
            statsTracker.shouldSwitch(currentScore, candidate15Pct, thresholdPercent = 10.0),
        )

        // AutoModelPicker hysteresis with currentModelId
        val activeModel = ModelCandidate(
            provider = "openrouter",
            id = "active-model:free",
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 100.0,
        )
        val slightlyBetterModel = ModelCandidate(
            provider = "openrouter",
            id = "slightly-better:free",
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 105.0, // 5% higher
        )
        val significantlyBetterModel = ModelCandidate(
            provider = "openrouter",
            id = "much-better:free",
            contextTokens = 64_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 120.0, // 20% higher
        )

        val picker = AutoModelPicker(
            apiClient = { "[]" },
            statsTracker = statsTracker,
        )

        val pickSlight = picker.pickFromCandidates(
            listOf(activeModel, slightlyBetterModel),
            ModelRole.Main,
            currentModelId = activeModel.id,
        )
        assertEquals("5% better hone par purana model hi retain hona chahiye", activeModel.id, pickSlight?.id)

        val pickMuch = picker.pickFromCandidates(
            listOf(activeModel, significantlyBetterModel),
            ModelRole.Main,
            currentModelId = activeModel.id,
        )
        assertEquals("20% better hone par naya model switch hona chahiye", significantlyBetterModel.id, pickMuch?.id)
    }

    @Test
    fun classifierDefaultHeavy() {
        // Unknown or non-question task without light keywords defaults to Heavy
        val obscureAction = "parse kernel trace dump and extract hex offsets"
        val diff1 = TaskDifficultyClassifier.classify(obscureAction)
        assertEquals("Non-question obscure action default Heavy hona chahiye", TaskDifficulty.Heavy, diff1)

        val regularStatement = "calculate custom matrix transformation"
        val diff2 = TaskDifficultyClassifier.classify(regularStatement)
        assertEquals("Action command default Heavy hona chahiye", TaskDifficulty.Heavy, diff2)

        // Explicit question or light pattern remains Light
        val question = "what is the capital of France?"
        val diffQuestion = TaskDifficultyClassifier.classify(question)
        assertEquals("Question Light hona chahiye", TaskDifficulty.Light, diffQuestion)

        val howQuestion = "how does photosynthesis work"
        val diffHow = TaskDifficultyClassifier.classify(howQuestion)
        assertEquals("How question Light hona chahiye", TaskDifficulty.Light, diffHow)
    }

    @Test
    fun modelScopeSortedHeavyToLight() {
        val modelSmall = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen2.5-7B-Instruct",
            contextTokens = 32_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 7.0,
        )
        val modelMid = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen2.5-72B-Instruct",
            contextTokens = 32_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 72.0,
        )
        val modelGiant = ModelCandidate(
            provider = "modelscope",
            id = "Qwen/Qwen3.5-397B-A17B",
            contextTokens = 32_000,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 397.0,
            activeParamsB = 17.0,
        )

        val list = listOf(modelSmall, modelGiant, modelMid)
        val sorted = list.sortedWith(ModelScorer.HEAVY_TO_LIGHT_COMPARATOR)

        assertEquals("Sabse heavy (397B) sabse upar hona chahiye", "Qwen/Qwen3.5-397B-A17B", sorted[0].id)
        assertEquals("Dusra 72B hona chahiye", "Qwen/Qwen2.5-72B-Instruct", sorted[1].id)
        assertEquals("Teesra 7B hona chahiye", "Qwen/Qwen2.5-7B-Instruct", sorted[2].id)
    }

    @Test
    fun magicubeKamToModelScopeSkip() {
        val msCandidate = RouterCandidate(
            id = "Qwen/Qwen2.5-72B-Instruct",
            provider = "modelscope",
            model = dummyChatModel(),
            isFree = true,
            isBackup = false,
        )
        val orCandidate = RouterCandidate(
            id = "meta-llama/llama-3.3-70b-instruct:free",
            provider = "openrouter",
            model = dummyChatModel(),
            isFree = true,
            isBackup = false,
        )

        val router = FreeKeyRouter(listOf(msCandidate, orCandidate))

        // Balance 4.0 (< 5.0) -> ModelScope skipped
        MagicubeLedger.syncBalance(4.0)
        assertFalse(MagicubeLedger.canAffordModelScope())
        assertFalse(router.checkCachedQuota(msCandidate))

        val pickLow = router.pickBestCandidate(ModelRole.Main)
        assertEquals("Magicube < 5 hone par ModelScope skip hona chahiye", "meta-llama/llama-3.3-70b-instruct:free", pickLow?.id)

        // Balance 10.0 (>= 5.0) -> ModelScope included and can compete
        MagicubeLedger.syncBalance(10.0)
        assertTrue(MagicubeLedger.canAffordModelScope())
        assertTrue(router.checkCachedQuota(msCandidate))

        val pickSufficient = router.pickBestCandidate(ModelRole.Main)
        assertNotNull(pickSufficient)
    }

    @Test
    fun probeBudgetsRespected() = runTest {
        val probeService = ModelProbeService()

        // 1. OpenRouter budget limit: max 6 probes per day
        // probeCandidates evaluates top 5 candidates per role; provide across 2 roles to test cap 6
        val openRouterCandidates = (1..10).map { i ->
            ModelCandidate(
                provider = "openrouter",
                id = "openrouter/model-$i:free",
                contextTokens = 32_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                totalParamsB = 10.0 + i,
            )
        }

        probeService.probeCandidates(
            mapOf(
                ModelRole.Main to openRouterCandidates.take(5),
                ModelRole.Coding to openRouterCandidates.drop(5).take(5),
            )
        )
        assertEquals(
            "OpenRouter daily probe limit 6 honi chahiye",
            6,
            probeService.getOpenRouterProbeCount(),
        )

        // 2. ModelScope probe requires Magicube >= 20.0
        val modelScopeCandidates = (1..5).map { i ->
            ModelCandidate(
                provider = "modelscope",
                id = "modelscope/model-$i",
                contextTokens = 32_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                totalParamsB = 10.0 + i,
            )
        }

        // When Magicube balance is 15.0 (< 20.0): ModelScope probe skipped entirely
        MagicubeLedger.syncBalance(15.0)
        probeService.probeCandidates(mapOf(ModelRole.Main to modelScopeCandidates))
        assertEquals(
            "Magicube < 20 hone par ModelScope probe run nahi hona chahiye",
            0,
            probeService.getModelScopeProbeCount(),
        )

        // When Magicube balance is 50.0 (>= 20.0): ModelScope probes up to daily limit (3)
        MagicubeLedger.syncBalance(50.0)
        probeService.probeCandidates(mapOf(ModelRole.Main to modelScopeCandidates))
        assertEquals(
            "ModelScope daily probe limit 3 honi chahiye",
            3,
            probeService.getModelScopeProbeCount(),
        )
    }
}
