package com.meenai.mina.core.cap.sandbox

import java.io.File
import java.security.MessageDigest

object Sha256 {
    /** Streaming calculation, returns lowercase hexadecimal. */
    fun ofFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
