package com.meenai.mina.capsule.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LiveShareStringsTest {

    private val liveShareKeys = listOf(
        "live_share_title",
        "live_share_desc",
        "live_share_stop",
        "live_share_started",
        "live_share_stopped",
        "live_share_paused_sensitive",
        "live_share_permission_denied",
        "live_share_paused_indicator",
        "channel_live_share",
    )

    private fun loadStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeName == "string") {
                val name = n.attributes?.getNamedItem("name")?.nodeValue ?: continue
                strings[name] = n.textContent
            }
        }
        return strings
    }

    @Test
    fun englishLiveShareStringsAreValid() {
        val engFile = File("src/main/res/values/strings_capsule.xml")
        val fallback = File("app/src/main/res/values/strings_capsule.xml")
        val file = if (engFile.exists()) engFile else fallback
        assertTrue(file.exists())

        val strings = loadStrings(file)
        for (key in liveShareKeys) {
            val text = checkNotNull(strings[key]) { "Key $key missing in English strings" }
            assertTrue("Key $key must not be blank", text.isNotBlank())
            assertFalse("Key $key must not contain 'Mina'", text.contains("Mina", ignoreCase = true))
            assertFalse("Key $key must not contain Chinese", text.any { it in '\u4e00'..'\u9fff' })
        }
    }

    @Test
    fun hindiLiveShareStringsAreValid() {
        val hiFile = File("src/main/res/values-hi/strings_capsule.xml")
        val fallback = File("app/src/main/res/values-hi/strings_capsule.xml")
        val file = if (hiFile.exists()) hiFile else fallback
        assertTrue(file.exists())

        val strings = loadStrings(file)
        for (key in liveShareKeys) {
            val text = checkNotNull(strings[key]) { "Key $key missing in Hindi strings" }
            assertTrue("Key $key must not be blank", text.isNotBlank())
            assertFalse("Key $key must not contain 'Mina'", text.contains("Mina", ignoreCase = true))
            assertFalse("Key $key must not contain Chinese", text.any { it in '\u4e00'..'\u9fff' })
        }
    }
}
