package com.meenai.mina.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.meenai.mina.core.shell.HelperHandoff
import com.meenai.mina.core.shell.HelperRestorePolicy
import com.meenai.mina.core.shell.PermissionCommands
import com.meenai.mina.core.shell.RestoreCommands
import com.meenai.mina.core.shell.RestoreWish
import java.io.File
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * Entry point to the built-in helper process. Mina starts it as a shell through native wireless
 * debugging (adbd's exec: service): `CLASSPATH=<base.apk> app_process /system/bin
 * --nice-name=<package name>:helper com.meenai.mina.shell.HelperMain <package name> <Mina's uid> <signature fingerprint>`, and the one-time
 * password reads one line from the standard input (see [HelperHandoff.command]). It is also the one that
 * starts when the computer runs the "Start with Computer" script through USB debugging. The commands and
 * parameters are the same (see [HelperHandoff.usbScript]).
 *
 * The privilege logic is all in [ShellService]; here it is only responsible for handing over its Binder
 * to Mina, and not exiting after the Mina process ends: undoing the changes left by Mina, and handing
 * over again when Mina gets up again. Mina dies but does not come back for a while, and when the wish it
 * handed over ([RestoreWish]) is about to be started, its accessibility service is written back to the
 * system list, and then its process is started through the handover provider (see
 * [HelperRestorePolicy]).
 */
object HelperProcess {
    private const val TAG = "MinaHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: HelperMain <package> <uid> <cert-sha256> (nonce on stdin)")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certPin = args[2]
        // Close after reading: standard input is only used to pass passwords
        val launchNonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        runCatching { System.`in`.close() }
        if (launchNonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            exitProcess(2)
        }
        // When ShellContext builds its own ActivityThread, it must have a main thread Looper.
        Looper.prepareMainLooper()
        val service = ShellService(allowedUid = appUid)
        // For apps that were killed in mute last time and could not be restored in time, please return
        // the sound first.
        service.recoverMuted()
        val helper = Helper(service, packageName, appUid, certPin)
        helper.start(launchNonce)
        Looper.loop()
    }

    private class Helper(
        private val service: ShellService,
        private val packageName: String,
        private val appUid: Int,
        private val certPin: String,
    ) {
        private val context: Context = ShellContext.create()
        private val authority = HelperHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        /** The handover and Mina death processing are all arranged on this thread, without interleaving with each other or blocking the main thread. */
        private val worker = Handler(HandlerThread("helper-handoff").apply { start() }.looper)
        /** The password for the next handover (reissued when Mina accepted it last time). */
        private var nonce: String? = null
        /** The token handed to us by the Mina process; null means Mina is not around now. */
        private var appToken: IBinder? = null
        private var retrying = false
        /** The last wish Mina handed over; the assistant did nothing when he first got up. */
        private var wish = RestoreWish.NONE
        private val restorePolicy = HelperRestorePolicy()
        /** Every time Mina dies, one will be added: during this period of waiting, Mina comes back and dies again, and the previous wait is invalid. */
        private var deaths = 0

        init {
            service.onRestoreWish = { flags ->
                worker.post {
                    val next = RestoreWish.fromFlags(flags)
                    if (next != wish) Log.i(TAG, "restore wish: $next")
                    wish = next
                }
            }
        }

        fun start(launchNonce: String) = worker.post {
            when (deliver(launchNonce)) {
                Outcome.Accepted -> {
                    // Mina accepted this one: all previously created (or remaining) helper instances
                    // should go.
                    killOtherInstances()
                    watchUid()
                }
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> exitProcess(1)
            }
        }

        private enum class Outcome { Accepted, Rejected, Failed }

        /** Get Mina's provider through ActivityManager (same as `content call`), and hand over the Binder and self-reported uid/pid. */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?): Outcome {
            if (n == null) return Outcome.Rejected
            // The package name and uid may be occupied by other apps that have been reinstalled: If the
            // signature does not match, do not submit it to Binder, undo the changes and exit.
            if (!signedAsPinned()) {
                Log.e(TAG, "$packageName is missing or not signed with the pinned certificate; exiting")
                runCatching { service.releaseAll() }
                exitProcess(0)
            }
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                    IBinder::class.java, String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed
            try {
                // The authority with the same name can only belong to one package; it is better to check
                // and do not hand over the Binder to apps other than Mina.
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, n)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putString(HelperHandoff.KEY_APK, System.getProperty("java.class.path"))
                    restorePolicy.report(System.currentTimeMillis()).takeUnless { it.empty }?.let { r ->
                        putLong(HelperHandoff.KEY_RESTORED_AT, r.lastRestoredAtMs)
                        putInt(HelperHandoff.KEY_RESTORES, r.recent)
                        putLong(HelperHandoff.KEY_GAVE_UP_AT, r.gaveUpAtMs)
                    }
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(provider, caller, authority, HelperHandoff.METHOD_ATTACH, null, extras) as Bundle?
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) {
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_TOKEN) ?: return Outcome.Rejected
                nonce = result.getString(HelperHandoff.KEY_NEXT)
                appToken = appBinder
                try {
                    appBinder.linkToDeath({ worker.post { onAppDied(appBinder) } }, 0)
                } catch (t: Throwable) {
                    worker.post { onAppDied(appBinder) }
                }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        /** The Mina process is gone: the screen, mute, and clipboard changes it created are removed (the same as when the service ended), and the assistant itself remains. */
        private fun onAppDied(token: IBinder) {
            if (appToken !== token) return
            appToken = null
            runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "cleanup failed", it) }
            if (!installed()) {
                Log.i(TAG, "$packageName is gone; exiting")
                exitProcess(0)
            }
            Log.i(TAG, "$packageName died; waiting for it to come back")
            val death = ++deaths
            worker.postDelayed({ if (death == deaths) maybeRestore() }, HelperRestorePolicy.GRACE_MS)
        }

        /**
         * Mina has been dead for a while and hasn't come back yet: she decides whether to poop or not
         * according to her last wish. If you pull it, first write it back to the accessibility service
         * (the system will remove it when it is forcibly stopped), and then transfer it again: when you
         * get Mina's provider, the system will start its process (the provider is not subject to
         * background startup restrictions, and does not display the interface), the Application is
         * created as usual, and the capsule and other services follow. The shell can get the provider of
         * any App (the same path used by the `content` command).
         */
        private fun maybeRestore() {
            val now = System.currentTimeMillis()
            when (val d = restorePolicy.decide(wish, appBack = appToken != null || retrying, now)) {
                HelperRestorePolicy.Decision.Restore -> Unit
                HelperRestorePolicy.Decision.AppBack -> return
                else -> {
                    Log.i(TAG, "not restoring $packageName: $d")
                    return
                }
            }
            Log.i(TAG, "restoring $packageName (${restorePolicy.report(now).recent} in the last window)")
            if (wish.accessibility) restoreAccessibility()
            retrying = true
            redeliver(0)
        }

        /** Add Mina's accessibility service back to the system list and turn on the main switch; leave other services untouched. If it fails, just keep a log and pull Mina as usual (it will repair itself after it gets up). */
        private fun restoreAccessibility() {
            val me = PermissionCommands.accessibilityService(packageName)
            val current = service.runCommand(PermissionCommands.READ_ACCESSIBILITY).getString("output")
            for (cmd in RestoreCommands.accessibility(current, me)) {
                val r = service.runCommand(cmd)
                if (r.getInt("exitCode", -1) != 0) {
                    Log.w(TAG, "restore accessibility: '$cmd' failed: ${r.getString("output")?.trim()}")
                    return
                }
            }
            Log.i(TAG, "accessibility service of $packageName written back")
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun installed(): Boolean = runCatching {
            context.packageManager.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(context.packageManager, packageName, 0, userId)
            true
        }.getOrElse { t ->
            // Exit only when the package is no longer available; when reflection is unavailable and
            // cannot be seen clearly, it is treated as if it is still there.
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            cause !is android.content.pm.PackageManager.NameNotFoundException
        }

        /**
         * Stare at Mina's uid: when the process restarts (from non-existence to any state), the system
         * will report it once, and then hand over the Binder again. The uid importance monitor of
         * ActivityManager is used (PACKAGE_USAGE_STATS is required, the shell has it), and the callback
         * is in the binder thread. When the registration fails, Mina will take care of it: before the
         * handover, a new assistant will be created through adbd, and this will be terminated after the
         * new one is accepted.
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun watchUid() {
            runCatching {
                // Create an ActivityManager using the context of the shell package name: when
                // registering, the system checks permissions based on the caller's package name.
                val manager = ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                    .apply { isAccessible = true }.newInstance(context, worker)
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val listener = Proxy.newProxyInstance(HelperProcess::class.java.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
                    when (method.name) {
                        "onUidImportance" -> { onUidImportance(a[0] as Int, a[1] as Int); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === a?.getOrNull(0)
                        "toString" -> "MinaHelperUidListener"
                        else -> null
                    }
                }
                ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
                    .invoke(manager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            }.onFailure { Log.w(TAG, "cannot watch $packageName; relying on the app to restart the helper", it) }
        }

        private fun onUidImportance(uid: Int, importance: Int) {
            if (uid != appUid || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) return
            worker.post { if (appToken == null && !retrying) redeliver(0) }
        }

        /** Re-handover; if the provider is temporarily unavailable, try again after a while. If it still doesn't work, wait until Mina comes up next time. */
        private fun redeliver(attempt: Int) {
            if (appToken != null) return
            when (deliver(nonce)) {
                Outcome.Accepted -> retrying = false
                // Mina does not recognize this assistant (has been reinstalled, unpaired, or has been
                // replaced with a new one): Exit
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> {
                    retrying = attempt + 1 < RETRY_DELAYS_MS.size
                    if (retrying) worker.postDelayed({ redeliver(attempt + 1) }, RETRY_DELAYS_MS[attempt + 1])
                }
            }
        }

        /** The [packageName] installed now still has the same uid and signature certificate fingerprint as those given by Mina at startup. If you can't see clearly, it will be regarded as inconsistent. */
        @SuppressLint("DiscouragedPrivateApi")
        private fun signedAsPinned(): Boolean = runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (userId == 0) pm.getPackageInfo(packageName, flags)
            else pm.javaClass.getMethod("getPackageInfoAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, packageName, flags, userId) as PackageInfo
            val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            info.applicationInfo?.uid == appUid && HelperHandoff.certPin(signers) == certPin
        }.getOrElse { t ->
            Log.w(TAG, "cannot read signing info of $packageName", t)
            false
        }

        /** Only recognize processes with exactly the same process name and the same uid: other app_processes cannot be accidentally damaged. */
        private fun killOtherInstances() {
            val me = Process.myPid()
            val name = HelperHandoff.processName(packageName)
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@forEach
                val argv0 = String(cmdline.takeWhile { it != 0.toByte() }.toByteArray())
                if (argv0 == name) {
                    Log.i(TAG, "stopping older helper $pid")
                    Process.killProcess(pid)
                }
            }
        }

        companion object {
            private const val PER_USER_RANGE = 100_000
            private val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 3_000, 10_000)
        }
    }
}
