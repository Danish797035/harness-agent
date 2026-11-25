package com.meenai.mina.core.workspace

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * `files/sandbox/` The owner of this tree: Anyone who wants to write in the tree must get permission
 * here first. A copy in the process is given by the App and passed to the place to be written via the
 * construction parameters.
 *
 * - - [exclusive]: Change the tree structure, or delete the entire piece (install/unpack/change the root
 * file system, reset, clear the workspace). No one else was writing when I got it.
 * - - [shared]: Adding things to existing trees (skill synchronization, running commands, downloading
 * web pages, importing attachments). There can be many at the same time,
 * But it is mutually exclusive with [exclusive]: when the reset is halfway through, no one will create a
 * directory in it (in this way, the last rmdir will be ENOTEMPTY).
 *
 * When someone is waiting for [exclusive], the new [shared] is also queued behind it, and the reset will
 * not be starved to death by a steady stream of commands. If the same coroutine chain is already holding
 * (either type), it will be released directly. Nested calls will not lock themselves.
 */
class SandboxTree {
    /** You need to get it to start: [shared] is only held briefly, [exclusive] is held full time, so new readers are waiting while writers queue up. */
    private val entry = Mutex()
    /** There is [shared] locked while in use. */
    private val noShared = Mutex()
    private val count = Mutex()
    private var holders = 0

    /** Which trees does this coroutine chain hold permissions for at this moment? */
    private class Held(val trees: Set<SandboxTree>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun heldTrees() = currentCoroutineContext()[Held]?.trees.orEmpty()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        return entry.withLock { noShared.withLock { withContext(Held(held + this)) { block() } } }
    }

    suspend fun <T> shared(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        entry.withLock { count.withLock { if (holders++ == 0) noShared.lock() } }
        try {
            return withContext(Held(held + this)) { block() }
        } finally {
            withContext(NonCancellable) { count.withLock { if (--holders == 0) noShared.unlock() } }
        }
    }
}
