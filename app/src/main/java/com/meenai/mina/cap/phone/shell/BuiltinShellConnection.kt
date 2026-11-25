package com.meenai.mina.cap.phone.shell

import com.meenai.mina.core.shell.HelperLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.meenai.mina.shell.IHarnessShell

/**
 * Connectivity for built-in assistants. The assistant process is resident and does not end with the
 * unbinding: when unbinding, let it undo this change ([IHarnessShell.releaseAll]), the effect is the
 * same as the end of the service; when rebinding, directly use the Binder in hand. If there is no
 * assistant, ask the watchdog to pick one up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellConnection(hub: HelperHub, need: () -> Unit) : ShellBinding {
    // Single thread: revocation and next release are executed sequentially; revocation is a synchronous
    // binder call, leaving the main thread alone
    private val link = HelperLink(
        hub.shell,
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        release = { it.releaseAll() },
        need = need,
    )

    override val shell: StateFlow<IHarnessShell?> get() = link.shell
    override val dropped: StateFlow<Boolean> get() = link.dropped
    override fun bind() = link.bind()
    override fun unbind() = link.unbind()
    override fun reconnect() = link.reconnect()
}
