package com.meenai.mina.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.cap.sandbox.SandboxPathContract
import com.meenai.mina.core.cap.sandbox.SandboxPort
import org.junit.runner.RunWith
import java.io.File

/** The path rule contract runs on the file system of the real device (the behavior of symbolic links and canonicalFile is subject to Android). */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxPathContractDeviceTest : SandboxPathContract() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    override fun sandbox(): SandboxPort = AndroidSandbox(context, SandboxState(context))
    override fun outsideDir(): File = File(context.filesDir, "contract-outside").apply { mkdirs() }
}
