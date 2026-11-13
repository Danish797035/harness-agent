package com.meenai.mina.about

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.meenai.mina.HarnessApp

/**
 * FCM Service to receive release update triggers in the background.
 * Release publish hone par FCM topic message milta hai, jo bina 6 ghante wait kiye
 * latest release fetch karta hai aur background auto-download drive karta hai.
 */
class HarnessFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM token received, ensuring topic subscription is refreshed")
        runCatching {
            getSharedPreferences(PushUpdateSubscriber.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PushUpdateSubscriber.KEY_SUBSCRIBED)
                .apply()
            PushUpdateSubscriber.ensureSubscribed(this)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        Log.i(TAG, "FCM release message received: data=$data")
        val app = applicationContext as? HarnessApp ?: return
        handleMessage(app, data)
    }

    companion object {
        private const val TAG = "HarnessFCMService"

        fun handleMessage(
            context: Context,
            data: Map<String, String>,
            onTrigger: (Context) -> Unit = { ctx ->
                val app = ctx.applicationContext as? HarnessApp
                if (app != null) {
                    UpdateAutomation.onPushReceived(app, app.updates)
                }
            }
        ): Boolean {
            val tag = data["tag"]
            val version = data["version"]
            Log.d(TAG, "Handling release push for version=$version tag=$tag")
            onTrigger(context)
            return true
        }
    }
}
