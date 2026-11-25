package com.meenai.mina.core.prompt

import com.meenai.mina.core.assembly.allTools
import com.meenai.mina.core.assembly.knownToolNames
import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.core.assembly.legacyNames
import com.meenai.mina.core.knowledge.KnowledgeIndex
import com.meenai.mina.core.knowledge.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.meenai.mina.core.tool.Verb
import org.junit.Test
import java.io.File

/**
 * The tool names mentioned in the knowledge, skills, and prompt words released with the App must all be
 * registered currently: those with screen prefixes (phone_..., web_...), the old names before the name
 * change (browser_..., launch_app, etc.), and the old names in backticks are all mentioned tools. Also
 * keep a comparison table of old names: each old name is suitable for the current tool.
 */
class ToolNamesInTextTest {
    private val roots = listOf("src/main/resources/knowledge", "src/main/resources/skills", "src/main/resources/prompts").map(::File)
    private val known = knownToolNames()
    private val legacy = legacyNames()
    private val prefixed = Regex("(?<![A-Za-z0-9_{.=&/-])(?:phone|web|browser)_[a-z0-9_]*[a-z0-9](?![A-Za-z0-9_])")
    private val word = Regex("(?<![A-Za-z0-9_`{.=&/-])[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?![A-Za-z0-9_`}])")
    private val ticked = Regex("`([a-z][a-z0-9_]*)`")

    @Test fun everyToolMentionIsARegisteredTool() {
        val bad = mutableListOf<String>()
        for (root in roots) for (f in root.walkTopDown().filter { it.isFile && it.extension in setOf("md", "json") }) {
            val text = f.readText()
            val mentions = prefixed.findAll(text).map { it.value } +
                word.findAll(text).map { it.value }.filter { legacy.replacements(it).isNotEmpty() } +
                ticked.findAll(text).map { it.groupValues[1] }.filter { legacy.replacements(it).isNotEmpty() }
            mentions.filter { it !in known }.distinct().forEach { bad += "${f.path}: $it" }
        }
        assertTrue("tool names that are not registered:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * In the knowledge and skills, everything that tells the model to run must be real: the script path
     * (`scripts/x.py`, `sites/a.b/scripts/x.py`, single file name `x.py`) must match the script marked
     * as runnable in the list; the identifier used as a command (`a_b` in backticks is preceded and
     * followed by "use / run / call / command / script", or written as `a_b(` The way it is called) is
     * the registered tool, tool parameters or header keys.
     */
    @Test fun everyCommandMentionExists() {
        val bad = commandMentions(File("src/main/resources"))
        assertTrue("commands or scripts that do not exist:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun commandMentions(resources: File): List<String> {
        val runnable = GuideCatalog.loadBundled().runnableScripts()
        val byFileName = runnable.map { it.substringAfterLast('/') }.toSet()
        val allowed = known + allTools().flatMap { it.def.props.keys } + KnowledgeIndex.KEYS
        val bad = mutableListOf<String>()
        for (dir in listOf("knowledge", "skills")) for (f in File(resources, dir).walkTopDown().filter { it.isFile && it.extension == "md" }) {
            val rel = f.relativeTo(resources).invariantSeparatorsPath
            // Entry directory, written in the same way as the skill parameter of run_bundled_script:
            // skills/documents, skills/documents
            val entry = rel.removePrefix("knowledge/").split('/').take(2).joinToString("/")
            val text = f.readText()
            for (m in scriptPath.findAll(text)) {
                val path = m.value.removePrefix("/opt/mina/skills/").let { if (it.startsWith("scripts/")) "$entry/$it" else it }
                if (path !in runnable) bad += "$rel: ${m.value}"
            }
            scriptFile.findAll(text).map { it.value }.filter { it !in byFileName }.forEach { bad += "$rel: $it" }
            for (m in tickedIdentifier.findAll(text)) {
                val name = m.groupValues[1]
                if (name in allowed) continue
                val before = text.substring(maxOf(0, m.range.first - 16), m.range.first).split('\n', '。', '；', ';').last()
                val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 9))
                if (callBefore.containsMatchIn(before) || callAfter.containsMatchIn(after)) bad += "$rel: $name"
            }
            called.findAll(text).map { it.groupValues[1] }.filter { it !in known }.forEach { bad += "$rel: $it(" }
        }
        return bad.distinct()
    }

    private val scriptPath = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh|ts)(?![A-Za-z0-9_])")
    private val scriptFile = Regex("(?<![A-Za-z0-9_./-])[a-z0-9_-]+\\.(?:py|mjs|sh)(?![A-Za-z0-9_])")
    private val tickedIdentifier = Regex("`([a-z][a-z0-9]*(?:_[a-z0-9]+)+)`")
    private val callBefore = Regex("用|跑|调用|运行|执行|通过|命令|脚本|工具|\\b(?:run|call|invoke|via|command|script|tool)s?\\b", RegexOption.IGNORE_CASE)
    private val callAfter = Regex("^(?:\\(|\\s*的?(?:工具|命令|脚本)|\\s*(?:command|script|tool)s?\\b)", RegexOption.IGNORE_CASE)
    private val called = Regex("(?<![A-Za-z0-9_.`])([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\\(")

    @Test fun legacyTableFollowsTheRule() {
        assertEquals(listOf("phone_tap", "web_tap"), legacy.replacements("tap"))
        assertEquals(listOf("phone_launch_app"), legacy.replacements("launch_app"))
        assertEquals(listOf("web_navigate"), legacy.replacements("browser_navigate"))
        assertEquals(listOf("web_wait_for"), legacy.replacements("browser_wait"))
        // The names that haven’t changed are not in the list.
        assertTrue(legacy.replacements("finish").isEmpty() && legacy.replacements("run_command").isEmpty())
        // Each old name corresponds to the current tool, and the tools corresponding to the same old
        // name do the same thing; except for those that are split into knowledge and skills.
        val split = setOf(setOf(Verb.KnowledgeSearch, Verb.SkillSearch), setOf(Verb.KnowledgeRead, Verb.SkillRead))
        for (old in legacy.names) {
            val now = legacy.replacements(old)
            assertTrue(old, now.isNotEmpty() && now.all { it in known })
            val verbs = now.map { knownTools().verb(it) }.toSet()
            assertTrue(old, verbs.size == 1 || verbs in split)
        }
    }

    @Test fun rewriteChangesOnlyToolMentions() {
        assertEquals("用 `phone_launch_app` 打开,再 `phone_tap` / `web_tap` 按钮;tap 一下", legacy.rewrite("用 `launch_app` 打开,再 `tap` 按钮;tap 一下"))
        assertEquals("先 web_navigate 再 phone_type_text / web_type_text", legacy.rewrite("先 browser_navigate 再 type_text"))
        assertEquals("not_a_tool 与 phone_tap 不动", legacy.rewrite("not_a_tool 与 phone_tap 不动"))
    }
}
