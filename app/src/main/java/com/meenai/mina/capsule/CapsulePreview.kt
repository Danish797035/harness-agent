package com.meenai.mina.capsule

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** There are at most a few small pictures on the card per second. */
const val PREVIEW_FPS = 5

/**
 * The frames to be pushed for the small picture of the card: at most one frame for each [intervalMs],
 * and only the latest ones in the middle will be kept; those that are the same as the previous one
 * ([same]) will not be pushed. Only close the card when it is expanded, cancel it when it is closed, and
 * do not push it if it is not collected.
 */
fun <T : Any> Flow<T>.previewFrames(intervalMs: Long = 1_000L / PREVIEW_FPS, same: (T, T) -> Boolean = { a, b -> a === b }): Flow<T> = flow {
    var last: T? = null
    conflate().collect { f ->
        val prev = last
        if (prev != null && (prev === f || same(prev, f))) return@collect
        last = f
        emit(f)
        delay(intervalMs)
    }
}

/**
 * If the new frame is the same as the previous one (the same picture, or the same size, and the same
 * pixels), it will not be pushed: a new copy of the webpage picture will be given every time. The old
 * frames on the virtual screen will be recycled, so look at [Bitmap.isRecycled] first, and don’t compare
 * the pixels with the recycled images.
 */
fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
    a === b || !a.isRecycled && !b.isRecycled && a.width == b.width && a.height == b.height && a.sameAs(b)
