package com.meenai.mina.core.cap.sandbox

import java.io.File
import com.meenai.mina.core.knowledge.ScriptEntry
import com.meenai.mina.core.workspace.WorkspaceFileGate
import com.meenai.mina.core.workspace.SandboxTree
import kotlinx.serialization.json.JsonObject

/** When the sandbox command requests a mobile tool, the action execution entry for this task is returned. */
fun interface PhoneCommandHandler {
    suspend fun call(tool: String, args: JsonObject): JsonObject
}

sealed interface SandboxStatus {
    data class Unsupported(val reason: String) : SandboxStatus
    data object NotInstalled : SandboxStatus
    data class Installing(val percent: Int, val step: String) : SandboxStatus
    data class Ready(val network: Boolean) : SandboxStatus
    data class Broken(val reason: String) : SandboxStatus
}

/** The Agent is the only entrance to the Linux sandbox; the paths are all paths in the sandbox ([SandboxPaths]). */
interface SandboxPort {
    /** Tree lock of sandbox directory: Everything written to the workspace is written in its permission. */
    val tree: SandboxTree

    fun status(): SandboxStatus

    /** Whether the user allows the sandbox to connect to the Internet (it will also be known when the running environment is not ready); by default, it is in the ready state. */
    fun networkAllowed(): Boolean = (status() as? SandboxStatus.Ready)?.network == true

    /** [cwd]: The relative path under `/workspace`, null is `/workspace` itself. */
    suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult

    /** When the current task provides a mobile tool entry, the command can be called through the bridge; the old implementation can still only implement [run]. */
    suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit, phone: PhoneCommandHandler?): CommandResult =
        run(workspace, argv, cwd, timeoutMs, onProgress)

    /** The host file corresponding to the sandbox path; when it is out of bounds (including symbolic links pointing outside) or [forWrite] is not in the workspace, null is returned. Only used for judgment, read and write [files]. */
    fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File?

    /** Reading and writing files in the sandbox directory on the host side go through this process: open once, verify on the handle, and then read and write on this handle. */
    val files: WorkspaceFileGate

    /** The normalized sandbox path → (host root directory, relative path under the root) is given to [files]; the skill directory is read-only, and is null when out of bounds. Create the workspace directory if it does not exist. */
    fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>?

    /** Save it to "Download/Harness" on your phone and return to "Download/Harness/<name>". */
    suspend fun share(file: File, name: String): String

    fun freeBytes(): Long
    fun workspaceBytes(workspace: String): Long

    /**
     * Save the complete output to /workspace/.mina/out-<n>.txt and return the sandbox path. Created by
     * [files] (an exception is thrown when.mina is replaced by a link, etc., and nothing is written).
     */
    fun saveOutput(workspace: String, text: String): String

    /** [skillPath]: The skill directory (relative path) under `/opt/mina/skills`; read the list packaged with the App, and it is not empty. */
    fun scriptManifest(skillPath: String): List<ScriptEntry>

    /**
     * When the operating environment is not ready yet (unpacking is being started for the first time, or
     * has not yet started): Start preparation and wait for it, up to [timeoutMs]; return to the state
     * after waiting. Unsupported devices, return as soon as possible when preparation fails. The default
     * implementation is not equal.
     */
    suspend fun awaitReady(timeoutMs: Long): SandboxStatus = status()

    /** Check `/opt/mina/skills` to the one packaged with the App: any extra, missing, or modified ones will be resynchronized. Just record the failures, don't throw them away. */
    suspend fun prepare() {}

    /** [file] under [skillPath] is the same as the packed one byte by byte; if it is different, resynchronize it and check again. If it is still different, it will be false. */
    suspend fun verifyScript(skillPath: String, file: String): Boolean = true
}
