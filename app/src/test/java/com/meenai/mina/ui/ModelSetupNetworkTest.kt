package com.meenai.mina.ui

import com.meenai.mina.core.model.ListFailure
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.settings.AesGcmKeyCipher
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * "Test and save" real-life column model interface and adapter: custom OpenAI compatible interface,
 * facing a local service that talks back by path. When the list interface receives 404, I get "This
 * company really doesn't provide lists" (the hand-filled model can be adjusted) and "the address and
 * interface type are filled in wrongly" (the call is also 404).
 */
class ModelSetupNetworkTest {
    @get:Rule val texts = EnglishTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Path → returns something; the unwritten path returns to the 404 web page. [chat] provides a callback interface based on the model name in the request. */
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private var chat: ((model: String) -> MockResponse)? = null
    private val chatModels = mutableListOf<String>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                chat?.takeIf { path == "/chat/completions" }?.let { answer ->
                    val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(request.body.readUtf8())?.groupValues?.get(1).orEmpty()
                    synchronized(chatModels) { chatModels += model }
                    return answer(model)
                }
                return routes[path]?.invoke()
                    ?: MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html><body>Not Found</body></html>")
            }
        }
        server.start()
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun toolCall() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
            """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
    )

    private fun setUp(): Pair<ProviderStore, ModelSetupViewModel> {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mina/1.2.3")
        val vm = ModelSetupViewModel(store, scope, io = Dispatchers.IO)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setKind(ProviderConfig.Kind.OpenAiCompat)
        vm.setBaseUrl(base)
        vm.setApiKey("sk")
        return store to vm
    }

    private fun ModelSetupViewModel.testAndWait() = runBlocking {
        testAndSave()
        withTimeout(15_000) { state.first { it.form.test != ListState.Running } }
    }

    @Test fun listAndChatBoth404MeansNothingIsSaved() {
        val (store, vm) = setUp()
        vm.testAndWait()
        assertTrue("先要一个模型名", vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        vm.setModel("gemini")
        vm.testAndWait()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.startsWith("No models could be listed, and a test call with “gemini” wasn’t found either"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test fun list404ButChatWorksSavesANoListProvider() {
        routes["/chat/completions"] = ::toolCall
        val (store, vm) = setUp()
        vm.testAndWait()
        vm.setModel("my-model")
        vm.testAndWait()
        val p = store.providers.value.single()
        assertEquals(emptyList<Any>(), p.models)
        assertEquals(setOf("my-model"), p.verifiedModels)
        assertEquals("my-model", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        // The "verified" written down is still there after re-reading it.
        assertEquals(setOf("my-model"), ProviderStore(tmp.root, AesGcmKeyCipher { key }).provider(p.id)?.verifiedModels)
        assertEquals(listOf("/models", "/models", "/chat/completions"), List(server.requestCount) { server.takeRequest().path })
    }

    /** First in the list are other models: the one that has not been activated calls back 400, and then tries the next one. If it succeeds, save it and use it as the main model. Please tell which one you tried first failed. */
    @Test fun notActivatedFirstModelFallsBackToTheNext() {
        routes["/models"] = ::thirdPartyFirst
        chat = { model -> if (model == "qwen-plus") toolCall() else notActivated() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus"), chatModels)
        val p = store.providers.value.single()
        assertEquals("qwen-plus", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        val note = vm.state.value.probeNote.orEmpty()
        assertTrue(note, note.startsWith("antdigital/Ling-3.1-flash, tried first, didn’t pass the test: this account hasn’t activated this model or has no access to it (the provider says: The product is not activated"))
        assertTrue(note, note.endsWith("The main model is “qwen-plus”, which passed."))
        assertEquals(null, vm.state.value.pickToProbe)
        assertTrue("qwen-plus" in p.verifiedModels)
    }

    /** None of the ones I tried have been activated: the key is correct, save the service provider and indefinite master models, and ask the user to select them from the list. */
    @Test fun noCandidateActivatedSavesProviderWithoutMain() {
        routes["/models"] = ::thirdPartyFirst
        chat = { notActivated() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus", "qwen-max"), chatModels)
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals(null, store.roles.value.main)
        val s = vm.state.value
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeNote.orEmpty(), s.probeNote.orEmpty().startsWith("The provider is connected, but “antdigital/Ling-3.1-flash” didn’t pass the test:"))
        assertTrue(s.probeNote.orEmpty(), s.probeNote.orEmpty().endsWith("Tried antdigital/Ling-3.1-flash, qwen-plus, qwen-max; none of them is activated."))
    }

    /** The account limit of the model being tried first is zero (it says limit: 0 in 429): it is not considered to be stuck in the current limit. Then try the next one. If it passes and is saved, it will become the main model. */
    @Test fun quotaZeroFirstModelFallsBackToTheNext() {
        routes["/models"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[{"id":"paid-only"},{"id":"free-ok"}]}""")
        }
        chat = { model -> if (model == "free-ok") toolCall() else quotaZero() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("paid-only", "free-ok"), chatModels)
        val p = store.providers.value.single()
        assertEquals("free-ok", store.configFor(ModelRole.Main)?.model)
        assertTrue("free-ok" in p.verifiedModels)
        assertFalse(vm.state.value.showForm)
        val note = vm.state.value.probeNote.orEmpty()
        assertTrue(note, note.startsWith("paid-only, tried first, didn’t pass the test: this account hasn’t activated this model or has no access to it (the provider says:"))
        assertTrue(note, note.endsWith("The main model is “free-ok”, which passed."))
    }

    /** Real current limit (429 does not say the quota is zero): The first one failed due to reasons other than the model, and did not save or try the next one. */
    @Test fun plainRateLimitOnTheFirstModelSavesNothing() {
        routes["/models"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[{"id":"a"},{"id":"b"}]}""")
        }
        chat = {
            MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Rate limit reached for requests, limit: 60 per minute"}}""")
        }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("a"), chatModels.distinct())
        assertTrue(vm.state.value.form.test is ListState.Failed)
        assertTrue(store.providers.value.isEmpty())
    }

    private fun quotaZero() = MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody(
        """[{"error":{"code":429,"message":"You exceeded your current quota. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: paid-only","status":"RESOURCE_EXHAUSTED"}}]""",
    )

    private fun thirdPartyFirst() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"object":"list","data":[{"id":"antdigital/Ling-3.1-flash"},{"id":"text-embedding-v4"},{"id":"qwen-plus"},{"id":"qwen-max"}]}""",
    )

    private fun notActivated() = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(
        """{"code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation.","request_id":"r1"}""",
    )

    @Test fun badKeyIsAKeyErrorAndNothingIsSaved() {
        routes["/models"] = { MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}""") }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertFalse("Key 不对不要模型名", vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
    }

    /** Custom selection OpenAI Responses: The column model is still `GET /models`, try calling `/responses` (without touching `/chat/completions`), the saved service provider and main model all have this interface. */
    @Test fun customResponsesListsThenProbesTheResponsesEndpoint() {
        routes["/models"] = {
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"object":"list","data":[{"id":"gpt-x"}]}""")
        }
        routes["/responses"] = {
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            )
        }
        val (store, vm) = setUp()
        vm.setKind(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val p = store.providers.value.single()
        assertEquals(ProviderConfig.Kind.OpenAiResponses, p.kind)
        assertEquals(setOf("gpt-x"), p.verifiedModels)
        val main = store.configFor(ModelRole.Main)
        assertEquals("gpt-x", main?.model)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, main?.kind)
        assertFalse(vm.state.value.showForm)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("/models", "/responses"), requests.map { it.path })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
    }
}
