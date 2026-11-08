package com.meenai.mina.ui.router

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.meenai.mina.R
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.task.TaskService
import com.meenai.mina.ui.MainActivity

/**
 * Handles the 9:00 IST daily login reminder notification for ModelScope Magicube.
 * Skipped if already marked as logged in today.
 */
object MagicubeReminder {
    const val NOTIFICATION_ID = 9001

    fun maybeNotify(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!MagicubeLedger.shouldSendLoginReminder(nowMs)) {
            return false
        }
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!notifications.areNotificationsEnabled()) return false
        TaskService.ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = context.getString(R.string.router_magicube_reminder_title)
        val body = context.getString(R.string.router_magicube_reminder_body)

        val notification = Notification.Builder(context, TaskService.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            notifications.notify(NOTIFICATION_ID, notification)
        }
        return true
    }
}
