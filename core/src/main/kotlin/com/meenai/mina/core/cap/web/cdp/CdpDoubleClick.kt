package com.meenai.mina.core.cap.web.cdp

import com.meenai.mina.core.uistate.DOUBLE_TAP_GAP_MS
import kotlinx.coroutines.delay

/**
 * Double-click: Press and lift twice on the same point, the second clickCount is 2, and the page
 * receives click, click, and dblclick (the same as a real double-click). The interval between two times
 * is [gapMs], within the time limit for the browser to recognize a double-click.
 */
suspend fun InputTrack.mouseDoubleClick(x: Double, y: Double, gapMs: Long = DOUBLE_TAP_GAP_MS) {
    mouse("mouseMoved", x, y, "none", 0, 0)
    mouse("mousePressed", x, y, "left", 1, 1)
    mouse("mouseReleased", x, y, "left", 0, 1)
    delay(gapMs)
    mouse("mousePressed", x, y, "left", 1, 2)
    mouse("mouseReleased", x, y, "left", 0, 2)
}
