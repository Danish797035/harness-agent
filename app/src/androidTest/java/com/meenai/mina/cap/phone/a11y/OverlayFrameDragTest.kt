package com.meenai.mina.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Dragging on the root of the floating layer window: Drag only when the handle is pressed. When dragging, the button inside is canceled and is not counted; sliding outside the handle is handed over to the content inside. */
@RunWith(AndroidJUnit4::class)
class OverlayFrameDragTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()
    private val drag = object : OverlayDrag {
        override fun start() { calls += "start" }
        override fun move(dx: Float, dy: Float) = Unit
        override fun end(dx: Float, dy: Float) { calls += "end" }
    }

    /** What's inside (like the stow button on the card and the roll back and forth): Note the presses, lifts, and cancels received. */
    private inner class Content : View(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> calls += "down"
                MotionEvent.ACTION_UP -> calls += "up"
                MotionEvent.ACTION_CANCEL -> calls += "cancel"
            }
            return true
        }
    }

    /** The entire 400×800 window is filled with content. */
    private fun frame(handle: Rect?): OverlayFrame = OverlayFrame(context).apply {
        addView(Content(), FrameLayout.LayoutParams(400, 800))
        measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        layout(0, 0, 400, 800)
        drag = this@OverlayFrameDragTest.drag
        this.handle = handle
    }

    private fun OverlayFrame.gesture(x: Float, y: Float, dy: Float) {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, yy: Float) = MotionEvent.obtain(t, t, action, x, yy, 0).also { dispatchTouchEvent(it); it.recycle() }
        ev(MotionEvent.ACTION_DOWN, y)
        ev(MotionEvent.ACTION_MOVE, y + dy / 2)
        ev(MotionEvent.ACTION_MOVE, y + dy)
        ev(MotionEvent.ACTION_UP, y + dy)
    }

    private val far get() = ViewConfiguration.get(context).scaledTouchSlop * 4f

    @Test fun dragInTheHandleMovesAndNeverClicks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 30f, far)
        }
        // Moved past the threshold: start dragging, cancel will be received (will not be regarded as
        // click), lift only to end dragging
        assertEquals(listOf("down", "start", "cancel", "end"), calls)
    }

    @Test fun swipeOutsideTheHandleIsLeftToTheContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 400f, far)
        }
        // There is no dragging; the entire series of touches is given to the content inside (e.g.
        // scrolling)
        assertEquals(listOf("down", "up"), calls)
    }

    @Test fun tapInTheHandleStillClicks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect(0, 0, 400, 60)).gesture(200f, 30f, 0f)
        }
        assertEquals(listOf("down", "up"), calls)
    }

    @Test fun emptyHandleMeansNoDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            frame(Rect()).gesture(200f, 30f, far)
        }
        assertEquals(listOf("down", "up"), calls)
    }
}
