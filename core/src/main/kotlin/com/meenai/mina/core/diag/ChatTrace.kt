package com.meenai.mina.core.diag

/**
 * Process-wide trace of model calls.
 *
 * A chat failure is impossible to diagnose from a screenshot: the useful part is
 * the provider's HTTP status and body, which never reaches the UI. The app installs
 * a [sink] that appends these lines to a text file, so a single failed attempt can
 * be read as data. Recording is cheap and never throws.
 */
object ChatTrace {

    /** Installed once by the app; null in tests and in the helper process. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun record(line: String) {
        val write = sink ?: return
        runCatching { write(line) }
    }

    /** `Class: message`, the same shape the switch notice shows. */
    fun describe(e: Throwable): String {
        val message = e.message?.trim().orEmpty().ifBlank { e::class.simpleName.orEmpty() }
        return e::class.simpleName.orEmpty() + ": " + message.replace(Regex("\\s+"), " ").take(300)
    }
}
