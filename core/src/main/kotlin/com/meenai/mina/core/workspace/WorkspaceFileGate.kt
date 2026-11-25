package com.meenai.mina.core.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID

/**
 * Files entering and exiting the sandbox directory (workspace, skill directory, root file system) on the
 * host side all pass through this channel.
 *
 * The command in the sandbox and Mina are the same user, and the file or directory can be changed to a
 * symbolic link, hard link or FIFO at any time, so "check first according to the path, and then use
 * according to the path" cannot be stopped. Each section here is opened relative to the directory handle
 * that has been opened in the previous section, and does not follow the link; the last file is only
 * opened once and verified on the handle (the actual location is still in the root directory, it is an
 * ordinary file, and there is only one hard link), and subsequent reading and writing are all on this
 * same handle. Deletion is also verified in this way.
 *
 * [rel] is a relative path in the root directory (separated by `/`); `..` and null bytes are considered
 * out of bounds. Errors are thrown as [SandboxError], and the message does not contain the host path.
 */
class WorkspaceFileGate(private val sys: FdSys) {

    /** Read: ordinary file, only one hard link; when [under] is given, the file must be directly in `<root>/<under>/`. */
    fun openRead(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.READ, createDirs = false)

    /** Create a new one (there is already a file_exists report with the same name and will never be overwritten); the missing directories are created piece by piece. */
    fun createNew(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.CREATE_NEW, createDirs = true)

    /** Write: Create if there is no one; truncate the existing ones after verification (continue writing when [append]), so other linked files cannot be truncated. */
    fun openWrite(root: File, rel: String, append: Boolean): GatedFile {
        val g = open(root, rel, null, if (append) OpenHow.APPEND else OpenHow.WRITE, createDirs = true)
        if (!append) try { g.truncate() } catch (e: IOException) { g.close(); throw e }
        return g
    }

    /** Create a new [name] in `<root>/<dir>/`; if the name is the same, try "name (2).ext"..., at most [max]. Returns the actual name and handle. */
    fun createNumbered(root: File, dir: String, name: String, max: Int = 999): Pair<String, GatedFile> {
        for (n in 1..max) {
            val candidate = numbered(name, n)
            try {
                return candidate to createNew(root, "$dir/$candidate", under = dir)
            } catch (e: SandboxError) {
                if (e.code != FILE_EXISTS) throw e
            }
        }
        throw SandboxError(FILE_EXISTS, "Too many files with this name.", recoverable = true)
    }

    /** Delete it after verifying that it is an ordinary file. */
    fun delete(root: File, rel: String, under: String? = null) {
        open(root, rel, under, OpenHow.READ, createDirs = false).use { it.delete() }
    }

    /** Create a table of contents (paragraph by paragraph, without following links). */
    fun mkdirs(root: File, rel: String) {
        anchored(root, segments(rel), create = true) { _, _ -> }
    }

    /** The name in the directory; if the directory does not exist, it will be empty. */
    fun list(root: File, rel: String): List<String> = try {
        anchored(root, segments(rel), create = false) { _, d -> sys.list(d) }
    } catch (e: SandboxError) {
        if (e.code == FILE_NOT_FOUND) emptyList() else throw e
    }

    /**
     * Replace the whole [rel]: Write a new one in the same directory and rename it. Whichever item it
     * turns out to be a link, FIFO, or hard link is just replaced, and what it points to is not written.
     */
    fun replace(root: File, rel: String, bytes: ByteArray, mode: Int = 420 /* 0644 */) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { rootReal, dir ->
            val tmp = ".harness-new-" + UUID.randomUUID()
            val f = file(dir, tmp, OpenHow.CREATE_NEW)
            try {
                f.use {
                    verify(it, rootReal, dir, tmp)
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) it.channel().write(buf)
                    sys.chmod(it, mode)
                }
                sys.rename(dir, tmp, segs.last())
            } catch (e: Exception) {
                runCatching { sys.remove(dir, tmp) }
                throw e
            }
        }
    }

    /** Let [rel] become a symbolic link pointing to [target] (it will not move if it is already); replace the entire original item. */
    fun symlink(root: File, rel: String, target: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { _, dir ->
            if (sys.readlink(dir, segs.last()) == target) return@anchored
            val tmp = ".harness-link-" + UUID.randomUUID()
            sys.symlink(dir, tmp, target)
            try { sys.rename(dir, tmp, segs.last()) } catch (e: Exception) { runCatching { sys.remove(dir, tmp) }; throw e }
        }
    }

    /**
     * Delete the entire `<root>/<rel>` tree: each layer is opened relative to the handle of the previous
     * layer and does not follow the link. The link only deletes the link itself. Directories that are
     * changed to links during the deletion process will not be followed. First make the read-only
     * directory writable.
     */
    fun deleteTree(root: File, rel: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        try {
            anchored(root, segs.dropLast(1), create = false) { rootReal, dir -> removeEntry(dir, segs.last(), rootReal, 0) }
        } catch (e: SandboxError) {
            if (e.code != FILE_NOT_FOUND) throw e
        }
    }

    private fun removeEntry(dir: OpenFd, name: String, rootReal: String, depth: Int) {
        val st = sys.lstat(dir, name) ?: return
        if (st.kind == FdStat.Kind.DIR) {
            if (depth > MAX_DEPTH) throw IOException("directory tree too deep to delete")
            val sub = try { sys.openDir(dir, name, create = false) } catch (e: FdSysException) { null }
            if (sub != null) sub.use {
                // If it is moved outside the root directory during the deletion process, it will not be
                // deleted.
                if (!inside(it.realPath(), rootReal)) return
                runCatching { sys.chmod(it, 448 /* 0700 */) }
                for (child in sys.list(it)) removeEntry(it, child, rootReal, depth + 1)
            }
        }
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    private fun open(root: File, rel: String, under: String?, how: OpenHow, createDirs: Boolean): GatedFile {
        val segs = segments(rel)
        if (segs.isEmpty()) throw notAFile()
        if (under != null && segs.dropLast(1) != segments(under)) throw outside()
        val rootFd = openRoot(root)
        val rootReal: String
        val dir: OpenFd
        try {
            rootReal = rootFd.realPath()
            dir = walk(rootFd, segs.dropLast(1), createDirs)
        } catch (e: Exception) {
            rootFd.close()
            throw e
        }
        // After that, dir returns to GatedFile; when dir is not the root directory itself, the root
        // directory handle is closed here.
        if (dir !== rootFd) rootFd.close()
        try {
            if (under != null && dir.realPath() != rootReal.trimEnd('/') + "/" + segments(under).joinToString("/")) throw outside()
            val f = file(dir, segs.last(), how)
            try {
                return GatedFile(sys, dir, f, segs.last(), verify(f, rootReal, dir, segs.last()))
            } catch (e: Exception) {
                f.close()
                throw e
            }
        } catch (e: Exception) {
            dir.close()
            throw e
        }
    }

    /** Root directory handle → Open section by section to the directory pointed to by [segs]; the intermediate handles will be closed when used up. */
    private fun walk(rootFd: OpenFd, segs: List<String>, create: Boolean): OpenFd {
        var cur = rootFd
        for (s in segs) {
            val next = try { sys.openDir(cur, s, create) } catch (e: FdSysException) {
                if (cur !== rootFd) cur.close()
                throw map(e, dirStep = true)
            }
            if (cur !== rootFd) cur.close()
            cur = next
        }
        return cur
    }

    private fun <T> anchored(root: File, segs: List<String>, create: Boolean, block: (String, OpenFd) -> T): T {
        val rootFd = openRoot(root)
        try {
            val rootReal = rootFd.realPath()
            val dir = walk(rootFd, segs, create)
            try {
                if (!inside(dir.realPath(), rootReal) && dir !== rootFd) throw outside()
                return block(rootReal, dir)
            } finally {
                if (dir !== rootFd) dir.close()
            }
        } finally {
            rootFd.close()
        }
    }

    private fun openRoot(root: File): OpenFd = try { sys.openRoot(root) } catch (e: FdSysException) { throw map(e, dirStep = true) }

    private fun file(dir: OpenFd, name: String, how: OpenHow): OpenFd =
        try { sys.openFile(dir, name, how) } catch (e: FdSysException) { throw map(e, dirStep = false) }

    /** Verify on the handle: the actual location is the name in the directory where it was opened, in the root directory; an ordinary file; only a hard link. */
    private fun verify(f: OpenFd, rootReal: String, dir: OpenFd, name: String): FdStat {
        val real = f.realPath()
        if (!inside(real, rootReal) || real != dir.realPath().trimEnd('/') + "/" + name) throw outside()
        val st = f.stat()
        if (st.kind != FdStat.Kind.FILE) throw notAFile()
        if (st.nlink != 1L) throw SandboxError(LINKED_FILE, "The file has more than one hard link, so Harness does not use it; copy it (cp) and use the copy.", recoverable = true)
        return st
    }

    private fun map(e: FdSysException, dirStep: Boolean): SandboxError = when (e.kind) {
        FdSysException.Kind.NOT_FOUND -> SandboxError(FILE_NOT_FOUND, "The file does not exist.", recoverable = true)
        FdSysException.Kind.EXISTS -> SandboxError(FILE_EXISTS, "A file with this name already exists.", recoverable = true)
        FdSysException.Kind.NOT_DIR -> SandboxError(FILE_NOT_FOUND, "A folder on this path is not a folder.", recoverable = true)
        FdSysException.Kind.LINK ->
            if (dirStep) SandboxError(NOT_A_FILE, "A folder on this path is a link; Harness's file tools do not follow links. Use the real path.", recoverable = true)
            else notAFile()
        FdSysException.Kind.NOT_FILE -> notAFile()
    }

    private fun outside() = SandboxError(OUTSIDE, "The file is outside the allowed folder.", recoverable = true)
    private fun notAFile() = SandboxError(NOT_A_FILE, "Not a regular file (a directory, link or special file).", recoverable = true)

    companion object {
        const val FILE_NOT_FOUND = "file_not_found"
        const val FILE_EXISTS = "file_exists"
        const val NOT_A_FILE = "not_a_file"
        const val LINKED_FILE = "linked_file"
        const val OUTSIDE = "path_outside_workspace"
        private const val MAX_DEPTH = 256

        /** Each segment in the root directory; `..`, null byte is out of bounds. */
        fun segments(rel: String): List<String> {
            if ('\u0000' in rel) throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            return rel.split('/').filter { it.isNotEmpty() && it != "." }.onEach {
                if (it == "..") throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            }
        }

        /** "a.csv" → "a (2).csv"; add the one without extension directly at the end. */
        fun numbered(name: String, n: Int): String {
            if (n <= 1) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }

        internal fun inside(path: String, root: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
    }
}

/** A file that has passed [WorkspaceFileGate] verification; both reading and writing are on this handle. [stat] is verified to be real-time. */
class GatedFile internal constructor(
    private val sys: FdSys,
    private val dir: OpenFd,
    private val fd: OpenFd,
    val name: String,
    val stat: FdStat,
) : Closeable {
    val size: Long get() = stat.size

    fun channel() = fd.channel()

    /** Read from the beginning (or from the current position); close the stream but not the handle. */
    fun input(): InputStream = object : java.io.FilterInputStream(Channels.newInputStream(fd.channel())) { override fun close() = Unit }

    fun output(): OutputStream = object : java.io.FilterOutputStream(Channels.newOutputStream(fd.channel())) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    /** Read up to [len] bytes starting from [offset] and ending at the end of the file. */
    fun read(offset: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = fd.channel().read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    fun write(bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) fd.channel().write(buf)
    }

    fun currentSize(): Long = fd.channel().size()

    internal fun truncate() = fd.truncate(0)

    /** Delete this file: Delete it only if the name in the directory is still the same file (device number + inode). If it is changed to something else, it will not be deleted. */
    fun delete() {
        val now = sys.lstat(dir, name) ?: return
        if (now.dev != stat.dev || now.ino != stat.ino || now.kind != FdStat.Kind.FILE) return
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    override fun close() {
        runCatching { fd.close() }
        runCatching { dir.close() }
    }
}
