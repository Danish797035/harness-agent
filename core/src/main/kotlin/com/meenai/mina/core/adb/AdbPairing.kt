package com.meenai.mina.core.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** The type of service advertised by adbd via mDNS. */
object AdbServiceTypes {
    /** Announced only when the "Pair using pairing code" dialog box is open. */
    const val PAIRING = "_adb-tls-pairing._tcp"
    /** Announced when wireless debugging is on; the port will change every time wireless debugging is turned on and off. */
    const val CONNECT = "_adb-tls-connect._tcp"
}

/** Find the service port published by adbd on the local network; if not found (timeout), null is returned. */
/**
 * Find the service port published by adbd on this machine. Several entries may be found for the same
 * service (after turning wireless debugging off and on again, the old one may still be in mDNS, and the
 * new one may be renamed with a serial number). Hand them all over, with the first one found first, and
 * the caller will try them one by one.
 */
fun interface AdbServiceFinder {
    suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int>
}

class AdbPairingException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { CONNECT_FAILED, TLS_FAILED, WRONG_CODE, PROTOCOL }
}

/** The identity reported by the device when pairing is successful (usually a device GUID of type=1). */
class AdbPeerInfo(val type: Int, val data: String)

/**
 * Pairing code pairing for wireless debugging (client). Process: TLS 1.3 → Export 64-byte key
 * ("adb-label\0") → Password = pairing code + export key → Exchange SPAKE2 message → Use negotiated
 * AES-GCM to exchange 8192-byte PeerInfo (we send adb public key, the device returns GUID). There is a
 * 6-byte header in front of each packet: version (1), type (0=SPAKE2,1=PEER_INFO), payload length
 * (big-endian uint32).
 */
class AdbPairing(private val tls: AdbTls) {
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000): AdbPeerInfo {
        val socket = try {
            tls.connect(host, port, timeoutMs)
        } catch (e: SSLException) {
            throw AdbPairingException(AdbPairingException.Reason.TLS_FAILED, "TLS handshake failed", e)
        } catch (e: IOException) {
            throw AdbPairingException(AdbPairingException.Reason.CONNECT_FAILED, "cannot connect to $host:$port", e)
        }
        socket.use { return exchange(it, code) }
    }

    private fun exchange(socket: SSLSocket, code: String): AdbPeerInfo {
        val exported = tls.exportKeyingMaterial(socket, EXPORT_LABEL, EXPORT_LENGTH)
        val auth = PairingAuth.client(code.toByteArray(Charsets.UTF_8) + exported)
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        writePacket(output, TYPE_SPAKE2, auth.message)
        val theirMsg = readPacket(input, TYPE_SPAKE2) ?: throw protocol("connection closed before key exchange")
        try {
            auth.initCipher(theirMsg)
        } catch (e: IllegalArgumentException) {
            throw protocol("bad key exchange message: ${e.message}")
        }

        val info = ByteArray(PEER_INFO_SIZE)
        info[0] = PEER_RSA_PUB_KEY
        val key = tls.identity.adbPublicKey.toByteArray(Charsets.UTF_8)
        check(key.size < PEER_INFO_SIZE - 1)
        System.arraycopy(key, 0, info, 1, key.size)
        writePacket(output, TYPE_PEER_INFO, auth.encrypt(info))

        // When the pairing code is incorrect, the device cannot unlock our PeerInfo and disconnects
        // directly.
        val sealed = try {
            readPacket(input, TYPE_PEER_INFO)
        } catch (e: IOException) {
            if (e is AdbPairingException) throw e
            null
        } ?: throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "device closed the connection (wrong pairing code?)")
        val plain = try {
            auth.decrypt(sealed)
        } catch (e: AEADBadTagException) {
            throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "cannot decrypt device info (wrong pairing code?)", e)
        }
        if (plain.size != PEER_INFO_SIZE) throw protocol("peer info has ${plain.size} bytes")
        var end = 1
        while (end < plain.size && plain[end] != 0.toByte()) end++
        return AdbPeerInfo(plain[0].toInt() and 0xff, String(plain, 1, end - 1, Charsets.UTF_8))
    }

    private fun protocol(msg: String) = AdbPairingException(AdbPairingException.Reason.PROTOCOL, msg)

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_LENGTH = 64
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1

        fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).put(VERSION).put(type).putInt(payload.size).array()
            out.write(header + payload)
            out.flush()
        }

        /** Read a packet and check the type; the other party disconnects before the packet header and returns null. */
        fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray? {
            val header = ByteArray(6)
            try {
                input.readFully(header)
            } catch (e: EOFException) {
                return null
            }
            val bb = ByteBuffer.wrap(header)
            val version = bb.get()
            val type = bb.get()
            val len = bb.getInt()
            if (version != VERSION) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unsupported pairing version $version")
            if (type != expectedType) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unexpected packet type $type")
            if (len <= 0 || len > MAX_PAYLOAD) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "bad payload size $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}
