package com.meenai.mina.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The three interface texts in English (default), Simplified Chinese and Traditional Chinese must be
 * matched one by one: the same entries, the same placeholders (Traditional Chinese versus Simplified
 * Chinese); there should not be any untranslated Chinese in English. Other languages ([others]) should
 * also match English one by one, and the plurals should be written in full according to the numerals of
 * the respective languages.
 */
class StringResourcesTest {
    /** An interface language other than Chinese and English: resource folder, and which quantifiers should be written in the plural of this language (according to the plurality rules of CLDR). */
    private class Other(val folder: String, val locale: Locale, val quantities: Set<String>) {
        val texts by lazy { ResFileTexts(folder, locale) }
    }

    private val others = listOf(
        Other("values-hi", Locale.forLanguageTag("hi"), setOf("one", "other")),
    )

    private val en = ResFileTexts.english

    /** Entries marked with translatable="false" are only in the default file. */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun everyStringHasBothLanguages() {
        val hi = others.single().texts
        assertEquals(en.strings.keys - fixed, hi.strings.keys)
        assertEquals(en.plurals.keys, hi.plurals.keys)
        assertTrue(en.strings.size > 300)
    }

    @Test
    fun placeholdersMatch() {
        val hi = others.single().texts
        for (name in hi.strings.keys - setOf("date_month_day", "date_year_month_day")) {
            assertEquals(name, args(en.strings.getValue(name)), args(hi.strings.getValue(name)))
        }
        for (name in hi.plurals.keys) {
            val expected = args(hi.plurals.getValue(name).getValue("other"))
            en.plurals.getValue(name).values.forEach { assertEquals(name, expected, args(it)) }
        }
    }

    @Test
    fun otherLanguagesMatchEnglish() {
        for (o in others) {
            val t = o.texts
            assertEquals(o.folder, en.strings.keys - fixed, t.strings.keys)
            assertEquals(o.folder, en.plurals.keys, t.plurals.keys)
            for (name in t.strings.keys - setOf("date_month_day", "date_year_month_day")) {
                assertEquals("${o.folder}/$name", args(en.strings.getValue(name)), args(t.strings.getValue(name)))
            }
            for (name in t.plurals.keys) {
                val forms = t.plurals.getValue(name)
                assertEquals("${o.folder}/$name", o.quantities, forms.keys)
                val expected = args(en.plurals.getValue(name).getValue("other"))
                forms.forEach { (q, v) -> assertEquals("${o.folder}/$name/$q", expected, args(v)) }
            }
        }
    }

    /** There should be no untranslated Chinese in a language that does not use Chinese characters; Japanese uses Chinese characters to begin with, so there is no need to look them up. */
    @Test
    fun otherLanguagesHaveNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (o in others.filter { it.folder != "values-ja" }) {
            val left = o.texts.strings.filter { (k, v) -> k != "chat_example_creator" && cjk.containsMatchIn(v) }.keys +
                o.texts.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
            assertEquals(o.folder, emptySet<String>(), left)
        }
    }

    /** Each interface language has a corresponding folder in the resources, and ui_locale is written in this language. */
    @Test
    fun everyAppLanguageHasItsTexts() {
        val byLanguage = others.associateBy { AppLanguage.modernCode(it.locale.language) }
        for (l in AppLanguage.entries) {
            val code = l.language ?: continue
            if (code == "en") continue
            val o = byLanguage[code]
            assertTrue("$l has no folder", o != null)
            assertEquals(l.toString(), code, AppLanguage.modernCode(Locale.forLanguageTag(o!!.texts.strings.getValue("ui_locale")).language))
        }
    }

    @Test
    fun englishHasNoChineseLeftOver() {
        // The blogger’s name in the example is originally in Chinese.
        val allowed = fixed + "chat_example_creator"
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in allowed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        // There is no Android ICU in the single test: the step of supplementing the text system is
        // replaced with a fake one, and only one private label is recognized as Traditional Chinese
        val likely = { tag: String -> if (tag == "zh-x-hant") "Hant" else "Hans" }
        fun of(tag: String?) = AppLanguage.fromTag(tag, likely)
        assertEquals(AppLanguage.System, of(null))
        assertEquals(AppLanguage.System, of(""))
        assertEquals(AppLanguage.English, of("en"))
        assertEquals(AppLanguage.English, of("fr-FR"))
        // Hindi: tag hi-IN ya hi dono
        assertEquals(AppLanguage.Hindi, of("hi-IN"))
        assertEquals(AppLanguage.Hindi, of("hi"))
    }

    @Test
    fun eachLanguageHasItsOwnTag() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.fromTag(l.tag) { "Hans" })
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
