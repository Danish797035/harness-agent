package com.meenai.mina.core.cap.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.SandboxPaths

/** The rules for saving to "Download/Mina"; the share_file tool and `mina share` in the sandbox are shared, and both paths are equally strict. */
object ShareRules {
    const val MAX_BYTES = 100L shl 20

    /**
     * [path] is a sandbox path (the relative path is based on /workspace), which must be in the
     * workspace, parsed by [SandboxPort.hostPath] (link escape is null), is an ordinary file (does not
     * follow links), and does not exceed 100 MB; [name] defaults to the file's own name, which must be a
     * single file name without control characters. Returns the host file and save name; throws
     * [SandboxError] if it is not in compliance with the rules.
     */
    fun check(sandbox: SandboxPort, workspace: String, path: String, name: String?): Pair<File, String> {
        val p = SandboxPaths.normalize(path)?.takeIf(SandboxPaths::isWritable)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val f = sandbox.hostPath(workspace, p, forWrite = true)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared; links that lead outside are refused.", recoverable = true)
        val given = name?.trim() ?: f.name
        if (!plainName(given)) throw SandboxError("invalid_arguments", "name: must be a plain file name without control characters", recoverable = true)
        val saveAs = fitName(given)
        if (refusedType(saveAs) || refusedType(f.name)) {
            throw SandboxError("share_refused_type", "App installation packages (.apk, .apks, .xapk, .aab) cannot be shared.", recoverable = false)
        }
        val hp = f.toPath()
        if (!Files.exists(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("file_not_found", "$p does not exist.", recoverable = true)
        if (!Files.isRegularFile(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("not_a_file", "$p is not a regular file.", recoverable = true)
        val size = Files.size(hp)
        if (size > MAX_BYTES) throw SandboxError("file_too_large", "$p is ${size shr 20} MB; at most 100 MB can be shared.", recoverable = true)
        return f to saveAs
    }

    /** Single file name: does not contain path separators, control characters, format characters (rewriting direction, zero width, etc., which can make the displayed name inconsistent with the actual one) and line/paragraph separators. */
    fun plainName(n: String): Boolean =
        n.isNotEmpty() && '/' !in n && '\\' !in n && n != "." && n != ".." &&
            n.codePoints().noneMatch(::hiddenChar)

    /** Do not allow export: Put the installation package into "Downloads", and users can install the app created in the sandbox with just one click. */
    private val REFUSED_TYPES = setOf("apk", "apks", "xapk", "aab")

    fun refusedType(name: String): Boolean = name.substringAfterLast('.', "").trim().lowercase() in REFUSED_TYPES

    /** The upper limit of file name is 255 bytes (UTF-8), leaving room for the system to add "(n)" serial number. */
    const val NAME_MAX_BYTES = 240

    /** Truncate by UTF-8 bytes (and optional word count), retain the extension (not if the extension itself is too long), and do not split characters. */
    fun fitName(name: String, maxBytes: Int = NAME_MAX_BYTES, maxChars: Int = Int.MAX_VALUE): String {
        fun bytes(t: String) = t.toByteArray(Charsets.UTF_8).size
        fun chars(t: String) = t.codePointCount(0, t.length)
        if (bytes(name) <= maxBytes && chars(name) <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val keepExt = ext.isNotEmpty() && bytes(ext) <= 32 && chars(ext) < maxChars
        val base = if (keepExt) name.substring(0, dot) else name
        val roomBytes = maxBytes - if (keepExt) bytes(ext) else 0
        val roomChars = if (maxChars == Int.MAX_VALUE) Int.MAX_VALUE else maxChars - if (keepExt) chars(ext) else 0
        val sb = StringBuilder()
        var used = 0
        var count = 0
        var i = 0
        while (i < base.length && count < roomChars) {
            val cp = base.codePointAt(i)
            val n = bytes(String(Character.toChars(cp)))
            if (used + n > roomBytes) break
            sb.appendCodePoint(cp)
            used += n
            count++
            i += Character.charCount(cp)
        }
        return sb.toString() + if (keepExt) ext else ""
    }

    /** Control characters, format characters (Cf) and U+2028/U+2029. */
    fun hiddenChar(cp: Int): Boolean =
        Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029
}
