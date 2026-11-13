package com.meenai.mina.about

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * FCM topic subscription for release push updates.
 * Pehli run par topic `harness-releases` subscribe karta hai.
 * Google Play Services na hone par app bina crash huye silently periodic polling par fall back karta hai.
 */
object PushUpdateSubscriber {
    const val TOPIC_RELEASES = "harness-releases"
    const val PREFS_NAME = "push_updates"
    const val KEY_SUBSCRIBED = "subscribed_releases_v1"
    private const val TAG = "PushUpdateSubscriber"

    fun ensureSubscribed(
        context: Context,
        isPlayServicesAvailable: (Context) -> Boolean = ::checkPlayServices,
        topicSubscriber: (String, (Boolean) -> Unit) -> Unit = ::defaultSubscribeTopic,
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        subscribeInternal(prefs, appContext, isPlayServicesAvailable, topicSubscriber)
    }

    internal fun subscribeInternal(
        prefs: SharedPreferences,
        context: Context,
        isPlayServicesAvailable: (Context) -> Boolean,
        topicSubscriber: (String, (Boolean) -> Unit) -> Unit,
    ): Boolean {
        if (prefs.getBoolean(KEY_SUBSCRIBED, false)) {
            return false
        }

        val available = runCatching { isPlayServicesAvailable(context) }.getOrDefault(false)
        if (!available) {
            Log.d(TAG, "Google Play Services not available; silently falling back to polling")
            return false
        }

        topicSubscriber(TOPIC_RELEASES) { success ->
            if (success) {
                prefs.edit().putBoolean(KEY_SUBSCRIBED, true).apply()
                Log.i(TAG, "Subscribed to FCM topic: $TOPIC_RELEASES")
            } else {
                Log.w(TAG, "Failed to subscribe to FCM topic: $TOPIC_RELEASES")
            }
        }
        return true
    }

    private fun checkPlayServices(context: Context): Boolean {
        return runCatching {
            val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            val code = availability.isGooglePlayServicesAvailable(context)
            code == com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    private fun defaultSubscribeTopic(topic: String, onComplete: (Boolean) -> Unit) {
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic(topic)
                .addOnCompleteListener { task ->
                    onComplete(task.isSuccessful)
                }
        }.onFailure {
            onComplete(false)
        }
    }
}
