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

/** A usage window: what percentage is used (0..100) and when it resets (epoch milliseconds). */
data class GeminiUsageWindow(
    val percentUsed: Int,
    val resetsAtEpochMs: Long,
    /** Exact fractional usage in [0.0, 1.0] for sub-percent remaining quota display (e.g. 90.8%). */
    val fractionUsed: Double = (percentUsed / 100.0).coerceIn(0.0, 1.0),
)

/**
 * Gemini account's own usage, from the Usage limits page of the Gemini web page: [daily] is the current
 * window (GXU_FIVE_HOURLY), [weekly] is the weekly window (GXU_WEEKLY). [tier] is the package name
 * returned by Google (may be empty).
 */
data class GeminiUsage(
    val daily: GeminiUsageWindow? = null,
    val weekly: GeminiUsageWindow? = null,
    val tier: String? = null,
)

/**
 * Parse the JSON response for `UsagePageQuery`.
 *
 * Structure: `data.viewer.rpcs.getUsageInfo.pools[]`, each item has `poolType`, `percentageUsed` (0..1
 * decimal) and `gxu.windowRefreshesAt` (ISO time). Returns null when there is a missing field, a bad
 * time, or no known window, and the caller will return the native count accordingly and will not regard
 * 0 as the real usage.
 */
object GeminiUsageParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val VIEWER_NULL = Regex(""""viewer"\s*:\s*null""")

    fun parse(body: String): GeminiUsage? {
        val text = stripXssiPrefix(body)
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        // GraphQL responses for web pages are Relay arrays ([{data, errors}]); also accepts single
        // objects for easy testing and legacy formats
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

    /**
     * Is this reply "wrong login status"? When the webpage is not logged in/the token expires, the HTTP
     * is still 200, but `viewer` is null (or UNAUTHENTICATED). The caller changes the token accordingly
     * and tries again.
     */
    fun needsFreshSession(body: String): Boolean {
        val text = stripXssiPrefix(body).trimStart()
        // What I got when I was redirected to the login page was HTML, not JSON.
        if (!text.startsWith("[") && !text.startsWith("{")) return true
        return VIEWER_NULL.containsMatchIn(text) || text.contains("UNAUTHENTICATED")
    }

    /** There is an anti-XSSI prefix in front of Google's reply (for example, `)]}'` + newline), so clean it first. */
    private fun stripXssiPrefix(body: String): String =
        body.trimStart().let { if (it.startsWith(")]}'")) it.substring(4).trimStart() else it }

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
            fractionUsed = fraction.coerceIn(0.0, 1.0),
        )
    }

    /** Get an object from the root object according to the path. If any layer is incorrect, null will be returned. */
    private fun JsonObject.path(vararg keys: String): JsonObject? {
        var current: JsonObject = this
        for (key in keys) {
            current = runCatching { current[key]?.jsonObject }.getOrNull() ?: return null
        }
        return current
    }
}
