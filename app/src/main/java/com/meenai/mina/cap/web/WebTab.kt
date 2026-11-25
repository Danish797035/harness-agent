package com.meenai.mina.cap.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.meenai.mina.core.cap.web.DialogReply
import com.meenai.mina.core.cap.web.ExternalLink
import com.meenai.mina.core.cap.web.PageDialogs
import com.meenai.mina.core.uistate.PageDialog
import com.meenai.mina.core.cap.web.WebUrlPolicy
import com.meenai.mina.cap.web.cdp.WebDevtools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A web page tag: a WebView plus its loading state. The desktop version of the web page is opened by
 * default, and you can switch back to the mobile version by pressing the site (see [WebDevtools]). It
 * must be created in the main thread; [assetLoaders] is only used for web pages that come with apps such
 * as local fixtures (one for each domain name). When the system supports multiple configurations, use an
 * independent web page configuration named mina (cookies and storage are separated from other WebViews
 * in the system). Permission requests for pages (positioning, camera, microphone, MIDI, protected media)
 * will be silently rejected; links to Apps will not be jumped; downloads will not be accepted for the
 * time being. The page pop-up box (alert/confirm/prompt, exit page confirmation) does not pop up the
 * system dialog box, and the Agent (or the user's own browser page) reply such as [dialogs] is recorded.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebTab(
    context: Context,
    val id: String,
    ownerTaskId: String,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    /** The DevTools session entry for this label; the default is the one in the process. */
    val devtools: WebDevtools = WebDevtools.of(context),
) {
    /** The tag pool receives what happens on tags. All are called back on the main thread. */
    interface Listener {
        fun onPageFinished(tab: WebTab) {}
        fun onCrashed(tab: WebTab) {}
        fun onExternalLink(tab: WebTab, link: ExternalLink) {}
        /** The page needs to open a new window; return true to indicate that it has been accepted (hand the WebView of the new tab to [resultMsg]). */
        fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        /** The new tab that pops up will go to another website for the first time: the tab pool turns it off. */
        fun onPopupRefused(tab: WebTab, url: String) {}
        /** The page needs to close its own window (window.close()). */
        fun onCloseWindow(tab: WebTab) {}
        /** The page triggers downloading (only saves, does not open or install). */
        fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {}
        /** The page address, title or loading progress has changed (refresh the address bar and progress bar for visible labels). */
        fun onPageChanged(tab: WebTab) {}
        /** A box popped up on the page, please note it in [dialogs] and wait for a reply. */
        fun onDialog(tab: WebTab) {}
        /** Received the favicon for the current page. */
        fun onIcon(tab: WebTab, icon: Bitmap) {}
    }

    /**
     * web_upload The file that is placed here first and is to be handed over to the page file selection
     * box (the content:// address of the workspace file); it is taken away once when the selection box
     * pops up. If there are no documents to be submitted, the selection box is directly canceled. Only
     * read and write on the main thread.
     */
    var pendingUpload: List<Uri>? = null

    /** The number of times the selection box popped up on the page but there were no pending documents (cancelled). */
    var choosersRefused: Int = 0
        private set

    /** Which task does this label belong to (the user's own browser page has its own number); when the user closes the page lent to the task and the task accepts it, it changes to that task. */
    var ownerTaskId: String = ownerTaskId
        internal set

    val view: WebView = WebView(context)

    /** The page popup waiting for a reply on this label. */
    val dialogs = PageDialogs()

    var listener: Listener? = null

    /** When the page pops up, it is the address of the page that opened it. The pop-up tab can only navigate within this website for life. */
    var popupOpener: String? = null

    // The pop-up tag has already been loaded on the same site page: if you want to go to other websites
    // later, just block that navigation and no longer close the tag.
    private var popupCommitted = false
    /** I have answered the account and password for the network agent several times (only on the main thread). */
    private var proxyAuthAnswers = 0

    /** Whether the main document is loading. Only read and write on the main thread. */
    var loading: Boolean = false
        private set

    /** The loading progress of the main document (0–100). Only read and write on the main thread. */
    var progress: Int = 0
        private set

    /** Whether the latest [load] waited until the loading was completed (including the error page); the timeout is false. */
    var lastLoadFinished: Boolean = false
        private set

    /** The latest HTTP error status of the main document (4xx/5xx); if it cannot be obtained or there is no error, it is null. */
    var httpErrorStatus: Int? = null
        private set

    /** The error code given by the browser when the main document was not opened last time (such as net::ERR_NAME_NOT_RESOLVED); if it is opened or there is no error, it is null. */
    var mainFrameError: String? = null
        private set

    /** During the most recent [load] period, the page changed to download (the main document is a file). */
    var loadBecameDownload: Boolean = false
        private set

    /** The most recent blocked app link; [load] is cleared at the beginning. */
    var lastExternalLink: ExternalLink? = null
        private set

    /** [destroy] is followed by true. */
    @Volatile var destroyed: Boolean = false
        private set

    /** It is true after the rendering process crashes or is recycled by the system. The tag can no longer be used and will be reopened by the upper layer. */
    @Volatile var crashed: Boolean = false
        private set

    /** The rendering process did not crash by itself, but was reclaimed by the system to free up memory ([crashed] is true at the same time). */
    @Volatile var reclaimed: Boolean = false
        private set

    private var pending: CompletableDeferred<Boolean>? = null
    // After initiating a new load, it will wait until its own onPageStarted before recognizing
    // onPageFinished. The late completion callback of the last navigation will not count.
    private var armed = false
    private var mainFrameFailed = false
    private var mainUrl: String? = null

    init {
        // Configuration is set before WebView does anything else
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(view, PROFILE)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = false
            setGeolocationEnabled(false)
            // The new window is handed to the tag pool through onCreateWindow; the script cannot open
            // the window by itself without user operation.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        devtools.prepare(this, context)
        // Third-party cookies are turned off (decision); if there are subsequent sites that do need to
        // be determined separately
        cookieManager().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoaders.firstNotNullOfOrNull { it.shouldInterceptRequest(request.url) }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame && refusePopup(url)) return true
                // Links that jump to App (intent:, custom protocol, app store) will not jump; only those
                // in the main document will be told to the tag pool (silent blocking in sub-frames)
                WebUrlPolicy.external(url)?.let { link ->
                    if (request.isForMainFrame) {
                        lastExternalLink = link
                        listener?.onExternalLink(this@WebTab, link)
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                // The first navigation of the pop-up window does not necessarily go through
                // shouldOverrideUrlLoading: Check again here
                if (url != null && refusePopup(url)) {
                    v.stopLoading()
                    return
                }
                loading = true
                armed = true
                mainFrameFailed = false
                mainFrameError = null
                httpErrorStatus = null
                mainUrl = url
                devtools.onPageStarted(this@WebTab, url)
                listener?.onPageChanged(this@WebTab)
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                listener?.onPageChanged(this@WebTab)
            }

            // Check the website of the pop-up window again when the page starts to display (the last
            // step when the first two may be bypassed)
            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (url != null && refusePopup(url)) v.stopLoading()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (!armed) return
                loading = false
                finish(!mainFrameFailed)
                listener?.onPageFinished(this@WebTab)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    mainFrameError = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "net::error ${error.errorCode}"
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    httpErrorStatus = response.statusCode
                    mainFrameFailed = true
                }
            }

            // The network proxy requires an account and password: only answer the HTTP proxy in Settings
            // › Network Proxy. If you answer it several times and still ask (the account and password
            // are incorrect), don’t answer it again; the website’s own login box is not filled in here.
            override fun onReceivedHttpAuthRequest(v: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                val credentials = WebProxy.credentialsFor(host)
                if (credentials == null || proxyAuthAnswers >= MAX_PROXY_AUTH_ANSWERS) {
                    handler.cancel()
                    return
                }
                proxyAuthAnswers++
                handler.proceed(credentials.first, credentials.second)
            }

            // If there is a problem with the certificate, we will not continue.
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == mainUrl) {
                    mainFrameFailed = true
                    mainFrameError = "certificate_error ${error.primaryError}"
                }
            }

            // Return true so that the entire App will not be killed; the tag will be invalid and
            // destroyed by the tag pool
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone for tab $id crashed=${detail.didCrash()}")
                crashed = true
                reclaimed = !detail.didCrash()
                loading = false
                (view.parent as? ViewGroup)?.removeView(view)
                finish(false)
                listener?.onCrashed(this@WebTab)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                return listener?.onCreateWindow(this@WebTab, isUserGesture, resultMsg) ?: false
            }

            override fun onCloseWindow(window: WebView) {
                listener?.onCloseWindow(this@WebTab)
            }

            override fun onProgressChanged(v: WebView, newProgress: Int) {
                progress = newProgress
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedIcon(v: WebView, icon: Bitmap?) {
                if (icon != null) listener?.onIcon(this@WebTab, icon)
            }

            // Page pop-up: write down and return (returning true means handling it yourself, the system
            // does not pop-up), does not occupy the interface thread; the page script stops before
            // replying.
            override fun onJsAlert(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.ALERT, message, null, result)

            override fun onJsConfirm(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.CONFIRM, message, null, result)

            override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                held(PageDialog.PROMPT, message, defaultValue, result)

            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.BEFORE_UNLOAD, message, null, result)

            // The user cannot see this label, and the authorization popup is equivalent to clicking
            // Agree for the user: reject it all.
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            // File selection box: only submit the workspace files placed in advance by web_upload, and
            // clear them immediately after taking them; if not, cancel the system selector without
            // popping up.
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val files = pendingUpload
                pendingUpload = null
                if (files.isNullOrEmpty()) {
                    choosersRefused++
                    callback.onReceiveValue(null)
                    return true
                }
                val picked = if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files else files.take(1)
                callback.onReceiveValue(picked.toTypedArray())
                return true
            }
        }
        // The download is not accepted temporarily and will be taken over by the download module. The
        // download is handed over to the tag pool (dropped to the workspace); the file will not be
        // opened or installed.
        view.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val l = listener
            if (l == null) Log.i(TAG, "download ignored on tab $id: ${url.take(80)}")
            else l.onDownload(this, url, userAgent.orEmpty(), contentDisposition, mimetype)
            // The navigation that is waiting for loading becomes a download: the page will not be loaded
            // again and there is no need to wait for timeout.
            if (pending != null) {
                loadBecameDownload = true
                loading = false
                finish(false)
            }
        }
    }

    /** Note the page pop-up box; the response is handed to the browser on the main thread (JsResult can only be returned once). */
    private fun held(kind: String, message: String?, defaultText: String?, result: JsResult): Boolean {
        val text = message.orEmpty().take(PageDialog.MAX_MESSAGE)
        dialogs.open(PageDialog(kind, text, defaultText?.take(PageDialog.MAX_MESSAGE)), object : DialogReply {
            override fun accept(text: String?) {
                view.post { if (result is JsPromptResult && text != null) result.confirm(text) else result.confirm() }
            }
            override fun dismiss() {
                view.post { result.cancel() }
            }
        })
        Log.i(TAG, "page dialog $kind held on tab $id")
        listener?.onDialog(this)
        // Loading pop-up box: The page has stopped here, and the loading wait will no longer wait until
        // it times out.
        if (pending != null) finish(true)
        return true
    }

    /**
     * If the pop-up tab needs to navigate to a different website than the page that opened it (except
     * about:blank): block. The tag pool is turned off when it is not loaded on the same site page, and
     * then only this navigation is blocked.
     */
    private fun refusePopup(url: String): Boolean {
        val opener = popupOpener ?: return false
        if (url == "about:blank" || WebUrlPolicy.sameSite(opener, url)) {
            if (url != "about:blank") popupCommitted = true
            return false
        }
        if (!popupCommitted) listener?.onPopupRefused(this, url)
        else Log.i(TAG, "popup tab $id kept on its site, refused ${url.take(80)}")
        return true
    }

    private fun finish(ok: Boolean) {
        pending?.complete(ok)
        pending = null
        armed = false
    }

    /** Open the URL and wait for the main document to load; timeout, main document error, or HTTP 4xx/5xx returns false. Only http/https URLs are accepted. */
    suspend fun load(url: String, timeoutMs: Long = 20_000): Boolean = withContext(Dispatchers.Main) {
        if (crashed) return@withContext false
        WebUrlPolicy.checkNavigable(url)?.let {
            Log.w(TAG, "refused to load a non-web address on tab $id: $it")
            lastLoadFinished = false
            return@withContext false
        }
        // Set up the desktop simulation before navigating for the first time (if the connection fails,
        // wait at most for a short while and open it as usual)
        devtools.beforeLoad(this@WebTab, url)
        if (crashed || destroyed) return@withContext false
        finish(false)
        lastExternalLink = null
        lastLoadFinished = false
        mainFrameError = null
        loadBecameDownload = false
        val done = CompletableDeferred<Boolean>()
        pending = done
        loading = true
        view.loadUrl(url)
        val ok = withTimeoutOrNull(timeoutMs) { done.await() }
        lastLoadFinished = ok != null
        if (ok == null) {
            if (pending === done) finish(false)
            view.stopLoading()
            loading = false
        }
        ok ?: false
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        dialogs.close()
        devtools.release(this)
        listener = null
        finish(false)
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    companion object {
        private const val TAG = "MinaWebTab"
        /** The same tab page can alternatively answer the upper limit of the account and password: If you ask again, the account and password are incorrect, so you won’t answer again to avoid being redirected. */
        private const val MAX_PROXY_AUTH_ANSWERS = 3
        private const val PROFILE = "mina"

        /**
         * The cookie library where the web page login data is located: when supporting multiple
         * configurations, it is configured by mina, otherwise it is the default. Called by the main
         * thread. When multiple configurations are not supported, it is shared with any other WebView in
         * the App process: there must be no other WebView in the App to handle website login, otherwise
         * the login status will be mixed together.
         */
        fun cookieManager(): CookieManager =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager
            } else {
                CookieManager.getInstance()
            }

        /** Web storage (localStorage, IndexedDB, etc.) with the same configuration as [cookieManager]. Called by the main thread. */
        fun webStorage(): WebStorage =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).webStorage
            } else {
                WebStorage.getInstance()
            }
    }
}
