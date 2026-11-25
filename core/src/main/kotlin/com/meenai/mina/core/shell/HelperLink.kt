package com.meenai.mina.core.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A "connection" to the resident assistant: the assistant process does not end with unbinding, and the
 * Binder is provided by [source] (null if not available). External connections are the same as those
 * bound by services: [shell] only has a value during the binding period, [dropped] means it was
 * connected, is still needed, but is now disconnected. When unbinding, use [release] to ask the
 * assistant to remove the changes left by this binding (virtual screen, mute, clipboard), the effect is
 * equivalent to the end of the service.
 *
 * [scope] must be executed sequentially in a single thread: the unbinding undo is ranked before the
 * rebinding release, and the screen created by the new task will not be accidentally deleted by the last
 * undo.
 */
class HelperLink<T : Any>(
    private val source: StateFlow<T?>,
    private val scope: CoroutineScope,
    private val release: (T) -> Unit,
    private val need: () -> Unit,
) {
    private val _shell = MutableStateFlow<T?>(null)
    val shell: StateFlow<T?> = _shell.asStateFlow()

    private val _dropped = MutableStateFlow(false)
    val dropped: StateFlow<Boolean> = _dropped.asStateFlow()

    private var wanted = false
    private var job: Job? = null

    @Synchronized
    fun bind() {
        if (wanted) return
        wanted = true
        var seen = false
        job = scope.launch {
            source.collect { t ->
                synchronized(this@HelperLink) {
                    if (!wanted) return@collect
                    _shell.value = t
                    if (t != null) {
                        seen = true
                        _dropped.value = false
                    } else {
                        _dropped.value = seen
                    }
                }
                if (t == null) need()
            }
        }
    }

    @Synchronized
    fun unbind() {
        if (!wanted) return
        wanted = false
        job?.cancel()
        job = null
        val last = _shell.value
        _shell.value = null
        _dropped.value = false
        if (last != null) scope.launch { runCatching { release(last) } }
    }

    /** "Reconnect": When bound but without an assistant, ask the watchdog to start again immediately. */
    fun reconnect() {
        val ask = synchronized(this) { wanted && _shell.value == null }
        if (ask) need()
    }
}
