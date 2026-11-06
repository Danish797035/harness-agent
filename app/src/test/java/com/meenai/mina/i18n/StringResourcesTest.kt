package com.meenai.mina.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 英文(默认)、简体和繁体三份界面文字要一一对上:同样的条目、同样的占位符(繁体对照简体);英文里不该留着没翻的中文。
 * 其他语言([others])也要和英文一一对上,复数按各自语言的数量词写全。
 */
class StringResourcesTest {
    /** 中英以外的一种界面语言:资源文件夹,和这种语言的复数要写哪几个数量词(按 CLDR 的复数规则)。 */
    private class Other(val folder: String, val locale: Locale, val quantities: Set<String>) {
        val texts by lazy { ResFileTexts(folder, locale) }
    }

    private val others = listOf(
        Other("values-hi", Locale.forLanguageTag("hi"), setOf("one", "other")),
    )

    private val en = ResFileTexts.english

    /** 标了 translatable="false" 的条目只在默认文件里。 */
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

    /** 不用汉字的语言里不该留着没翻的中文;日语本来就用汉字,不查。 */
    @Test
    fun otherLanguagesHaveNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (o in others.filter { it.folder != "values-ja" }) {
            val left = o.texts.strings.filter { (k, v) -> k != "chat_example_creator" && cjk.containsMatchIn(v) }.keys +
                o.texts.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
            assertEquals(o.folder, emptySet<String>(), left)
        }
    }

    /** 每种界面语言在资源里都有对应的文件夹,ui_locale 写的就是这种语言。 */
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
        // 例子里的博主名本来就是中文
        val allowed = fixed + "chat_example_creator"
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in allowed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        // 单测里没有安卓的 ICU:补文字体系这一步换成假的,只认一个私用标签是繁体
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
