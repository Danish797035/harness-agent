package com.meenai.mina.core.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** The type and identity of the file pointed to by the handle (fstat/lstat). */
data class FdStat(val kind: Kind, val nlink: Long, val size: Long, val dev: Long, val ino: Long) {
    enum class Kind { FILE, DIR, LINK, OTHER }
}

/** Open the handle obtained once; only the handle itself (read /proc/self/fd/N on Android) is used to determine the actual file, and there is no need to retrace the path. */
interface OpenFd : Closeable {
    fun realPath(): String
    fun stat(): FdStat
    /** The channel on the file handle (read or write, depending on the opening method); the directory does not. */
    fun channel(): FileChannel
    fun truncate(size: Long)
}

enum class OpenHow { READ, CREATE_NEW, WRITE, APPEND }

/** The system call error reported by [FdSys] is changed from [WorkspaceFileGate] to the error code of the tool layer. */
class FdSysException(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { NOT_FOUND, EXISTS, LINK, NOT_DIR, NOT_FILE }
}

/**
 * The system call layer. Calls with dir are based on the opened directory. [name] is a single-segment
 * name, and the last segment does not follow the link. The implementation on Android is app(open
 * O_NOFOLLOW + /proc/self/fd/N); [NioFdSys] is an approximation of the path on the JVM and is only used
 * for JVM testing.
 */
interface FdSys {
    /** The root directory itself (a path given by the caller and unreachable by the sandbox); the last paragraph is LINK. */
    fun openRoot(path: File): OpenFd
    fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd
    fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd
    /** Do not follow the link; null if not present. */
    fun lstat(dir: OpenFd, name: String): FdStat?
    fun readlink(dir: OpenFd, name: String): String?
    fun list(dir: OpenFd): List<String>
    /** Delete the item itself (file, link or empty directory) without following the link. */
    fun remove(dir: OpenFd, name: String)
    /** Change the name in the same directory, and replace the entire [to] (file or link) if it already exists. */
    fun rename(dir: OpenFd, from: String, to: String)
    fun symlink(dir: OpenFd, name: String, target: String)
    fun chmod(fd: OpenFd, mode: Int)
}

/**
 * Approximate implementation on JVM: parsing by path, there is a gap between inspection and use. Only
 * used for JVM unit testing (there is no /proc/self/fd on macOS); on mobile phones, it is always
 * implemented using Android in the app.
 */
object NioFdSys : FdSys {
    private class Fd(val path: Path, private val ch: FileChannel?) : OpenFd {
        private val real = path.toRealPath().toString()
        override fun realPath() = real
        override fun stat(): FdStat = statOf(path) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        override fun channel(): FileChannel = ch ?: throw FdSysException(FdSysException.Kind.NOT_FILE)
        override fun truncate(size: Long) { channel().truncate(size) }
        override fun close() { ch?.close() }
    }

    private fun fd(dir: OpenFd) = dir as Fd

    private fun attrs(p: Path): BasicFileAttributes? =
        try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { null }

    private fun statOf(p: Path): FdStat? {
        val a = try { Files.readAttributes(p, "unix:mode,nlink,size,dev,ino", LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { return null }
        val mode = (a["mode"] as Int) and 0xF000
        val kind = when (mode) { 0x8000 -> FdStat.Kind.FILE; 0x4000 -> FdStat.Kind.DIR; 0xA000 -> FdStat.Kind.LINK; else -> FdStat.Kind.OTHER }
        return FdStat(kind, (a["nlink"] as Number).toLong(), (a["size"] as Number).toLong(), (a["dev"] as Number).toLong(), (a["ino"] as Number).toLong())
    }

    private fun dirAt(p: Path): Fd {
        val a = attrs(p) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        if (a.isSymbolicLink) throw FdSysException(FdSysException.Kind.LINK)
        if (!a.isDirectory) throw FdSysException(FdSysException.Kind.NOT_DIR)
        return Fd(p, null)
    }

    override fun openRoot(path: File): OpenFd = dirAt(path.toPath().toAbsolutePath())

    override fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd {
        val p = fd(dir).path.resolve(name)
        if (create && attrs(p) == null) runCatching { Files.createDirectory(p) }
        return dirAt(p)
    }

    override fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd {
        val p = fd(dir).path.resolve(name)
        val a = attrs(p)
        if (a?.isSymbolicLink == true) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.LINK)
        // NIO cannot be opened. O_NONBLOCK: FIFO and the like are rejected first by type to avoid
        // getting stuck during reading and writing.
        if (a != null && !a.isRegularFile) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.NOT_FILE)
        val opts = when (how) {
            OpenHow.READ -> setOf(StandardOpenOption.READ)
            OpenHow.CREATE_NEW -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            OpenHow.WRITE -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            OpenHow.APPEND -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        val ch = try {
            FileChannel.open(p, opts + LinkOption.NOFOLLOW_LINKS)
        } catch (e: FileAlreadyExistsException) {
            throw FdSysException(FdSysException.Kind.EXISTS, e)
        } catch (e: NoSuchFileException) {
            throw FdSysException(FdSysException.Kind.NOT_FOUND, e)
        }
        return Fd(p, ch)
    }

    override fun lstat(dir: OpenFd, name: String): FdStat? = statOf(fd(dir).path.resolve(name))

    override fun readlink(dir: OpenFd, name: String): String? =
        runCatching { Files.readSymbolicLink(fd(dir).path.resolve(name)).toString() }.getOrNull()

    override fun list(dir: OpenFd): List<String> = Files.list(fd(dir).path).use { s -> s.map { it.fileName.toString() }.toList() }

    override fun remove(dir: OpenFd, name: String) {
        try { Files.delete(fd(dir).path.resolve(name)) } catch (e: NoSuchFileException) { throw FdSysException(FdSysException.Kind.NOT_FOUND, e) }
    }

    override fun rename(dir: OpenFd, from: String, to: String) {
        val d = fd(dir).path
        Files.move(d.resolve(from), d.resolve(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun symlink(dir: OpenFd, name: String, target: String) {
        Files.createSymbolicLink(fd(dir).path.resolve(name), java.nio.file.Paths.get(target))
    }

    override fun chmod(fd: OpenFd, mode: Int) {
        val perms = java.nio.file.attribute.PosixFilePermissions.fromString(
            (0..8).joinToString("") { i -> if (mode and (1 shl (8 - i)) != 0) "rwx"[i % 3].toString() else "-" },
        )
        Files.setPosixFilePermissions(fd(fd).path, perms)
    }
}
