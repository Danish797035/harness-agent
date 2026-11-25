package com.meenai.mina.cap.sandbox

import android.content.Context
import com.meenai.mina.core.workspace.WorkspaceFileGate
import java.io.File
import java.security.MessageDigest
import com.meenai.mina.core.workspace.SandboxTree

/**
 * The directory of the sandbox on the host: the root file system, workspace, skills, /proc alias under
 * `files/sandbox/`; the temporary directory of PRoot is in the cache; the executable file is only in
 * [nativeDir] (files in the data directory cannot be execve since Android 10).
 */
class SandboxDirs internal constructor(
    val root: File, val tmp: File, val nativeDir: File,
    /** The host side can read and write these directories (reachable by the sandbox) through it; on the mobile phone, it is [AndroidFdSys.gate], the copy of the JVM test transfer path. */
    val gate: WorkspaceFileGate,
    /** Before writing to this tree, you must first obtain its permission; the copy on your phone is the one in the process ([SandboxOwner]), and you can give it to yourself when testing. No default: a private copy cannot be locked to others. */
    val tree: SandboxTree,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "sandbox"), File(context.cacheDir, "proot-tmp"), File(context.applicationInfo.nativeLibraryDir),
        AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree,
    )

    val rootfs: File get() = File(root, "rootfs")
    val work: File get() = File(root, "work")
    val skills: File get() = File(root, "skills")
    val procStandIns: File get() = File(root, "proc")

    /** One conversation and one workspace: the ID of the simple name is c-<ID>, and the rest is h-<hash> (the ID is not allowed to spell out other paths, and the two categories will not conflict with each other). */
    fun workspace(conversationId: String): File = File(work, dirName(conversationId))

    fun lib(name: String): File = File(nativeDir, name)

    /**
     * Before binding, create all the directories to be bound: when the source directory does not exist,
     * PRoot will write a warning to the standard error, and the standard error will be merged into the
     * command output, and the model will see it. When the skill directory has not been synchronized, it
     * is an empty directory.
     */
    fun ensureBindSources(workspace: File) {
        for (d in listOf(workspace, skills, tmp)) d.mkdirs()
    }

    private fun dirName(id: String): String =
        if (SAFE_ID.matches(id)) "c-$id"
        else "h-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
