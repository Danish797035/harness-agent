package com.meenai.mina.core.resource

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A group of system resources (virtual screen, web page display, etc.) that live and die together: Use
 * [acquire] to build, register on the spot after building, and release [close] in the reverse order of
 * registration. There is a hang in between "After the construction is completed, return it to the caller
 * and then record it in the field". The task is canceled at this time, and no one remembers to put the
 * built things. If the registration is done in the same section as the construction, there will be no
 * such gap. Any thread is available; each resource is only placed once, [close] can be adjusted
 * repeatedly.
 */
class ResourceScope {
    private class Entry(val context: CoroutineContext, val release: () -> Unit)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var closed = false

    /**
     * Adjust [create] in [context], register immediately after creation, and also adjust [release] in
     * [context] at the end. Even if the task is canceled after it is built (cancellation is thrown when
     * it is handed back), the resources have been returned to the scope. The ones created when the scope
     * is closed are immediately discarded and [IllegalStateException] is thrown.
     */
    suspend fun <T> acquire(context: CoroutineContext, release: (T) -> Unit, create: suspend () -> T): T =
        withContext(context) {
            val made = create()
            val owned = synchronized(lock) { if (!closed) entries += Entry(context) { release(made) }; !closed }
            if (!owned) {
                release(made)
                throw IllegalStateException("resource scope already closed")
            }
            made
        }

    /** Release them all in the reverse order of registration; failure to release one will not affect the others, and the first error will be thrown after the release. Uninterrupted by cancellation. */
    suspend fun close() {
        val todo = synchronized(lock) {
            closed = true
            entries.asReversed().toList().also { entries.clear() }
        }
        var failure: Throwable? = null
        for (e in todo) {
            try {
                withContext(e.context + NonCancellable) { e.release() }
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }
        failure?.let { throw it }
    }
}
