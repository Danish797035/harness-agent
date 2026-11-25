package com.meenai.mina.task

import android.content.Context
import android.os.PowerManager
import com.meenai.mina.core.agent.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface WakeLockPort {
    fun acquire()
    fun release()
}

/**
 * The screen does not turn off when the task is running: hold the screen wake lock and do not change any
 * system settings or brightness. After the screen is turned off, the system will discard the injected
 * touch and then lock it, so the screen cannot be turned off automatically while the Agent is working;
 * however, the screen does not have to be fully bright when the user is not operating, and it will dim
 * as usual when the system times out (see [ScreenWakeLock]).
 *
 * Only held while Running. The screen does not need to be on for the Agent when waiting for
 * takeover/answer/continue/unlock, and then hold [GRACE_MS] (the user is probably about to process,
 * don't let the screen go out in front of his eyes) and then release it, and hold it again when the task
 * continues to run. End, failure, and cancellation are released immediately. The lock follows the
 * process: the system automatically takes back Mina when it exits abnormally, and no recovery persistent
 * is required. The user can still turn off the screen by pressing the power button (without
 * ACQUIRE_CAUSES_WAKEUP). At this time, the gate will wait for the task to be unlocked.
 */
class KeepAwake(private val lock: WakeLockPort, private val scope: CoroutineScope) {
    private var held = false
    private var pendingRelease: Job? = null
    private var closed = false

    @Synchronized
    fun onState(state: TaskState) {
        if (closed) return
        when (state) {
            is TaskState.Running -> {
                pendingRelease?.cancel()
                pendingRelease = null
                if (!held) {
                    lock.acquire()
                    held = true
                }
            }
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> release()
            else -> if (held && pendingRelease == null) {
                pendingRelease = scope.launch {
                    delay(GRACE_MS)
                    synchronized(this@KeepAwake) {
                        pendingRelease = null
                        release()
                    }
                }
            }
        }
    }

    @Synchronized
    fun release() {
        pendingRelease?.cancel()
        pendingRelease = null
        if (held) {
            lock.release()
            held = false
        }
    }

    /** Task ends: Release and ignore any state reached afterward (late release in the bypass coroutine cannot retake the lock). */
    @Synchronized
    fun close() {
        closed = true
        release()
    }

    companion object {
        const val GRACE_MS = 30_000L
    }
}

/**
 * Several tasks share a screen lock: each task gets a copy of [lease], which is acquired when the first
 * one is held and released when the last one is released. Remember whether you took each copy. Repeated
 * acquisition or release will not be counted.
 */
class SharedWakeLock(private val lock: WakeLockPort) {
    private var count = 0

    fun lease(): WakeLockPort = object : WakeLockPort {
        private var held = false

        override fun acquire() {
            synchronized(this@SharedWakeLock) {
                if (held) return
                held = true
                if (count++ == 0) lock.acquire()
            }
        }

        override fun release() {
            synchronized(this@SharedWakeLock) {
                if (!held) return
                held = false
                if (--count == 0) lock.release()
            }
        }
    }
}

/**
 * Use SCREEN_DIM_WAKE_LOCK for screen lock: The screen remains on. When the user does not touch the
 * phone for a period of time, the system will dim it as usual. When the user touches the phone, it will
 * return to the original brightness. The system brightness is not changed or brightened. When dimming,
 * the screen is still open and interactive, and the injected touch is still delivered; the Agent
 * operates another virtual display, and the input there is not affected by the brightness of the main
 * screen. This type of lock has been marked as deprecated (FLAG_KEEP_SCREEN_ON for windows is
 * recommended), but Mina does not have its own window when running tasks in the background, and this
 * type of lock held by the foreground service is still valid.
 */
class ScreenWakeLock(context: Context) : WakeLockPort {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(LEVEL, "Mina:task")
        .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire(MAX_HOLD_MS)
    override fun release() { if (lock.isHeld) lock.release() }

    companion object {
        /** Keep the screen on but allow the system to dim by timeout. */
        @Suppress("DEPRECATION")
        const val LEVEL = PowerManager.SCREEN_DIM_WAKE_LOCK

        /** The upper limit is set to prevent any path from missing the release; it is normally released by the task status. */
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
