package com.meenai.mina.cap.web

import android.content.Context
import android.webkit.WebViewDatabase

/** The setting page operation of the site registration form; the registration form is the one in the process (given by the App, shared by the setting page and the tag pool). */
object WebSites {
    fun registry(context: Context): SiteRegistry = WebOwner.of(context).webSites

    /** Exit operation for settings page: cookie and web page storage of mina web page configuration. Called by the main thread. */
    fun login(context: Context): WebLogin = WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry(context))

    /**
     * "Exit all and clear web page data" on the settings page: The cache must be cleared even if the tab
     * is not open, so temporarily open an invisible tab with the same configuration to clear it, and
     * close it after use. Called by the main thread.
     */
    fun logoutAll(context: Context) {
        val tmp = WebTab(context.applicationContext, "clear", "settings")
        try {
            login(context).logoutAll(listOf(tmp.view), WebViewDatabase.getInstance(context))
        } finally {
            tmp.destroy()
        }
    }
}
