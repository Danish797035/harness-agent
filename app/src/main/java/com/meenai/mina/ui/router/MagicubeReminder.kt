package com.meenai.mina.ui.router

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meenai.mina.R
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.task.TaskService
import com.meenai.mina.ui.MainActivity

/**
 * Handles the 9:00 IST daily login reminder notification and scheduling for ModelScope Magicube.
 * Skipped if already marked as logged in today.
 */
object MagicubeReminder {
    const val NOTIFICATION_ID = 9001
    const val ALARM_REQUEST_CODE = 9002
    const val ACTION_MARK_LOGIN_REQUEST_CODE = 9003

    const val ACTION_ALARM = "com.meenai.mina.ui.router.MAGICUBE_REMINDER_ALARM"
    const val ACTION_MARK_LOGIN = "com.meenai.mina.ui.router.MAGICUBE_MARK_LOGIN"

    fun scheduleDailyAlarm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, MagicubeReminderReceiver::class.java).apply {
            action = ACTION_ALARM
        }
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val targetMs = MagicubeLedger.nextNineAmIst(nowMs)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pending)
        }
    }

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

        val markLoginIntent = Intent(context, MagicubeReminderReceiver::class.java).apply {
            action = ACTION_MARK_LOGIN
        }
        val markLoginPendingIntent = PendingIntent.getBroadcast(
            context,
            ACTION_MARK_LOGIN_REQUEST_CODE,
            markLoginIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val loginAction = Notification.Action.Builder(
            null,
            context.getString(R.string.router_magicube_action_login),
            markLoginPendingIntent,
        ).build()

        val title = context.getString(R.string.router_magicube_reminder_title)
        val body = context.getString(R.string.router_magicube_reminder_body)

        val notification = Notification.Builder(context, TaskService.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .addAction(loginAction)
            .setAutoCancel(true)
            .build()

        runCatching {
            notifications.notify(NOTIFICATION_ID, notification)
        }
        return true
    }
}
