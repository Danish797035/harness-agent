package com.meenai.mina.cap.phone.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The part of the built-in assistant that stares at the home screen: in a short period of time before
 * and after the Agent's action, what tasks were changed at the top of the home screen ([MainScreenLog]).
 * When an App opens another App not from its own page on the Mina screen, but from the background
 * (services, notification jumps, system selectors), the system puts it on the home screen; Mina
 * recognizes it based on this record and moves it back.
 *
 * Watch it once when the task changes: register the task stack listener with the system
 * (ITaskStackListener, the shell has the authority to manage tasks), the listener is only used as a
 * "changed" signal, and does not parse the parameters (the method order of each version is different).
 * After receiving it, read the task list again; if the registration fails, return to watch it every
 * [POLL_MS] during the period of staring. When querying ([arrivals]), look at it again on the spot, so
 * as not to miss the final appearance. Don’t look at the to-do list when you’re not staring.
 */
internal class MainScreenWatch(
    private val taskManager: () -> Any,
    /** All tasks on the screen at the moment are arranged by most recently used. */
    private val tasks: () -> List<MainScreenLog.Task>,
) {
    private val log = MainScreenLog()
    private val thread = HandlerThread("mina-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    /** Watch until when (uptimeMillis). */
    @Volatile private var until = 0L
    /** If the task stack listener is registered, it will be true, if it is not registered, it will be false, and if it has not been tried yet, it will be null. */
    @Volatile private var listening: Boolean? = null
    private val queued = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val signal = StackSignal()

    /** Stare at [windowMs] from now on; return to the top task on the home screen at the moment (-1 if not). */
    fun watch(windowMs: Int): Int {
        val now = SystemClock.uptimeMillis()
        val top = synchronized(log) {
            scanLocked(now, record = now < until)
            until = maxOf(until, now + windowMs.coerceIn(0, MAX_WINDOW_MS))
            log.topTaskId
        }
        if (listening == null) listening = register()
        if (listening == false && polling.compareAndSet(false, true)) handler.post(::poll)
        return top
    }

    /**
     * [since] After that, the main screen pops up to the top tasks, arranged one after another; each
     * item also shows which screen it is on at the moment (it is -1 if it is not there), and whether
     * this record is obtained by monitoring or polling. See [toBundle] for fields.
     */
    fun arrivals(since: Long): Bundle = synchronized(log) {
        val now = SystemClock.uptimeMillis()
        if (now < until + GRACE_MS) scanLocked(now, record = true)
        toBundle(log.since(since), log::displayOf, listening == true)
    }

    fun close() {
        if (listening == true) runCatching {
            taskManager().javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager(), listener())
        }
        listening = null
        thread.quitSafely()
    }

    private fun scanLocked(now: Long, record: Boolean) {
        val list = try { tasks() } catch (t: Throwable) { Log.w(TAG, "cannot list tasks", t); return }
        log.scan(list, now, record)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        synchronized(log) { scanLocked(now, record = true) }
    }

    /** The task stack has changed: combine them and look at them again, and only look at a series of changes once. */
    private fun onSignal() {
        if (SystemClock.uptimeMillis() >= until) return
        if (queued.compareAndSet(false, true)) handler.postDelayed({ queued.set(false); scan() }, COALESCE_MS)
    }

    private fun poll() {
        if (SystemClock.uptimeMillis() >= until) { polling.set(false); return }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, signal)!!

    private fun register(): Boolean = try {
        val am = taskManager()
        am.javaClass.methods.first { it.name == "registerTaskStackListener" && it.parameterTypes.size == 1 }.invoke(am, listener())
        Log.i(TAG, "task stack listener registered")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "task stack listener unavailable, polling instead", t)
        false
    }

    /**
     * The system calls back the Binder through the ITaskStackListener interface. It only recognizes "a
     * callback has come" and does not read parameters; these callbacks are one-way and do not require a
     * reply. The descriptor is filled in according to the name of the interface, and the system packages
     * it according to it.
     */
    private inner class StackSignal : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val TAG = "MinaShell"
        private const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        private const val COALESCE_MS = 30L
        /** Check again after the window has passed, and still check on the spot: Mina will take a while to complete the judgment. */
        private const val GRACE_MS = 2_000L
        private const val MAX_WINDOW_MS = 30_000

        const val KEY_TASK = "task"
        const val KEY_PACKAGE = "package"
        const val KEY_USER = "user"
        const val KEY_AT = "at"
        const val KEY_FROM = "from"
        const val KEY_SAME_TASK = "sameTask"
        const val KEY_STANDARD = "standard"
        const val KEY_ACTIVITY = "activity"
        const val KEY_NOW = "now"
        const val KEY_LISTENING = "listening"

        /** A set of records is organized into a parallel array (there is no object list in the Bundle); [now] is which screen the task is on at the moment. */
        fun toBundle(list: List<MainScreenLog.Arrival>, now: (Int) -> Int, listening: Boolean): Bundle = Bundle().apply {
            putIntArray(KEY_TASK, list.map { it.taskId }.toIntArray())
            putStringArray(KEY_PACKAGE, list.map { it.packageName }.toTypedArray())
            putIntArray(KEY_USER, list.map { it.userId }.toIntArray())
            putLongArray(KEY_AT, list.map { it.at }.toLongArray())
            putIntArray(KEY_FROM, list.map { it.from }.toIntArray())
            putBooleanArray(KEY_SAME_TASK, list.map { it.sameTask }.toBooleanArray())
            putBooleanArray(KEY_STANDARD, list.map { it.standard }.toBooleanArray())
            putStringArray(KEY_ACTIVITY, list.map { it.topActivity }.toTypedArray())
            putIntArray(KEY_NOW, list.map { now(it.taskId) }.toIntArray())
            putBoolean(KEY_LISTENING, listening)
        }
    }
}
