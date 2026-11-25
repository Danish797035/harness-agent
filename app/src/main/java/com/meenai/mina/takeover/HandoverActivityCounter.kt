package com.meenai.mina.takeover

import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** Return what the user did on the home screen at that moment: the number of operations and the page they stopped on (null if not available). */
data class HandoverActivity(val inputCount: Int, val stoppedAt: String?)

/**
 * During the takeover period, count the number of times the user clicks, inputs, and scrolls
 * [packageName] on the home screen; one swipe will send many scrolling events in succession, and those
 * with an interval of less than [MERGE_MS] will be counted as one; the same is true for consecutive text
 * changes in the same input box. The longest string of consecutive messages is counted as
 * [BURST_CAP_MS], and the excess part is counted separately to avoid long-term swiping or typing and
 * only remembering it once. By the way, write down the short name of the activity last switched to by
 * the package, which can be used when the page title cannot be obtained. The event comes from the
 * coroutine where the accessibility callback is located, and the reading comes from the returned
 * coroutine, so it is locked.
 */
class HandoverActivityCounter(val packageName: String) {
    private var count = 0
    private var scrollBurst: Burst? = null
    private var typingBurst: Burst? = null
    private var typingSource: Any? = null
    private var activity: String? = null

    val inputCount: Int @Synchronized get() = count
    val lastActivity: String? @Synchronized get() = activity

    @Synchronized
    fun onEvent(displayId: Int, eventPackage: CharSequence?, eventType: Int, className: CharSequence?, timeMs: Long, source: Any? = null) {
        if (displayId != Display.DEFAULT_DISPLAY || eventPackage?.toString() != packageName) return
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> count++
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Changing the input box is a new one
                if (typingSource != source) typingBurst = null
                typingSource = source
                typingBurst = advance(typingBurst, timeMs)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scrollBurst = advance(scrollBurst, timeMs)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }?.let { activity = it }
        }
    }

    private class Burst(val startAt: Long, var lastAt: Long)

    private fun advance(burst: Burst?, timeMs: Long): Burst {
        if (burst != null && timeMs - burst.lastAt < MERGE_MS && timeMs - burst.startAt < BURST_CAP_MS) {
            burst.lastAt = timeMs
            return burst
        }
        count++
        return Burst(timeMs, timeMs)
    }

    companion object {
        const val MERGE_MS = 1_000L
        const val BURST_CAP_MS = 5_000L
    }
}

/**
 * "Where to stop": The window title is often just the App name. In this case, use the page (Activity)
 * abbreviation. Only when both are present and different are they put together; if the title cannot be
 * obtained, use the page abbreviation.
 */
fun stoppedAt(title: String?, appLabel: String?, activity: String?): String? {
    val t = title?.takeIf { it.isNotBlank() }
    if (t == null || t == appLabel) return activity ?: t
    return t
}
