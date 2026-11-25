package com.meenai.mina.cap.web.cdp

import com.meenai.mina.core.cap.web.cdp.CdpTargetInfo

/** Correspondence between labels and DevTools goals: first write the mark in the label, and then evaluate the candidate goals one by one to find it. */
object TargetMatch {
    /** Put the marked global variable name on the page; delete it after finding it. */
    const val KEY = "__minaDevtoolsMark"

    fun markScript(marker: String): String = "window['$KEY']='$marker';1"
    const val READ_SCRIPT = "window['$KEY']||''"
    const val CLEAR_SCRIPT = "delete window['$KEY'];1"

    /**
     * The goals to try one by one: as long as the page is not connected to other clients (there is
     * webSocketDebuggerUrl), and is not claimed by other tags; the URL and the current URL of the tag
     * are the same (most likely it is).
     */
    fun candidates(targets: List<CdpTargetInfo>, claimed: Set<String>, tabUrl: String?): List<CdpTargetInfo> =
        targets.filter { it.type == "page" && it.webSocketDebuggerUrl != null && it.id !in claimed }
            .sortedBy { if (!tabUrl.isNullOrEmpty() && it.url == tabUrl) 0 else 1 }

    fun isMarker(value: String?, marker: String): Boolean = marker.isNotEmpty() && value == marker
}
