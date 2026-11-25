package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.core.agent.RunProgress
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.ChannelLevel
import com.meenai.mina.settings.ChannelRow
import com.meenai.mina.settings.NotificationSummary
import com.meenai.mina.settings.Problem
import com.meenai.mina.core.shell.BuiltinShellFailure
import com.meenai.mina.core.shell.BuiltinShellFix
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.ui.components.ValueTone

/** Repair actions can be performed directly on the settings page. */
enum class Fix {
    OpenAccessibility, AllowNotifications, AddModel,
    /** Directly open the settings page of the "Need Your Handling"/"Task Result"/"Task Progress" channel and turn on "Pop up on screen"; the return application notification general page cannot be opened. */
    OpenAttentionChannel, OpenResultChannel, OpenProgressChannel,
    /** "Pairing" notification channel settings page: The pairing code must be filled in this type of notification. */
    OpenPairingChannel,
    /** The "Application Information" page of this App: When the system blocks accessibility, restricted settings are allowed here. */
    OpenAppInfo,
    // Built-in assistant
    OpenPhoneSetup, RepairPhoneHelper, RetryPhoneHelper, UnpairPhoneHelper,
    OpenAboutPhone, OpenDeveloperOptions, OpenUsbDebugging, OpenWirelessDebugging, OpenWifi,
}

/** A line of text for the top banner and a link to the right. */
data class BannerCopy(val title: String, val detail: String, val link: String)

/** Instructions when clicking on the problem: use plain language to explain clearly what it is and how to do it; [fix] is empty, which means there is no one-click repair. */
data class Explanation(val title: String, val body: String, val action: String?, val fix: Fix?)

object StatusCopy {
    /** Sayings on the cards and notifications when doing several steps in a row without going through the model. */
    fun progress(p: RunProgress): String = when (p) {
        is RunProgress.Replay -> tr(R.string.progress_replay, p.step, p.total)
        is RunProgress.Batch -> trPlural(R.plurals.progress_batch, p.steps)
        // Just waiting for another task, no need for the user to do anything
        is RunProgress.WaitingForApp -> tr(R.string.progress_waiting_app, p.app)
    }

    /** The product, amount, and address of the payment acceptance are one item per line; if there is none, it will be null. Cards and notifications are shared. */
    fun paymentLines(item: String?, amount: String?, address: String?): String? =
        listOfNotNull(
            item?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_item, it) },
            amount?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_amount, it) },
            address?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }

    fun accessibilityValue(s: AccessibilityState): Pair<String, ValueTone> = when (s) {
        AccessibilityState.On -> tr(R.string.state_on) to ValueTone.Ok
        AccessibilityState.Off -> tr(R.string.state_off) to ValueTone.Bad
        AccessibilityState.EnabledNotRunning -> tr(R.string.state_not_running) to ValueTone.Bad
    }

    /** No need to explain, just click and fix the problem directly. */
    fun directFix(p: Problem): Fix? = when (p) {
        Problem.NotificationsOff -> Fix.AllowNotifications
        is Problem.AttentionLowered -> headsUpFix(p)
        Problem.NoModel -> Fix.AddModel
        // The introductory page explains each step clearly without any further explanations.
        is Problem.PhoneHelper -> when {
            PhoneSetupCopy.canReconnect(p.status) -> Fix.RetryPhoneHelper
            // This Wi-Fi does not allow wireless debugging yet: go ahead and turn it on yourself (the
            // system will ask if you want to allow it)
            (p.status as? BuiltinShellStatus.Failed)?.fix == BuiltinShellFix.OpenWirelessDebugging -> Fix.OpenWirelessDebugging
            else -> Fix.OpenPhoneSetup
        }
        else -> null
    }

    // ---- Settings › Notifications ----
    /** That line of small words after stopping and before the explanation is finished (the conversation page is the same as the floating card). */
    val STOP_SUMMARIZING: String get() = tr(R.string.stop_summarizing)
    val NOTIFICATIONS: String get() = tr(R.string.notifications_title)
    val PROGRESS_CHANNEL: String get() = tr(R.string.channel_progress)

    fun notificationSummary(s: NotificationSummary): Pair<String, ValueTone> = when (s) {
        NotificationSummary.Normal -> tr(R.string.notif_normal) to ValueTone.Ok
        is NotificationSummary.Lowered -> trPlural(R.plurals.notif_lowered, s.count) to ValueTone.Warn
        NotificationSummary.AllOff -> tr(R.string.state_turned_off) to ValueTone.Bad
    }

    fun channelLevelValue(row: ChannelRow): Pair<String, ValueTone> = when (row.level) {
        ChannelLevel.Off -> tr(R.string.state_turned_off) to if (row.warn) ValueTone.Warn else ValueTone.Plain
        ChannelLevel.HeadsUp -> tr(R.string.notif_heads_up) to ValueTone.Ok
        ChannelLevel.Quiet -> tr(R.string.notif_quiet) to if (row.warn) ValueTone.Warn else ValueTone.Plain
    }

    val NOTIF_APP_SUBTITLE_ON: String get() = tr(R.string.notif_app_on)
    val NOTIF_APP_SUBTITLE_OFF: String get() = tr(R.string.notif_app_off)
    val NOTIF_ATTENTION_WHEN: String get() = tr(R.string.notif_attention_when)
    val NOTIF_RESULT_WHEN: String get() = tr(R.string.notif_result_when)
    val NOTIF_PROGRESS_WHEN: String get() = tr(R.string.notif_progress_when)
    val NOTIF_FOOTER: String get() = tr(R.string.notif_footer)

    val ATTENTION_CHANNEL: String get() = tr(R.string.channel_attention)
    val RESULT_CHANNEL: String get() = tr(R.string.channel_result)
    val HEADS_UP_ACTION: String get() = tr(R.string.heads_up_action)

    /** Of the two channels that have been turned down, "Need You to Handle" is dealt with first. */
    fun headsUpFix(p: Problem.AttentionLowered): Fix = if (p.attention) Fix.OpenAttentionChannel else Fix.OpenResultChannel

    private fun headsUpTitle(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_title, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    private fun headsUpBody(p: Problem.AttentionLowered): String {
        val lead = if (p.attention) tr(R.string.heads_up_lead_attention) else tr(R.string.heads_up_lead_result)
        val also = if (p.attention && p.result) tr(R.string.heads_up_also, RESULT_CHANNEL) else ""
        return tr(R.string.heads_up_body, lead, also, HEADS_UP_ACTION)
    }

    private fun headsUpPath(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_path, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    fun banner(p: Problem): BannerCopy = when (p) {
        Problem.SystemTooOld -> BannerCopy(tr(R.string.banner_too_old), tr(R.string.banner_too_old_detail), tr(R.string.learn_more))
        is Problem.PhoneHelper -> when (val st = p.status) {
            BuiltinShellStatus.NotPaired -> BannerCopy(tr(R.string.banner_not_paired), tr(R.string.banner_not_paired_detail), tr(R.string.chat_go_settings))
            BuiltinShellStatus.WirelessDebuggingOff -> BannerCopy(tr(R.string.banner_wireless_off), tr(R.string.banner_wireless_off_detail), tr(R.string.heads_up_action))
            BuiltinShellStatus.Connecting -> BannerCopy(tr(R.string.banner_connecting), tr(R.string.banner_connecting_detail), tr(R.string.view))
            is BuiltinShellStatus.Failed ->
                if (st.reason == BuiltinShellFailure.NoWifi) BannerCopy(tr(R.string.banner_paused), PhoneSetupCopy.NO_WIFI_LINE, tr(R.string.view))
                // The computer startup assistant has stopped: the title tells the truth, the following
                // sentence tells how to restart it
                else if (st.reason == BuiltinShellFailure.UsbHelperStopped) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.failure(st.reason).body, tr(R.string.view),
                )
                else if (st.reason == BuiltinShellFailure.WifiNotAllowed) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.WIFI_NOT_ALLOWED_LINE, tr(R.string.heads_up_action),
                )
                else BannerCopy(
                    tr(R.string.banner_failed), PhoneSetupCopy.failure(st.reason).title,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.fix_it),
                )
            BuiltinShellStatus.Running -> error("not a problem")
        }
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> BannerCopy(tr(R.string.banner_a11y_off), tr(R.string.banner_a11y_off_detail), tr(R.string.turn_on_go))
            // Outdated services that have not been unlocked after booting cannot be bound to them: they
            // will be restored by themselves after being unlocked, and the user will not be allowed to
            // turn them off and on again.
            AccessibilityState.EnabledNotRunning ->
                if (p.userLocked) BannerCopy(tr(R.string.banner_a11y_locked), tr(R.string.banner_a11y_locked_detail), tr(R.string.view))
                else BannerCopy(tr(R.string.banner_a11y_not_running), tr(R.string.banner_a11y_not_running_detail), tr(R.string.fix_it))
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> BannerCopy(tr(R.string.banner_no_model), tr(R.string.banner_no_model_detail), tr(R.string.add))
        Problem.NotificationsOff -> BannerCopy(tr(R.string.banner_notif_off), tr(R.string.banner_notif_off_detail), tr(R.string.allow))
        is Problem.AttentionLowered -> BannerCopy(headsUpTitle(p), headsUpBody(p) + "\n" + headsUpPath(p), HEADS_UP_ACTION)
        Problem.FocusMayLeak -> BannerCopy(tr(R.string.banner_focus), tr(R.string.banner_focus_detail), tr(R.string.learn_more))
    }

    fun explain(p: Problem, androidRelease: String): Explanation = when (p) {
        Problem.SystemTooOld -> Explanation(
            tr(R.string.explain_too_old),
            tr(R.string.explain_too_old_body, androidRelease),
            null, null,
        )
        is Problem.PhoneHelper -> Explanation(
            PhoneSetupCopy.TITLE, PhoneSetupCopy.INTRO, PhoneSetupCopy.DEGRADED_ACTION, Fix.OpenPhoneSetup,
        )
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> Explanation(
                tr(R.string.explain_a11y_off),
                tr(R.string.explain_a11y_off_body),
                tr(R.string.chat_go_settings), Fix.OpenAccessibility,
            )
            AccessibilityState.EnabledNotRunning -> Explanation(
                tr(R.string.banner_a11y_not_running),
                tr(R.string.explain_a11y_not_running_body),
                tr(R.string.chat_go_settings), Fix.OpenAccessibility,
            )
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> Explanation(
            tr(R.string.explain_no_model), tr(R.string.explain_no_model_body),
            tr(R.string.add), Fix.AddModel,
        )
        Problem.FocusMayLeak -> Explanation(
            tr(R.string.explain_focus),
            tr(R.string.explain_focus_body, androidRelease),
            null, null,
        )
        is Problem.AttentionLowered -> Explanation(
            headsUpTitle(p), headsUpBody(p) + "\n\n" + headsUpPath(p), HEADS_UP_ACTION, headsUpFix(p),
        )
        Problem.NotificationsOff -> Explanation(
            tr(R.string.explain_notif), tr(R.string.explain_notif_body),
            tr(R.string.allow), Fix.AllowNotifications,
        )
    }

    /**
     * The description of the step of running the command on the task card is: "Processing file: <40 before command
     * word>", and "can connect to the Internet" when connected to the Internet. The running environment is
     * invisible to the user, not literally a sandbox.
     */
    fun sandboxCommand(command: String, network: Boolean): String = sandboxLine(tr(R.string.sandbox_command_prefix), command, 40, network)

    /** When running skill script: "Running script". */
    fun sandboxScript(network: Boolean): String = tr(R.string.sandbox_script) + if (network) " · $SANDBOX_ONLINE" else ""

    /** The progress reported by `mina progress` in the command. */
    fun sandboxProgress(text: String, network: Boolean): String = sandboxLine("", text, 60, network)

    val SANDBOX_ONLINE: String get() = tr(R.string.sandbox_online)

    /** The tool waits for the runtime environment to be ready. */
    val RUNTIME_PREPARING: String get() = tr(R.string.runtime_preparing)

    private fun sandboxLine(prefix: String, text: String, max: Int, network: Boolean): String {
        // Like `mina progress`, control characters, zero-width characters and rewriting direction
        // characters are removed. What is displayed on the card is the actual command.
        val one = com.meenai.mina.cap.sandbox.BridgeProtocol.cleanProgress(text.replace(Regex("\\s+"), " "))
        val cut = if (one.codePointCount(0, one.length) > max) one.substring(0, one.offsetByCodePoints(0, max)) + "…" else one
        return prefix + cut + if (network) " · $SANDBOX_ONLINE" else ""
    }
}
