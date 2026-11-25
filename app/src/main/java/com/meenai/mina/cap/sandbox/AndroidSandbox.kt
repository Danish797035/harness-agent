package com.meenai.mina.cap.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.workspace.GatedFile
import com.meenai.mina.core.workspace.WorkspaceFileGate
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.SandboxPaths
import com.meenai.mina.settings.NetProxyStore
import com.meenai.mina.core.cap.sandbox.SandboxPort
import com.meenai.mina.core.cap.sandbox.PhoneCommandHandler
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.knowledge.ScriptEntry
import com.meenai.mina.core.knowledge.ScriptManifest
import com.meenai.mina.core.cap.sandbox.ShareRules
import com.meenai.mina.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val EXIT_TIMEOUT = 124
private const val EXIT_LAUNCHER_ERROR = 125
private const val EXIT_SIGKILL = 128 + 9
/** The launcher itself has a time limit; Kotlin will forcefully kill it before it ends after waiting for so long. */
private const val FALLBACK_GRACE_MS = 10_000L
private const val DESTROY_GRACE_MS = 3_000L
private const val READER_GRACE_MS = 2_000L
private const val TIMEOUT_SLACK_MS = 500L
private const val SIZE_CACHE_MS = 60_000L
internal val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
private const val TAG = "AndroidSandbox"
internal const val SHARE_MAX_FILES = 20
internal const val SHARE_MAX_BYTES = 500L shl 20

/**
 * The export quota of a task (an AndroidSandbox), share_file is shared with `mina share`. It is reserved
 * based on the file size before copying, and is settled based on actual bytes after copying; export
 * failure does not account for the quota.
 */
internal class ShareQuota(private val maxFiles: Int = SHARE_MAX_FILES, private val maxBytes: Long = SHARE_MAX_BYTES) {
    private var files = 0
    private var bytes = 0L

    @Synchronized fun reserve(size: Long) {
        if (files + 1 > maxFiles || bytes + size > maxBytes) {
            throw SandboxError("share_limit", "One task can share at most $maxFiles files and ${maxBytes shr 20} MB in total.", recoverable = false)
        }
        files++
        bytes += size
    }

    @Synchronized fun settle(reserved: Long, actual: Long) { bytes += actual - reserved }

    @Synchronized fun release(reserved: Long) {
        files--
        bytes -= reserved
    }
}

/**
 * A dedicated thread that starts the sandbox process and remains active while the process is alive. The
 * launcher sets PR_SET_PDEATHSIG, which follows the thread that forked it: when the thread pool recycles
 * idle threads, the command initiated by that thread will be treated as "Mina is dead" and killed. The
 * coroutine scheduler returns the exception in start() to the caller, and the thread itself will not
 * exit due to exception.
 */
private val launcherThread = Executors.newSingleThreadExecutor { r -> Thread(r, "harness-sandbox-launcher").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/** Android implementation of [SandboxPort]: Launcher + PRoot runs commands, and file operations directly read and write the host directory. */
class AndroidSandbox internal constructor(
    private val dirs: SandboxDirs,
    private val currentStatus: () -> SandboxStatus,
    private val dnsServers: () -> List<String>,
    /** Open a bridge session for each run; if there is no Android socket in the JVM test, pass false. */
    private val bridges: Boolean = false,
    private val version: String = "",
    /** Save to "Download/Harness": (File that has passed the checkpoint verification, save name) → Exported file; cannot be exported when it is empty. */
    private val exporter: (suspend (GatedFile, String) -> SharedFile)? = null,
    /** Notify the task (file card) after the export is successful. */
    private val onShared: (SharedFile) -> Unit = {},
    /** Preparations made before each run (check the skill catalog into the packaged copy). */
    private val sync: suspend () -> Unit = {},
    /** The skill script packaged with the App (relative path under the skills directory → content); both the manifest and the script are subject to it. */
    private val skillFiles: () -> Map<String, ByteArray> = { emptyMap() },
    /** Begins (or gets ongoing) runtime environment preparation; null if preparation is not required. */
    private val startPreparing: () -> Job? = { null },
    /** The user's networking settings; click in the ready state when not given. */
    private val allowNetwork: (() -> Boolean)? = null,
    /** Network proxy (Settings › Network proxy, see [NetProxyStore]); the empty string is not used. Only given in commands that allow networking. */
    private val proxy: () -> String = { "" },
    /** The bridge client in the APK; the existing running environment must also be updated with the APK. */
    private val clientScript: () -> ByteArray? = { null },
) : SandboxPort {
    override val tree get() = dirs.tree

    constructor(context: Context, state: SandboxState, onShared: (SharedFile) -> Unit = {}) : this(
        SandboxDirs(context), { state.status.value }, { dns(context) }, bridges = true, version = versionOf(context),
        exporter = ShareExporter(context)::export, onShared = onShared, sync = { SkillsSync.sync(SandboxDirs(context)) { state.status.value is SandboxStatus.Ready } },
        skillFiles = { SkillsSync.bundled },
        startPreparing = { RuntimePreparer.ensure(context, retryFailed = true) },
        allowNetwork = { state.networkAllowed },
        proxy = { NetProxyStore.get(context).get() },
        clientScript = { context.assets.open("sandbox/harness").use { it.readBytes() } },
    )

    private val quota = ShareQuota()

    private class SizeEntry(val bytes: Long, val at: Long)
    private val sizes = ConcurrentHashMap<String, SizeEntry>()

    override fun status(): SandboxStatus = currentStatus()

    override fun networkAllowed(): Boolean = allowNetwork?.invoke() ?: super.networkAllowed()

    override suspend fun awaitReady(timeoutMs: Long): SandboxStatus {
        val s = status()
        if (s is SandboxStatus.Ready || s is SandboxStatus.Unsupported) return s
        val job = startPreparing() ?: return status()
        withTimeoutOrNull(timeoutMs) { job.join() }
        return status()
    }

    override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit): CommandResult = runWithPhone(workspace, argv, cwd, timeoutMs, onProgress, null)

    override suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit,
        phone: PhoneCommandHandler?): CommandResult {
        val network = (status() as? SandboxStatus.Ready)?.network == true
        // Check the cwd given by the caller again: it must be normalized and still in the work area.
        val rel = cwd?.takeIf { it.isNotEmpty() }?.let { c ->
            val n = SandboxPaths.normalize(c)?.takeIf(SandboxPaths::isWritable)
                ?: throw SandboxError("path_outside_workspace", "cwd must be a directory inside /workspace.", recoverable = true)
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        }.orEmpty()
        // Skill scripts are not synchronized and do not block commands: when the script cannot run,
        // run_bundled_script will report the truth accordingly.
        prepare()
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 3600).toInt()
        // The permissions are in the directory from the creation of the directory to the end of the
        // command: the command is also written to the workspace, and you have to wait until it finishes
        // running before deleting it.
        val o = tree.shared {
            val ws = withContext(Dispatchers.IO) {
                dirs.workspace(workspace).also {
                    dirs.ensureBindSources(it)
                    syncClientScript()
                    // If cwd does not exist in the workspace, build it (section by section, without
                    // following links); if it cannot be built, do not build it, and let PRoot report an
                    // error by itself.
                    rel.takeIf { c -> c.isNotEmpty() }?.let { c -> runCatching { files.mkdirs(it, c) } }
                    if (network) writeResolvConf()
                }
            }
            val cmd = ProcRunner.argv(dirs, ws, rel, timeoutSec, network, standIns(), argv)
            // When the command ends (including timeout, kill, cancellation), the bridge is closed: the
            // old name and token can no longer be used.
            val session = if (bridges) bridgeFor(workspace, network, onProgress, phone).open() else null
            try {
                launch(cmd, ProcRunner.env(dirs, session?.name, session?.token, workspace, if (network) proxy() else ""), timeoutSec * 1000L + FALLBACK_GRACE_MS)
            } finally {
                session?.close()
            }
        }
        sizes.remove(workspace)
        val text = String(o.output, Charsets.UTF_8)
        if (o.exitCode == EXIT_LAUNCHER_ERROR) {
            val reason = text.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (reason.startsWith("harnessrun: network isolation unavailable") || reason.startsWith("minarun: network isolation unavailable")) {
                throw SandboxError("sandbox_network_isolation_unavailable",
                    "The sandbox cannot cut off the network on this device, so commands are refused while network access is off. ($reason)")
            }
        }
        val timedOut = o.timedOut || launcherTimedOut(o.exitCode, o.durationMs, timeoutSec)
        return CommandResult(
            exitCode = if (o.timedOut) EXIT_TIMEOUT else o.exitCode, timedOut = timedOut,
            killedBySystem = !timedOut && o.exitCode == EXIT_SIGKILL, durationMs = o.durationMs,
            output = text, droppedBytes = o.droppedBytes, network = network,
        )
    }

    override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? {
        val p = SandboxPaths.normalize(sandboxPath) ?: return null
        return when {
            SandboxPaths.isWritable(p) -> SandboxPaths.resolveHost(dirs.workspace(workspace), p.removePrefix(SandboxPaths.WORKSPACE).trimStart('/'))
            forWrite -> null
            else -> SandboxPaths.resolveHost(dirs.skills, p.removePrefix(SandboxPaths.SKILLS).trimStart('/'))
        }
    }

    override val files: WorkspaceFileGate get() = dirs.gate

    // The workspace directory can only be created for writing (the caller holds the permission of
    // SandboxTree): reading and checking the path do not hold permission, and the creation will conflict
    // with the deletion of the reset.
    override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = when {
        SandboxPaths.isWritable(normalized) -> dirs.workspace(workspace).also { if (forWrite) it.mkdirs() } to normalized.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        forWrite -> null
        else -> dirs.skills to normalized.removePrefix(SandboxPaths.SKILLS).trimStart('/')
    }

    /** The path check of web_upload: only ordinary files in this workspace that pass the threshold are recognized; the skill directory and outside the workspace are considered out of bounds. */
    fun uploadCheck(workspace: String, sandboxPath: String): WorkspacePathCheck {
        hostPath(workspace, sandboxPath, forWrite = true) ?: return WorkspacePathCheck.Outside
        val n = SandboxPaths.normalize(sandboxPath) ?: return WorkspacePathCheck.Outside
        val (root, rel) = hostRoot(workspace, n, forWrite = false) ?: return WorkspacePathCheck.Outside
        return try {
            files.openRead(root, rel).close()
            WorkspacePathCheck.Ok
        } catch (e: SandboxError) {
            if (e.code == WorkspaceFileGate.OUTSIDE) WorkspacePathCheck.Outside else WorkspacePathCheck.NotFound
        }
    }

    /**
     * [file] has been checked by ShareRules by workspace; it is opened once through the gateway when
     * exporting (the root directory is the workspace where it is located), the amount is calculated
     * based on the size of the handle, and the copy is also read from this handle. Unexpected errors are
     * only recorded on Mina's side, and only the error code and a sentence are handed over, without
     * Mina's private path.
     */
    override suspend fun share(file: File, name: String): String {
        val export = exporter ?: throw SandboxError("share_unavailable", "Saving files to Downloads is not available in this version.")
        val (within, rel) = withContext(Dispatchers.IO) { workspaceOf(file) }
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val opened = withContext(Dispatchers.IO) { files.openRead(within, rel) }
        return opened.use { g ->
            val reserved = g.size
            quota.reserve(reserved)
            val shared = try {
                export(g, name)
            } catch (e: Throwable) {
                quota.release(reserved)
                throw mapExportError(e)
            }
            quota.settle(reserved, shared.size)
            onShared(shared)
            shared.savedAs
        }
    }

    private fun mapExportError(e: Throwable): Throwable = when (e) {
        is CancellationException, is SandboxError -> e
        is FileTooLarge -> SandboxError("file_too_large", "At most ${ShareRules.MAX_BYTES shr 20} MB can be shared.", recoverable = true)
        is Exception -> {
            Log.w(TAG, "export to Downloads failed", e)
            SandboxError("share_failed", "Saving to Downloads failed.", recoverable = true)
        }
        else -> e
    }

    /** The relative path between the workspace directory where the file is located (the first level under work/) and the workspace; it is null if it is not in any workspace. */
    private fun workspaceOf(file: File): Pair<File, String>? {
        val work = dirs.work.canonicalFile
        val rel = file.canonicalFile.toRelativeString(work).takeIf { !it.startsWith("..") && it.isNotEmpty() && !File(it).isAbsolute } ?: return null
        val first = rel.substringBefore(File.separatorChar)
        return if (first == rel) null else File(work, first) to rel.substringAfter(File.separatorChar).replace(File.separatorChar, '/')
    }

    override fun freeBytes(): Long = dirs.root.takeIf { it.exists() }?.usableSpace ?: dirs.root.parentFile?.usableSpace ?: 0L

    /** Accumulate without following the link; cache for one minute, and become invalid after running commands in this workspace. */
    override fun workspaceBytes(workspace: String): Long {
        val now = System.currentTimeMillis()
        sizes[workspace]?.takeIf { now - it.at < SIZE_CACHE_MS }?.let { return it.bytes }
        val bytes = treeBytes(dirs.workspace(workspace).toPath())
        sizes[workspace] = SizeEntry(bytes, now)
        return bytes
    }

    /** Create a new one in.mina/ via the gateway (does not overwrite the existing one); it will be thrown when.mina is replaced by a link, etc., and nothing will be written. */
    @Synchronized
    override fun saveOutput(workspace: String, text: String): String {
        val root = dirs.workspace(workspace).apply { mkdirs() }
        var next = (files.list(root, ".mina").mapNotNull { OUT_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        repeat(100) {
            val rel = ".mina/out-$next.txt"
            try {
                files.createNew(root, rel, under = ".mina").use { it.write(text.toByteArray(Charsets.UTF_8)) }
                return "${SandboxPaths.WORKSPACE}/$rel"
            } catch (e: SandboxError) {
                if (e.code != WorkspaceFileGate.FILE_EXISTS) throw IOException("cannot save output: ${e.code}")
                next++
            }
        }
        throw IOException("cannot save output: too many files")
    }

    /** Read the list packaged with the app: the commands in the sandbox can be changed, so you can’t trust them. */
    override fun scriptManifest(skillPath: String): List<ScriptEntry> {
        val bytes = skillFiles()["$skillPath/scripts/manifest.json"] ?: return emptyList()
        return ScriptManifest.parse(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun prepare() {
        try { sync() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "skill sync failed", e) }
    }

    private val bundledClient: ByteArray? by lazy(clientScript)

    /** The installed root file system version may not have changed, but the bridge client in the APK has been updated. */
    private fun syncClientScript() {
        val expected = bundledClient ?: return
        val rel = "usr/local/bin/harness"
        val matches = runCatching {
            dirs.gate.openRead(dirs.rootfs, rel).use { it.size == expected.size.toLong() && it.read(0, expected.size).contentEquals(expected) }
        }.getOrDefault(false)
        if (!matches || !File(dirs.rootfs, rel).canExecute()) dirs.gate.replace(dirs.rootfs, rel, expected, mode = 493 /* 0755 */)
        val legacy = "usr/local/bin/mina"
        val legacyMatches = runCatching {
            dirs.gate.openRead(dirs.rootfs, legacy).use { it.size == expected.size.toLong() && it.read(0, expected.size).contentEquals(expected) }
        }.getOrDefault(false)
        if (!legacyMatches || !File(dirs.rootfs, legacy).canExecute()) dirs.gate.replace(dirs.rootfs, legacy, expected, mode = 493 /* 0755 */)
    }

    override suspend fun verifyScript(skillPath: String, file: String): Boolean {
        val rel = "$skillPath/$file"
        val expected = skillFiles()[rel] ?: return false
        if (withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }) return true
        prepare()
        return withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }
    }

    /** `mina share` and share_file follow the same set of path rules. */
    private fun bridgeFor(workspace: String, network: Boolean, onProgress: (String) -> Unit, phone: PhoneCommandHandler?): SandboxBridge {
        return SandboxBridge(
        onProgress = onProgress,
        onShare = { path, name ->
            val (f, n) = withContext(Dispatchers.IO) { ShareRules.check(this@AndroidSandbox, workspace, path, name) }
            share(f, n)
        },
        info = {
            buildJsonObject { put("version", version); put("network", network); put("workspace", SandboxPaths.WORKSPACE) }
        },
        onPhone = phone?.let { handler -> { tool, args -> handler.call(tool, args) } },
        )
    }

    /**
     * The launcher timeout exit code is 124, but the command itself may also exit with 124, and the
     * launcher does not mark it otherwise. Therefore, only 124 that runs out of the time limit (leaving
     * [TIMEOUT_SLACK_MS] margin) is considered a timeout; those that exit with 124 in advance will be
     * counted as the actual exit code of the command.
     */
    internal fun launcherTimedOut(exitCode: Int, durationMs: Long, timeoutSec: Int): Boolean =
        exitCode == EXIT_TIMEOUT && durationMs >= timeoutSec * 1000L - TIMEOUT_SLACK_MS

    private fun standIns(): List<String> = dirs.procStandIns.list()?.sorted().orEmpty()

    /** When connecting to the Internet, write the DNS of the current network into the root file system: replace the entire file through the gateway (the commands in the root file system can be changed, but the original links are only replaced). */
    private fun writeResolvConf() {
        val servers = runCatching(dnsServers).getOrNull().orEmpty().ifEmpty { FALLBACK_DNS }
        files.replace(dirs.rootfs, "etc/resolv.conf", servers.joinToString("") { "nameserver $it\n" }.toByteArray())
    }

    internal companion object {
        private val OUT_NAME = Regex("^out-(\\d+)\\.txt$")

        private fun versionOf(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

        internal fun dns(context: Context): List<String> {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val lp = cm.getLinkProperties(cm.activeNetwork ?: return emptyList()) ?: return emptyList()
            return lp.dnsServers.mapNotNull { it.hostAddress }
        }

        internal fun treeBytes(root: Path): Long {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
            var total = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
            return total
        }

        /**
         * start() on the dedicated thread, and the rest on the IO thread: read output (upper limit 1
         * MB), etc. End. When [limitMs] is still not over, kill by force. When canceling the coroutine,
         * first SIGTERM (the launcher clears the entire process tree after receiving it), and then
         * SIGKILL after [DESTROY_GRACE_MS].
         */
        internal suspend fun launch(
            cmd: List<String>, env: Map<String, String>, limitMs: Long,
            start: (ProcessBuilder) -> Process = ProcessBuilder::start,
        ): ProcOutcome {
            val begin = System.nanoTime()
            // The startup itself cannot be canceled. When withContext returns the result to the original
            // coroutine, if it has been canceled, the result will be lost, so the process will write it
            // down in the block: even if the result is lost, it will know who to kill.
            var started: Process? = null
            try {
                withContext(launcherThread + NonCancellable) {
                    started = start(ProcessBuilder(cmd).redirectErrorStream(true).apply {
                        environment().clear()
                        environment().putAll(env)
                    })
                }
            } catch (e: CancellationException) {
                started?.let(::abandon)
                throw e
            }
            val p = started!!
            // Use ordinary threads to read output: blocked read cannot be canceled, and placing it in a
            // coroutine will make cancellation wait forever.
            val collector = OutputCollector()
            val reader = thread(isDaemon = true, name = "mina-sandbox-output") { runCatching { p.inputStream.use(collector::drain) } }
            try {
                // Canceled during startup: throw here, kill the newly started process by the following
                // catch
                currentCoroutineContext().ensureActive()
                p.outputStream.close()
                val finished = runInterruptible(Dispatchers.IO) { p.waitFor(limitMs, TimeUnit.MILLISECONDS) }
                if (!finished) p.destroyForcibly()
                val code = runInterruptible(Dispatchers.IO) { p.waitFor() }
                // Normally, the entire process tree has been cleared before the launcher exits, and the
                // pipeline is closed; in case there are still descendants occupying the pipeline, the
                // wait will not be infinite.
                runInterruptible(Dispatchers.IO) { reader.join(READER_GRACE_MS) }
                // There are still descendants occupying the pipe: turn off our end, and the reading
                // thread will end.
                if (reader.isAlive) runCatching { p.inputStream.close() }
                val (bytes, dropped) = collector.snapshot()
                return ProcOutcome(code, bytes, dropped, !finished, (System.nanoTime() - begin) / 1_000_000)
            } catch (e: CancellationException) {
                abandon(p)
                throw e
            }
        }

        /** First SIGTERM (the launcher clears the entire process tree after receiving it), and then SIGKILL after [DESTROY_GRACE_MS]. */
        private fun abandon(p: Process) {
            p.destroy()
            thread(isDaemon = true, name = "mina-sandbox-reaper") { if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        }
    }
}
