package com.meenai.mina.cap.sandbox

import com.meenai.mina.chat.ConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * How much of a conversation's workspace occupies: [dirName] is its directory name under `work/`;
 * [conversationId] and [title] are the conversations it belongs to (null when the conversation has been
 * deleted and cannot be recognized); when [busy] is true, this conversation has tasks running or queued
 * and cannot be cleared.
 */
data class WorkspaceUsage(val dirName: String, val conversationId: String?, val title: String?, val bytes: Long, val busy: Boolean)

/** The space occupied by files and computing environments: running environment, skills, and workspaces for each conversation (largest first). */
data class SandboxUsage(val runtime: Long, val skills: Long, val workspaces: List<WorkspaceUsage>) {
    val workspacesTotal: Long get() = workspaces.sumOf { it.bytes }
    val total: Long get() = runtime + skills + workspacesTotal
}

/**
 * Read and clear the workspace for each conversation. The size is calculated as an ordinary file in the
 * directory tree, and links are not followed (commands in the workspace can create links, and pointing
 * to nowhere is not counted); when clearing, it is deleted through the file gate by handle just like
 * deleting a dialog, and links are not followed, sharing permissions of tree locks are held, and mutual
 * exclusions are reset; this dialogue is uncleared when a task is running or queued ([active]), so as
 * not to delete the files being used by the task. The operating environment and skills come with the App
 * and are not explained here.
 */
class SandboxUsageReader(
    private val dirs: SandboxDirs,
    /** Saved conversations (number and title). */
    private val conversations: () -> List<ConversationSummary>,
    /** Dialogue with tasks running or queued. */
    private val active: () -> Collection<String>,
) {
    /** Read the disk; there may be many files in the workspace, which need to be adjusted in a background thread. */
    fun read(): SandboxUsage {
        val byDir = conversations().associateBy { dirs.workspace(it.id).name }
        val busy = active().map { dirs.workspace(it).name }.toSet()
        val workspaces = dirs.work.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }.map { dir ->
            val c = byDir[dir.name]
            WorkspaceUsage(dir.name, c?.id, c?.title, AndroidSandbox.treeBytes(dir.toPath()), dir.name in busy)
        }.sortedByDescending { it.bytes }
        return SandboxUsage(AndroidSandbox.treeBytes(dirs.rootfs.toPath()), AndroidSandbox.treeBytes(dirs.skills.toPath()), workspaces)
    }

    /** Clear the workspace named [dirName] under `work/`; its dialog has tasks that are unclear when running, and returns false. */
    suspend fun clear(dirName: String): Boolean {
        if (dirName in busyDirs()) return false
        dirs.tree.shared { withContext(Dispatchers.IO) { dirs.gate.deleteTree(dirs.work, dirName) } }
        return true
    }

    /** Clear all unused workspaces; return the number left because there are tasks running. */
    suspend fun clearAll(): Int {
        val busy = busyDirs()
        val names = withContext(Dispatchers.IO) { dirs.work.listFiles().orEmpty().map { it.name } }
        dirs.tree.shared {
            withContext(Dispatchers.IO) { names.filter { it !in busy }.forEach { runCatching { dirs.gate.deleteTree(dirs.work, it) } } }
        }
        return names.count { it in busy }
    }

    private fun busyDirs(): Set<String> = active().map { dirs.workspace(it).name }.toSet()
}
