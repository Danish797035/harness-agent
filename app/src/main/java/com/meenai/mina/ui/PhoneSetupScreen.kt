package com.meenai.mina.ui

import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import com.meenai.mina.ui.components.keepsKeyboard
import com.meenai.mina.about.Diagnostics
import com.meenai.mina.about.DiagnosticsCollector
import com.meenai.mina.about.setupInputs
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.meenai.mina.core.shell.BuiltinShellFailure
import com.meenai.mina.core.shell.BuiltinShellFix
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.HelperRestart
import com.meenai.mina.core.shell.PairingCode
import com.meenai.mina.core.shell.PairingNotifyIssue
import com.meenai.mina.core.shell.PhoneSetupFlow
import com.meenai.mina.core.shell.SetupPhase
import com.meenai.mina.core.shell.SetupStep
import com.meenai.mina.core.shell.SetupView
import com.meenai.mina.core.shell.StepMark
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.cardSurface
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.ProgressDots
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.ui.theme.MinaShapes
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The user confirms the completed steps and is still there after turning the screen and entering the background. */
private val StepSetSaver = Saver<Set<SetupStep>, String>(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { s -> s.split(',').mapNotNull { n -> SetupStep.entries.firstOrNull { it.name == n } }.toSet() },
)

/**
 * "Set up mobile phone operations" guide page: Connect to real-time status. Each step is automatically
 * checked by pressing the system switch. If it cannot be read or cannot be read on time, the user clicks
 * "I have opened it, next step"; click on a previous step in the list to go back and redo it. Start
 * looking for the pairing window when you stop at the pairing step: if accessibility is turned on, Mina
 * will read the pairing code by herself, otherwise a notification with an input box will be sent.
 * [repair] came in from the "re-pairing" of the settings page.
 */
@Composable
fun PhoneSetupScreen(status: SystemStatus, repair: Boolean, onFix: (Fix) -> Unit, onboarding: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val pairing = phone.pairing
    val discovery by phone.shellControl.pairingDiscovery.collectAsState()
    val scanning by phone.shellManager.scanningPorts.collectAsState()
    val shell = status.builtinShell
    var repairStarted by rememberSaveable { mutableStateOf(false) }
    if (repair && !repairStarted) { repairStarted = true; pairing.startRepair() }
    val repairing by pairing.repairing.collectAsState()
    var confirmed by rememberSaveable(stateSaver = StepSetSaver) { mutableStateOf(emptySet<SetupStep>()) }
    var revisit by rememberSaveable { mutableStateOf<SetupStep?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var accessibilitySkipped by rememberSaveable { mutableStateOf(false) }

    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var usbAsked by rememberSaveable { mutableStateOf(false) }
    var stalledMs by remember { mutableLongStateOf(0L) }

    val inputs = status.setupInputs(discovery, repairing, accessibilitySkipped, confirmed, revisit, stalledMs, usbAsked)
    val view = PhoneSetupFlow.view(inputs)
    // If you stop at the same place and there is no progress, count the time. If you change the place,
    // count from the beginning; when the time limit is reached, there is no need to count again.
    val stallPoint = PhoneSetupFlow.stallPoint(view)
    LaunchedEffect(stallPoint) {
        stalledMs = 0L
        if (stallPoint == null) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (stalledMs < PhoneSetupFlow.STALL_MS) {
            delay(1_000L)
            stalledMs = SystemClock.elapsedRealtime() - start
        }
    }
    // If "Start using computer instead" appears, keep it and do not take it back because the connection
    // starts again.
    LaunchedEffect(view.usbFallback) { if (view.usbFallback) usbAsked = true }
    // Go back and redo the step: when you read that it changes from closed to open, you will
    // automatically return to the original step without clicking again.
    val revisitDetected = revisit?.let { PhoneSetupFlow.detected(it, inputs) }
    var revisitSawOff by rememberSaveable(revisit) { mutableStateOf(false) }
    LaunchedEffect(revisit, revisitDetected) {
        if (revisitDetected == false) revisitSawOff = true
        else if (revisitDetected == true && revisitSawOff) revisit = null
    }
    // The connection is started or has been successful: follow the assistant and no longer stop at the
    // step of looking back.
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Working || view.phase == SetupPhase.Success) revisit = null }
    // When it comes to pairing, start looking for the pairing window; if the notification does not allow
    // it, then request it at this time (the pairing code must be filled in the notification). If the
    // "Pairing" notification is turned off, you need to turn it on in the system settings. If it does
    // not automatically jump to it, there is a button on the page.
    LaunchedEffect(view.current) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    // When you stop at the pairing step and leave the boot page, you will most likely have to go to the
    // system settings and open the pairing window (you don’t have to click "Go to Wireless Debugging"):
    // From this point on, watch for another 10 minutes to avoid the boot page being left for too long,
    // and the pairing code reading and code filling notifications have stopped.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    val notificationsOff = view.notifyIssue == PairingNotifyIssue.NotificationsOff
    LaunchedEffect(notificationsOff) {
        if (notificationsOff && !askedNotifications) {
            askedNotifications = true
            onFix(Fix.AllowNotifications)
        }
    }
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Success) phone.setupPrompt.clear() }
    // Prepare the "Start with Computer" script when opening the boot page (bring a new password when the
    // assistant is not available)
    LaunchedEffect(Unit) { phone.shellManager.usbScript.refresh() }
    val usbCommand = remember { phone.shellManager.usbScript.command() }

    val copyUsbCommand = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(PhoneSetupCopy.USB_TITLE, usbCommand))
        Toast.makeText(context, PhoneSetupCopy.USB_COPIED, Toast.LENGTH_SHORT).show()
    }
    val close = { pairing.cancelRepair(); onClose() }
    BackHandler(onBack = close)
    PhoneSetupContent(
        view = view,
        onStepAction = { step ->
            when (step) {
                SetupStep.DeveloperOptions -> onFix(Fix.OpenAboutPhone)
                SetupStep.UsbDebugging -> onFix(Fix.OpenUsbDebugging)
                SetupStep.Accessibility -> onFix(Fix.OpenAccessibility)
                SetupStep.WirelessDebugging -> onFix(Fix.OpenDeveloperOptions)
                SetupStep.PairingCode -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                SetupStep.Connect -> Unit
            }
        },
        onFailureFix = { fix ->
            when (fix) {
                BuiltinShellFix.Retry -> onFix(Fix.RetryPhoneHelper)
                BuiltinShellFix.RePair -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                BuiltinShellFix.OpenWifi -> onFix(Fix.OpenWifi)
                BuiltinShellFix.OpenWirelessDebugging -> onFix(Fix.OpenWirelessDebugging)
                BuiltinShellFix.RunUsbCommand -> copyUsbCommand()
            }
        },
        onAllowNotifications = { onFix(Fix.AllowNotifications) },
        onOpenPairingChannel = { onFix(Fix.OpenPairingChannel) },
        headsUpLowered = status.notificationsAllowed && (status.attentionLowered || status.resultLowered),
        onOpenHeadsUp = { onFix(if (status.attentionLowered) Fix.OpenAttentionChannel else Fix.OpenResultChannel) },
        onConfirmStep = { step -> confirmed = confirmed + step; revisit = null },
        onSubmit = { code, port -> pairing.submit(code, port) },
        onClose = close,
        onAppInfo = { onFix(Fix.OpenAppInfo) },
        onSkipAccessibility = { accessibilitySkipped = true; revisit = null },
        onRevisit = { step -> revisit = step },
        onboarding = onboarding,
        onDiagnostics = { diagnosticsOpen = true },
        scanning = scanning,
        onConnectPort = { port ->
            phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellManager.connectWithPort(port)
            }
        },
        usbCommand = usbCommand,
        onAskUsb = { usbAsked = true },
        onCopyUsbCommand = copyUsbCommand,
    )
    // Diagnostic information can be sent to the author at any step: the input on the guide page is used,
    // and the steps confirmed by the user are also included.
    if (diagnosticsOpen) {
        DiagnosticsSheet(load = { Diagnostics.render(DiagnosticsCollector.collect(context, status, inputs)) }, onDismiss = { diagnosticsOpen = false })
    }
}

/**
 * A stateless boot page for easy testing. The steps are a numbered list: those that are completed are
 * ticked, the current step lights up with a button, and those that follow are grayed out. Several steps
 * of reading the switch include [onConfirmStep] ("I've turned it on, next step"); you can click
 * [onRevisit] to go back and redo the completed steps, and pass null to return to the original steps.
 * When [onboarding] is true, it is the last step to get started for the first time, and there will be an
 * extra row of pagination points at the bottom. [onSubmit] The received code has been verified to be a
 * 6-digit number. When the one-step pairing notification cannot be sent
 * ([SetupView.needsNotifications]), the first notification is the main button: [onAllowNotifications] or
 * [onOpenPairingChannel]. There is always "Copy Diagnostic Information" at the bottom when
 * [onDiagnostics] is not empty, and there is also one in the error step when it fails. In addition to
 * success, there is "I'll talk about it later" at the bottom of every stage, and you can always leave
 * when you return: the user is not trapped on this page. When [scanning] is true, the connection step
 * indicates that the machine is looking for a port; when it cannot be connected, [onConnectPort]
 * receives the wireless debugging port filled in by the user (verified 1–65535). [usbCommand] is not
 * empty, "Start using computer instead" will appear: it does not usually appear, but only in the
 * wireless debugging step there is a line "Cannot find wireless debugging?" ([onAskUsb]); when
 * [SetupView.usbFallback] is true, a card is given in the current step, which is a description, command
 * and copy button ([onCopyUsbCommand]). For [SetupPhase.Unavailable], this environment cannot be set up
 * for mobile phone operation: there is only an instruction card and "Got it", without any steps.
 */
@Composable
fun PhoneSetupContent(
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (code: String, port: Int?) -> Unit,
    onClose: () -> Unit,
    headsUpLowered: Boolean = false,
    onOpenHeadsUp: () -> Unit = {},
    onAppInfo: () -> Unit = {},
    onSkipAccessibility: () -> Unit = {},
    onRevisit: (SetupStep?) -> Unit = {},
    onboarding: Boolean = false,
    onOpenPairingChannel: () -> Unit = {},
    onDiagnostics: (() -> Unit)? = null,
    scanning: Boolean = false,
    onConnectPort: ((Int) -> Unit)? = null,
    usbCommand: String? = null,
    onAskUsb: () -> Unit = {},
    onCopyUsbCommand: () -> Unit = {},
) {
    val canRevisit = view.phase == SetupPhase.Guide || view.phase == SetupPhase.Failed
    fun revisitable(step: SetupStep) = canRevisit && step != view.current && step in PhoneSetupFlow.REVISITABLE &&
        view.marks.getValue(step) in setOf(StepMark.Done, StepMark.Skipped, StepMark.Failed)
    val c = MinaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            TopBar(PhoneSetupCopy.BAR_TITLE, MinaIcons.Back, onClose, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (view.phase == SetupPhase.Unavailable) {
                    // There is no way to become an assistant in this environment: only explanations, not
                    // steps that lead to failure.
                    GroupCard {
                        Column(Modifier.padding(16.dp).testTag("setup-unavailable")) {
                            Text(PhoneSetupCopy.UNAVAILABLE, style = MinaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
                            Text(
                                PhoneSetupCopy.UNAVAILABLE_REASON, Modifier.padding(top = 8.dp),
                                fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                            )
                        }
                    }
                } else {
                    if (view.phase == SetupPhase.Success) {
                        Text(
                            PhoneSetupCopy.SUCCESS_LINE, Modifier.padding(top = 4.dp, bottom = 16.dp).testTag("setup-success"),
                            style = MinaType.Body, fontWeight = FontWeight.SemiBold, color = c.ok,
                        )
                    } else {
                        Text(
                            PhoneSetupCopy.INTRO, Modifier.padding(top = 4.dp, bottom = 16.dp),
                            fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                        )
                    }
                    GroupCard {
                        SetupStep.entries.forEach { step ->
                            StepRow(
                                step, view.marks.getValue(step), current = step == view.current, first = step.ordinal == 0,
                                onClick = if (revisitable(step)) ({ onRevisit(step) }) else null,
                            ) {
                                if (step == view.current) CurrentStepBody(
                                    step, view, onStepAction, onFailureFix, onAllowNotifications, onOpenPairingChannel, headsUpLowered, onOpenHeadsUp,
                                    onConfirmStep, onSubmit, onAppInfo, onSkipAccessibility, onRevisit, onDiagnostics, onConnectPort,
                                )
                                val working = step == SetupStep.Connect && view.phase == SetupPhase.Working
                                if (working) WorkingLine(scanning)
                                if (usbCommand != null && (step == view.current || working)) {
                                    if (view.usbFallback) UsbStartCard(view.restart, usbCommand, onCopyUsbCommand)
                                    else if (step == SetupStep.WirelessDebugging) LinkText(PhoneSetupCopy.USB_ASK, "setup-usb-ask", onAskUsb)
                                }
                            }
                        }
                    }
                    if (SetupStep.entries.any(::revisitable)) FooterNote(PhoneSetupCopy.REVISIT_NOTE)
                    if (view.phase != SetupPhase.Success) FooterNote(PhoneSetupCopy.SKIP_NOTE)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                if (view.phase == SetupPhase.Success) {
                    Text(
                        PhoneSetupCopy.successNote(view.restart), Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        style = MinaType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                }
                if (onboarding) ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Phone), Modifier.padding(bottom = 8.dp))
                when (view.phase) {
                    SetupPhase.Success -> MinaButton(
                        if (onboarding) PhoneSetupCopy.START_USING else PhoneSetupCopy.SUCCESS_DONE, onClose,
                        Modifier.fillMaxWidth().testTag("setup-done"),
                    )
                    SetupPhase.Unavailable -> MinaButton(stringResource(R.string.ok_got_it), onClose, Modifier.fillMaxWidth().testTag("setup-got-it"))
                    // When connecting or unable to connect, you can leave first and then continue in the
                    // settings.
                    SetupPhase.Guide, SetupPhase.Working, SetupPhase.Failed -> MinaButton(
                        PhoneSetupCopy.LATER, onClose, Modifier.fillMaxWidth().testTag("setup-later"),
                        kind = ButtonKind.Text, size = ButtonSize.Medium,
                    )
                }
                onDiagnostics?.let {
                    MinaButton(
                        PhoneSetupCopy.DIAGNOSTICS, it, Modifier.fillMaxWidth().testTag("setup-diagnostics"),
                        kind = ButtonKind.Text, size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/**
 * Another way: Use a computer to start the assistant via a USB data cable. Placed in the current step,
 * it has the same light-colored background as the other prompts in this step: the title, a description,
 * the command and the copy button. When the assistant originally started like this
 * ([HelperRestart.UsbCommand]), the title said "Run it again".
 */
@Composable
private fun UsbStartCard(restart: HelperRestart, command: String, onCopy: () -> Unit) {
    val c = MinaTheme.colors
    val (title, body) = PhoneSetupCopy.usbCard(restart)
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, RoundedCornerShape(12.dp)).padding(12.dp).testTag("setup-usb")) {
        Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
        Text(body, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = c.ink2)
        Text(
            command, Modifier.padding(top = 8.dp).fillMaxWidth().background(c.fill, RoundedCornerShape(10.dp)).padding(10.dp).testTag("setup-usb-command"),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
        )
        PillButton(PhoneSetupCopy.USB_COPY, primary = false, tag = "setup-usb-copy", onClick = onCopy)
    }
}

@Composable
private fun StepRow(step: SetupStep, mark: StepMark, current: Boolean, first: Boolean, onClick: (() -> Unit)?, body: @Composable () -> Unit) {
    val copy = PhoneSetupCopy.step(step)
    val c = MinaTheme.colors
    Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).testTag("setup-step-${step.name}")) {
        if (!first) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepBadge(step.ordinal + 1, mark)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        copy.title, Modifier.weight(1f), style = MinaType.Body,
                        fontWeight = if (mark == StepMark.Current || mark == StepMark.Failed) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.ink,
                    )
                    if (mark == StepMark.Done) RowValue(copy.done, ValueTone.Ok)
                    if (mark == StepMark.Skipped) RowValue(PhoneSetupCopy.ACCESSIBILITY_SKIPPED)
                }
                if (!current) Text(copy.brief, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = if (mark == StepMark.Todo) c.ink3 else c.ink2)
                body()
            }
        }
    }
}

@Composable
private fun StepBadge(n: Int, mark: StepMark) {
    val c = MinaTheme.colors
    val bg = when (mark) {
        StepMark.Done -> c.ok
        StepMark.Current -> c.accent
        StepMark.Failed -> c.bad
        StepMark.Todo, StepMark.Skipped -> c.greySoft
    }
    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        when (mark) {
            StepMark.Done -> Icon(MinaIcons.Check, null, Modifier.size(15.dp), tint = c.onAccent)
            StepMark.Failed -> Icon(MinaIcons.Exclaim, null, Modifier.size(15.dp), tint = c.onAccent)
            else -> Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.onAccent)
        }
    }
}

@Composable
private fun CurrentStepBody(
    step: SetupStep,
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenPairingChannel: () -> Unit,
    headsUpLowered: Boolean,
    onOpenHeadsUp: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (String, Int?) -> Unit,
    onAppInfo: () -> Unit,
    onSkipAccessibility: () -> Unit,
    onRevisit: (SetupStep?) -> Unit,
    onDiagnostics: (() -> Unit)?,
    onConnectPort: ((Int) -> Unit)?,
) {
    val copy = PhoneSetupCopy.step(step)
    Text(copy.body, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = MinaTheme.colors.ink2)
    view.failure?.let { f ->
        val fc = PhoneSetupCopy.failure(f.reason)
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MinaTheme.colors.badSoft, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(fc.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MinaTheme.colors.bad)
            Text(fc.body, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MinaTheme.colors.ink2)
        }
        // When you want to run USB commands again, there are commands and copy buttons in the card
        // below, so you don’t need to repeat one.
        if (f.fix != BuiltinShellFix.RunUsbCommand || !view.usbFallback) {
            PillButton(PhoneSetupCopy.fixLabel(f.fix), primary = true, tag = "setup-fix") { onFailureFix(f.fix) }
        }
        // Port not found automatically: Ask the user to fill in the information according to the
        // "Wireless Debugging" page
        if (step == SetupStep.Connect && f.reason == BuiltinShellFailure.ConnectFailed) onConnectPort?.let { ConnectPort(it) }
        if (view.phase == SetupPhase.Failed) onDiagnostics?.let { LinkText(PhoneSetupCopy.DIAGNOSTICS, "setup-diagnostics-failed", it) }
    }
    // Which step is the most likely reason for failure: Make it clear and give the go back button
    view.suspect?.let { s ->
        Text(
            PhoneSetupCopy.suspect(s),
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MinaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                .testTag("setup-suspect"),
            fontSize = 13.sp, lineHeight = 18.sp, color = MinaTheme.colors.ink,
        )
        PillButton(PhoneSetupCopy.goBackTo(s.step), primary = view.failure == null, tag = "setup-suspect-back") { onRevisit(s.step) }
    }
    // When there is no accessibility, the pairing code can only be filled in the notification: if the
    // notification cannot be sent, fix it first. This is the main button for this step.
    val fixNotificationsFirst = step == SetupStep.PairingCode && view.needsNotifications && !view.autoRead
    if (step == SetupStep.PairingCode) {
        when (view.notifyIssue) {
            PairingNotifyIssue.NotificationsOff -> {
                Text(PhoneSetupCopy.NEED_NOTIFICATIONS, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MinaTheme.colors.warn)
                PillButton(PhoneSetupCopy.ALLOW_NOTIFICATIONS, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onAllowNotifications() }
            }
            PairingNotifyIssue.ChannelOff -> {
                Text(PhoneSetupCopy.PAIRING_CHANNEL_OFF, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MinaTheme.colors.warn)
                PillButton(PhoneSetupCopy.OPEN_PAIRING_CHANNEL, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onOpenPairingChannel() }
            }
            // You can also pull down to fill in the text without popping up: only reminding, not
            // grabbing the main button; when accessibility is on, notifications are not needed and no
            // mention is made.
            PairingNotifyIssue.NotHeadsUp -> if (!view.autoRead) {
                Text(
                    PhoneSetupCopy.PAIRING_LOWERED,
                    Modifier.padding(top = 10.dp).fillMaxWidth().background(MinaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                        .testTag("setup-pairing-lowered"),
                    fontSize = 13.sp, lineHeight = 18.sp, color = MinaTheme.colors.ink,
                )
                PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-pairing-lowered-open") { onOpenPairingChannel() }
            }
            null -> Unit
        }
        if (headsUpLowered && !view.needsNotifications) {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().background(MinaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                    .testTag("setup-heads-up"),
            ) {
                Text(PhoneSetupCopy.HEADS_UP_LOWERED, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MinaTheme.colors.warn)
            }
            PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-heads-up-open") { onOpenHeadsUp() }
        }
        if (view.autoRead) AutoReadHint() else NotifyGuide()
        if (view.portFound && !view.autoRead) Text(PhoneSetupCopy.PORT_FOUND, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MinaTheme.colors.ok)
    }
    // Recommended skip step for accessibility: Mina can open it by itself after pairing, and will not be
    // blocked by "restricted settings"
    val a11y = step == SetupStep.Accessibility
    if (a11y) PillButton(PhoneSetupCopy.ACCESSIBILITY_SKIP, primary = true, tag = "setup-skip-a11y", onClick = onSkipAccessibility)
    if (view.failure == null && copy.action != null) {
        PillButton(copy.action, primary = !a11y && !fixNotificationsFirst, tag = "setup-action") { onStepAction(step) }
    }
    if (a11y && view.showAppInfo) PillButton(PhoneSetupCopy.APP_INFO, primary = false, tag = "setup-app-info", onClick = onAppInfo)
    // The switch I read is just for reference: the user said that once it is turned on, it will go down
    // and it will not get stuck at any step.
    if (step in PhoneSetupFlow.CONFIRMABLE) PillButton(PhoneSetupCopy.TURNED_ON_NEXT, primary = false, tag = "setup-confirm") { onConfirmStep(step) }
    if (step == SetupStep.PairingCode) ManualCode(askPort = !view.portFound, onSubmit = onSubmit)
    if (view.revisiting) LinkText(PhoneSetupCopy.REVISIT_BACK, "setup-revisit-back") { onRevisit(null) }
}

@Composable
private fun LinkText(text: String, tag: String?, onClick: () -> Unit) {
    Text(
        text, Modifier.padding(top = 6.dp).clickable(onClick = onClick).padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        fontSize = 13.sp, color = MinaTheme.colors.accent,
    )
}

/** Turn on accessibility: Before going to system settings, Mina will read the pairing code by itself. */
@Composable
private fun AutoReadHint() {
    val c = MinaTheme.colors
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.okSoft, RoundedCornerShape(12.dp))
            .padding(12.dp).testTag("setup-pairing-hint"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MinaIcons.Check, null, Modifier.size(20.dp), tint = c.ok)
        Text(PhoneSetupCopy.AUTO_READ_HINT, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
    }
}

/** Accessibility is not turned on: Use a small three-line numbered icon to explain how to fill in the pairing code in the notification without leaving the pairing window. */
@Composable
private fun NotifyGuide() {
    val c = MinaTheme.colors
    val icons = listOf(MinaIcons.Phone, MinaIcons.Bell, MinaIcons.Key)
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, RoundedCornerShape(12.dp))
            .padding(12.dp).testTag("setup-pairing-guide"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhoneSetupCopy.NOTIFY_GUIDE.forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onAccent)
                }
                Icon(icons[i], null, Modifier.size(18.dp), tint = c.accent)
                Text(line, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = c.ink)
            }
        }
    }
}

/** What to do when the notification does not pop up: fill in the code directly in the App (and fill in the port when the pairing window cannot be found). */
@Composable
private fun ManualCode(askPort: Boolean, onSubmit: (String, Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        PhoneSetupCopy.MANUAL_TOGGLE, Modifier.padding(top = 8.dp).clickable { open = !open }.padding(vertical = 4.dp).testTag("setup-manual"),
        fontSize = 13.sp, color = MinaTheme.colors.accent,
    )
    if (!open) return
    Text(PhoneSetupCopy.MANUAL_HINT, fontSize = 12.5.sp, lineHeight = 17.sp, color = MinaTheme.colors.ink3)
    OutlinedTextField(
        code, { code = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-code"),
        label = { Text(PhoneSetupCopy.CODE_LABEL) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    if (askPort) {
        OutlinedTextField(
            port, { port = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-port"),
            label = { Text(PhoneSetupCopy.PORT_LABEL) }, supportingText = { Text(PhoneSetupCopy.PORT_HINT) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    error?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = MinaTheme.colors.bad) }
    PillButton(PhoneSetupCopy.SUBMIT, primary = true, tag = "setup-submit") {
        val c = PairingCode.normalize(code)
        val p = if (askPort && port.isNotBlank()) PairingCode.port(port) else null
        error = when {
            c == null -> PhoneSetupCopy.CODE_INVALID
            askPort && port.isNotBlank() && p == null -> PhoneSetupCopy.PORT_INVALID
            else -> null
        }
        if (error == null && c != null) onSubmit(c, p)
    }
}

/** Wireless debugging port filled in by the user: "IP address and port" may be pasted in the entire paragraph (taken after the last colon), with spaces or full-width numbers; if it is not 1-65535, it will be null. */
internal object ConnectPortInput {
    fun parse(text: String): Int? {
        val tail = text.substringAfterLast(':').substringAfterLast('：')
        val digits = tail.filterNot { it.isWhitespace() }.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        return PairingCode.port(digits)
    }
}

/** What to do when you can't connect: Fill in the port on the "Wireless Debugging" page and click "Connect". */
@Composable
private fun ConnectPort(onConnect: (Int) -> Unit) {
    var port by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(
        port, { port = it; invalid = false }, Modifier.fillMaxWidth().padding(top = 10.dp).keepsKeyboard().testTag("setup-connect-port"),
        label = { Text(PhoneSetupCopy.CONNECT_PORT_LABEL) },
        supportingText = { Text(if (invalid) PhoneSetupCopy.PORT_INVALID else PhoneSetupCopy.CONNECT_PORT_HINT, color = if (invalid) MinaTheme.colors.bad else MinaTheme.colors.ink3) },
        singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    PillButton(PhoneSetupCopy.CONNECT_PORT_SUBMIT, primary = false, tag = "setup-connect-port-submit") {
        val p = ConnectPortInput.parse(port)
        invalid = p == null
        if (p != null) onConnect(p)
    }
}

@Composable
private fun WorkingLine(scanning: Boolean) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MinaTheme.colors.accent, strokeWidth = 2.dp)
        Text(
            if (scanning) PhoneSetupCopy.SCANNING_PORTS else PhoneSetupCopy.WORKING, Modifier.testTag("setup-working"),
            fontSize = 13.5.sp, color = MinaTheme.colors.ink2,
        )
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, tag: String? = null, onClick: () -> Unit) {
    MinaButton(
        text, onClick, Modifier.padding(top = 10.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Small,
    )
}

// ---------- Setting page "Advanced" ----------

/** There are three lines of built-in assistant in the "Advanced" group: status, re-pairing, and unpairing (confirmation required). It cannot be canceled if it is not paired. */
@Composable
fun PhoneHelperSection(status: BuiltinShellStatus, onRepair: () -> Unit, onUnpair: () -> Unit, usbDebugging: Boolean = true) {
    var confirming by remember { mutableStateOf(false) }
    GroupCard {
        val (value, tone) = PhoneSetupCopy.statusValue(status)
        ListRow(MinaIcons.Phone, PhoneSetupCopy.ROW_TITLE, PhoneSetupCopy.statusLine(status, usbDebugging), first = true) {
            RowValue(value, tone)
        }
        ListRow(MinaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, onClick = onRepair) {
            RowChevron()
        }
        val paired = status != BuiltinShellStatus.NotPaired
        ListRow(
            MinaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
            titleColor = if (paired) MinaTheme.colors.bad else MinaTheme.colors.ink3, tone = if (paired) TileTone.Bad else TileTone.Neutral, onClick = if (paired) ({ confirming = true }) else null,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = { TextButton({ confirming = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MinaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- Conversation page ----------

/** When the task requires the use of a mobile phone and the mobile phone operation is not ready, the line below the task card. */
@Composable
fun PhoneSetupLine(onOpen: () -> Unit) {
    val shape = MinaShapes.Card
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).cardSurface(MinaTheme.colors, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(PhoneSetupCopy.DEGRADED_LINE, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MinaTheme.colors.ink2)
        Text(
            PhoneSetupCopy.DEGRADED_ACTION, Modifier.clickable(onClick = onOpen).padding(4.dp).testTag("phone-setup-open"),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MinaTheme.colors.accent,
        )
    }
}
