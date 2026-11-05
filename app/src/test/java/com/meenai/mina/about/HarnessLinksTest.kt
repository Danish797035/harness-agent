package com.meenai.mina.about

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class HarnessLinksTest {
    @get:Rule val texts = ChineseTexts()

    private val device = HarnessLinks.Device("Google", "Pixel 8", "14", 34, "0.1.0", "65fcf92")

    @Test
    fun bodyHasThreePrefilledLines() {
        val lines = HarnessLinks.issueBody(device).lines()
        assertEquals("- Device: Google Pixel 8", lines[0])
        assertEquals("- Android version: 14 (SDK 34)", lines[1])
        assertEquals("- Harness Agent version: 0.1.0 (build 65fcf92)", lines[2])
        assertTrue(lines.contains("### What went wrong"))
        assertTrue(lines.contains("### Steps you took"))
        assertTrue(lines.contains("### What you expected"))
        // 诊断信息不自动带上:只提示用户去哪里复制
        assertTrue(lines.contains("### Diagnostics"))
        assertTrue(lines.contains("(Optional) In Harness Agent, open About › Send feedback › Copy diagnostics, then paste it here"))
    }

    /** 型号里已经带厂商名时不重复写。 */
    @Test
    fun modelNotDoubled() {
        val body = HarnessLinks.issueBody(device.copy(manufacturer = "samsung", model = "SAMSUNG SM-S9180"))
        assertTrue(body.startsWith("- Device: SAMSUNG SM-S9180\n"))
    }

    @Test
    fun issueUrlIsEncoded() {
        val url = HarnessLinks.issueUrl(device)
        assertTrue(url.startsWith("https://github.com/Danish797035/harness-agent/issues/new?title=&body="))
        val query = url.substringAfter('?')
        assertFalse("no raw spaces or newlines", query.any { it == ' ' || it == '\n' || it == '+' })
        assertFalse("no raw non-ASCII", query.any { it.code > 127 })
        // 能被当成合法 URI 解析,正文解码回原样
        URI(url)
        val body = URLDecoder.decode(query.substringAfter("body="), "UTF-8")
        assertEquals(HarnessLinks.issueBody(device), body)
    }
}
