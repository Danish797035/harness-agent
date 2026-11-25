package com.meenai.mina.about

import android.app.Activity
import android.os.Bundle
import com.meenai.mina.HarnessApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * When you click on the update notification, you will first fall here: no interface will be displayed,
 * the downloaded APK will be verified and handed directly to the system installer, and then exit
 * immediately. If the verification fails (the file is corrupted, not a higher version, or the signature
 * is incorrect), nothing will be opened.
 */
class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HarnessApp
        val updater = ApkUpdater(this)
        Thread {
            val intent = runCatching {
                runBlocking {
                    app.updates.load()
                    withContext(Dispatchers.IO) {
                        val release = app.updates.state.value.update ?: app.updates.state.value.latest
                        if (release?.apkUrl != null && updater.state(release).phase == DownloadPhase.Ready) {
                            updater.installIntent(release)
                        } else null
                    }
                }
            }.getOrNull()
            runOnUiThread {
                if (intent != null) startActivity(intent)
                finish()
            }
        }.start()
    }
}
