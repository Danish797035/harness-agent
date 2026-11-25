package com.meenai.mina.core.action

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Boundary of execution entry: The constructor of [com.meenai.mina.core.tool.ActionPass] is internal,
 * and the App module cannot create it; in the core, only core.action can create it, and only core.action
 * can adjust the irreversible judgment and agreement check. Package dependencies can only go one way:
 * core.action and core.guard do not depend on core.agent which schedules them.
 */
class SingleEntryTest {
    private val action = File("src/main/kotlin/com/meenai/mina/core/action").canonicalPath + File.separator
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))

    /** Lines of code in the source code (whole lines of comments are not counted; strings and URLs within the lines are counted as usual). */
    private fun codeLines(): List<Triple<File, Int, String>> = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(f, i + 1, line)
            }
        }.toList()
    }

    private fun outsideAction(f: File) = !f.canonicalPath.startsWith(action)

    private fun offenders(pattern: Regex) = codeLines()
        .filter { (f, _, line) -> outsideAction(f) && pattern.containsMatchIn(line) }
        .map { (f, n, line) -> "${f.path}:$n: ${line.trim()}" }

    @Test fun sourcesAreFound() {
        assertTrue("找不到源码目录(工作目录应为 core/)", roots.first().isDirectory)
        assertTrue(codeLines().any { (f, _, line) -> !outsideAction(f) && "ActionPass()" in line })
    }

    @Test fun onlyTheExecutorHoldsThePass() {
        val bad = offenders(Regex("""\bActionPass\s*\(""")).filterNot { "class ActionPass" in it }
        assertTrue("这些地方自己造执行凭证,绕过了执行入口:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun onlyTheExecutorDecides() {
        val bad = offenders(Regex(
            """IrreversibleDetector\s*\.\s*(needsCheck|classify|hasLabelAt|unresolved)\b|\.\s*permits\s*\(|\.\s*noteWrite\s*\(|\bIrreversibleDetector\s*[,)]"""
        ))
        assertTrue("这些地方绕过执行入口直接判定:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun actionAndGuardDoNotDependOnTheLoop() {
        val bad = listOf("action", "guard").flatMap { pkg ->
            File("src/main/kotlin/com/meenai/mina/core/$pkg").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().filter { it.startsWith("import com.meenai.mina.core.agent.") }.map { "${f.path}: $it" }
            }.toList()
        }
        assertTrue("执行入口和守卫反过来依赖了主循环:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
