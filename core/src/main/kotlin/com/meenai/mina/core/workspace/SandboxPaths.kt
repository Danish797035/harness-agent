package com.meenai.mina.core.workspace

import java.io.File
import java.nio.file.Files

/** The model only sees paths in the sandbox; here it is normalized and judged to be out of bounds. */
object SandboxPaths {
    const val WORKSPACE = "/workspace"
    const val SKILLS = "/opt/harness/skills"
    const val LEGACY_SKILLS = "/opt/mina/skills"

    /** Relative paths are based on [cwd]; do not return null under [WORKSPACE] or [SKILLS] after normalization. */
    fun normalize(path: String, cwd: String = WORKSPACE): String? {
        if (path.isEmpty() || '\u0000' in path) return null
        val parts = ArrayDeque<String>()
        for (seg in (if (path.startsWith("/")) path else "$cwd/$path").split('/')) when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(seg)
        }
        val n = "/" + parts.joinToString("/")
        return n.takeIf { under(it, WORKSPACE) || under(it, SKILLS) || under(it, LEGACY_SKILLS) }
    }

    /** Only the workspace is writable; the skills directory is read-only. */
    fun isWritable(normalized: String): Boolean = under(normalized, WORKSPACE)

    /**
     * [sandboxRel] under [root]; check the symbolic links piece by piece: those pointing outside [root]
     * and the target does not exist (you can’t tell where it will fall after writing it) will be null,
     * and the final canonicalFile must still be under [root].
     */
    fun resolveHost(root: File, sandboxRel: String): File? {
        val base = root.canonicalFile
        var cur = base
        for (seg in sandboxRel.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            cur = File(cur, seg)
            val p = cur.toPath()
            if (Files.isSymbolicLink(p)) {
                if (!Files.exists(p)) return null
                cur = cur.canonicalFile
                if (!inside(cur, base)) return null
            }
        }
        return cur.canonicalFile.takeIf { inside(it, base) }
    }

    private fun under(p: String, root: String) = p == root || p.startsWith("$root/")
    private fun inside(f: File, base: File) = f == base || f.path.startsWith(base.path + File.separator)
}
