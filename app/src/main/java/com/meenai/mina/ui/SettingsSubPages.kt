package com.meenai.mina.ui

import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.ThemeMode
import com.meenai.mina.ui.components.MinaSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.meenai.mina.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.settings.ChannelRow
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.Problem
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.settings.notificationOverview
import com.meenai.mina.task.ParallelLimit
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.RadioRow
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.core.cap.phone.OperatingPace

// ---------- Mobile phone operation ----------

/** Settings › Phone operations: Permissions and pairing status, as well as re-pairing and unpairing. Which item to click is handled in the same way as before, leaving it to [onProblem]. */
@Composable
fun PhoneOpsScreen(
    status: SystemStatus,
    androidRelease: String,
    onProblem: (Problem) -> Unit,
    onOpenNotifications: () -> Unit,
    onRepair: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
) {
    val check = Prerequisites.check(status, hasMainModel = true)
    var confirmUnpair by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_phone_ops), onBack) {
        GroupLabel(stringResource(R.string.settings_status))
        GroupCard {
            val (a11yText, a11yTone) = StatusCopy.accessibilityValue(status.accessibility)
            ListRow(
                MinaIcons.Hand, stringResource(R.string.settings_a11y), stringResource(R.string.settings_a11y_sub), first = true,
                onClick = check.problems.firstOrNull { it is Problem.Accessibility }?.let { { onProblem(it) } },
            ) { RowValue(a11yText, a11yTone) }
            // The functions of mobile phones below Android 12 are not available and are not listed in
            // this row (the system version row has been explained)
            if (status.sdkInt >= Prerequisites.MIN_SDK) {
                val helper = status.builtinShell
                val (hText, hTone) = PhoneSetupCopy.statusValue(helper)
                val reconnect = PhoneSetupCopy.canReconnect(helper)
                ListRow(
                    MinaIcons.Link, PhoneSetupCopy.ROW_TITLE,
                    if (reconnect && helper is BuiltinShellStatus.Failed) PhoneSetupCopy.failure(helper.reason).title
                    else PhoneSetupCopy.statusLine(helper, status.usbDebugging),
                    onClick = { onProblem(Problem.PhoneHelper(helper)) },
                ) { if (reconnect) RowValue(PhoneSetupCopy.RECONNECT, ValueTone.Link) else RowValue(hText, hTone) }
            }
            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            ListRow(MinaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_progress_sub), onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
            val tooOld = status.sdkInt < Prerequisites.MIN_SDK
            val focusWarning = Problem.FocusMayLeak in check.problems
            ListRow(
                MinaIcons.Phone, stringResource(R.string.settings_system_version),
                if (focusWarning) stringResource(R.string.settings_android_focus, androidRelease) else stringResource(R.string.settings_android, androidRelease),
                onClick = when {
                    tooOld -> ({ onProblem(Problem.SystemTooOld) })
                    focusWarning -> ({ onProblem(Problem.FocusMayLeak) })
                    else -> null
                },
            ) {
                when {
                    tooOld -> RowValue(stringResource(R.string.pill_unsupported), ValueTone.Bad)
                    focusWarning -> RowValue(stringResource(R.string.pill_available), ValueTone.Plain)
                    else -> RowValue(stringResource(R.string.settings_meets), ValueTone.Ok)
                }
            }
        }
        if (status.sdkInt >= Prerequisites.MIN_SDK) {
            GroupLabel(stringResource(R.string.phone_channel))
            GroupCard {
                ListRow(MinaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, first = true, onClick = onRepair) {
                    RowChevron()
                }
                val paired = status.builtinShell != BuiltinShellStatus.NotPaired
                ListRow(
                    MinaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
                    titleColor = if (paired) MinaTheme.colors.bad else MinaTheme.colors.ink3,
                    tone = if (paired) TileTone.Bad else TileTone.Neutral,
                    onClick = if (paired) ({ confirmUnpair = true }) else null,
                )
            }
            FooterNote(PhoneSetupCopy.usageNote(status.helperRestart))
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = {
                TextButton({ confirmUnpair = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MinaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- notify ----------

/**
 * Settings › Notifications: Just view the status and change it in the system. The top is the App
 * notification master switch, and the three task notification channels below have one line each (when
 * will it be sent + will it pop up now). Whichever line is clicked will open the corresponding system
 * settings page by [onFix] (if the master switch is not turned on, first request permission or open the
 * application notification page).
 */
@Composable
fun NotificationsScreen(status: SystemStatus, onFix: (Fix) -> Unit, onBack: () -> Unit) {
    val o = status.notificationOverview()
    PageScaffold(StatusCopy.NOTIFICATIONS, onBack) {
        GroupCard {
            ListRow(
                MinaIcons.Bell, stringResource(R.string.settings_notif_master),
                if (o.appAllowed) StatusCopy.NOTIF_APP_SUBTITLE_ON else StatusCopy.NOTIF_APP_SUBTITLE_OFF,
                first = true, tone = if (o.appAllowed) TileTone.Neutral else TileTone.Bad,
                onClick = if (o.appAllowed) null else ({ onFix(Fix.AllowNotifications) }),
            ) {
                if (o.appAllowed) RowValue(stringResource(R.string.settings_allowed), ValueTone.Ok) else RowValue(StatusCopy.HEADS_UP_ACTION, ValueTone.Link)
            }
        }
        GroupLabel(stringResource(R.string.settings_task_notifs))
        GroupCard {
            ChannelRowItem(StatusCopy.ATTENTION_CHANNEL, StatusCopy.NOTIF_ATTENTION_WHEN, o.attention, Fix.OpenAttentionChannel, onFix, first = true)
            ChannelRowItem(StatusCopy.RESULT_CHANNEL, StatusCopy.NOTIF_RESULT_WHEN, o.result, Fix.OpenResultChannel, onFix)
            ChannelRowItem(StatusCopy.PROGRESS_CHANNEL, StatusCopy.NOTIF_PROGRESS_WHEN, o.progress, Fix.OpenProgressChannel, onFix)
        }
        FooterNote(StatusCopy.NOTIF_FOOTER)
    }
}

@Composable
private fun ChannelRowItem(title: String, whenText: String, row: ChannelRow, fix: Fix, onFix: (Fix) -> Unit, first: Boolean = false) {
    val (text, tone) = StatusCopy.channelLevelValue(row)
    ListRow(
        MinaIcons.Bell, title, whenText, first = first,
        tone = if (row.warn) TileTone.Warn else TileTone.Neutral,
        onClick = { onFix(fix) },
    ) {
        RowValue(text, tone)
        RowChevron()
    }
}

// ---------- While working ----------

/** Parallel task settings: the upper limit set by the user, [max] (1–3), and the [limit] allowed by the device at the moment. */
data class ParallelSetting(val max: Int, val limit: ParallelLimit)

/**
 * Settings › When working: the screen is always on, the operated App is muted (both are on by default,
 * and can be turned off); when [parallel] is not empty (the experiment switch is on), there is one more
 * line for the number of tasks being performed simultaneously. The following group is the operating
 * rhythm: fast / standard (default) / human-like, which will take effect after selecting the next task.
 */
@Composable
fun DuringWorkScreen(
    keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit,
    muteTarget: Boolean, onMuteTarget: (Boolean) -> Unit,
    parallel: ParallelSetting?, onParallelMax: (Int) -> Unit, onBack: () -> Unit,
    operatingPace: OperatingPace = OperatingPace.DEFAULT, onOperatingPace: (OperatingPace) -> Unit = {},
    selfConfig: Boolean = false, onSelfConfig: (Boolean) -> Unit = {},
) {
    PageScaffold(stringResource(R.string.settings_during_work), onBack) {
        GroupCard {
            ListRow(MinaIcons.Sun, stringResource(R.string.settings_keep_on), stringResource(R.string.settings_keep_on_sub), first = true, onClick = { onKeepScreenOn(!keepScreenOn) }) {
                MinaSwitch(keepScreenOn, onKeepScreenOn, Modifier.testTag("work-keep-screen-on"))
            }
            ListRow(MinaIcons.Mute, stringResource(R.string.settings_mute), stringResource(R.string.settings_mute_sub), onClick = { onMuteTarget(!muteTarget) }) {
                MinaSwitch(muteTarget, onMuteTarget, Modifier.testTag("work-mute-target"))
            }
            parallel?.let { ParallelRow(it, onParallelMax) }
        }
        // Allow Mina to change its own settings: off by default. Only after turning it on will there be
        // settings_list and settings_set in the task.
        GroupCard {
            ListRow(
                MinaIcons.Spark, stringResource(R.string.settings_self_config),
                stringResource(R.string.settings_self_config_sub),
                first = true,
                onClick = { onSelfConfig(!selfConfig) },
            ) {
                MinaSwitch(selfConfig, onSelfConfig, Modifier.testTag("work-self-config"))
            }
        }
        GroupLabel(stringResource(R.string.settings_pace))
        GroupCard {
            OperatingPace.entries.forEachIndexed { i, p ->
                RadioRow(paceLabel(p), selected = p == operatingPace, onClick = { onOperatingPace(p) }, subtitle = paceDetail(p), first = i == 0)
            }
        }
        FooterNote(stringResource(R.string.settings_pace_foot))
    }
}

@Composable
private fun paceLabel(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast
        OperatingPace.STANDARD -> R.string.settings_pace_standard
        OperatingPace.HUMAN -> R.string.settings_pace_human
    },
)

@Composable
private fun paceDetail(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast_detail
        OperatingPace.STANDARD -> R.string.settings_pace_standard_detail
        OperatingPace.HUMAN -> R.string.settings_pace_human_detail
    },
)

/** Perform several tasks at the same time: if the device does not support it, it will be grayed out and the reason will be written. The upper limit is fixed at 1. */
@Composable
private fun ParallelRow(p: ParallelSetting, onMax: (Int) -> Unit) {
    val supported = p.limit.reason == null
    ListRow(
        MinaIcons.Phone, stringResource(R.string.settings_parallel),
        subtitle = p.limit.reason ?: stringResource(R.string.settings_parallel_sub),
        titleColor = if (supported) MinaTheme.colors.ink else MinaTheme.colors.ink3,
    ) {
        if (!supported) {
            RowValue(trPlural(R.plurals.settings_count_tasks, 1))
            return@ListRow
        }
        Text(
            "−", Modifier.clickable(enabled = p.max > 1) { onMax(p.max - 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max > 1) MinaTheme.colors.accent else MinaTheme.colors.ink3,
        )
        RowValue(trPlural(R.plurals.settings_count_tasks, p.max))
        Text(
            "+", Modifier.clickable(enabled = p.max < 3) { onMax(p.max + 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max < 3) MinaTheme.colors.accent else MinaTheme.colors.ink3,
        )
    }
}

// ---------- Suspension Capsule ----------

/** Settings › Suspended capsule text. */
object CapsuleSettingsCopy {
    val TITLE: String get() = tr(R.string.capsule_settings_title)
    val SUBTITLE: String get() = tr(R.string.capsule_settings_sub)
    val FOOTNOTE: String get() = tr(R.string.capsule_settings_foot)
    /** The system may close the background app and the capsule disappears: please allow the user to disable it in the system settings. */
    val BACKGROUND: String get() = tr(R.string.capsule_settings_background)

    fun label(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always)
    }

    fun detail(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off_detail)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks_detail)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always_detail)
    }
}

/** Settings › Suspension Capsule: Off / Show when there is a task (default) / Always show, it will take effect on the spot if selected. */
@Composable
fun CapsuleScreen(mode: CapsuleMode, onMode: (CapsuleMode) -> Unit, onBack: () -> Unit) {
    PageScaffold(CapsuleSettingsCopy.TITLE, onBack) {
        GroupCard {
            CapsuleMode.entries.forEachIndexed { i, m ->
                RadioRow(
                    CapsuleSettingsCopy.label(m), selected = m == mode, onClick = { onMode(m) },
                    subtitle = CapsuleSettingsCopy.detail(m), first = i == 0,
                )
            }
        }
        FooterNote(CapsuleSettingsCopy.FOOTNOTE)
        FooterNote(CapsuleSettingsCopy.BACKGROUND)
    }
}

/** Settings › Language: Follow the system, Simplified Chinese, Traditional Chinese, English; change it immediately after selecting it, and the interface will be rebuilt accordingly. */
@Composable
fun LanguageScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    // Follow the settings: the appearance has been changed in the task, and this page also matches
    val themeMode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    PageScaffold(stringResource(R.string.appearance_title), onBack) {
        // Language and appearance are put on the same page: both are "what does the interface look like"
        GroupLabel(stringResource(R.string.appearance_group))
        GroupCard {
            ThemeMode.entries.forEachIndexed { idx, m ->
                RadioRow(
                    appearanceLabel(m), selected = m == themeMode, first = idx == 0,
                    subtitle = if (m == ThemeMode.System) stringResource(R.string.appearance_system_detail) else null,
                    onClick = {
                        // The interface changes color on the spot according to the settings, without
                        // rebuilding, and the page and scroll position are not lost.
                        if (m != themeMode) flags.setThemeMode(m)
                    },
                )
            }
        }
        GroupLabel(stringResource(R.string.language_title))
        GroupCard {
            AppLanguage.entries.forEachIndexed { i, l ->
                RadioRow(
                    languageLabel(l), selected = l == language, onClick = { if (l != language) onLanguage(l) },
                    subtitle = if (l == AppLanguage.System) stringResource(R.string.language_system_detail) else null, first = i == 0,
                )
            }
        }
    }
}

/** The name of the language: Each language always uses its own writing method (Japanese, Español...), and the "following system" is written according to the current language. */
@Composable
fun languageLabel(l: AppLanguage): String = stringResource(
    when (l) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.English -> R.string.language_english
        AppLanguage.Hindi -> R.string.language_hindi
    },
)

// ---------- Privacy and Data ----------

/** Settings › Website Login. */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- Advanced ----------

/** Settings › Files and Computing Environment: Allow script networking, network proxy, used space (clean workspace by conversation), reset running environment. */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** Settings › Remembered operation routes: [count], all can be cleared (confirm first); [count] is empty, which means it has not been read out yet. */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                MinaIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = MinaType.Value, color = MinaTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Settings › Recovery and Repair: Several authorization switches for mobile phone operation. Below is the entrance to compatibility self-check. */
@Composable
fun RecoveryScreen(onBack: () -> Unit, onOpenSelfCheck: () -> Unit = {}) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack) {
        RecoverySwitches()
        GroupCard {
            ListRow(
                MinaIcons.Bug, stringResource(R.string.selfcheck_title), stringResource(R.string.selfcheck_row_sub), first = true,
                onClick = onOpenSelfCheck,
            ) { RowChevron() }
        }
    }
}

/** The current representation of the appearance (used both on the settings page and on this line). */
fun appearanceLabel(mode: ThemeMode): String = tr(
    when (mode) {
        ThemeMode.System -> R.string.appearance_system
        ThemeMode.Light -> R.string.appearance_light
        ThemeMode.Dark -> R.string.appearance_dark
    },
)
