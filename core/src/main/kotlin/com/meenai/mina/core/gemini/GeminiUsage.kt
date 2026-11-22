package com.meenai.mina.core.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/** 一个用量窗口:用掉了百分之几(0..100)和什么时候重置(纪元毫秒)。 */
data class GeminiUsageWindow(val percentUsed: Int, val resetsAtEpochMs: Long)

/**
 * Gemini 账号自己的用量,来自 Gemini 网页的 Usage limits 页:
 * [daily] 是当前窗口(GXU_FIVE_HOURLY),[weekly] 是每周窗口(GXU_WEEKLY)。
 * [tier] 是 Google 返回的套餐名(可能为空)。
 */
data class GeminiUsage(
    val daily: GeminiUsageWindow? = null,
    val weekly: GeminiUsageWindow? = null,
    val tier: String? = null,
)

/**
 * 解析 `UsagePageQuery` 的 JSON 回复。
 *
 * 结构:`data.viewer.rpcs.getUsageInfo.pools[]`,每一项有 `poolType`、`percentageUsed`(0..1 的小数)
 * 和 `gxu.windowRefreshesAt`(ISO 时间)。缺字段、坏时间或没有已知窗口时返回 null,
 * 调用方据此退回本机计数,不会把 0 当成真实用量。
 */
object GeminiUsageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): GeminiUsage? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        // 网页的 GraphQL 回复是 Relay 数组([{data, errors}]);也接受单个对象,方便测试和旧格式
        val payload = when (root) {
            is JsonArray -> root.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .firstOrNull { runCatching { it["data"]?.jsonObject }.getOrNull() != null }
                ?: return null
            is JsonObject -> root
            else -> return null
        }
        val info = payload.path("data", "viewer", "rpcs", "getUsageInfo") ?: return null
        val pools = runCatching { info["pools"]?.jsonArray }.getOrNull() ?: return null
        var daily: GeminiUsageWindow? = null
        var weekly: GeminiUsageWindow? = null
        for (pool in pools) {
            val obj = runCatching { pool.jsonObject }.getOrNull() ?: continue
            val type = obj["poolType"]?.jsonPrimitive?.contentOrNull ?: continue
            val window = windowOf(obj) ?: continue
            when (type) {
                "GXU_FIVE_HOURLY" -> daily = window
                "GXU_WEEKLY" -> weekly = window
            }
        }
        if (daily == null && weekly == null) return null
        val tier = info["subscriptionTier"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return GeminiUsage(daily = daily, weekly = weekly, tier = tier)
    }

    private fun windowOf(pool: JsonObject): GeminiUsageWindow? {
        val fraction = pool["percentageUsed"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }
            ?: return null
        if (fraction < 0.0) return null
        val resetsAt = pool["gxu"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("windowRefreshesAt")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val epochMs = runCatching { OffsetDateTime.parse(resetsAt).toInstant().toEpochMilli() }
            .getOrElse { return null }
        return GeminiUsageWindow(
            percentUsed = (fraction * 100.0).roundToInt().coerceIn(0, 100),
            resetsAtEpochMs = epochMs,
        )
    }

    /** 从根对象按路径取一个对象,任何一层不对就返回 null。 */
    private fun JsonObject.path(vararg keys: String): JsonObject? {
        var current: JsonObject = this
        for (key in keys) {
            current = runCatching { current[key]?.jsonObject }.getOrNull() ?: return null
        }
        return current
    }
}
