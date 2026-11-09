package com.meenai.mina.ui.router

import com.meenai.mina.core.router.MagicubeState
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsMagicubeStorageTest {

    @Test
    fun testLoadEmptyReturnsNull() {
        val prefs = MemoryPrefs()
        val storage = PrefsMagicubeStorage(prefs)
        assertNull(storage.load())
    }

    @Test
    fun testSaveAndLoadMatchesState() {
        val prefs = MemoryPrefs()
        val storage = PrefsMagicubeStorage(prefs)

        val state = MagicubeState(
            shortTermBalance = 200.0,
            shortTermDayKey = "2026-05-10",
            isLoginMarkedDayKey = "2026-05-10",
            isAlibabaLinkedDayKey = "2026-05-10",
            longTermBalance = 150.5,
            longTermExpiryEpochMs = 1778400000000L,
            isAlibabaLinked = true,
            isBalanceKnown = true,
        )

        storage.save(state)
        val loaded = storage.load()

        assertEquals(200.0, loaded!!.shortTermBalance, 0.001)
        assertEquals("2026-05-10", loaded.shortTermDayKey)
        assertEquals("2026-05-10", loaded.isLoginMarkedDayKey)
        assertEquals("2026-05-10", loaded.isAlibabaLinkedDayKey)
        assertEquals(150.5, loaded.longTermBalance, 0.001)
        assertEquals(1778400000000L, loaded.longTermExpiryEpochMs)
        assertTrue(loaded.isAlibabaLinked)
        assertTrue(loaded.isBalanceKnown)
    }

    @Test
    fun testIsBalanceKnownFalsePreserved() {
        val prefs = MemoryPrefs()
        val storage = PrefsMagicubeStorage(prefs)

        val state = MagicubeState(
            shortTermBalance = 0.0,
            shortTermDayKey = null,
            isLoginMarkedDayKey = null,
            isAlibabaLinkedDayKey = null,
            longTermBalance = 0.0,
            longTermExpiryEpochMs = null,
            isAlibabaLinked = false,
            isBalanceKnown = false,
        )

        storage.save(state)
        val loaded = storage.load()
        assertNotNull(loaded)
        assertFalse(loaded!!.isBalanceKnown)
    }
}
