package com.meenai.mina.core.cap.sandbox

import java.io.File
import java.nio.file.Files
import com.meenai.mina.core.workspace.NioFdSys
import com.meenai.mina.core.workspace.SandboxPaths
import com.meenai.mina.core.knowledge.ScriptEntry
import com.meenai.mina.core.workspace.WorkspaceFileGate
import com.meenai.mina.core.workspace.SandboxTree

/** The host directory is placed in the sandbox in the temporary directory; the command does not actually run, press [next] to return the result and note down the parameters received. */
class FakeSandbox(var status: SandboxStatus = SandboxStatus.Ready(network = false)) : SandboxPort {
    class Run(val workspace: String, val argv: List<String>, val cwd: String?, val timeoutMs: Long)

    override val tree = SandboxTree()
    val root: File = Files.createTempDirectory("fake-sandbox").toFile()
    val skillsDir = File(root, "skills").apply { mkdirs() }
    val runs = mutableListOf<Run>()
    var phoneHandler: PhoneCommandHandler? = null
    val shared = mutableListOf<Pair<File, String>>()
    var next: (Run) -> CommandResult = { CommandResult(0, false, false, 10, "", 0, false) }
    var free: Long = 10L shl 30
    var used: Long = 0
    val manifests = mutableMapOf<String, List<ScriptEntry>>()
    private var outputs = 0

    fun workspaceDir(ws: String) = File(root, "work/$ws").apply { mkdirs() }

    override fun status() = status

    /** The state after awaitReady (the simulation background is ready or failed); null = unchanged. */
    var afterWait: SandboxStatus? = null
    var waits = 0

    override suspend fun awaitReady(timeoutMs: Long): SandboxStatus {
        waits++
        afterWait?.let { status = it }
        return status
    }

    override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult {
        val r = Run(workspace, argv, cwd, timeoutMs)
        runs += r
        return next(r)
    }

    override suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit, phone: PhoneCommandHandler?): CommandResult {
        phoneHandler = phone
        return run(workspace, argv, cwd, timeoutMs, onProgress)
    }

    override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? {
        val p = SandboxPaths.normalize(sandboxPath) ?: return null
        return when {
            SandboxPaths.isWritable(p) -> SandboxPaths.resolveHost(workspaceDir(workspace), p.removePrefix(SandboxPaths.WORKSPACE).trimStart('/'))
            forWrite -> null
            else -> SandboxPaths.resolveHost(skillsDir, p.removePrefix(SandboxPaths.SKILLS).trimStart('/'))
        }
    }

    override val files = WorkspaceFileGate(NioFdSys)

    override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = when {
        SandboxPaths.isWritable(normalized) -> workspaceDir(workspace) to normalized.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        forWrite -> null
        else -> skillsDir to normalized.removePrefix(SandboxPaths.SKILLS).trimStart('/')
    }

    override suspend fun share(file: File, name: String): String { shared += file to name; return "Download/Harness/$name" }
    override fun freeBytes() = free
    override fun workspaceBytes(workspace: String) = used

    override fun saveOutput(workspace: String, text: String): String {
        val rel = ".mina/out-${++outputs}.txt"
        val f = SandboxPaths.resolveHost(workspaceDir(workspace), rel) ?: throw java.io.IOException("$rel escapes the workspace")
        f.parentFile.mkdirs()
        f.writeText(text)
        return "${SandboxPaths.WORKSPACE}/.mina/out-$outputs.txt"
    }

    override fun scriptManifest(skillPath: String): List<ScriptEntry> = manifests[skillPath].orEmpty()

    var prepares = 0
    /** Script that is inconsistent with the packaged one (and cannot be restored): "<skill>/<file>". */
    val tampered = mutableSetOf<String>()
    override suspend fun prepare() { prepares++ }
    override suspend fun verifyScript(skillPath: String, file: String) = "$skillPath/$file" !in tampered
}
