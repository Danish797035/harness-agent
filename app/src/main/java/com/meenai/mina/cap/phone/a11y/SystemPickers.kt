package com.meenai.mina.cap.phone.a11y

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * The package where the system interface called up by the target App is located (permission box,
 * selector, file and photo selector): appearing in the foreground does not count as being cut away. It
 * is obtained by parsing according to the standard Intent, and the package name is not hard-coded.
 */
internal object SystemPickers {
    /** The package where the system's own selectors (ResolverActivity, etc.) are located. */
    const val SYSTEM_PACKAGE = "android"
    private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"

    fun intents(): List<Intent> = buildList {
        add(Intent(ACTION_REQUEST_PERMISSIONS))
        add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null))
        add(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Intent(MediaStore.ACTION_PICK_IMAGES))
    }

    fun settingsPackage(pm: PackageManager): String? =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName }.getOrNull()

    fun resolve(pm: PackageManager): Set<String> =
        intents().mapNotNull { runCatching { pm.resolveActivity(it, 0)?.activityInfo?.packageName }.getOrNull() }.toSet() + SYSTEM_PACKAGE
}
