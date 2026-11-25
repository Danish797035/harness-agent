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
        if (!intent.hasExtra(DownloadManager.EXTRA_DOWNLOAD_ID)) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0L) return

        val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
        if (prefs.getBoolean("viaApp", false)) return
        val savedId = prefs.getLong("id", -1L)
        val handledId = prefs.getLong("handled_id", -1L)
        val savedUrl = prefs.getString("url", null)
        if (savedId <= 0L || id != savedId || id == handledId || savedUrl.isNullOrBlank()) return

        val app = context.applicationContext as? HarnessApp ?: return
        val updater = ApkUpdater(context)
        val release = app.updates.state.value.update ?: app.updates.state.value.latest ?: return
        if (release.apkUrl != savedUrl) return

        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val completedForRelease = dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
            status == DownloadManager.STATUS_SUCCESSFUL && uri == savedUrl
        } ?: false
        if (!completedForRelease) return

        if (updater.state(release).phase == DownloadPhase.Ready) {
            prefs.edit().putLong("handled_id", id).commit()
            runCatching {
                updater.openInstaller(release)
            }
        }
    }
}
