package com.meenai.mina.core.shell

/**
 * The local abstract socket of the WebView debugging interface: one for each process with debugging
 * enabled, named `webview_devtools_remote_<pid>`. The built-in assistant only recognizes names like this
 * when connecting, and the pid must belong to Mina's uid.
 */
object DevtoolsSocket {
    private const val PREFIX = "webview_devtools_remote_"
    private val NAME = Regex("""webview_devtools_remote_([1-9][0-9]{0,9})""")

    fun name(pid: Int): String = "$PREFIX$pid"

    /** The pid in the name is not like this (additional suffixes, leading 0s, out of range) and returns null. */
    fun pidOf(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** The real uid in `/proc/<pid>/status` (the first column of the Uid row); if there is no such row, null is returned. */
    fun procUid(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("Uid:") }
            ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
}
