package com.meenai.mina.core.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The agreement shared by both parties when the assistant process hands the Binder to Mina: the
 * provider's method name and Bundle key, the assistant's process name, the startup command, and the
 * checks that Mina must pass before accepting it.
 */
object HelperHandoff {
    const val SHELL_UID = 2000
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "com.meenai.mina.shell.HelperMain"

    // Assistant → Mina
    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"
    /** The record of the assistant pulling Mina up (see [HelperRestoreReport]); if not pulled up, it will not be recorded. */
    const val KEY_RESTORED_AT = "restoredAt"
    const val KEY_RESTORES = "restores"
    const val KEY_GAVE_UP_AT = "restoreGaveUpAt"

    // Mina → assistant
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"

    fun authority(packageName: String) = "$packageName.helper"

    /** The process name of the assistant (--nice-name of app_process); also used to identify old assistant instances. */
    fun processName(packageName: String) = "$packageName:helper"

    /**
     * A command executed by adbd's exec: service (adbd runs it with `sh -c`). As soon as the stream is
     * closed, adbd kills all the processes in the sh group, so the assistant uses setsid to start a new
     * session; sh waits for `read` until the app receives the handover and then closes the stream, so as
     * to avoid sh exiting before the setsid takes effect and the assistant being killed.
     *
     * The password does not enter the command line (the same shell process can read
     * /proc/<pid>/cmdline): App opens the stream and writes a line [stdinLine], sh reads the variable,
     * and then uses the built-in echo to pass it to the assistant through the pipe; the standard input
     * of the assistant is this pipe, not the adb stream. [certPin] is the fingerprint of Mina's
     * signature certificate. It is not a secret and is placed in the parameters.
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /** The script file name of "Start with computer" is placed in the App's external file directory (the computer reads it as a shell through USB debugging). */
    const val USB_SCRIPT = "usb-start.sh"

    /** The master user's external storage is also called /sdcard in the shell; commands shown to the user use this shorthand. Other users write according to the actual path. */
    private const val PRIMARY_STORAGE = "/storage/emulated/0/"

    /** The command to be run on the computer. [scriptPath] is the actual path of the script on the phone. */
    fun usbCommand(scriptPath: String): String =
        "adb shell sh " + (if (scriptPath.startsWith(PRIMARY_STORAGE)) "/sdcard/" + scriptPath.removePrefix(PRIMARY_STORAGE) else scriptPath)

    /**
     * The startup script run by the computer through USB debugging: starts with the same assistant and
     * the same parameters as [command], and the password is changed to [nonce] (written in the script,
     * see [HandoffNonces.usbNonce]). As soon as the adb shell session ends (the command is run and the
     * connection is unplugged), adbd kills its group of processes: the assistant uses nohup to ignore
     * the hang-up signal, then uses setsid to start a new session, and puts it in a sub-shell in the
     * background (after the sub-shell exits, it is managed by init). The standard output and error are
     * lost and are no longer connected to the adb stream; the script waits for a while for setsid to
     * take effect. If the assistant process is still there, say "Starting". If it is not there (mostly
     * because the password has expired or Mina does not recognize it), ask the user to open Mina and run
     * it again. Only use the commands available in the original Android sh and toybox. If you are not a
     * shell (not running through adb), you can't afford it: Mina only accepts assistants sent by shell.
     */
    fun usbScript(apkPath: String, packageName: String, appUid: Int, certPin: String, nonce: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        val again = usbCommand(scriptPath)
        return """
            |#!/system/bin/sh
            |# Written by Mina. Starts Mina's phone helper from a computer: $again
            |if [ "${'$'}(id -u)" != "$SHELL_UID" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |if [ ! -f ${quote(apkPath)} ]; then
            |  echo "Mina was updated or reinstalled. Open Mina once, then run this command again."
            |  exit 1
            |fi
            |n=${quote(nonce)}
            |(echo "${'$'}n" | CLASSPATH=${quote(apkPath)} nohup setsid app_process /system/bin --nice-name=$name $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} >/dev/null 2>&1 &)
            |sleep 2
            |if pidof $name >/dev/null 2>&1; then
            |  echo "Mina helper starting... Open Mina to confirm phone control is ready."
            |  echo "It keeps running after you unplug the cable. After a phone restart, run this command again."
            |else
            |  echo "Mina helper did not start. Open Mina once, then run this command again."
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /**
     * When to rewrite the "Start with computer" script: when the assistant comes or leaves, and when the
     * password is completely invalidated ([HandoffNonces.resets], such as unpairing); the value sent is
     * whether the assistant is present at the moment. The latter assistant may not be there all the
     * time. If you don't rewrite it, the script will be left with invalid passwords.
     */
    fun usbScriptWrites(helper: Flow<Any?>, resets: Flow<Int>): Flow<Boolean> = combine(helper, resets) { h, _ -> h != null }

    /**
     * The script to be written at this moment: the assistant writes [usbStub] without a password and
     * does not ask for a password if it is connected to ([connected]); otherwise, it writes [usbScript]
     * with a new password. [certPin] and [nonce] are only taken when writing a script with a password.
     */
    fun usbFile(
        connected: Boolean, apkPath: String, packageName: String, appUid: Int, certPin: () -> String, nonce: () -> String, scriptPath: String,
    ): String =
        if (connected) usbStub(packageName, scriptPath)
        else usbScript(apkPath, packageName, appUid, certPin(), nonce(), scriptPath)

    /** The script that is replaced when the assistant is connected: no password, just say one sentence. If the process is no longer there (for example, the phone has been restarted and Mina has not been opened yet), please ask the user to open Mina first. */
    fun usbStub(packageName: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        return """
            |#!/system/bin/sh
            |# Written by Mina. The phone helper is connected, so this file holds no start code.
            |if pidof $name >/dev/null 2>&1; then
            |  echo "Mina helper is already running. Nothing to do."
            |else
            |  echo "Mina helper is not running. Open Mina once, then run this command again: ${usbCommand(scriptPath)}"
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /** SHA-256 fingerprint of the signing certificate (DER); if there are multiple signers, sort them with commas. Returns null if there is no certificate. */
    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) } }
            .sorted().joinToString(",")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    enum class Reject {
        /** The caller is not a shell(uid 2000). */
        NotShell,
        /** The uid/pid reported by the assistant does not match the caller seen by Binder. */
        IdMismatch,
        /** The assistant is not running the currently installed APK (updated by Mina): let it exit and start a new one. */
        StaleApk,
        NoBinder,
        BadNonce,
        /** The process in which Binder is located is not the one that is handed over. */
        BinderElsewhere,
    }

    /** Check that can be done without touching the Binder; by returning null. The password will be checked by [HandoffNonces]. */
    fun check(
        callingUid: Int, callingPid: Int, reportedUid: Int, reportedPid: Int,
        helperApk: String?, appApk: String, hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

/**
 * Several persistent passwords: [resume] is used for the next handover of the running assistant,
 * [launch] is used for the new assistant who just started from adbd, [launchAt] is the issuance time of
 * [launch] (the number of milliseconds since booting); [usb] is written in the "Start with computer"
 * script, [usbAt] is its issuance time (number of milliseconds on the wall clock: the script will still
 * work across reboots).
 */
data class NonceSlots(
    val resume: String? = null, val launch: String? = null, val launchAt: Long = 0,
    val usb: String? = null, val usbAt: Long = 0,
)

interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

/**
 * Mina generates,one-time valid communication commands. When any one is used, several will be
 * invalidated, and a new one [NonceSlots.resume] will be issued to the assistant who accepted it; it is
 * saved so that after the Mina process is killed, the living assistant will recognize it when it is
 * handed over again. [clock] measures the age of the startup password (since booting), and [wallClock]
 * measures the age of the "start with computer" password.
 */
class HandoffNonces(
    private val store: NonceStore,
    private val clock: () -> Long,
    private val wallClock: () -> Long = clock,
    private val random: () -> String = ::randomNonce,
) {
    @Synchronized
    fun issueLaunch(): String = random().also { store.save(store.load().copy(launch = it, launchAt = clock())) }

    /**
     * The password written into the "Start with Computer" script. If the one you have is still new (more
     * than half of the remaining time is left), keep using it to avoid it being replaced while the
     * computer is running the script; otherwise, replace it with another one. Like other passwords, they
     * become invalid after being used once (see [accept]).
     */
    @Synchronized
    fun usbNonce(): String {
        val s = store.load()
        val age = wallClock() - s.usbAt
        if (s.usb != null && age in 0..USB_TTL_MS / 2) return s.usb
        return random().also { store.save(s.copy(usb = it, usbAt = wallClock())) }
    }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val age = clock() - s.launchAt
        // The startup password is only valid during the handover waiting period; if it is not used, it
        // will become invalid after expiration.
        val launch = s.launch?.takeIf { age in 0..LAUNCH_TTL_MS }
        // The password in the script is waiting for the user to plug in and run the command. The
        // password should be longer; it will not be recognized if it expires or the clock goes back.
        val usb = s.usb?.takeIf { wallClock() - s.usbAt in 0..USB_TTL_MS }
        return same(nonce, s.resume) or same(nonce, launch) or same(nonce, usb)
    }

    /** If the password is correct, use it and return the new password, if not, return null. */
    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    private val _resets = MutableStateFlow(0)
    /** The number of times the password has been invalidated as a whole ([clear]): If it changes, it means that the written password (such as the one in the script) cannot be used. */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    @Synchronized
    fun clear() {
        store.save(NonceSlots())
        _resets.value++
    }

    private fun same(a: String, b: String?) =
        b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        /** Covers the total time for the app to open the stream and wait for the handover, and the engine to wait for another round of handover. */
        const val LAUNCH_TTL_MS = 30_000L
        /**
         * The aging of the "Start with computer" password: After the script is written, it may take a
         * while for the user to plug in and run the command (or run it again after restarting). Mina
         * rewrites the script every time it is opened, cannot see the assistant, or opens the boot page,
         * and replaces it with a new one after half use.
         */
        const val USB_TTL_MS = 24 * 60 * 60_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
