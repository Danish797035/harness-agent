package com.meenai.mina.core.router.picker

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRankingsRepositoryTest {

    private val sampleJson = """
        {
          "updatedAt": "2026-11-08T00:00:00Z",
          "version": 1,
          "sources": ["LMSYS Chatbot Arena"],
          "models": [
            {
              "model": "deepseek-r1",
              "overallScore": 96.5,
              "codingScore": 97.2,
              "toolUseScore": 92.0,
              "visionScore": 50.0,
              "speedScore": 68.0
            },
            {
              "model": "qwen-2.5-coder-32b-instruct",
              "overallScore": 92.0,
              "codingScore": 96.5,
              "toolUseScore": 91.5,
              "visionScore": 50.0,
              "speedScore": 80.0
            }
          ]
        }
    """.trimIndent()

    @Test
    fun loadFromBundledDefaultWhenNoCacheAndOffline() = runBlocking {
        val cacheStore = InMemoryModelRankingsCacheStore()
        val repo = ModelRankingsRepository(
            cacheStore = cacheStore,
            httpClient = OkHttpClient(),
            downloadUrl = "http://invalid-offline-host-12345.local/models.json",
            bundledResourceSupplier = { sampleJson },
        )

        val rankings = repo.getRankings()
        assertEquals(2, rankings.models.size)
        assertEquals("deepseek-r1", rankings.models[0].model)
    }

    @Test
    fun usesCachedDataWithin24Hours() = runBlocking {
        val cacheStore = InMemoryModelRankingsCacheStore()
        val parsed = ModelRankingsParser.parse(sampleJson)
        val initialTimestamp = System.currentTimeMillis() - 1000L // 1 sec ago
        cacheStore.save(CachedRankings(timestampMs = initialTimestamp, data = parsed))

        // Even with an invalid host, it should immediately return cached without hitting network
        val repo = ModelRankingsRepository(
            cacheStore = cacheStore,
            httpClient = OkHttpClient(),
            downloadUrl = "http://invalid-host.local",
            bundledResourceSupplier = { null },
        )

        val rankings = repo.getRankings(forceRefresh = false)
        assertEquals(2, rankings.models.size)
        assertEquals("deepseek-r1", rankings.models[0].model)
    }

    @Test
    fun fallbackToOldCacheWhenOfflineAndExpired() = runBlocking {
        val cacheStore = InMemoryModelRankingsCacheStore()
        val parsed = ModelRankingsParser.parse(sampleJson)
        // 48 hours ago (expired cache)
        val oldTimestamp = System.currentTimeMillis() - (48 * 60 * 60 * 1000L)
        cacheStore.save(CachedRankings(timestampMs = oldTimestamp, data = parsed))

        // Network fails
        val repo = ModelRankingsRepository(
            cacheStore = cacheStore,
            httpClient = OkHttpClient(),
            downloadUrl = "http://invalid-host.local",
            bundledResourceSupplier = { null },
        )

        val rankings = repo.getRankings(forceRefresh = false)
        // Gracefully falls back to existing cache
        assertEquals(2, rankings.models.size)
        assertEquals("deepseek-r1", rankings.models[0].model)
    }

    @Test
    fun findRanking_resolvesCandidatesViaNormalizer() = runBlocking {
        val cacheStore = InMemoryModelRankingsCacheStore()
        val parsed = ModelRankingsParser.parse(sampleJson)
        cacheStore.save(CachedRankings(timestampMs = System.currentTimeMillis(), data = parsed))

        val repo = ModelRankingsRepository(
            cacheStore = cacheStore,
            bundledResourceSupplier = { null },
        )

        val r1 = repo.findRanking("deepseek/deepseek-r1:free")
        assertNotNull(r1)
        assertEquals("deepseek-r1", r1?.model)
        assertEquals(97.2, r1?.codingScore ?: 0.0, 0.01)

        val coder = repo.findRanking("qwen/qwen-2.5-coder-32b-instruct:free")
        assertNotNull(coder)
        assertEquals("qwen-2.5-coder-32b-instruct", coder?.model)
    }
}
