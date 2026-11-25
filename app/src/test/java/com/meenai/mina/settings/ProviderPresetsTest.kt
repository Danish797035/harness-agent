package com.meenai.mina.settings

import com.meenai.mina.i18n.ChineseTexts
import com.meenai.mina.ui.AddForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    /** The addresses and names are not repeated (according to the address to identify the default); all cloud servers use https, and plaintext http only runs on your own machine. */
    @Test fun urlsAreUniqueAndOnlySelfHostedUsePlainHttp() {
        val withUrl = ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }
        assertEquals(withUrl.size, withUrl.map { it.baseUrl }.distinct().size)
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.label }.distinct().size)
        withUrl.forEach { p ->
            if (p.selfHosted) assertTrue(p.label, p.baseUrl.startsWith("http://127.0.0.1:"))
            else assertTrue(p.label, p.baseUrl.startsWith("https://"))
            assertEquals(p, ProviderPresets.forBaseUrl(p.baseUrl))
        }
    }

    /** For services you run yourself: the address can be changed, the Key does not need to be filled in, and the name still uses the default name; the default cloud address cannot be changed, and the Key is required. */
    @Test fun selfHostedPresetsHaveEditableUrlAndOptionalKey() {
        val ollama = AddForm.of(preset("Ollama")).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(ollama.urlEditable)
        assertFalse(ollama.keyRequired)
        assertTrue("不填 Key 也能测试", ollama.canTest)
        assertEquals("Ollama", ollama.providerName)
        val openAi = AddForm.of(preset("OpenAI"))
        assertFalse(openAi.urlEditable)
        assertTrue(openAi.keyRequired)
        assertFalse(openAi.canTest)
        assertTrue(openAi.copy(apiKey = "sk").canTest)
    }

    /** If the host name matches, you can identify it (regardless of the path); services you run yourself and unrecognized addresses are not counted. */
    @Test fun presetByHost() {
        assertEquals("Gemini", ProviderPresets.forHost("https://generativelanguage.googleapis.com")?.label)
        assertEquals("Gemini", ProviderPresets.forHost(" https://generativelanguage.googleapis.com/v1beta/openai/ ")?.label)
        assertEquals("Anthropic", ProviderPresets.forHost("https://api.anthropic.com/v1")?.label)
        assertEquals("OpenAI", ProviderPresets.forHost("https://api.openai.com")?.label)
        assertEquals("DeepSeek", ProviderPresets.forHost("https://api.deepseek.com/v1")?.label)
        assertNull(ProviderPresets.forHost("https://models.example.com/v1"))
        assertNull(ProviderPresets.forHost("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.forHost("not a url"))
        // Every default that has an address and is not running by itself will recognize itself.
        ProviderPresets.all.filter { it.baseUrl.isNotEmpty() && !it.selfHosted }.forEach {
            assertEquals(it.label, ProviderPresets.forHost(it.baseUrl)?.label)
        }
    }
}
