package com.meenai.mina.core.vision

import com.meenai.mina.core.model.Part
import kotlinx.coroutines.CancellationException
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures sent to any model (screen reading, screenshots, review of the main model, view model,
 * checker) are encoded in this way: JPEG, the long side does not exceed [MAX_SIDE], and the quality is
 * [QUALITY]. A full-resolution PNG costs several thousand tokens and MB, and screen reading relies on
 * the control tree. You only need to be able to see the layout and text clearly on the screen. Which
 * screens cannot be sent (protected pages, payment pages) are decided by the caller, only coding is done
 * here.
 */
object ModelImage {
    const val MAX_SIDE = 1280
    const val QUALITY = 80

    /**
     * Encoded into a picture sent to the model. When [ops] is empty or cannot be decoded, it is sent as
     * it is (identify mime according to the file header). If the size can be read, bring it.
     * [screenshotId]: Which time the screen is read; if it is not a screen image, it is null.
     */
    fun part(img: ByteArray, ops: ImageOps?, screenshotId: Int? = null): Part.Image {
        val jpeg = ops?.let { o -> attempt { o.toJpeg(img, MAX_SIDE, QUALITY) } }
        val out = jpeg ?: img
        val size = ops?.let { o -> attempt { o.size(out) } } ?: pngSize(out)
        return Part.Image(
            Base64.getEncoder().encodeToString(out), if (jpeg != null) "image/jpeg" else mimeOf(img),
            size?.first ?: 0, size?.second ?: 0, screenshotId,
        )
    }

    /** Size after scaling according to [MAX_SIDE] (same algorithm as [ImageOps.toJpeg]); used for estimation. */
    fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val f = MAX_SIDE.toDouble() / max(width, height)
        if (f >= 1.0) return width to height
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    private inline fun <T> attempt(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun mimeOf(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
        else -> "image/png"
    }

    /** Read the width and height directly in PNG's IHDR; if it is not PNG, it will be null. */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()) return null
        fun int(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
}
