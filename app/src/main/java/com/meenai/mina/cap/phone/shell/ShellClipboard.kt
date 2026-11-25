package com.meenai.mina.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method

/**
 * The shell process directly accesses the system clipboard. In some systems, ClipboardManager will read
 * Settings before reading the clipboard, but app_process does not have the process registration of a
 * normal App and cannot obtain ContentProvider through ActivityThread. Binder still checks permissions
 * by package name, uid, user, and device; here it just avoids the extra Settings query of the Java
 * wrapper layer. Unrecognized interfaces (e.g. signatures from earlier versions) fall back to
 * ClipboardManager, which behaves the same as before.
 */
@SuppressLint("PrivateApi")
internal class ShellClipboard(private val context: Context) {
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }
    private val getClip by lazy { method("getPrimaryClip") }
    private val getDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }
    private val callerLabel by lazy {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(ShellContext.SHELL_PACKAGE, 0)).toString()
        }.getOrDefault(ShellContext.SHELL_PACKAGE)
    }

    val primaryClip: ClipData?
        get() = getClip.let { if (it == null) manager.primaryClip else call(it) as ClipData? }
    val primaryClipDescription: ClipDescription?
        get() = getDescription.let { if (it == null) manager.primaryClipDescription else call(it) as ClipDescription? }
    fun hasPrimaryClip(): Boolean = hasClip.let { if (it == null) manager.hasPrimaryClip() else call(it) as Boolean }
    // Writing still follows the framework's original ClipData preparation and clearing process, only
    // replacing the read path with additional Settings queries.
    fun setPrimaryClip(clip: ClipData) = manager.setPrimaryClip(clip)
    fun clearPrimaryClip() = manager.clearPrimaryClip()

    /** Find the interface based on capabilities: first identify the original signature, and then identify the known extended signature; if neither is found, null will be returned, and the caller will return to ClipboardManager. */
    private fun method(name: String): Method? {
        if (service == null) return null
        val type = runCatching { Class.forName("android.content.IClipboard") }.getOrNull() ?: return null
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!
        val candidates = listOf(
            // Android 14+: package name, attributionTag, userId, deviceId
            arrayOf(s, s, i, i),
            // Android 12–13: package name, attributionTag, userId
            arrayOf(s, s, i),
            // Some systems expand the reading to: package name, application name, privacy stack,
            // attributionTag, userId, deviceId, whether the user operates
            arrayOf(s, s, s, s, i, i, Boolean::class.javaPrimitiveType!!),
        )
        return candidates.firstNotNullOfOrNull { runCatching { type.getMethod(name, *it) }.getOrNull() }
    }

    private fun call(method: Method): Any? {
        val userId = Process.myUid() / 100_000
        val args: Array<Any?> = when (method.parameterTypes.size) {
            // The background save/restore does not require the user to click "Paste" and does not forge
            // user operations; the privacy stack is empty as the default value of the framework.
            7 -> arrayOf(ShellContext.SHELL_PACKAGE, callerLabel, "", null, userId, 0, false)
            // ShellContext is the system context of the default device, which is consistent with the
            // device used by the original ClipboardManager.
            4 -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId, 0)
            else -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId)
        }
        return method.invoke(service, *args)
    }
}
