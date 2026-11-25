package com.meenai.mina.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.meenai.mina.shell.IHarnessShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Agent-specific virtual screen. The screen is output to the ImageReader of this process, and the
 * screenshot does not rely on the accessible screenshot interface. The frame processing speed is limited
 * to ≤10 frames/second: if the incoming frame is less than 100ms from the last processing, the
 * expiration will be delayed and the latest frame will be fetched. During this period, the old frame
 * will be discarded by acquireLatestImage.
 */
class AgentDisplay(
    private val shell: IHarnessShell,
    val width: Int,
    val height: Int,
    dpi: Int,
) {
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** Real-time picture: speed limit ≤10 frames/second, zoom to width ≤360. */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // Full frame with row padding, width = rowStride / pixelStride
    private var hasFrame = false
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
    private val previewHeight = maxOf(1, height * previewWidth / width)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try {
            shell.createDisplay(reader.surface, width, height, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                Canvas(preview).drawBitmap(bmp, Rect(0, 0, width, height), Rect(0, 0, previewWidth, previewHeight), paint)
                _frames.value = preview
            }
        } finally {
            image.close()
        }
    }

    /** The entire PNG of the latest frame; returns null if the frame has not been received. */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** OCR uses an original size copy of the frame, avoiding PNG encoding and decoding; the caller is responsible for recycle. */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        // End on the frame thread to avoid concurrency with frames being processed
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val PREVIEW_MAX_WIDTH = 360
        const val FRAME_INTERVAL_MS = 100L
    }
}
