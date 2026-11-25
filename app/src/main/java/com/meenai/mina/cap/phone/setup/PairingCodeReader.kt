package com.meenai.mina.cap.phone.setup

import android.content.Context
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.cap.phone.a11y.SystemPickers
import com.meenai.mina.core.shell.BuiltinShellControl
import com.meenai.mina.core.shell.PairingDiscovery
import com.meenai.mina.core.shell.PairingScreen
import com.meenai.mina.core.shell.PhoneSetupFlow
import com.meenai.mina.core.shell.PairingScreenReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * When pairing, use Mina's accessibility service to read the pairing code on the system pairing window,
 * and pair directly after reading it. The user does not need to fill in the notification. Only run
 * during the period when [PairingCoordinator] is open, and only look at the system settings window; no
 * logs will be written for the read codes. If accessibility is not turned on, nothing will be done, and
 * the notification will still be sent when paired.
 */
internal object PairingCodeReader {
    /** The content of the window is often brushed out several times, waiting for it to stabilize before reading. */
    private const val SETTLE_MS = 300L
    private const val MAX_NODES = 400

    suspend fun run(context: Context, control: BuiltinShellControl, coordinator: PairingCoordinator) {
        val settings = SystemPickers.settingsPackage(context.packageManager) ?: return
        val tried = mutableSetOf<String>()
        ScreenAccessibility.instance.collectLatest { service ->
            service ?: return@collectLatest
            service.eventsFor(Display.DEFAULT_DISPLAY)
                .filter { it.packageName?.toString() == settings }
                .map { }
                .onStart { emit(Unit) }
                .conflate()
                .collect {
                    delay(SETTLE_MS)
                    val portFound = control.pairingDiscovery.value is PairingDiscovery.Found
                    val attempt = readings(service, settings).firstNotNullOfOrNull {
                        PairingScreen.attempt(it, portFound, PhoneSetupFlow.effectiveStatus(control.status.value, coordinator.repairing.value), tried)
                    } ?: return@collect
                    tried += attempt.code
                    coordinator.pair(attempt.code, attempt.port)
                }
        }
    }

    /** Read each system setting window once: the pairing code and port must be in the same window (the page below the pairing window also has an "IP:Port"). */
    private fun readings(service: ScreenAccessibility, settings: String): List<PairingScreenReading> =
        service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != settings) return@mapNotNull null
            PairingScreen.read(texts(root))
        }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }
}
