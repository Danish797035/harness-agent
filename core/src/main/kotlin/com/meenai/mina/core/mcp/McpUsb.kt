package com.meenai.mina.core.mcp

import com.meenai.mina.core.remote.RemoteAccess
import kotlinx.serialization.json.Json
import com.meenai.mina.core.remote.RemoteTaskApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Computer connection via USB: Open the computer with adb and only monitor on the local loopback. The
 * computer is connected via `adb forward`. Here is the agreement with the computer: the content of the
 * connection information file and the script running on the computer. Only the commands available in the
 * original Android sh and toybox are used.
 */
object McpUsb {
    /** The name of the connection information file is placed in the external file directory of the App: other apps cannot read it from Android 11 onwards, and the computer can read it as a shell through USB debugging. */
    const val INFO_FILE = "mcp-usb.json"

    /** The name of the script file running on the computer is put together with the "Start with Computer" script. */
    const val SCRIPT = "connect-computer.sh"

    private val pretty = Json { prettyPrint = true }

    /**
     * Connection information: The address and request header to be filled in by the MCP client on the
     * computer, plus the port forwarding to be run first. [boot] is the number of this boot (the same as
     * `settings get global boot_count` read in the shell). Based on this, the script recognizes that it
     * is not an old file left behind by the last boot.
     */
    fun info(port: Int, token: String, boot: String): String = pretty.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("api_version", 1)
            put("api_url", "http://127.0.0.1:$port${RemoteTaskApi.ROOT}")
            put("transport", "streamable-http")
            put("url", "http://127.0.0.1:$port${McpRpc.PATH}")
            putJsonObject("headers") { put("Authorization", "Bearer $token") }
            put("token", token)
            put("port", port)
            put("forward", forward(port))
            put("boot", boot)
        },
    ) + "\n"

    /** The command on the computer to transfer the local port to the loopback port of the mobile phone; it must be run again after unplugging and plugging in the data cable. */
    fun forward(port: Int) = "adb forward tcp:$port tcp:$port"

    /**
     * The script run by the computer through USB debugging (`adb shell sh <scriptPath>`, add `off` at
     * the end when shutting down). Without any password or token, you can leave it there forever. Open:
     * First run the "start with computer" script [helperScript], then start Mina's front-end service
     * [component] and action [onAction] as a shell. Wait until the connection information file
     * [infoPath] for this startup is written and type it out. Turn off: start the same service, action
     * [offAction], etc. The connection information file is deleted (the token is invalidated
     * accordingly). It will not run when you are not a shell: the service only accepts callers with
     * shell-level permissions, and ordinary apps cannot start it.
     */
    fun script(scriptPath: String, helperScript: String, infoPath: String, component: String, onAction: String, offAction: String, port: Int): String {
        val again = "adb shell sh $scriptPath"
        return """
            |#!/system/bin/sh
            |# Written by Mina. Lets AI tools on a computer use Mina over USB: $again   (turn off: $again off)
            |if [ "${'$'}(id -u)" != "2000" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |info=${quote(infoPath)}
            |if [ "${'$'}1" = "off" ]; then
            |  out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(offAction)} 2>&1)
            |  case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; exit 1 ;; esac
            |  i=0
            |  while [ -f "${'$'}info" ]; do
            |    i=${'$'}((i + 1))
            |    if [ ${'$'}i -gt 10 ]; then echo "Mina did not confirm. Open Mina > Settings > Computer access (MCP) and turn off USB there."; exit 1; fi
            |    sleep 1
            |  done
            |  echo "Computer access over USB is off. The old token no longer works."
            |  exit 0
            |fi
            |if [ -f ${quote(helperScript)} ]; then
            |  sh ${quote(helperScript)} || echo "Phone control is not ready yet; tasks that operate apps wait until it is."
            |fi
            |boot=${'$'}(settings get global boot_count 2>/dev/null)
            |out=${'$'}(am start-foreground-service -n ${quote(component)} -a ${quote(onAction)} 2>&1)
            |case "${'$'}out" in *rror*|*xception*) echo "${'$'}out"; echo "Is Mina installed and up to date?"; exit 1 ;; esac
            |i=0
            |until grep -q "\"boot\": *\"${'$'}boot\"" "${'$'}info" 2>/dev/null; do
            |  i=${'$'}((i + 1))
            |  if [ ${'$'}i -gt 15 ]; then echo "Mina did not open the connection. Open Mina > Settings > Computer access (MCP) to see why, then run this again."; exit 1; fi
            |  sleep 1
            |done
            |echo "Computer access over USB is on. On the computer, run: ${forward(port)}"
            |echo "Then add this MCP server (Streamable HTTP) to your AI tool:"
            |cat "${'$'}info"
            |""".trimMargin()
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
