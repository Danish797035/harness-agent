package com.meenai.mina.core.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's regex engine is stricter than Java on computers: unescaped `{` and `}` (not {n}, {n,m}
 * times) can be compiled on a computer, but an error will be thrown as soon as they are loaded on a
 * mobile phone. Here we scan all the hard-coded regular literals in the source code and block this
 * category on the computer.
 */
class RegexPortabilityTest {
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))
    // Only look at the literal immediately following the calling parentheses; the triple quotes remain
    // as they are, and ordinary strings are restored by Kotlin escape.
    private val call = Regex("""(?:Regex|Pattern\.compile)\(\s*(\"\"\"[\s\S]*?\"\"\"|"(?:[^"\\]|\\.)*")""")
    private val toRegex = Regex("""("(?:[^"\\]|\\.)*")\.toRegex\(""")

    @Test
    fun literalPatternsHaveNoBareBraces() {
        val offenders = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*")) return@mapIndexedNotNull null
                    (call.findAll(line) + toRegex.findAll(line)).map { pattern(it.groupValues[1]) }
                        .firstOrNull { hasBareBrace(it) }
                        ?.let { "${f.path}:${i + 1}: $it" }
                }.toList()
            }
        }
        assertTrue("正则里有没转义的花括号,安卓上会编译失败:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun detectorCatchesTheClass() {
        assertTrue(hasBareBrace("""\{\{([a-z0-9_]+)}}"""))
        assertTrue(hasBareBrace("""\{\d+}"""))
        assertTrue(!hasBareBrace("""\{\{([a-z0-9_]+)\}\}"""))
        assertTrue(!hasBareBrace("""\p{L}{1,8}\s{2}[{}]"""))
    }

    private fun pattern(lit: String) =
        if (lit.startsWith("\"\"\"")) lit.removeSurrounding("\"\"\"")
        else lit.removeSurrounding("\"").replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    /** After removing escaped curly braces, attributes such as \p{...}, [...] character group and {n}/{n,}/{n,m} times, just leave curly braces left. */
    private fun hasBareBrace(pattern: String): Boolean {
        var p = pattern.replace(Regex("""\\[pP]\{[A-Za-z_=]+\}"""), "")
        p = p.replace(Regex("""\\[{}\\]"""), "")
        p = p.replace(Regex("""\[(?:\\.|[^\]])*]"""), "")
        p = p.replace(Regex("""\{\d+(,\d*)?}"""), "")
        return '{' in p || '}' in p
    }
}
