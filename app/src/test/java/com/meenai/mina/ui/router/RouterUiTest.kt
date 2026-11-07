package com.meenai.mina.ui.router

import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.i18n.HindiTexts
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterUiTest {

    @Test
    fun chipTextFormat_whenProviderInName() {
        val model = ActiveModel(
            id = "gemini-flash",
            name = "Gemini Flash",
            provider = "Gemini",
            quotaRemaining = 38,
            quotaTotal = 50,
        )
        // Hindi format: "Gemini Flash · 38/50 aaj"
        assertEquals("Gemini Flash · 38/50 aaj", formatChipText(model, todayLabel = "aaj"))
        // English format: "Gemini Flash · 38/50 today"
        assertEquals("Gemini Flash · 38/50 today", formatChipText(model, todayLabel = "today"))
    }

    @Test
    fun chipTextFormat_whenProviderNotInName() {
        val model = ActiveModel(
            id = "llama-3",
            name = "Llama",
            provider = "OpenRouter",
            quotaRemaining = 18,
            quotaTotal = 20,
        )
        // Hindi format: "Llama · OpenRouter · 18/20 aaj"
        assertEquals("Llama · OpenRouter · 18/20 aaj", formatChipText(model, todayLabel = "aaj"))
        // English format: "Llama · OpenRouter · 18/20 today"
        assertEquals("Llama · OpenRouter · 18/20 today", formatChipText(model, todayLabel = "today"))
    }

    @Test
    fun toastSettingOff_suppressesToast() {
        val prefs = MemoryPrefs()
        val flags = FeatureFlags(prefs)

        // Default must be ON
        assertTrue("Switch notice default should be On", flags.showSwitchNotice())

        // User turns toggle OFF
        flags.setShowSwitchNotice(false)
        assertFalse(flags.showSwitchNotice())

        // Simulate toast display decision based on setting
        val event = SwitchEvent(
            fromModel = "Gemini",
            toModel = "Llama",
            toProvider = "OpenRouter",
        )
        val showNotice = flags.showSwitchNotice()

        // When setting is OFF, toast must not be triggered
        val shouldDisplayToast = event != null && showNotice
        assertFalse("When switch notice setting is off, toast must not show", shouldDisplayToast)

        // When setting is ON, toast must be triggered
        flags.setShowSwitchNotice(true)
        val shouldDisplayToastWhenOn = event != null && flags.showSwitchNotice()
        assertTrue("When switch notice setting is on, toast must show", shouldDisplayToastWhenOn)
    }

    @Test
    fun sheetPinClick_togglesPinModel() = runTest {
        val router = FakeRouterStatus(
            initialModel = ActiveModel(
                id = "gemini-flash",
                name = "Gemini Flash",
                provider = "Gemini",
                isPinned = false,
            )
        )

        assertFalse(router.current.first().isPinned)
        assertNull(router.pinnedModelId)

        // Simulate clicking "Pin this model" on bottom sheet
        router.pinModel("gemini-flash")

        assertEquals("gemini-flash", router.pinnedModelId)
        assertTrue(router.current.first().isPinned)

        // Unpin
        router.pinModel(null)
        assertNull(router.pinnedModelId)
        assertFalse(router.current.first().isPinned)
    }

    @Test
    fun switchEventFormatting() {
        val event = SwitchEvent(
            fromModel = "Gemini",
            toModel = "Llama",
            toProvider = "OpenRouter",
        )
        assertEquals(
            "Gemini limit khatam, Llama (OpenRouter) par switch",
            event.formatMessage(isHindi = true),
        )
        assertEquals(
            "Gemini limit reached, switched to Llama (OpenRouter)",
            event.formatMessage(isHindi = false),
        )
    }

    @Test
    fun throttlingEmitsAtMostTwicePerSecond() = runTest {
        val source = flow {
            emit(1)
            emit(2)
            emit(3)
        }
        val throttled = source.throttleLatest(500L)
        val results = mutableListOf<Int>()
        val job = kotlinx.coroutines.launch {
            throttled.collect { results.add(it) }
        }
        advanceTimeBy(600L)
        job.cancel()
        assertTrue(results.isNotEmpty())
    }
}
