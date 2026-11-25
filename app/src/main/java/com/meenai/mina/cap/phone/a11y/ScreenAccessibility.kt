package com.meenai.mina.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.app.Notification
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.meenai.mina.core.uistate.ScreenEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Mina's accessibility service: operations and events are transferred to the currently connected instance, and tasks holding old instances can also be restored after reconnection. */
open class ScreenAccessibility : AccessibilityService() {

    private class DisplayEvent(val displayId: Int, val event: AccessibilityEvent)


    override fun onServiceConnected() {
        super.onServiceConnected()
        // Many apps mark the text controls that display results as "not important for accessibility". If
        // you don't read them, you won't be able to see the results. This has been declared in the
        // configuration file and will be filled in again at runtime to prevent the system from using the
        // old service configuration.
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        _instance.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
        overlayHost?.closeAll()
        overlayHost = null
    }

    private var overlayHost: OverlayHost? = null

    /** A host used to draw floating layers on the user screen (only used in the main thread); when the service is disconnected, the windows it adds will be removed together. */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (instance.value !== this) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowChanges.tryEmit(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowGeneration.incrementAndGet()
        }
        val access = (application as com.meenai.mina.HarnessApp).appAccess.current()
        if (access.denial(event.packageName?.toString()) != null) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && notifications.subscriptionCount.value > 0) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = listOfNotNull(title, body).joinToString(" ").ifBlank { event.text.joinToString(" ") }
            notifications.tryEmit(ScreenEvent("notification", event.packageName?.toString(), text.take(2_000)))
        }
        // If no one subscribes, we won’t copy or check the screen number. The event is very
        // confidential.
        if (events.subscriptionCount.value == 0) return
        val displayId = displayOf(event)
        if (displayId == Display.INVALID_DISPLAY) return
        // API 33 The following system will recycle the event object after the callback returns, and the
        // subscriber will make a copy before asynchronously reading it.
        events.tryEmit(DisplayEvent(displayId, AccessibilityEvent(event)))
    }

    override fun onInterrupt() = Unit

    /** Specifies the windows on the screen; the list is empty if there are no windows on the screen (or does not exist). */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        instance.value?.windowsOnAllDisplays?.get(displayId).orEmpty()

    /** Make a single-finger gesture along [path] on the specified screen; return true if completed, false if canceled or unable to be sent. */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        return gesture(displayId, listOf(path), durationMs)
    }

    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        dispatch(displayId, paths.map { GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1)) })

    /** Connect [count] on the same point (several strokes in one gesture): press [holdMs] each time, and space [gapMs] between two clicks. */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean {
        val at = Path().apply { moveTo(x, y) }
        return dispatch(displayId, (0 until count).map { i -> GestureDescription.StrokeDescription(at, i * (holdMs + gapMs), holdMs.coerceAtLeast(1)) })
    }

    private suspend fun dispatch(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val service = instance.value ?: return false
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .apply { strokes.forEach(::addStroke) }
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
    }

    /** The result of taking a screenshot of the user's screen (home screen): there is an anti-screenshot window on the screen, or the screenshot cannot be taken (error code). */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * Take a screenshot of the home screen (the screen that the user is looking at, including Mina's own
     * floating layer: it is up to the caller to decide whether to hide it or not). The system limits two
     * screenshots to be taken at least a while apart. If it is too fast, wait one second and take
     * another screenshot. The picture is a software bitmap (can read pixels and codes).
     */
    suspend fun takeMainScreenshot(): MainShot {
        val first = screenshotOnce()
        if (first !is MainShot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        kotlinx.coroutines.delay(SCREENSHOT_INTERVAL_MS)
        return screenshotOnce()
    }

    private suspend fun screenshotOnce(): MainShot = suspendCancellableCoroutine { cont ->
        val service = instance.value
        if (service == null) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(MainShot::Ok) ?: MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                // There is an anti-screenshot window on the screen: Android 14 and above has a special
                // error code; earlier systems will show black screenshots, and the caller can judge by
                // looking at the screen.
                val secure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                if (cont.isActive) cont.resume(if (secure) MainShot.Secure else MainShot.Failed(errorCode))
            }
        })
    }

    /**
     * Accessibility events that occur on the specified screen. Channels are retained across instances,
     * and only the currently connected service publishes events.
     */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent> =
        events.filter { it.displayId == displayId }.map { it.event }

    fun notificationEvents(): Flow<ScreenEvent> = notifications

    /** Signal that the window has changed (see [windowChanges]); the channel is retained across service instances. */
    fun windowChanges(): Flow<Unit> = windowChanges

    /**
     * The screen where the event occurs. Events from API 33 onwards have their own screen numbers;
     * earlier systems search for each screen window according to the window number of the event, and if
     * not found (such as a notification event without a window number), it is discarded.
     */
    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1) return Display.INVALID_DISPLAY
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        }
        return Display.INVALID_DISPLAY
    }

    companion object {
        private const val EVENT_BUFFER = 64
        /** The system required interval between two screenshots (the original Android version is one second). */
        private const val SCREENSHOT_INTERVAL_MS = 1_000L

        // Maintain hot flow: The caller uses UNDISPATCHED to subscribe first and then operate, and do
        // not introduce an asynchronous subscription window due to service switching. Events are only
        // for observation. If the subscriber cannot process them, the oldest ones will be discarded
        // without blocking system callbacks.
        private val events = MutableSharedFlow<DisplayEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        /** The window has changed (added, removed, moved, including input method pop-up and collapse): it is just a signal, the subscriber can read the window by himself. */
        private val windowChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val windowGeneration = java.util.concurrent.atomic.AtomicLong()

        /** Add one every time a window is added, removed, or paged (anything on the screen counts); it is only used to determine whether the window has changed since the last time. The value itself has no meaning. */
        fun windowGeneration(): Long = windowGeneration.get()
        private val notifications = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** The connected service instance; null if the service is not started or disconnected. */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
