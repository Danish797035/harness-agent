package com.meenai.mina.cap.phone.shell

import kotlinx.coroutines.flow.StateFlow
import com.meenai.mina.shell.IHarnessShell

/** A connection to the shell identity service; [SharedShell] binds/unbinds by reference count, the caller only sees these. */
interface ShellBinding {
    /** It is the interface of the service when connected during binding, otherwise it is null. */
    val shell: StateFlow<IHarnessShell?>

    /** It was connected and still needed, but now it's disconnected. Reconnect or unbind and reset. */
    val dropped: StateFlow<Boolean>

    fun bind()

    /** After unbundling, no changes to the virtual screen, mute, or clipboard will be left. */
    fun unbind()

    /** "Reconnect": If you fail to connect automatically after disconnection, try again manually. */
    fun reconnect()
}
