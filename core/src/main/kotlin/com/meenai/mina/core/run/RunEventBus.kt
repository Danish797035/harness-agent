package com.meenai.mina.core.run

import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** A Run event dispatcher. You can order one copy each of records, interfaces and delivery, and the failure of a single recipient will not affect other recipients. */
class RunEventBus : RunSink {
    private val listeners = CopyOnWriteArrayList<RunSink>()

    fun subscribe(listener: RunSink): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun event(e: RunEvent) {
        listeners.forEach { listener ->
            try { listener.event(e) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* Operation cannot be interrupted by subscriber failure */ }
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        listeners.forEach { listener ->
            try { listener.media(mediaId, png) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* Same as above */ }
        }
    }
}
