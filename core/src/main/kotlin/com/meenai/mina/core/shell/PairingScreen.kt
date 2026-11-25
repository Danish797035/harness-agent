package com.meenai.mina.core.shell

/** The pairing code and port read from the system pairing window; null if not read or ambiguous. */
data class PairingScreenReading(val code: String?, val port: Int?)

/** Use the read code to pair; when [port] is empty, use the found pairing port. */
data class PairingAttempt(val code: String, val port: Int?)

/**
 * Recognize the pairing code and "IP:port" from all text in a window. Just look at the text, not the
 * control id, interface language or some layout: the pairing code is exactly 6 digits in the entire text
 * (there can be spaces in the middle); the port is the number after the colon in "IPv4:port" or
 * "[IPv6]:port". If two different codes or ports appear in the same window, just treat it as if it has
 * not been read, and would rather return it to the user to fill it in manually.
 */
object PairingScreen {
    private val CODE = Regex("""\d{6}""")
    private val IPV4_PORT = Regex("""(?<![\w.:])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})(?![\w.:])""")
    private val IPV6_PORT = Regex("""\[[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[\w.]+)?]:(\d{1,5})(?![\w.:])""")

    fun read(texts: List<String>): PairingScreenReading {
        val codes = texts.mapNotNull { t -> t.filterNot { it.isWhitespace() }.takeIf { CODE.matches(it) } }.toSet()
        val ports = texts.flatMap(::ports).toSet()
        return PairingScreenReading(codes.singleOrNull(), ports.singleOrNull())
    }

    private fun ports(text: String): List<Int> {
        val v4 = IPV4_PORT.findAll(text)
            .filter { m -> (1..4).all { m.groupValues[it].toInt() <= 255 } }
            .map { it.groupValues[5] }
        val v6 = IPV6_PORT.findAll(text).map { it.groupValues[1] }
        return (v4 + v6).mapNotNull { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } }.toList()
    }

    /**
     * Should I use the read code to pair it: there is a code, there is a port (read on the screen, or
     * the pairing window has been found), this code has not been tried, and the assistant is waiting for
     * pairing (not pairing, not ready). If the code is incorrect, the same code will not be tried again,
     * and a new code will be replaced in the pairing window.
     */
    fun attempt(reading: PairingScreenReading, portFound: Boolean, status: BuiltinShellStatus, tried: Set<String>): PairingAttempt? {
        val code = reading.code ?: return null
        if (code in tried) return null
        if (reading.port == null && !portFound) return null
        val waiting = when (status) {
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> true
            is BuiltinShellStatus.Failed -> status.reason.duringPairing || status.fix == BuiltinShellFix.RePair
            BuiltinShellStatus.Connecting, BuiltinShellStatus.Running -> false
        }
        return if (waiting) PairingAttempt(code, reading.port) else null
    }
}
