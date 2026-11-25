package com.meenai.mina.core.visual

import kotlin.math.abs

/**
 * A brightness grid reduced into a frame ([cols]×[rows], row priority): take one before and after the
 * action as circumstantial evidence to judge the effect (mainly based on the interface status). It only
 * uses the pixel reading function for sampling and does not rely on Android classes (can be tested on
 * the JVM); the port takes the ready-made bitmap sampling and does not encode the image.
 */
class FrameGrid(val cols: Int, val rows: Int, val luma: IntArray) {
    init { require(luma.size == cols * rows) }

    companion object {
        private const val SAMPLES = 2

        /** Cut the [width]×[height] picture into grids, and take the average brightness of 2×2 points in each grid; [pixel] returns the ARGB at (x, y). A screen that is too small is null. */
        fun sample(width: Int, height: Int, pixel: (Int, Int) -> Int): FrameGrid? {
            val cols = FrameTuning.GRID_COLS; val rows = FrameTuning.GRID_ROWS
            val cw = width / cols
            val ch = height / rows
            if (cw == 0 || ch == 0) return null
            val out = IntArray(cols * rows)
            for (gy in 0 until rows) for (gx in 0 until cols) {
                var sum = 0
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    sum += luma(pixel(gx * cw + (2 * sx + 1) * cw / (2 * SAMPLES), gy * ch + (2 * sy + 1) * ch / (2 * SAMPLES)))
                }
                out[gy * cols + gx] = sum / (SAMPLES * SAMPLES)
            }
            return FrameGrid(cols, rows, out)
        }

        private fun luma(p: Int) = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
    }
}

/** Between two frames: the changed grid ratio of the entire screen is [whole], and the ratio in the target area is [region] (no area given is null). */
data class FrameChange(val whole: Double, val region: Double?) {
    /** More than a few grids have obviously changed (the cursor flashing and the ripple of a dot are not counted). */
    val visible: Boolean get() = whole >= FrameTuning.FRAME_VISIBLE || (region ?: 0.0) >= FrameTuning.REGION_VISIBLE
    /** The entire screen area has changed. */
    val large: Boolean get() = whole >= FrameTuning.FRAME_LARGE
}

/** A rough comparison of the two frames before and after the action: if the brightness difference of each frame exceeds [FrameTuning.CELL_DELTA], the frame has changed. */
object FrameDiff {
    /** [roi] is the target area (0–999 normalized [l,t,r,b]); the two frames are inconsistent in size and null if one frame is missing. */
    fun compare(before: FrameGrid?, after: FrameGrid?, roi: List<Int>? = null): FrameChange? {
        if (before == null || after == null || before.cols != after.cols || before.rows != after.rows) return null
        val cols = before.cols; val rows = before.rows
        var changed = 0
        var inRegion = 0; var regionChanged = 0
        val box = roi?.takeIf { it.size == 4 }?.let { r ->
            // Expand half a grid outward to prevent small controls from falling into the cracks of the
            // grid.
            val l = (r[0] * cols / 1000.0 - 0.5).toInt().coerceIn(0, cols - 1)
            val t = (r[1] * rows / 1000.0 - 0.5).toInt().coerceIn(0, rows - 1)
            val rr = (r[2] * cols / 1000.0 + 0.5).toInt().coerceIn(l, cols - 1)
            val b = (r[3] * rows / 1000.0 + 0.5).toInt().coerceIn(t, rows - 1)
            intArrayOf(l, t, rr, b)
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * cols + x
            val d = abs(before.luma[i] - after.luma[i]) > FrameTuning.CELL_DELTA
            if (d) changed++
            if (box != null && x in box[0]..box[2] && y in box[1]..box[3]) {
                inRegion++
                if (d) regionChanged++
            }
        }
        return FrameChange(changed.toDouble() / (cols * rows), box?.let { regionChanged.toDouble() / inRegion.coerceAtLeast(1) })
    }
}
