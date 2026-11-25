package com.meenai.mina.core.cap.sandbox

import com.meenai.mina.core.tool.Entry
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.EvidenceLog
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.bool
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.str
import com.meenai.mina.core.tool.strArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val SANDBOX = "sandbox"

private const val TIMEOUT = "Time limit in seconds, 1-600 (default 60). The command is stopped when it runs out."

/**
 * A set of sandboxes: provided when the device supports it (the tool call will wait for it when the
 * running environment is not ready). The paths are all paths in the sandbox; they are not screen
 * operations, but they are lock screen levels, and they do not enter screen guards (sandbox guards are
 * handled separately). Executed in [com.meenai.mina.core.cap.sandbox.SandboxTools].
 */
internal interface SandboxTool : AgentTool {
    override val capability: String get() = SANDBOX
    override val offScreen: Boolean get() = true
    override fun available(ctx: ToolContext) = ctx.sandboxRunner != null
    override fun offered(ctx: ToolContext) = ctx.sandboxAvailable()
}

/** The output of commands and scripts (the failed output is also a fact, so remember it as such), the files read, and the paths for writing and exporting are all facts that the model may reference. */
private fun res(r: ToolExecution, k: String) = (r.content[k] as? JsonPrimitive)?.contentOrNull
private fun ok(r: ToolExecution) = r.content["ok"] == JsonPrimitive(true)

internal object RunCommand : SandboxTool {
    override val verb = Verb.RunCommand
    override val label = "Work with files"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val composite = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("run_command",
        Prompts.tool("run_command"),
        mapOf(
            "command" to buildJsonObject { put("type", "string"); put("description", "The shell command, 1-20000 characters."); put("minLength", 1); put("maxLength", 20000) },
            "timeoutSec" to int(TIMEOUT, 1, 600),
            "cwd" to str("Optional working directory, a relative path under /workspace."),
        ), listOf("command"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> runCommand(a, ws, ctx.callId, ctx.sub, ctx.tools) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("command")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        // The command was written by the assistant himself: putting it where it came from is not
        // considered evidence; lines in the output that contain the exact words in the command (echo
        // echo) are also not counted.
        listOfNotNull(res(r, "output")?.let { output("退出码 ${res(r, "exitCode")}:\n$it", Place.command("run_command「${o.argStr("command")?.take(200)}」"), o.argStr("command")) })
}

internal object RunBundledScript : SandboxTool {
    override val verb = Verb.RunBundledScript
    override val label = "Run built-in script"
    override val risk = Risk.Write
    override val composite = true
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("run_bundled_script",
        Prompts.tool("run_bundled_script"),
        mapOf(
            "source" to str("Directory the script ships in, under /opt/mina/skills: the scriptSource from knowledge_read or skill_read, e.g. skills/documents."),
            "script" to str("Path of the script inside that directory, e.g. scripts/extract_pdf.py."),
            "args" to strArray("Arguments, at most 50, each passed unchanged.", 50),
            "timeoutSec" to int(TIMEOUT, 1, 600),
        ), listOf("source", "script"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, ready -> runScript(a, ws, ready.network, ctx.callId, ctx.sub, ctx.tools) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = listOfNotNull(o.argStr("source"), o.argStr("script")).joinToString("/")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        listOfNotNull(res(r, "output")?.let { output("退出码 ${res(r, "exitCode")}:\n$it", Place.command("run_bundled_script ${o.argStr("source")}/${o.argStr("script")}"), o["args"]?.toString()) })
}

internal object ReadFile : SandboxTool {
    override val verb = Verb.ReadFile
    override val label = "Read file"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("read_file",
        Prompts.tool("read_file"),
        mapOf(
            "path" to str("Sandbox path, absolute or relative to /workspace."),
            "offset" to int("Byte offset to start at (default 0).", 0),
            "limit" to int("Bytes to read, 1-262144 (default 65536).", 1, MAX_READ_BYTES),
        ), listOf("path"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> withContext(Dispatchers.IO) { readFile(a, ws) } }
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        // Read back the file written by the assistant using write_file. What it reads is the words
        // written by itself, which is not counted as evidence.
        listOfNotNull(if (ok(r) && res(r, "path") !in log.writtenPaths) res(r, "content")?.let { output(it, Place.file("read_file ${res(r, "path")}")) } else null)
}

internal object WriteFile : SandboxTool {
    override val verb = Verb.WriteFile
    override val label = "Write file"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("write_file",
        Prompts.tool("write_file"),
        mapOf(
            "path" to str("Sandbox path under /workspace, absolute or relative."),
            "content" to str("The text to write, at most 1 MB."),
            "append" to bool("Append instead of replacing (default false)."),
        ), listOf("path", "content"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> tree.shared { withContext(Dispatchers.IO) { writeFile(a, ws) } } }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (!ok(r)) return emptyList()
        res(r, "path")?.let { log.writtenPaths += it }
        return listOf(output("write_file 写入 ${res(r, "path")}(${res(r, "bytesWritten")} 字节,文件现为 ${res(r, "size")} 字节)"))
    }
}

internal object SearchFiles : SandboxTool {
    override val verb = Verb.SearchFiles
    override val label = "Search files"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("search_files",
        Prompts.tool("search_files"),
        mapOf(
            "pattern" to str("Regular expression to search for."),
            "path" to str("Directory or file to search (default /workspace)."),
            "glob" to str("Optional file name filter, e.g. *.csv or **/*.py."),
        ), listOf("pattern"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> withContext(Dispatchers.IO) { searchFiles(a, ws) } }
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (!ok(r)) return emptyList()
        return listOfNotNull((r.content["matches"] as? JsonArray)?.joinToString("\n") { m ->
            val mo = m as? JsonObject
            "${(mo?.get("file") as? JsonPrimitive)?.contentOrNull}:${(mo?.get("line") as? JsonPrimitive)?.contentOrNull}: ${(mo?.get("text") as? JsonPrimitive)?.contentOrNull}"
        }?.let { output(it, Place.command("search_files「${o.argStr("pattern")?.take(100)}」")) })
    }
}

internal object ShareFile : SandboxTool {
    override val verb = Verb.ShareFile
    override val label = "Export file"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("share_file",
        Prompts.tool("share_file"),
        mapOf(
            "path" to str("Sandbox path of the file under /workspace."),
            "name" to str("Optional file name to save as (default: the file's own name)."),
        ), listOf("path"))

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.sandboxRunner!!.run { ws, _ -> shareFile(a, ws) }
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) =
        listOfNotNull(if (ok(r)) output("share_file 已存到 ${res(r, "savedAs")}") else null)
}

/** An observation from the sandbox: command output, file contents, writing and exporting paths. [authored] is a command written by the assistant itself (the echo in the output is not remembered). */
private fun output(text: String, place: Place? = null, authored: String? = null) =
    Observation(ObsKind.Output, text, source = SANDBOX, place = place, authored = authored)

internal object SandboxGroup {
    val tools: List<AgentTool> = listOf(RunCommand, RunBundledScript, ReadFile, WriteFile, SearchFiles, ShareFile)
}
