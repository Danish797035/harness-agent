package com.meenai.mina.confirm

import com.meenai.mina.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.meenai.mina.R
import com.meenai.mina.task.alertQuietly
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction

/**
 * Confirm the text on cards and notifications. The fields are all written by the model: they can only be
 * used as plain text (no tags are parsed); line breaks and line delimiters are pressed into spaces, and
 * control characters, zero-width characters and text direction control characters are removed; "·" and
 * colons in the fields are replaced with other symbols, so that fake "· Amount:" fields cannot be
 * spelled out.
 */
object ConfirmationCopy {
    /** The number of words displayed in that line when collapsed. */
    const val CONTENT_MAX = 60
    /** The maximum number of words that can be displayed in expanded cards and notifications (the same as the upper limit when reporting). */
    const val DETAIL_MAX = 200
    /** Confirm the instructions on the card: You only need to agree to the operation of spending money, and only agree to this once at a time. */
    val NOTE: String get() = tr(R.string.confirm_note)

    fun actionLabel(action: IrrevAction): String = when (action) {
        IrrevAction.send -> tr(R.string.chat_send)
        IrrevAction.pay -> tr(R.string.confirm_pay)
        IrrevAction.delete -> tr(R.string.confirm_delete)
        IrrevAction.order -> tr(R.string.confirm_order)
        IrrevAction.submit -> tr(R.string.confirm_submit)
        IrrevAction.other -> tr(R.string.confirm_other)
    }

    fun title(r: ConfirmationRequest) = tr(R.string.confirm_title, actionLabel(r.action))

    /** The collapsed line: "In <App name> · Object: <To whom> · Content: <First 60 words> · Amount: <How much>"; empty fields are not written. */
    fun body(r: ConfirmationRequest, appName: String): String = fields(r, appName, CONTENT_MAX).joinToString(" · ")

    /** Expanded card and notification large text: one field per line, with complete content displayed (up to 200 words). */
    fun detail(r: ConfirmationRequest, appName: String): String = fields(r, appName, DETAIL_MAX).joinToString("\n")

    private fun fields(r: ConfirmationRequest, appName: String, contentMax: Int): List<String> {
        val content = plain(r.content).let { if (it.length > contentMax) it.take(contentMax) + "…" else it }
        return listOfNotNull(
            tr(R.string.confirm_in_app, plain(appName)),
            plain(r.target).takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_target, it) },
            content.takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_content, it) },
            r.amount?.let(::plain)?.takeIf { it.isNotEmpty() }?.let { tr(R.string.payment_amount, it) },
        )
    }

    private val INVISIBLE = Regex("[\\u061C\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val SPACES = Regex("[\\s\\u2028\\u2029\\u0085\\u00A0\\u3000]+")

    private fun plain(s: String) = s.replace(INVISIBLE, "").replace(SPACES, " ")
        .filterNot { Character.isISOControl(it) }
        .replace('·', '/').replace(':', '∶').replace('：', '∶')
        .trim()
}

/** Eye-catching notice for confirming the level: and taking over the same high-priority channel, buttons "Agree" and "Disagree". */
object ConfirmationNotifier {
    /** Notification buttons and card buttons bring it, and the service accordingly discards answers clicked on old notifications and old cards. */
    const val EXTRA_REQUEST = "confirmation_request"

    /** The display name of the App; use the package name when it cannot be read (not installed, the package is not visible). */
    fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** Declare a number each time: Each time the loop declares, a new waiting state is created. The service and interface in the same process get the same object. */
    fun requestKey(state: TaskState.WaitingConfirmation): Int = System.identityHashCode(state)

    /** The data of the button Intent: each declaration and each button are different. The system will not replace the button on the old notification with the newly declared one. */
    fun intentData(key: Int, approve: Boolean) = "mina-confirm://request/$key/" + if (approve) "approve" else "decline"

    fun build(
        context: Context, channel: String, state: TaskState.WaitingConfirmation, service: Class<*>, approveAction: String, declineAction: String,
        contentIntent: PendingIntent,
        /** When several tasks are waiting for confirmation at the same time, each uses a request code, and the button carries [runExtra] to indicate which task it is. */
        codeOffset: Int = 0,
        runExtra: Pair<String, String>? = null,
        /** It still sends but does not sound or banner pops up (when the suspended capsule is being displayed), see [alertQuietly]. */
        quiet: Boolean = false,
    ): Notification {
        val r = state.request
        val key = requestKey(state)
        val app = appLabel(context, r.app)
        fun button(title: Int, action: String, approve: Boolean, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                context, code + codeOffset,
                Intent(context, service).setAction(action).setData(Uri.parse(intentData(key, approve))).putExtra(EXTRA_REQUEST, key)
                    .apply { runExtra?.let { (k, v) -> putExtra(k, v) } },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            return Notification.Action.Builder(null, context.getString(title), pi).apply {
                // Click "Agree" on the lock screen to unlock it first
                if (approve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
            }.build()
        }
        // Only the type of operation you need to agree to is shown on the lock screen. The objects and
        // content can only be seen after unlocking them.
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(context.getString(R.string.confirmation_locked_hint))
            .build()
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(ConfirmationCopy.body(r, app))
            .setStyle(Notification.BigTextStyle().bigText(ConfirmationCopy.detail(r, app)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(button(R.string.action_approve, approveAction, true, 20))
            .addAction(button(R.string.action_decline, declineAction, false, 21))
            .apply { if (quiet) alertQuietly() }
            .build()
    }
}
