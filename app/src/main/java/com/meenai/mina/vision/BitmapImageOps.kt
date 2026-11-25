package com.meenai.mina.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.meenai.mina.core.vision.ImageOps
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Image processing implemented using system Bitmap; the modified image is output as PNG (without losing details), and only [toJpeg] is output as JPEG. */
object BitmapImageOps : ImageOps {
    /** Pixels with a brightness lower than it are considered black. */
    private const val BLACK_LUMA = 16
    private const val BLACK_SHARE = 0.98
    /** When judging all black, the maximum number of points can be drawn, and it is too slow to view the entire screen point by point. */
    private const val SAMPLE_SIDE = 64

    private fun decode(img: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(img, 0, img.size) ?: throw IllegalArgumentException("not a decodable image")

    private inline fun <T> use(img: ByteArray, block: (Bitmap) -> T): T {
        val bmp = decode(img)
        try { return block(bmp) } finally { bmp.recycle() }
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    /** Recycle after encoding; the result may be the original image (the system will directly return to the original image if the size has not changed), and repeated recycling is harmless. */
    private fun Bitmap.png(): ByteArray = try { encode(Bitmap.CompressFormat.PNG, 100) } finally { recycle() }

    private fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt().coerceIn(0, size - 1)

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    override fun size(img: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img, 0, img.size, o)
        require(o.outWidth > 0 && o.outHeight > 0) { "not a decodable image" }
        return o.outWidth to o.outHeight
    }

    /** Taking the true average of each block: shrinking to 8×8 in one step is just a sampling point, and the results of the same picture twice may be different. Read line by line, not the entire screen pixels at once. */
    override fun aHash(img: ByteArray): Long = use(img) { bmp ->
        val w = bmp.width; val h = bmp.height
        val sum = LongArray(64); val count = IntArray(64)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val by = y * 8 / h * 8
            for (x in 0 until w) {
                val i = by + x * 8 / w
                sum[i] += luma(row[x]); count[i]++
            }
        }
        val gray = DoubleArray(64) { if (count[it] == 0) 0.0 else sum[it].toDouble() / count[it] }
        val mean = gray.sum() / 64.0
        gray.foldIndexed(0L) { i, acc, g -> if (g >= mean) acc or (1L shl i) else acc }
    }

    override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int): ByteArray = use(img) { bmp ->
        val f = maxSide.toDouble() / max(bmp.width, bmp.height)
        val out = if (f < 1.0) {
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).roundToInt()), max(1, (bmp.height * f).roundToInt()), true)
        } else bmp
        try { out.encode(Bitmap.CompressFormat.JPEG, quality) } finally { if (out !== bmp) out.recycle() }
    }

    override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int): ByteArray = use(img) { bmp ->
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val half = strokePx / 2f
        // The stroke is centered on the line; the pixel grids on the right and bottom are [r, r+1), so
        // the line must fall on r+1, so that the four sides have the same width and are all within the
        // frame
        val rect = RectF(
            toPx(box[0], out.width) - expandPx + half, toPx(box[1], out.height) - expandPx + half,
            toPx(box[2], out.width) + 1 + expandPx - half, toPx(box[3], out.height) + 1 + expandPx - half,
        )
        val paint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = strokePx.toFloat() }
        Canvas(out).drawRect(rect, paint)
        out.png()
    }

    override fun crop(img: ByteArray, box: IntArray): ByteArray = use(img) { bmp ->
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val l = toPx(box[0], bmp.width); val t = toPx(box[1], bmp.height)
        val r = toPx(box[2], bmp.width); val b = toPx(box[3], bmp.height)
        require(r >= l && b >= t) { "empty box" }
        Bitmap.createBitmap(bmp, l, t, r - l + 1, b - t + 1).png()
    }

    override fun scale(img: ByteArray, factor: Double): ByteArray = use(img) { bmp ->
        require(factor > 0) { "factor must be positive" }
        Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * factor).roundToInt()), max(1, (bmp.height * factor).roundToInt()), true)
            .png()
    }

    override fun pixels(img: ByteArray): IntArray = use(img) { bmp ->
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    override fun isMostlyBlack(img: ByteArray): Boolean = use(img) { bmp ->
        val sx = max(1, bmp.width / SAMPLE_SIDE); val sy = max(1, bmp.height / SAMPLE_SIDE)
        var total = 0; var black = 0
        for (y in 0 until bmp.height step sy) for (x in 0 until bmp.width step sx) {
            total++
            if (luma(bmp.getPixel(x, y)) < BLACK_LUMA) black++
        }
        black >= total * BLACK_SHARE
    }
}
