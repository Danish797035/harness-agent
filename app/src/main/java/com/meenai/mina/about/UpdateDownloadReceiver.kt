package com.meenai.mina.about

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meenai.mina.HarnessApp

/**
 * Listens for DownloadManager completion, verifies download ID, and opens the installer or unknown sources settings.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
        val savedId = prefs.getLong("id", -1L)
        if (id == -1L || id != savedId) return

        val app = context.applicationContext as? HarnessApp ?: return
        val updater = ApkUpdater(context)
        val release = app.updates.state.value.update ?: app.updates.state.value.latest ?: return
        if (updater.state(release).phase == DownloadPhase.Ready) {
            runCatching {
                updater.openInstaller(release)
            }
        }
    }
}
