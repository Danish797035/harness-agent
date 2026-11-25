package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meenai.mina.core.shell.AutoOnBlock
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.HelperRestart
import com.meenai.mina.core.shell.PermissionResult
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryConsent
import com.meenai.mina.core.shell.RepairOutcome
import com.meenai.mina.core.shell.RestoreOutcome
import com.meenai.mina.cap.phone.PhoneServices
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaType

/**
 * The text of several authorization switches for mobile phone operation in Settings › Recovery and
 * Repair (pure functions, easy for testing). Just like the introductory page, it is written in plain
 * language. Each item is clearly stated what Mina will do after it is turned on and how to turn it off.
 * The instructions are always written below the switch. They are all turned on by default, and Mina can
 * make up for it after configuring the mobile phone for operation.
 */
object RecoveryCopy {
    data class SwitchCopy(val title: String, val subtitle: String, val explain: String)

    fun switch(c: RecoveryCapability): SwitchCopy = when (c) {
        RecoveryCapability.Accessibility -> SwitchCopy(
            tr(R.string.rec_a11y),
            tr(R.string.rec_default_on),
            tr(R.string.rec_a11y_body),
        )
        RecoveryCapability.BatteryWhitelist -> SwitchCopy(
            tr(R.string.rec_battery),
            tr(R.string.rec_default_on),
            tr(R.string.rec_battery_body),
        )
        RecoveryCapability.RestoreAfterReboot -> SwitchCopy(
            tr(R.string.rec_boot),
            tr(R.string.rec_default_on),
            tr(R.string.rec_boot_body),
        )
        RecoveryCapability.AutoWirelessDebugging -> SwitchCopy(
            tr(R.string.rec_auto_wireless),
            tr(R.string.rec_default_on),
            tr(R.string.rec_auto_wireless_body),
        )
    }

    val NEED_SETUP: String get() = tr(R.string.rec_need_setup)
    val FAILED: String get() = tr(R.string.rec_failed)
    val WORKING: String get() = tr(R.string.rec_working)

    /** One line of feedback after flipping the switch; not much to say when successful. */
    fun result(r: PermissionResult): String? = when (r) {
        PermissionResult.Done -> null
        PermissionResult.NoRunner -> NEED_SETUP
        is PermissionResult.Failed -> FAILED
    }

    /** Several items that require paired wireless debugging to work: It is not available when starting the assistant using only the USB boot command. */
    private val NEEDS_PAIRING = setOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)

    /**
     * Let me say something about the item that is enabled by default and has not yet taken effect: If
     * the phone is not configured properly, it will be prompted to complete the settings first (it will
     * automatically open after configuration). If the automatic opening is not successful, just tell the
     * truth; if it is effective or the user has turned it off, there will be no further explanation.
     * When the assistant is started using only the USB startup command ([HelperRestart.UsbCommand]), the
     * two instructions for restarting the assistant through wireless debugging are clearly unavailable
     * and the command must be run again after restarting.
     */
    fun pending(
        c: RecoveryCapability, consent: RecoveryConsent, paired: Boolean, autoFailed: Set<RecoveryCapability>,
        restart: HelperRestart = HelperRestart.None,
    ): String? = when {
        !consent.wants(c) -> null
        restart == HelperRestart.UsbCommand && c in NEEDS_PAIRING -> tr(R.string.rec_needs_pairing)
        consent.isApplied(c) -> null
        !paired -> NEED_SETUP
        c in autoFailed -> FAILED
        else -> null
    }

    /** Under "Turn on accessibility for me", the time when the automatic restart of the accessibility service was successful last time is shown. If it fails, it will not be mentioned. The prompt at the top of the interface will still provide manual processing. */
    fun restarted(o: RepairOutcome?, zone: ZoneId = ZoneId.systemDefault()): String? =
        o?.takeIf { it.restarted }?.let { tr(R.string.rec_restarted_at, HH_MM.format(Instant.ofEpochMilli(it.atMillis).atZone(zone))) }

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

    /** "Automatically turn on wireless debugging" below, I will tell you why it failed to turn on and stopped first; I won't say more if I don't stop. */
    fun autoOnBlocked(b: AutoOnBlock?): String? = when (b) {
        null -> null
        AutoOnBlock.NotAllowedHere -> tr(R.string.rec_not_allowed_here)
        AutoOnBlock.Refused -> tr(R.string.rec_refused)
        AutoOnBlock.GaveUp -> tr(R.string.rec_gave_up)
    }

    /** "Automatic recovery after restart" below, the results of the last boot; not much to say about normal recovery. */
    fun lastRestore(o: RestoreOutcome?): String? = when (o) {
        null, RestoreOutcome.Restored -> null
        RestoreOutcome.PermissionMissing -> tr(R.string.rec_permission_missing)
        RestoreOutcome.SettingUnavailable -> tr(R.string.rec_setting_unavailable)
        RestoreOutcome.NotRecovered -> tr(R.string.rec_not_recovered)
    }
}

/** Connect several switches in the real state: select [com.meenai.mina.settings.RecoverySettings] and execute the paired connection. */
@Composable
fun RecoverySwitches() {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val settings = phone.recoverySettings
    val consent by settings.consent.collectAsState()
    val shell by phone.shellControl.status.collectAsState()
    val autoFailed by phone.recovery.permissions.autoFailed.collectAsState()
    val lastRestore by settings.lastRestore.collectAsState()
    val repaired by phone.recovery.accessibilityRepair.last.collectAsState()
    val autoOnBlocked by phone.shellManager.wirelessAutoOn.blocked.collectAsState()
    var busy by remember { mutableStateOf<RecoveryCapability?>(null) }
    var message by remember { mutableStateOf<Pair<RecoveryCapability, String>?>(null) }
    val notes = RecoveryCapability.entries.mapNotNull { cap ->
        val m = message
        (if (m?.first == cap) m.second else RecoveryCopy.pending(
            cap, consent, shell == BuiltinShellStatus.Running || phone.shellControl.paired, autoFailed, phone.shellControl.restart,
        ))
            ?.let { cap to it }
    }.toMap()
    RecoverySection(
        consent = consent, busy = busy, notes = notes, lastRestore = lastRestore, restartNote = RecoveryCopy.restarted(repaired),
        autoOnNote = RecoveryCopy.autoOnBlocked(autoOnBlocked),
        onToggle = { cap, on ->
            busy = cap
            message = null
            // Don’t follow the page: When the user leaves the settings page, the command must be
            // completed before the selection is consistent with the system.
            phone.pairing.scope.launch {
                val r = phone.recovery.permissions.set(cap, on)
                message = RecoveryCopy.result(r)?.let { cap to it }
                busy = null
            }
        },
    )
}

/**
 * Several switches without status for easy testing. The switch shows whether the user wants this item
 * (turn it on without turning it off personally), and the toggle is executed directly without
 * confirmation; [notes] is a line of prompts under a certain item (the result of the toggle, the
 * settings must be completed first, and the automatic opening is not successful); [restartNote] is the
 * line of instructions under "Turn on accessibility for me" (the accessibility service was automatically
 * restarted last time); [autoOnNote] It's the line below "Automatically turn on wireless debugging" (it
 * didn't work for you, so I stopped it first).
 */
@Composable
fun RecoverySection(
    consent: RecoveryConsent,
    busy: RecoveryCapability?,
    notes: Map<RecoveryCapability, String>,
    lastRestore: RestoreOutcome?,
    onToggle: (RecoveryCapability, Boolean) -> Unit,
    restartNote: String? = null,
    autoOnNote: String? = null,
) {
    GroupCard {
        RecoveryCapability.entries.forEachIndexed { i, cap ->
            val copy = RecoveryCopy.switch(cap)
            val on = consent.wants(cap)
            val icon = iconOf(cap)
            ListRow(icon, copy.title, if (busy == cap) RecoveryCopy.WORKING else copy.subtitle, first = i == 0) {
                MinaSwitch(on, { want -> onToggle(cap, want) }, Modifier.testTag("recovery-${cap.name}"), enabled = busy == null)
            }
            Note(copy.explain, MinaTheme.colors.ink3)
            notes[cap]?.let { Note(it, MinaTheme.colors.bad) }
            if (cap == RecoveryCapability.Accessibility) restartNote?.let { Note(it, MinaTheme.colors.ink3) }
            if (cap == RecoveryCapability.RestoreAfterReboot && consent.isApplied(cap)) {
                RecoveryCopy.lastRestore(lastRestore)?.let { Note(it, MinaTheme.colors.warn) }
            }
            if (cap == RecoveryCapability.AutoWirelessDebugging && consent.wants(cap)) autoOnNote?.let { Note(it, MinaTheme.colors.warn) }
        }
    }
}

private fun iconOf(c: RecoveryCapability): ImageVector = when (c) {
    RecoveryCapability.Accessibility -> MinaIcons.Hand
    RecoveryCapability.BatteryWhitelist -> MinaIcons.Sun
    RecoveryCapability.RestoreAfterReboot -> MinaIcons.Wrench
    RecoveryCapability.AutoWirelessDebugging -> MinaIcons.Refresh
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MinaType.Hint, color = color)
}
