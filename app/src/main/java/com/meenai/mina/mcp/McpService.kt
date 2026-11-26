package com.meenai.mina.mcp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.meenai.mina.HarnessApp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.task.TaskService

/**
 * Foreground service while the computer connection is open: just to keep the process alive in the
 * background and the port always open; the received tasks are still handed over to [TaskService] for
 * running. The task service will exit by itself when it has no tasks, so the monitoring is not placed in
 * it. You can directly turn off the computer connection on the permanent notification (both types are
 * turned off). It will be rebuilt (START_STICKY) after being recycled by the system; exit on the spot if
 * the switches are turned off.
 *
 * The computer also uses this service when turning on and off USB through USB debugging: it is open to
 * the outside world in the list, but the caller is required to hold INTERACT_ACROSS_USERS_FULL. The
 * shell has this permission, but ordinary apps cannot get it (it only gives system signatures and
 * installers, and adb cannot grant it), so only `adb shell am start-foreground-service` initiated as a
 * shell can afford it; Mina herself is not restricted. Starting from the foreground service method: Apps
 * in the background starting from Android 12 generally cannot open foreground services by themselves,
 * but startup initiated by shell is not limited to this.
 */
class McpService : Service() {
    private val runtime get() = (application as HarnessApp).mcp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        val applied = runCatching { McpModes.apply(intent?.action, runtime.settings, runtime.usb, runtime.relaySettings) }
            .onFailure { Log.w("MinaMcp", "could not update computer access settings", it) }.isSuccess
        // After startForegroundService, you must enter the foreground as soon as possible, even if you
        // are about to exit; if the system does not allow you to enter the foreground, exit to prevent
        // the process from crashing.
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w("MinaMcp", "computer access service could not enter the foreground", it) }.isSuccess
        val ready = foreground && applied && runCatching { runtime.sync() }
            .onFailure { Log.w("MinaMcp", "could not start computer access", it) }.getOrDefault(false)
        if (!ready) {
            runtime.relay.stop()
            runtime.relayNetwork.stop()
            runtime.stopServer()
            runtime.usb.unpublish()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.relay.stop()
        runtime.relayNetwork.stop()
        runtime.stopServer()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val port = runtime.settings.port
        val body = when {
            runtime.relaySettings.config.value.enabled -> R.string.relay_notif_body
            runtime.settings.enabled.value && runtime.usb.enabled.value -> R.string.mcp_notif_body_both
            runtime.usb.enabled.value -> R.string.mcp_notif_body_usb
            else -> R.string.mcp_notif_body
        }
        val open = TaskService.openApp(this)
        val off = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(McpModes.ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.mcp_notif_title))
            .setContentText(tr(body, port))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr(R.string.mcp_turn_off), off).build())
            .build()
    }

    companion object {
        /** Staggered with task notification (slot number, result number section). */
        private const val NOTIFICATION_ID = 30_001
    }
}
