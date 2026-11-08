package com.meenai.mina.capsule

import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests for long-press shortcut menu on the bubble:
 * 1. Exactly 8 shortcut items are present in order:
 *    - Live share
 *    - Screen ke baare mein poochho (Ask about screen)
 *    - Circle to ask
 *    - Voice
 *    - Screen ka Hindi translate (Translate screen to Hindi)
 *    - Page ka summary (Page summary)
 *    - Screen se text copy (Copy text from screen)
 *    - Task rok do (Stop task)
 * 2. User-facing strings exist in English and Hindi.
 * 3. User-facing strings do not contain "Mina" or Chinese characters.
 */
class CapsuleShortcutMenuTest {

    @get:Rule val texts = EnglishTexts()

    @Test
    fun shortcutMenuItemsCountAndOrder() {
        val shortcuts = CapsuleShortcut.allShortcuts()
        assertEquals(9, shortcuts.size)

        assertEquals(CapsuleShortcut.LiveShare, shortcuts[0])
        assertEquals(CapsuleShortcut.AskAboutScreen, shortcuts[1])
        assertEquals(CapsuleShortcut.CircleToAsk, shortcuts[2])
        assertEquals(CapsuleShortcut.ActHere, shortcuts[3])
        assertEquals(CapsuleShortcut.Voice, shortcuts[4])
        assertEquals(CapsuleShortcut.TranslateHindi, shortcuts[5])
        assertEquals(CapsuleShortcut.PageSummary, shortcuts[6])
        assertEquals(CapsuleShortcut.CopyText, shortcuts[7])
        assertEquals(CapsuleShortcut.StopTask, shortcuts[8])
    }

    @Test
    fun englishTitlesAreDescriptiveAndNoMina() {
        val shortcuts = CapsuleShortcut.allShortcuts()
        for (item in shortcuts) {
            val title = tr(item.titleRes)
            assertTrue("Title should not be blank for ${item.name}", title.isNotBlank())
            assertFalse("Title should not contain 'Mina' in ${item.name}", title.contains("Mina", ignoreCase = true))
            assertFalse("Title should not contain Chinese characters in ${item.name}", title.any { it in '\u4e00'..'\u9fff' })
        }
    }

    @Test
    fun hindiStringsExistAndAreValid() {
        val hindiFile = File("src/main/res/values-hi/strings_capsule.xml")
        val fallbackFile = File("app/src/main/res/values-hi/strings_capsule.xml")
        val file = if (hindiFile.exists()) hindiFile else fallbackFile
        assertTrue("Hindi strings_capsule.xml must exist", file.exists())

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

        val requiredKeys = listOf(
            "capsule_menu_live_share",
            "capsule_menu_ask_about_screen",
            "capsule_menu_circle_to_ask",
            "capsule_menu_act_here",
            "capsule_menu_voice",
            "capsule_menu_translate_hindi",
            "capsule_menu_page_summary",
            "capsule_menu_copy_text",
            "capsule_menu_stop_task",
            "capsule_dismiss_zone",
        )

        for (key in requiredKeys) {
            val text = checkNotNull(strings[key]) { "Key $key must exist in values-hi" }
            assertTrue("Key $key must not be blank in values-hi", text.isNotBlank())
            assertFalse("Key $key in values-hi must not contain 'Mina'", text.contains("Mina", ignoreCase = true))
            assertFalse("Key $key in values-hi must not contain Chinese characters", text.any { it in '\u4e00'..'\u9fff' })
        }
    }
}
