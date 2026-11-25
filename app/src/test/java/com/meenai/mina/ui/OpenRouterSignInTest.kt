package com.meenai.mina.ui

import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.ModelListResult
import com.meenai.mina.core.oauth.OpenRouterAuth
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.settings.AesGcmKeyCipher
import com.meenai.mina.settings.ChatGptSignIns
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * Log in with an OpenRouter account and go through: open the authorization page in the browser (fake),
 * return to the local machine callback with the code, change to Key, press OpenRouter default to save as
 * a service provider, select the main model, open the details; exit and only delete the local Key. The
 * interface for changing Key is a local fake service.
 */
class OpenRouterSignInTest {
    @get:Rule val texts = EnglishTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val listCalls = mutableListOf<Pair<String, String?>>()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        openRouterAuth = OpenRouterAuth(http, keysUrl = server.url("/api/v1/auth/keys").toString()),
        modelLister = { _, url, apiKey, _ ->
            synchronized(listCalls) { listCalls += url to apiKey }
            ModelListResult.Ok(listOf(ListedModel("openai/gpt-x"), ListedModel("anthropic/claude-x")))
        },
    )

    /** Fake browser: When opening the authorization page, request the local callback with code and state as the user agreed; [pages] notes the result displayed in the browser after the callback. */
    private inner class Browser(private val approve: Boolean = true) : SignInBrowser {
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        override fun open(url: String) {
            opened += url
            val u = url.toHttpUrl()
            val callback = u.queryParameter("callback_url")!!.toHttpUrl().newBuilder().host("127.0.0.1")
            if (approve) callback.addQueryParameter("code", "the-code").addQueryParameter("state", u.queryParameter("state"))
            else callback.addQueryParameter("error", "access_denied")
            Thread { http.newCall(Request.Builder().url(callback.build()).build()).execute().close() }.start()
        }
        override fun returnToApp() {}
        override fun page(failure: String?): String { pages += failure; return "<p>ok</p>" }
    }

    private fun ModelSetupViewModel.signInAndWait(provider: AccountProvider) = runBlocking {
        signInWithAccount(provider)
        withTimeout(15_000) { state.first { !it.accountSignIn.running && (it.signedInProvider != null || it.accountSignIn.failure != null) } }
    }

    @Test fun signInStoresAnEncryptedKeyAndOpensTheProvider() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-SECRET"}"""))
        val s = store()
        val browser = Browser()
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        val p = s.openRouterAccount()!!
        assertEquals(p.id, ui.signedInProvider)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals(OpenRouterAuth.API_BASE, p.baseUrl)
        assertEquals("OpenRouter", p.name)
        assertEquals("sk-or-v1-SECRET", s.providerKey(p.id))
        assertEquals(listOf(OpenRouterAuth.API_BASE to "sk-or-v1-SECRET"), listCalls)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertNotNull("还没有主模型时选一个", ui.roles.main)
        assertEquals(listOf<String?>(null), browser.pages)
        assertTrue(browser.opened.single().startsWith("https://openrouter.ai/auth?"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("\"code\":\"the-code\""))
        // Key is only encrypted and stored, not entered into the configuration file.
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("SECRET")) }
        // When I read it again, I still recognized that it was authorized.
        assertEquals(p.id, store().openRouterAccount()?.id)

        vm.signedInShown()
        assertNull(vm.state.value.signedInProvider)
        runBlocking {
            vm.signOutAccount(p.id)
            withTimeout(5_000) { vm.state.first { it.openRouterAccount?.signedIn == false } }
        }
        assertNull(s.providerKey(p.id))
        assertNotNull("服务商和模型留着", s.provider(p.id)?.models)
        assertTrue(vm.state.value.signedOut(s.provider(p.id)!!))
    }

    @Test fun deniedAuthorizationSaysSoAndSavesNothing() {
        val s = store()
        val browser = Browser(approve = false)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)
        assertEquals(AccountProvider.OpenRouter, ui.accountSignIn.provider)
        assertEquals("Sign-in was cancelled.", ui.accountSignIn.failure)
        assertEquals(listOf<String?>("Sign-in was cancelled."), browser.pages)
        assertTrue(s.providers.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    /**
     * The user has already filled in the OpenRouter Key by himself: authorize the login and save it in
     * another key, without deleting the key; log out and only delete the authorized key. Afterwards, I
     * fill in the Key by myself and "test and save", and only update the one I filled in, not the
     * authorized one.
     */
    @Test fun signInKeepsAKeyTheUserTypedForOpenRouter() {
        val s = store()
        val typed = s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed", emptyList())
        val signed = s.connectOpenRouter("sk-or-v1-signed", emptyList())
        assertFalse(typed.id == signed.id)
        assertEquals("sk-typed", s.providerKey(typed.id))
        assertEquals("sk-or-v1-signed", s.providerKey(signed.id))
        assertNull(typed.signIn)
        assertEquals(signed.id, s.openRouterAccount()?.id)
        // Log in again and update the authorized one. There will be no more third one.
        assertEquals(signed.id, s.connectOpenRouter("sk-or-v1-again", emptyList()).id)
        assertEquals(2, s.providers.value.size)
        // Fill it out again Key: Update your own family
        assertEquals(typed.id, s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed-2", emptyList()).id)
        assertEquals("sk-or-v1-again", s.providerKey(signed.id))
        s.signOutOpenRouter(signed.id)
        assertNull(s.providerKey(signed.id))
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // The company that fills in the Key cannot be regarded as logging in or out.
        s.signOutOpenRouter(typed.id)
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // After re-reading, the two families still separated.
        val reloaded = store()
        assertEquals(signed.id, reloaded.openRouterAccount()?.id)
        assertEquals("sk-typed-2", reloaded.providerKey(typed.id))
    }
}
