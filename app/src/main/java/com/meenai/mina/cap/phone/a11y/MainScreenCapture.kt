package com.meenai.mina.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.normalize
import com.meenai.mina.core.userscreen.CapturedScreen
import com.meenai.mina.core.userscreen.UserScreen
import com.meenai.mina.core.userscreen.UserScreenText
import com.meenai.mina.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Take a screenshot of the screen that the user is looking at at the moment on the home screen, together
 * with the front-end App and the words on the screen (see [UserScreenText]: Read the accessibility tree
 * first, and recognize the words on the screen if not enough). When there is an anti-screenshot window
 * on the screen (the screenshot will report an error, or the screenshot will be almost completely black
 * but there is an app window on the screen), the screen will not be left, just note which app it is
 * from, and the screenshot will not be taken. It is up to the caller whether Mina's own floating layer
 * should be hidden first (see [OverlayWindow.hiddenWhile]); floating layers and input methods do not
 * count when reading trees.
 */
internal object MainScreenCapture {
    private const val TAG = "MinaScreenCapture"
    private const val JPEG_QUALITY = 88
    /** You can only wait this long to learn the characters: if it times out, just use the characters in the tree. */
    private const val OCR_WAIT_MS = 4_000L

    sealed interface Result {
        class Captured(val shot: CapturedScreen) : Result
        /** The front desk is Mina herself: no cut. */
        data object Self : Result
        data object Unavailable : Result
    }

    /** Only view the ownership of the window root and do not traverse the text; refuse to share if there is an unknown ownership. */
    fun sourcePackages(service: ScreenAccessibility): Set<String>? {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (windows.isEmpty()) return null
        val packages = windows.map { it.root?.packageName?.toString() }
        if (packages.any { it.isNullOrBlank() }) return null
        return packages.filterNotNull().toSet()
    }

    /** Screenshot (the caller has hidden the floating layer). The tree should be read at the same moment as the screenshot: read it immediately after taking the screenshot. */
    suspend fun shoot(service: ScreenAccessibility): Pair<ScreenAccessibility.MainShot, ScreenRead?> {
        val shot = service.takeMainScreenshot()
        val read = runCatching { readTree(service) }.getOrNull()
        return shot to read
    }

    /** The screenshots and trees are organized into one screen (in the background: encoding, character recognition); [topInset]/[bottomInset] are the pixels occupied by the status bar and navigation bar. */
    suspend fun build(
        service: ScreenAccessibility, shot: ScreenAccessibility.MainShot, read: ScreenRead?,
        topInset: Int, bottomInset: Int, appLabel: (String) -> String,
    ): Result = withContext(Dispatchers.Default) {
        val pkg = read?.topPackage
        if (pkg == service.packageName) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return@withContext Result.Self
        }
        val now = System.currentTimeMillis()
        val label = pkg?.let(appLabel)
        when (shot) {
            is ScreenAccessibility.MainShot.Failed -> {
                Log.w(TAG, "screenshot failed: ${shot.code}")
                Result.Unavailable
            }
            ScreenAccessibility.MainShot.Secure -> Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
            is ScreenAccessibility.MainShot.Ok -> {
                val bmp = shot.bitmap
                try {
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                    // Before Android 12, the screenshot of the anti-screenshot window was black: if
                    // there was an App window on the screen and the screen was almost completely black,
                    // it would be regarded as unable to capture, and no black picture would be sent.
                    if (read?.anyAppWindow != false && BitmapImageOps.isMostlyBlack(jpeg)) {
                        return@withContext Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
                    }
                    val snap = read?.let { snapshotOf(it, bmp.width, bmp.height) }
                    val ocr = if (snap != null && UserScreenText.needsOcr(snap)) recognize(bmp) else null
                    val (lines, fromOcr) = snap?.let {
                        UserScreenText.lines(it, ocr, top = normalize(topInset, bmp.height), bottom = normalize(bmp.height - bottomInset, bmp.height))
                    } ?: (emptyList<com.meenai.mina.core.userscreen.ScreenLine>() to false)
                    Result.Captured(CapturedScreen(jpeg, UserScreen("", now, pkg, label, lines = lines, fromOcr = fromOcr)))
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** Windows belonging to the App on the home screen (status bar, navigation bar, input method, floating layer are not counted) are read as elements. */
    private fun readTree(service: ScreenAccessibility): ScreenRead {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val m = service.resources.displayMetrics
        val size = runCatching { service.overlays().area() }.getOrNull()
        return ScreenReader.read(windows, size?.width ?: m.widthPixels, size?.height ?: m.heightPixels)
    }

    private fun snapshotOf(r: ScreenRead, width: Int, height: Int) = ScreenSnapshot(
        snapshotId = "user-screen", packageName = r.topPackage, elements = r.entries.map { it.element },
        width = width, height = height, hasWindow = r.anyAppWindow, windows = r.windows,
    )

    private suspend fun recognize(bmp: Bitmap) = try {
        withTimeoutOrNull(OCR_WAIT_MS) { PhoneOcr.read(bmp.copy(Bitmap.Config.ARGB_8888, false)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "text recognition failed", e)
        null
    }
}
