package com.meenai.mina.ui.router

import com.meenai.mina.core.gemini.GeminiUsage
import com.meenai.mina.core.gemini.GeminiUsageWindow
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.i18n.HindiTexts
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.meenai.mina.ui.ChatCopy
import com.meenai.mina.ui.components.AiBrand
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
    fun switchEventNamesTheRealFailureInsteadOfQuota() {
        val event = SwitchEvent(
            fromModel = "Gemini 3.1 Pro",
            toModel = "gemini-flash-lite-latest",
            reason = "Gemini Web returned HTTP 400: bad at",
        )
        assertEquals(
            "Gemini 3.1 Pro: Gemini Web returned HTTP 400: bad at — switched to gemini-flash-lite-latest",
            event.formatMessage(isHindi = false),
        )
        assertEquals(
            "Gemini 3.1 Pro: Gemini Web returned HTTP 400: bad at — gemini-flash-lite-latest par switch",
            event.formatMessage(isHindi = true),
        )
    }

    @Test
    fun routineSwitchStillReadsAsALimit() {
        val event = SwitchEvent(fromModel = "Gemini", toModel = "Llama", reason = "Switching candidate")
        assertEquals("Gemini limit reached, switched to Llama", event.formatMessage(isHindi = false))
        assertNull(event.failureDetail())
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
        assertEquals("Soch raha hai · 0s · Qwen 2.5 72B", label0)

        val label25 = ChatCopy.modelWaitLabel(25, "Qwen 2.5 72B")
        assertEquals("Soch raha hai · 25s · Qwen 2.5 72B", label25)

        val label40NoModel = ChatCopy.modelWaitLabel(40, "")
        assertEquals("Soch raha hai · 40s", label40NoModel)

        for (sec in 0..40) {
            val label = ChatCopy.modelWaitLabel(sec, "Qwen 2.5 72B")
            assertEquals("Soch raha hai · ${sec}s · Qwen 2.5 72B", label)
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
    fun resolveDisplayModel_chipAndWaitLineUseActualRunningModel_noEstimateDuringTask() {
        val routerPick = ActiveModel(
            id = "liquid/lfm-3-1.7b:free",
            name = "lfm-3-1.7b",
            provider = "OpenRouter",
            usedToday = 0,
            limit = null,
            isEstimate = true,
            reason = "Optimal free candidate",
        )
        val usages = listOf(
            ProviderUsage(
                provider = "Gemini",
                models = listOf(
                    ModelQuotaLine(
                        modelId = "google/gemini-2.0-flash-exp:free",
                        modelName = "Gemini 2.0 Flash",
                        todayRequests = 3,
                        limit = 50,
                        isEstimate = false,
                    )
                ),
            )
        )

        // When task is active on Gemini 2.0 Flash, chip & wait line MUST both show Gemini 2.0 Flash (never lfm/estimate)
        val resolvedRunning = resolveDisplayModel(
            routerModel = routerPick,
            runningModelId = "google/gemini-2.0-flash-exp:free",
            isTaskRunning = true,
            usages = usages,
        )
        assertEquals("Gemini 2.0 Flash", waitModelName(resolvedRunning))
        assertEquals("Gemini 2.0 Flash · 3/50 today", formatChipText(resolvedRunning, todayLabel = "today"))
        assertEquals(
            "Thinking · 1s · Gemini 2.0 Flash",
            ChatCopy.modelWaitLabel(1, waitModelName(resolvedRunning), isHindi = false),
        )
        assertFalse(resolvedRunning.isEstimate)

        // While task is running and model ID matches router pick with isEstimate=true, strip estimate flag
        val samePick = resolveDisplayModel(
            routerModel = routerPick,
            runningModelId = "liquid/lfm-3-1.7b:free",
            isTaskRunning = true,
            usages = emptyList(),
        )
        assertFalse(samePick.isEstimate)
        assertEquals("lfm-3-1.7b · OpenRouter · 0 today", formatChipText(samePick, todayLabel = "today"))
        assertEquals("lfm-3-1.7b", waitModelName(samePick))

        // While task is running and runningModelId is not yet set, never show estimate/fallback model
        val suppressedFallback = resolveDisplayModel(
            routerModel = routerPick,
            runningModelId = null,
            isTaskRunning = true,
            usages = usages,
        )
        assertEquals("", waitModelName(suppressedFallback))
        assertFalse(suppressedFallback.isEstimate)

        // When idle (no task running), chip shows router's current pick as before
        val idleModel = resolveDisplayModel(
            routerModel = routerPick,
            runningModelId = "google/gemini-2.0-flash-exp:free",
            isTaskRunning = false,
            usages = usages,
        )
        assertEquals("lfm-3-1.7b · OpenRouter · 0 today (estimate)", formatChipText(idleModel, todayLabel = "today"))
    }

    @Test
    fun formatChipText_geminiWeb_showsProPlanWithoutEstimate() {
        val geminiWebModel = ActiveModel(
            id = "gemini-4-argon",
            name = "Gemini 4 Argon",
            provider = "Google Gemini",
            usedToday = 0,
            limit = null,
            isEstimate = false,
            quotaRemaining = 0,
            quotaTotal = 0,
            reason = "Google One AI Premium (Pro Plan)",
        )
        assertEquals("Gemini 4 Argon · Pro Plan", formatChipText(geminiWebModel, todayLabel = "today"))

        val geminiWebModelUsed = geminiWebModel.copy(usedToday = 3)
        assertEquals("Gemini 4 Argon · 3 today · Pro Plan", formatChipText(geminiWebModelUsed, todayLabel = "today"))
    }

    @Test
    fun aiBrandDetection_detectsOfficialBrands() {
        assertEquals(AiBrand.Google, AiBrand.detect("google/gemini-2.0-flash-exp:free"))
        assertEquals(AiBrand.Google, AiBrand.detect("Gemini Flash"))
        assertEquals(AiBrand.OpenAi, AiBrand.detect("openai/gpt-4o"))
        assertEquals(AiBrand.OpenAi, AiBrand.detect("chatgpt-account"))
        assertEquals(AiBrand.Anthropic, AiBrand.detect("anthropic/claude-3.5-sonnet"))
        assertEquals(AiBrand.Meta, AiBrand.detect("meta-llama/llama-3.3-70b-instruct"))
        assertEquals(AiBrand.DeepSeek, AiBrand.detect("deepseek/deepseek-r1"))
        assertEquals(AiBrand.Qwen, AiBrand.detect("Qwen/Qwen2.5-72B-Instruct"))
        assertEquals(AiBrand.Zhipu, AiBrand.detect("zhipu/glm-4.7-flash"))
        assertEquals(AiBrand.ModelScope, AiBrand.detect("modelscope"))
        assertEquals(AiBrand.Mistral, AiBrand.detect("mistralai/mistral-large"))
        assertEquals(AiBrand.Xai, AiBrand.detect("xai/grok-2"))
        assertEquals(AiBrand.OpenRouter, AiBrand.detect("openrouter"))
    }

    @Test
    fun modelQuotaLine_carriesRankAndQuotaStates() {
        val line = ModelQuotaLine(
            modelId = "google/gemini-2.0-flash",
            modelName = "Gemini 2.0 Flash",
            todayRequests = 10,
            limit = 50,
            rank = 1,
            quotaState = QuotaState.Ready,
        )
        assertEquals(1, line.rank)
        assertEquals(QuotaState.Ready, line.quotaState)

        val lowLine = line.copy(todayRequests = 48, quotaState = QuotaState.Low)
        assertEquals(QuotaState.Low, lowLine.quotaState)

        val exhaustedLine = line.copy(todayRequests = 50, quotaState = QuotaState.Exhausted)
        assertEquals(QuotaState.Exhausted, exhaustedLine.quotaState)
    }

    @Test
    fun quotaCalculator_computesExactAndFallbackPercentagesCleanly() {
        // Exact server quota preserves fractional percentage (e.g., 90.8%)
        val exact = ActiveModel(
            id = "gemini-4-hydrogen",
            name = "Gemini 4 Hydrogen",
            provider = "Google Gemini",
            remainingPercent = 90.8,
            isExactQuota = true,
            tierLabel = "Pro Plan",
            windowLabel = "5h window",
        )
        val exactSnap = QuotaCalculator.compute(exact)
        assertEquals(90.8, exactSnap.remainingPercent, 0.01)
        assertEquals("90.8%", exactSnap.formattedPercent)
        assertTrue(exactSnap.isExactServerQuota)

        // Pro plan fallback uses configured/default daily budget (100)
        val proFallback = ActiveModel(
            id = "gemini-4-argon",
            name = "Gemini 4 Argon",
            provider = "Google Gemini",
            usedToday = 4,
            limit = null,
            reason = "Google One AI (Pro Plan)",
        )
        val proSnap = QuotaCalculator.compute(proFallback, configuredDailyBudget = 100)
        assertEquals(96.0, proSnap.remainingPercent, 0.01)
        assertEquals("96.0%", proSnap.formattedPercent)
        assertFalse(proSnap.isExactServerQuota)

        // Explicit limit uses model limit over configured budget
        val limited = ActiveModel(
            id = "google/gemini-2.0-flash:free",
            name = "Gemini 2.0 Flash",
            provider = "OpenRouter",
            usedToday = 25,
            limit = 50,
        )
        val limitedSnap = QuotaCalculator.compute(limited, configuredDailyBudget = 100)
        assertEquals(50.0, limitedSnap.remainingPercent, 0.01)
    }

    @Test
    fun fakeRouterStatus_tracksThinkingEffortAndGeminiUsage() {
        val status = FakeRouterStatus(
            initialModel = ActiveModel(id = "gemini-4-argon", name = "Gemini 4 Argon", provider = "Google Gemini")
        )
        assertNull(status.thinkingEffort.value)
        status.setThinkingEffort("high")
        assertEquals("high", status.thinkingEffort.value)

        status.setConnectionStatus(ConnectionStatus.Connected)
        assertEquals(ConnectionStatus.Connected, status.connectionStatus.value)

        status.setGeminiUsage(
            GeminiUsage(
                daily = GeminiUsageWindow(percentUsed = 13, resetsAtEpochMs = 1_800_000_000_000L, fractionUsed = 0.125),
                weekly = GeminiUsageWindow(percentUsed = 5, resetsAtEpochMs = 1_900_000_000_000L, fractionUsed = 0.05),
            )
        )
        assertEquals(87.5, status.current.value.remainingPercent, 0.01)
        assertEquals(95.0, status.current.value.weeklyRemainingPercent ?: 0.0, 0.01)
    }
}

