package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.net.LocalHosts
import com.meenai.mina.core.workspace.SandboxPaths
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** The original result of a process run; [timedOut] only means that the time limit on the Kotlin side has arrived. */
internal class ProcOutcome(val exitCode: Int, val output: ByteArray, val droppedBytes: Long, val timedOut: Boolean, val durationMs: Long)

/** Combining the launcher + PRoot's command line and environment variables. */
object ProcRunner {
    const val OUTPUT_LIMIT = 1 shl 20
    const val READ_CHUNK = 64 * 1024
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    /** When using a proxy, the local machine and LAN address are still directly connected (the same as Mina's own connection, see [LocalHosts]). */
    const val NO_PROXY = LocalHosts.NO_PROXY

    /**
     * [cwd]: The relative path under `/workspace`, the empty string is `/workspace`; [standIns]: The
     * substitute file name in `files/sandbox/proc/`, each is tied to /proc/<name>. /dev only binds the
     * devices listed in [ProotDevices] (does not bind the entire /dev, see the instructions there).
     */
    fun argv(
        dirs: SandboxDirs, workspace: File, cwd: String, timeoutSec: Int, network: Boolean, standIns: List<String>, command: List<String>,
        devices: List<String> = ProotDevices.bindArgs(),
    ): List<String> = listOf(
        dirs.lib("libharnessrun.so").path, "--timeout", timeoutSec.toString(), "--net", if (network) "on" else "off", "--",
        dirs.lib("libproot.so").path, "--kill-on-exit", "--link2symlink", "-0", "-r", dirs.rootfs.path,
    ) + devices + listOf("-b", "/proc", "-b", "/sys") +
        standIns.flatMap { listOf("-b", "${File(dirs.procStandIns, it).path}:/proc/$it") } +
        listOf(
            "-b", "${workspace.path}:${SandboxPaths.WORKSPACE}",
            "-b", "${dirs.skills.path}:${SandboxPaths.SKILLS}",
            "-b", "${dirs.skills.path}:${SandboxPaths.LEGACY_SKILLS}",
            "-w", if (cwd.isEmpty()) SandboxPaths.WORKSPACE else "${SandboxPaths.WORKSPACE}/$cwd",
        ) + command

    /** The environment is cleared first and only these are given; HARNESS_BRIDGE / HARNESS_TOKEN is not given when the bridge is not open. */
    fun env(
        dirs: SandboxDirs, bridgeName: String?, token: String?, conversationId: String,
        /** The container's network proxy; an empty string means not using it (see NetProxyStore). The caller is not allowed to give an empty string when connected to the Internet. */
        proxy: String = "",
    ): Map<String, String> = buildMap {
        put("PROOT_LOADER", dirs.lib("libproot_loader.so").path)
        put("PROOT_TMP_DIR", dirs.tmp.path)
        put("HOME", "/root")
        put("PATH", PATH)
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        // The current time zone of the phone is read every time it is started; Alpine's libc does not
        // read the Android system time zone.
        put("TZ", timezone())
        bridgeName?.let {
            put("HARNESS_BRIDGE", it)
            put("MINA_BRIDGE", it)
        }
        token?.let {
            put("HARNESS_TOKEN", it)
            put("MINA_TOKEN", it)
        }
        put("HARNESS_CONVERSATION", conversationId)
        put("MINA_CONVERSATION", conversationId)
        // The proxy is only used for commands in the container: common tools recognize these variables
        // (some only recognize uppercase letters); the local address does not use the proxy
        if (proxy.isNotBlank()) {
            for (name in listOf("http_proxy", "https_proxy", "all_proxy")) {
                put(name, proxy)
                put(name.uppercase(), proxy)
            }
            put("no_proxy", NO_PROXY)
            put("NO_PROXY", NO_PROXY)
        }
    }

    /**
     * The mobile time zone is written as TZ in the sandbox. The region name (such as Asia/Shanghai) is
     * given as it is, parsed by the tzdata that comes with the running environment, and the historical
     * offset and daylight saving time are retained; the fixed offset (GMT+08:00 and the like) cannot be
     * given as it is: the symbol in POSIX's TZ is opposite to it. The whole point is replaced with a
     * tzdata name such as Etc/GMT-8, and the rest is written in POSIX format `<+0545>-05:45`; when the
     * time zone cannot be read, UTC is used.
     */
    internal fun timezone(zone: () -> ZoneId = ZoneId::systemDefault): String {
        val z = runCatching(zone).getOrNull() ?: return "UTC"
        val fixed = z as? ZoneOffset ?: z.id.takeIf { it.startsWith("GMT") || it.startsWith("UT") }?.let { z.normalized() as? ZoneOffset }
        val s = fixed?.totalSeconds ?: return z.id
        if (s == 0) return "UTC"
        if (s % 3600 == 0 && s / 3600 in -12..14) return "Etc/GMT" + (if (s > 0) "-" else "+") + abs(s / 3600)
        val a = abs(s)
        val hm = String.format(Locale.ROOT, "%02d%02d", a / 3600, a / 60 % 60)
        val posix = String.format(Locale.ROOT, "%02d:%02d", a / 3600, a / 60 % 60) +
            (if (a % 60 != 0) String.format(Locale.ROOT, ":%02d", a % 60) else "")
        return if (s > 0) "<+$hm>-$posix" else "<-$hm>$posix"
    }

    /** The read stream ends, and the result is [OutputCollector]. */
    internal fun collect(input: InputStream, limit: Int = OUTPUT_LIMIT): Pair<ByteArray, Long> =
        OutputCollector(limit).apply { drain(input) }.snapshot()
}

/** Read the stream to the end; leave at most [limit] bytes, read any more and discard them and count them (the command will be stuck on the write output if you do not finish reading). Snapshots can also be taken while reading. */
internal class OutputCollector(private val limit: Int = ProcRunner.OUTPUT_LIMIT) {
    private val kept = java.io.ByteArrayOutputStream(minOf(limit, ProcRunner.READ_CHUNK))
    private var dropped = 0L

    fun drain(input: InputStream) {
        val buf = ByteArray(ProcRunner.READ_CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(this) {
                val room = limit - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
                if (n > room) dropped += n - maxOf(room, 0)
            }
        }
    }

    @Synchronized fun snapshot(): Pair<ByteArray, Long> = kept.toByteArray() to dropped
}
