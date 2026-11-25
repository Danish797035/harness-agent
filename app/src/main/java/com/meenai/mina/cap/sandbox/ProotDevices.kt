package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.WorkspaceFileGate
import java.io.File

/**
 * /dev visible in PRoot. Not binding the entire /dev:`/dev/socket/` (such as dnsproxyd, which allows the
 * system to do domain name resolution for the sandbox) and binder devices (`/dev/binder`,
 * `/dev/hwbinder`, `/dev/vndbinder`, which can take data out through system services) can bypass the
 * network disconnection, so only the following character devices are bound; `/dev/fd`, `/dev/stdin`,
 * etc. create symbolic links pointing to /proc in the root file system.
 */
object ProotDevices {
    /** Only bind if it exists on the host; bind the same path to the same location in the sandbox. */
    val DEVICE_BINDS = listOf(
        "/dev/null",
        "/dev/zero",
        "/dev/random",
        "/dev/urandom",
        "/dev/tty",
        "/dev/ptmx",
        "/dev/pts",
    )

    /** Symbolic link in the root file system /dev: name → target. */
    val DEV_SYMLINKS = linkedMapOf(
        "fd" to "/proc/self/fd",
        "stdin" to "/proc/self/fd/0",
        "stdout" to "/proc/self/fd/1",
        "stderr" to "/proc/self/fd/2",
    )

    /** `-b` parameters of PRoot (only devices that exist on the host). */
    fun bindArgs(exists: (String) -> Boolean = { File(it).exists() }): List<String> =
        DEVICE_BINDS.filter(exists).flatMap { listOf("-b", it) }

    /** It is correct to create the /dev directory and [DEV_SYMLINKS]; in the root file system. When the link: dev is replaced, it will not be built outside. */
    fun prepareRootfsDev(rootfs: File, gate: WorkspaceFileGate) {
        for ((name, target) in DEV_SYMLINKS) gate.symlink(rootfs, "dev/$name", target)
    }
}
