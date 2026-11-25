package com.meenai.mina.mcp

import com.meenai.mina.core.mcp.McpRpc
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meenai.mina.BuildConfig
import com.meenai.mina.core.remote.RemoteAccess
import com.meenai.mina.core.remote.RemoteActivity
import com.meenai.mina.core.remote.RemoteHost
import com.meenai.mina.core.remote.RemoteEndpoints
import com.meenai.mina.core.remote.RelayClient
import com.meenai.mina.core.remote.RelayGateway
import com.meenai.mina.settings.RelaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.meenai.mina.core.mcp.McpUsb
import com.meenai.mina.core.shell.HelperHandoff
import com.meenai.mina.device.AndroidDeviceAccess
import com.meenai.mina.core.remote.RemoteTasks
import com.meenai.mina.settings.McpSettings
import com.meenai.mina.settings.McpUsbSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** The computer connection looks like at the moment: it is closed, which port it is listening on, or it cannot be opened (the port is occupied, etc.). */
sealed interface McpServerState {
    data object Off : McpServerState
    data class Listening(val port: Int) : McpServerState
    data class Failed(val message: String) : McpServerState
}

/**
 * Computer Connection (MCP) switches and services: one copy of the entire process. See [McpModes] for
 * the monitoring range of LAN ([settings]) and USB ([usb]), and the remote gateway ([relaySettings])
 * uses independent outbound channels. There is a [McpService] front-end service that starts when it is
 * turned on. Local monitoring and outbound connections are determined by their respective switches; it
 * stops when they are turned off. Tasks in the three modes share [tasks] and the same protocol entry.
 */
class McpRuntime(
    private val context: Context, val settings: McpSettings, val usb: McpUsbSettings, private val tasks: RemoteTasks,
    val device: AndroidDeviceAccess,
    val relaySettings: RelaySettings,
) {
    private val _state = MutableStateFlow<McpServerState>(McpServerState.Off)
    val state: StateFlow<McpServerState> = _state.asStateFlow()
    @Volatile private var host: RemoteHost? = null
    private var hostListen = McpModes.Listen.Off
    private val endpoints = RemoteEndpoints(tasks, BuildConfig.VERSION_NAME, device = device.access)
    private val relayAccess = RemoteAccess(token = { if (relaySettings.config.value.enabled) settings.token() else null })
    // For requests coming through the gateway, you need to turn on "Allow wake-up and unlocking through
    // the gateway" to see the screen capabilities; the remote screen brightening and unlocking
    // permissions on the phone still need to be turned on.
    private val relay = RelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), { req ->
        relayAccess.handle(req) { endpoints.handle(it, deviceAccess = relaySettings.config.value.deviceAccess) }
    })
    private val relayNetwork = RelayNetwork(context, relay::networkChanged)
    val relayState get() = relay.state
    private val active get() = listen() != McpModes.Listen.Off || relaySettings.config.value.enabled

    /** The most recently connected client and the last request; empty when not listening. */
    val activity: RemoteActivity get() = if (active) endpoints.activity.value else RemoteActivity()

    /** Pairing only saves credentials and the user must enable remote access separately. */
    suspend fun pairRelay(gateway: String, code: String) {
        check(!relaySettings.config.value.paired) { "Unpair the previous gateway first" }
        val remote = RelayGateway(RelayGateway.parse(gateway), RelayGateway.http())
        val paired = remote.pair(code)
        try { relaySettings.save(paired) }
        catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { remote.unpair(paired.deviceToken) } }
            throw e
        }
    }

    fun setRelayDeviceAccess(on: Boolean) = relaySettings.setDeviceAccess(on)

    fun setRelayEnabled(on: Boolean) {
        if (!on) { relayNetwork.stop(); relay.stop() }
        try { relaySettings.setEnabled(on) } finally { if (!on) apply() }
        if (on) apply()
    }

    suspend fun unpairRelay() {
        setRelayEnabled(false)
        relaySettings.pairing()?.let { RelayGateway(RelayGateway.parse(it.gateway), RelayGateway.http()).unpair(it.deviceToken) }
        relaySettings.clear()
    }

    fun listen(): McpModes.Listen = McpModes.listen(settings.enabled.value, usb.enabled.value)

    /** The LAN switch on the settings page. */
    fun setEnabled(on: Boolean) {
        settings.setEnabled(on)
        apply()
    }

    /** The USB switch on the settings page (the computer uses the adb switch to perform [McpService] actions, the same set). */
    fun setUsbEnabled(on: Boolean) {
        if (on) usb.enable() else usb.disable()
        apply()
    }

    /** The switch has changed: There is also a method that allows the service to re-monitor by pressing the new switch when it is turned on, and stops the service when it is turned off. */
    private fun apply() {
        if (active) ensureRunning() else {
            context.stopService(Intent(context, McpService::class.java))
            _state.value = McpServerState.Off
        }
    }

    /**
     * There is one method that starts the service when it is turned on (if it is already running, just
     * press the switch to readjust it). The foreground service may be rejected by the system from the
     * background. In this case, it will be recorded as not started and will be restarted next time when
     * the Mina interface is opened.
     */
    fun ensureRunning() {
        if (!active) return
        runCatching { context.startForegroundService(Intent(context, McpService::class.java)) }
            .onFailure { e ->
                Log.w(TAG, "could not start computer access service", e)
                _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
    }

    /**
     * Tuned by [McpService]: Synchronize local monitoring and remote channels. The service is reused
     * when the listening range does not change, and connection information is published when USB is
     * turned on. Returns false if neither the local listening nor the remote channel is enabled or
     * failed to start.
     */
    @Synchronized internal fun sync(): Boolean {
        val want = listen()
        if (host != null && hostListen != want) stopListener()
        if (want == McpModes.Listen.Off) _state.value = McpServerState.Off
        val ok = want != McpModes.Listen.Off && (host != null || startServer(want))
        if (ok && usb.enabled.value) runCatching { usb.publish(host!!.boundPort) }.onFailure { Log.w(TAG, "cannot write USB connection info", it) }
        else usb.unpublish()
        val remoteOn = relaySettings.config.value.enabled
        if (remoteOn) {
            val pairing = relaySettings.pairing()
            if (pairing != null) { relayNetwork.start(); relay.start(pairing) }
            else { relayNetwork.stop(); relay.stop(); relaySettings.setEnabled(false) }
        } else { relayNetwork.stop(); relay.stop() }
        return ok || remoteOn && relaySettings.config.value.enabled
    }

    private fun startServer(want: McpModes.Listen): Boolean {
        val network = want == McpModes.Listen.PrivateNetworks
        val h = RemoteHost(tasks, { if (settings.enabled.value) settings.token() else null }, settings.port, allowAllInterfaces = network,
            serverVersion = BuildConfig.VERSION_NAME, onError = { Log.w(TAG, "computer access server error", it) },
            loopbackToken = usb::token, device = device.access, endpoints = endpoints)
        return try {
            h.start()
            host = h
            hostListen = want
            _state.value = McpServerState.Listening(h.boundPort)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not listen on port ${settings.port}", e)
            _state.value = McpServerState.Failed(e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    @Synchronized internal fun stopServer() {
        relayNetwork.stop()
        relay.stop()
        stopListener()
    }

    private fun stopListener() {
        host?.stop()
        host = null
        hostListen = McpModes.Listen.Off
        if (_state.value is McpServerState.Listening) _state.value = McpServerState.Off
    }

    /** "Connect via USB" script to run on the computer: no secret, written once when the App starts (skip if external storage is not available). */
    fun writeUsbScript() {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, McpUsb.SCRIPT)
        val text = McpUsb.script(
            scriptPath = shortPath(f.absolutePath),
            helperScript = File(dir, HelperHandoff.USB_SCRIPT).absolutePath,
            infoPath = File(dir, McpUsb.INFO_FILE).absolutePath,
            component = "${context.packageName}/${McpService::class.java.name}",
            onAction = McpModes.ACTION_USB_ON, offAction = McpModes.ACTION_USB_OFF, port = settings.port,
        )
        if (runCatching { f.readText() }.getOrNull() == text) return
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** The command to be run on the computer (it is what is displayed on the settings page). */
    fun usbCommand(): String {
        val path = context.getExternalFilesDir(null)?.let { File(it, McpUsb.SCRIPT).absolutePath }
            ?: "/storage/emulated/0/Android/data/${context.packageName}/files/${McpUsb.SCRIPT}"
        return "adb shell sh " + shortPath(path)
    }

    /** Change the token: the old one becomes invalid on the spot. */
    fun regenerateToken(): String = settings.regenerate()

    companion object {
        private const val TAG = "MinaMcp"

        /** The master user's external storage is also called /sdcard in the shell; the commands shown to humans are shorthand (the same as the "launch from computer" script). */
        private fun shortPath(path: String) = HelperHandoff.usbCommand(path).removePrefix("adb shell sh ")

        /** Addresses that the computer can connect to: LAN and private network IPv4 addresses on the enabled network card. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && RemoteAccess.privateAddress(it) }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        fun url(address: String, port: Int) = "http://$address:$port${McpRpc.PATH}"

        /** Configuration fragment for the MCP client on the computer: address plus Authorization header. */
        fun clientConfig(url: String, token: String): String = """
            {
              "mcpServers": {
                "mina": {
                  "type": "http",
                  "url": "$url",
                  "headers": { "Authorization": "Bearer $token" }
                }
              }
            }
        """.trimIndent()
    }
}
