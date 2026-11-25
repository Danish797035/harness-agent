package com.meenai.mina.cap.phone.setup

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.meenai.mina.core.adb.AdbConnectPort
import com.meenai.mina.core.adb.AdbConnection
import com.meenai.mina.core.adb.ConnectCandidates
import com.meenai.mina.core.adb.AdbPairing
import com.meenai.mina.core.adb.AdbServiceTypes
import com.meenai.mina.core.adb.AdbTls
import com.meenai.mina.core.adb.LocalPortScan
import com.meenai.mina.core.adb.NioPortScanner
import com.meenai.mina.core.adb.PortCache
import com.meenai.mina.core.shell.BuiltinShellEngine
import com.meenai.mina.core.shell.BuiltinShellPlatform
import com.meenai.mina.core.shell.HelperHandoff
import com.meenai.mina.core.shell.PermissionCommands
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryConsent
import com.meenai.mina.core.shell.WirelessDebuggingAutoOn
import com.meenai.mina.core.shell.ShellCommandRunner
import com.meenai.mina.cap.phone.shell.HelperHub
import com.meenai.mina.cap.phone.shell.adb.AndroidAdb
import com.meenai.mina.cap.phone.shell.adb.NsdAdbServiceFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/**
 * Real implementation of built-in assistants: pairing, assistant via native adbd, watchdog. Create a
 * copy by [com.meenai.mina.cap.phone.PhoneServices]. When the App starts, [start] starts looking at the
 * assistant; [hub] is the point where the assistant hands over the Binder; [consent] is the user's
 * choice of which capabilities (see it for "automatically turning on wireless debugging").
 */
class BuiltinShellManager(context: Context, private val hub: HelperHub, private val consent: () -> RecoveryConsent) {
    private val app = context.applicationContext
    private var started = false

    private val _scanningPorts = MutableStateFlow(false)
    /** Scanning this machine to find the wireless debugging port (the boot page displays "Searching on this machine..."). */
    val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    private val platform: AndroidPlatform by lazy {
        AndroidPlatform(app, hub, { lastConnectCandidates = it }, { _scanningPorts.value = it })
    }

    /** The last time I looked for a connection port, each source gave me several candidates (used for diagnostic information); this time, it is null as the process has not connected yet. */
    @Volatile var lastConnectCandidates: ConnectCandidates? = null
        private set

    /** Turn on wireless debugging for the user when you want to start the assistant: only the app itself writes the system settings (no other connections are available when you want to start the assistant). */
    val wirelessAutoOn: WirelessDebuggingAutoOn by lazy {
        WirelessDebuggingAutoOn(
            wants = { consent().wants(RecoveryCapability.AutoWirelessDebugging) },
            canWrite = { app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
            read = platform::wirelessDebugging,
            write = { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) },
        )
    }

    /** Pairing client + watchdog. */
    val engine: BuiltinShellEngine by lazy {
        BuiltinShellEngine(
            platform, hub.shell, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO, autoOn = wirelessAutoOn,
        )
    }

    /**
     * Recovery and authorization switch are used: the assistant is run through it (the assistant can be
     * used on the computer via USB without a paired connection); otherwise, each command is connected to
     * the paired adbd separately, and it is disconnected after running.
     */
    val commandRunner = ShellCommandRunner { command ->
        withContext(Dispatchers.IO) {
            hub.shell.value?.let { it.runCommand(command)?.getString("output").orEmpty() }
                ?: platform.connect().use { it.exec(command).readText() }
        }
    }

    /** "Start with computer" script: Rewrite it according to the assistant's presence. */
    val usbScript = UsbStartScript(app, hub)

    /** Some people want to use an assistant but don't have one: a watchdog is needed (it needs to be paired), and the script is also prepared for the computer. */
    fun need() {
        engine.need()
        usbScript.refresh()
    }

    /** The user fills in the wireless debugging port on the boot page: bring it with you the next time you connect (only use it this time, it will be recorded as cache when connected), and then try again. */
    suspend fun connectWithPort(port: Int) {
        platform.manualPort.set(port)
        engine.retry()
    }

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < MIN_SDK) return
        started = true
        usbScript.start()
        val e = engine
        // As soon as wireless debugging is turned on, try to connect back; when it is turned off, the
        // status immediately changes to "Wireless debugging is turned off"
        app.contentResolver.registerContentObserver(
            DeveloperSettings.wirelessDebuggingUri, false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = e.wirelessDebuggingChanged()
            },
        )
        // When stuck on "No Wi-Fi" or wireless debugging is turned off, try again as soon as you are
        // connected to any Wi-Fi (does not need to be able to access the Internet)
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = e.wifiAvailable()
                },
            )
        }
        e.start()
    }

    private class AndroidPlatform(
        private val app: Context,
        private val hub: HelperHub,
        private val candidates: (ConnectCandidates) -> Unit,
        private val scanning: (Boolean) -> Unit,
    ) : BuiltinShellPlatform {
        /** The port filled in by the user will be taken away from the next connection. */
        val manualPort = AtomicReference<Int?>(null)
        private val scan = LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(portRangeText()) })
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val finder = NsdAdbServiceFinder(app)
        private val portCache = object : PortCache {
            override fun load() = prefs.getInt("port", 0).takeIf { it > 0 }
            override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
        }

        override fun paired() = Build.VERSION.SDK_INT >= MIN_SDK && prefs.getBoolean("paired", false)

        override fun setPaired(paired: Boolean) {
            prefs.edit().putBoolean("paired", paired).apply()
        }

        override fun usbStarted() = prefs.getBoolean("usbStarted", false)

        override fun setUsbStarted(on: Boolean) {
            prefs.edit().putBoolean("usbStarted", on).apply()
        }

        // When this item is not written, it is read out. null: If you can’t see it clearly, don’t stop
        // it.
        override fun wirelessDebugging(): Boolean? =
            Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }

        override fun wifiConnected(): Boolean = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(true)

        override suspend fun findPairingPort(timeoutMs: Long): Int? = finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs).firstOrNull()

        override fun pair(port: Int, code: String) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(app))).pair(LOCALHOST, port, code)
        }

        override suspend fun launchHelper() {
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            // The assistant checks Mina’s signature before each handover.
            val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin(app))
            val connection = connect(tls)
            // If the stream is open, sh will wait; if the handover is reached (or times out) and then
            // closed, the handover result will still be judged by the engine from HelperHub.
            val before = hub.shell.value
            connection.use {
                val stream = it.exec(command)
                stream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
            }
        }

        suspend fun connect(tls: AdbTls = AndroidAdb.tls(AndroidAdb.identity(app))): AdbConnection =
            AdbConnectPort(
                ::tlsPort, finder, portCache, report = candidates, scan = scan, manual = { manualPort.getAndSet(null) }, scanning = scanning,
            ).connect { host, port -> AdbConnection.open(host, port, tls) }

        /** When scanning ports, only scan the machine itself: 127.0.0.1 and the IPv4 address on Wi-Fi. */
        private fun localHosts(): List<String> = listOf(LOCALHOST) + runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        }.getOrDefault(emptyList())

        /** The system allocates a range of temporary ports; some systems do not allow the app to read it, and if it cannot read it, it scans the default section. */
        private fun portRangeText(): String? = runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()

        override fun stopHelper() {
            // destroy will cause the helper process to exit on the spot, and this call will most likely
            // end with a DeadObjectException
            runCatching { hub.shell.value?.destroy() }
        }

        override fun forget() {
            hub.forget()
            AndroidAdb.forget(app)
            prefs.edit().remove("port").apply()
        }

        @SuppressLint("PrivateApi")
        private fun tlsPort(): Int? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, TLS_PORT_PROPERTY) as String?
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    companion object {
        /** The built-in assistant is enabled starting with Android 12; older systems use the compatibility path without the newer screen flags. */
        const val MIN_SDK = Build.VERSION_CODES.S
        private const val PREFS = "builtin_shell"
        /** The original Android adbd writes the wireless debugging connection port in this system property; if it cannot be read, it relies on cache, mDNS, scanning the local port and the port filled in by the user. */
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val LOCALHOST = AdbConnectPort.LOCALHOST
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val HANDOFF_WAIT_MS = 15_000L
    }
}
