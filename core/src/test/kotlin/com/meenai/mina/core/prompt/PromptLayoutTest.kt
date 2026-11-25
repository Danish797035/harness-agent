package com.meenai.mina.core.prompt

import com.meenai.mina.core.agent.BatchRunner
import com.meenai.mina.core.testing.PromptSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.meenai.mina.core.testing.BATCHABLE
import com.meenai.mina.core.assembly.allTools

/** The hierarchical rules of prompts/ (see prompts/README.md): senior management does not mention the tool name, each tool has a description, no redundant files, and the feedback is self-contained; each layer is capitalized. */
class PromptLayoutTest {
    private val root = File("src/main/resources/prompts")
    private fun files(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
    private fun size(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.sumOf { it.readBytes().size }

    private val toolNames = allTools().map { it.name } + BatchRunner.spec(BATCHABLE).name
    /** It is also the tool name of the common English word: system/. It is used as a word as usual and does not count as a roll call. */
    private val plainWords = setOf("phone_screenshot", "phone_back", "wait", "phone_tap", "phone_scroll", "phone_swipe")

    @Test fun systemLayerNamesNoTools() {
        for (f in files("system")) {
            val text = File(root, "system/$f").readText()
            val named = toolNames.filter { it !in plainWords && Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
            assertTrue("system/$f names tools $named; tool rules belong in capabilities/ or tools/", named.isEmpty())
        }
    }

    /** The general rule for universal tools has always been to not name tools with any ability (it will still appear even if it doesn't have that ability). */
    @Test fun generalNamesNoCapabilityTool() {
        val text = File(root, "capabilities/general.md").readText()
        val named = allTools().filter { it.capability != null }.map { it.name }
            .filter { Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
        assertTrue("capabilities/general.md names capability tools $named; put them in that capability's section", named.isEmpty())
    }

    @Test fun everyToolHasExactlyOneDescriptionFile() {
        val used = allTools().associate { it.name to it.promptFile } + (BatchRunner.spec(BATCHABLE).name to "do_steps")
        val defs = allTools().associate { it.name to it.description } + (BatchRunner.spec(BATCHABLE).name to BatchRunner.spec(BATCHABLE).description)
        for ((tool, file) in used) {
            assertTrue("$tool: missing prompts/tools/$file.md", File(root, "tools/$file.md").isFile)
            // The description does come from this document: the placeholder in the template can be
            // matched after being replaced with "any text"
            val template = File(root, "tools/$file.md").readText().trim()
            val pattern = template.split(Regex("\\{\\{[a-z0-9_]+}}")).joinToString(".+") { Regex.escape(it) }
            assertTrue("$tool does not load prompts/tools/$file.md", Regex(pattern).matches(defs.getValue(tool)))
        }
        assertEquals("orphan or missing files in prompts/tools", used.values.map { "$it.md" }.toSet(), files("tools"))
    }

    /** Each entrance tool (also provided when the ability group is collected) has a short description, which is not redundant; the short description only names the tools registered for this task. */
    @Test fun everyEntryToolHasShortDescription() {
        val entries = allTools().filter { it.entry != null }.map { it.name }
        assertEquals(setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command"), entries.toSet())
        assertEquals(entries.map { "$it.md" }.toSet(), files("entries"))
        for (e in entries) {
            val text = File(root, "entries/$e.md").readText()
            val named = Regex("(?<![A-Za-z_])(?:phone|web)_[a-z_]+").findAll(text).map { it.value }.filter { it !in toolNames }.toList()
            assertTrue("entries/$e.md names unknown tools $named", named.isEmpty())
        }
    }

    @Test fun noOrphanFiles() {
        assertEquals(setOf("identity.md", "principles.md"), files("system"))
        assertEquals((listOf("phone", "app-access", "batch", "web", "sandbox", "general", "inline-screen", "channels", "groups", "handover")).map { "$it.md" }.toSet(), files("capabilities"))
        assertEquals(Feedback.FILES.map { "$it.md" }.toSet(), files("feedback"))
        val roles = setOf("app_classifier", "describe", "intent_gate", "locate", "memory_extract", "route_match", "skill_draft", "app_knowledge", "app_knowledge_input", "verifier", "outcome_judge", "user_screen", "stop_summary", "conversation_title")
        assertEquals(roles.map { "$it.md" }.toSet(), files("roles"))
        roles.forEach { assertTrue(Prompts.role(it).isNotBlank()) }
        assertEquals(setOf("README.md"), root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet())
    }

    /** Each key in the feedback directory has a code in use (according to the error code description, the entire section is taken by prefix). */
    @Test fun noOrphanFeedbackKeys() {
        val code = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = Feedback.keys.filter { k -> "\"$k\"" !in code && !(k.startsWith("failure.") && "\"failure.\"" in code) }
        assertTrue("feedback keys not used by any code: $unused", unused.isEmpty())
    }

    /**
     * Each piece of feedback is self-contained: no blanks at the beginning or end (the newlines at the
     * beginning and end will be removed when reading, and spelling separated by them will quietly lose
     * words), and the code does not use a piece of feedback to spell it with other words using + or a
     * string template; if you want to spell it out, write the separation in the template and fill it in
     * with {{placeholder}}.
     */
    @Test fun feedbackEntriesAreSelfContained() {
        for (f in Feedback.FILES) for ((k, v) in Feedback.parse(File(root, "feedback/$f.md").readText())) {
            assertTrue("feedback $k starts or ends with whitespace", v == v.trim())
        }
        val problems = mutableListOf<String>()
        for (file in File("src/main/kotlin").walk().filter { it.extension == "kt" }) {
            val s = file.readText()
            for (m in Regex("""Feedback(\[|\.format\()""").findAll(s)) {
                val end = closing(s, m.range.last)
                val before = s.substring(0, m.range.first).trimEnd()
                val after = s.substring(end + 1).trimStart()
                if (before.endsWith("+") || after.startsWith("+") || before.endsWith("\${")) problems += "${file.name}: ${s.substring(m.range.first, end + 1).take(60)}"
            }
            // The name of the feedback (val X get() = Feedback..., fun x(...) = Feedback.format...)
            // cannot be spelled out
            val names = Regex("""val ([A-Z_0-9]+): String get\(\) = Feedback""").findAll(s).map { it.groupValues[1] } +
                Regex("""fun ([a-zA-Z0-9]+)\([^)]*\) = Feedback\.format""").findAll(s).map { it.groupValues[1] }
            for (n in names.toSet()) {
                val used = Regex("""(\+\s*$n\b)|(\b$n\s*\+)|(\$$n\b)|(\$\{$n\b)""")
                // Other files may also be used
                File("src/main/kotlin").walk().filter { it.extension == "kt" }.forEach { g ->
                    used.find(g.readText())?.let { problems += "${g.name}: ${it.value} concatenates feedback $n" }
                }
            }
        }
        assertTrue("feedback used as part of a concatenation: $problems", problems.isEmpty())
    }

    /** Find the matching closing bracket starting from the opening bracket [open]; skip the string. */
    private fun closing(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i++; while (s[i] != '"') { if (s[i] == '\\') i++; i++ } }
                '(', '[' -> depth++
                ')', ']' -> if (--depth == 0) return i
            }
            i++
        }
        error("unbalanced at $open")
    }

    /**
     * The size of each layer (bytes, UTF-8) is written into the golden sample of the prompt surface
     * (prompt-surface/sizes.txt): there is no hard upper limit, the increase can be seen in the
     * submitted differences, and you can judge whether it is appropriate during review.
     */
    @Test fun layerSizesRendered() {
        val text = buildString {
            for (layer in listOf("system", "capabilities", "tools", "entries", "feedback", "roles")) {
                appendLine("$layer: ${size(layer)} bytes, ${files(layer).size} files")
            }
        }
        print(text)
        PromptSurface.check("sizes", text)
    }
}
