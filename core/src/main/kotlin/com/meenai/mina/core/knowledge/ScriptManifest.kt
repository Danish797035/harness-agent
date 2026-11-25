package com.meenai.mina.core.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.meenai.mina.core.workspace.SandboxPaths

/** An item in the skill script list (`scripts/manifest.json`); [file] is the relative path in the skill directory. */
@Serializable
data class ScriptEntry(val file: String, val runnable: Boolean, val needs: List<String> = emptyList(), val network: Boolean = false, val reason: String? = null)

/**
 * `scripts/manifest.json`:`[{file, runnable, needs, network, reason?}]`,[ScriptEntry.file] in the skill
 * directory are relative paths in the skill directory. run_bundled_script, knowledge_read, and
 * skill_read are all based on it.
 */
object ScriptManifest {
    const val NEEDS_NETWORK = "needs network access: the user can turn on \"Allow scripts to go online\" (允许脚本联网) in Harness Agent's settings"
    private val json = Json { ignoreUnknownKeys = true }
    private val INTERPRETERS = mapOf("py" to "/usr/bin/python3", "mjs" to "/usr/bin/node", "js" to "/usr/bin/node", "sh" to "/bin/sh")

    /** It will be empty if it cannot be read; items whose path is not a relative path in the skill directory will be discarded. */
    fun parse(text: String): List<ScriptEntry> = try {
        json.decodeFromString<List<ScriptEntry>>(text).filter { safe(it.file) }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** Use absolute paths: not affected by files with the same name in the workspace or PATH. */
    fun interpreterFor(file: String): String? = INTERPRETERS[file.substringAfterLast('.', "").lowercase()]

    /** Whether it can run at this moment, give the reason if it cannot: if it is marked as inoperable, use its own reason; if it needs to be connected to the Internet but the network is turned off, it will prompt you to open it in the settings. */
    fun effectiveRunnable(e: ScriptEntry, networkAllowed: Boolean): Pair<Boolean, String?> = when {
        !e.runnable -> false to (e.reason ?: "cannot be run for now")
        interpreterFor(e.file) == null -> false to "unsupported script type"
        e.network && !networkAllowed -> false to NEEDS_NETWORK
        else -> true to null
    }

    private fun safe(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/")) return false
        val base = "${SandboxPaths.SKILLS}/x"
        return SandboxPaths.normalize(rel, base)?.startsWith("$base/") == true
    }
}
