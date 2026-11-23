package com.meenai.mina.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meenai.mina.R
import com.meenai.mina.core.gemini.GeminiProbe
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import java.util.concurrent.TimeUnit

private const val GEMINI_LOGIN_START = "https://gemini.google.com/app"
private const val LOGIN_MAX_POLLS = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiWebLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: (GeminiWebSession) -> Unit,
    onError: (String) -> Unit = {},
) {
    val c = MinaTheme.colors
    var activeTab by remember { mutableIntStateOf(0) } // 0: WebView, 1: Manual
    var isLoading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()

    // Back should walk the WebView history before it closes the whole sheet.
    BackHandler(enabled = activeTab == 0) {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(R.string.signin_gemini_title),
                            style = MinaType.Title,
                            color = c.ink
                        )
                        Text(
                            text = tr(R.string.signin_row_gemini),
                            style = MinaType.Hint,
                            color = c.ink2
                        )
                    }

                    // Tab selector (WebView / Manual)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.fill)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (activeTab == 0) c.surface else Color.Transparent)
                                .clickable { activeTab = 0 }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Web",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (activeTab == 0) c.accent else c.ink2
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (activeTab == 1) c.surface else Color.Transparent)
                                .clickable { activeTab = 1 }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Manual",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (activeTab == 1) c.accent else c.ink2
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    if (activeTab == 0) {
                        MinaButton(
                            text = "Reload",
                            onClick = {
                                hint = null
                                isLoading = true
                                val wv = webView
                                if (wv?.url?.startsWith("https://gemini.google.com") == true) {
                                    wv.reload()
                                } else {
                                    wv?.loadUrl(GEMINI_LOGIN_START)
                                }
                            },
                            kind = ButtonKind.Text,
                            size = ButtonSize.Small,
                        )
                    }

                    MinaButton(
                        text = ModelSetupCopy.CANCEL,
                        onClick = onDismiss,
                        kind = ButtonKind.Text,
                        size = ButtonSize.Small,
                    )
                }

                if (isLoading && activeTab == 0) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = c.accent,
                        trackColor = c.hair,
                    )
                }

                if (activeTab == 0) {
                    // In-app WebView Google Login
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { context ->
                                createGoogleLoginWebView(
                                    context = context,
                                    scope = scope,
                                    onWebViewReady = { webView = it },
                                    onLoadingChange = { loading -> isLoading = loading },
                                    onHint = { message -> hint = message },
                                    onLoginCaptured = { session ->
                                        isLoading = false
                                        hint = null
                                        onSuccess(session)
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        hint?.let { message ->
                            Text(
                                text = message,
                                style = MinaType.Hint,
                                color = c.bad,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .background(c.fill)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                } else {
                    // Manual Cookie / Token Entry fallback
                    ManualCookieEntry(
                        onSuccess = onSuccess,
                        onError = onError
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createGoogleLoginWebView(
    context: Context,
    scope: CoroutineScope,
    onWebViewReady: (WebView) -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onHint: (String?) -> Unit,
    onLoginCaptured: (GeminiWebSession) -> Unit
): WebView {
    val json = Json { ignoreUnknownKeys = true }
    var loginHandled = false
    var captureAttempts = 0
    var captureScheduled = false
    var htmlFetchRunning = false
    var htmlFallbackAttempts = 0
    var pageTextTokenTried = false
    val popups = mutableListOf<WebView>()

    // Use the device's real WebView version (minus the "; wv" marker). Google
    // rejects old browser versions and its __Secure-1PSIDTS cookie is bound to
    // the exact User-Agent, so this same string must be used for later API calls.
    val userAgent = runCatching {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }.getOrDefault(GeminiWebClient.CHROME_USER_AGENT)

    return WebView(context).apply {
        val mainView = this
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = userAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        fun isGeminiUrl(url: String?): Boolean =
            url != null && (url.startsWith("https://gemini.google.com") || url.contains("gemini.google.com/app"))

        // Cookies for a Google account live on several hosts. Reading only
        // gemini.google.com can miss the auth cookies the API needs.
        fun collectCookies(): String {
            cookieManager.flush()
            val merged = linkedMapOf<String, String>()
            for (host in listOf("https://accounts.google.com", "https://google.com", "https://gemini.google.com")) {
                val raw = cookieManager.getCookie(host).orEmpty()
                for (part in raw.split(';')) {
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val name = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    if (name.isNotEmpty()) merged[name] = value
                }
            }
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        fun finish(session: GeminiWebSession) {
            if (loginHandled) return
            loginHandled = true
            cookieManager.flush()
            onHint(null)
            onLoginCaptured(session)
        }

        val capturer = object {
            // Gemini is a single-page app: onPageFinished fires before the page has
            // finished booting, so read the page once per navigation and keep
            // polling for a while. If WIZ_global_data never exposes SNlM0e (newer
            // builds keep it in the app bundle), fall back to the HTML session.
            fun fetchSessionFromHtml(cookies: String) {
                if (loginHandled || htmlFetchRunning) return
                htmlFetchRunning = true
                htmlFallbackAttempts++
                onHint("Finishing sign-in…")
                scope.launch {
                    val session = withContext(Dispatchers.IO) {
                        runCatching {
                            val http = GeminiWebClient.geminiWebHttp.newBuilder()
                                .cookieJar(CookieJar.NO_COOKIES)
                                .callTimeout(30, TimeUnit.SECONDS)
                                .build()
                            GeminiWebClient.fetchSessionFromCookies(http, cookies, userAgent)
                        }.getOrNull()
                    }
                    htmlFetchRunning = false
                    if (session != null) {
                        verify(session)
                    } else if (htmlFallbackAttempts < 3) {
                        onHint("Still finishing sign-in…")
                        captureAttempts = 0
                        scheduleCapture(2_000)
                    } else {
                        onHint("Could not read the Gemini session. Tap Reload or use Manual.")
                    }
                }
            }

            /**
             * Ask Google whether the captured cookies are really signed in
             * (`GetUserStatus`, the same RPC the web app runs on load). A status of
             * 1000 proves it. Without it, the page still being the signed-in Gemini
             * app is the gate: a guest page never carries the auth cookie, so
             * `__Secure-1PSID` + a real build label means a real account, and the
             * post-login chat self-test shows the user the real state if it isn't.
             */
            fun verify(session: GeminiWebSession) {
                if (loginHandled) return
                onHint("Checking your Gemini account…")
                probeInPage(session) { probe ->
                    if (probe != null) handleProbe(probe, session)
                    else verifyOverHttp(session)
                }
            }

            fun handleProbe(probe: GeminiProbe?, session: GeminiWebSession) {
                if (loginHandled) return
                val signedInPage = session.cookies.contains("__Secure-1PSID") && session.cfb2h.isNotBlank()
                if (probe?.signedIn == true) {
                    finish(
                        session.copy(
                            capacity = probe.capacity,
                            capacityField = probe.capacityField,
                            defaultModelId = probe.models.firstOrNull()?.id.orEmpty(),
                            defaultModelNumber = probe.models.firstOrNull()?.modelNumber ?: 0,
                        )
                    )
                } else if (signedInPage) {
                    // Google's GetUserStatus can lag or reject a live session and
                    // must not block a real login: the auth cookie and the Gemini
                    // page are the proof that the account is signed in.
                    finish(session)
                } else {
                    onHint((probe?.statusMessage() ?: "Could not verify the Gemini session.") + " Tap Reload to try again.")
                    captureAttempts = 0
                    scheduleCapture(3_000)
                }
            }

            fun verifyOverHttp(session: GeminiWebSession) {
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            val http = GeminiWebClient.geminiWebHttp.newBuilder()
                                .cookieJar(CookieJar.NO_COOKIES)
                                .callTimeout(30, TimeUnit.SECONDS)
                                .build()
                            GeminiWebClient.probeSession(http, session)
                        }.getOrNull()
                    }
                    if (loginHandled) return@launch
                    handleProbe(outcome, session)
                }
            }

            fun probeInPage(session: GeminiWebSession, onResult: (GeminiProbe?) -> Unit) {
                if (loginHandled) return
                val atLiteral = JsonPrimitive(session.snlm0e).toString()
                val blLiteral = JsonPrimitive(session.cfb2h).toString()
                val sidLiteral = JsonPrimitive(session.fdrfje).toString()
                mainView.evaluateJavascript(
                    """
                    (function() {
                      window.__geminiProbe = "PENDING";
                      try {
                        var at = $atLiteral;
                        var bl = $blLiteral;
                        var sid = $sidLiteral;
                        var wiz = window.WIZ_global_data || {};
                        if (!at) at = wiz.SNlM0e || "";
                        if (!bl) bl = wiz.cfb2h || "";
                        if (!sid) sid = wiz.FdrFJe || "";
                        var uuid = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now())).toUpperCase();
                        var url = "/_/BardChatUi/data/batchexecute?rpcids=otAQ7b&hl=en&_reqid=" +
                          Math.floor(Math.random() * 90000 + 10000) + "&rt=c&source-path=/app";
                        if (bl) url += "&bl=" + encodeURIComponent(bl);
                        if (sid) url += "&f.sid=" + encodeURIComponent(sid);
                        var body = "at=" + encodeURIComponent(at) + "&f.req=" +
                          encodeURIComponent(JSON.stringify([[["otAQ7b","[]",null,"generic"]]]));
                        fetch(url, {
                          method: "POST",
                          credentials: "include",
                          headers: {
                            "Content-Type": "application/x-www-form-urlencoded;charset=utf-8",
                            "X-Same-Domain": "1",
                            "x-goog-ext-525001261-jspb": '[1,null,null,null,null,null,null,null,[4,5,6,8],null,null,null,null,null,null,null,"' + uuid + '"]',
                            "x-goog-ext-73010989-jspb": "[0]"
                          },
                          body: body
                        }).then(function(r) { return r.text(); })
                          .then(function(t) { window.__geminiProbe = t; })
                          .catch(function(e) { window.__geminiProbe = "ERR:" + e; });
                      } catch (e) {
                        window.__geminiProbe = "ERR:" + e;
                      }
                    })();
                    """.trimIndent(),
                    null
                )
                var tries = 0
                fun poll() {
                    if (loginHandled) return
                    mainView.evaluateJavascript("window.__geminiProbe || ''") { raw ->
                        val value = runCatching {
                            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                json.parseToJsonElement(raw).jsonPrimitive.content
                            } else {
                                raw
                            }
                        }.getOrDefault("")
                        when {
                            value.isBlank() || value == "PENDING" -> {
                                if (tries++ < 24) mainView.postDelayed({ poll() }, 500)
                                else onResult(null)
                            }
                            value.startsWith("ERR:") -> onResult(null)
                            else -> onResult(
                                runCatching { GeminiWebClient.parseProbeResponse(value) }.getOrNull()
                            )
                        }
                    }
                }
                mainView.postDelayed({ poll() }, 600)
            }

            /**
             * Reads `SNlM0e` from the page's own HTML. Newer Gemini builds can
             * leave `window.WIZ_global_data` without the token while the inline
             * bootstrap script still carries it; sending the account cookies with
             * an empty `at` makes Google answer as a guest.
             */
            fun tokenFromPageText(onResult: (String) -> Unit) {
                if (loginHandled || pageTextTokenTried) {
                    onResult("")
                    return
                }
                pageTextTokenTried = true
                mainView.evaluateJavascript(
                    "(function(){try{" +
                        "var m=(document.documentElement.innerHTML||'').match(/\"SNlM0e\"\\s*:\\s*\"([^\"]+)\"/);" +
                        "return m?m[1]:'';}catch(e){return '';}})();"
                ) { raw ->
                    val value = runCatching {
                        if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            json.parseToJsonElement(raw).jsonPrimitive.content
                        } else {
                            raw
                        }
                    }.getOrDefault("")
                    onResult(value)
                }
            }

            fun scheduleCapture(delayMs: Long = 500) {
                if (loginHandled || captureScheduled) return
                captureScheduled = true
                mainView.postDelayed({
                    captureScheduled = false
                    capture()
                }, delayMs)
            }

            fun capture() {
                if (loginHandled) return
                val cookies = collectCookies()
                if (!cookies.contains("__Secure-1PSID")) {
                    // Still on the Google account page. Keep watching: the user may
                    // need a while to type the password or pass 2-step verification.
                    scheduleCapture(900)
                    return
                }
                captureAttempts++
                mainView.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var wiz = window.WIZ_global_data || {};
                            return JSON.stringify({
                                snlm0e: wiz.SNlM0e || "",
                                cfb2h: wiz.cfb2h || "",
                                fdrfje: wiz.FdrFJe || "",
                                email: wiz.o3H74c || ""
                            });
                        } catch(e) {
                            return "{}";
                        }
                    })();
                    """.trimIndent()
                ) { result ->
                    if (loginHandled) return@evaluateJavascript
                    try {
                        val cleanJson = if (result.startsWith("\"") && result.endsWith("\"")) {
                            json.parseToJsonElement(result).jsonPrimitive.content
                        } else result

                        val parsed = json.parseToJsonElement(cleanJson).jsonObject
                        val snlm0e = parsed["snlm0e"]?.jsonPrimitive?.content.orEmpty()
                        val cfb2h = parsed["cfb2h"]?.jsonPrimitive?.content.orEmpty()
                        val fdrfje = parsed["fdrfje"]?.jsonPrimitive?.content.orEmpty()
                        val email = parsed["email"]?.jsonPrimitive?.content.orEmpty()

                        val cookiesNow = collectCookies()
                        if (snlm0e.isNotBlank()) {
                            verify(
                                GeminiWebSession(
                                    cookies = cookiesNow,
                                    snlm0e = snlm0e,
                                    cfb2h = cfb2h,
                                    fdrfje = fdrfje,
                                    email = email,
                                    userAgent = userAgent,
                                    signedIn = true
                                )
                            )
                        } else if (cookiesNow.contains("__Secure-1PSID") && captureAttempts >= 2) {
                            // The page bundle did not expose SNlM0e. Look in the
                            // page HTML, then read the logged-in page over HTTP:
                            // the session needs a real `at` token or Google treats
                            // the account's requests as guest traffic.
                            tokenFromPageText { token ->
                                if (loginHandled) return@tokenFromPageText
                                if (token.isNotBlank()) {
                                    verify(
                                        GeminiWebSession(
                                            cookies = collectCookies(),
                                            snlm0e = token,
                                            cfb2h = cfb2h,
                                            fdrfje = fdrfje,
                                            email = email,
                                            userAgent = userAgent,
                                            signedIn = true
                                        )
                                    )
                                } else {
                                    fetchSessionFromHtml(collectCookies())
                                }
                            }
                        } else if (captureAttempts < LOGIN_MAX_POLLS) {
                            scheduleCapture(700)
                        } else {
                            fetchSessionFromHtml(cookiesNow)
                        }
                    } catch (_: Exception) {
                        if (captureAttempts < LOGIN_MAX_POLLS) scheduleCapture(700)
                        else fetchSessionFromHtml(collectCookies())
                    }
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadingChange(true)
                onHint(null)
                // A new page means a fresh chance to capture the session.
                captureAttempts = 0
                htmlFallbackAttempts = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLoadingChange(false)
                if (isGeminiUrl(url)) capturer.scheduleCapture(300)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (isGeminiUrl(url)) capturer.scheduleCapture(300)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isGeminiUrl(url)) capturer.scheduleCapture(300)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    onLoadingChange(false)
                    onHint("Could not load Google sign-in. Check your connection and tap Reload.")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    onLoadingChange(false)
                    onHint("Google returned HTTP ${errorResponse?.statusCode}. Tap Reload to try again.")
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val message = resultMsg ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = userAgent
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            // Keep one WebView: load the popup target in the main view.
                            mainView.loadUrl(target)
                            return true
                        }
                    }
                }
                popups.add(popup)
                transport.webView = popup
                message.sendToTarget()
                return true
            }
        }

        post { onWebViewReady(this) }
        // A fresh sign-in must start from no account: leftovers in the shared
        // WebView cookie store would otherwise "log in" without the user.
        cookieManager.removeAllCookies {
            cookieManager.flush()
            mainView.post { loadUrl(GEMINI_LOGIN_START) }
        }
    }
}

@Composable
private fun ManualCookieEntry(
    onSuccess: (GeminiWebSession) -> Unit,
    onError: (String) -> Unit
) {
    val c = MinaTheme.colors
    var cookieInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = tr(R.string.signin_gemini_manual_toggle),
            style = MinaType.Title,
            color = c.ink
        )
        Text(
            text = "Paste your __Secure-1PSID (or full cookie string) from gemini.google.com. The SNlM0e token will be auto-detected if left empty.",
            style = MinaType.Hint,
            color = c.ink2,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        OutlinedTextField(
            value = cookieInput,
            onValueChange = { cookieInput = it; errorMsg = null },
            label = { Text(tr(R.string.signin_gemini_cookie_hint)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            placeholder = { Text("__Secure-1PSID=...; __Secure-1PSIDTS=...") }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it; errorMsg = null },
            label = { Text(tr(R.string.signin_gemini_token_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("SNlM0e token string (optional)") }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = emailInput,
            onValueChange = { emailInput = it },
            label = { Text("Account email (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("e.g. user@gmail.com") }
        )

        errorMsg?.let {
            Text(
                text = it,
                color = c.bad,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        MinaButton(
            text = if (isSubmitting) "Detecting session…" else tr(R.string.signin_gemini_submit),
            onClick = {
                val raw = cookieInput.trim()
                if (raw.isBlank()) {
                    errorMsg = "Please enter cookies"
                    return@MinaButton
                }

                val fullCookies = GeminiWebClient.normalizeCookieHeader(raw)
                isSubmitting = true
                errorMsg = null

                scope.launch {
                    try {
                        val token = tokenInput.trim().trim('"')
                        // Always try to read the page: it also gives us the build
                        // label and f.sid the chat endpoint needs. Cookies copied
                        // from a PC are bound to a desktop UA, so try both.
                        val detected = withContext(Dispatchers.IO) {
                            val uas = listOf(
                                GeminiWebClient.CHROME_USER_AGENT,
                                GeminiWebClient.DESKTOP_CHROME_USER_AGENT,
                            )
                            val found = uas.mapNotNull { ua ->
                                runCatching {
                                    // No cookie jar here: it would replace the pasted
                                    // Cookie header with whatever an old session left.
                                    val http = GeminiWebClient.geminiWebHttp.newBuilder()
                                        .cookieJar(CookieJar.NO_COOKIES)
                                        .callTimeout(30, TimeUnit.SECONDS)
                                        .build()
                                    GeminiWebClient.fetchSessionFromCookies(http, fullCookies, ua)
                                }.getOrNull()
                            }
                            found.firstOrNull { it.snlm0e.isNotBlank() } ?: found.firstOrNull()
                        }
                        val session = when {
                            detected != null -> detected.copy(
                                snlm0e = token.ifBlank { detected.snlm0e },
                                email = emailInput.trim().ifBlank { detected.email },
                            )
                            token.isNotBlank() -> GeminiWebSession(
                                cookies = fullCookies,
                                snlm0e = token,
                                email = emailInput.trim(),
                                signedIn = true
                            )
                            else -> throw IllegalStateException(
                                "Could not detect a Gemini session. Check the cookies and try again."
                            )
                        }
                        // Manual sessions must be real too: ask Google whether
                        // these cookies are actually signed in, so we never store
                        // a stale pair and call it a login.
                        val probe = withContext(Dispatchers.IO) {
                            runCatching {
                                val http = GeminiWebClient.geminiWebHttp.newBuilder()
                                    .cookieJar(CookieJar.NO_COOKIES)
                                    .callTimeout(30, TimeUnit.SECONDS)
                                    .build()
                                GeminiWebClient.probeSession(http, session)
                            }.getOrNull()
                        }
                        when {
                            probe != null && !probe.signedIn ->
                                throw IllegalStateException(probe.statusMessage() + " Sign in with a real Google account.")
                            probe != null -> onSuccess(session.copy(
                                capacity = probe.capacity,
                                capacityField = probe.capacityField,
                                defaultModelId = probe.models.firstOrNull()?.id.orEmpty(),
                                defaultModelNumber = probe.models.firstOrNull()?.modelNumber ?: 0,
                            ))
                            else -> throw IllegalStateException(
                                "Google could not verify these cookies. They may be for another browser or account."
                            )
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message ?: "Failed to validate session"
                        onError(errorMsg ?: "")
                    } finally {
                        isSubmitting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting && cookieInput.isNotBlank()
        )
    }
}
