package com.meenai.mina.cap.phone.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meenai.mina.R
import com.meenai.mina.core.shell.BuiltinShellFix
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.PairingChannelHealth
import com.meenai.mina.core.shell.PairingDiscovery
import com.meenai.mina.core.shell.PhoneSetupFlow
import com.meenai.mina.ui.MainActivity
import com.meenai.mina.ui.PhoneSetupCopy
import com.meenai.mina.setup.PairingCodeReceiver

/** What should be displayed in the pairing notification at this time. */
sealed interface PairingNotice {
    /** "Enter pairing code" with input box; [error] is the reason why it failed last time and what to do. The input box remains as it is. */
    data class EnterCode(val port: Int?, val error: String? = null) : PairingNotice
    data object Working : PairingNotice
    data object Done : PairingNotice
    /** Failed to connect after pairing: Go back to Mina to see what to do. */
    data class Problem(val title: String) : PairingNotice

    companion object {
        /**
         * Only used during the period when the user is pairing (when [PairingCoordinator] is on). The
         * input box will pop up only when the pairing window is found; failures in the pairing phase
         * (code incorrect, expired, window closed) will be left in the notification and refilled;
         * failures to re-pair (pairing failure) will also be given an input box after the pairing window
         * is found, which is the same as the barrier-free code reading recognition
         * (PairingScreen.attempt); for other failures, please return to Mina.
         */
        fun of(status: BuiltinShellStatus, discovery: PairingDiscovery): PairingNotice? {
            val port = (discovery as? PairingDiscovery.Found)?.port
            return when (status) {
                BuiltinShellStatus.Running -> Done
                BuiltinShellStatus.Connecting -> Working
                is BuiltinShellStatus.Failed -> when {
                    status.reason.duringPairing -> EnterCode(port, PhoneSetupCopy.notifyRetry(status.reason))
                    status.fix == BuiltinShellFix.RePair && port != null -> EnterCode(port)
                    else -> Problem(PhoneSetupCopy.failure(status.reason).title)
                }
                BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> port?.let { EnterCode(it) }
            }
        }

        /** When reading the pairing code without any obstacles, the code-filling notification will appear after so long. It has probably already been matched, so don't bother me. */
        const val AUTO_READ_GRACE_MS = 5_000L
        /** If no one fills in the code notification after it pops up, it will pop up again after such a long time; it can be up to [MAX_REALERTS] times without refreshing the screen. */
        val REALERT_MS = listOf(8_000L, 20_000L, 40_000L)
        val MAX_REALERTS = REALERT_MS.size

        /** How long to wait before issuing [notice]. When accessibility is not turned on, it will pop up when you find the pairing window, no waiting. */
        fun delayBefore(notice: PairingNotice, autoRead: Boolean): Long =
            if (notice is EnterCode && autoRead) AUTO_READ_GRACE_MS else 0L

        /** It has been reminded [sent] times, how long will it be before the next time; null means no more reminders. */
        fun realertAfter(notice: PairingNotice, sent: Int): Long? = if (notice is EnterCode) REALERT_MS.getOrNull(sent) else null

        /**
         * [PairingCoordinator] A calculation when following the status update notification: which item
         * should be displayed at this time, and whether to stop following it after it is displayed. When
         * the code is filled in and pairing has not started ([submitted]), it will always be "pairing",
         * and it will not end at this time (the same is true if the assistant is already running, and
         * the status will change after pairing starts); when "re-pairing" is performed, the old running
         * status is not considered to be paired ([PhoneSetupFlow.effectiveStatus]), and the assistant
         * will not end until re-pairing is running.
         */
        fun follow(status: BuiltinShellStatus, discovery: PairingDiscovery, repairing: Boolean, submitted: Boolean): Pair<PairingNotice?, Boolean> =
            if (submitted) Working to false
            else of(PhoneSetupFlow.effectiveStatus(status, repairing), discovery) to (status == BuiltinShellStatus.Running && !repairing)
    }
}

/** There is already a pairing channel in the system; [userSet] is the importance of the user changing it. */
data class ChannelSnapshot(val id: String, val importance: Int, val userSet: Boolean)

/** How to deal with paired channels: [Create] creates a new [id] (high priority); [Use] uses the existing [id], [health] is what it looks like now. */
sealed interface ChannelPlan {
    val id: String
    val health: PairingChannelHealth

    data class Create(override val id: String) : ChannelPlan {
        override val health: PairingChannelHealth get() = PairingChannelHealth.Ready
    }

    data class Use(override val id: String, override val health: PairingChannelHealth) : ChannelPlan
}

/**
 * Selection of paired channels (pure function, easy for testing). After the channel importance is
 * established, the app cannot change it: if the old version is not high enough and has not been touched
 * by users, it will be rebuilt with a new ID; if the user has lowered or turned it off by himself, it
 * will not be changed back and will be prompted on the boot page.
 */
object PairingChannel {
    /** The channel IDs used are newer as they go forward; for newly installed channels, just use the last one. */
    val IDS = listOf("pairing_code", "pairing_alert")
    /** For earlier versions of channels, delete them as soon as you see them to avoid adding another item in the settings. */
    const val LEGACY = "pairing"

    fun plan(existing: List<ChannelSnapshot>): ChannelPlan {
        val current = existing.filter { it.id in IDS }.maxByOrNull { IDS.indexOf(it.id) }
            ?: return ChannelPlan.Create(IDS.last())
        val newer = IDS.getOrNull(IDS.indexOf(current.id) + 1)
        return when {
            current.importance == NotificationManager.IMPORTANCE_NONE -> ChannelPlan.Use(current.id, PairingChannelHealth.Off)
            current.importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelPlan.Use(current.id, PairingChannelHealth.Ready)
            current.userSet -> ChannelPlan.Use(current.id, PairingChannelHealth.Lowered)
            newer != null -> ChannelPlan.Create(newer)
            else -> ChannelPlan.Use(current.id, PairingChannelHealth.Lowered)
        }
    }
}

/**
 * Pairing notification: high priority, with sound and vibration (listen to the system during Do Not
 * Disturb), pops up above the pairing window of the system settings; the input box uses RemoteInput, and
 * the user does not need to leave the pairing window. The entered code is given to [PairingCodeInput].
 */
object PairingNotifier {
    const val KEY_CODE = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    private const val ID = 4401
    private const val DONE_TIMEOUT_MS = 8_000L
    /** The code-filling notification can be kept for this long at most (the same length as the matching window), and the app will not stay hanging even if it is killed. */
    private const val INPUT_TIMEOUT_MS = 10 * 60_000L

    /** Make sure there is a paired channel and go back to the one you want to use (the boot page prompts you to open its settings page). */
    fun ensureChannel(context: Context): ChannelPlan {
        val nm = context.getSystemService(NotificationManager::class.java)
        val existing = PairingChannel.IDS.mapNotNull { id ->
            nm.getNotificationChannel(id)?.let { ChannelSnapshot(id, it.importance, it.hasUserSetImportance()) }
        }
        val plan = PairingChannel.plan(existing)
        if (plan is ChannelPlan.Create) {
            nm.createNotificationChannel(
                NotificationChannel(plan.id, PhoneSetupCopy.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(false)
                },
            )
        }
        (PairingChannel.IDS + PairingChannel.LEGACY).filter { it != plan.id }.forEach { id ->
            if (nm.getNotificationChannel(id) != null) nm.deleteNotificationChannel(id)
        }
        return plan
    }

    fun show(context: Context, notice: PairingNotice) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = ensureChannel(context).id
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
        when (notice) {
            is PairingNotice.EnterCode -> {
                val input = RemoteInput.Builder(KEY_CODE).setLabel(PhoneSetupCopy.NOTIFY_INPUT_LABEL).setAllowFreeFormInput(true).build()
                // RemoteInput needs to be variable to fill in the results in the Intent.
                val reply = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, PairingCodeReceiver::class.java).apply { notice.port?.let { putExtra(EXTRA_PORT, it) } },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val action = Notification.Action.Builder(null, PhoneSetupCopy.NOTIFY_ACTION, reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                // The screen can be filled in directly without being asked to unlock it first.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
                val text = notice.error ?: PhoneSetupCopy.NOTIFY_ENTER_TEXT
                b.setContentTitle(PhoneSetupCopy.NOTIFY_ENTER_TITLE)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    // It will ring and pop up every time, including reminders when no one fills in and
                    // prompts after incorrect entries.
                    .setOnlyAlertOnce(false)
                    .setTimeoutAfter(INPUT_TIMEOUT_MS)
                    .addAction(action.build())
                if (notice.port != null) b.setSubText(PhoneSetupCopy.NOTIFY_WINDOW_FOUND)
            }
            PairingNotice.Working -> b.setContentTitle(PhoneSetupCopy.NOTIFY_WORKING).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
            PairingNotice.Done -> b.setContentTitle(PhoneSetupCopy.NOTIFY_DONE_TITLE).setContentText(PhoneSetupCopy.NOTIFY_DONE_TEXT)
                .setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(DONE_TIMEOUT_MS)
            is PairingNotice.Problem -> b.setContentTitle(notice.title).setContentText(PhoneSetupCopy.NOTIFY_PROBLEM_TEXT).setAutoCancel(true)
        }
        // The system will discard the notification if it is not allowed; the guide page asks the user
        // for permission before the pairing step.
        runCatching { nm.notify(ID, b.build()) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
