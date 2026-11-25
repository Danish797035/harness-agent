package com.meenai.mina.cap.web.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.meenai.mina.core.cap.web.cdp.CdpStream
import com.meenai.mina.core.cap.web.cdp.CdpTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * There are two ways to connect to the WebView debugging socket of this process. An endpoint is an
 * abstract socket name. The time limit of reading and writing is managed by the protocol layer (the flow
 * is turned off at the point, and the blocked reading and writing are returned). There is no socket
 * timeout here.
 */
internal class DirectTransport : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return object : CdpStream {
            override val input: InputStream = s.inputStream
            override val output: OutputStream = s.outputStream
            override fun close() {
                runCatching { s.shutdownInput() }
                runCatching { s.shutdownOutput() }
                s.close()
            }
        }
    }
}

/** Connected by someone else (built-in assistant, shell identity): [relay] After the connection is completed, the socket is handed back; when the party connecting on behalf of the client is not available, it throws IOException. */
internal class RelayTransport(
    private val relay: (String) -> ParcelFileDescriptor?,
) : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val pfd = try {
            relay(endpoint)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("phone helper could not relay: ${e.message}", e)
        } ?: throw IOException("phone helper refused or could not connect $endpoint")
        return FdStream(pfd)
    }
}

/** Hand back the socket fd. When shutting down, shut down first and let the thread blocked in reading return. */
private class FdStream(private val pfd: ParcelFileDescriptor) : CdpStream {
    override val input: InputStream = FileInputStream(pfd.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun close() {
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
        pfd.close()
    }
}
