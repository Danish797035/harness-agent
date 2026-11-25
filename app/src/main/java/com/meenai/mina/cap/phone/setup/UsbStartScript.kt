package com.meenai.mina.cap.phone.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.meenai.mina.cap.phone.shell.HelperHub
import com.meenai.mina.core.shell.HelperHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Start with computer" script: The computer runs `adb shell sh <external file directory>/usb-start.sh` through USB
 * debugging. It starts with the same assistant as the wireless debugging, and the handover also uses the
 * same [HelperHub.offer] (also check shell identity, password, signature). Write a script with a
 * one-time password when the assistant is not available; replace it with instructions without a password
 * when the assistant is connected, and no usable passwords will be left on the disk. The external file
 * directory cannot be written by the app itself without permission, and the shell can also read it;
 * other apps cannot read it.
 */
class UsbStartScript(context: Context, private val hub: HelperHub) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** The location of the script on the phone; null if external storage is temporarily unavailable. */
    private fun file(): File? = app.getExternalFilesDir(null)?.let { File(it, HelperHandoff.USB_SCRIPT) }

    /** The command to be run on the computer (it is what is displayed and copied on the boot page). */
    fun command(): String =
        HelperHandoff.usbCommand(file()?.absolutePath ?: "/storage/emulated/0/Android/data/${app.packageName}/files/${HelperHandoff.USB_SCRIPT}")

    /** Adjustment when the app starts: Rewrite the script according to whether the assistant is present or not, and whether the password has been invalidated (unpaired). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { HelperHandoff.usbScriptWrites(hub.shell, hub.nonceResets).collect { write(connected = it) } }
    }

    /** Someone wants to use the assistant but does not have it. When opening the boot page, call: Write it again (if the password is new, keep it, see [HelperHub.usbNonce]). */
    fun refresh() {
        scope.launch { write(connected = hub.shell.value != null) }
    }

    @Synchronized
    private fun write(connected: Boolean) {
        val f = file() ?: return
        val text = runCatching {
            HelperHandoff.usbFile(
                connected, app.applicationInfo.sourceDir, app.packageName, Process.myUid(), { ownCertPin(app) }, hub::usbNonce, f.absolutePath,
            )
        }.getOrElse {
            Log.w(TAG, "cannot build ${f.name}", it)
            return
        }
        // Write the temporary file first and then rename it: when the computer is reading, it will not
        // read half of it
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "cannot write ${f.name}", it) }
    }

    private companion object {
        const val TAG = "MinaUsbStart"
    }
}

/** The fingerprint of Mina's signature certificate: the assistant uses it to check Mina before each handover (shared by both startup methods), which is taken from the App's own installation information. */
internal fun ownCertPin(context: Context): String {
    val signers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .signingInfo?.apkContentsSigners?.map { it.toByteArray() }
    return HelperHandoff.certPin(signers) ?: error("cannot read own signing certificate")
}
