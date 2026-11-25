package com.meenai.mina.cap.phone.shell

import android.os.Handler
import android.os.Looper

/**
 * A shell connection is shared within the process and is bound/unbound based on reference counting; one
 * copy is created by [com.meenai.mina.cap.phone.PhoneServices]. Unbinding will remove changes such as
 * the virtual screen on the shell side, so tasks and startup recovery cannot be bound to one and then
 * unbundled. [create] is used to establish a connection for the first time.
 */
class SharedShell(private val create: () -> ShellBinding) {
    private var connection: ShellBinding? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun acquire(): ShellBinding {
        val c = connection ?: create().also { connection = it }
        if (users++ == 0) onMain { c.bind() }
        return c
    }

    @Synchronized
    fun release() {
        check(users > 0) { "release without acquire" }
        if (--users == 0) {
            val c = connection
            onMain { synchronized(this) { if (users == 0) c?.unbind() } }
        }
    }

    /** "Reconnect": If it is bound but there is no assistant, please start the watchdog again immediately; if no one is bound, do nothing. */
    @Synchronized
    fun reconnect() {
        connection?.takeIf { users > 0 }?.reconnect()
    }

    // Binding/unbinding is unified into the main thread to avoid interleaving with connection callbacks.
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
