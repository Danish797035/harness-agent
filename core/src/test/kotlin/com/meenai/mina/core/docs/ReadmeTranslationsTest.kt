package com.meenai.mina.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Several translations under docs/readme/ can keep up with the English on the warehouse homepage
 * README.md:
 * - - Each translation begins with `<!-- translated-from: README.md @ <fingerprint> -->`, and the fingerprint is
 * [fingerprint] from README.md at the time of translation; README.md has been changed but the
 * translation has not been changed accordingly. This fails and lists the expired copies;
 * - - The translations have the same number of sections as README.md and `## `, and the local images
 * used are the same (each is parsed into the path in the warehouse according to the directory where it
 * is located and then compared); the badge images on the Internet are written in each language, so there
 * is no comparison. The generated paragraph is only in README.md and Simplified Chinese
 * [ReadmeSections.README_ZH], guarded by [ReadmeSectionsTest]; other translations cannot have it.
 */
class ReadmeTranslationsTest {
    private val root = File("..").canonicalFile
    private val source get() = File(root, ReadmeSections.README).readText()

    @Test fun translationsAreUpToDate() {
        val current = fingerprint(source)
        val stale = TRANSLATIONS.mapNotNull { name ->
            val marker = MARKER.find(File(root, name).readText())?.groupValues?.get(1)
            when (marker) {
                null -> "$name 开头缺少 <!-- translated-from: README.md @ $current -->"
                current -> null
                else -> "$name 是从旧版 README.md($marker)翻译的;按 README.md 的改动更新译文,再把开头的 translated-from 改成 $current"
            }
        }
        assertTrue("README 译文过期了(README.md 现在的指纹是 $current):\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test fun translationsKeepStructure() {
        val sections = sections(source)
        val images = localImages(source, ReadmeSections.README)
        val bad = TRANSLATIONS.flatMap { name ->
            val text = File(root, name).readText()
            listOfNotNull(
                sections(text).takeIf { it != sections }?.let { "$name 有 $it 个 ## 小节,README.md 有 $sections 个" },
                localImages(text, name).takeIf { it != images }?.let { "$name 的本地图片和 README.md 不一样:多了 ${it - images},少了 ${images - it}" },
            )
        }
        assertTrue("README 译文和 README.md 结构不一致:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun translationsHaveNoGeneratedBlocks() {
        val bad = (TRANSLATIONS - ReadmeSections.README_ZH).filter { "<!-- generated:" in File(root, it).readText() }
        assertTrue("生成段落只放在 README.md、${ReadmeSections.README_ZH},其他译文里换成指向它们的一句话:$bad", bad.isEmpty())
    }

    @Test fun fingerprintIgnoresGeneratedBlocksAndLineEndings() {
        val a = "前\n${ReadmeSections.start("x")}\n旧\n${ReadmeSections.end("x")}\n后\n"
        val b = "前\r\n${ReadmeSections.start("x")}\r\n新的清单\r\n${ReadmeSections.end("x")}\r\n后\r\n"
        assertEquals(fingerprint(a), fingerprint(b))
        assertNotEquals(fingerprint(a), fingerprint(a.replace("后", "改了")))
        assertEquals(12, fingerprint(a).length)
    }

    companion object {
        /** The translations are all under docs/, and the docsDir input for testing in core/build.gradle.kts has been covered; if you put it elsewhere, you need to add additional input. */
        val TRANSLATIONS = listOfNotNull(
            ReadmeSections.README_ZH.takeIf { File(File("..").canonicalFile, it).exists() },
        )
        private val MARKER = Regex("""<!-- translated-from: README\.md @ ([0-9a-f]+) -->""")
        private val GENERATED = Regex("""<!-- generated:([\w-]+):start -->.*?<!-- generated:\1:end -->""", RegexOption.DOT_MATCHES_ALL)

        /** README.md removes SHA-256 after generating paragraphs (together with markers) and uniform line breaks, and takes the first 12 hexadecimal digits. The generated paragraphs change with the code, not the translation. */
        fun fingerprint(readme: String): String {
            val text = GENERATED.replace(readme.replace("\r\n", "\n"), "")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        }

        private fun prose(text: String): List<String> {
            var fenced = false
            return text.lines().filter { line ->
                if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
            }
        }

        private fun sections(text: String) = prose(text).count { it.startsWith("## ") }

        private val IMAGE = Regex("""<img\s[^>]*\bsrc="([^"]+)"|!\[[^\]]*]\(([^)\s]+)""")

        /** The local pictures in [text] are parsed into the paths in the warehouse according to the directory of the file [path] (relative to the warehouse root directory). The relative writing methods in each language are different and can be compared. */
        private fun localImages(text: String, path: String): Set<String> {
            val dir = File(path).parentFile
            return IMAGE.findAll(prose(text).joinToString("\n")).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filter { "://" !in it }.map { (if (dir == null) File(it) else File(dir, it)).normalize().invariantSeparatorsPath }.toSet()
        }
    }
}
