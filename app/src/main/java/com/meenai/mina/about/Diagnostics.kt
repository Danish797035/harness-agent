package com.meenai.mina.about

import com.meenai.mina.core.adb.ConnectCandidates
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.PairingDiscovery
import com.meenai.mina.core.shell.PhoneSetupFlow
import com.meenai.mina.core.shell.A11yLossRecord
import com.meenai.mina.core.shell.HelperRestart
import com.meenai.mina.core.shell.HelperRestoreReport
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryConsent
import com.meenai.mina.core.shell.SetupInputs
import com.meenai.mina.core.shell.SetupStep
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.ChatGptSignInFailureRecord
import java.net.URI

/**
 * The status read at the moment of "copying diagnostic information". Only include what is needed for
 * troubleshooting: version, model, status of each step of mobile phone operation, model interface type
 * and address, and the results of recent runs; do not include Key, login, chat content, task target and
 * screen content, nor IP, port and Wi-Fi name. Several items on the boot page are directly the input of
 * the boot page itself, [setup] (same state machine calculation step), and are not counted again.
 */
data class DiagnosticsSnapshot(
    val generatedAtMs: Long,
    val app: App,
    val device: Device,
    val setup: SetupInputs,
    val phone: Phone,
    val recovery: Recovery,
    val models: Models,
    val runs: List<Run>,
    val update: Update,
    /** Speech to text: What is selected, the situation is recognized by the mobile phone; if the old call center does not give it, I will not write this paragraph. */
    val speech: Speech? = null,
) {
    data class App(val versionName: String, val build: String, val installer: String?, val language: String)

    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val abis: List<String>,
        /** The status of the sandbox (running environment): Write the reason when it is not supported. */
        val sandbox: String,
    )

    data class Phone(
        val accessibility: AccessibilityState,
        /** The "Wireless Debugging" item in the system settings; this item cannot be read as null. */
        val wirelessDebuggingSetting: Boolean?,
        val connect: ConnectCandidates?,
        /** There are existing pairing code notification channels in the system (id to the most important level); none of them are empty. Whether it is available is subject to [SetupInputs.pairingChannel]. */
        val pairingChannels: Map<String, Int>,
        val attentionChannelImportance: Int?,
        val wifiConnected: Boolean?,
        /** Original text of the most recent pairing or connection failure (desensitized when rendered). */
        val lastError: String?,
        /**
         * App in another user (available, work data): The built-in assistant determines each user's copy
         * (number, tag, type, data group, excluding name) and how many other copies can be opened; null
         * if the assistant is not connected or cannot be asked.
         */
        val appCopies: String? = null,
        /** The status of the paired key in this process (unused, read back, new, unreadable and reasons); if not read, it is null. */
        val adbKey: String? = null,
        /** At the end of the most recent task in this process, a few apps on Mina's screen were left or closed (only the number, not the package name); the remaining ones are null. */
        val appsAfterTask: String? = null,
    )

    data class Recovery(
        val consent: RecoveryConsent,
        val writeSecureSettings: Boolean,
        /** Is Mina unrestricted in system battery optimization? If not read, it is null. */
        val ignoringBatteryOptimizations: Boolean?,
        val lastRestore: String?,
        /** Record of the built-in assistant pulling Mina up after it was killed (the assistant handover timer comes); not null. */
        val helperRestores: HelperRestoreReport? = null,
        /** The last time Mina was started, it was found that the accessibility service was removed or not bound; if not, it is null. */
        val lastA11yLoss: A11yLossRecord? = null,
    )

    data class Models(
        val providers: List<Provider>,
        val main: RoleModel?,
        val vision: RoleModel?,
        val light: RoleModel?,
        val chatGptSignedIn: Boolean,
        /** The most recent login using ChatGPT failed since this run (indicating that it has been desensitized); if not, it is null. */
        val chatGptLastFailure: ChatGptSignInFailureRecord? = null,
        /** Account login (xAI) The latest login failed; if not, it is null. */
        val accountLastFailure: ChatGptSignInFailureRecord? = null,
    )

    /** A service provider: interface type, address (only the host name is taken), several models listed (null if not listed). */
    data class Provider(val kind: String, val baseUrl: String, val listedModels: Int?)

    data class RoleModel(val model: String, val baseUrl: String)

    /** A run: start time, ending, failure category (not the original words), steps taken, model used. */
    data class Run(val startedAtMs: Long, val outcome: String, val failKind: String?, val judged: String?, val steps: Int, val model: String?)

    data class Update(val status: String, val latest: String?, val available: String?)

    /**
     * [choice] is the user's choice (auto, system, off, or service provider model "model @ address");
     * [systemAvailable] and [onDevice] are the capabilities of the system, [silent] is recorded as not
     * responding; [remembered] is the identification service that passed the roll call test; [services]
     * is the identification service registered on the mobile phone, one line for each.
     */
    data class Speech(
        val choice: String,
        val systemAvailable: Boolean,
        val onDevice: Boolean,
        val silent: Boolean,
        val remembered: String?,
        val services: List<String>,
    )
}

/** Write [DiagnosticsSnapshot] as a piece of plain text: the segment order is fixed, and the English key is used. It can be easily read when sent to WeChat or GitHub. Finally, go through the entire section again for desensitization. */
object Diagnostics {
    const val HEADER = "Harness Agent diagnostics"

    fun render(s: DiagnosticsSnapshot): String = sanitize(buildString {
        appendLine(HEADER)
        appendLine("generated: ${java.time.Instant.ofEpochMilli(s.generatedAtMs).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)}")

        section("App")
        line("version", "${s.app.versionName} (build ${s.app.build})")
        line("installer", s.app.installer ?: "unknown")
        line("language", s.app.language)

        section("Device")
        val d = s.device
        line("model", if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}")
        line("android", "${d.androidRelease} (SDK ${d.sdk})")
        line("abi", d.abis.joinToString(", ").ifEmpty { "unknown" })
        line("arm64 sandbox", if ("arm64-v8a" in d.abis) "supported (${d.sandbox})" else "not supported (${d.sandbox})")

        section("Phone control")
        phone(s)

        section("Recovery")
        val r = s.recovery
        for (c in RecoveryCapability.entries) line(capability(c), consent(r.consent, c))
        line("write secure settings", if (r.writeSecureSettings) "granted" else "not granted")
        line("battery optimization", when (r.ignoringBatteryOptimizations) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" })
        line("last reboot restore", r.lastRestore ?: "none")
        line("restored by helper", r.helperRestores?.let { h ->
            (if (h.lastRestoredAtMs > 0) "${ago(s.generatedAtMs - h.lastRestoredAtMs)}, ${h.recent} recently" else "never") +
                (if (h.gaveUpAtMs > 0) ", backed off ${ago(s.generatedAtMs - h.gaveUpAtMs)}" else "")
        } ?: "never")
        line("accessibility on start", r.lastA11yLoss?.let { "${it.loss.name.lowercase()} ${ago(s.generatedAtMs - it.atMs)}" } ?: "ok")

        section("Models")
        val m = s.models
        line("providers", if (m.providers.isEmpty()) "none" else "${m.providers.size}")
        m.providers.forEach { p -> appendLine("- ${p.kind} ${host(p.baseUrl)}" + (p.listedModels?.let { " ($it models listed)" } ?: "")) }
        line("main", m.main?.let(::role) ?: "not set")
        line("vision", m.vision?.let(::role) ?: "off")
        line("light", m.light?.let(::role) ?: "off")
        line("ChatGPT sign-in", yes(m.chatGptSignedIn))
        m.chatGptLastFailure?.let { f ->
            line("ChatGPT last sign-in failure", "${ago(s.generatedAtMs - f.atMs)}: ${f.kind}" +
                (f.code?.let { " (code $it)" } ?: "") + f.detail.takeIf { it.isNotBlank() }?.let { " — ${it.take(MAX_ERROR)}" }.orEmpty())
        }
        m.accountLastFailure?.let { f ->
            line("account last sign-in failure", "${ago(s.generatedAtMs - f.atMs)}: ${f.kind}" +
                (f.code?.let { " (code $it)" } ?: "") + f.detail.takeIf { it.isNotBlank() }?.let { " — ${it.take(MAX_ERROR)}" }.orEmpty())
        }

        s.speech?.let { v ->
            section("Speech")
            line("speech to text", v.choice)
            line("built-in recognition", if (!v.systemAvailable) "none" else "present" + (if (v.onDevice) ", on-device" else "") + (if (v.silent) ", marked no response" else ""))
            v.remembered?.let { line("recognition service used", it) }
            line("recognition services", if (v.services.isEmpty()) "none" else "${v.services.size}")
            v.services.forEach { appendLine("- $it") }
        }

        section("Recent runs")
        if (s.runs.isEmpty()) appendLine("none")
        s.runs.forEach { run ->
            appendLine(
                "- ${ago(s.generatedAtMs - run.startedAtMs)}: ${run.outcome}" +
                    (run.failKind?.let { " ($it)" } ?: "") + (run.judged?.let { ", judged $it" } ?: "") +
                    ", ${run.steps} steps" + (run.model?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""),
            )
        }

        section("Update")
        line("last check", s.update.status)
        line("latest seen", s.update.latest ?: "none")
        line("update available", s.update.available ?: "no")
    }.trimEnd() + "\n")

    private fun StringBuilder.phone(s: DiagnosticsSnapshot) {
        val i = s.setup
        val p = s.phone
        val view = PhoneSetupFlow.view(i)
        line("helper", shell(i.status))
        line("helper restart", when (i.restart) {
            HelperRestart.Wireless -> "wireless debugging (paired)"
            HelperRestart.UsbCommand -> "USB start command"
            HelperRestart.None -> "not set up"
        })
        line("setup", "phase=${view.phase}" + (view.current?.let { ", current=${step(it)}" } ?: ""))
        line("steps", SetupStep.entries.joinToString(", ") { "${it.ordinal + 1} ${view.marks[it]}" })
        view.suspect?.let { line("suspect", step(it.step) + if (it.unreadable) " (switch unreadable)" else "") }
        line("confirmed by user", i.confirmed.sortedBy { it.ordinal }.joinToString(", ") { step(it) }.ifEmpty { "none" })
        if (i.accessibilitySkipped) line("accessibility step", "skipped")
        if (i.repair) line("repair", "yes")
        line("developer options", onOff(i.developerOptions))
        if (i.developerSettingsMissing) line("developer options page", "missing")
        if (i.wirelessMissing) line("wireless debugging setting", "missing")
        line("USB debugging", onOff(i.usbDebugging))
        line("wireless debugging", p.wirelessDebuggingSetting?.let(::onOff) ?: "unreadable")
        line("accessibility", when (p.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        })
        line("restricted settings", if (i.restrictedSettings) "may block" else "no")
        line("pairing service", when (i.discovery) {
            is PairingDiscovery.Found -> "found"
            PairingDiscovery.Searching -> "searching"
            PairingDiscovery.Idle -> "not found / not searching"
        })
        line("connect service", p.connect?.let { c ->
            "cached ${c.cache}, property ${c.property}, mDNS ${c.mdns} candidates; " +
                (if (c.scanned) "scan: ${c.scanOpen} open, ${c.scanAdb} adb; " else "scan: not run; ") +
                "manual: " + (if (c.manual) "yes" else "no") + "; " +
                if (c.connected) "connected" else "none connected"
        } ?: "not tried in this session")
        line("notifications", if (i.notificationsAllowed) "allowed" else "blocked")
        line("pairing channel", "${i.pairingChannel} (" +
            (if (p.pairingChannels.isEmpty()) "not created" else p.pairingChannels.entries.joinToString(", ") { "${it.key}=${importance(it.value)}" }) + ")")
        view.notifyIssue?.let { line("pairing notification issue", it.name) }
        line("attention channel", importance(p.attentionChannelImportance))
        line("Wi-Fi", when (p.wifiConnected) { true -> "connected"; false -> "not connected"; null -> "unknown" })
        line("last error", p.lastError?.takeIf { it.isNotBlank() }?.take(MAX_ERROR) ?: "none")
        line("adb key status", p.adbKey ?: "unknown")
        line("app copies", p.appCopies ?: "unknown")
        p.appsAfterTask?.let { line("apps after task", it) }
    }

    private fun StringBuilder.section(name: String) {
        appendLine()
        appendLine("[$name]")
    }

    private fun StringBuilder.line(key: String, value: String) {
        appendLine("$key: $value")
    }

    private fun step(s: SetupStep) = "${s.ordinal + 1} ${s.name}"

    private fun shell(s: BuiltinShellStatus): String = when (s) {
        is BuiltinShellStatus.Failed -> "Failed (reason=${s.reason}, fix=${s.fix})"
        else -> s.toString()
    }

    private fun capability(c: RecoveryCapability) = when (c) {
        RecoveryCapability.Accessibility -> "auto accessibility"
        RecoveryCapability.BatteryWhitelist -> "battery whitelist"
        RecoveryCapability.RestoreAfterReboot -> "restore after reboot"
        RecoveryCapability.AutoWirelessDebugging -> "auto wireless debugging"
    }

    private fun consent(r: RecoveryConsent, c: RecoveryCapability) =
        (if (r.wants(c)) "on" else "off by user") + ", " + (if (r.isApplied(c)) "applied" else "not applied")

    private fun role(r: DiagnosticsSnapshot.RoleModel) = "${r.model} @ ${host(r.baseUrl)}"

    /** Only the host name is left in the address (there may be other things in the path and query); if it cannot be parsed, it will not be written. */
    fun host(url: String): String = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(unparsed host)"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    /** The importance of the notification channel, according to the system's values: 0 off, 1 lowest, 2 low, 3 default, 4 high. */
    private fun importance(i: Int?) = when (i) {
        null -> "not created"
        0 -> "off"
        1 -> "min"
        2 -> "low"
        3 -> "default"
        4, 5 -> "high"
        else -> "$i"
    }

    fun ago(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 48 * 60 -> "${m / 60}h ago"
            else -> "${m / (24 * 60)}d ago"
        }
    }

    private const val MAX_ERROR = 200

    private val IP = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b(:\d{1,5})?""")
    /** Written in full (at least five paragraphs) or abbreviated with "::"; 12:30:05 in the time does not count. */
    private val IPV6 = Regex("""\[?(?:\b(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}\b|[0-9A-Fa-f]{0,4}::[0-9A-Fa-f:]*[0-9A-Fa-f])(?:%\w+)?\]?(:\d{1,5})?""")
    private val PORT = Regex("""(?i)\bport\s*[=:]?\s*\d{2,5}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val SECRET_PAIR = Regex("""(?i)\b(api[_-]?key|key|token|secret|password|passwd|authorization|cookie)\b(\s*[=:]\s*)\S+""")

    /** The last pass: IP (with port), IPv6, port number, email, "name=value" like credentials, and then give it to [Redactor] (Key, JWT, long and complex string). */
    fun sanitize(text: String): String {
        var out = IP.replace(text, "<ip>")
        out = IPV6.replace(out, "<ip>")
        out = PORT.replace(out, "port <n>")
        out = EMAIL.replace(out, "<email>")
        out = SECRET_PAIR.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + Redactor.MASK }
        return Redactor.pageData(out)
    }
}
