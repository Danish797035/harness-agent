package com.meenai.mina.core.router.picker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Model ranking scores retrieved from public leaderboards (LMSYS Chatbot Arena, Open LLM Leaderboard).
 * All scores are scaled on a 0.0 - 100.0 baseline.
 */
@Serializable
data class ModelRankingEntry(
    val model: String,
    val overallScore: Double = 0.0,
    val codingScore: Double = 0.0,
    val toolUseScore: Double = 0.0,
    val visionScore: Double = 0.0,
    val speedScore: Double = 0.0,
)

/**
 * Encapsulates the complete leaderboard rankings payload.
 */
@Serializable
data class ModelRankingsData(
    val updatedAt: String = "",
    val version: Int = 1,
    val sources: List<String> = emptyList(),
    val models: List<ModelRankingEntry> = emptyList(),
)

/**
 * Parser for leaderboard JSON responses.
 */
object ModelRankingsParser {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(jsonString: String): ModelRankingsData {
        return runCatching {
            json.decodeFromString<ModelRankingsData>(jsonString)
        }.getOrElse {
            ModelRankingsData()
        }
    }
}
