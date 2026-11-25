package com.meenai.mina.settings

import android.content.Context
import com.meenai.mina.cap.web.WebProxy
import com.meenai.mina.core.net.NetProxy
import com.meenai.mina.core.net.ProxyAddress
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Settings › Network proxy, in the shape of `http://[user:password@]host:port`; an empty string means no use, direct
 * connection. The address may contain the account password, so like the service provider's Key, it is
 * encrypted by [KeyCipher] and stored in a separate file, without entering clear text preferences or
 * writing it into the log.
 *
 * Who uses it: Mina's own connection (model adjustment, column model, account login for token, download
 * update, built-in browser) is directly connected to the local machine and LAN address through
 * [NetProxy]; files and commands in the computing environment can obtain it through environment
 * variables when networking is allowed (see `AndroidSandbox`).
 *
 * In the past, it was only used for containers, and there was `container-proxy.bin` (another key): use
 * [legacyCipher] to solve it when reading it for the first time, save it as a new one, and then delete
 * the old file.
 */
class NetProxyStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val legacyCipher: KeyCipher? = null,
    /** Adjust after saving the new value (let the established connection be reconnected according to the new settings). */
    private val onChange: () -> Unit = {},
) {
    private val file = File(dir, FILE)
    private val legacy = File(dir, LEGACY_FILE)
    @Volatile private var cached: String? = null

    /** The existing agent; if it does not exist or cannot be decrypted (the machine has been changed or the key is lost), it will be an empty string. */
    @Synchronized fun get(): String {
        cached?.let { return it }
        migrate()
        val stored = runCatching { if (file.isFile) String(cipher.decrypt(file.readBytes()), Charsets.UTF_8) else "" }.getOrDefault("")
        return stored.also { cached = it }
    }

    /** Save [proxy](remove both ends of the blank); delete the file if it is empty. The caller first checks with [valid]. */
    fun set(proxy: String) {
        synchronized(this) {
            val v = proxy.trim()
            require(valid(v)) { "not a proxy address" }
            if (v.isEmpty()) file.delete() else write(v)
            legacy.delete()
            cached = v
        }
        onChange()
    }

    /** The old version is only the one saved when the container is in use: if it can be unlocked, save it as the new one; if the new one already exists, or if it cannot be unlocked, leave it untouched, and delete it when new values are saved later. */
    private fun migrate() {
        val old = legacyCipher ?: return
        if (file.isFile || !legacy.isFile) return
        val v = runCatching { String(old.decrypt(legacy.readBytes()), Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            if (v.isNotBlank()) write(v.trim())
            legacy.delete()
        }
    }

    private fun write(v: String) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(cipher.encrypt(v.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val FILE = "net-proxy.bin"
        private const val LEGACY_FILE = "container-proxy.bin"
        private const val KEY_ALIAS = "mina-net-proxy"
        private const val LEGACY_KEY_ALIAS = "mina-container-proxy"

        /** Empty string (not used), or proxy address with protocol, host, and port (http, https, socks5, socks5h), without a path. */
        fun valid(proxy: String): Boolean = ProxyAddress.valid(proxy)

        /** The way it is written for people to see: without account and password (that line can be seen at any time on the settings page). */
        fun display(proxy: String): String = ProxyAddress.parse(proxy)?.display.orEmpty()

        @Volatile private var instance: NetProxyStore? = null

        /** The one in the product; connect [NetProxy.shared] to it when you first pick it up. */
        fun get(context: Context): NetProxyStore = instance ?: synchronized(this) {
            instance ?: NetProxyStore(
                File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(KEY_ALIAS), KeystoreKeyCipher(LEGACY_KEY_ALIAS),
                onChange = { NetProxy.shared.changed(); WebProxy.apply(context.applicationContext) },
            ).also { store ->
                instance = store
                NetProxy.sharedSource = store::get
            }
        }
    }
}
