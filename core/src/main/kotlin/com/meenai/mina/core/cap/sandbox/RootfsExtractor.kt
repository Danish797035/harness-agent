package com.meenai.mina.core.cap.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

data class ExtractReport(
    val files: Int,
    val dirs: Int,
    val symlinks: Int,
    val hardlinksCopied: Int,
    /** There are at most [RootfsExtractor.MAX_SKIPPED_NAMES] names; the total number is [skippedTotal]. */
    val skipped: List<String>,
    val skippedTotal: Int = skipped.size,
)

class UnsafeArchiveException(msg: String) : Exception(msg)

/**
 * Stream root filesystem tar.gz to dest.
 *
 * - - The target of the symbolic link is left intact (even if it is an absolute path, it is interpreted
 * as the root file system in PRoot).
 * - - The App on Android cannot create hard links, and the hard links will copy the solved target
 * instead.
 * - - Device file, FIFO skipped, recorded as skipped.
 * - - Any entry (and hard link target) that is not in dest after normalization, or passes through an
 * resolved symbolic link, throws UnsafeArchiveException;
 * The extracted content is deleted by the caller using [deleteTree] - File.deleteRecursively cannot be
 * used, it will be deleted outside dest following the (absolute) symbolic link.
 * - - If the number of entries exceeds maxEntries, or the total number of bytes of ordinary files and
 * hard link copies exceeds maxBytes, an UnsafeArchiveException will also be thrown.
 */
object RootfsExtractor {
    const val MAX_SKIPPED_NAMES = 1000

    fun extract(
        tarGz: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport = extractTar(GzipCompressorInputStream(BufferedInputStream(tarGz), true), dest, maxBytes, maxEntries)

    /** Same as [extract], the input is the decompressed tar stream (the caller decodes xz, etc. by himself). */
    fun extractTar(
        input: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        val skipped = mutableListOf<String>()
        var skippedTotal = 0
        var entries = 0
        var bytes = 0L
        fun skip(name: String) {
            skippedTotal++
            if (skipped.size < MAX_SKIPPED_NAMES) skipped += name
        }
        fun addBytes(n: Long, name: String) {
            bytes += n
            if (bytes > maxBytes) throw UnsafeArchiveException("archive exceeds $maxBytes bytes at: $name")
        }

        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (++entries > maxEntries) throw UnsafeArchiveException("archive has more than $maxEntries entries")
                val rel = relativeName(entry.name)
                if (rel.isEmpty()) continue // "./" itself is dest
                val target = resolveInside(root, rel, entry.name)
                when {
                    entry.isDirectory -> {
                        removeIfLinkOrFile(target, dirOk = true)
                        Files.createDirectories(target)
                        setMode(target, entry.mode, isDir = true)
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        Files.createSymbolicLink(target, linkTarget(target, entry.linkName))
                        symlinks++
                    }
                    entry.isLink -> {
                        val linkRel = relativeName(entry.linkName)
                        val source = resolveInside(root, linkRel, entry.linkName)
                        if (linkRel.isEmpty() || !Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                            skip(rel)
                            continue
                        }
                        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attrs.isRegularFile) addBytes(attrs.size(), rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // NOFOLLOW_LINKS: If the target is a symbolic link, copy the link itself rather
                        // than what it points to.
                        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                        hardlinks++
                    }
                    // Placed before isFile: Commons Compress may also be true for isFile on device
                    // entries
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skip(rel)
                    entry.isFile -> {
                        // The library will only read the number of bytes declared by the entry, so the
                        // upper limit of the declared size is enough.
                        addBytes(entry.size, rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // CREATE_NEW: If a symbolic link appears on the path, you would rather fail than
                        // write in the past.
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { tar.copyTo(it) }
                        setMode(target, entry.mode, isDir = false)
                        files++
                    }
                    else -> skip(rel) // Other unknown types
                }
            }
        }
        return ExtractReport(files, dirs, symlinks, hardlinks, skipped, skippedTotal)
    }

    /**
     * Delete the entire tree and never follow symbolic links (the link itself is deleted, but the things
     * it points to remain unchanged); before deleting, change each directory to be readable, writable
     * and accessible by the owner, otherwise the contents in the read-only directory cannot be deleted.
     * Do nothing if dest does not exist.
     */
    fun deleteTree(dest: File) {
        val root = dest.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        // Do not pass FOLLOW_LINKS: Symbolic links (including when root itself is a link) are handed to
        // visitFile as ordinary files.
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).toMutableSet()
                perms += listOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                Files.setPosixFilePermissions(dir, perms)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun linkTarget(at: Path, linkName: String): Path =
        try {
            at.fileSystem.getPath(linkName)
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid symlink target: ${e.message}")
        }

    /** Remove the "./" at the beginning and "/" at the end; absolute paths are rejected directly. */
    private fun relativeName(name: String): String {
        if (name.startsWith("/")) throw UnsafeArchiveException("absolute path in archive: $name")
        var n = name
        while (n.startsWith("./")) n = n.substring(2)
        if (n == ".") n = ""
        return n.trimEnd('/')
    }

    private fun resolveInside(root: Path, rel: String, original: String): Path {
        val p = try {
            root.resolve(rel).normalize()
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid path in archive: ${e.message}")
        }
        if (!p.startsWith(root)) throw UnsafeArchiveException("path escapes destination: $original")
        // If one of the intermediate levels is a symbolic link that has been solved, writing it in may
        // fall outside dest.
        var cur = root
        for (part in root.relativize(p).toList().dropLast(1)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) throw UnsafeArchiveException("path goes through a symlink: $original")
        }
        return p
    }

    private fun prepareParent(target: Path) {
        target.parent?.let { Files.createDirectories(it) }
    }

    private fun removeIfLinkOrFile(target: Path, dirOk: Boolean) {
        if (Files.isSymbolicLink(target)) {
            Files.delete(target)
        } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!dirOk) Files.delete(target) // Non-empty directories will throw IOException and hand it over to the caller.
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target)
        }
    }

    /** Only take 0777; the owner can always read and write, and the directory can always be entered, otherwise it cannot be written or deleted later. */
    private fun setMode(target: Path, tarMode: Int, isDir: Boolean) {
        var m = (tarMode and 0x1ff) or 0x180
        if (isDir) m = m or 0x40
        Files.setPosixFilePermissions(target, toPerms(m))
    }

    private val permBits = listOf(
        0x100 to PosixFilePermission.OWNER_READ,
        0x80 to PosixFilePermission.OWNER_WRITE,
        0x40 to PosixFilePermission.OWNER_EXECUTE,
        0x20 to PosixFilePermission.GROUP_READ,
        0x10 to PosixFilePermission.GROUP_WRITE,
        0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ,
        0x2 to PosixFilePermission.OTHERS_WRITE,
        0x1 to PosixFilePermission.OTHERS_EXECUTE,
    )

    private fun toPerms(mode: Int): Set<PosixFilePermission> =
        permBits.filter { (bit, _) -> mode and bit != 0 }.mapTo(mutableSetOf()) { it.second }
}
