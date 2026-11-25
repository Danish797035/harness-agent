package com.meenai.mina.core.remote

import com.meenai.mina.core.device.DeviceAccess
import com.meenai.mina.core.mcp.McpRpc
import com.meenai.mina.core.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Protocol entry common to authenticated connections. The callers first complete their respective
 * authentications: RemoteAccess for the LAN, and the paired HTTPS gateway for the outbound tunnel. No
 * monitoring, no network requests, no determination of source credibility; both transmissions share task
 * use cases and recent activities. Mobile phone screen capabilities (reading lock screen status, waking
 * up, and unlocking) are determined by the caller on a case-by-case basis: if not provided, these tools
 * and endpoints will not appear at all, instead of being rejected after appearing. Requests coming
 * through the gateway are not accepted by default, see docs/dev/remote-mcp.md.
 */
class RemoteEndpoints(tasks: RemoteTasks, private val serverVersion: String, private val clock: () -> Long = System::currentTimeMillis,
    device: DeviceAccess? = null) {
    private val service = RemoteTaskService(tasks)
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    /** A set of protocol entrances: task API plus MCP. The only difference between the two sets is the ability to have a mobile phone screen, task services and recent activities are shared. */
    private inner class Surface(device: DeviceAccess?) {
        val api = RemoteTaskApi(service, serverVersion, device)
        val rpc = McpRpc(McpTaskTools(service, device), serverVersion) { remote, name -> seen(remote, name) }
    }
    private val withDevice = Surface(device)
    private val tasksOnly = if (device == null) withDevice else Surface(null)

    /** When [deviceAccess] is false, the wake-up, unlock and lock screen status cannot be seen in this request. */
    fun handle(req: HttpRequest, deviceAccess: Boolean = true): HttpResponse {
        val surface = if (deviceAccess) withDevice else tasksOnly
        if (req.path == McpRpc.PATH) return surface.rpc.handle(req)
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-mina-client")?.take(64))
            return surface.api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
