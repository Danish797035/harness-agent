package com.meenai.mina.ui.router

import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.picker.ModelRole
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppFreeKeyRouterTest {

    @Test
    fun koiKeyNahi_candidatesKhaaliAurCrashNahi() {
        val candidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = null,
            zaiKey = null,
            modelScopeKey = null,
        )
        assertTrue("Koi key na ho to candidates khaali hone chahiye", candidates.isEmpty())

        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(router)

        val current = realStatus.current.value
        assertEquals("", current.id)
        assertEquals("No model", current.name)
        assertEquals(0, current.quotaRemaining)
        assertEquals(0, current.quotaTotal)
        assertTrue("Usage list must be empty", realStatus.usage.value.isEmpty())

        // Ensure task operations do not crash on empty router
        router.resetTask()
        realStatus.pinModel("any-model")
        assertEquals("any-model", router.pinnedModelId)
        realStatus.pinModel(null)
        assertNull(router.pinnedModelId)
    }

    @Test
    fun sirfEkKey_wahiProvider() {
        // Only OpenRouter key
        val orCandidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = "sk-openrouter-test",
            zaiKey = null,
            modelScopeKey = null,
        )
        assertFalse(orCandidates.isEmpty())
        assertTrue("Sirf OpenRouter candidates hone chahiye", orCandidates.all { it.provider == "openrouter" })
        assertTrue("Koi aur provider nahi hona chahiye", orCandidates.none { it.provider == "zai" || it.provider == "modelscope" })

        // Only Z.ai key
        val zaiCandidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = null,
            zaiKey = "sk-zai-test",
            modelScopeKey = null,
        )
        assertFalse(zaiCandidates.isEmpty())
        assertTrue("Sirf Z.ai candidates hone chahiye", zaiCandidates.all { it.provider == "zai" })
        assertTrue("Koi aur provider nahi hona chahiye", zaiCandidates.none { it.provider == "openrouter" || it.provider == "modelscope" })
        assertEquals(2, zaiCandidates.size)

        // Only ModelScope key
        val msCandidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = null,
            zaiKey = null,
            modelScopeKey = "sk-ms-test",
        )
        assertFalse(msCandidates.isEmpty())
        assertTrue("Sirf ModelScope candidates hone chahiye", msCandidates.all { it.provider == "modelscope" })
        assertTrue("Koi aur provider nahi hona chahiye", msCandidates.none { it.provider == "openrouter" || it.provider == "zai" })
        assertTrue("ModelScope backup provider hona chahiye", msCandidates.all { it.isBackup })

        // All three keys
        val allCandidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = "sk-or-test",
            zaiKey = "sk-zai-test",
            modelScopeKey = "sk-ms-test",
        )
        val providers = allCandidates.map { it.provider }.toSet()
        assertEquals(setOf("openrouter", "zai", "modelscope"), providers)
    }

    @Test
    fun toggleOffPar_toastNahi() {
        val prefs = MemoryPrefs()
        val flags = FeatureFlags(prefs)

        // Default On hona chahiye
        assertTrue("Default switch notice On hona chahiye", flags.showSwitchNotice())

        // Toggle Off
        flags.setShowSwitchNotice(false)
        assertFalse("Toggle Off hone ke baad false hona chahiye", flags.showSwitchNotice())

        val event = SwitchEvent(
            fromModel = "GLM-4.7 Flash",
            toModel = "Gemini 2.0 Flash",
            toProvider = "OpenRouter",
        )

        val shouldDisplayToast = flags.showSwitchNotice()
        assertFalse("Toggle Off par toast nahi aana chahiye", shouldDisplayToast)

        // Toggle On karne par toast wapas aana chahiye
        flags.setShowSwitchNotice(true)
        val shouldDisplayToastOn = flags.showSwitchNotice()
        assertTrue("Toggle On par toast aana chahiye", shouldDisplayToastOn)
    }

    @Test
    fun chipAsliStatusDikhaye() {
        val candidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = null,
            zaiKey = "sk-zai-test",
            modelScopeKey = null,
        )
        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(router)

        val active = realStatus.current.value
        assertEquals("glm-4.7-flash", active.id)
        assertEquals("GLM-4.7 Flash", active.name)
        assertEquals("Z.ai", active.provider)
        assertFalse(active.isPinned)

        val chipTextHindi = formatChipText(active, todayLabel = "aaj")
        assertEquals("GLM-4.7 Flash · Z.ai · 38/50 aaj", chipTextHindi)

        val chipTextEnglish = formatChipText(active, todayLabel = "today")
        assertEquals("GLM-4.7 Flash · Z.ai · 38/50 today", chipTextEnglish)
    }

    @Test
    fun pinnedModelHoToRoutingBand() {
        val candidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = "sk-or-test",
            zaiKey = "sk-zai-test",
            modelScopeKey = null,
        )
        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(router)

        // Pin model
        realStatus.pinModel("glm-4.7-flash")
        assertEquals("glm-4.7-flash", router.pinnedModelId)
        assertTrue(realStatus.current.value.isPinned)
        assertEquals("GLM-4.7 Flash", realStatus.current.value.name)

        // When pinned, routing is disabled: pick candidate always returns pinned candidate
        val pickedVision = router.pickBestCandidate(ModelRole.Vision)
        assertEquals("glm-4.7-flash", pickedVision?.id)

        val pickedLight = router.pickBestCandidate(ModelRole.Light)
        assertEquals("glm-4.7-flash", pickedLight?.id)

        // Unpin
        realStatus.pinModel(null)
        assertNull(router.pinnedModelId)
        assertFalse(realStatus.current.value.isPinned)
    }
}
