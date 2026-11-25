package com.meenai.mina.core.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.meenai.mina.core.workspace.SandboxPaths

/**
 * Each [SandboxPort] implementation must meet the path rules: out-of-bounds, symbolic link escape, skill
 * directory read-only, complete output does not leave the workspace. Subclasses give the sandbox and a
 * host directory (the target of the symbolic link) outside the sandbox root directory.
 */
abstract class SandboxPathContract {
    protected abstract fun sandbox(): SandboxPort
    /** A directory on the host that does not belong to any workspace or skill directory; the test only creates subdirectories in it. */
    protected abstract fun outsideDir(): File
    protected open val workspace = "contract-ws"

    private val sb by lazy { sandbox() }
    private fun wsRoot(): File = sb.hostPath(workspace, SandboxPaths.WORKSPACE, forWrite = true)!!.also { it.mkdirs() }
    private fun freshOutside(): File = File(outsideDir(), "case-${System.nanoTime()}").apply { mkdirs() }

    @Test fun relativeAndAbsoluteNameTheSameFile() {
        val a = sb.hostPath(workspace, "d/a.txt", forWrite = true)
        assertNotNull(a)
        assertEquals(a, sb.hostPath(workspace, "/workspace/d/a.txt", forWrite = true))
        assertEquals(a, sb.hostPath(workspace, "/workspace/x/../d/./a.txt", forWrite = false))
        assertTrue(a!!.canonicalPath.startsWith(wsRoot().canonicalPath + File.separator))
    }

    @Test fun escapesRejected() {
        for (p in listOf("../x", "/workspace/../x", "/etc/passwd", "/workspacex/a", "/opt/mina/skills/../../../x")) {
            assertNull(p, sb.hostPath(workspace, p, forWrite = false))
            assertNull(p, sb.hostPath(workspace, p, forWrite = true))
        }
    }

    @Test fun skillsReadableNotWritable() {
        assertNotNull(sb.hostPath(workspace, "/opt/mina/skills/domain/x/SKILL.md", forWrite = false))
        assertNull(sb.hostPath(workspace, "/opt/mina/skills/domain/x/SKILL.md", forWrite = true))
    }

    @Test fun symlinkOutOfWorkspaceRejected() {
        val out = freshOutside()
        File(out, "secret").writeText("key")
        val root = wsRoot()
        Files.createSymbolicLink(File(root, "out-${out.name}").toPath(), out.toPath())
        Files.createSymbolicLink(File(root, "dangling-${out.name}").toPath(), File(out, "not-yet").toPath())
        for (p in listOf("out-${out.name}", "out-${out.name}/secret", "out-${out.name}/new.txt", "dangling-${out.name}")) {
            assertNull(p, sb.hostPath(workspace, p, forWrite = false))
            assertNull(p, sb.hostPath(workspace, p, forWrite = true))
        }
    }

    @Test fun workspacesAreSeparate() {
        assertNotEquals(sb.hostPath("$workspace-a", "f.txt", forWrite = true), sb.hostPath("$workspace-b", "f.txt", forWrite = true))
    }

    @Test fun saveOutputStaysInWorkspace() {
        val path = sb.saveOutput(workspace, "完整输出")
        assertTrue(path, path.startsWith("/workspace/.mina/out-"))
        assertEquals("完整输出", sb.hostPath(workspace, path, forWrite = false)!!.readText())
        // .mina is replaced by a link pointing to the outside: if not written, an exception will be
        // thrown.
        val out = freshOutside()
        val mina = File(wsRoot(), ".mina")
        mina.deleteRecursively()
        Files.createSymbolicLink(mina.toPath(), out.toPath())
        try {
            assertThrows(Exception::class.java) { sb.saveOutput(workspace, "leak") }
            assertTrue(out.list()!!.isEmpty())
        } finally {
            Files.delete(mina.toPath())
        }
    }
}
