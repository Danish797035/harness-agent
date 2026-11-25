package com.meenai.mina.cap.sandbox

import android.content.Context
import android.util.Log
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Prepare the running environment in the background: when the App starts, after Mina upgrade, after
 * reset, when the task is to be used but is not ready yet. Invisible to the user, only a low priority
 * neutral notification ([SandboxForeground]) during preparation. Not connected to the Internet.
 */
object RuntimePreparer {
    private const val TAG = "RuntimePreparer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** Preparation has failed in this process: the App no longer automatically retries when it starts, and the task takes time (retryFailed of [ensure]) before trying again. */
    @Volatile private var failed = false

    /** Preparations in progress; not null. */
    val current: Job? get() = job?.takeIf { it.isActive }

    /** If preparation is needed, start and return the preparation (return the one if it is already being prepared); there is no need to return null. */
    @Synchronized
    fun ensure(context: Context, retryFailed: Boolean = false): Job? {
        current?.let { return it }
        val app = context.applicationContext
        val state = SandboxState.get(app)
        if (!needsWork(state.status.value, state.installedVersion, BundledRuntime.VERSION, skipBroken = failed && !retryFailed)) return null
        val j = scope.launch {
            try {
                RootfsInstaller.create(app).install { _, _ -> }
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "runtime preparation failed", e)
            }
        }
        job = j
        // The foreground service prevents the process from being recycled during the preparation period
        // and displays a neutral notification; in the background, the system does not allow the
        // foreground service to be started and only prepares the process in the process.
        try {
            SandboxForeground.start(app)
        } catch (e: Exception) {
            Log.i(TAG, "preparing without the foreground notification: $e")
        }
        return j
    }

    /** Do you need to be prepared: not installed, installed with an old version (Mina has been upgraded), or errors (unless [skipBroken]). Unsupported or ongoing immobility. */
    internal fun needsWork(status: SandboxStatus, installed: String?, bundled: String, skipBroken: Boolean): Boolean = when (status) {
        is SandboxStatus.Unsupported, is SandboxStatus.Installing -> false
        is SandboxStatus.Ready -> installed != bundled
        SandboxStatus.NotInstalled -> true
        is SandboxStatus.Broken -> !skipBroken
    }
}
