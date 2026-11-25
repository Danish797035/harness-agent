package com.meenai.mina.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Only observe the default network when remote access is enabled, do not create VPNs, bind or change the networks of other modules. */
internal class RelayNetwork(context: Context, private val changed: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun start() {
        if (callback != null) return
        val observer = object : ConnectivityManager.NetworkCallback() {
            private var initialized = false
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                val different = initialized && current != network
                initialized = true; current = network
                if (different) changed()
            }
            override fun onLost(network: Network) { if (current == network) { current = null; changed() } }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(observer); callback = observer }
    }

    @Synchronized fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }
}
