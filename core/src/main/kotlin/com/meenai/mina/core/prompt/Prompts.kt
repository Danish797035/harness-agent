package com.meenai.mina.core.prompt

import java.util.concurrent.ConcurrentHashMap

/**
 * The prompt words released with the App are all under the resource directory prompts/. See
 * prompts/README.md for hierarchy: system/identity (L0), capabilities/one section of each capability
 * (L1), tools/description of each tool, feedback/feedback to the model from the tool and the main loop,
 * roles/internal roles (list standards, verification, description, positioning...). {{name}} in the text
 * is filled in by the caller; if it is omitted, an error will be reported directly and the placeholder
 * will not be sent to the model.
 */
object Prompts {
    private val cache = ConcurrentHashMap<String, String>()

    /** Original text of the resource (remove leading and trailing blanks). */
    fun read(path: String): String = cache.getOrPut(path) {
        checkNotNull(Prompts::class.java.getResourceAsStream("/prompts/$path")) { "missing prompts/$path" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    }

    fun system(name: String): String = read("system/$name.md")

    fun capability(name: String): String = read("capabilities/$name.md")

    fun role(name: String): String = read("roles/$name.md")

    /** A short description of the set of entrance tools when it is closed (prompts/entries/<tool name>.md). */
    fun entry(tool: String): String = read("entries/$tool.md")

    /** A tool sends a description of the model (prompts/tools/<file>.md) and fills in the placeholders. */
    fun tool(file: String, vararg vars: Pair<String, Any?>): String = fill(read("tools/$file.md"), vars.toMap())

    /** One-pass replacement: Even if there are {{...}} in the filled-in value (words on the screen, user’s words), they will remain unchanged. */
    fun fill(template: String, vars: Map<String, Any?>): String = PLACEHOLDER.replace(template) { m ->
        val k = m.groupValues[1]
        check(k in vars) { "unfilled placeholder {{$k}} in: ${template.take(80)}" }
        vars[k].toString()
    }

    private val PLACEHOLDER = Regex("\\{\\{([a-z0-9_]+)\\}\\}")
}
