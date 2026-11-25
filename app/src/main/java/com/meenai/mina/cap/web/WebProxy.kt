package com.meenai.mina.cap.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.meenai.mina.core.net.LocalHosts
import com.meenai.mina.core.net.NetProxy
import com.meenai.mina.core.net.ProxyAddress

/**
 * The network proxy of the built-in browser (all WebViews in this process) takes the same address as
 * Settings › Network Proxy. Android's WebView can only set a separate proxy for the App if it supports
 * "proxy overlay" (androidx.webkit's [ProxyController]); if it does not support it, or if the proxy is
 * SOCKS with an account and password (the browser kernel does not support SOCKS authentication), the
 * browser will connect directly and it will be stated on the settings page. The local machine and LAN
 * addresses are still directly connected; when the HTTP proxy asks for an account and password,
 * [credentialsFor] answers (see `WebTab`).
 */
object WebProxy {
    /** What's happening on the browser side at the moment. */
    enum class Mode {
        /** No proxy is set up (or the proxy is only used by the container): direct connection. */
        Off,
        /** The browser uses a proxy. */
        Applied,
        /** The WebView of this mobile phone does not support separate proxy: direct connection. */
        Unsupported,
        /** The SOCKS proxy browser with account and password cannot be used: direct connection. */
        SocksWithCredentials,
    }

    private const val TAG = "MinaWebProxy"
    @Volatile private var overridden = false

    /** Determine how the browser connects according to the current settings (pure function, easy for testing). */
    fun mode(address: ProxyAddress?, supported: Boolean): Mode = when {
        address == null -> Mode.Off
        !supported -> Mode.Unsupported
        address.socks && address.hasCredentials -> Mode.SocksWithCredentials
        else -> Mode.Applied
    }

    /** The proxy rule writing method recognized by the browser kernel. SOCKS Some kernels only recognize `socks://`, try `socks5://` first. */
    fun rules(address: ProxyAddress): List<String> {
        val host = if (address.host.contains(':')) "[${address.host}]" else address.host
        return if (address.socks) listOf("socks5://$host:${address.port}", "socks://$host:${address.port}")
        else listOf("http://$host:${address.port}")
    }

    /** WebView does not support setting up a separate proxy; the query itself will load the WebView and put it in the background thread. */
    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** The situation on the browser side at this moment (WebView will be loaded and placed in the background thread for adjustment). */
    fun mode(): Mode = NetProxy.shared.current().let { mode(it, it != null && supported()) }

    /**
     * Set up (or remove) the proxy for the WebView of this process according to the current settings. Do
     * nothing (no WebView is loaded for this) if neither proxy nor proxy is set. Do it on the main
     * thread; can be called from any thread.
     */
    fun apply(context: Context) {
        val address = NetProxy.shared.current()
        if (address == null && !overridden) return
        Handler(Looper.getMainLooper()).post {
            runCatching { applyNow(address) }.onFailure { Log.w(TAG, "proxy override failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun applyNow(address: ProxyAddress?) {
        if (!supported()) return
        val controller = ProxyController.getInstance()
        if (mode(address, true) != Mode.Applied || address == null) {
            if (overridden) controller.clearProxyOverride({ it.run() }, {})
            overridden = false
            return
        }
        for (rule in rules(address)) {
            val config = ProxyConfig.Builder().addProxyRule(rule).bypassSimpleHostnames().apply {
                LocalHosts.BYPASS_RULES.forEach { addBypassRule(it) }
            }.build()
            val ok = runCatching { controller.setProxyOverride(config, { it.run() }, {}) }.isSuccess
            if (ok) {
                overridden = true
                return
            }
        }
    }

    /** When the browser asks for the account and password: the current HTTP proxy will answer the question, and other hosts (the website's own login box) will not answer. */
    fun credentialsFor(host: String?): Pair<String, String>? {
        val p = NetProxy.shared.current() ?: return null
        if (p.socks || !p.hasCredentials || host == null) return null
        if (!host.removeSurrounding("[", "]").equals(p.host, ignoreCase = true)) return null
        return p.user to p.password
    }
}
