package com.meenai.mina.browser

/**
 * Where the web view of the browser page is hung. There is only one web page tab (placed in the
 * process), but there may be several browser pages at the same time: when there is more than one
 * instance of the App interface (from notifications, desktop, another app is opened, covering the
 * original one), each instance may stop on the browser page, leaving a container for the web page. A
 * view can only be hung in one container at the same time. If it is hung in the wrong container (hanging
 * it in the covered one), the user will see nothing.
 *
 * Rule: attach to the host with a container that most recently returned to the foreground ([started]),
 * preferring the newer one on ties. When that host leaves, switch to the most recent foreground host
 * among the rest. [close] tells the caller whether the leaving host is the last one: only close the
 * web page when the last host leaves (and not on configuration change); leaving the browser page
 * elsewhere must not close a page someone else is still viewing.
 *
 * [C] is the container that holds the view, and [A] is the interface where this is located (the view
 * environment is changed to it when it is hung up). Only called on the main thread.
 */
class PageHosts<C : Any, A : Any> {
    /** A browser page. */
    class Host<C : Any, A : Any>(val activity: A) {
        /** This is the container reserved for the web page at this moment; it is null when there is no open web page. */
        var container: C? = null
            internal set
        internal var startedAt = 0L
    }

    private val hosts = mutableListOf<Host<C, A>>()
    private var clock = 0L

    /** This is the browser page. */
    fun open(host: Host<C, A>) {
        if (host !in hosts) hosts += host
    }

    /** This point has left the browser page (or the interface is gone); returning true means that there is no other browser page. */
    fun close(host: Host<C, A>): Boolean {
        hosts.remove(host)
        host.container = null
        return hosts.isEmpty()
    }

    /** This is back to the front desk (visible). */
    fun started(host: Host<C, A>) {
        host.startedAt = ++clock
    }

    /** The container here is present or missing. */
    fun setContainer(host: Host<C, A>, container: C?) {
        host.container = container
    }

    /** The place where the view should hang: the one that has recently returned to the foreground among the several places on the browser page where there are containers; if not, it is null. */
    fun target(): Host<C, A>? =
        hosts.withIndex().filter { it.value.container != null }
            .maxWithOrNull(compareBy<IndexedValue<Host<C, A>>>({ it.value.startedAt }, { it.index }))?.value
}
