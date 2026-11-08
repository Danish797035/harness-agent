package com.meenai.mina.core.router.picker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRankingsScorerTest {

    private val rankings = listOf(
        ModelRankingEntry(
            model = "deepseek-r1",
            overallScore = 96.5,
            codingScore = 97.2,
            toolUseScore = 92.0,
            visionScore = 50.0,
            speedScore = 68.0,
        ),
        ModelRankingEntry(
            model = "qwen-2.5-coder-32b-instruct",
            overallScore = 92.0,
            codingScore = 98.5,
            toolUseScore = 91.5,
            visionScore = 50.0,
            speedScore = 80.0,
        ),
        ModelRankingEntry(
            model = "generic-32b-instruct",
            overallScore = 80.0,
            codingScore = 75.0,
            toolUseScore = 78.0,
            visionScore = 50.0,
            speedScore = 80.0,
        ),
    )

    private fun createRepository(): ModelRankingsRepository {
        val cache = InMemoryModelRankingsCacheStore()
        cache.save(CachedRankings(
            timestampMs = System.currentTimeMillis(),
            data = ModelRankingsData(models = rankings)
        ))
        return ModelRankingsRepository(cacheStore = cache, bundledResourceSupplier = { null })
    }

    @Test
    fun scoreWithLeaderboard_boostsRankedModel() {
        val repo = createRepository()
        val c1 = ModelCandidate(
            provider = "openrouter",
            id = "qwen/qwen-2.5-coder-32b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 32.0,
            parameterSizeB = 32.0,
        )
        val c2 = ModelCandidate(
            provider = "openrouter",
            id = "other/generic-32b-instruct:free",
            contextTokens = 32768,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 32.0,
            parameterSizeB = 32.0,
        )

        val score1 = ModelScorer.computeScore(c1, repo.findRanking(c1.id), ModelRole.Coding)
        val score2 = ModelScorer.computeScore(c2, repo.findRanking(c2.id), ModelRole.Coding)

        assertTrue("Model with 98.5 coding score should score higher than 75.0 coding score", score1 > score2)
    }

    @Test
    fun scoreWithoutLeaderboard_fallsBackToLiveData() {
        val candidate = ModelCandidate(
            provider = "openrouter",
            id = "custom/unknown-model-70b:free",
            contextTokens = 65536,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            totalParamsB = 70.0,
            parameterSizeB = 70.0,
        )

        val liveScore = ModelScorer.computeScore(candidate, ranking = null)
        assertTrue("Live score should be computed from 70B size + context", liveScore >= 70.0)
    }

    @Test
    fun autoModelPicker_picksCodingModelForCodingRole() = runBlocking {
        val repo = createRepository()
        val candidates = listOf(
            ModelCandidate(
                provider = "openrouter",
                id = "other/generic-32b-instruct:free",
                contextTokens = 32768,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                totalParamsB = 32.0,
                parameterSizeB = 32.0,
            ),
            ModelCandidate(
                provider = "openrouter",
                id = "qwen/qwen-2.5-coder-32b-instruct:free",
                contextTokens = 32768,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                totalParamsB = 32.0,
                parameterSizeB = 32.0,
            ),
        )

        val picker = AutoModelPicker(
            apiClient = object : OpenRouterApiClient {
                override suspend fun fetchModelsJson(): String = "[]"
            },
            rankingsRepository = repo,
        )

        val picked = picker.pickFromCandidates(candidates, ModelRole.Coding)
        assertNotNull(picked)
        assertEquals("qwen/qwen-2.5-coder-32b-instruct:free", picked?.id)
    }
}
