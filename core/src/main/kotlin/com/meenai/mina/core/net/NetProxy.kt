package com.meenai.mina.core.net

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.net.Authenticator as JavaAuthenticator
import okhttp3.Authenticator as OkAuthenticator

/** The mistakes made when using an online agency are separate from the mistakes made by the service provider. */
enum class ProxyFailure {
    /** Unable to connect to the proxy, or the proxy did not forward the request. */
    Unreachable,
    /** The agent asked for an account and password, but the one given was wrong (or not given). */
    AuthFailed,
}

/**
 * The network proxy used by Mina for its own network connection (model adjustment, column model, account
 * login and token exchange, downloading updates, built-in browser download). [source] gives the proxy
 * address currently stored (the empty string is not used); cache the parsing results according to the
 * original text, and adjust [changed] after changing the settings.
 *
 * - - The OkHttp client built by [client] asks [selector] for each request: the local machine and the
 * LAN address ([LocalHosts.bypassesProxy]) are directly connected, and others go through the proxy;
 * Direct connection when there is no proxy or the proxy is not applicable ([ProxyAddress.forApp] is
 * false). Streaming replies are still read piece by piece without buffering.
 * - - The account and password of the HTTP proxy must be `Proxy-Authorization` when requested by the
 * proxy (and before establishing a tunnel); if the same request is rejected, it will not be retried.
 * - - The account password of the SOCKS agent can only be given to the one installed by
 * [installSocksAuth] through [java.net.Authenticator] in the whole process.
 * Only answer queries sent to the current SOCKS proxy (host and port are correct), and nothing else.
 */
class NetProxy(private val source: () -> String) {
    private class Parsed(val raw: String, val address: ProxyAddress?)

    @Volatile private var parsed: Parsed? = null
    private val pools = CopyOnWriteArrayList<WeakReference<okhttp3.ConnectionPool>>()

    /** The proxy that Mina's own connection is going to go through at this moment; it is null if it is not set, written incorrectly or is not applicable. */
    fun current(): ProxyAddress? {
        val raw = runCatching(source).getOrDefault("")
        parsed?.takeIf { it.raw == raw }?.let { return it.address }
        val address = ProxyAddress.parse(raw)?.takeIf { it.forApp }
        parsed = Parsed(raw, address)
        return address
    }

    /** The proxy to connect to this host; the local machine and LAN address are directly connected (null). */
    fun proxyFor(host: String): ProxyAddress? = current()?.takeUnless { LocalHosts.bypassesProxy(host) }

    val selector: ProxySelector = Selector()

    val authenticator: OkAuthenticator = OkAuthenticator { route, response -> authenticate(route, response) }

    private fun authenticate(route: Route?, response: Response): Request? {
        // After providing the account and password, it was still rejected: No more attempts, and the
        // caller reported "The agent's account or password is incorrect."
        if (response.request.header(PROXY_AUTHORIZATION) != null) return null
        val proxy = current() ?: return null
        if (proxy.socks || !proxy.hasCredentials) return null
        if (route != null && route.proxy.type() != Proxy.Type.HTTP) return null
        return response.request.newBuilder().header(PROXY_AUTHORIZATION, Credentials.basic(proxy.user, proxy.password)).build()
    }

    /** Press [builder] to build a client with the proxy settings here; its connection pool will clear idle connections when the settings are changed. */
    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder.proxySelector(selector).proxyAuthenticator(authenticator).build().also { pools += WeakReference(it.connectionPool) }

    /** The settings have been changed: re-parse, all idle connections are disconnected, and the next request is connected according to the new settings (ongoing requests are still completed). */
    fun changed() {
        parsed = null
        pools.removeAll { it.get() == null }
        pools.forEach { it.get()?.evictAll() }
    }

    /** SOCKS proxy account password: Only answer inquiries sent to the current SOCKS proxy. */
    val socksAuthenticator: JavaAuthenticator = object : JavaAuthenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            socksCredentials(requestingProtocol, requestingHost, requestingPort)
    }

    internal fun socksCredentials(protocol: String?, host: String?, port: Int): PasswordAuthentication? {
        val proxy = current() ?: return null
        if (!proxy.socks || !proxy.hasCredentials) return null
        if (protocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
        if (host == null || !host.removeSurrounding("[", "]").equals(proxy.host, ignoreCase = true) || port != proxy.port) return null
        return PasswordAuthentication(proxy.user, proxy.password.toCharArray())
    }

    /** Install [socksAuthenticator](one for the whole process); adjust it once when the App starts. */
    fun installSocksAuth() = JavaAuthenticator.setDefault(socksAuthenticator)

    private inner class Selector : ProxySelector() {
        val owner: NetProxy get() = this@NetProxy

        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyFor(host)?.javaProxy() ?: Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    companion object {
        private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

        /** The one in the product: When the App starts, connect [sharedSource] to the saved settings. */
        @Volatile var sharedSource: () -> String = { "" }
        val shared: NetProxy = NetProxy { sharedSource() }

        /** The copy corresponding to the client built by [client]; other clients are null. */
        fun of(http: OkHttpClient): NetProxy? = (http.proxySelector as? Selector)?.owner

        /** Is this failure a problem with the agent: [http] left the agent when connecting to [url], and the error was in the part of connecting to the agent and passing the agent. */
        fun failure(http: OkHttpClient, url: HttpUrl, e: IOException): ProxyFailure? {
            of(http)?.proxyFor(url.host) ?: return null
            return classify(e)
        }

        /**
         * Categories of network errors when using a proxy: The proxy rejected the account and password,
         * which is counted as [ProxyFailure.AuthFailed]; the proxy cannot be connected (rejected
         * connection, host cannot be found, connection timed out), the proxy failed to establish a
         * tunnel, and the SOCKS handshake was incorrectly calculated as [ProxyFailure.Unreachable];
         * problems such as interruption during reading reply are not counted as proxy (null).
         */
        fun classify(e: IOException): ProxyFailure? {
            val chain = generateSequence(e as Throwable) { it.cause }.take(6).toList()
            val messages = chain.map { it.message.orEmpty() }
            if (messages.any { it.contains("authenticate with proxy", true) || (it.contains("SOCKS", true) && it.contains("auth", true)) }) {
                return ProxyFailure.AuthFailed
            }
            if (messages.any { it.contains("for CONNECT", true) || it.contains("SOCKS", true) }) return ProxyFailure.Unreachable
            val connecting = chain.any {
                it is ConnectException || it is NoRouteToHostException || it is UnknownHostException ||
                    (it is SocketTimeoutException && it.message.orEmpty().contains("connect", true))
            }
            return if (connecting) ProxyFailure.Unreachable else null
        }
    }
}
