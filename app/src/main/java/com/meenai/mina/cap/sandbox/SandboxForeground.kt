package com.meenai.mina.cap.sandbox

import com.meenai.mina.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.meenai.mina.R
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.meenai.mina.sandbox.SandboxService

/**
 * Foreground service during preparation of the running environment (type dataSync, Android requires
 * foreground service for long-term background work). Only a low-priority message "Mina is preparing"
 * will be displayed and will be removed after preparation; no result notification will be sent. The
 * preparation itself is in [RuntimePreparer].
 */
open class SandboxForeground : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.notif_channel_prepare), NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // You must enter the foreground as soon as possible after startForegroundService
        startForeground(ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher?.isActive == true) return START_NOT_STICKY
        watcher = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            val progress = launch {
                SandboxState.get(this@SandboxForeground).status.collect { s ->
                    if (s is SandboxStatus.Installing) nm.notify(ID, notification(s.percent))
                }
            }
            RuntimePreparer.current?.join()
            progress.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(percent: Int?): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.notif_preparing))
        .apply { if (percent != null) setProgress(100, percent, false) }
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL = "runtime_prepare"
        private const val ID = 20

        /** Enter the front desk and wait for [RuntimePreparer]'s current preparation to end. Called only by [RuntimePreparer]. */
        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, SandboxService::class.java))
        }
    }
}
