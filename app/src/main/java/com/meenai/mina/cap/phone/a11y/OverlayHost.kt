package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.i18n.AppLanguage
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.view.KeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where to place the floating layer window: [gravity] is a combination of [android.view.Gravity],
 * [x]/[y] is the pixel away from that edge. The coordinates are calculated based on the entire screen
 * (including the status bar, notch and bottom gesture bar), and the place to be avoided is determined by
 * the caller by pressing [OverlayArea].
 */
data class OverlayPlacement(val gravity: Int, val x: Int = 0, val y: Int = 0)

/** The width and height of the screen at this moment (according to the current orientation), and the pixels occupied by the status bar, notch, navigation bar or gesture bar on all four sides. */
data class OverlayArea(
    val width: Int, val height: Int,
    val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0,
)

/**
 * Dragging the window: After pressing and moving your finger across the system's touch threshold, it is
 * considered dragging. At this time, the button being pressed in the window is canceled and will no
 * longer be regarded as a click. You can make only one part of the window (handle, see
 * [OverlayWindow.draggable]) draggable, and slide elsewhere as usual to the content inside (such as
 * scrolling). [move]/[end] gives how many pixels the finger has moved on the screen since it was pressed
 * (calculated according to screen coordinates, the window will not shake as the finger moves).
 */
interface OverlayDrag {
    fun start()
    fun move(dx: Float, dy: Float)
    fun end(dx: Float, dy: Float)
}

/**
 * Long press: When the finger does not move after pressing (has not moved past the touch threshold), it
 * is considered a long press when the holdMs is full. The first move past the threshold is dragging, and
 * then it is no longer considered a long press (see [HoldGesture]). After the long press starts, the
 * window does not follow the dragging, and the position of the finger on the screen (screen coordinates)
 * is handed over here; the button being pressed in the window is canceled at this time, and the click is
 * no longer established.
 */
interface OverlayHold {
    fun start(rawX: Float, rawY: Float)
    fun move(rawX: Float, rawY: Float)
    fun end(rawX: Float, rawY: Float)
    /** The system took away this series of touches (for example, the window was removed, the call came in): this long press does not count. */
    fun cancel()
}

/**
 * A series of touches counts as a click, drag or long press (pure logic, the clock is controlled by the
 * calling party): [down], then [Kind.Tap] (possibly also a tap); when the finger is more than [slopPx]
 * from the pressed point, it is [Kind.Drag]; when it reaches the tap point ([timeout]), it is
 * [Kind.Hold]. It will not change after dragging or long pressing, and [down] will come back next time.
 */
class HoldGesture(private val slopPx: Float) {
    enum class Kind { Tap, Drag, Hold }

    var kind = Kind.Tap
        private set

    fun down(): Kind {
        kind = Kind.Tap
        return kind
    }

    /** The finger is now [dist] pixels away from the pressed location. */
    fun move(dist: Float): Kind {
        if (kind == Kind.Tap && dist > slopPx) kind = Kind.Drag
        return kind
    }

    fun timeout(): Kind {
        if (kind == Kind.Tap) kind = Kind.Hold
        return kind
    }
}

/**
 * Use accessibility services to draw a floating layer on the user's screen (home screen): the window
 * type is an accessible floating layer and does not require the "Show on top of other applications"
 * permission. Regardless of the mechanism: adding windows, changing positions, dragging, switching
 * whether to accept the keyboard, and tearing down windows; what is drawn is determined by the Compose
 * content given by the caller. The window is only as big as the content, and the areas other than the
 * content are left to the following App as usual. When the service is disconnected,
 * [ScreenAccessibility] is adjusted to [closeAll], and the window is removed accordingly. Only used on
 * the main thread.
 */
class OverlayHost internal constructor(private val service: ScreenAccessibility) {
    private val windows = mutableListOf<OverlayWindow>()

    /**
     * Add a floating window that wraps the size of the content. If you don't usually grab the focus and
     * can't click, hand it over to the app below as usual; when [onOutside] is given, pressing it
     * outside the window will notify it (for hiding the card). When [touchable] is false, the window
     * only draws and does not receive touch (pressing on the top is equivalent to pressing on the App
     * below). If it cannot be added (the service has been disconnected and the system does not allow
     * it), null will be returned and no error will be thrown.
     */
    fun open(
        placement: OverlayPlacement,
        onOutside: (() -> Unit)? = null,
        touchable: Boolean = true,
        content: @Composable () -> Unit,
    ): OverlayWindow? {
        val owners = OverlayOwners()
        val frame = OverlayFrame(service).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            addView(
                ComposeView(AppLanguage.wrap(service)).apply { setContent(content) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            this.onOutside = onOutside
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Press the entire screen to prevent the system from squeezing the window into the status
            // bar and navigation bar: the place to be avoided is determined by the caller by pressing
            // area()
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            place(placement)
        }
        val window = OverlayWindow(this, frame, params, owners)
        owners.start()
        return try {
            wm().addView(frame, params)
            windows += window
            window
        } catch (e: RuntimeException) {
            // The window token has expired when the service was just disconnected, or the system does
            // not allow this type of window: it is treated as if there is no floating layer
            Log.w(TAG, "overlay unavailable", e)
            owners.destroy()
            null
        }
    }

    /** At this moment, the size of the screen and the part of the four sides occupied by the system bar and bangs (will change after the screen is turned). Return to the display size when it cannot be read, leaving a common height for the status bar at the top and bottom. */
    fun area(): OverlayArea = runCatching {
        val m = wm().currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        OverlayArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
    }.getOrElse {
        val d = service.resources.displayMetrics
        val bar = (FALLBACK_INSET_DP * d.density).toInt()
        OverlayArea(d.widthPixels, d.heightPixels, insetTop = bar, insetBottom = bar)
    }

    /**
     * The top edge of the input method on the user's screen (screen coordinates); null when no pop-up
     * occurs. No matter which app the input method is played against (including Mina's own floating
     * layer), the capsules and cards will get out of the way accordingly. It will be re-read as soon as
     * the window changes. If the window cannot be read, it will be regarded as not popping up.
     */
    fun imeTop(): Flow<Int?> = service.windowChanges().onStart { emit(Unit) }
        .map { readImeTop() }
        .distinctUntilChanged()

    private fun readImeTop(): Int? = runCatching {
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also(w::getBoundsInScreen) }
            ?.takeIf { !it.isEmpty }?.top
    }.getOrNull()

    /** Remove all floating layers (when service is disconnected). */
    fun closeAll() {
        windows.toList().forEach(OverlayWindow::close)
    }

    internal fun wm(): WindowManager = service.getSystemService(WindowManager::class.java)

    internal fun forget(window: OverlayWindow) {
        windows.remove(window)
    }

    private companion object {
        const val TAG = "MinaOverlay"
        const val FALLBACK_INSET_DP = 24
    }
}

/** A window added by [OverlayHost.open]. After tearing it down and calling it again, it will do nothing. */
class OverlayWindow internal constructor(
    private val host: OverlayHost,
    private val frame: OverlayFrame,
    private val params: WindowManager.LayoutParams,
    private val owners: OverlayOwners,
) {
    var isOpen = true
        private set

    /** The width and height (pixels) of the window at the moment; it is 0 before typesetting. */
    val width: Int get() = frame.width
    val height: Int get() = frame.height

    /** Change position. */
    fun place(placement: OverlayPlacement) {
        if (!isOpen || params.gravity == placement.gravity && params.x == placement.x && params.y == placement.y) return
        params.place(placement)
        update()
    }

    /**
     * If it can be dragged, it will be [drag], if it cannot be dragged, it will be null. [handle] can
     * only be dragged when pressed in this area (pixel coordinates in the window, such as the title bar
     * of the card). When it is null, it can be dragged wherever it is pressed; when it is empty, it
     * cannot be dragged anywhere.
     */
    fun draggable(drag: OverlayDrag?, handle: Rect? = null) {
        if (!isOpen) return
        frame.drag = drag
        frame.handle = handle?.let(::Rect)
    }

    /**
     * [hold] can be given when long-pressed, but null can not be given: [holdMs] milliseconds are
     * long-pressed if pressed and not moved (mutually exclusive with [draggable] dragging, the first
     * move is dragging). Long press and start with a slight vibration, and the button in the window will
     * be cancelled.
     */
    fun holdable(hold: OverlayHold?, holdMs: Long = 400) {
        if (!isOpen) return
        frame.setHold(hold, holdMs)
    }

    /**
     * Fill the entire screen (such as darkening the layer behind when you press and hold to speak), or
     * return to a size that wraps the content. When the window that is usually empty wraps the content,
     * it has no size and does not occupy a full-screen canvas; it is filled when it is full (the window
     * system that wraps the content will first fill the width of the dialog box, and the content will
     * not fill the entire screen).
     */
    fun setFullScreen(full: Boolean) {
        if (!isOpen) return
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        update()
    }

    /** When the keyboard can be received (see [setFocusable]), the user presses the return key; when the input method is open, the return key first closes the input method, not here. */
    fun onBack(onBack: (() -> Unit)?) {
        if (isOpen) frame.onBack = onBack
    }

    /** When you click inside the window (no matter where you click), adjust [onTouch]: the caller will restore the faded capsule accordingly. */
    fun onTouch(onTouch: (() -> Unit)?) {
        if (isOpen) frame.onTouch = onTouch
    }

    /** When the content size changes (capsule is replaced by a card, rearranged after screen rotation), [onResize] is adjusted, and the caller repositions it accordingly. */
    fun onResize(onResize: (() -> Unit)?) {
        if (isOpen) frame.onResize = onResize
    }

    /**
     * Whether it can receive the keyboard: it must be opened when typing in the floating layer (the
     * input method will pop up facing it), closed immediately when closed, and the focus returned to the
     * app below.
     */
    fun setFocusable(focusable: Boolean) {
        if (!isOpen) return
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == params.flags) return
        params.flags = flags
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        update()
    }

    /**
     * Hide the thing in the window first, wait until it really goes off the screen, then do [block]
     * (such as taking a screenshot of the user's screen), and then display it after it is done. "It's
     * really gone": After the hidden frame is drawn and handed over to the system (frame submission
     * callback), wait for [GONE_FRAMES] refreshes before the synthesizer replaces this frame; if the
     * frame submission callback does not come (the window is not drawn at this time), it will wait at
     * most [GONE_WAIT_MS]. The window itself remains (the position and status remain unchanged), but it
     * is transparent during this period.
     */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val content = frame.getChildAt(0)
        if (!isOpen || content == null) return block()
        content.visibility = View.INVISIBLE
        try {
            withTimeoutOrNull(GONE_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    frame.viewTreeObserver.registerFrameCommitCallback { if (cont.isActive) cont.resume(Unit) }
                    frame.invalidate()
                }
            }
            repeat(GONE_FRAMES) {
                suspendCancellableCoroutine { cont -> Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) } }
            }
            return block()
        } finally {
            if (isOpen) content.visibility = View.VISIBLE
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        frame.release()
        host.forget(this)
        runCatching { host.wm().removeViewImmediate(frame) }
        owners.destroy()
    }

    private fun update() {
        runCatching { host.wm().updateViewLayout(frame, params) }.onFailure { close() }
    }

    private companion object {
        /** The hidden frame is handed over to the system and waits for a few refreshes: the compositor replaces it at the next refresh. */
        const val GONE_FRAMES = 2
        const val GONE_WAIT_MS = 300L
    }
}

/**
 * The root of the floating window contains the Compose content. Press outside the window ([onOutside]);
 * when dragging ([drag]), press your finger inside the handle ([handle], if not the entire window), and
 * move across the touch threshold, the series of touches will be intercepted, and the buttons inside
 * will be canceled. Dragging is calculated according to screen coordinates. If you can press and hold
 * ([hold]), the time will start when you press it. If the time limit is reached without moving, cut off
 * the series of touches and give them to [hold].
 */
internal class OverlayFrame(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null
    var onResize: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onTouch: (() -> Unit)? = null
    var drag: OverlayDrag? = null
    var handle: Rect? = null
    private var hold: OverlayHold? = null
    private var holdMs = 400L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val gesture = HoldGesture(slop.toFloat())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false
    private var holding = false
    /** Cancel the content sent by yourself when long pressing: when it passes [track], it is not regarded as the system's cancellation. */
    private var ownCancel = false
    private val holdTimer = Runnable(::holdReached)
    /** Long press the timer to run the main thread's Handler, but it will not run. View.postDelayed: The latter has to wait until the window is hung up before running. */
    private val timer = Handler(Looper.getMainLooper())

    init {
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onResize?.invoke()
        }
    }

    /**
     * Change the recipient of long press ([hold] is null, long press is not possible). When the same
     * receiver is installed again (it will be installed every time it is repainted), nothing will
     * change; when it is replaced or removed, the ongoing long press will first notify the old one of
     * cancellation, and the lift after this series of touches will no longer be handed over to anyone -
     * otherwise the old one will never wait until the end.
     */
    fun setHold(hold: OverlayHold?, holdMs: Long) {
        this.holdMs = holdMs
        if (hold === this.hold) return
        val old = this.hold
        this.hold = hold
        timer.removeCallbacks(holdTimer)
        if (holding) {
            holding = false
            tracking = false
            old?.cancel()
        }
    }

    fun release() {
        timer.removeCallbacks(holdTimer)
        hold = null
        holding = false
        drag = null
        handle = null
        onResize = null
        onOutside = null
        onBack = null
        onTouch = null
        tracking = false
        dragging = false
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val outside = onOutside ?: return false
            outside()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke()
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val back = onBack
        if (back != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e) && (dragging || holding)

    /** You can drag even if there is nothing in the content to connect to. */
    @SuppressLint("ClickableViewAccessibility") // Dragging just moves the position; when reading the screen, use "Move to the other side" on the capsule
    override fun onTouchEvent(e: MotionEvent): Boolean = track(e)

    /** Follow this string of touches; return true to indicate that the touch will continue (when dragging or long-pressing is possible). */
    private fun track(e: MotionEvent): Boolean {
        if (ownCancel) return true
        val d = drag
        val h = hold
        if (d == null && h == null) {
            timer.removeCallbacks(holdTimer)
            tracking = false
            dragging = false
            holding = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastX = e.rawX
                lastY = e.rawY
                tracking = handle?.contains(e.x.toInt(), e.y.toInt()) ?: true
                dragging = false
                holding = false
                gesture.down()
                timer.removeCallbacks(holdTimer)
                if (tracking && h != null) timer.postDelayed(holdTimer, holdMs)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                lastX = e.rawX
                lastY = e.rawY
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (holding) {
                    hold?.move(e.rawX, e.rawY)
                } else if (gesture.move(hypot(dx, dy)) == HoldGesture.Kind.Drag) {
                    timer.removeCallbacks(holdTimer)
                    if (d != null) {
                        if (!dragging) {
                            dragging = true
                            d.start()
                        }
                        d.move(dx, dy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(holdTimer)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (holding) {
                    if (up) hold?.end(e.rawX, e.rawY) else hold?.cancel()
                } else if (dragging) d?.end(e.rawX - downX, e.rawY - downY)
                tracking = false
                dragging = false
                holding = false
            }
        }
        return tracking
    }

    /** If the time limit is reached after pressing and not moving: it becomes a long press. The button being pressed inside is immediately canceled (without waiting for the next touch event), and neither tap nor double-click is valid anymore. */
    private fun holdReached() {
        val h = hold ?: return
        if (!tracking || dragging || gesture.timeout() != HoldGesture.Kind.Hold) return
        holding = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        ownCancel = true
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            ownCancel = false
            cancel.recycle()
        }
        h.start(lastX, lastY)
    }
}

private fun WindowManager.LayoutParams.place(p: OverlayPlacement) {
    gravity = p.gravity
    x = p.x
    y = p.y
}

/** Compose needs to configure its own life cycle, state preservation, and ViewModel host outside of Activity; it enters RESUMED when a window is added, and is destroyed when it is removed. */
internal class OverlayOwners : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        // Those that have never been started (failed before adding a window) must first enter CREATED
        // before they can be destroyed.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
