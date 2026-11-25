package com.meenai.mina.cap.phone.display

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The virtual screen is built only when it is used: it is built by [get] when the task opens the app for
 * the first time, or uses the system command or event tool for the first time, and it has been the same
 * since then; pure dialogue tasks are not built from beginning to end. [width] and [height] are the size
 * of the screen to be built. Use them to report the size when reading the screen before it is built.
 * Generics are only used to allow unit testing to be replaced with fake objects. In the product, it is
 * [LazyAgentDisplay].
 */
class LazyDisplay<T : Any>(val width: Int, val height: Int, private val create: suspend () -> T) {
    private val lock = Mutex()
    @Volatile private var made: T? = null

    /** The screen has been built; it is null if it has not been built yet. Won't build it. */
    val current: T? get() = made

    /** Take the screen and build it when it is called for the first time; if it fails to build, it will be thrown. It will try again next time it is called. */
    suspend fun get(): T = made ?: lock.withLock { made ?: create().also { made = it } }
}

typealias LazyAgentDisplay = LazyDisplay<AgentDisplay>
