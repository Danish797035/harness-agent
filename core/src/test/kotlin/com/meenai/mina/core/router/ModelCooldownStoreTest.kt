package com.meenai.mina.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ModelCooldownStoreTest {

    @Test
    fun tracksTemporaryCooldowns() {
        val store = ModelCooldownStore()
        val now = 100_000L

        assertTrue(store.isAvailable("model-a", "provider-a", now))
        store.markCooldown("model-a", 30_000L, now)

        assertFalse(store.isAvailable("model-a", "provider-a", now + 10_000L))
        assertEquals(20_000L, store.getRemainingCooldownMs("model-a", now + 10_000L))
        assertTrue(store.isAvailable("model-a", "provider-a", now + 30_001L))
    }

    @Test
    fun computesGeminiMidnightPacificReset() {
        val pacific = ZoneId.of("America/Los_Angeles")
        // Set fixed time: 2026-06-15 14:00:00 PT
        val zdt = ZonedDateTime.of(2026, 6, 15, 14, 0, 0, 0, pacific)
        val nowMs = zdt.toInstant().toEpochMilli()

        val nextMidnight = ModelCooldownStore.nextPacificMidnight(nowMs)
        val resetZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextMidnight), pacific)

        assertEquals(2026, resetZdt.year)
        assertEquals(6, resetZdt.monthValue)
        assertEquals(16, resetZdt.dayOfMonth)
        assertEquals(0, resetZdt.hour)
        assertEquals(0, resetZdt.minute)
        assertEquals(0, resetZdt.second)
    }

    @Test
    fun computesOpenRouterMidnightUtcReset() {
        val utc = ZoneOffset.UTC
        // Set fixed time: 2026-11-06 18:30:00 UTC
        val zdt = ZonedDateTime.of(2026, 11, 6, 18, 30, 0, 0, utc)
        val nowMs = zdt.toInstant().toEpochMilli()

        val nextMidnight = ModelCooldownStore.nextUtcMidnight(nowMs)
        val resetZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextMidnight), utc)

        assertEquals(2026, resetZdt.year)
        assertEquals(11, resetZdt.monthValue)
        assertEquals(7, resetZdt.dayOfMonth)
        assertEquals(0, resetZdt.hour)
        assertEquals(0, resetZdt.minute)
    }

    @Test
    fun avoidsModelProactivelyWhenQuotaRemainingIsLow() {
        val store = ModelCooldownStore()
        val now = 50_000L

        store.updateQuotaRemaining("model-x", 1) // 1 request left (< 2)

        // For continuing existing task: still available
        assertTrue(store.isAvailable("model-x", "provider-x", now, isNewTask = false))

        // For starting a new task: proactively avoided
        assertFalse(store.isAvailable("model-x", "provider-x", now, isNewTask = true))

        store.updateQuotaRemaining("model-x", 5)
        assertTrue(store.isAvailable("model-x", "provider-x", now, isNewTask = true))
    }

    @Test
    fun persistsAndRestoresCooldownsAndBadKeys() {
        var savedJson = ""
        val store = ModelCooldownStore { savedJson = it }

        store.markCooldown("m1", 50_000L, 1000L)
        store.markBadKey("broken_provider")
        store.updateQuotaRemaining("m2", 10)

        assertTrue(savedJson.isNotEmpty())

        val restoredStore = ModelCooldownStore()
        restoredStore.loadFromJson(savedJson)

        assertTrue(restoredStore.isKeyBad("broken_provider"))
        assertFalse(restoredStore.isAvailable("m1", "p1", 2000L))
        assertTrue(restoredStore.isAvailable("m2", "p2", 2000L, isNewTask = true))
    }
}
