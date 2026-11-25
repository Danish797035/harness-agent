package com.meenai.mina.cap.web

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.meenai.mina.cap.web.cdp.DesktopProfile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

/** Where to hang the web page tag: Presentation on a private display, a window added directly to the display, or no window (the page may not be fully rendered). */
enum class HostMode { Presentation, WindowManager, Detached }

/**
 * The backend host of web page tags: App's self-built private virtual display (without PUBLIC flag,
 * without shell identity assistant), on which a window is placed to host the WebView of the current tag.
 * The page therefore considers itself visible, and timers, animation frames, and lazy loading run as
 * usual; the displayed image is entered into ImageReader and is only used for screenshots and visual
 * positioning.
 *
 * Two pieces of display: the mobile version label is hung on the piece that is the same size as the main
 * screen; the desktop version label is hung on the piece that is the size of the desktop viewport. Each
 * block is only built when there is a label that needs to be used (the desktop version is the default,
 * so build it first when building the host; if it cannot be built, use the mobile phone block). It is
 * not built in vain and displayed in vain; the page is drawn according to 1 CSS pixel to 1 pixel, the
 * screenshot is clear, and the coordinates are not compressed. Only one view can be hung at the same
 * time. [width], [height], and screenshots all click on the block where they are located.
 *
 * Construction, attach/detach, and close are all called on the main thread.
 */
class WebHost(private val context: Context, widthPx: Int, heightPx: Int, densityDpi: Int) : Closeable {
    /** A private display and its windows and frames. */
    private inner class Layer(val width: Int, val height: Int, dpi: Int, name: String) {
        val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val virtualDisplay: VirtualDisplay
        val container: FrameLayout
        val mode: HostMode
        var presentation: Presentation? = null
        var windowManager: WindowManager? = null

        // pendingImage/copyScheduled/lastCopyAt/scratch only moves on the frame thread; the frame is
        // protected by frameLock, and the screenshot is read from any thread
        val frameLock = Any()
        var frame: Bitmap? = null
        var pendingImage: Image? = null
        var copyScheduled = false
        var lastCopyAt = 0L
        var scratch: ByteBuffer? = null

        init {
            var vd: VirtualDisplay? = null
            var addedWindow: View? = null
            try {
                reader.setOnImageAvailableListener({ r -> onFrame(r) }, frameHandler)
                val dm = context.getSystemService(DisplayManager::class.java)
                // flags=0: Private display, only this App can put content on it
                vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, 0)
                    ?: error("createVirtualDisplay returned null")
                val display = vd.display
                var chosen = HostMode.Detached
                var box = showPresentation(display)?.also { chosen = HostMode.Presentation }
                if (box == null) box = addWindow(display)?.also {
                    chosen = HostMode.WindowManager
                    addedWindow = it
                }
                if (box == null) {
                    Log.w(TAG, "web tabs run detached: pages may not fully render")
                    box = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(width, height) }
                }
                virtualDisplay = vd
                container = box
                mode = chosen
                // Which host the view is hung on can be retrieved from its parent container (change the
                // display accordingly when changing versions)
                box.tag = this@WebHost
            } catch (e: Throwable) {
                reader.setOnImageAvailableListener(null, null)
                presentation?.let { p -> runCatching { p.dismiss() } }
                addedWindow?.let { w -> runCatching { windowManager?.removeViewImmediate(w) } }
                vd?.release()
                reader.close()
                throw e
            }
            Log.i(TAG, "web host $name on display ${virtualDisplay.display.displayId} mode=$mode ${width}x$height@$dpi")
        }

        val displayId: Int get() = virtualDisplay.display.displayId

        private fun showPresentation(display: Display): FrameLayout? {
            var p: Presentation? = null
            return try {
                p = Presentation(context, display)
                val c = FrameLayout(p.context)
                p.setContentView(c)
                p.show()
                presentation = p
                c
            } catch (e: Exception) {
                Log.w(TAG, "Presentation on private display failed, trying WindowManager", e)
                runCatching { p?.dismiss() }
                null
            }
        }

        private fun addWindow(display: Display): FrameLayout? = try {
            val wctx = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION, null)
            val wm = wctx.getSystemService(WindowManager::class.java)
            val c = FrameLayout(wctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
                0,
                PixelFormat.OPAQUE,
            )
            wm.addView(c, lp)
            windowManager = wm
            c
        } catch (e: Exception) {
            Log.w(TAG, "WindowManager on private display failed, falling back to detached WebView", e)
            null
        }

        fun copy(): Bitmap? = synchronized(frameLock) { frame?.let { Bitmap.createBitmap(it, 0, 0, width, height) } }

        /** Called after the frame thread has stopped. */
        fun release() {
            reader.setOnImageAvailableListener(null, null)
            pendingImage?.close()
            pendingImage = null
            presentation?.let { p -> runCatching { p.dismiss() } }
            container.removeAllViews()
            windowManager?.let { wm -> runCatching { wm.removeViewImmediate(container) } }
            virtualDisplay.release()
            reader.close()
            synchronized(frameLock) {
                frame?.recycle()
                frame = null
            }
        }

        // Every time a frame comes, only the latest one is kept, and it is copied every 100 milliseconds
        // at most; no new frames will appear on the static page, and the last frame pressed will also be
        // copied.
        private fun onFrame(r: ImageReader) {
            try {
                val image = r.acquireLatestImage() ?: return
                pendingImage?.close()
                pendingImage = image
                if (!copyScheduled) {
                    copyScheduled = true
                    val wait = (FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - lastCopyAt)).coerceAtLeast(0)
                    frameHandler.postDelayed(::copyPending, wait)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "frame acquire failed", e)
            }
        }

        private fun copyPending() {
            copyScheduled = false
            val image = pendingImage ?: return
            pendingImage = null
            try {
                val plane = image.planes[0]
                // There may be alignment padding at the end of the line: the bitmap is allocated once
                // and reused according to the line span, and then cropped to the display width when
                // taking a screenshot.
                val stridePx = plane.rowStride / plane.pixelStride
                synchronized(frameLock) {
                    val cur = frame
                    val target = if (cur != null && cur.width == stridePx && cur.height == image.height) cur
                    else Bitmap.createBitmap(stridePx, image.height, Bitmap.Config.ARGB_8888).also { cur?.recycle() }
                    val src = plane.buffer.apply { rewind() }
                    val needed = target.byteCount
                    if (src.remaining() >= needed) target.copyPixelsFromBuffer(src)
                    else {
                        // The last line may not have padding, and the buffer is shorter than the entire
                        // bitmap: first pad a buffer of equal length
                        val s = scratch?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed).also { scratch = it }
                        s.clear()
                        s.put(src)
                        s.rewind()
                        target.copyPixelsFromBuffer(s)
                    }
                    frame = target
                }
                lastCopyAt = SystemClock.uptimeMillis()
            } catch (e: Throwable) {
                Log.w(TAG, "frame copy failed", e)
            } finally {
                image.close()
            }
        }
    }

    private val phoneWidth = widthPx
    private val phoneHeight = heightPx
    private val phoneDpi = densityDpi
    private val frameThread: HandlerThread
    private val frameHandler: Handler
    private var phone: Layer? = null
    private var desktop: Layer? = null
    private var phoneFailed = false
    private var desktopFailed = false
    @Volatile private var active: Layer

    /** The pixel size of the currently hanging display (that is, the web page viewport). */
    val width: Int get() = active.width
    val height: Int get() = active.height
    /** The id and hanging method of the currently hung piece of display. */
    val displayId: Int get() = active.displayId
    val mode: HostMode get() = active.mode

    /** After [close], it is true: the view cannot be mounted anymore. */
    @Volatile var closed: Boolean = false
        private set

    init {
        checkMain()
        frameThread = HandlerThread("mina-web-frames").apply { start() }
        frameHandler = Handler(frameThread.looper)
        // The new tab defaults to the desktop version: build the desktop part first; if it cannot be
        // built, then build the mobile part. If both parts fail, it will be thrown (the caller only
        // gives the mobile interface)
        active = try {
            desktopLayer() ?: Layer(widthPx, heightPx, densityDpi, PHONE_NAME).also { phone = it }
        } catch (e: Throwable) {
            frameThread.quitSafely()
            frameThread.join()
            throw e
        }
    }

    /**
     * Put the tab's view into the host window, replacing the hung one. When [desktop] is true, it will
     * be hung on the desktop viewport display (if it cannot be built, it will be hung on the mobile
     * phone); by default, the version currently used by this label will be used.
     */
    fun attach(tab: WebTab, desktop: Boolean = tab.devtools.wantsDesktopHost(tab.view)) {
        val view = tab.view
        checkMain()
        if (closed) return
        val target = (if (desktop) desktopLayer() ?: phoneLayer() else phoneLayer() ?: desktopLayer()) ?: active
        if (view.parent === target.container) {
            active = target
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        for (l in listOfNotNull(phone, this.desktop)) if (l !== target) l.container.removeAllViews()
        target.container.removeAllViews()
        target.container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        active = target
        if (target.mode == HostMode.Detached) layoutDetached(target, view)
    }

    fun detach(view: WebView) {
        checkMain()
        for (l in listOfNotNull(phone, desktop)) if (view.parent === l.container) l.container.removeView(view)
    }

    private fun phoneLayer(): Layer? {
        phone?.let { return it }
        if (phoneFailed) return null
        return try {
            Layer(phoneWidth, phoneHeight, phoneDpi, PHONE_NAME).also { phone = it }
        } catch (e: Throwable) {
            Log.w(TAG, "phone-size web display failed; mobile pages use the desktop-size display", e)
            phoneFailed = true
            null
        }
    }

    private fun desktopLayer(): Layer? {
        desktop?.let { return it }
        if (desktopFailed) return null
        return try {
            Layer(DesktopProfile.HOST_WIDTH, DesktopProfile.HOST_HEIGHT, DesktopProfile.HOST_DPI, "mina-web-desktop").also { desktop = it }
        } catch (e: Throwable) {
            Log.w(TAG, "desktop-size web display failed; desktop pages use the phone-size display", e)
            desktopFailed = true
            null
        }
    }

    /** The latest frame on the display (a copy cropped to the display width), used for the small picture-in-picture window; null when there is no picture yet. */
    fun latestFrame(): Bitmap? = active.copy()

    /**
     * The latest frame on display (up to about 100 milliseconds later); null if no frame has been drawn
     * yet. PNG encoding takes tens of milliseconds, so don’t schedule it on the main thread.
     */
    fun latestFramePng(): ByteArray? {
        val bmp = active.copy() ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        // Stop the frame thread first, and then you can safely close the image and reader in its hand
        phone?.reader?.setOnImageAvailableListener(null, null)
        desktop?.reader?.setOnImageAvailableListener(null, null)
        frameThread.quitSafely()
        frameThread.join()
        phone?.release()
        desktop?.release()
    }

    // When the window is not hung, no one layouts the view. You have to manually measure the display
    // size once.
    private fun layoutDetached(l: Layer, view: View) {
        val w = View.MeasureSpec.makeMeasureSpec(l.width, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(l.height, View.MeasureSpec.EXACTLY)
        l.container.measure(w, h)
        l.container.layout(0, 0, l.width, l.height)
        view.requestLayout()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebHost must be used on the main thread" }

    companion object {
        private const val TAG = "MinaWebHost"
        private const val FRAME_INTERVAL_MS = 100L
        private const val PHONE_NAME = "mina-web"

        /**
         * The label of the view has been changed to the mobile version/desktop version: if it is hanging
         * on a certain host, it will be changed to the display of the corresponding size. No matter who
         * put it up in the first place. If it is not on the host (it is in the hands of the user and in
         * the background during takeover), it will not move. The next time it is hung, it will be hung
         * according to the version. Called by the main thread.
         */
        fun followMode(tab: WebTab) {
            val host = (tab.view.parent as? View)?.tag as? WebHost ?: return
            host.attach(tab)
        }
    }
}
