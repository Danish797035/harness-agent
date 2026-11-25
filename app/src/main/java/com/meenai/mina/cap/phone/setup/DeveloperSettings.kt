package com.meenai.mina.cap.phone.setup

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Guide the system switches you want to see, and the Intent to jump to the corresponding settings page.
 * There is no public jump entrance for "USB debugging" and "wireless debugging": go to the developer
 * options and highlight that line according to the original settings; phones that do not recognize this
 * parameter will stop at the top of the developer options.
 */
object DeveloperSettings {
    /** The original global setting items; undisclosed constants, read by name. */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    /** The key in the "Wireless Debugging" line in the original developer options is used to highlight. */
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    /** The key in the "USB Debugging" line in the original developer options. */
    private const val USB_DEBUGGING_KEY = "enable_adb"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    val developerOptionsUri: Uri = Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
    val wirelessDebuggingUri: Uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
    val usbDebuggingUri: Uri = Settings.Global.getUriFor(Settings.Global.ADB_ENABLED)

    fun developerOptions(cr: ContentResolver): Boolean =
        Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** If this item has not been written (it has never been turned on), it will be counted as off; for mobile phones that cannot be read accurately, the "I have turned it on" on the guide page will be counted. */
    fun wirelessDebugging(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1

    /**
     * There is no wireless debugging item in the system. The original version (from Android 11 onwards)
     * will always write this setting as 0 or 1 according to the current state when it is turned on, so
     * it can be read whether it is turned on or off; this item is always missing, indicating that the
     * system does not have wireless debugging. If an error occurs while reading (the system does not
     * allow reading), if you do not draw a conclusion, just assume it is correct.
     */
    fun wirelessDebuggingMissing(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.getString(cr, ADB_WIFI_ENABLED) == null }.getOrDefault(false)

    /**
     * The "Developer Options" page cannot be opened in the system: there is no page connection for the
     * skipped Intent (some operating environments do not have Android system settings). If you can't ask
     * (the system won't let you ask), if you don't draw a conclusion, just assume it.
     */
    fun developerOptionsPageMissing(pm: PackageManager): Boolean =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0) == null }.getOrDefault(false)

    /**
     * Set the wireless debugging switch to on (authorization to modify system settings is required). If
     * the write is rejected (throws an exception or returns false), it returns false; after writing, the
     * system may call it back immediately (see [com.meenai.mina.core.shell.WirelessDebuggingAutoOn]),
     * and the caller can read it again.
     */
    fun turnOnWirelessDebugging(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1) }.getOrDefault(false)

    fun usbDebugging(cr: ContentResolver): Boolean = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1

    /** "About the mobile phone", the version number is here (some mobile phones are in the "Software Information" below it). */
    fun aboutPhone(): List<Intent> = listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun developerOptionsPage(): List<Intent> = listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun usbDebuggingPage(): List<Intent> = highlighted(USB_DEBUGGING_KEY)

    fun wirelessDebuggingPage(): List<Intent> = highlighted(WIRELESS_DEBUGGING_KEY)

    private fun highlighted(key: String): List<Intent> {
        val highlight = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
        return listOf(highlight) + developerOptionsPage()
    }

    fun wifiPage(): List<Intent> = listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}
