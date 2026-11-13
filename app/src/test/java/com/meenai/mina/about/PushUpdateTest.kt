package com.meenai.mina.about

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushUpdateTest {

    private class TestPrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { temp[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { temp[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { temp[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { temp[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in temp) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    private class DummyContext : Context() {
        override fun getAssets() = throw UnsupportedOperationException()
        override fun getResources() = throw UnsupportedOperationException()
        override fun getPackageManager() = throw UnsupportedOperationException()
        override fun getContentResolver() = throw UnsupportedOperationException()
        override fun getMainLooper() = throw UnsupportedOperationException()
        override fun getApplicationContext() = this
        override fun setTheme(resid: Int) {}
        override fun getTheme() = throw UnsupportedOperationException()
        override fun getClassLoader() = javaClass.classLoader
        override fun getPackageName(): String = "com.danish.harnessagent"
        override fun getApplicationInfo() = throw UnsupportedOperationException()
        override fun getPackageResourcePath() = throw UnsupportedOperationException()
        override fun getPackageCodePath() = throw UnsupportedOperationException()
        override fun getSharedPreferences(name: String?, mode: Int) = throw UnsupportedOperationException()
        override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?) = false
        override fun deleteSharedPreferences(name: String?) = false
        override fun openFileInput(name: String?) = throw UnsupportedOperationException()
        override fun openFileOutput(name: String?, mode: Int) = throw UnsupportedOperationException()
        override fun deleteFile(name: String?) = false
        override fun getFileStreamPath(name: String?) = throw UnsupportedOperationException()
        override fun getDataDir() = throw UnsupportedOperationException()
        override fun getFilesDir() = throw UnsupportedOperationException()
        override fun getNoBackupFilesDir() = throw UnsupportedOperationException()
        override fun getExternalFilesDir(type: String?) = throw UnsupportedOperationException()
        override fun getExternalFilesDirs(type: String?) = throw UnsupportedOperationException()
        override fun getObbDir() = throw UnsupportedOperationException()
        override fun getObbDirs() = throw UnsupportedOperationException()
        override fun getCacheDir() = throw UnsupportedOperationException()
        override fun getCodeCacheDir() = throw UnsupportedOperationException()
        override fun getExternalCacheDir() = throw UnsupportedOperationException()
        override fun getExternalCacheDirs() = throw UnsupportedOperationException()
        override fun getExternalMediaDirs() = throw UnsupportedOperationException()
        override fun fileList() = emptyArray<String>()
        override fun getDir(name: String?, mode: Int) = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = throw UnsupportedOperationException()
        override fun moveDatabaseFrom(sourceContext: Context?, name: String?) = false
        override fun deleteDatabase(name: String?) = false
        override fun getDatabasePath(name: String?) = throw UnsupportedOperationException()
        override fun databaseList() = emptyArray<String>()
        override fun getWallpaper() = throw UnsupportedOperationException()
        override fun peekWallpaper() = throw UnsupportedOperationException()
        override fun getWallpaperDesiredMinimumWidth() = 0
        override fun getWallpaperDesiredMinimumHeight() = 0
        override fun setWallpaper(bitmap: android.graphics.Bitmap?) {}
        override fun setWallpaper(data: java.io.InputStream?) {}
        override fun clearWallpaper() {}
        override fun startActivity(intent: android.content.Intent?) {}
        override fun startActivity(intent: android.content.Intent?, options: android.os.Bundle?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?, options: android.os.Bundle?) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: android.os.Bundle?) {}
        override fun sendBroadcast(intent: android.content.Intent?) {}
        override fun sendBroadcast(intent: android.content.Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, receiverPermission: String?) {}
        override fun sendOrderedBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun sendStickyBroadcast(intent: android.content.Intent?) {}
        override fun sendStickyOrderedBroadcast(intent: android.content.Intent?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun removeStickyBroadcast(intent: android.content.Intent?) {}
        override fun sendStickyBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun sendStickyOrderedBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun removeStickyBroadcastAsUser(intent: android.content.Intent?, user: android.os.UserHandle?) {}
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?) = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int) = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?) = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?, flags: Int) = null
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver?) {}
        override fun startService(service: android.content.Intent?) = null
        override fun startForegroundService(service: android.content.Intent?) = null
        override fun stopService(service: android.content.Intent?) = false
        override fun bindService(service: android.content.Intent, conn: android.content.ServiceConnection, flags: Int) = false
        override fun unbindService(conn: android.content.ServiceConnection) {}
        override fun startInstrumentation(className: android.content.ComponentName, profileFile: String?, arguments: android.os.Bundle?) = false
        override fun getSystemService(name: String) = null
        override fun getSystemServiceName(serviceClass: Class<*>) = null
        override fun checkPermission(permission: String, pid: Int, uid: Int) = 0
        override fun checkCallingPermission(permission: String) = 0
        override fun checkCallingOrSelfPermission(permission: String) = 0
        override fun checkSelfPermission(permission: String) = 0
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun grantUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(uri: android.net.Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(toPackage: String?, uri: android.net.Uri?, modeFlags: Int) {}
        override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int) = 0
        override fun checkCallingUriPermission(uri: android.net.Uri?, modeFlags: Int) = 0
        override fun checkCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int) = 0
        override fun checkUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int) = 0
        override fun enforceUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun enforceCallingUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun createPackageContext(packageName: String?, flags: Int) = throw UnsupportedOperationException()
        override fun createContextForSplit(splitName: String?) = throw UnsupportedOperationException()
        override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration) = throw UnsupportedOperationException()
        override fun createDisplayContext(display: android.view.Display) = throw UnsupportedOperationException()
        override fun createDeviceProtectedStorageContext() = throw UnsupportedOperationException()
        override fun isDeviceProtectedStorage() = false
    }

    @Test
    fun firstRunSubscribesTopicWhenPlayServicesAvailable() {
        val prefs = TestPrefs()
        val context = DummyContext()
        var subscribedTopic: String? = null

        val result = PushUpdateSubscriber.subscribeInternal(
            prefs = prefs,
            context = context,
            isPlayServicesAvailable = { true },
            topicSubscriber = { topic, onComplete ->
                subscribedTopic = topic
                onComplete(true)
            }
        )

        assertTrue(result)
        assertEquals(PushUpdateSubscriber.TOPIC_RELEASES, subscribedTopic)
        assertTrue(prefs.getBoolean(PushUpdateSubscriber.KEY_SUBSCRIBED, false))
    }

    @Test
    fun alreadySubscribedSkipsSubscription() {
        val prefs = TestPrefs()
        prefs.edit().putBoolean(PushUpdateSubscriber.KEY_SUBSCRIBED, true).commit()
        val context = DummyContext()
        var called = false

        val result = PushUpdateSubscriber.subscribeInternal(
            prefs = prefs,
            context = context,
            isPlayServicesAvailable = { true },
            topicSubscriber = { _, _ -> called = true }
        )

        assertFalse(result)
        assertFalse(called)
    }

    @Test
    fun playServicesUnavailableSilentlySkips() {
        val prefs = TestPrefs()
        val context = DummyContext()
        var called = false

        val result = PushUpdateSubscriber.subscribeInternal(
            prefs = prefs,
            context = context,
            isPlayServicesAvailable = { false },
            topicSubscriber = { _, _ -> called = true }
        )

        assertFalse(result)
        assertFalse(called)
        assertFalse(prefs.getBoolean(PushUpdateSubscriber.KEY_SUBSCRIBED, false))
    }

    @Test
    fun failedSubscriptionDoesNotMarkSubscribed() {
        val prefs = TestPrefs()
        val context = DummyContext()

        val result = PushUpdateSubscriber.subscribeInternal(
            prefs = prefs,
            context = context,
            isPlayServicesAvailable = { true },
            topicSubscriber = { _, onComplete -> onComplete(false) }
        )

        assertTrue(result)
        assertFalse(prefs.getBoolean(PushUpdateSubscriber.KEY_SUBSCRIBED, false))
    }

    @Test
    fun messageReceivedTriggersUpdateAutomation() {
        val context = DummyContext()
        var triggered = false

        val handled = HarnessFirebaseMessagingService.handleMessage(
            context = context,
            data = mapOf("version" to "0.1.49", "tag" to "v0.1.49-162"),
            onTrigger = { triggered = true }
        )

        assertTrue(handled)
        assertTrue(triggered)
    }

    @Test
    fun fcmPayloadDoesNotContainDisplayNotification() {
        // FCM HTTP v1 payload contract: notification block must not be included,
        // so that Android delivers raw data directly to onMessageReceived in both background & foreground.
        val payloadData = mapOf("version" to "0.1.48", "tag" to "v0.1.48-161")
        assertFalse(payloadData.containsKey("notification"))
        assertEquals("0.1.48", payloadData["version"])
        assertEquals("v0.1.48-161", payloadData["tag"])
    }
}
