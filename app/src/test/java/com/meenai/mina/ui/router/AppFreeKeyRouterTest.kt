package com.meenai.mina.ui.router

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelAccessErrors
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.core.router.RouterAction
import com.meenai.mina.core.router.RouterCandidate
import com.meenai.mina.core.router.RouterErrorClassifier
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.router.picker.AutoModelPicker
import com.meenai.mina.core.router.picker.ModelRole
import com.meenai.mina.settings.AesGcmKeyCipher
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class AppFreeKeyRouterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cipherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        MagicubeLedger.detachStorage()
        MagicubeLedger.clear()
        MagicubeLedger.recordDailyLogin()
    }

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
        assertNull("Candidates empty hone par pin disabled rehna chahiye", router.pinnedModelId)
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
        assertFalse("ModelScope ab permanent backup nahi hai, dynamic compete karta hai", msCandidates.all { it.isBackup })

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

        // Z.ai ka limit provider nahi batata, isliye counter + estimate label
        val chipTextHindi = formatChipText(active, todayLabel = "aaj")
        assertEquals("GLM-4.7 Flash · Z.ai · 0 aaj (anumaan)", chipTextHindi)

        val chipTextEnglish = formatChipText(active, todayLabel = "today")
        assertEquals("GLM-4.7 Flash · Z.ai · 0 today (estimate)", chipTextEnglish)

        // Jab limit pata ho (jaise 12/50)
        val activeWithLimit = active.copy(usedToday = 12, limit = 50, isEstimate = false)
        assertEquals("GLM-4.7 Flash · Z.ai · 12/50 today", formatChipText(activeWithLimit, todayLabel = "today"))
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
        val p = store.connect(
            name = "ModelScope",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://api-inference.modelscope.cn/v1",
            apiKey = null,
            models = listOf(ListedModel("Qwen/Qwen2.5-72B-Instruct")),
        )

        // Key na hone par candidates khaali
        val candidatesBefore = AppFreeKeyRouter.createCandidates(store)
        assertTrue("Key na hone par candidates khaali hone chahiye", candidatesBefore.isEmpty())

        // Ab key update karo
        store.updateProviderKey(p.id, "ms-valid-key")

        // Key save hone ke baad candidates banne chahiye
        val candidatesAfter = AppFreeKeyRouter.createCandidates(store)
        assertFalse("Key save hone ke baad candidates banne chahiye", candidatesAfter.isEmpty())
        assertTrue(candidatesAfter.any { it.provider == "modelscope" && it.id == "Qwen/Qwen2.5-72B-Instruct" })
    }

    @Test
    fun modelScopeNoProviderSupported_aglaModelNoCrash() = runTest {
        val errorMsg = "Model deepseek-ai/DeepSeek-V4-Pro has no provider supported"
        val model1 = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                throw ModelError.ModelNotAvailable("deepseek-ai/DeepSeek-V4-Pro", errorMsg)
            }
        }
        val model2 = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                return ModelReply(listOf(Part.Text("success from qwen")), StopReason.EndTurn, null)
            }
        }

        val c1 = RouterCandidate(
            id = "deepseek-ai/DeepSeek-V4-Pro",
            provider = "modelscope",
            model = model1,
            curatedRank = 10,
        )
        val c2 = RouterCandidate(
            id = "Qwen/Qwen2.5-72B-Instruct",
            provider = "modelscope",
            model = model2,
            curatedRank = 5,
        )

        val router = FreeKeyRouter(listOf(c1, c2), delayMs = {})
        val userMsg = listOf(Message(Role.User, listOf(Part.Text("test query")), Author.User))

        // completeStreaming catches 400 not available, marks unsupported, and seamlessly switches to c2
        var replyText = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList()) {
            replyText = it
        }

        assertEquals("Qwen/Qwen2.5-72B-Instruct", router.activeCandidate?.id)
        assertTrue(router.cooldownStore.isUnsupported("deepseek-ai/DeepSeek-V4-Pro"))

        // Also verify ModelAccessErrors.of and RouterErrorClassifier.classify
        val modelError = ModelAccessErrors.of(400, errorMsg, "deepseek-ai/DeepSeek-V4-Pro")
        assertTrue(modelError is ModelError.ModelNotAvailable)
        val action = RouterErrorClassifier.classify(modelError!!, status = 400, body = errorMsg)
        assertEquals(RouterAction.SWITCH, action)
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
        store.connect(
            name = "OpenRouter",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-test",
            models = listOf(
                ListedModel("google/gemini-2.0-flash-exp:free"),
                ListedModel("meta-llama/llama-3.3-70b-instruct:free"),
            ),
        )

        // Z.ai
        store.connect(
            name = "Z.ai",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://api.z.ai/v1",
            apiKey = "sk-zai-test",
            models = listOf(
                ListedModel("glm-4.7-flash"),
                ListedModel("glm-4.6v-flash"),
            ),
        )

        // ModelScope
        store.connect(
            name = "ModelScope",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://api-inference.modelscope.cn/v1",
            apiKey = "sk-ms-test",
            models = listOf(
                ListedModel("Qwen/Qwen2.5-72B-Instruct"),
                ListedModel("Qwen/Qwen2.5-7B-Instruct"),
            ),
        )

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
    fun limitNaMile_estimateLabel_fakeOpenRouterKey_sahiUsedAurLimit() {
        val store = createTestStore()
        store.connect(
            name = "OpenRouter",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-test",
            models = listOf(ListedModel("google/gemini-2.0-flash-exp:free")),
        )

        val candidates = AppFreeKeyRouter.createCandidates(store)
        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(
            router = router,
            store = store,
        )

        // Pehle without fake key response (initial before refresh)
        val initialUsage = realStatus.usage.value.first { it.provider == "OpenRouter" }
        val initialModel = initialUsage.models.first()
        assertTrue("Limit na hone par estimate true hona chahiye", initialModel.isEstimate)
        assertNull("Limit na milne par limit null hona chahiye", initialModel.limit)

        // Directly set fake OpenRouter key info
        realStatus.setOpenRouterKeyInfo(
            OpenRouterKeyInfo(
                usage = 12,
                limit = 50,
                isFreeTier = true,
            )
        )

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
        assertEquals("Gemini 2.0 Flash · OpenRouter · 12/50 today", chipText)

        val activeStandalone = ActiveModel(
            id = "qwen-3.5",
            name = "Qwen3.5",
            provider = "",
            usedToday = 12,
            limit = 50,
            isEstimate = false,
        )
        assertEquals("Qwen3.5 · 12/50 today", formatChipText(activeStandalone, todayLabel = "today"))
    }

    @Test
    fun modelScope_magicubeLedgerSyncBalance_updatesUsageAndBalance() {
        MagicubeLedger.clear()
        val store = createTestStore()
        store.connect(
            name = "ModelScope",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://api-inference.modelscope.cn/v1",
            apiKey = "ms-test-key",
            models = listOf(ListedModel("Qwen/Qwen2.5-72B-Instruct")),
        )

        // User syncs site balance to 150.0
        MagicubeLedger.syncBalance(150.0)
        assertEquals(150.0, MagicubeLedger.totalBalance(), 0.01)

        val candidates = AppFreeKeyRouter.createCandidates(store)
        val router = FreeKeyRouter(candidates)
        val realStatus = RealRouterStatus(router = router, store = store)
        val usages = realStatus.usage.value
        val msUsage = usages.firstOrNull { it.provider == "ModelScope" }

        org.junit.Assert.assertNotNull(msUsage)
        assertEquals(150.0, msUsage?.magicubeBalance ?: 0.0, 0.01)
    }

    @Test
    fun imageEditModel_excludedFromModelScopeChatAndNoVisionRole() {
        val edit1 = ListedModel("Qwen/Qwen-Image-Edit", vision = true)
        val edit2 = ListedModel("MusePublic/Qwen-Image-Edit", vision = true)
        val valid = ListedModel("Qwen/Qwen2.5-VL-7B-Instruct", vision = true)

        assertFalse("Qwen-Image-Edit must not be chat model", AppFreeKeyRouter.isModelScopeChatModel(edit1))
        assertFalse("MusePublic/Qwen-Image-Edit must not be chat model", AppFreeKeyRouter.isModelScopeChatModel(edit2))
        assertTrue("Qwen2.5-VL must be chat model", AppFreeKeyRouter.isModelScopeChatModel(valid))

        val candidates = AppFreeKeyRouter.createCandidates(
            openRouterKey = null,
            zaiKey = null,
            modelScopeKey = "test-key",
            modelScopeModels = listOf(edit1, edit2, valid),
        )

        // Only valid chat model should be created
        assertEquals(1, candidates.size)
        assertEquals("Qwen/Qwen2.5-VL-7B-Instruct", candidates[0].id)
        assertTrue(candidates[0].roles.contains(ModelRole.Vision))
    }

    @Test
    fun autoPopulateSettings_userKaChunaModelNaBadle_khaliRolesBhare() {
        val store = createTestStore()
        val p = store.connect(
            name = "OpenRouter",
            kind = ProviderConfig.Kind.OpenAiCompat,
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-test",
            models = listOf(
                ListedModel("meta-llama/llama-3.3-70b-instruct:free", toolCalls = true, vision = false, contextTokens = 64_000),
                ListedModel("meta-llama/llama-3.1-8b-instruct:free", toolCalls = true, vision = false, contextTokens = 64_000),
                ListedModel("google/gemini-2.0-flash-exp:free", toolCalls = true, vision = true, contextTokens = 128_000),
            ),
        )

        // Case 1: All roles empty (main == null, light == SameAsMain, vision == Off)
        assertNull(store.roles.value.main)
        assertEquals(RoleChoice.SameAsMain, store.roles.value.light)
        assertEquals(RoleChoice.Off, store.roles.value.vision)

        val picker = AutoModelPicker(apiClient = { "[]" })
        AppFreeKeyRouter.autoPopulateSettingsRoles(store, picker)

        // Main should be auto-filled with 70b
        val autoMain = store.roles.value.main
        assertNotNull("Main role auto-populate hona chahiye", autoMain)
        val mainConfig = store.configs.value.first { it.id == autoMain }
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", mainConfig.model)

        // Light should be auto-filled with 8b
        val autoLight = store.roles.value.light
        assertTrue("Light role auto-populate hona chahiye", autoLight is RoleChoice.Model)
        val lightConfig = store.configs.value.first { it.id == (autoLight as RoleChoice.Model).configId }
        assertEquals("meta-llama/llama-3.1-8b-instruct:free", lightConfig.model)

        // Vision should be auto-filled with gemini flash
        val autoVision = store.roles.value.vision
        assertTrue("Vision role auto-populate hona chahiye", autoVision is RoleChoice.Model)
        val visionConfig = store.configs.value.first { it.id == (autoVision as RoleChoice.Model).configId }
        assertEquals("google/gemini-2.0-flash-exp:free", visionConfig.model)

        // Case 2: User manually changes Main to a specific model -> autoPopulate must NOT overwrite it
        val customModel = "my-custom-model"
        store.use(com.meenai.mina.core.model.ModelRole.Main, p.id, customModel, Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit))
        val userChosenMainId = store.roles.value.main
        assertNotNull(userChosenMainId)
        val userChosenConfig = store.configs.value.first { it.id == userChosenMainId }
        assertEquals(customModel, userChosenConfig.model)

        // Run auto-populate again
        AppFreeKeyRouter.autoPopulateSettingsRoles(store, picker)

        // User's chosen model must remain untouched!
        assertEquals("User ka chuna model nahi badalna chahiye", userChosenMainId, store.roles.value.main)
        assertEquals(customModel, store.configs.value.first { it.id == store.roles.value.main }.model)
    }

    @Test
    fun modelScopeLowBalance_fallbackReasonAndStatus() {
        val msCand = RouterCandidate(
            id = "Qwen/Qwen2.5-72B-Instruct",
            provider = "modelscope",
            model = object : ChatModel {
                override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
            },
            curatedRank = 90,
        )
        val orCand = RouterCandidate(
            id = "meta-llama/llama-3.3-70b-instruct:free",
            provider = "openrouter",
            model = object : ChatModel {
                override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
            },
            curatedRank = 80,
        )

        // Balance is low (< 5.0)
        MagicubeLedger.syncBalance(2.0)
        assertFalse(MagicubeLedger.canAffordModelScope())

        val router = FreeKeyRouter(listOf(msCand, orCand))
        router.resetTask()
        val realStatus = RealRouterStatus(router)

        // Best candidate should fall back to OpenRouter
        val picked = router.pickBestCandidate()
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", picked?.id)

        // Reason should reflect ModelScope low balance fallback
        val reason = router.determineReason(picked)
        assertEquals("ModelScope low balance, OpenRouter fallback", reason)

        // Active model in realStatus must NOT say "No model available"
        val active = realStatus.current.value
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", active.id)
        assertEquals("ModelScope low balance, OpenRouter fallback", active.reason)

        // In usage list, ModelScope models must have status LowBalance
        val msUsage = realStatus.usage.value.find { it.provider.equals("ModelScope", ignoreCase = true) }
        assertNotNull(msUsage)
        assertTrue("ModelScope model status should be LowBalance", msUsage!!.models.all { it.status == ModelStatusKind.LowBalance })
    }

    @Test
    fun configuredMainModel_triedFirstWhenAvailable() {
        val cand1 = RouterCandidate(
            id = "model-high-rank",
            provider = "openrouter",
            model = object : ChatModel {
                override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
            },
            curatedRank = 99,
        )
        val cand2 = RouterCandidate(
            id = "model-user-main",
            provider = "openrouter",
            model = object : ChatModel {
                override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
            },
            curatedRank = 50,
        )

        val router = FreeKeyRouter(
            candidates = listOf(cand1, cand2),
            configuredMainModelProvider = { "model-user-main" },
        )

        // For ModelRole.Main, user's configured main model should be picked even if another model has higher rank
        val picked = router.pickBestCandidate(ModelRole.Main)
        assertEquals("model-user-main", picked?.id)
        assertEquals("Configured main model", router.determineReason(picked))
    }

    @Test
    fun pretrainedAndTinyModels_excludedFromModelScopeChat() {
        val ernie = ListedModel("baidu/ERNIE-4.5-0.3B-PT")
        assertFalse("ERNIE-4.5-0.3B-PT must be excluded", AppFreeKeyRouter.isModelScopeChatModel(ernie))

        val qwenBase = ListedModel("Qwen/Qwen2.5-7B-Base")
        assertFalse("Base model must be excluded", AppFreeKeyRouter.isModelScopeChatModel(qwenBase))

        val qwenInstruct = ListedModel("Qwen/Qwen2.5-72B-Instruct")
        assertTrue("Instruct model must be allowed", AppFreeKeyRouter.isModelScopeChatModel(qwenInstruct))
    }
}
