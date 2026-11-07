package com.meenai.mina.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Ensures user-visible strings in app/src/main (both Android resources and Kotlin code)
 * do not contain Chinese text.
 */
class NoChineseInUserVisibleStringsTest {

    private val cjk = Pattern.compile("[\\u4e00-\\u9fff]")

    private val mainJavaDir: File by lazy {
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("Cannot find src/main/java directory")
    }

    private val resDir: File by lazy {
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("Cannot find src/main/res directory")
    }

    @Test
    fun englishStringResourcesHaveNoChinese() {
        val valuesDir = File(resDir, "values")
        assertTrue("values directory exists", valuesDir.isDirectory)
        val files = valuesDir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: emptyArray()
        val violations = mutableListOf<String>()
        for (file in files) {
            val content = file.readText()
            val matcher = Pattern.compile("<(?:string|item)[^>]*>(.*?)</(?:string|item)>", Pattern.DOTALL).matcher(content)
            while (matcher.find()) {
                val text = matcher.group(1)
                if (cjk.matcher(text).find()) {
                    violations += "${file.name}: $text"
                }
            }
        }
        assertTrue("Found Chinese in English string resources: $violations", violations.isEmpty())
    }

    @Test
    fun hindiStringResourcesHaveNoChinese() {
        val valuesDir = File(resDir, "values-hi")
        assertTrue("values-hi directory exists", valuesDir.isDirectory)
        val files = valuesDir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: emptyArray()
        val violations = mutableListOf<String>()
        for (file in files) {
            val content = file.readText()
            val matcher = Pattern.compile("<(?:string|item)[^>]*>(.*?)</(?:string|item)>", Pattern.DOTALL).matcher(content)
            while (matcher.find()) {
                val text = matcher.group(1)
                if (cjk.matcher(text).find()) {
                    violations += "${file.name}: $text"
                }
            }
        }
        assertTrue("Found Chinese in Hindi string resources: $violations", violations.isEmpty())
    }

    @Test
    fun kotlinSourcesHaveNoChineseInUserVisibleStrings() {
        val allowedFiles = setOf(
            "AndroidScreen.kt",
            "LoginProbe.kt",
            "DesktopProfile.kt",
            "ConversationTitler.kt",
            "ProviderPresets.kt",
            "ChatViewModel.kt",
            "SourceViewModel.kt",
            "BrowserPageAsk.kt",
        )

        val violations = mutableListOf<String>()
        mainJavaDir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
            if (file.name in allowedFiles) return@forEach
            val raw = file.readText()
            val stripped = stripComments(raw)
            val strings = extractStrings(stripped)
            for ((lineNo, str) in strings) {
                if (cjk.matcher(str).find()) {
                    violations += "${file.name}:$lineNo: \"$str\""
                }
            }
        }
        assertTrue("Found Chinese in user-visible strings: $violations", violations.isEmpty())
    }

    companion object {
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            val n = text.length
            while (i < n) {
                if (text.startsWith("\"\"\"", i)) {
                    val end = text.indexOf("\"\"\"", i + 3)
                    if (end == -1) {
                        out.append(text.substring(i))
                        break
                    }
                    out.append(text.substring(i, end + 3))
                    i = end + 3
                } else if (text[i] == '"') {
                    var j = i + 1
                    while (j < n) {
                        if (text[j] == '\\') {
                            j += 2
                        } else if (text[j] == '"') {
                            j++
                            break
                        } else if (text[j] == '\n') {
                            break
                        } else {
                            j++
                        }
                    }
                    out.append(text.substring(i, minOf(j, n)))
                    i = j
                } else if (text.startsWith("//", i)) {
                    val end = text.indexOf('\n', i + 2)
                    if (end == -1) break
                    out.append('\n')
                    i = end + 1
                } else if (text.startsWith("/*", i)) {
                    val end = text.indexOf("*/", i + 2)
                    if (end == -1) break
                    val comment = text.substring(i, end + 2)
                    out.append("\n".repeat(comment.count { it == '\n' }))
                    i = end + 2
                } else {
                    out.append(text[i])
                    i++
                }
            }
            return out.toString()
        }

        fun extractStrings(text: String): List<Pair<Int, String>> {
            val results = mutableListOf<Pair<Int, String>>()
            var i = 0
            var lineNo = 1
            val n = text.length
            while (i < n) {
                if (text.startsWith("\"\"\"", i)) {
                    val startLine = lineNo
                    val end = text.indexOf("\"\"\"", i + 3)
                    if (end == -1) break
                    val s = text.substring(i + 3, end)
                    results += startLine to s
                    lineNo += text.substring(i, end + 3).count { it == '\n' }
                    i = end + 3
                } else if (text[i] == '"') {
                    val startLine = lineNo
                    var j = i + 1
                    while (j < n) {
                        if (text[j] == '\\') {
                            j += 2
                        } else if (text[j] == '"') {
                            j++
                            break
                        } else if (text[j] == '\n') {
                            break
                        } else {
                            j++
                        }
                    }
                    val s = text.substring(i + 1, if (j > i + 1 && text[minOf(j - 1, n - 1)] == '"') j - 1 else j)
                    results += startLine to s
                    i = j
                } else {
                    if (text[i] == '\n') lineNo++
                    i++
                }
            }
            return results
        }
    }
}
