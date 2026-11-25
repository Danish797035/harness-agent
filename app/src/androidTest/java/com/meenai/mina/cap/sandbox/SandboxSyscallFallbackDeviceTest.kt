package com.meenai.mina.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Some Android versions install the seccomp policy for the App to send SIGSYS to new system calls
 * (io_uring, clone3, faccessat2, epoll_pwait2, rseq...), and some allow it directly. Programs in the
 * sandbox on both platforms cannot be killed by signals: blocked calls receive "Not Supported" (ENOSYS)
 * and take their own retreat, and released calls get the kernel's own results; calls with paths must be
 * judged according to the path in the sandbox. All via product path AndroidSandbox.run → minarun →
 * PRoot.
 */
@RunWith(AndroidJUnit4::class)
class SandboxSyscallFallbackDeviceTest {
    private val sb = AndroidSandbox(SandboxSmokeTest.testDirs, { SandboxStatus.Ready(network = false) }, { AndroidSandbox.dns(context) }, bridges = true)

    private fun sh(command: String, timeoutMs: Long = 60_000): CommandResult =
        runBlocking { sb.run("dev-sigsys", listOf("/bin/sh", "-lc", command), null, timeoutMs) {} }

    @Test fun nodeEvaluatesJs() {
        val r = sh("node -e 'console.log(1+1)'")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("2", r.output.trim())
    }

    @Test fun nodeFsAndTimers() {
        val script = """
            const fs = require('fs');
            fs.writeFileSync('sigsys-node.txt', 'written');
            setTimeout(async () => {
              const text = await fs.promises.readFile('sigsys-node.txt', 'utf8');
              fs.unlinkSync('sigsys-node.txt');
              setImmediate(() => console.log('read:' + text));
            }, 50);
        """.trimIndent()
        val r = sh("cat > sigsys-node.js <<'EOF'\n$script\nEOF\nnode sigsys-node.js; rc=\$?; rm -f sigsys-node.js; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("read:written", r.output.trim())
    }

    /** musl's faccessat(..., AT_EACCESS) tries faccessat2 first; the answer must be consistent with the real permissions. */
    @Test fun effectiveAccessChecks() {
        val script = """
            import os
            open('sigsys-acc.txt', 'w').close()
            os.chmod('sigsys-acc.txt', 0o644)
            print(os.access('sigsys-acc.txt', os.R_OK, effective_ids=True),
                  os.access('sigsys-acc.txt', os.X_OK, effective_ids=True),
                  os.access('/bin/sh', os.X_OK, effective_ids=True),
                  os.access('sigsys-missing', os.F_OK, effective_ids=True))
            os.remove('sigsys-acc.txt')
        """.trimIndent()
        val r = sh(
            "cat > sigsys-acc.py <<'EOF'\n$script\nEOF\npython3 sigsys-acc.py; rc=\$?; rm -f sigsys-acc.py; " +
                "touch sigsys-t; [ -r sigsys-t ] && echo test-r-ok; rm -f sigsys-t; exit \$rc",
        )
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("True False True False", "test-r-ok"), r.output.trim().lines())
    }

    /**
     * The process is alive, and each call returns -1: ENOSYS if blocked by the platform; if the platform
     * releases it, the kernel will report an error (EINVAL) according to the invalid parameter here.
     * io_uring_setup is answered by PRoot itself ENOSYS (the path operation of io_uring is not called by
     * the system and cannot be converted by PRoot), and has nothing to do with the platform.
     */
    @Test fun trappedSyscallsReturnEnosys() {
        // io_uring_setup, clone3, epoll_pwait2, rseq(arm64 number)
        val script = """
            import ctypes, errno
            libc = ctypes.CDLL(None, use_errno=True)
            for nr in (425, 435, 441, 293):
                ctypes.set_errno(0)
                rc = libc.syscall(nr, 0, 0, 0, 0, 0, 0)
                print(nr, rc, errno.errorcode.get(ctypes.get_errno()))
        """.trimIndent()
        val r = sh("cat > sigsys-nr.py <<'EOF'\n$script\nEOF\npython3 sigsys-nr.py; rc=\$?; rm -f sigsys-nr.py; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        val lines = r.output.trim().lines()
        assertEquals(r.output, 4, lines.size)
        assertEquals("425 -1 ENOSYS", lines[0])
        for ((line, nr) in lines.drop(1).zip(listOf(435, 441, 293))) {
            assertTrue(r.output, line == "$nr -1 ENOSYS" || line == "$nr -1 EINVAL")
        }
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            SandboxSmokeTest.prepareTestSandbox()
        }
    }
}
