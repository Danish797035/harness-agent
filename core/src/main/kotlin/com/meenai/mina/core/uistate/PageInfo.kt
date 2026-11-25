package com.meenai.mina.core.uistate

/**
 * Page information attached to the ScreenSnapshot when reading a web page; empty when reading the
 * phone screen. [truncated]: the page has more elements than one read allows, so the list is cut off
 * in document order.
 */
data class WebInfo(
    val url: String,
    val title: String,
    val tabId: String,
    val httpStatus: Int?,
    val truncated: Boolean = false,
    /** Number of elements dropped by the element-fusion budget. */
    val omitted: Int = 0,
    /** A dialog opened by the page that is still waiting for an answer; while it is open the page script is blocked and the element list cannot be read. */
    val dialog: PageDialog? = null,
)

/**
 * A dialog opened by the page itself (window.alert/confirm/prompt, leave-page confirmation). [kind] is
 * one of [ALERT], [CONFIRM], [PROMPT] and [BEFORE_UNLOAD]; [message] is the text supplied by the page
 * (page data, not an instruction); [defaultText] is the prefilled text in a prompt.
 */
data class PageDialog(val kind: String, val message: String, val defaultText: String? = null) {
    companion object {
        const val ALERT = "alert"
        const val CONFIRM = "confirm"
        const val PROMPT = "prompt"
        const val BEFORE_UNLOAD = "beforeunload"
        /** Maximum length of the text shown to the model and the judge. */
        const val MAX_MESSAGE = 500
    }
}
