package com.meenai.mina.ui.router

import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerTest {

    private val sampleModels = listOf(
        ModelQuotaLine(
            modelId = "google/gemini-2.0-flash-exp:free",
            modelName = "Gemini 2.0 Flash",
            provider = "OpenRouter",
            todayRequests = 5,
            limit = 50,
            isFree = true,
            supportsVision = true,
            supportsTools = true,
            contextTokens = 128_000,
            quotaState = QuotaState.Ready,
        ),
        ModelQuotaLine(
            modelId = "meta-llama/llama-3.3-70b-instruct:free",
            modelName = "Llama 3.3 70B",
            provider = "OpenRouter",
            todayRequests = 48,
            limit = 50,
            isFree = true,
            supportsVision = false,
            supportsTools = true,
            contextTokens = 128_000,
            quotaState = QuotaState.Low,
        ),
        ModelQuotaLine(
            modelId = "meta-llama/llama-3.1-8b-instruct:free",
            modelName = "Llama 3.1 8B",
            provider = "OpenRouter",
            todayRequests = 50,
            limit = 50,
            isFree = true,
            supportsVision = false,
            supportsTools = false,
            contextTokens = 32_000,
            quotaState = QuotaState.Exhausted,
            exhaustedReason = "Daily limit reached (50/50 requests)",
        ),
        ModelQuotaLine(
            modelId = "qwen/qwen-2.5-coder-32b-instruct:free",
            modelName = "Qwen 2.5 Coder 32B",
            provider = "OpenRouter",
            todayRequests = 10,
            limit = null,
            isFree = true,
            supportsVision = false,
            supportsTools = true,
            contextTokens = 32_000,
            quotaState = QuotaState.Ready,
        ),
        ModelQuotaLine(
            modelId = "openai/gpt-4o",
            modelName = "GPT-4o",
            provider = "OpenAI",
            todayRequests = 2,
            limit = null,
            isFree = false,
            supportsVision = true,
            supportsTools = true,
            contextTokens = 128_000,
            quotaState = QuotaState.Ready,
        ),
    )

    // ==========================================
    // 1. Search & Filter Tests
    // ==========================================

    @Test
    fun filterModels_allFilter_returnsAllWhenSearchEmpty() {
        val result = ModelPickerLogic.filterModels(sampleModels, "", ModelPickerFilter.All)
        assertEquals(5, result.size)
    }

    @Test
    fun filterModels_searchByName_returnsMatchingModels() {
        val result = ModelPickerLogic.filterModels(sampleModels, "llama", ModelPickerFilter.All)
        assertEquals(2, result.size)
        assertTrue(result.all { it.modelName.contains("Llama") })
    }

    @Test
    fun filterModels_searchByProvider_returnsMatchingModels() {
        val result = ModelPickerLogic.filterModels(sampleModels, "openai", ModelPickerFilter.All)
        assertEquals(1, result.size)
        assertEquals("GPT-4o", result.first().modelName)
    }

    @Test
    fun filterModels_freeFilter_returnsOnlyFreeModels() {
        val result = ModelPickerLogic.filterModels(sampleModels, "", ModelPickerFilter.Free)
        assertEquals(4, result.size)
        assertTrue(result.all { it.isFree })
        assertFalse(result.any { it.modelId == "openai/gpt-4o" })
    }

    @Test
    fun filterModels_visionFilter_returnsOnlyVisionModels() {
        val result = ModelPickerLogic.filterModels(sampleModels, "", ModelPickerFilter.Vision)
        assertEquals(2, result.size)
        assertTrue(result.all { it.supportsVision })
    }

    @Test
    fun filterModels_toolsFilter_returnsOnlyToolSupportingModels() {
        val result = ModelPickerLogic.filterModels(sampleModels, "", ModelPickerFilter.Tools)
        assertEquals(4, result.size)
        assertTrue(result.all { it.supportsTools })
        assertFalse(result.any { it.modelId == "meta-llama/llama-3.1-8b-instruct:free" })
    }

    @Test
    fun filterModels_longContextFilter_returnsModelsAboveThreshold() {
        val result = ModelPickerLogic.filterModels(sampleModels, "", ModelPickerFilter.LongContext)
        assertEquals(3, result.size)
        assertTrue(result.all { it.contextTokens >= ModelPickerLogic.LONG_CONTEXT_THRESHOLD })
    }

    @Test
    fun filterModels_combinedSearchAndFilter() {
        // Search "qwen" with Tools filter -> should find Qwen Coder
        val qwenTools = ModelPickerLogic.filterModels(sampleModels, "qwen", ModelPickerFilter.Tools)
        assertEquals(1, qwenTools.size)
        assertEquals("qwen/qwen-2.5-coder-32b-instruct:free", qwenTools.first().modelId)

        // Search "qwen" with Vision filter -> should be empty
        val qwenVision = ModelPickerLogic.filterModels(sampleModels, "qwen", ModelPickerFilter.Vision)
        assertTrue(qwenVision.isEmpty())
    }

    @Test
    fun formatContextTokens_formatsProperly() {
        assertEquals("128k", ModelPickerLogic.formatContextTokens(128_000))
        assertEquals("32k", ModelPickerLogic.formatContextTokens(32_000))
        assertEquals("1M", ModelPickerLogic.formatContextTokens(1_000_000))
        assertEquals("500", ModelPickerLogic.formatContextTokens(500))
    }

    // ==========================================
    // 2. Quota State Tests
    // ==========================================

    @Test
    fun computeQuotaState_readyWhenHealthy() {
        val (state, reason) = ModelPickerLogic.computeQuotaState(
            isBadKey = false,
            isUnsupported = false,
            cooldownMs = 0L,
            limit = 50,
            todayRequests = 10,
        )
        assertEquals(QuotaState.Ready, state)
        assertNull(reason)
    }

    @Test
    fun computeQuotaState_lowWhenNearLimit() {
        val (state, reason) = ModelPickerLogic.computeQuotaState(
            isBadKey = false,
            isUnsupported = false,
            cooldownMs = 0L,
            limit = 50,
            todayRequests = 48,
        )
        assertEquals(QuotaState.Low, state)
        assertNull(reason)
    }

    @Test
    fun computeQuotaState_exhaustedWhenLimitReached() {
        val (state, reason) = ModelPickerLogic.computeQuotaState(
            isBadKey = false,
            isUnsupported = false,
            cooldownMs = 0L,
            limit = 50,
            todayRequests = 50,
        )
        assertEquals(QuotaState.Exhausted, state)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Daily limit reached"))
    }

    @Test
    fun computeQuotaState_exhaustedWhenCooldownActive() {
        val (state, reason) = ModelPickerLogic.computeQuotaState(
            isBadKey = false,
            isUnsupported = false,
            cooldownMs = 120_000L, // 2 minutes
            limit = 50,
            todayRequests = 10,
        )
        assertEquals(QuotaState.Exhausted, state)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Cooldown active"))
        assertTrue(reason.contains("2 min"))
    }

    @Test
    fun computeQuotaState_exhaustedWhenBadKey() {
        val (state, reason) = ModelPickerLogic.computeQuotaState(
            isBadKey = true,
            isUnsupported = false,
            cooldownMs = 0L,
            limit = null,
            todayRequests = 0,
        )
        assertEquals(QuotaState.Exhausted, state)
        assertNotNull(reason)
        assertTrue(reason!!.contains("invalid or expired"))
    }

    @Test
    fun computeQuotaState_modelScopeInsufficientBalance() {
        val (state, reason) = ModelPickerLogic.computeQuotaState(
            isBadKey = false,
            isUnsupported = false,
            cooldownMs = 0L,
            limit = null,
            todayRequests = 5,
            isModelScope = true,
            magicubeBalance = 1.0,
            costPerCall = 5.0,
        )
        assertEquals(QuotaState.Exhausted, state)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Insufficient Magicube balance"))
    }

    // ==========================================
    // 3. Favourites & Recent Models Tests
    // ==========================================

    @Test
    fun favorites_toggleAndPersistence() {
        val prefs = MemoryPrefs()
        val store = ModelPreferencesStore(prefs)

        assertFalse(store.isFavorite("gemini-flash"))
        assertEquals(0, store.getFavorites().size)

        // Add to favorite
        val state1 = store.toggleFavorite("gemini-flash")
        assertTrue(state1)
        assertTrue(store.isFavorite("gemini-flash"))
        assertEquals(setOf("gemini-flash"), store.getFavorites())

        // Toggle off
        val state2 = store.toggleFavorite("gemini-flash")
        assertFalse(state2)
        assertFalse(store.isFavorite("gemini-flash"))
        assertEquals(emptySet<String>(), store.getFavorites())
    }

    @Test
    fun recents_recordAndOrder() {
        val prefs = MemoryPrefs()
        val store = ModelPreferencesStore(prefs)

        store.recordRecent("model-1")
        store.recordRecent("model-2")
        store.recordRecent("model-3")

        // Most recent should be at the front: model-3, model-2, model-1
        assertEquals(listOf("model-3", "model-2", "model-1"), store.getRecentModels())

        // Recording existing model should move it to front
        store.recordRecent("model-1")
        assertEquals(listOf("model-1", "model-3", "model-2"), store.getRecentModels())

        // Recording up to max
        store.recordRecent("model-4")
        store.recordRecent("model-5")
        store.recordRecent("model-6")
        val recents = store.getRecentModels()
        assertEquals(5, recents.size) // max 5
        assertEquals("model-6", recents.first())
        assertFalse(recents.contains("model-2")) // oldest trimmed
    }
}
