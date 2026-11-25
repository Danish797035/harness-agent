package com.meenai.mina.core.tool


/**
 * What open_link can open. Check once before the dispatcher is executed and again before the shell
 * service starts. The settings page only recognizes the `android.settings.*` action disclosed by the
 * original Android version; the link rejects files and script protocols, and the web link must specify
 * the App (otherwise it will fall into the browser).
 */
object LinkPolicy {
    const val APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    val SETTINGS_ACTIONS: Set<String> = setOf(
        "android.settings.SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS",
        "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS", "android.settings.BATTERY_SAVER_SETTINGS",
        APP_DETAILS, "android.settings.APPLICATION_SETTINGS", "android.settings.DATE_SETTINGS",
        "android.settings.LOCALE_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS", "android.settings.LOCATION_SOURCE_SETTINGS",
        "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.ACCESSIBILITY_SETTINGS",
    )

    private val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    /** The shape of the Android package name; it is also used by the dispatcher checking the package / app parameters. */
    val PACKAGE = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private const val MAX_URI = 2000

    /** null = allowed; otherwise an error message to the model. */
    fun check(action: String?, uri: String?, pkg: String?): String? {
        if (action == null && uri == null) return "Give uri, or action for an Android settings page."
        if (pkg != null && !PACKAGE.matches(pkg)) return "package must be an Android package name."
        if (uri != null && (uri.length > MAX_URI || uri.any { it.isWhitespace() || it.isISOControl() })) {
            return "uri must be at most $MAX_URI characters without spaces."
        }
        if (action != null) {
            if (action !in SETTINGS_ACTIONS) return "action must be one of the Android settings actions: ${SETTINGS_ACTIONS.joinToString()}."
            if (pkg != null) return "package is not used with action."
            if (action == APP_DETAILS) {
                val target = uri?.takeIf { it.startsWith("package:") }?.removePrefix("package:")
                if (target == null || !PACKAGE.matches(target)) return "$APP_DETAILS needs uri package:<package name>, e.g. package:com.example.app."
            } else if (uri != null) return "Only $APP_DETAILS takes a uri; open other settings pages with action alone."
            return null
        }
        val scheme = SCHEME.find(uri!!)?.groupValues?.get(1)?.lowercase() ?: return "uri must start with a scheme, e.g. https:// or app://."
        if (scheme in BLOCKED_SCHEMES) return "$scheme: links are not supported by phone_open_link."
        if (scheme == "package") return "package: uris are only for action $APP_DETAILS."
        if ((scheme == "http" || scheme == "https") && pkg == null) {
            return "A web link needs package (the app that should open it); otherwise it would open in a browser."
        }
        return null
    }
}
