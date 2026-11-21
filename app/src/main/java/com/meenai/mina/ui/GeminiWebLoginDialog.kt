package com.meenai.mina.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    var detectedEmail by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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

                    Spacer(modifier = Modifier.width(8.dp))

                    MinaButton(
                        text = ModelSetupCopy.CANCEL,
                        onClick = onDismiss,
                        kind = ButtonKind.Text
                    )
                }

                if (activeTab == 0) {
                    // In-app WebView Google Login
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { context ->
                                createGoogleLoginWebView(
                                    context = context,
                                    onLoadingChange = { loading -> isLoading = loading },
                                    onLoginCaptured = { session ->
                                        detectedEmail = session.email
                                        onSuccess(session)
                                    },
                                    onError = onError
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .align(Alignment.TopCenter)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).align(Alignment.Center),
                                    strokeWidth = 2.dp,
                                    color = c.accent
                                )
                            }
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
    onLoadingChange: (Boolean) -> Unit,
    onLoginCaptured: (GeminiWebSession) -> Unit,
    onError: (String) -> Unit
): WebView {
    val json = Json { ignoreUnknownKeys = true }
    var loginHandled = false
    var captureAttempts = 0

    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = GeminiWebClient.CHROME_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        fun isGeminiUrl(url: String?): Boolean =
            url != null && (url.startsWith("https://gemini.google.com") || url.contains("gemini.google.com/app"))

        // Gemini is a single-page app: onPageFinished fires before WIZ_global_data
        // exists, so reading it once left the dialog stuck at "signing in".
        // Poll for a few seconds and re-read on every in-app navigation instead.
        fun capture() {
            if (loginHandled || captureAttempts > 20) return
            val rawCookies = cookieManager.getCookie("https://gemini.google.com").orEmpty()
            if (!rawCookies.contains("__Secure-1PSID")) return
            captureAttempts++
            evaluateJavascript(
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

                    if (snlm0e.isNotBlank()) {
                        loginHandled = true
                        cookieManager.flush()
                        onLoginCaptured(
                            GeminiWebSession(
                                cookies = rawCookies,
                                snlm0e = snlm0e,
                                cfb2h = cfb2h,
                                fdrfje = fdrfje,
                                email = email,
                                signedIn = true
                            )
                        )
                    } else {
                        postDelayed({ capture() }, 500)
                    }
                } catch (_: Exception) {
                    postDelayed({ capture() }, 500)
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadingChange(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLoadingChange(false)
                if (isGeminiUrl(url)) capture()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (isGeminiUrl(url)) capture()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isGeminiUrl(url)) capture()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        loadUrl("https://accounts.google.com/ServiceLogin?continue=https://gemini.google.com/app")
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
                        val session = if (tokenInput.isNotBlank()) {
                            GeminiWebSession(
                                cookies = fullCookies,
                                snlm0e = tokenInput.trim().trim('"'),
                                email = emailInput.trim(),
                                signedIn = true
                            )
                        } else {
                            val detected = withContext(Dispatchers.IO) {
                                GeminiWebClient.fetchSessionFromCookies(GeminiWebClient.geminiWebHttp, fullCookies)
                            }
                            detected.copy(email = emailInput.trim().ifBlank { detected.email })
                        }
                        onSuccess(session)
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
