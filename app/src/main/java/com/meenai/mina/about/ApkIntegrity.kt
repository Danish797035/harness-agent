package com.meenai.mina.about

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

/** Why is the update package rejected when it fails the verification? The interface gives different instructions based on this. */
class ApkRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The size and SHA-256 do not match, or the installation package cannot be read at all. */
        Corrupt,
        /** It is not Mina, or the version is not newer than the installed one. */
        NotNewer,
        /** The signing certificate is different from the installed Mina. */
        Signer,
    }
}

/**
 * The download address only accepts HTTPS release attachments from the official repository. Subsequent
 * jumps (to GitHub's attachment storage) are handled by the system download service and are beyond our
 * control, so all downloaded files must be re-verified: size and SHA-256 (the release metadata is
 * verified only after verification), package name, version number, and signature certificate.
 */
internal object ApkIntegrity {
    private val official = "${HarnessLinks.RELEASES}/download/".toHttpUrl()

    fun requireOfficialUrl(url: String) {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host == official.host && parsed.port == 443)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        require(parsed.encodedPath.startsWith(official.encodedPath))
        require(parsed.encodedPath.endsWith(".apk", ignoreCase = true))
    }

    /** If the abstract is given, it must be sha256 and correct; other writing methods are not considered correct, so we will not let it go. */
    fun verify(file: File, size: Long?, digest: String?) {
        corruptUnless(file.isFile && file.length() > 0)
        if (size != null) corruptUnless(file.length() == size)
        if (digest != null) {
            corruptUnless(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            corruptUnless(sha256(file).equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }

    /**
     * The signing certificate of the new package (each certificate SHA-256) must be consistent with the
     * installed one; when the new package is rotated for signature, the installed certificate must be in
     * its signature history. The system installer will also block it. The purpose of blocking it in
     * advance is to give a clear prompt.
     */
    fun requireSameSigner(installed: Set<String>, archive: Set<String>, archiveHistory: Set<String>) {
        if (installed.isEmpty() || archive.isEmpty()) throw ApkRejected(ApkRejected.Reason.Signer)
        if (archive != installed && !archiveHistory.containsAll(installed)) throw ApkRejected(ApkRejected.Reason.Signer)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun corruptUnless(ok: Boolean) {
        if (!ok) throw ApkRejected(ApkRejected.Reason.Corrupt)
    }
}
