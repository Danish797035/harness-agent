package com.meenai.mina.cap.web

import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.SandboxPaths
import com.meenai.mina.core.workspace.WorkspaceFileGate
import java.io.File
import java.util.UUID

/**
 * Transfer area for web page upload: The files in the workspace are opened once by [gate], verified on
 * the handle, and copied from this handle to [root]/<random>/. [root] is in the app's private cache
 * directory and cannot be touched by the sandbox; only this copy is handed over to the page, and no
 * matter how you change files in the workspace after checking, it will not affect it.
 */
class UploadStaging(
    private val root: File,
    private val gate: WorkspaceFileGate,
    private val maxFileBytes: Long = 100L shl 20,
    private val maxTotalBytes: Long = 200L shl 20,
) {
    /** A copy uploaded once: [dir] The entire directory will be deleted as soon as it is used. */
    class Staged(val dir: File, val files: List<File>)

    sealed interface Result {
        class Ok(val staged: Staged) : Result
        /** [error]:path_outside_workspace、file_not_found、linked_file、file_too_large。 */
        class Refused(val error: String, val path: String) : Result
    }

    fun stage(workspaceDir: File, paths: List<String>): Result {
        val dir = File(root, UUID.randomUUID().toString())
        val out = mutableListOf<File>()
        var total = 0L
        try {
            for ((i, p) in paths.withIndex()) {
                val rel = SandboxPaths.normalize(p)?.takeIf(SandboxPaths::isWritable)?.removePrefix(SandboxPaths.WORKSPACE)?.trimStart('/')
                    ?.takeIf { it.isNotEmpty() } ?: return refuse(dir, "path_outside_workspace", p)
                val g = try { gate.openRead(workspaceDir, rel) } catch (e: SandboxError) { return refuse(dir, codeOf(e), p) }
                g.use {
                    if (g.size > maxFileBytes || total + g.size > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                    // Each file has one level of directory: files with the same name in different
                    // directories retain their original names.
                    val target = File(File(dir, i.toString()).apply { mkdirs() }, g.name)
                    var copied = 0L
                    target.outputStream().use { o ->
                        val input = g.input()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            // After verification, the file became larger again: the calculation is still
                            // based on the upper limit.
                            if (copied > maxFileBytes || total + copied > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                            o.write(buf, 0, n)
                        }
                    }
                    total += copied
                    out += target
                }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return Result.Ok(Staged(dir, out))
    }

    fun delete(staged: Staged) {
        staged.dir.deleteRecursively()
    }

    /** Delete all copies (clear the ones left by the last process when starting). */
    fun clear() {
        root.deleteRecursively()
    }

    private fun refuse(dir: File, error: String, path: String): Result {
        dir.deleteRecursively()
        return Result.Refused(error, path)
    }

    private fun codeOf(e: SandboxError) = when (e.code) {
        WorkspaceFileGate.OUTSIDE -> "path_outside_workspace"
        WorkspaceFileGate.LINKED_FILE -> WorkspaceFileGate.LINKED_FILE
        else -> "file_not_found"
    }

    companion object {
        const val FILE_TOO_LARGE = "file_too_large"
        /** The staging area under the cache directory; FileProvider only opens this directory (res/xml/web_files.xml). */
        const val DIR = "webupload"
    }
}
