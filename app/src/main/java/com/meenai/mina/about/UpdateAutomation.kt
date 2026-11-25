package com.meenai.mina.about

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.meenai.mina.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The second half of the automatic update: After checking and discovering a new version, download the
 * APK to the application-specific directory without waiting for the user to click on it. After
 * downloading, a notification will be sent, and you can click on the notification to proceed directly to
 * the installation. The same version will be reminded at most once; the system installer will still ask
 * the user for confirmation. Manual download and installation of the About page are not affected.
 */
object UpdateAutomation {
    private const val CHANNEL = "update-ready"
    private const val NOTIFICATION_ID = 5001
    private const val PREFS = "auto-update"
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every time the App is opened, it will check the version in the background. If a new version is found, it will automatically download and send a reminder. */
    fun onAppOpened(context: Context, checker: UpdateChecker) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                runCatching { checker.autoCheck() }
                runCatching { drive(appContext, ApkUpdater(appContext), checker) }
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * FCM push notification milne par: bina 6 ghante wait kiye seedha latest release check karo,
     * aur naya version milte hi maujooda drive automation ko chalao (download + install notification).
     */
    fun onPushReceived(context: Context, checker: UpdateChecker) {
        val appContext = context.applicationContext
        scope.launch {
            val start = System.currentTimeMillis()
            while (!running.compareAndSet(false, true)) {
                if (System.currentTimeMillis() - start > 15_000L) return@launch
                delay(500)
            }
            try {
                runCatching { checker.check(manual = false) }
                runCatching { drive(appContext, ApkUpdater(appContext), checker) }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun drive(app: Context, updater: ApkUpdater, checker: UpdateChecker) {
        val release = checker.state.value.update ?: return
        if (release.apkUrl == null) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "notified:" + release.version
        if (prefs.getBoolean(key, false)) return
        val state = runCatching { updater.state(release) }.getOrNull() ?: return
        when (state.phase) {
            DownloadPhase.Ready -> {
                prefs.edit().putBoolean(key, true).commit()
                notify(app, release)
                return
            }
            DownloadPhase.Downloading, DownloadPhase.Paused -> {}
            else -> runCatching { updater.download(release) }
        }
        // The downloads you downloaded this time, or those that were still pending in the previous
        // round, wait for the results to come out: you will be reminded when the download is completed,
        // and you will stop if it fails or is cleared.
        val deadline = System.currentTimeMillis() + 45 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(5000)
            val s = runCatching { updater.state(release) }.getOrNull()
            when (s?.phase) {
                DownloadPhase.Ready -> {
                    prefs.edit().putBoolean(key, true).commit()
                    notify(app, release)
                    return
                }
                DownloadPhase.Idle, DownloadPhase.Failed, null -> return
                else -> {}
            }
        }
    }

    private fun notify(app: Context, release: ReleaseInfo) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.about_update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val pi = PendingIntent.getActivity(
                app, 0, Intent(app, UpdateInstallActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.about_update_ready, release.version))
                .setContentText(app.getString(R.string.about_update_notification_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }
}
