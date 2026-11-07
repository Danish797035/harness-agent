package com.meenai.mina.core.router.picker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Container for cached model candidates with fetch timestamp.
 */
@Serializable
data class CachedModelData(
    val timestampMs: Long,
    val models: List<ModelCandidate>,
)

/**
 * Cache persistence contract for model candidates.
 */
interface ModelCacheStore {
    fun load(): CachedModelData?
    fun save(data: CachedModelData)
    fun clear()
}

/**
 * In-memory cache implementation, suitable for transient runs and tests.
 */
class InMemoryModelCacheStore(
    private var cached: CachedModelData? = null,
) : ModelCacheStore {
    override fun load(): CachedModelData? = cached
    override fun save(data: CachedModelData) {
        cached = data
    }
    override fun clear() {
        cached = null
    }
}

/**
 * File-backed persistent cache implementation.
 */
class FileModelCacheStore(
    private val cacheFile: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelCacheStore {
    override fun load(): CachedModelData? {
        return runCatching {
            if (!cacheFile.exists() || !cacheFile.isFile) return null
            json.decodeFromString<CachedModelData>(cacheFile.readText())
        }.getOrNull()
    }

    override fun save(data: CachedModelData) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val content = json.encodeToString(data)
            cacheFile.writeText(content)
        }
    }

    override fun clear() {
        runCatching {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        }
    }
}
