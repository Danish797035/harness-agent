package com.meenai.mina.cap.web

import android.content.Context
import com.meenai.mina.cap.web.cdp.WebDevtools

/** The DevTools session entrance, site registration form, upper limit of the total number of tags, and the page left in the background of the web page in the process: are given by the App, and the settings page is shared with each task. */
interface WebOwner {
    val webDevtools: WebDevtools
    val webSites: SiteRegistry
    /** The upper limit of the total number of tags shared by the tag pools of each task. */
    val tabBudget: TabBudget
    /** The page that remains in the background and continues to run after the task is completed (at most one page globally). */
    val pageKeeper: PageKeeper

    companion object {
        fun of(context: Context): WebOwner = context.applicationContext as? WebOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement WebOwner to give the browser's shared state")
    }
}
