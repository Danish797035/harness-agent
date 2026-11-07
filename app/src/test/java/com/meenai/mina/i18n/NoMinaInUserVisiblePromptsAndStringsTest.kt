package com.meenai.mina.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Ensures prompts in core/src/main/resources/prompts and user-visible strings in
 * app/src/main/res/values* do not contain the standalone word "Mina" or "Mina's".
 * Technical identifiers like $MINA_DISPLAY_ID, package names, or Theme.Mina are excluded.
 */
class NoMinaInUserVisiblePromptsAndStringsTest {

    private val minaPattern = Pattern.compile("\\bMina('s)?\\b")

    private val promptsDir: File by lazy {
        listOf(
            File("core/src/main/resources/prompts"),
            File("../core/src/main/resources/prompts")
        ).firstOrNull { it.isDirectory }
            ?: error("Cannot find core/src/main/resources/prompts directory")
    }

    private val resDir: File by lazy {
        listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res")
        ).firstOrNull { it.isDirectory }
            ?: error("Cannot find app/src/main/res directory")
    }

    @Test
    fun promptsHaveNoMinaWord() {
        val violations = mutableListOf<String>()
        promptsDir.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { file ->
            val lines = file.readLines()
            for ((lineNo, line) in lines.withIndex()) {
                val matcher = minaPattern.matcher(line)
                while (matcher.find()) {
                    violations += "${file.name}:${lineNo + 1}: $line"
                }
            }
        }
        assertTrue("Found 'Mina' in prompts: $violations", violations.isEmpty())
    }

    @Test
    fun stringResourcesHaveNoMinaWord() {
        val violations = mutableListOf<String>()
        resDir.listFiles { d -> d.isDirectory && d.name.startsWith("values") }?.forEach { dir ->
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: emptyArray()
            for (file in files) {
                val content = file.readText()
                val matcher = Pattern.compile("<(?:string|item)[^>]*>(.*?)</(?:string|item)>", Pattern.DOTALL).matcher(content)
                while (matcher.find()) {
                    val text = matcher.group(1)
                    if (minaPattern.matcher(text).find()) {
                        violations += "${dir.name}/${file.name}: $text"
                    }
                }
            }
        }
        assertTrue("Found 'Mina' in user-visible string resources: $violations", violations.isEmpty())
    }
}
