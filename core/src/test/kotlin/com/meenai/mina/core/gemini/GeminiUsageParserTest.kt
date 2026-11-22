package com.meenai.mina.core.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GeminiUsageParserTest {
    private val body = """
        {
          "data": {
            "viewer": {
              "rpcs": {
                "getUsageInfo": {
                  "subscriptionTier": "GOOGLE_AI_PRO",
                  "pools": [
                    {
                      "poolType": "GXU_FIVE_HOURLY",
                      "percentageUsed": 0,
                      "gxu": { "windowRefreshesAt": "2026-11-22T04:21:00Z" }
                    },
                    {
                      "poolType": "GXU_WEEKLY",
                      "percentageUsed": 0.04,
                      "gxu": { "windowRefreshesAt": "2026-11-25T14:21:00Z" }
                    }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun readsDailyAndWeeklyWindows() {
        val usage = GeminiUsageParser.parse(body)!!
        assertEquals(0, usage.daily?.percentUsed)
        assertEquals(4, usage.weekly?.percentUsed)
        assertEquals("GOOGLE_AI_PRO", usage.tier)
        assertEquals(Instant.parse("2026-11-22T04:21:00Z").toEpochMilli(), usage.daily?.resetsAtEpochMs)
        assertEquals(Instant.parse("2026-11-25T14:21:00Z").toEpochMilli(), usage.weekly?.resetsAtEpochMs)
    }

    @Test
    fun readsRelayArrayResponseFromTheWeb() {
        // 网页的回复带防 XSSI 前缀,并且是数组
        val relay = ")]}'\n[$body]"
        val usage = GeminiUsageParser.parse(relay)!!
        assertEquals(0, usage.daily?.percentUsed)
        assertEquals(4, usage.weekly?.percentUsed)
        assertEquals("GOOGLE_AI_PRO", usage.tier)
    }

    @Test
    fun unknownPoolsAreIgnoredAndMissingInfoIsNull() {
        val onlyUnknown = """
            {"data":{"viewer":{"rpcs":{"getUsageInfo":{"pools":[
              {"poolType":"SOMETHING_ELSE","percentageUsed":0.5,"gxu":{"windowRefreshesAt":"2026-11-22T04:21:00Z"}}
            ]}}}}}
        """.trimIndent()
        assertNull(GeminiUsageParser.parse(onlyUnknown))
        assertNull(GeminiUsageParser.parse("""{"data":{}}"""))
        assertNull(GeminiUsageParser.parse("""[{"data":null,"errors":[{"message":"nope"}]}]"""))
        assertNull(GeminiUsageParser.parse("""[{"data":{"viewer":null}}]"""))
        assertNull(GeminiUsageParser.parse("not json"))
    }

    @Test
    fun badResetTimeIsSkippedNotShownAsZero() {
        val bad = """
            {"data":{"viewer":{"rpcs":{"getUsageInfo":{"pools":[
              {"poolType":"GXU_WEEKLY","percentageUsed":0.04,"gxu":{"windowRefreshesAt":"soon"}}
            ]}}}}}
        """.trimIndent()
        assertNull(GeminiUsageParser.parse(bad))
    }

    @Test
    fun percentageIsClampedAndRounded() {
        val high = """
            {"data":{"viewer":{"rpcs":{"getUsageInfo":{"pools":[
              {"poolType":"GXU_FIVE_HOURLY","percentageUsed":1.4,"gxu":{"windowRefreshesAt":"2026-11-22T04:21:00Z"}}
            ]}}}}}
        """.trimIndent()
        assertEquals(100, GeminiUsageParser.parse(high)?.daily?.percentUsed)
    }
}
