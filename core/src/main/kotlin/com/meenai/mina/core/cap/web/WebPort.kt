package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.KeyCombo
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import kotlinx.serialization.json.JsonObject
import com.meenai.mina.core.uistate.PageDialog

data class TabInfo(val tabId: String, val url: String, val title: String, val current: Boolean)

/**
 * [externalLink]: The page wants to jump to App (intent:/custom protocol/app store), but there is no
 * jump. [settled]: When there are DevTools, it is true when the network is idle and the page is quiet
 * after loading, and it is false when the upper limit is not quiet; it is null when there is no
 * DevTools. [netError]: The error code given by the browser when the main document is not opened (such
 * as net::ERR_NAME_NOT_RESOLVED); when it is opened, it is null. [upgradedFrom]: After plaintext http is
 * rejected, use https when retrying, the original http URL. [download]: The URL is actually a file, and
 * this time it is opened and turned into a download; its download record.
 */
data class NavResult(
    val tab: TabInfo,
    val loaded: Boolean,
    val httpStatus: Int?,
    val newTab: Boolean,
    val externalLink: ExternalLink?,
    val settled: Boolean? = null,
    val netError: String? = null,
    val upgradedFrom: String? = null,
    val download: DownloadInfo? = null,
    /** The loading page pops up and is still waiting for a reply (the loading wait stops when it appears). */
    val dialog: PageDialog? = null,
)

/**
 * A paragraph of the whole page text; [links] and [images] are (text or description, address);
 * [nextOffset] is the starting point of the next paragraph, and it is null after reading. [quality] is
 * the quality signal of reading the page (it is present when pressing [PageRead] to read); [netError] is
 * the error code given by the browser when opening the URL in the background tab fails.
 */
data class PageText(
    val url: String,
    val title: String,
    val text: String,
    val links: List<Pair<String, String>>,
    val images: List<Pair<String, String>>,
    val truncated: Boolean,
    val nextOffset: Int?,
    val quality: ReadQuality? = null,
    val netError: String? = null,
)

/** [file] is the sandbox path /workspace/downloads/<name>; [status] is done|failed|running. */
data class DownloadInfo(val file: String, val bytes: Long, val mime: String?, val status: String, val error: String?)

/**
 * The result of web_keep_open: When [keep] is true, this page ([tabId], [url], [title], site [host])
 * will remain in the background and continue to run after the task ends, up to [hours] hours; [replaces]
 * is another page of the site left at the moment (it will be closed when the task ends), and is not
 * null. When [keep] is false, the remaining pages in this task are cancelled.
 */
data class KeepOpenResult(
    val keep: Boolean,
    val tabId: String?,
    val url: String?,
    val title: String?,
    val host: String?,
    val hours: Int,
    val replaces: String? = null,
)

/** The current tab is the page the user is looking at in the Mina browser (borrowed): it is not closed with the task and there is no need to keep it. */
class WebTabIsUsers(val tabId: String) : Exception("tab $tabId is the user's own page")

/** The result of web_site_mode: [host] is the site of the current page, [mobile] is now using the mobile version, [reloaded] the current page has been reloaded according to the new version. */
data class SiteModeResult(val host: String, val mobile: Boolean, val reloaded: Boolean)

sealed interface EvalJs {
    /** [json] is the result after desensitization and truncation; [fresh]: This time, a new tag reading world is created, and the ones that previously existed in the state are gone. */
    data class Ok(val json: String, val truncated: Boolean, val fresh: Boolean = false) : EvalJs

    /**
     * [error] is script_error|timeout|read_only (the script wants to change the
     * page)|invalid_selector|blocked_network|blocked_navigation|
     * limit_exceeded|network_error|too_large|navigated (the page has changed the
     * document)|devtools_unavailable|no_tab_open.
     */
    data class Failed(val error: String, val message: String) : EvalJs
}

/** The condition of web_wait_for; [wire] is the way it is written in the tool parameters. */
enum class WaitKind(val wire: String) {
    TEXT_PRESENT("text_present"), TEXT_ABSENT("text_absent"), NETWORK_IDLE("network_idle"), URL_CONTAINS("url_contains");

    companion object {
        fun of(wire: String): WaitKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** [met]: The condition is established within the time limit; [url] is the address at the end. */
data class WaitOutcome(val met: Boolean, val waitedMs: Long, val url: String?)

/** There is no DevTools session for the current tag (the system does not support it and cannot be connected): The tool to be recorded using the network results in devtools_unavailable. */
class DevtoolsUnavailable : Exception("devtools unavailable")

/** Web page tags: Add unique navigation, page reading, tags, downloading and uploading to the same set of interfaces used for mobile phone screen reading/operation. */
interface WebPort : ScreenPort {
    suspend fun navigate(url: String, newTab: Boolean): NavResult
    /** [scrollLazy]: Trigger the lazy loading content scrolling once before reading (the page will move); it is false when verifying and collecting evidence, and it will only read without scrolling. */
    suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean = true): PageText

    /**
     * The reading method of web_read_page: with quality signal, folded list block; [PageRead.url] is
     * opened in the temporary background tag when given, closed after reading, and the current tag is
     * not moved. When background reading is not supported, the url throws
     * [UnsupportedOperationException].
     */
    suspend fun read(req: PageRead): PageText =
        if (req.url == null) readPage(req.maxChars, req.offset, req.lazy) else throw UnsupportedOperationException("background reads are not available")
    /**
     * Read script: Run [script] (see [ReadScriptProtocol]) read-only in the reading world of the current
     * tag's real page, the upper limit is [timeoutMs]; [args] is the parameter of the site command (args
     * in the script). Several calls on the same document with the same tag share state. If DevTools
     * cannot be used, it will report devtools_unavailable and other methods will not be returned.
     */
    suspend fun evaluate(script: String, timeoutMs: Long, args: JsonObject? = null): EvalJs
    suspend fun tabs(): List<TabInfo>
    suspend fun switchTab(tabId: String): TabInfo?
    suspend fun closeTab(tabId: String): Boolean
    /** [UnsupportedOperationException] is thrown when the download function is not connected, and the tool result is downloads_unavailable. */
    suspend fun downloads(): List<DownloadInfo>
    suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult
    suspend fun historyBack(): ActionResult
    /**
     * The site of the current label uses the mobile version or desktop version of the web page (default
     * desktop version), remember by site, and reload the current page. The webpage that is not opened is
     * null; [UnsupportedOperationException] is thrown when it is not supported.
     */
    suspend fun setSiteMode(mobile: Boolean): SiteModeResult? = throw UnsupportedOperationException("site mode is not available")

    /**
     * After the task ends, leave the current label and continue running (when [keep] is false, cancel
     * the remaining page in this task). By default, all tabs are closed when the task ends; the
     * remaining page will be left for at most [KeepOpenResult.hours] hours, and only one page will be
     * left globally. If the web page is not opened, it is null; when the current tag is the user's own
     * page, [WebTabIsUsers] is thrown; when [UnsupportedOperationException] is not supported,
     * [UnsupportedOperationException] is thrown.
     */
    suspend fun keepOpen(keep: Boolean, reason: String): KeepOpenResult? = throw UnsupportedOperationException("keeping a page open is not available")

    /** The version used by the current tag at the moment: `mobile` or `desktop`; if there is no open web page or it is not known, it is null. */
    suspend fun siteMode(): String? = null

    /**
     * Running [code](async function body) in the isolated world of the current label's real page, the call to
     * `mina.*` returns to Mina via [bridge]; the upper limit is [timeoutMs]. If DevTools cannot be used,
     * it will report devtools_unavailable and other methods will not be returned.
     */
    suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun =
        ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "Page scripts need the page's DevTools connection, which is not available here.")

    // web_interact: An action that requires a DevTools session; returns devtools_unavailable when the
    // connection is not available, and does not pretend to do it.
    suspend fun hover(at: WebPoint): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** Native drop-down box selects an item; [option] is the display text or value of the option. */
    suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** The page pop-up box that answers the reply on the current label (see [PageDialogs]); [text] only gives the input box pop-up box. There is no pop-up box reporting no_dialog. */
    suspend fun answerDialog(accept: Boolean, text: String?): ActionResult = ActionResult.Failed(NO_DIALOG)
    /** Press the key combination once for the current focus. */
    suspend fun pressCombo(combo: KeyCombo): ActionResult = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
    /** XHR/fetch responses (without body) recorded by the current tag, oldest first. Throws [DevtoolsUnavailable] when there are no DevTools. */
    suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> = throw DevtoolsUnavailable()

    /** Get a response, if you can get the body (JSON/text, not too big), bring it; if not in the record, it will be null. Throws [DevtoolsUnavailable] when there are no DevTools. */
    suspend fun capture(id: String): CapturedResponse? = throw DevtoolsUnavailable()

    /** Wait until [kind] is established, and at most [timeoutMs]. Depending on the network conditions, [DevtoolsUnavailable] will be thrown when there is no DevTools. */
    suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome = throw DevtoolsUnavailable()

}

/** The landing point of web_interact: reading screen number, or view pixel coordinates. */
sealed interface WebPoint {
    data class Ref(val ref: String, val snapshotId: String) : WebPoint
    data class Xy(val x: Int, val y: Int) : WebPoint
}

const val DEVTOOLS_UNAVAILABLE = "devtools_unavailable"
const val NO_DIALOG = "no_dialog"
/** There is a pop-up box on the page waiting for a reply: don't do anything else, just reply first. */
const val DIALOG_OPEN = "dialog_open"

/** The current label is being handed over to the user for processing (being taken over); methods that return non-ActionResult use it to report, and the tool result is handed_over. */
class WebTabHandedOver(val tabId: String) : Exception("tab $tabId is with the user")

/** The page pop-up box on the current tab is hanging (the page is stopped): Use this method to read the page and open the URL. The tool result is dialog_open. */
class WebDialogOpen(val tabId: String) : Exception("tab $tabId has a page dialog open")

/** The label's rendering process crashes (or is recycled by the system), and the label has been destroyed; use this method to report non-ActionResult methods, and the tool result is tab_crashed. */
class WebTabCrashed(val tabId: String) : Exception("tab $tabId crashed")
