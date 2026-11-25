package com.meenai.mina.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Settings › Network proxy: You can add an account and password, encrypt and save it as a file without leaving any clear text; the address must be checked before saving; the way it is written for people to read does not include the account and password; the copy saved when the old version is only for the container will still be connected. */
class NetProxyStoreTest {
    private val dir: File = Files.createTempDirectory("proxy").toFile()

    /** "Encryption" for testing: reverse byte by byte, enough to see that the file is not the original text. */
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { plain[it].toInt().inv().toByte() }
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    @Test fun storedEncryptedAndReadBack() {
        val proxy = "http://alice:s3cret@10.0.0.2:8080"
        NetProxyStore(dir, cipher).set(proxy)
        val file = File(dir, "net-proxy.bin")
        assertTrue(file.isFile)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("文件里不是明文", raw.contains("s3cret") || raw.contains("alice"))
        // Open a new copy (like after restarting) and you can still read it.
        assertEquals(proxy, NetProxyStore(dir, cipher).get())
    }

    @Test fun emptyRemovesTheFile() {
        val store = NetProxyStore(dir, cipher)
        store.set("socks5://127.0.0.1:1080")
        store.set("  ")
        assertEquals("", store.get())
        assertFalse(File(dir, "net-proxy.bin").exists())
        assertEquals("", NetProxyStore(dir, cipher).get())
    }

    /** If it cannot be unlocked (the key is gone), it should be treated as unset, and the command will not be connected with garbled codes. */
    @Test fun unreadableCountsAsNotSet() {
        File(dir, "net-proxy.bin").writeBytes(byteArrayOf(1, 2, 3))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, broken).get())
    }

    @Test fun onlyProxyAddressesAreAccepted() {
        for (ok in listOf("", "http://127.0.0.1:7890", "https://proxy.example.com:443", "socks5h://u:p@[::1]:1080", "HTTP://host:80/")) {
            assertTrue(ok, NetProxyStore.valid(ok))
        }
        for (bad in listOf("127.0.0.1:7890", "ftp://h:21", "http://host", "http://host:0", "http://host:70000", "http://h:80/path", "http://h :80", "http://h:80?x=1")) {
            assertFalse(bad, NetProxyStore.valid(bad))
        }
    }

    @Test fun displayLeavesOutCredentials() {
        assertEquals("http://10.0.0.2:8080", NetProxyStore.display("http://alice:s3cret@10.0.0.2:8080"))
        assertEquals("socks5://127.0.0.1:1080", NetProxyStore.display("socks5://127.0.0.1:1080"))
    }

    /** Another "key": add one by byte, which cannot be solved with [cipher]. */
    private val legacyCipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it] + 1).toByte() }
        override fun decrypt(data: ByteArray) = ByteArray(data.size) { (data[it] - 1).toByte() }
    }

    /** The old version only provides the proxy that is saved when the container is used: it can still be read after the upgrade, change it to a new file, delete the old one, and then only the new one will be recognized. */
    @Test fun containerOnlySettingIsCarriedOver() {
        val old = File(dir, "container-proxy.bin")
        old.writeBytes(legacyCipher.encrypt("socks5://bob:pw@10.0.0.9:1080".toByteArray()))
        var changes = 0
        val store = NetProxyStore(dir, cipher, legacyCipher) { changes++ }
        assertEquals("socks5://bob:pw@10.0.0.9:1080", store.get())
        assertFalse(old.exists())
        assertTrue(File(dir, "net-proxy.bin").isFile)
        assertEquals("读的时候不算改了设置", 0, changes)
        // After restarting (there are no old files anymore) it is still the same
        assertEquals("socks5://bob:pw@10.0.0.9:1080", NetProxyStore(dir, cipher, legacyCipher).get())
        store.set("http://127.0.0.1:7890")
        assertEquals(1, changes)
    }

    /** The new one is already there: the old file does not overwrite it. */
    @Test fun newSettingWinsOverLeftoverLegacyFile() {
        NetProxyStore(dir, cipher).set("http://10.0.0.2:8080")
        File(dir, "container-proxy.bin").writeBytes(legacyCipher.encrypt("http://old:1".toByteArray()))
        assertEquals("http://10.0.0.2:8080", NetProxyStore(dir, cipher, legacyCipher).get())
    }

    /** The old file cannot be opened: if it is not set, no error will be reported. */
    @Test fun unreadableLegacyFileCountsAsNotSet() {
        File(dir, "container-proxy.bin").writeBytes(byteArrayOf(9, 9))
        val broken = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(data: ByteArray): ByteArray = throw IllegalStateException("key gone")
        }
        assertEquals("", NetProxyStore(dir, cipher, broken).get())
    }
}
