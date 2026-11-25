package com.meenai.mina.core.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mina's own adb identity: an RSA-2048 key + self-signed certificate. Give [adbPublicKey] to the device
 * when pairing, and present [certificate](the device only compares the public key in the certificate) on every TLS connection thereafter.
 */
class AdbIdentity(val keyPair: KeyPair, val name: String) {
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    val certificate: X509Certificate by lazy { SelfSignedCertificate.create(keyPair) }

    /**
     * A line in the device keystore: base64 (524-byte public key structure) + space + name. There cannot
     * be any spaces in the name: the system settings split this line by spaces. If the name contains
     * spaces, it cannot be deleted from the "Paired Devices".
     */
    val adbPublicKey: String by lazy {
        Base64.getEncoder().encodeToString(AdbPublicKey.encode(publicKey)) + " " + name.replace(Regex("\\s"), "_")
    }

    companion object {
        /** The public index of the new key: F4 (65537). If you can't get the index when reading back the key, press it to fill it in and verify that it is correct. */
        val PUBLIC_EXPONENT: BigInteger = RSAKeyGenParameterSpec.F4

        fun generate(name: String): AdbIdentity {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, PUBLIC_EXPONENT))
            return AdbIdentity(g.generateKeyPair(), name)
        }

        /**
         * Read back the identity from the saved PKCS#8. The platform is not required to provide a
         * private key with CRT parameters: as long as it is an RSA private key, the modulus is taken,
         * and the public index is found as [publicExponent]. Cannot read [AdbKeyUnreadableException].
         */
        fun fromPkcs8(pkcs8: ByteArray, name: String): AdbIdentity {
            val kf = KeyFactory.getInstance("RSA")
            val priv = try {
                kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                throw AdbKeyUnreadableException("stored key is not valid PKCS#8", e)
            }
            val rsa = priv as? RSAPrivateKey ?: throw AdbKeyUnreadableException("stored key is not RSA (${priv.javaClass.simpleName})")
            val pub = kf.generatePublic(RSAPublicKeySpec(rsa.modulus, publicExponent(rsa, pkcs8))) as RSAPublicKey
            return AdbIdentity(KeyPair(pub, rsa), name)
        }

        /**
         * The public index corresponding to the private key: If the platform provides the CRT format,
         * use it directly; otherwise, read it from the RSAPrivateKey structure in PKCS#8; if it is not
         * in the structure (it is written as 0), press [PUBLIC_EXPONENT] to fill it in. Before filling
         * it, sign it once and verify it once. If it does not match, throw it away. Do not use the wrong
         * public key to connect.
         */
        internal fun publicExponent(key: RSAPrivateKey, pkcs8: ByteArray): BigInteger {
            (key as? RSAPrivateCrtKey)?.publicExponent?.takeIf { it.signum() > 0 }?.let { return it }
            Pkcs8Rsa.publicExponent(pkcs8)?.takeIf { it.signum() > 0 }?.let { return it }
            if (!signsWith(key, PUBLIC_EXPONENT)) throw AdbKeyUnreadableException("cannot recover the public exponent of the stored key")
            return PUBLIC_EXPONENT
        }

        private fun signsWith(key: RSAPrivateKey, e: BigInteger): Boolean = runCatching {
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, e))
            val data = "mina-adb-key-check".toByteArray()
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(data); sign() }
            Signature.getInstance("SHA256withRSA").run { initVerify(pub); update(data); verify(sig) }
        }.getOrDefault(false)
    }
}

/** The saved key is there, but it cannot be read (cannot be unlocked or parsed). You cannot quietly replace it with a new one: the device only recognizes the one it was paired with. */
class AdbKeyUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The current status of the key (for diagnostic information). */
enum class AdbKeyState {
    /** The key has not been used in this process. */
    NotLoaded,
    /** Read back the saved one. */
    Loaded,
    /** There was no one before (or the one that could not be read was replaced during pairing), so I created a new one and saved it. */
    Created,
    /** Save a handful but can't read them: need to re-pair them. */
    Unreadable,
}

/**
 * There is only one adb identity in a process: pairing and connection must get the same key, and they
 * will not use different keys because of a certain read failure. Only pairing ([forPairing]) will
 * replace the stored key with a new one when it cannot be read (pairing will originally give the new key
 * to the device); connection ([forConnect]) will throw [AdbKeyUnreadableException] when it cannot be
 * read, and the interface will ask the user to pair again accordingly.
 */
class AdbIdentitySource(
    private val store: AdbKeyStore,
    private val name: () -> String,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private var cached: AdbIdentity? = null

    @Volatile var state: AdbKeyState = AdbKeyState.NotLoaded
        private set

    /** The reason why the latest read cannot be read (for diagnostic information); it is null when it can be read. */
    @Volatile var problem: String? = null
        private set

    /** Use for connection: the existing one; if there is no one, create a new one and save it; if it is saved but cannot be read, throw it away without overwriting. */
    @Synchronized
    fun forConnect(): AdbIdentity = cached ?: load()

    /** For pairing: the same one as [forConnect]; if only the saved one cannot be read, replace it with a new one and save it here (subsequent pairing will give it to the device). */
    @Synchronized
    fun forPairing(): AdbIdentity = cached ?: try {
        load()
    } catch (e: AdbKeyUnreadableException) {
        log("replacing the unreadable adb key for a new pairing", e)
        store.clear()
        create()
    }

    /** Unpairing: The key and cache are deleted together, and a new key is used for next pairing. */
    @Synchronized
    fun forget() {
        cached = null
        store.clear()
        state = AdbKeyState.NotLoaded
        problem = null
    }

    private fun load(): AdbIdentity {
        val bytes = try {
            store.read()
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        } ?: return create()
        val id = try {
            AdbIdentity.fromPkcs8(bytes, name())
        } catch (e: AdbKeyUnreadableException) {
            throw unreadable(e)
        }
        state = AdbKeyState.Loaded
        problem = null
        return id.also { cached = it }
    }

    private fun create(): AdbIdentity {
        val id = AdbIdentity.generate(name())
        store.write(id.keyPair.private.encoded)
        state = AdbKeyState.Created
        problem = null
        return id.also { cached = it }
    }

    private fun unreadable(e: AdbKeyUnreadableException): AdbKeyUnreadableException {
        state = AdbKeyState.Unreadable
        problem = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.message ?: it.javaClass.simpleName }.take(200)
        log("stored adb key is unreadable; pair again to replace it", e)
        return e
    }
}

/**
 * Access to keys (PKCS#8). The app uses Android Keystore to wrap a layer of encrypted storage. If [read]
 * has not been saved, null will be returned; if it is saved but cannot be read,
 * [AdbKeyUnreadableException](the two must be separated, the latter cannot be regarded as absent) will be thrown.
 */
interface AdbKeyStore {
    fun read(): ByteArray?
    fun write(pkcs8: ByteArray)
    fun clear()
}

/**
 * Encrypt the key stored in the file: file content = 1 byte IV length + IV + AES-GCM ciphertext. The IV
 * is generated by Cipher itself (the Android Keystore key does not allow the caller to specify the IV).
 */
class WrappedKeyFile(private val file: File, private val wrappingKey: () -> SecretKey) : AdbKeyStore {
    override fun read(): ByteArray? {
        if (!file.isFile) return null
        return try {
            val b = file.readBytes()
            require(b.isNotEmpty()) { "key file is empty" }
            val ivLen = b[0].toInt() and 0xff
            require(b.size > 1 + ivLen) { "key file is truncated" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
            c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        } catch (e: Exception) {
            throw AdbKeyUnreadableException("cannot unwrap ${file.name}", e)
        }
    }

    override fun write(pkcs8: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = c.iv
        val out = byteArrayOf(iv.size.toByte()) + iv + c.doFinal(pkcs8)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(out)
        check(tmp.renameTo(file)) { "cannot write ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * Read the RSA public exponent from PKCS#8:PrivateKeyInfo::= SEQUENCE { version, algorithm, privateKey
 * OCTET STRING }, privateKey is RSAPrivateKey::= SEQUENCE { version, modulus, publicExponent,... }.
 * Returns null if the structure is not correct.
 */
internal object Pkcs8Rsa {
    fun publicExponent(pkcs8: ByteArray): BigInteger? = runCatching {
        val info = Der(pkcs8).read(0x30)
        val inner = Der(info).apply { read(0x02); read(0x30) }.read(0x04)
        val rsa = Der(Der(inner).read(0x30))
        rsa.read(0x02)
        rsa.read(0x02)
        BigInteger(rsa.read(0x02))
    }.getOrNull()

    /** Only enough to read the upper layers of DER: take the contents of the specified tags in order. */
    private class Der(private val b: ByteArray) {
        private var pos = 0

        fun read(tag: Int): ByteArray {
            require((b[pos++].toInt() and 0xff) == tag) { "unexpected tag" }
            var len = b[pos++].toInt() and 0xff
            if (len >= 0x80) {
                val n = len and 0x7f
                require(n in 1..3) { "unsupported length" }
                len = 0
                repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xff) }
            }
            require(pos + len <= b.size) { "truncated" }
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }
    }
}

/**
 * adb's RSA public key binary format (all little-endian, 524 bytes in total): modulo digit (64), n0inv =
 * −1/n mod 2^32, modulus n, rr = (2^2048)^2 mod n, public exponent.
 */
object AdbPublicKey {
    private const val WORDS = 64
    const val SIZE = 4 + 4 + WORDS * 4 * 2 + 4

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == 2048) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32 - n.mod(r32).modInverse(r32)
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(EdPoint.toLe(n, 256))
        buf.put(EdPoint.toLe(rr, 256))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }
}

/** Minimal self-signed X.509 v3 certificate (no extensions): CN=Mina,1970-01-01 to 2099-12-31,SHA256withRSA. */
internal object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(keyPair: KeyPair): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, ByteArray(0)))
        val name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME), tlv(0x0c, "Mina".toByteArray()))))
        val validity = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            algId, name, validity, name,
            keyPair.public.encoded,
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    internal fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
