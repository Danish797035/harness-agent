package com.meenai.mina.core.vision

/**
 * Image processing interface: The input is encoded image bytes (screenshot PNG, etc.), and the output is
 * also encoded bytes. The frame coordinates [box] are [left, top, right, bottom], normalized by 0–999,
 * and have the same caliber as the bounds of the screen reading element.
 */
interface ImageOps {
    fun size(img: ByteArray): Pair<Int, Int>

    /** 8×8 grayscale average hashing; the smaller the [hamming] distance between two frames, the more similar they are. */
    fun aHash(img: ByteArray): Long

    /** Compression before sending to the model: the long side does not exceed [maxSide]. */
    fun toJpeg(img: ByteArray, maxSide: Int = 1280, quality: Int = 80): ByteArray

    /** Draw a red frame where [box] extends [expandPx]. */
    fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int = 3, strokePx: Int = 4): ByteArray

    fun crop(img: ByteArray, box: IntArray): ByteArray

    fun scale(img: ByteArray, factor: Double): ByteArray

    /** Pixel-by-pixel ARGB, row-major. */
    fun pixels(img: ByteArray): IntArray

    /** Almost completely black: This is what a protected page cutout looks like. */
    fun isMostlyBlack(img: ByteArray): Boolean
}

fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
