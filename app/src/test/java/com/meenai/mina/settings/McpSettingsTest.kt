package com.meenai.mina.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings() = McpSettings(tmp.root, AesGcmKeyCipher { key })

    @Test fun offByDefaultAndTheSwitchSurvivesARestart() {
        assertFalse(settings().enabled.value)
        assertEquals(McpSettings.DEFAULT_PORT, settings().port)
        settings().setEnabled(true)
        assertTrue(settings().enabled.value)
        settings().setEnabled(false)
        assertFalse(settings().enabled.value)
    }

    @Test fun unreadableFileMeansOff() {
        tmp.root.resolve("mcp.json").writeText("{oops")
        assertFalse(settings().enabled.value)
    }

    @Test fun tokenIsRandomStableAndStoredEncrypted() {
        val token = settings().token()
        assertTrue(token.length >= 40)
        assertEquals(token, settings().token())
        // The file contains cipher text and the original text cannot be read.
        val raw = tmp.root.resolve("mcp-token.bin").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(token))
        // If you change the key (for example, the keystore is gone after reinstallation) and the old
        // token cannot be read, another one will be generated.
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNotEquals(token, McpSettings(tmp.root, AesGcmKeyCipher { other }).token())
    }

    @Test fun regenerateReplacesTheToken() {
        val s = settings()
        val old = s.token()
        val fresh = s.regenerate()
        assertNotEquals(old, fresh)
        assertEquals(fresh, s.token())
        assertEquals(fresh, settings().token())
    }
}
