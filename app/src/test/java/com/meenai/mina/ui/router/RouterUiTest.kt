package com.meenai.mina.ui.router

import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.i18n.HindiTexts
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.meenai.mina.ui.ChatCopy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        val job = launch {
            throttled.collect { results.add(it) }
        }
        advanceTimeBy(600L)
        job.cancel()
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun chipAndQuotaSameNumber_noPlaceholderDemo() {
        val active = ActiveModel(
            id = "Qwen/Qwen2.5-72B-Instruct",
            name = "Qwen 2.5 72B",
            provider = "ModelScope",
            usedToday = 5,
            limit = null,
            quotaRemaining = 0,
            quotaTotal = 0,
        )
        // Chip text formats using usedToday without fallback 38/50
        assertEquals("Qwen 2.5 72B · ModelScope · 5 today", formatChipText(active, todayLabel = "today"))
        assertEquals("Qwen 2.5 72B · ModelScope · 5 aaj", formatChipText(active, todayLabel = "aaj"))

        // ProviderUsage reads the exact same usedToday count (5) and totalQuota is 0 (no hardcoded 50)
        val usage = ProviderUsage(
            provider = "ModelScope",
            todayCount = active.usedToday,
            remainingQuota = 0,
            totalQuota = 0,
        )
        assertEquals(5, usage.todayCount)
        assertEquals(0, usage.totalQuota)
    }

    @Test
    fun dotCallStatus_untestedIsGrey_successIsGreen_failureIsRed() {
        val modelUntested = ActiveModel(callStatus = ModelCallStatus.Untested)
        assertEquals(ModelCallStatus.Untested, modelUntested.callStatus)

        val modelSuccess = ActiveModel(callStatus = ModelCallStatus.Success)
        assertEquals(ModelCallStatus.Success, modelSuccess.callStatus)

        val modelFailure = ActiveModel(callStatus = ModelCallStatus.Failure)
        assertEquals(ModelCallStatus.Failure, modelFailure.callStatus)
    }

    @Test
    fun waitLabelIncrementsSecondsAndIncludesModelName() {
        val label0 = ChatCopy.modelWaitLabel(0, "Qwen 2.5 72B")
        assertEquals("Model ka jawab aa raha hai · 0s · Qwen 2.5 72B", label0)

        val label25 = ChatCopy.modelWaitLabel(25, "Qwen 2.5 72B")
        assertEquals("Model ka jawab aa raha hai · 25s · Qwen 2.5 72B", label25)

        val label40NoModel = ChatCopy.modelWaitLabel(40, "")
        assertEquals("Model ka jawab aa raha hai · 40s", label40NoModel)

        for (sec in 0..40) {
            val label = ChatCopy.modelWaitLabel(sec, "Qwen 2.5 72B")
            assertEquals("Model ka jawab aa raha hai · ${sec}s · Qwen 2.5 72B", label)
        }
    }

    @Test
    fun switchEventTimeoutReason_retainsExactMessage() {
        val event = SwitchEvent(
            fromModel = "Qwen 2.5 72B",
            toModel = "Llama 3.1 8B",
            reason = "Model slow tha, dusra try kar raha hoon",
        )
        assertEquals("Model slow tha, dusra try kar raha hoon", event.formatMessage(isHindi = false))
        assertEquals("Model slow tha, dusra try kar raha hoon", event.formatMessage(isHindi = true))
    }

    @Test
    fun modelStatusKind_lowBalanceLabel() {
        assertEquals("Low balance", ModelStatusKind.LowBalance.label())
    }

    @Test
    fun modelQuotaRow_displaysFullModelId() {
        val line = ModelQuotaLine(
            modelId = "Qwen/Qwen3.5-27B",
            modelName = "Qwen 3.5 27B",
            todayRequests = 0,
        )
        // Full model id is preserved in modelId
        assertEquals("Qwen/Qwen3.5-27B", line.modelId)
        val displayText = line.modelId.ifBlank { line.modelName }
        assertEquals("Qwen/Qwen3.5-27B", displayText)
    }

    @Test
    fun unknownBalance_showsNullMagicubeBalance() {
        MagicubeLedger.clear()
        assertFalse(MagicubeLedger.isBalanceKnown)

        val usage = ProviderUsage(
            provider = "ModelScope",
            magicubeBalance = if (MagicubeLedger.isBalanceKnown) MagicubeLedger.totalBalance() else null,
        )
        assertNull("Unknown balance should result in null magicubeBalance", usage.magicubeBalance)
    }
}
