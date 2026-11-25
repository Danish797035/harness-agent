package com.meenai.mina.core.testing

import com.meenai.mina.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * Tips for the golden pattern on the surface of the word: Write the full text (not the abstract), and
 * you can directly see the differences when reviewing and making changes. The gold sample is in
 * core/src/test/resources/prompt-surface/; regenerate:./gradlew:core:test --tests '*PromptSurface*'
 * -PupdateSnapshots
 */
object PromptSurface {
    private val dir = File(System.getProperty("mina.promptSurfaceDir") ?: "src/test/resources/prompt-surface")
    private val update = System.getProperty("mina.updateSnapshots") == "true"

    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("没有金样 ${file.path};用 ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots 生成")
        assertEquals("提示词表面 $name 变了;确认是有意的改动后用 -PupdateSnapshots 重新生成", file.readText(), actual)
    }

    /** A combination of capabilities: the system prompts the full text of the word, and then lists the tools sent to the model (name + definition summary, the full text of the definition is in tools). */
    fun system(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { appendLine("${it.name} #${hash(spec(it))}") }
    }

    /** The full picture of a request: the system prompts the full text of the word, and then lists the full text of the definition of each tool in the order sent to the model. */
    fun layer(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { append(spec(it)); appendLine() }
    }

    fun tools(all: List<ToolSpec>): String = buildString {
        all.forEach { append(spec(it)); appendLine() }
    }

    private fun spec(t: ToolSpec) = "## ${t.name}\n${t.description}\n${t.parametersJsonSchema}\n"

    /** The full text of the prompt words for internal roles (standard, verification, description, positioning, etc.). */
    fun roles(): String = buildString {
        for (name in ROLES) {
            appendLine("## $name")
            appendLine(role(name))
        }
    }

    private val ROLES = listOf("describe", "intent_gate", "locate", "memory_extract", "route_match", "skill_draft", "app_knowledge", "app_knowledge_input", "verifier", "stop_summary")

    private fun role(name: String): String =
        checkNotNull(PromptSurface::class.java.getResourceAsStream("/prompts/roles/$name.md")) { "missing role prompt $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** A set of "labels → words sent to the model" are written out one by one. */
    fun entries(items: List<Pair<String, String>>): String = buildString {
        items.forEach { (k, v) -> appendLine("### $k"); appendLine(v) }
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
}
