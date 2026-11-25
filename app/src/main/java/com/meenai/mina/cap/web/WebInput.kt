package com.meenai.mina.cap.web

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Real input of web page tags: touch via dispatchTouchEvent, text via input connection commitText, and
 * key press via dispatchKeyEvent. The page receives all trusted events (isTrusted), just like human
 * operations. Do not use script clicks or script assignments instead. The input connection is directly
 * from WebView without going through the system input method: the keyboard will not play on the home
 * screen. Coordinates are WebView view pixels.
 */
class WebInput(private val tab: WebTab) {
    private val view get() = tab.view

    suspend fun tap(xPx: Int, yPx: Int) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, xPx.toFloat(), yPx.toFloat())
        delay(TAP_HOLD_MS)
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xPx.toFloat(), yPx.toFloat())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300) {
        val steps = max(2, (durationMs / MOVE_INTERVAL_MS).toInt())
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        for (i in 1..steps) {
            delay(durationMs / steps)
            val f = i.toFloat() / steps
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
    }

    /** Pass the entire text to the currently focused web page input box; return false if no input link is available (no editable focus). */
    suspend fun commitText(text: String): Boolean = withConnection { it.commitText(text, 1) }

    suspend fun key(keyCode: Int) = key(keyCode, 0)

    /** Select all (Ctrl+A) and delete; some editors do not recognize the key combination, then use the input connection to select all and clear. */
    suspend fun selectAllAndDelete() {
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key(KeyEvent.KEYCODE_DEL, 0)
    }

    /** Select all in the input connection and then write an empty string: the fallback route when the key combination is not cleared. */
    suspend fun selectAllAndCommitEmpty(): Boolean = withConnection {
        it.performContextMenuAction(android.R.id.selectAll)
        it.commitText("", 1)
    }

    /** [meta] is the modified state of KeyEvent (META_CTRL_ON type), used for key combinations. */
    suspend fun key(keyCode: Int, meta: Int) {
        val down = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            view.dispatchKeyEvent(keyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, meta))
            view.dispatchKeyEvent(keyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, meta))
        }
    }

    private fun keyEvent(down: Long, at: Long, action: Int, code: Int, meta: Int) =
        KeyEvent(down, at, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    // Indicate it is a finger: when the tool type is not set, the pointerType seen on the page is an
    // empty string, and the real finger is touch.
    private suspend fun touch(down: Long, at: Long, action: Int, x: Float, y: Float) = withContext(Dispatchers.Main) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val e = MotionEvent.obtain(down, at, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            view.dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    // The input connection of the browser kernel has its own thread: if there is a handler, it will be
    // adjusted in that thread, otherwise it will be adjusted in the main thread.
    private suspend fun withConnection(block: (InputConnection) -> Boolean): Boolean {
        val ic = withContext(Dispatchers.Main) {
            if (!view.hasFocus()) view.requestFocus()
            view.onCreateInputConnection(EditorInfo())
        } ?: return false
        val handler = ic.handler
        if (handler == null || handler.looper == Looper.getMainLooper()) return withContext(Dispatchers.Main) { block(ic) }
        return suspendCancellableCoroutine { c -> handler.post { c.resume(runCatching { block(ic) }.getOrDefault(false)) } }
    }

    private companion object {
        const val TAP_HOLD_MS = 50L
        const val MOVE_INTERVAL_MS = 16L
    }
}
