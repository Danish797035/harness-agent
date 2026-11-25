package com.meenai.mina.cap.sandbox

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.UUID

/**
 * Real-machine use case of the bridge: The `mina` command in the sandbox calls back to Mina through the
 * abstract socket. Use the installed sandbox (both python3 and mina clients are installed during
 * installation); skip if not installed.
 */
@RunWith(AndroidJUnit4::class)
class BridgeDeviceTest {
    private val shared = Collections.synchronizedList(mutableListOf<SharedFile>())
    private val sb = AndroidSandbox(context, SandboxState.get(context)) { shared += it }
    private val progress = Collections.synchronizedList(mutableListOf<String>())

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("bridge-ws", listOf("/bin/sh", "-lc", command), null, 30_000) { progress += it } }

    // Use the installed sandbox (python3 and mina client were installed during installation); skip it if
    // it is not installed. The test does not unblock the root file system by yourself.
    @Before fun needsInstalledSandbox() {
        assumeTrue("sandbox not installed", SandboxState.get(context).status.value is SandboxStatus.Ready)
        assumeTrue("python3 not installed in the rootfs",
            Files.exists(File(SandboxDirs(context).rootfs, "usr/bin/python3").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    // Test files exported to "Download/Mina" are deleted and do not remain in the user's download
    // directory.
    @After fun deleteExported() {
        for (f in shared) runCatching { context.contentResolver.delete(Uri.parse(f.uri), null, null) }
        SandboxDirs(context).workspace("bridge-ws").deleteRecursively()
    }

    @Test fun progressReachesCallback() {
        val r = sh("mina progress 第1步")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("第1步"), progress.toList())
    }

    @Test fun shareCallsExporter() {
        // The name contains a random string, and there will be no name collision in the download
        // directory; files outside the workspace are first rejected by path rules.
        val name = "mina-bridge-test-${UUID.randomUUID().toString().take(8)}.txt"
        val r = sh("echo hi > $name; harness share $name; echo rc=$?; harness share /etc/passwd; echo rc=$?")
        assertTrue(r.output, r.output.contains("\"savedAs\":\"Download/Harness/$name\""))
        assertTrue(r.output, r.output.contains("rc=0"))
        assertTrue(r.output, r.output.contains("path_outside_workspace"))
        assertEquals(r.output, 1, Regex("rc=1").findAll(r.output).count())
        assertEquals(listOf("Download/Harness/$name"), shared.map { it.savedAs })
    }

    @Test fun bridgeWorksWithNetOff() {
        val r = sh("harness info")
        assertEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("\"network\":false"))
        assertTrue(r.output, r.output.contains("\"workspace\":\"/workspace\""))
    }

    @Test fun sessionClosedAfterCommand() {
        val name = sh("echo \$MINA_BRIDGE").output.trim()
        assertTrue(name, name.startsWith("mina-bridge-"))
        val refused = try {
            LocalSocket().use { it.connect(LocalSocketAddress(name)) }
            false
        } catch (e: IOException) {
            true
        }
        assertTrue("old bridge name still accepts connections", refused)
    }

    @Test fun wrongTokenIsRejected() {
        val r = sh("MINA_TOKEN=forged mina info")
        assertEquals(r.output, 1, r.exitCode)
        assertTrue(r.output, r.output.contains("bad_token"))
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
