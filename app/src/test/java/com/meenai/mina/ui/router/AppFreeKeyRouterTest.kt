package com.meenai.mina.ui.router

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelEndpoint
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.ModelCandidate
import com.meenai.mina.core.router.RouterAction
import com.meenai.mina.core.router.RouterErrorClassifier
import com.meenai.mina.core.router.picker.ModelRole
import com.meenai.mina.settings.AesGcmKeyCipher
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.meenai.mina.settings.Provider
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class AppFreeKeyRouterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cipherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun createTestStore(): ProviderStore = ProviderStore(
        tmp.root,
        AesGcmKeyCipher { cipherKey },
        userAgent = "Mina/Test",
    ) { cfg, _, _, _ ->
        object : ChatModel {
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
        }
    }

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

    @Test
    fun keyProviderKeBaadSaveHo_tabBhiCandidatesBanteHain() {
        val store = createTestStore()
        val provider = Provider(
            id = "p-modelscope",
            name = "ModelScope",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            kind = ProviderConfig.Kind.OpenAiCompat,
            models = listOf(ListedModel("Qwen/Qwen2.5-72B-Instruct")),
        )
        store.saveProvider(provider)

        // Key na hone par candidates khaali
        val candidatesBefore = AppFreeKeyRouter.createCandidates(store)
        assertTrue("Key na hone par candidates khaali hone chahiye", candidatesBefore.isEmpty())

        // Ab key save karo
        store.setKey("p-modelscope", "ms-valid-key")

        // Key save hone ke baad candidates banne chahiye
        val candidatesAfter = AppFreeKeyRouter.createCandidates(store)
        assertFalse("Key save hone ke baad candidates banne chahiye", candidatesAfter.isEmpty())
        assertTrue(candidatesAfter.any { it.provider == "modelscope" && it.id == "Qwen/Qwen2.5-72B-Instruct" })
    }

    @Test
    fun modelScopeNoProviderSupported_aglaModelNoCrash() = runTest {
        val c1 = ModelCandidate(
            id = "deepseek-ai/DeepSeek-V4-Pro",
            provider = "modelscope",
            isFree = true,
            isBackup = true,
            tier = 3,
            contextWindow = 32_000,
        )
        val c2 = ModelCandidate(
            id = "Qwen/Qwen2.5-72B-Instruct",
            provider = "modelscope",
            isFree = true,
            isBackup = true,
            tier = 2,
            contextWindow = 32_000,
        )

        val router = FreeKeyRouter(listOf(c1, c2))
        assertEquals("deepseek-ai/DeepSeek-V4-Pro", router.pickBestCandidate(ModelRole.Main)?.id)

        // HTTP 400 "Model deepseek-ai/DeepSeek-V4-Pro has no provider supported"
        val errorMsg = "Model deepseek-ai/DeepSeek-V4-Pro has no provider supported"
        val action = RouterErrorClassifier.classify(400, errorMsg)
        assertEquals(RouterAction.SWITCH, action)

        val modelError = com.meenai.mina.core.model.ModelAccessErrors.of(400, errorMsg)
        assertTrue(modelError is ModelError.ModelNotAvailable)

        // Record failure in router
        router.recordResult(c1, false, action = action, isNotAvailable = true)

        // Model must be marked unsupported and not available
        assertTrue(router.cooldownStore.isUnsupported("deepseek-ai/DeepSeek-V4-Pro"))

        // Agla pick bina crash ke Qwen/Qwen2.5-72B-Instruct dega
        val nextCandidate = router.pickBestCandidate(ModelRole.Main)
        assertEquals("Qwen/Qwen2.5-72B-Instruct", nextCandidate?.id)
    }

    @Test
    fun candidatesEmpty_chipAsliModelDikhataHai_pinDisabled() {
        val router = FreeKeyRouter(emptyList())

        // Router ke candidates empty hain lekin providers ka fallback model set hai
        val realStatus = RealRouterStatus(
            router = router,
            getFallbackModel = { "qwen-plus" to "DashScope" },
        )

        val current = realStatus.current.value
        assertEquals("qwen-plus", current.id)
        assertEquals("Qwen Plus", current.name)
        assertEquals("DashScope", current.provider)
        assertFalse("Pin must be disabled when router has no candidates", current.canPin)
        assertTrue("Estimate must be true for fallback model", current.isEstimate)

        val chipText = formatChipText(current, todayLabel = "today")
        assertTrue("Chip mein asli model name aana chahiye", chipText.contains("Qwen Plus"))
        assertFalse("Chip mein 'No model' nahi hona chahiye", chipText.contains("No model"))
    }

    @Test
    fun threeProvidersSaved_providerQuotasMeinTeeno_harModelKiLine() {
        val store = createTestStore()
        // OpenRouter
        val pOr = Provider(
            id = "p-openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            kind = ProviderConfig.Kind.OpenAiCompat,
            models = listOf(
                ListedModel("google/gemini-2.0-flash-exp:free"),
                ListedModel("meta-llama/llama-3.3-70b-instruct:free"),
            ),
        )
        store.saveProvider(pOr)
        store.setKey("p-openrouter", "sk-or-test")

        // Z.ai
        val pZai = Provider(
            id = "p-zai",
            name = "Z.ai",
            baseUrl = "https://api.z.ai/v1",
            kind = ProviderConfig.Kind.OpenAiCompat,
            models = listOf(
                ListedModel("glm-4.7-flash"),
                ListedModel("glm-4.6v-flash"),
            ),
        )
        store.saveProvider(pZai)
        store.setKey("p-zai", "sk-zai-test")

        // ModelScope
        val pMs = Provider(
            id = "p-modelscope",
            name = "ModelScope",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            kind = ProviderConfig.Kind.OpenAiCompat,
            models = listOf(
                ListedModel("Qwen/Qwen2.5-72B-Instruct"),
                ListedModel("Qwen/Qwen2.5-7B-Instruct"),
            ),
        )
        store.saveProvider(pMs)
        store.setKey("p-modelscope", "sk-ms-test")

        val candidates = AppFreeKeyRouter.createCandidates(store)
        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(router = router, store = store)

        val usages = realStatus.usage.value
        assertEquals("Provider Quotas mein teeno hone chahiye", 3, usages.size)

        val orUsage = usages.firstOrNull { it.provider == "OpenRouter" }
        val zaiUsage = usages.firstOrNull { it.provider == "Z.ai" }
        val msUsage = usages.firstOrNull { it.provider == "ModelScope" }

        assertTrue("OpenRouter present", orUsage != null)
        assertTrue("Z.ai present", zaiUsage != null)
        assertTrue("ModelScope present", msUsage != null)

        // Har provider ke andar models ki line honi chahiye
        assertTrue("OpenRouter models line present", orUsage!!.models.isNotEmpty())
        assertTrue("Z.ai models line present", zaiUsage!!.models.isNotEmpty())
        assertTrue("ModelScope models line present", msUsage!!.models.isNotEmpty())

        // Z.ai: concurrency 1
        assertEquals(1, zaiUsage.concurrency)

        // ModelScope: Magicube cost per call and total kharcha
        val ms72b = msUsage.models.firstOrNull { it.modelId.contains("72B") }
        val ms7b = msUsage.models.firstOrNull { it.modelId.contains("7B") }
        assertEquals(2.0, ms72b?.costPerCall ?: 0.0, 0.01)
        assertEquals(0.5, ms7b?.costPerCall ?: 0.0, 0.01)
        assertTrue(msUsage.totalCostToday != null)
    }

    @Test
    fun limitNaMile_estimateLabel_fakeOpenRouterKey_sahiUsedAurLimit() = runTest {
        val store = createTestStore()
        val pOr = Provider(
            id = "p-openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            kind = ProviderConfig.Kind.OpenAiCompat,
            models = listOf(ListedModel("google/gemini-2.0-flash-exp:free")),
        )
        store.saveProvider(pOr)
        store.setKey("p-openrouter", "sk-or-test")

        val fakeClient = object : OpenRouterKeyClient {
            override suspend fun getKeyInfo(apiKey: String): OpenRouterKeyInfo {
                return OpenRouterKeyInfo(
                    usage = 12,
                    limit = 50,
                    isFreeTier = true,
                )
            }
        }

        val candidates = AppFreeKeyRouter.createCandidates(store)
        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(
            router = router,
            store = store,
            openRouterKeyClient = fakeClient,
            scope = this,
        )

        // Pehle without fake key response (initial before refresh)
        val initialUsage = realStatus.usage.value.first { it.provider == "OpenRouter" }
        val initialModel = initialUsage.models.first()
        assertTrue("Limit na hone par estimate true hona chahiye", initialModel.isEstimate)
        assertNull("Limit na milne par limit null hona chahiye", initialModel.limit)

        // Refresh triggers OpenRouter key client
        realStatus.refresh()
        advanceUntilIdle()

        // Key client response ke baad: limit = 50, usage = 12, isEstimate = false
        val refreshedUsage = realStatus.usage.value.first { it.provider == "OpenRouter" }
        val refreshedModel = refreshedUsage.models.first()
        assertEquals(50, refreshedModel.limit)
        assertEquals(12, refreshedModel.todayRequests)
        assertFalse(refreshedModel.isEstimate)

        // parseKeyJson verification
        val keyJson = """{"data": {"usage": 12, "limit": 50, "is_free_tier": true}}"""
        val parsed = HttpOpenRouterKeyClient().parseKeyJson(keyJson)
        assertEquals(12, parsed?.usage)
        assertEquals(50, parsed?.limit)
        assertTrue(parsed?.isFreeTier == true)

        val active = ActiveModel(
            id = "google/gemini-2.0-flash-exp:free",
            name = "Gemini 2.0 Flash",
            provider = "OpenRouter",
            usedToday = 12,
            limit = 50,
            isEstimate = false,
        )
        val chipText = formatChipText(active, todayLabel = "today")
        assertEquals("Gemini 2.0 Flash · 12/50 today", chipText)
    }
}
