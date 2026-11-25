package com.meenai.mina.ui

import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ListFailure
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelEndpoint
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelHttp
import com.meenai.mina.core.model.ModelListResult
import com.meenai.mina.core.model.ModelProtocol
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.settings.AesGcmKeyCipher
import com.meenai.mina.settings.ClientSetup
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import com.meenai.mina.core.gemini.GeminiWebSession

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @get:Rule val texts = EnglishTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** The results of the column model are given by address; an empty table is given for addresses not written. */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private var ids = 0

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Mina/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, _ -> listCalls += Triple(kind, url, apiKey); listing[url] ?: ModelListResult.Ok(emptyList()) },
    )

    /** The built model (model name, request header, etc.); what is returned by the trial call of "Test and Save" is determined by [reply], and the trial tool is called by default. */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler))

    private val deepseekUrl = "https://api.deepseek.com"
    private val kimiUrl = "https://api.moonshot.cn/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun testAndSaveStoresProviderAndPicksFirstModelAsMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        runCurrent()
        assertTrue("还没有服务商时表单展开", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        // Automatically select the first model as the main model (only this configuration is saved)
        assertEquals(listOf("deepseek-chat"), store.list().map { it.model })
        assertEquals(store.list().single().id, store.roles.value.main)
        val s = vm.state.value
        assertEquals(listOf(p), s.providers)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    /** After the models are listed, let the one that will be the main model call the tool once: bring a tool without parameters, the upper limit of the reply is enough for one call, and the client ID; save only if successful. */
    @Test fun testAndSaveSendsOneTinyRequestToTheMainCandidate() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val (model, setup) = built.first()
        assertEquals("deepseek-chat", model)
        assertEquals(1024, setup.maxOutputTokens)
        assertEquals("Mina/1.2.3", setup.headers["User-Agent"])
        assertEquals("DeepSeek 没声明会话头", setOf("User-Agent"), setup.headers.keys)
        val (_, messages, tools) = probes.single()
        assertEquals(1, messages.size)
        assertEquals(listOf("get_time"), tools.map { it.name })
        assertEquals(1, store.providers.value.size)
        assertNull(vm.state.value.probeNote)
    }

    /** If the interface works and the model only replies: then try the next one that can be used as the main model (if it says it won't call the tool, don't try it); if it only replies, save the service provider and indefinite main model, and ask the user to choose; after selecting, the reminder is closed. */
    @Test fun modelThatOnlyAnswersTextIsSavedButNotMadeMain() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { ModelReply(listOf(Part.Text("It is noon.")), StopReason.EndTurn, null) }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(3, p.models?.size)
        assertNull("不自动选主模型", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        val s = vm.state.value
        assertFalse("存下了,表单收起", s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), built.map { it.first })
        assertEquals(
            "The provider is connected, but “deepseek-chat” didn’t pass the test: it doesn’t call tools, and Harness Agent needs a model that can call tools. Pick a model from the list that your account has activated.\n" +
                "Tried deepseek-chat, deepseek-reasoner; none of them passed the test.",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(s.probeBeforePick(ModelRole.Vision, p.id))
        assertFalse(s.canNext)
        vm.pick(ModelRole.Main, p.id, "deepseek-reasoner"); runCurrent()
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
    }

    /** Under Kimi's Key, other models are listed first: try your own (kimi) first; if the first one fails, then try the next one. The one that passes will be the master model, and tell which one you tried first that failed. */
    @Test fun notActivatedModelFallsBackToTheNextCandidate() = runTest {
        listing[kimiUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("text-embedding-v4"),
            ListedModel("kimi-k2-preview"), ListedModel("moonshot-v1-8k"),
        ))
        reply = {
            if (built.last().first != "moonshot-v1-8k") throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.")
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("Kimi"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("自家的先试、向量模型不试", listOf("kimi-k2-preview", "moonshot-v1-8k"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("moonshot-v1-8k", store.configFor(ModelRole.Main)?.model)
        assertTrue("moonshot-v1-8k" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "kimi-k2-preview, tried first, didn’t pass the test: this account hasn’t activated this model or has no access to it (the provider says: The product is not activated.). The main model is “moonshot-v1-8k”, which passed.",
            s.probeNote,
        )
    }

    /**
     * Aggregation service provider (many models in a list): The model at the top is downstream (502),
     * but the models behind it can be used. 5xx should not be regarded as unavailable for the entire
     * family. If the model fails, the model will be saved as usual and the model that passes will be
     * used as the main model.
     */
    @Test fun serverErrorOnTheFirstCandidateFallsBackToTheNext() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("meituan/longcat-flash-lite"), ListedModel("deepseek/deepseek-v4.1-flash"),
        ))
        reply = {
            if (built.last().first == "meituan/longcat-flash-lite") throw ModelError.Server(502)
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("meituan/longcat-flash-lite", "deepseek/deepseek-v4.1-flash"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("deepseek/deepseek-v4.1-flash", store.configFor(ModelRole.Main)?.model)
        assertTrue("deepseek/deepseek-v4.1-flash" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "meituan/longcat-flash-lite, tried first, didn’t pass the test: the server returned an error (HTTP 502). The main model is “deepseek/deepseek-v4.1-flash”, which passed.",
            s.probeNote,
        )
    }

    /**
     * The list indicates that a certain model does not use the current interface type (for example, the
     * aggregation service ranks models that only support /messages first): do not try it, just try the
     * next one. Even if you try, it will inevitably fail, and every time you fail, you have to wait
     * until PROBE_TIMEOUT_MS is reached.
     */
    @Test fun modelOnAnotherProtocolIsSkippedWithoutProbing() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("deepseek/deepseek-v4-flash", protocols = listOf(ModelProtocol.ChatCompletions, ModelProtocol.Responses)),
        ))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("deepseek/deepseek-v4-flash"), built.map { it.first })
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.probeNote)
        assertEquals("deepseek/deepseek-v4-flash", store.configFor(ModelRole.Main)?.model)
    }

    /**
     * The list specifies a separate interface for a certain model (see [ListedModel.endpoint]): the call
     * follows its own protocol, regardless of the interface type selected here, and cannot be filtered
     * out just because there is no current interface type in `supported_endpoints`.
     */
    @Test fun modelWithItsOwnEndpointIsProbedWhateverTheKindIs() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel(
                "claude-sonnet-5-5",
                endpoint = ModelEndpoint(ModelProtocol.Messages, "https://api.commandcode.ai/provider/v1"),
                protocols = listOf(ModelProtocol.Messages),
            ),
        ))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("claude-sonnet-5-5"), built.map { it.first })
        assertEquals("claude-sonnet-5-5", store.configFor(ModelRole.Main)?.model)
    }

    /** All the models in the list indicate that they do not use the current interface type: do not try any of them, just save the service provider, and explain why. */
    @Test fun allModelsOnAnotherProtocolSaveWithAReason() = runTest {
        val url = "https://api.commandcode.ai/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("claude-sonnet-5-5", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("claude-sonnet-5", protocols = listOf(ModelProtocol.Messages)),
        ))
        reply = { throw AssertionError("不该试调用:这些模型不走 OpenAI 兼容") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertTrue("不该试调用", built.isEmpty())
        val p = store.providers.value.single()
        val s = vm.state.value
        assertFalse(s.showForm)
        // None of them passed: the service provider still saved it, explained the reasons, and asked the
        // user to choose from the list (perhaps changing the interface type). These two models have not
        // been tried once, so the line "Tried X, Y" cannot appear.
        assertEquals(p.id, s.pickToProbe)
        assertEquals(
            "The provider is connected, but “claude-sonnet-5-5” didn’t pass the test: it isn’t served over the interface type you picked, so the request never reaches it. Pick a model from the list that your account has activated.",
            s.probeNote,
        )
    }

    /**
     * The list does not include `supported_endpoints`, and the top model only uses another set of
     * interfaces: the service provider returns 400 unsupported_model. The adapter has the same set of
     * wrong mappings. This is considered a problem with that model. Then try the next one. The one that
     * passes becomes the master model, and the prompt contains the original words of the service
     * provider.
     */
    @Test fun endpointMismatchOnTheFirstCandidateFallsBackToTheNext() = runTest {
        val url = "https://relay.example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("vendor/shape-a"), ListedModel("vendor/shape-b")))
        val said = "Model 'vendor/shape-a' must be called via /provider/v1/messages."
        reply = {
            val m = built.last().first
            if (m == "vendor/shape-a") {
                throw ModelHttp.openAiError(400, null, """{"error":{"message":"$said","param":"model","code":"unsupported_model"}}""", m)
            }
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("vendor/shape-a", "vendor/shape-b"), built.map { it.first })
        assertEquals("vendor/shape-b", store.configFor(ModelRole.Main)?.model)
        assertEquals(
            "vendor/shape-a, tried first, didn’t pass the test: this account hasn’t activated this model or has no access to it (the provider says: Model 'vendor/shape-a' must be called via /provider/v1/messages.). The main model is “vendor/shape-b”, which passed.",
            vm.state.value.probeNote,
        )
    }

    /** Do both together: filter out those that do not use the current interface type and do not try them; the first remaining one returns 400 with the wrong interface, and then try the next one. */
    @Test fun listedProtocolsFilterFirstThenEndpointMismatchFallsThrough() = runTest {
        val url = "https://relay.example.com/provider/v1"
        listing[url] = ModelListResult.Ok(listOf(
            ListedModel("vendor/messages-only", protocols = listOf(ModelProtocol.Messages)),
            ListedModel("vendor/unknown"),
            ListedModel("vendor/chat", protocols = listOf(ModelProtocol.ChatCompletions)),
        ))
        reply = {
            val m = built.last().first
            if (m == "vendor/unknown") throw ModelHttp.openAiError(400, null, """{"error":{"code":"unsupported_model","param":"model"}}""", m)
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("写明只走 /messages 的不试", listOf("vendor/unknown", "vendor/chat"), built.map { it.first })
        assertEquals("vendor/chat", store.configFor(ModelRole.Main)?.model)
    }

    /**
     * The problem of Key cannot be regarded as "this model cannot use this interface": 401 or 403 means
     * that Key cannot use this interface. The first candidate blocks the entire save, giving the real
     * reason, and nothing is saved.
     */
    @Test fun keyErrorsThatMentionTheEndpointStillBlockTheSave() = runTest {
        val url = "https://relay.example.com/provider/v1"
        for ((status, body) in listOf(
            401 to """{"error":{"message":"Invalid API key","code":"unsupported_model"}}""",
            403 to """{"error":{"message":"Your API key is not supported for this endpoint"}}""",
        )) {
            listing[url] = ModelListResult.Ok(listOf(ListedModel("vendor/a"), ListedModel("vendor/b")))
            built.clear()
            reply = { throw ModelHttp.openAiError(status, null, body, built.last().first) }
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
            assertEquals("HTTP $status:只试第一个", listOf("vendor/a"), built.map { it.first })
            val failed = vm.state.value.form.test as ListState.Failed
            assertTrue(failed.message, failed.message.contains("The API key is invalid"))
            assertTrue(store.providers.value.isEmpty())
            assertNull(vm.state.value.pickToProbe)
        }
    }

    /** The ones I tried were all 5xx: most of them are unavailable (the address is right, but the service is not up), and it still doesn’t exist. */
    @Test fun serverErrorOnEveryCandidateSavesNothing() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("a-model"), ListedModel("b-model"), ListedModel("c-model")))
        reply = { throw ModelError.Server(502) }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("至多试 3 个", listOf("a-model", "b-model", "c-model"), built.map { it.first })
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("HTTP 502"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertNull(store.roles.value.main)
    }

    /** The first one tried was 5xx, and then the next one was Key. Wrong: the previous 5xx did not prove that the Key could be used, and it still did not exist. */
    @Test fun serverErrorThenAuthErrorSavesNothing() = runTest {
        val url = "https://api.qnaigc.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("a-model"), ListedModel("b-model")))
        reply = {
            if (built.last().first == "a-model") throw ModelError.Server(502)
            throw ModelError.Auth()
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("a-model", "b-model"), built.map { it.first })
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("The API key is invalid"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    /** Several of the ones I tried have not been activated: the Key is correct, the service provider is still saved, and the main model is not determined. Please tell me which ones have been tried, and ask the user to select from the list; */
    @Test fun allCandidatesNotActivatedSavesProviderAndAsksToPick() = runTest {
        listing[kimiUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("moonshot-v1-8k"), ListedModel("moonshot-v1-32k"), ListedModel("moonshot-v1-128k"),
        ))
        reply = { throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("Kimi"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("至多试 3 个", listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals("sk", store.providerKey(p.id))
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(
            "The provider is connected, but “moonshot-v1-8k” didn’t pass the test: this account hasn’t activated this model or has no access to it (the provider says: The product is not activated.). Pick a model from the list that your account has activated.\n" +
                "Tried moonshot-v1-8k, moonshot-v1-32k, moonshot-v1-128k; none of them is activated.",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        // The user chooses one: try to call it first. If it is not activated, tell the reason and do not
        // choose.
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "antdigital/Ling-3.1-flash") { picked++ }; runCurrent()
        assertEquals(
            ListState.Failed("This account hasn’t activated “antdigital/Ling-3.1-flash” or has no access to it (the provider says: The product is not activated.). Pick another model, or activate it in the provider’s console."),
            vm.state.value.manualPick[p.id],
        )
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, "moonshot-v1-128k") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertEquals("moonshot-v1-128k", store.configFor(ModelRole.Main)?.model)
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
    }

    /** The first one is that the Key is wrong (or there is something else that is not a model problem): if you don’t try anything else, it won’t save. */
    @Test fun authErrorOnTheFirstCandidateSavesNothing() = runTest {
        listing[kimiUrl] = ModelListResult.Ok(listOf(ListedModel("moonshot-v1-8k"), ListedModel("moonshot-v1-32k")))
        reply = { throw ModelError.Auth() }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("Kimi"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("moonshot-v1-8k"), built.map { it.first })
        assertTrue(vm.state.value.form.test is ListState.Failed)
        assertTrue(store.providers.value.isEmpty())
        assertNull(vm.state.value.pickToProbe)
    }

    /** The trial call fails (for example, the request header required by the service provider is not included): the error reported by the server is shown to others as it is, and nothing is saved. */
    @Test fun failedTinyRequestShowsTheServerErrorAndSavesNothing() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { throw ModelError.BadResponse("HTTP 400 {\"error\":{\"type\":\"MissingSessionID\"}}") }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("MissingSessionID"))
        assertTrue(failed.message.contains("deepseek-chat"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    @Test fun tinyRequestThatNeverAnswersTimesOut() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { awaitCancellation() }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Running, vm.state.value.form.test)
        advanceTimeBy(ModelSetupViewModel.PROBE_TIMEOUT_MS + 1); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("20"))
        assertTrue(store.providers.value.isEmpty())
    }

    /** The session header is declared by default (OpenCode Go): bring it when you try to call it, and the saved service provider will also remember it. */
    @Test fun presetSessionHeaderIsUsedForTheTestAndRemembered() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("glm-x", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        val setup = built.first().second
        assertTrue(setup.headers[go.sessionHeader!!].orEmpty().isNotEmpty())
        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
    }

    /** The model is not listed: do not save or try it first, and ask for a model name; fill it in and then test it, then use it to try to call it. If it is saved as a service provider that does not provide a list, it will be used as the main model and will be recorded as verified. */
    @Test fun noListAsksForAModelAndProbesIt() = runTest {
        val url = "http://192.168.1.5:8000/v1"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.testAndSave(); runCurrent()
        assertTrue(probes.isEmpty())
        assertTrue("没存", store.providers.value.isEmpty())
        val f = vm.state.value.form
        assertTrue(f.noList)
        assertEquals(ListState.Idle, f.test)
        assertFalse("没填模型名不能测试", f.canTest)
        vm.setApiKey("k")
        assertTrue("换 Key 不收起模型名", vm.state.value.form.noList)
        vm.setModel(" qwen3 ")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("qwen3", built.single().first)
        val p = store.providers.value.single()
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(setOf("qwen3"), p.verifiedModels)
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        assertEquals(emptyList<String>(), vm.state.value.unverified(p))
    }

    /** There is no model listed, and the hand-filled model still says that it cannot be found when trying to call it: it does not exist. Please explain clearly which model, address or interface type may be wrong. For example, the address matches the company. */
    @Test fun noListAndModelNotFoundSavesNothing() = runTest {
        val url = "https://generativelanguage.googleapis.com"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("gemini") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        vm.testAndSave(); runCurrent()
        vm.setModel("gemini"); vm.testAndSave(); runCurrent()
        assertEquals(
            ListState.Failed("No models could be listed, and a test call with “gemini” wasn’t found either: the address or interface type is probably wrong, or there is no such model (model names look like: e.g. gemini-flash-latest)."),
            vm.state.value.form.test,
        )
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        // The address has been changed: If you want to re-list, please close the model name first.
        vm.setBaseUrl("$url/")
        assertFalse(vm.state.value.form.noList)
    }

    /** By default (the list interface is normally available) the model is not listed, and the manually filled one cannot be found: it just says that the model does not exist. */
    @Test fun presetWithoutListReportsTheMissingModel() = runTest {
        val kimiPreset = ProviderPresets.all.first { it.label == "Kimi" }
        listing[kimiPreset.baseUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("moonshot-pro") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(kimiPreset.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        assertTrue(vm.state.value.form.noList)
        vm.setModel("moonshot-pro"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed("Model “moonshot-pro” doesn’t exist. Enter the full model ID from the provider’s docs (e.g. deepseek-chat)."), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** Customize the official address of a certain company: it is recommended to use the default of that company; the address is the default, and the interface type is different. Keep the Key when changing. */
    @Test fun officialHostSuggestsThePreset() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.suggestedPreset)
        vm.setBaseUrl("https://generativelanguage.googleapis.com")
        vm.setApiKey("gk")
        val f = vm.state.value.form
        assertEquals("Gemini", f.suggestedPreset?.label)
        assertTrue(f.presetKindMismatch)
        assertEquals(
            "This is Gemini’s official address. Its preset fills in the address and interface type for you. The interface type doesn’t match either: Gemini uses the Gemini interface, but OpenAI-compatible is selected.",
            ModelSetupCopy.presetSuggestion(f),
        )
        assertEquals(ProviderConfig.Kind.Gemini, f.exampleKind)
        // Other paths to the same host (such as its compatible interface): It is still recommended, but
        // it does not say that the interface type is wrong.
        vm.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals("Gemini", vm.state.value.form.suggestedPreset?.label)
        assertFalse(vm.state.value.form.presetKindMismatch)
        vm.setKind(ProviderConfig.Kind.Gemini)
        vm.setBaseUrl("https://api.anthropic.com")
        assertEquals("Anthropic", vm.state.value.form.suggestedPreset?.label)
        assertTrue(vm.state.value.form.presetKindMismatch)
        vm.selectPreset("Anthropic")
        val after = vm.state.value.form
        assertEquals(ProviderConfig.Kind.Anthropic, after.kind)
        assertEquals("gk", after.apiKey)
        assertNull("选了预设就不再建议", after.suggestedPreset)
    }

    /** Manually fill in the model name on the selection page: try to call it first, if it fails, don’t select it and explain the reason; only select it if it succeeds, and it will be recorded as verified. */
    @Test fun manualPickProbesFirst() = runTest {
        val store = store()
        val p = store.connect("192.168.1.5", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        val vm = vm(store)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("gemini") }
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "gemini") { picked++ }
        assertEquals(ListState.Running, vm.state.value.manualPick[p.id])
        runCurrent()
        assertEquals(ListState.Failed("Model “gemini” doesn’t exist. Enter the full model ID from the provider’s docs (e.g. deepseek-chat)."), vm.state.value.manualPick[p.id])
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        // If you only reply without calling tools, you cannot be the main model.
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.pickManual(ModelRole.Main, p.id, "chatty") { picked++ }; runCurrent()
        assertEquals(ListState.Failed("chatty didn’t call the tool. Harness Agent needs a model that can call tools to get things done; pick another main model."), vm.state.value.manualPick[p.id])
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, " qwen3 ") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertNull(vm.state.value.manualPick[p.id])
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("qwen3"), store.provider(p.id)?.verifiedModels)
    }

    /** Service providers saved in the old version that do not provide a list (I have never tried the hand-filled model): reminder. If the "retest" is passed, the reminder disappears and is written down. If it does not work, explain the reason. */
    @Test fun unverifiedManualModelsWarnUntilRetested() = runTest {
        val store = store()
        val p = store.connect("generativelanguage.googleapis.com", ProviderConfig.Kind.OpenAiCompat, "https://generativelanguage.googleapis.com", "k", emptyList())
        store.use(ModelRole.Main, p.id, "gemini", ProviderPresets.custom.capabilities)
        val vm = vm(store)
        runCurrent()
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { throw ModelError.ModelNotFound("gemini") }
        vm.retest(p.id); runCurrent()
        assertEquals(
            ListState.Failed("Model “gemini” doesn’t exist. Enter the full model ID from the provider’s docs (e.g. gemini-flash-latest)."),
            vm.state.value.retest[p.id],
        )
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.retest(p.id); runCurrent()
        assertNull(vm.state.value.retest[p.id])
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.single()))
        // Service providers with lists will not be reminded
        listing[deepseekUrl] = deepseek
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val ds = vm.state.value.providers.first { it.name == "DeepSeek" }
        vm.pick(ModelRole.Light, ds.id, "deepseek-coder"); runCurrent()
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.first { it.name == "DeepSeek" }))
    }

    /** Plain text http: No prompt for local area network or local area network; if you prompt for other addresses, you can still test. */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("This address isn’t encrypted; your API key and chats could be seen by others on the network.", vm.state.value.form.unencryptedWarning)
        vm.setApiKey("k")
        assertTrue(vm.state.value.form.canTest)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[kimiUrl] = ModelListResult.Ok(listOf(ListedModel("kimi-vl", vision = true), ListedModel("moonshot-v1-8k")))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("Kimi")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "Kimi"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("第二家不改主模型", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[kimiUrl] = ModelListResult.Ok(listOf(ListedModel("moonshot-v1-8k"), ListedModel("moonshot-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.openForm(); vm.selectPreset("Kimi"); vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        val kimi = vm.state.value.providers.first { it.name == "Kimi" }
        // The selection page lists all models of all service providers without filtering by
        // capabilities.
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, kimi.id, "moonshot-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("moonshot-plain", vision.model)
        assertEquals(kimi.id, vision.providerId)
        assertTrue("选了看图就记看图", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, kimi.id, "moonshot-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(kimi))
        // Use the main model: Use the main model for viewing pictures, and there is no longer a separate
        // configuration for viewing pictures.
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, kimi.id, "moonshot-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(kimi))
        // Need not
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("没人用的配置删掉", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset("自定义")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("自定义的 OpenAI 兼容接口可以不填 Key", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // The service does not provide a list: a model name is required first, but it is not saved.
        assertTrue(store.providers.value.isEmpty())
        assertFalse(vm.state.value.canNext)
        // The model only responds and does not call tools: save the service provider but not the master
        // model
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.setModel("chatty"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        // Even if the hand-filled chatty is not used as the main model, it will still be there when
        // selecting it.
        assertEquals(listOf("qwen3", "chatty"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("已有的配置保留它的能力", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("失败时保留原来的列表", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.meenai.mina.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.meenai.mina.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("Tool calling · unconfirmed" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("Tool calling" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("No tool calling" to false), ModelSetupCopy.tags(o[2]))
    }

    /** You can customize the service provider's name (remove the leading and trailing blanks); do not fill in the host name of the address. The same address with different names is two families. */
    @Test fun customProviderUsesTheGivenNameOrFallsBackToTheHost() = runTest {
        val url = "https://relay.example.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("m1")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k1")
        assertEquals("relay.example.com", vm.state.value.form.defaultName)
        vm.setName("  我的中转  ")
        vm.testAndSave(); runCurrent()
        assertEquals(listOf("我的中转"), store.providers.value.map { it.name })
        assertEquals("", vm.state.value.form.name)

        vm.openForm()
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k2"); vm.setName("   ")
        vm.testAndSave(); runCurrent()
        val names = store.providers.value.map { it.name }
        assertEquals(listOf("我的中转", "relay.example.com"), names)
        assertEquals(listOf("k1", "k2"), store.providers.value.map { store.providerKey(it.id) })
    }

    /** The name has an upper limit on length; it is cleared when changing to default; the address is preset by a certain company, and the default name is the default name; the default name is not affected by the name in the form. */
    @Test fun customNameIsCappedAndOnlyAppliesToCustom() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setName("名".repeat(ModelSetupViewModel.MAX_NAME + 8))
        assertEquals(ModelSetupViewModel.MAX_NAME, vm.state.value.form.name.length)
        // Cut by character: emoji will not be cut into half
        vm.setName("😀".repeat(ModelSetupViewModel.MAX_NAME + 3))
        val capped = vm.state.value.form.name
        assertEquals(ModelSetupViewModel.MAX_NAME, capped.codePointCount(0, capped.length))
        assertEquals("😀".repeat(ModelSetupViewModel.MAX_NAME), capped)
        vm.setBaseUrl(deepseekUrl)
        assertEquals("DeepSeek", vm.state.value.form.defaultName)
        vm.selectPreset("DeepSeek")
        assertEquals("", vm.state.value.form.name)
        assertEquals("DeepSeek", vm.state.value.form.copy(name = "别的名字").providerName)
    }

    /**
     * The results of manually filling in the model name without looking at the list: the list is drawn
     * (without it), the empty table is listed, there is no list interface, the key is wrong, the server
     * has an error, the response is not JSON, all are only the trial call filled in by hand; the trial
     * call is saved only after the trial call is passed, and it is recorded as manual filling.
     */
    private fun TestScope.typedModelIsProbedWhatever(listed: ModelListResult) {
        val url = "https://relay.example.com/v1"
        listing[url] = listed
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        assertFalse("没点之前不显示模型名", vm.state.value.form.showModel)
        vm.setManual(true)
        assertTrue(vm.state.value.form.showModel)
        vm.setModel(" plan-model ")
        vm.testAndSave(); runCurrent()
        assertEquals("只试手填的那个", listOf("plan-model"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(setOf("plan-model"), p.verifiedModels)
        assertEquals(setOf("plan-model"), p.typedModels)
        assertEquals("plan-model", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.options(p).single { it.id == "plan-model" }.typed)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun typedModelWithAListThatLacksIt() = runTest { typedModelIsProbedWhatever(deepseek) }
    @Test fun typedModelWithAnEmptyList() = runTest { typedModelIsProbedWhatever(ModelListResult.Ok(emptyList())) }
    @Test fun typedModelWithoutAListEndpoint() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.NotSupported)) }
    @Test fun typedModelWhenListingSaysTheKeyIsWrong() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.Auth)) }
    @Test fun typedModelWhenListingHitsAServerError() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.Server, "HTTP 500")) }
    @Test fun typedModelWhenListingIsNotJson() = runTest { typedModelIsProbedWhatever(ModelListResult.Failed(ListFailure.BadResponse)) }

    /** There is an error in the column model and you have no time to fill it in: tell the truth about the reason (if the Key is wrong, just say the Key is wrong), expand the model name column, fill it in and then test again, you still have to try to call it to pass. */
    @Test fun listErrorShowsTheReasonAndOpensTheModelField() = runTest {
        for ((i, failure) in listOf(ListFailure.Auth, ListFailure.Server, ListFailure.BadResponse).withIndex()) {
            val url = "https://relay$i.example.com/v1"
            listing[url] = ModelListResult.Failed(failure)
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
            vm.testAndSave(); runCurrent()
            val f = vm.state.value.form
            assertEquals(ListState.Failed(failure.text), f.test)
            assertTrue("出错后可以手填", f.showModel)
            assertTrue(store.providers.value.isEmpty())
            // The trial call also says that the Key is wrong: it does not exist.
            reply = { throw ModelError.Auth() }
            vm.setModel("plan-model"); vm.testAndSave(); runCurrent()
            assertTrue(vm.state.value.form.test is ListState.Failed)
            assertTrue(store.providers.value.isEmpty())
            reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
            vm.closeForm()
        }
    }

    /** There is a hand-filled one in the list: only try it, do not mark the hand-filled one. */
    @Test fun typedModelThatIsListedIsNotMarkedTyped() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-reasoner"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("deepseek-reasoner"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(emptySet<String>(), p.typedModels)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
        // Close the "manual filling" section so you don't have to fill it in
        vm.openForm(); vm.setApiKey("sk"); vm.setManual(true); vm.setModel("x"); vm.setManual(false)
        assertEquals("", vm.state.value.form.typedModel)
    }

    /** If there is a list on the selection page, you can also fill it in by hand; if you pass it, it will be marked as manual filling. If it is still there after refreshing the list, mark it as "manual filling". */
    @Test fun pickerAllowsTypingAModelForAListedProvider() = runTest {
        val store = store()
        val p = store.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk", deepseek.models)
        val vm = vm(store)
        runCurrent()
        vm.pickManual(ModelRole.Light, p.id, "deepseek-v9"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
        assertEquals("存进文件", setOf("deepseek-v9"), store().provider(p.id)?.typedModels)
        store.rememberModels(p.id, deepseek.models)
        vm.pick(ModelRole.Light, p.id, "deepseek-chat"); runCurrent()
        val options = vm.state.value.options(store.provider(p.id)!!)
        assertTrue(options.single { it.id == "deepseek-v9" }.typed)
        assertFalse(options.single { it.id == "deepseek-chat" }.typed)
        // The items in the selection list are not recorded as manual filling.
        vm.pickManual(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
    }

    // ---------- Don't test, save directly ----------

    /** The network is not connected once: no models are listed, no calls are tried, only the address, key and "not tested" are saved. */
    @Test fun saveWithoutTestStoresProviderWithoutListingOrProbe() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.saveWithoutTest(); runCurrent()
        assertTrue("一次列模型都没调", listCalls.isEmpty())
        assertTrue("一次试调用都没有", built.isEmpty())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals("sk-1", store.providerKey(p.id))
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue("标成没测过", p.savedUntested)
        // The saved file is also there
        assertTrue(store().provider(p.id)!!.savedUntested)
        // There is no main model: it is left to the user to choose (try calling it first when choosing)
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertEquals(p.id, s.pickToProbe)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue("存好后说清它没测过", s.probeNote?.contains(p.name) == true)
    }

    /** If the model name is filled in by hand: it will only be recorded as manually filled in, and will not automatically become the main model. When selecting it as the main model, try calling it first, and then use it after it works. */
    @Test fun saveWithoutTestWithTypedModelDoesNotBecomeMainUntilProbed() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-v9")
        vm.saveWithoutTest(); runCurrent()
        assertTrue(built.isEmpty())
        assertTrue(listCalls.isEmpty())
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-v9"), p.typedModels)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue(p.savedUntested)
        assertNull("没测过的模型不自动当主模型", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        assertEquals(p.id, vm.state.value.pickToProbe)
        // The same goes for reopening the settings (the interface status is gone): skip testing the
        // saved main selection model and try calling it first.
        val again = vm(store)
        runCurrent()
        assertNull(again.state.value.pickToProbe)
        assertTrue(again.state.value.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(again.state.value.probeBeforePick(ModelRole.Light, p.id))
        again.pickManual(ModelRole.Main, p.id, "deepseek-v9"); runCurrent()
        assertEquals(listOf("deepseek-v9"), built.map { it.first })
        assertEquals("deepseek-v9", store.configFor(ModelRole.Main)?.model)
        assertFalse("试通过了就不算没测过", store.provider(p.id)!!.savedUntested)
        assertFalse(again.state.value.probeBeforePick(ModelRole.Main, p.id))
    }

    /** If there is already a main model, skip the test and save another one: the main model remains unchanged. */
    @Test fun saveWithoutTestKeepsTheCurrentMainModel() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val main = store.configFor(ModelRole.Main)
        assertEquals("deepseek-chat", main?.model)
        vm.openForm(); vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://127.0.0.1:11434/v1"); vm.setManual(true); vm.setModel("local-model")
        vm.saveWithoutTest(); runCurrent()
        assertEquals(main, store.configFor(ModelRole.Main))
        assertNull(vm.state.value.pickToProbe)
    }

    /** The address format is incorrect, or the Key is not filled in, and nothing is saved. */
    @Test fun saveWithoutTestRefusesAnInvalidForm() = runTest {
        val store = store()
        val vm = vm(store)
        assertFalse("还没填 Key", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("api.deepseek.com")
        vm.setApiKey("sk")
        assertFalse("地址没写协议", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        // The service you run does not require a Key, you can save it
        vm.setBaseUrl("http://127.0.0.1:11434/v1")
        vm.setApiKey("")
        assertTrue(vm.state.value.form.canSaveUntested)
    }

    /** The one that has already been tested will skip the test and save it again: the service provider will not be repeated, the key will be replaced with a new one, and the verified model will be retained. */
    @Test fun saveWithoutTestKeepsExistingProviderAndItsVerifiedModels() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-chat"), p.verifiedModels)
        assertFalse(p.savedUntested)
        vm.openForm(); vm.setApiKey("sk-2"); vm.saveWithoutTest(); runCurrent()
        assertEquals("不重复添加", 1, store.providers.value.size)
        val after = store.provider(p.id)!!
        assertEquals("sk-2", store.providerKey(p.id))
        assertEquals("列过的列表留着", listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder"), after.models!!.map { it.id })
        assertEquals(setOf("deepseek-chat"), after.verifiedModels)
        assertFalse("核实过的模型让这家不算没测过", after.savedUntested)
    }

    @Test fun geminiWebSignInFlowControlsStateAndConnectsProvider() = runTest {
        val store = store()
        val vm = vm(store)
        runCurrent()

        // 1. Start sign in
        vm.signInWithGeminiWeb()
        assertTrue(vm.state.value.geminiWebLoginOpen)
        assertEquals(ListState.Running, vm.state.value.geminiWebSignIn)

        // 2. Cancel sign in
        vm.cancelGeminiWebSignIn()
        assertFalse(vm.state.value.geminiWebLoginOpen)
        assertEquals(ListState.Idle, vm.state.value.geminiWebSignIn)

        // 3. Fail sign in
        vm.signInWithGeminiWeb()
        vm.onGeminiWebLoginFailed("Network timeout")
        assertFalse(vm.state.value.geminiWebLoginOpen)
        assertEquals(ListState.Failed("Network timeout"), vm.state.value.geminiWebSignIn)
        vm.dismissAccountFailure()
        assertEquals(ListState.Idle, vm.state.value.geminiWebSignIn)

        // 4. Successful login captures session and connects provider
        val session = GeminiWebSession(
            cookies = "__Secure-1PSID=test-sid;",
            snlm0e = "test-at",
            cfb2h = "boq_assistant-bard-web-server_2026",
            fdrfje = "123456789",
            email = "user@gmail.com",
            signedIn = true
        )
        vm.onGeminiWebLoginSuccess(session, selfTest = false)
        runCurrent()

        val p = store.geminiWebProvider()
        assertTrue("Provider connected", p != null)
        assertEquals(ProviderConfig.Kind.GeminiWeb, p?.kind)
        assertEquals("user@gmail.com", store.geminiWebSession(p!!.id)?.email)
        assertEquals("gemini-4-argon", store.configFor(ModelRole.Main)?.model)

        val geminiAccount = vm.state.value.geminiWebAccount
        assertEquals("user@gmail.com", geminiAccount?.email)
        assertTrue(geminiAccount?.signedIn == true)

        // 5. Sign out
        vm.signOutGeminiWeb(p.id)
        runCurrent()
        assertNull(store.geminiWebSession(p.id))
        assertFalse(vm.state.value.geminiWebAccount?.signedIn == true)
    }
}
