package com.meenai.mina.core.record

/** Receiver of running records. The implementation swallows disk write errors by itself: recording failure cannot affect the task. */
interface RunSink {
    fun event(e: RunEvent)

    /** [png] is the saved picture [mediaId]. */
    fun media(mediaId: Int, png: ByteArray) {}

    companion object {
        val NONE: RunSink = object : RunSink {
            override fun event(e: RunEvent) {}
        }
    }
}
