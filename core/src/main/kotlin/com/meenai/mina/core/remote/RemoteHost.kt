package com.meenai.mina.core.remote

import com.meenai.mina.core.device.DeviceAccess
import java.net.InetAddress
import java.net.InetSocketAddress

/** A little bit of movement displayed on the settings page: how many clients have been connected recently and when was the last request. */
data class RemoteActivity(val recentClients: Int = 0, val lastRequestAtMs: Long? = null)

/**
 * Combined entrance for computer connection: Shared HTTP monitoring and authentication, common task API
 * and MCP are connected to the same [RemoteTaskService] in parallel. Only listen to [port];
 * [allowAllInterfaces]. When it is false, it only binds the local loopback 127.0.0.1 (connected and
 * tested via USB computer). When it is true, it binds all network cards, but only accepts connections
 * with private addresses at both ends (LAN, private network), and disconnects connections from the
 * public network on the spot. [token] recognizes connections from anywhere; [loopbackToken] only
 * recognizes connections from the local computer (the computer comes in through `adb forward` and loops
 * back).
 */
class RemoteHost(
    tasks: RemoteTasks,
    private val token: () -> String?,
    private val port: Int,
    private val allowAllInterfaces: Boolean,
    serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
    loopbackToken: () -> String? = { null },
    device: DeviceAccess? = null,
    private val endpoints: RemoteEndpoints = RemoteEndpoints(tasks, serverVersion, clock, device),
) {
    private val access = RemoteAccess(token, clock, loopbackToken = loopbackToken)
    val activity get() = endpoints.activity
    private var server: MiniHttpServer? = null

    val boundPort: Int get() = server?.port ?: -1

    fun handle(req: HttpRequest): HttpResponse = access.handle(req, endpoints::handle)

    /** Start listening; throw an exception when the port is occupied and the binding fails. */
    fun start() {
        val s = MiniHttpServer(bindAddress(allowAllInterfaces, port), ::handle, accept = { local, remote ->
            RemoteAccess.privateAddress(local) && RemoteAccess.privateAddress(remote)
        }, onError = onError)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop()
        server = null
    }

    companion object {
        const val RECENT_MS = 10 * 60_000L

        /**
         * Listening address: all network cards, or only IPv4 loopback 127.0.0.1. Loopback is programmed
         * into IPv4: `InetAddress.getLoopbackAddress()` on Android may give::1, while `adb forward` is
         * connected to 127.0.0.1.
         */
        fun bindAddress(allowAllInterfaces: Boolean, port: Int): InetSocketAddress =
            if (allowAllInterfaces) InetSocketAddress(port) else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    }
}
