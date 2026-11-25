package com.meenai.mina.cap.sandbox

import androidx.annotation.VisibleForTesting
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.cap.sandbox.RootfsExtractor
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * Synchronize the skill script (list + files listed in the list) packaged with the App to
 * `files/sandbox/skills/`, which is `/opt/mina/skills` in the sandbox. This directory can be written in
 * the sandbox (the binding of PRoot is writable), so every time it is checked according to the actual
 * content on the disk: it will not be moved if it is exactly the same as the packaged one; for extra,
 * missing, modified files, or non-ordinary files such as links, write a new one next to it and then
 * replace it as a whole.
 *
 * Parallel tasks: Synchronization is serialized by [lock]; at the moment of changing the directory, the
 * script being started in another task may not be able to find the file and report an error (returning
 * truthfully). The interpreter that is already running has already read the script and is not affected.
 * run_bundled_script will be checked byte by byte again before running ([matches]).
 */
object SkillsSync {
    private val lock = Mutex()
    private val packaged by lazy { GuideCatalog.loadBundled().scriptFiles() }

    /** Only for real machine testing: temporarily add a test skill. The official code does not set it. */
    @VisibleForTesting
    @Volatile var extraForTests: Map<String, ByteArray> = emptyMap()

    val bundled: Map<String, ByteArray> get() = if (extraForTests.isEmpty()) packaged else packaged + extraForTests

    /**
     * It is mutually exclusive with installation and reset via [SandboxTree] (this step in the
     * installation is run in the license held by the installation); [proceed] looks again after getting
     * the license: it will not be written until the operating environment is reset (no longer ready)
     * during the license period, so as not to create a skill directory in the newly deleted directory.
     */
    suspend fun sync(dirs: SandboxDirs, files: Map<String, ByteArray> = bundled, proceed: () -> Boolean = { true }) = dirs.tree.shared { lock.withLock {
        withContext(Dispatchers.IO) {
            if (!proceed()) return@withContext
            if (onDisk(dirs)?.let(::hashOf) == hashOf(files)) return@withContext
            val staging = File(dirs.root, "skills.new")
            RootfsExtractor.deleteTree(staging)
            staging.mkdirs()
            for ((rel, bytes) in files) {
                // The path comes from the packaged list; it is still parsed according to the rules and
                // is not written outside the directory.
                val f = SandboxPaths.resolveHost(staging, rel) ?: continue
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            // The skill directory sandbox can be written easily: the entire tree must be deleted
            // according to the handle, and the directory that is changed to a link in the middle will
            // not be followed.
            dirs.gate.deleteTree(dirs.root, dirs.skills.name)
            check(staging.renameTo(dirs.skills)) { "could not move synced skills into place" }
            // Old versions are judged by marked files and are no longer used.
            File(dirs.root, "skills.sha256").delete()
        }
    } }

    /** [rel] is byte-for-byte identical to the packed [expected] on disk (read through the gate, not following the link). */
    internal fun matches(dirs: SandboxDirs, rel: String, expected: ByteArray): Boolean = try {
        dirs.gate.openRead(dirs.skills, rel).use { g -> g.size == expected.size.toLong() && g.read(0, expected.size).contentEquals(expected) }
    } catch (e: SandboxError) {
        false
    } catch (e: IOException) {
        false
    }

    /** All files in the directory (relative path → content); if there are links, special files, or are too large or cannot be read, they will be null (will be treated as inconsistent). The directory is listed just to find the name, and the content is read through the pass. */
    private fun onDisk(dirs: SandboxDirs): Map<String, ByteArray>? {
        val base = dirs.skills.toPath()
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return null
        val out = HashMap<String, ByteArray>()
        var total = 0L
        return try {
            Files.walk(base).use { paths ->
                for (p in paths) {
                    if (p == base || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null
                    val rel = base.relativize(p).joinToString("/")
                    out[rel] = dirs.gate.openRead(dirs.skills, rel).use { g ->
                        total += g.size
                        if (total > MAX_ON_DISK) return null
                        g.read(0, g.size.toInt())
                    }
                }
            }
            out
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } catch (e: java.io.UncheckedIOException) {
            null
        }
    }

    /** The packaged skill script is much smaller than this; large files stuffed into the sandbox are not read into the memory and are directly treated as inconsistent and resynchronized. */
    private const val MAX_ON_DISK = 64L shl 20

    internal fun hashOf(files: Map<String, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((rel, bytes) in files.toSortedMap()) {
            md.update(rel.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(bytes.size.toString().toByteArray()); md.update(0)
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
