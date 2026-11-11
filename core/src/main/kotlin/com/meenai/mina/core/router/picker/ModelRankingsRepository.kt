package com.meenai.mina.core.router.picker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream

/**
 * Cached rankings container with persistent fetch timestamp.
 */
@Serializable
data class CachedRankings(
    val timestampMs: Long,
    val data: ModelRankingsData,
)

/**
 * Cache persistence contract for model rankings.
 */
interface ModelRankingsCacheStore {
    fun load(): CachedRankings?
    fun save(cached: CachedRankings)
    fun clear()
}

/**
 * In-memory cache store for tests and transient runtimes.
 */
class InMemoryModelRankingsCacheStore(
    private var cached: CachedRankings? = null,
) : ModelRankingsCacheStore {
    override fun load(): CachedRankings? = cached
    override fun save(cached: CachedRankings) {
        this.cached = cached
    }
    override fun clear() {
        this.cached = null
    }
}

/**
 * File-backed persistent cache for model rankings.
 */
class FileModelRankingsCacheStore(
    private val cacheFile: File,
    private val json: Json = ModelRankingsParser.json,
) : ModelRankingsCacheStore {
    override fun load(): CachedRankings? {
        return runCatching {
            if (!cacheFile.exists() || !cacheFile.isFile) return null
            json.decodeFromString<CachedRankings>(cacheFile.readText())
        }.getOrNull()
    }

    override fun save(cached: CachedRankings) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.encodeToString(cached))
        }
    }

    override fun clear() {
        runCatching {
            if (cacheFile.exists()) cacheFile.delete()
        }
    }
}

/**
 * Repository responsible for downloading, caching, and serving model rankings.
 * Enforces once-per-day rate limiting, with automatic offline fallback to local cache
 * and bundled resource defaults.
 */
class ModelRankingsRepository(
    private val cacheStore: ModelRankingsCacheStore = InMemoryModelRankingsCacheStore(),
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val downloadUrl: String = DEFAULT_DOWNLOAD_URL,
    private val bundledResourceSupplier: (() -> String?)? = { loadBundledDefault() },
) {
    companion object {
        const val DEFAULT_DOWNLOAD_URL =
            "https://raw.githubusercontent.com/Danish797035/harness-agent/main/model-rankings.json"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

        fun loadBundledDefault(): String? {
            return runCatching {
                val stream: InputStream? =
                    ModelRankingsRepository::class.java.classLoader?.getResourceAsStream("model-rankings.json")
                        ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("model-rankings.json")
                stream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
    }

    @Volatile
    private var inMemoryData: ModelRankingsData? = null

    /**
     * Retrieves current rankings. If cached within 24 hours, returns cached data immediately.
     * Otherwise fetches fresh JSON from GitHub, falling back to cache if offline.
     */
    suspend fun getRankings(forceRefresh: Boolean = false): ModelRankingsData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cacheStore.load()

        if (!forceRefresh && cached != null && (now - cached.timestampMs) < ONE_DAY_MS) {
            inMemoryData = cached.data
            return@withContext cached.data
        }

        // Attempt download
        val downloaded = downloadRankings()
        if (downloaded != null && downloaded.models.isNotEmpty()) {
            cacheStore.save(CachedRankings(timestampMs = now, data = downloaded))
            inMemoryData = downloaded
            return@withContext downloaded
        }

        // Fallback to existing cache even if older than 24h
        if (cached != null) {
            inMemoryData = cached.data
            return@withContext cached.data
        }

        // Fallback to bundled resource default
        val bundled = bundledResourceSupplier?.invoke()
        if (!bundled.isNullOrBlank()) {
            val parsed = ModelRankingsParser.parse(bundled)
            if (parsed.models.isNotEmpty()) {
                inMemoryData = parsed
                return@withContext parsed
            }
        }

        val empty = ModelRankingsData()
        inMemoryData = empty
        empty
    }

    /**
     * Synchronously returns cached rankings or bundled fallback without suspending.
     */
    fun getRankingsSync(): ModelRankingsData {
        inMemoryData?.let { return it }
        val cached = cacheStore.load()
        if (cached != null) {
            inMemoryData = cached.data
            return cached.data
        }
        val bundled = bundledResourceSupplier?.invoke()
        if (!bundled.isNullOrBlank()) {
            val parsed = ModelRankingsParser.parse(bundled)
            inMemoryData = parsed
            return parsed
        }
        return ModelRankingsData()
    }

    /**
     * Finds ranking for candidate ID using [ModelNameNormalizer].
     * Returns null if no match found (caller should use live data only).
     */
    fun findRanking(candidateId: String): ModelRankingEntry? {
        val current = inMemoryData ?: getRankingsSync()
        return ModelNameNormalizer.findMatch(candidateId, current.models)
    }

    private fun downloadRankings(): ModelRankingsData? {
        return runCatching {
            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                ModelRankingsParser.parse(body)
            }
        }.getOrNull()
    }
}
