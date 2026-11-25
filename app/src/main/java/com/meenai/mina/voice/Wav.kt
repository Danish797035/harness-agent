package com.meenai.mina.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pack the 16-bit mono PCM into standard WAV (44-byte header + little-endian data) and upload it to the service provider's text-to-text interface. */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray {
        val dataSize = pcm16.size * 2
        val buf = ByteBuffer.allocate(HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // fmt block length
        buf.putShort(1)             // PCM
        buf.putShort(1) // mono
        buf.putInt(rate)
        buf.putInt(rate * 2) // Bytes per second = sample rate × channels × 2 bytes
        buf.putShort(2) // block alignment
        buf.putShort(16) // bit depth
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm16) buf.putShort(s)
        return buf.array()
    }

    /** A silent WAV with all zeros, used to test whether a certain model can convert text (no real recording is required). */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
