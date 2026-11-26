package com.meenai.mina.image

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.meenai.mina.core.image.ImageClientException
import com.meenai.mina.ui.theme.HarnessTheme
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.requestMaxRefreshRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive

/**
 * Visible In-App Bottom Sheet Activity for LM Arena automation (arena.ai/image/direct).
 * Opens as a bottom sheet modal (~68% screen height) over chat.
 * Allows user to log in with Google/credentials (cookies permanently saved),
 * complete Cloudflare verification, monitor generation, and capture images.
 * Automatically closes as soon as the image generation completes.
 */
class ArenaImageActivity : ComponentActivity() {

    data class Session(
        val model: String,
        val prompt: String,
        val size: String?,
        val resultDeferred: CompletableDeferred<ByteArray>,
        val logger: ((String) -> Unit)?,
    )

    companion object {
        const val ARENA_URL = "https://arena.ai/image/direct"
        var activeSession: Session? = null
        private var instance: ArenaImageActivity? = null

        private val ARENA_SHEET_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        private val ARENA_HANDLE_SHAPE = RoundedCornerShape(2.dp)
        private val EXACT_ALLOWED_HOSTS = setOf(
            "arena.ai",
            "lmarena.ai",
            "challenges.cloudflare.com",
            "accounts.google.com",
            "hcaptcha.com",
            "discord.com",
            "github.com",
        )

        private val WILDCARD_ALLOWED_SUFFIXES = listOf(
            ".arena.ai",
            ".lmarena.ai",
            ".cloudflare.com",
            ".google.com",
            ".gstatic.com",
            ".googleusercontent.com",
            ".hcaptcha.com",
        )

        internal fun isArenaAutomationTarget(scheme: String?, host: String?): Boolean {
            if (!scheme.equals("https", ignoreCase = true)) return false
            val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && '@' !in it } ?: return false
            return h == "arena.ai" || h.endsWith(".arena.ai") || h == "lmarena.ai" || h.endsWith(".lmarena.ai")
        }

        internal fun isAllowedNavigation(scheme: String?, host: String?, rawUrl: String): Boolean {
            if (rawUrl.trim().equals("about:blank", ignoreCase = true)) return true
            if (!scheme.equals("https", ignoreCase = true)) return false
            val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && '@' !in it } ?: return false
            return h in EXACT_ALLOWED_HOSTS || WILDCARD_ALLOWED_SUFFIXES.any { h.endsWith(it) }
        }

        internal fun isAllowedNavigationUrl(url: String): Boolean {
            if (url.trim().equals("about:blank", ignoreCase = true)) return true
            val androidUri = runCatching { Uri.parse(url) }.getOrNull()
            if (androidUri != null && (!androidUri.scheme.isNullOrBlank() || !androidUri.host.isNullOrBlank())) {
                return isAllowedNavigation(androidUri.scheme, androidUri.host, url)
            }
            val javaUri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return isAllowedNavigation(javaUri.scheme, javaUri.host, url)
        }

        internal fun isArenaUrl(url: String): Boolean {
            val androidUri = runCatching { Uri.parse(url) }.getOrNull()
            if (androidUri != null && (!androidUri.scheme.isNullOrBlank() || !androidUri.host.isNullOrBlank())) {
                return isArenaAutomationTarget(androidUri.scheme, androidUri.host)
            }
            val javaUri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return isArenaAutomationTarget(javaUri.scheme, javaUri.host)
        }

        private fun isAuthOrLoginUri(scheme: String?, host: String?, path: String?): Boolean {
            if (!scheme.equals("https", ignoreCase = true)) return false
            val h = host?.lowercase().orEmpty()
            val p = path?.lowercase().orEmpty()
            return h == "accounts.google.com" || h == "discord.com" || h == "github.com" ||
                p.contains("/login") || p.contains("/auth")
        }

        internal fun modelParamFor(targetModel: String): String = when {
            targetModel.contains("mai", ignoreCase = true) -> "mai-image-2.7"
            targetModel.contains("grok", ignoreCase = true) -> "grok-imagine-image"
            targetModel.contains("muse", ignoreCase = true) -> "muse-image"
            targetModel.contains("seedream", ignoreCase = true) && targetModel.contains("pro", ignoreCase = true) -> "seedream-5.0-pro"
            targetModel.contains("seedream", ignoreCase = true) -> "seedream-5.0-lite"
            targetModel.contains("ideogram", ignoreCase = true) -> "ideogram-4.5"
            targetModel.contains("qwen", ignoreCase = true) -> "qwen-image-2.0"
            targetModel.contains("flux", ignoreCase = true) -> "flux-3-image-klein"
            else -> "mai-image-2.7"
        }

        internal fun buildTargetUrl(model: String): String {
            val targetModel = model.ifBlank { "mai-image-2.7" }
            val modelParam = modelParamFor(targetModel)
            return "$ARENA_URL?model_a=$modelParam&model=$modelParam"
        }

        internal fun buildAutomationJs(targetModel: String, prompt: String): String {
            val modelEscaped = JsonPrimitive(targetModel).toString()
            val promptEscaped = JsonPrimitive(prompt).toString()
            return """
                (function() {
                    function log(msg) {
                        if (window.AndroidBridge && window.AndroidBridge.log) {
                            window.AndroidBridge.log(msg);
                        } else {
                            console.log(msg);
                        }
                    }

                    const targetModel = $modelEscaped;
                    const promptText = $promptEscaped;
                    log("Visible Arena automation started for: " + targetModel);

                    function dismissTermsOnly() {
                        try {
                            const buttons = Array.from(document.querySelectorAll('button'));
                            const agreeBtn = buttons.find(b => {
                                const t = (b.textContent || '').trim().toLowerCase();
                                return t === 'agree' || t === 'i agree' || t === 'accept terms';
                            });
                            if (agreeBtn) {
                                log("Accepting Terms modal");
                                agreeBtn.click();
                            }
                        } catch (e) {
                            log("dismissTermsOnly err: " + e.message);
                        }
                    }

                    let modelAttempts = 0;
                    function ensureModel(onReady) {
                        dismissTermsOnly();
                        const clean = targetModel.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
                        const buttons = Array.from(document.querySelectorAll('button'));

                        const activeModelBtn = buttons.find(b => {
                            const t = (b.textContent || '').replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
                            return t.includes(clean) ||
                                   (clean.includes('mai') && t.includes('mai')) ||
                                   (clean.includes('grok') && t.includes('grok')) ||
                                   (clean.includes('muse') && t.includes('muse')) ||
                                   (clean.includes('seedream') && t.includes('seedream'));
                        });

                        if (activeModelBtn) {
                            log("Model is active: " + (activeModelBtn.textContent || '').trim());
                            onReady();
                            return;
                        }

                        const modelBtn = document.querySelector('[data-testid="model-select"]') ||
                            buttons.find(b => {
                                const aria = (b.getAttribute('aria-label') || '').toLowerCase();
                                const t = (b.textContent || '').toLowerCase();
                                return aria.includes('model') || t.includes('select model') || b.getAttribute('aria-haspopup') === 'dialog';
                            });

                        if (modelBtn) {
                            log("Clicking model selector button...");
                            modelBtn.click();
                            setTimeout(() => {
                                const options = Array.from(document.querySelectorAll('div[role="option"], [role="menuitem"], li, button, [role="button"], span'));
                                const match = options.find(el => {
                                    const t = (el.textContent || '').replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
                                    return t.includes(clean) ||
                                           (clean.includes('mai') && t.includes('mai')) ||
                                           (clean.includes('grok') && t.includes('grok')) ||
                                           (clean.includes('muse') && t.includes('muse')) ||
                                           (clean.includes('seedream') && t.includes('seedream')) ||
                                           (clean.includes('flux') && t.includes('flux')) ||
                                           (clean.includes('ideogram') && t.includes('ideogram'));
                                });
                                if (match) {
                                    log("Selected model from list: " + match.textContent.trim());
                                    match.click();
                                    setTimeout(onReady, 600);
                                } else {
                                    log("Model not found in list, proceeding with current selection");
                                    onReady();
                                }
                            }, 800);
                        } else if (modelAttempts < 3) {
                            modelAttempts++;
                            log("Model button not found yet, retrying (" + modelAttempts + "/3)...");
                            setTimeout(() => ensureModel(onReady), 800);
                        } else {
                            log("Proceeding with current model selection");
                            onReady();
                        }
                    }

                    function enterPrompt() {
                        dismissTermsOnly();
                        log("Looking for textarea...");
                        const textarea = document.querySelector('textarea[name="message"], textarea[placeholder*="Describe the image" i], textarea[placeholder*="prompt" i], textarea[placeholder*="message" i], textarea');
                        if (!textarea) {
                            log("Textarea not found yet, retrying...");
                            setTimeout(enterPrompt, 800);
                            return;
                        }

                        log("Entering prompt into textarea...");
                        textarea.focus();
                        if (textarea._valueTracker) {
                            textarea._valueTracker.setValue('');
                        }
                        const valSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value")?.set;
                        if (valSetter) {
                            valSetter.call(textarea, promptText);
                        } else {
                            textarea.value = promptText;
                        }
                        textarea.setAttribute('data-has-text', 'true');
                        textarea.dispatchEvent(new Event('input', { bubbles: true }));
                        textarea.dispatchEvent(new Event('change', { bubbles: true }));
                        try {
                            textarea.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: promptText }));
                        } catch (_) {}

                        setTimeout(() => {
                            dismissTermsOnly();
                            log("Submitting prompt...");
                            const sendBtn = document.querySelector('button[aria-label="Send message"], button[data-perf-target="send"], button[type="submit"]') ||
                                Array.from(document.querySelectorAll('button')).find(b => {
                                    const t = (b.textContent || '').trim().toLowerCase();
                                    return t === 'send' || t === 'generate';
                                });

                            if (sendBtn) {
                                sendBtn.removeAttribute('disabled');
                                sendBtn.disabled = false;
                                sendBtn.classList.remove('pointer-events-none', 'opacity-50');
                                sendBtn.click();
                            }

                            textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));

                            const form = textarea.closest('form');
                            if (form) {
                                try {
                                    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                                } catch (_) {}
                            }

                            pollForOutput();
                        }, 600);
                    }

                    function pollForOutput() {
                        log("Polling for generated image...");
                        let checks = 0;
                        const maxChecks = 130;

                        const timer = setInterval(() => {
                            checks++;
                            dismissTermsOnly();

                            const imgs = Array.from(document.querySelectorAll('img')).filter(img => {
                                const src = img.src || '';
                                return src && !src.includes('avatar') && !src.includes('logo') && !src.includes('icon') &&
                                       !src.includes('favicon') && !src.includes('svg+xml') && !src.includes('banner') &&
                                       (src.startsWith('blob:') || src.startsWith('data:image') || src.startsWith('http'));
                            });

                            if (imgs.length > 0) {
                                const targetImg = imgs[imgs.length - 1];
                                if (targetImg.complete && (targetImg.naturalWidth > 150 || targetImg.width > 150)) {
                                    clearInterval(timer);
                                    log("Generated image detected (" + targetImg.naturalWidth + "x" + targetImg.naturalHeight + "), extracting...");
                                    extractImage(targetImg);
                                    return;
                                }
                            }

                            if (checks >= maxChecks) {
                                clearInterval(timer);
                                log("Timed out waiting for generated image.");
                                if (window.AndroidBridge) window.AndroidBridge.onGenerationError("Timed out waiting for generated image (65s)");
                            }
                        }, 500);
                    }

                    function extractImage(img) {
                        try {
                            const canvas = document.createElement("canvas");
                            canvas.width = img.naturalWidth || img.width;
                            canvas.height = img.naturalHeight || img.height;
                            const ctx = canvas.getContext("2d");
                            ctx.drawImage(img, 0, 0);
                            const dataUrl = canvas.toDataURL("image/png");
                            const b64 = dataUrl.replace(/^data:image\/[a-z]+;base64,/, "");
                            if (b64 && b64.length > 100) {
                                log("Successfully extracted image Base64 via canvas (length=" + b64.length + ")");
                                if (window.AndroidBridge) window.AndroidBridge.onImageReady(b64);
                                return;
                            }
                        } catch (e) {
                            log("Canvas export failed: " + e.message + ", attempting fetch blob...");
                        }

                        fetch(img.src)
                            .then(r => r.blob())
                            .then(blob => {
                                const reader = new FileReader();
                                reader.onloadend = () => {
                                    const res = reader.result || '';
                                    const b64 = res.replace(/^data:.+;base64,/, "");
                                    log("Successfully extracted image Base64 via fetch (length=" + b64.length + ")");
                                    if (window.AndroidBridge) window.AndroidBridge.onImageReady(b64);
                                };
                                reader.readAsDataURL(blob);
                            })
                            .catch(err => {
                                log("Image fetch failed: " + err.message);
                                if (window.AndroidBridge) window.AndroidBridge.onGenerationError("Image extract failed: " + err.message);
                            });
                    }

                    window.extractCurrentArenaImage = function() {
                        log("Manual capture triggered by user");
                        const imgs = Array.from(document.querySelectorAll('img')).filter(img => {
                            const src = img.src || '';
                            return src && !src.includes('avatar') && !src.includes('logo') && !src.includes('icon') &&
                                   !src.includes('favicon') && !src.includes('svg+xml') && !src.includes('banner') &&
                                   (src.startsWith('blob:') || src.startsWith('data:image') || src.startsWith('http'));
                        });
                        if (imgs.length > 0) {
                            extractImage(imgs[imgs.length - 1]);
                            return true;
                        } else {
                            log("No generated image found on page yet");
                            return false;
                        }
                    };

                    setTimeout(() => {
                        ensureModel(() => {
                            enterPrompt();
                        });
                    }, 1500);
                })();
            """.trimIndent()
        }

        fun start(
            context: Context,
            model: String,
            prompt: String,
            size: String?,
            resultDeferred: CompletableDeferred<ByteArray>,
            logger: ((String) -> Unit)? = null,
        ) {
            activeSession = Session(model, prompt, size, resultDeferred, logger)
            val intent = Intent(context, ArenaImageActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }

        fun cancel() {
            activeSession?.resultDeferred?.completeExceptionally(CancellationException("LM Arena cancelled"))
            activeSession = null
            instance?.let { act -> act.runOnUiThread { act.finish() } }
        }
    }

    private var webView: WebView? = null
    private var statusMessage by mutableStateOf("Loading LM Arena...")
    private var webProgress by mutableFloatStateOf(0.1f)
    private var isPageLoaded by mutableStateOf(false)
    private var displayedModel by mutableStateOf("mai-image-2.7")
    private var automationInjected = false
    private var pendingInjectRunnable: Runnable? = null
    private var currentSession: Session? = null
    private var currentAutomationJs: String = ""

    private fun cancelPendingInjections() {
        pendingInjectRunnable?.let { webView?.removeCallbacks(it) }
        pendingInjectRunnable = null
    }

    private fun scheduleTryInject(view: WebView, delayMs: Long) {
        cancelPendingInjections()
        val runnable = Runnable {
            pendingInjectRunnable = null
            if (!isDestroyed && !isFinishing) {
                tryInject(view)
            }
        }
        pendingInjectRunnable = runnable
        view.postDelayed(runnable, delayMs)
    }

    private fun tryInject(view: WebView?) {
        if (automationInjected || view == null || isDestroyed || isFinishing) return
        val currentUrl = view.url.orEmpty()
        val parsed = runCatching { Uri.parse(currentUrl) }.getOrNull()
        if (!isArenaAutomationTarget(parsed?.scheme, parsed?.host)) return

        view.evaluateJavascript("""
            (function() {
                const href = (window.location.href || '').toLowerCase();
                if (href.includes('accounts.google.com') || href.includes('/login') || href.includes('/auth')) {
                    return "login";
                }
                const title = (document.title || '').toLowerCase();
                const isJustAMoment = title.includes('just a moment');
                const isChallengeStage = !!document.querySelector('#challenge-running, #challenge-stage, .cf-turnstile-wrapper');
                if (isJustAMoment || isChallengeStage) {
                    return "challenge";
                }
                if (document.body && (document.querySelector('textarea') || document.querySelector('button'))) {
                    return "ready";
                }
                return "loading";
            })();
        """.trimIndent()) { state ->
            if (isDestroyed || isFinishing) return@evaluateJavascript
            val latestUrl = view.url.orEmpty()
            val latestParsed = runCatching { Uri.parse(latestUrl) }.getOrNull()
            if (!isArenaAutomationTarget(latestParsed?.scheme, latestParsed?.host)) return@evaluateJavascript

            val cleanState = state?.replace("\"", "")?.trim()
            if (cleanState == "login") {
                statusMessage = "Please sign in to your account..."
                scheduleTryInject(view, 1500)
            } else if (cleanState == "challenge") {
                statusMessage = "Cloudflare verification needed — please tap checkbox below"
                // Do not abort! Allow user to tap the Turnstile checkbox on screen
                scheduleTryInject(view, 1000)
            } else if (cleanState == "ready" && !automationInjected) {
                automationInjected = true
                isPageLoaded = true
                statusMessage = "LM Arena ready. Automating prompt..."
                view.evaluateJavascript(currentAutomationJs, null)
            } else if (!automationInjected) {
                scheduleTryInject(view, 600)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        val session = activeSession
        if (session == null || session.resultDeferred.isCompleted) {
            finish()
            return
        }

        val targetModel = session.model.ifBlank { "mai-image-2.7" }
        displayedModel = targetModel
        currentSession = session
        currentAutomationJs = buildAutomationJs(targetModel, session.prompt)
        val targetUrl = buildTargetUrl(targetModel)
        requestMaxRefreshRate()

        class ArenaBridge {
            @JavascriptInterface
            fun onImageReady(base64Data: String) {
                val active = currentSession ?: activeSession
                active?.logger?.invoke("ArenaImageActivity: onImageReady called, payload length=${base64Data.length}")
                try {
                    val cleanB64 = base64Data.substringAfter("base64,").trim()
                    val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        runOnUiThread {
                            statusMessage = "Image ready! Returning to chat..."
                            active?.resultDeferred?.complete(bytes)
                            if (active == null || activeSession === active) {
                                activeSession = null
                            }
                            finish()
                        }
                    } else {
                        runOnUiThread {
                            statusMessage = "Error: Decoded image bytes are empty"
                            active?.resultDeferred?.completeExceptionally(
                                ImageClientException("Decoded image bytes are empty")
                            )
                            if (active == null || activeSession === active) {
                                activeSession = null
                            }
                            finish()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusMessage = "Error: Failed to decode image"
                        active?.resultDeferred?.completeExceptionally(
                            ImageClientException("Failed to decode image: ${e.message}", e)
                        )
                        if (active == null || activeSession === active) {
                            activeSession = null
                        }
                        finish()
                    }
                }
            }

            @JavascriptInterface
            fun onGenerationError(errorMsg: String) {
                val active = currentSession ?: activeSession
                active?.logger?.invoke("ArenaImageActivity: generation error: $errorMsg")
                runOnUiThread {
                    statusMessage = "Error: $errorMsg"
                    active?.resultDeferred?.completeExceptionally(
                        ImageClientException("LM Arena error: $errorMsg")
                    )
                    if (active == null || activeSession === active) {
                        activeSession = null
                    }
                    finish()
                }
            }

            @JavascriptInterface
            fun log(msg: String) {
                val active = currentSession ?: activeSession
                active?.logger?.invoke("ArenaWeb[JS]: $msg")
                runOnUiThread {
                    when {
                        msg.contains("active:", ignoreCase = true) -> statusMessage = "Model ready. Preparing prompt..."
                        msg.contains("Entering prompt", ignoreCase = true) -> statusMessage = "Typing prompt into LM Arena..."
                        msg.contains("Submitting", ignoreCase = true) -> statusMessage = "Generating image on LM Arena..."
                        msg.contains("Polling", ignoreCase = true) -> statusMessage = "Generating image... Please wait"
                        msg.contains("Generated image detected", ignoreCase = true) -> statusMessage = "Extracting generated image..."
                        msg.contains("Terms", ignoreCase = true) -> statusMessage = "Accepting terms..."
                    }
                }
            }
        }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                // Remove '; wv' tag so Cloudflare treats it as normal Chrome Mobile browser
                val defaultUa = userAgentString
                userAgentString = defaultUa.replace("; wv", "")
            }
            CookieManager.getInstance().let { cm ->
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            addJavascriptInterface(ArenaBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    val parsed = runCatching { Uri.parse(url) }.getOrNull()
                    val active = currentSession ?: activeSession
                    if (!isAllowedNavigation(parsed?.scheme, parsed?.host, url)) {
                        active?.logger?.invoke("ArenaImageActivity: blocked navigation to disallowed URL: $url")
                        runOnUiThread {
                            statusMessage = "Blocked navigation to untrusted URL"
                        }
                        return true
                    }
                    active?.logger?.invoke("ArenaImageActivity: shouldOverrideUrlLoading: $url")
                    if (isAuthOrLoginUri(parsed?.scheme, parsed?.host, parsed?.path)) {
                        runOnUiThread {
                            statusMessage = "Please sign in to your account..."
                        }
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    val mainUrl = view?.url ?: url ?: ""
                    val parsed = runCatching { Uri.parse(mainUrl) }.getOrNull()
                    if (isAuthOrLoginUri(parsed?.scheme, parsed?.host, parsed?.path)) {
                        statusMessage = "Please sign in to your account..."
                        return
                    }
                    if (!automationInjected && isArenaAutomationTarget(parsed?.scheme, parsed?.host)) {
                        tryInject(view)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "network error"
                        (currentSession ?: activeSession)?.logger?.invoke("ArenaImageActivity: main frame error: $desc")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    webProgress = newProgress / 100f
                    if (newProgress >= 100 && !automationInjected) {
                        val parsed = runCatching { Uri.parse(view?.url.orEmpty()) }.getOrNull()
                        if (isArenaAutomationTarget(parsed?.scheme, parsed?.host)) {
                            tryInject(view)
                        }
                    }
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = view
                    resultMsg.sendToTarget()
                    return true
                }
            }

            session.logger?.invoke("ArenaImageActivity: loading $targetUrl")
            loadUrl(targetUrl)
        }

        setContent {
            HarnessTheme(dark = true) {
                BackHandler {
                    handleUserClose()
                }

                // Modal backdrop dim with bottom sheet modal surface
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { handleUserClose() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.68f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {},
                        shape = ARENA_SHEET_SHAPE,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Drag handle indicator
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 36.dp, height = 4.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            shape = ARENA_HANDLE_SHAPE
                                        )
                                )
                            }

                            // Compact Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { handleUserClose() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = MinaIcons.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "LM Arena",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "• $displayedModel",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                        )
                                    }
                                    Text(
                                        text = statusMessage,
                                        fontSize = 11.sp,
                                        color = if (statusMessage.contains("Cloudflare") || statusMessage.contains("sign in", ignoreCase = true))
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        val view = webView ?: return@TextButton
                                        val parsed = runCatching { Uri.parse(view.url.orEmpty()) }.getOrNull()
                                        if (isArenaAutomationTarget(parsed?.scheme, parsed?.host)) {
                                            view.evaluateJavascript(
                                                "if (window.extractCurrentArenaImage) window.extractCurrentArenaImage();",
                                                null
                                            )
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "Capture",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                }
                            }

                            if (webProgress < 1.0f) {
                                LinearProgressIndicator(
                                    progress = { webProgress },
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                AndroidView(
                                    factory = { webView!! },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleUserClose() {
        cancelPendingInjections()
        val s = currentSession ?: activeSession
        if (s != null && !s.resultDeferred.isCompleted) {
            s.resultDeferred.completeExceptionally(CancellationException("LM Arena cancelled by user"))
        }
        if (s == null || activeSession === s) {
            activeSession = null
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelPendingInjections()
        val session = activeSession ?: return
        val targetModel = session.model.ifBlank { "mai-image-2.7" }
        automationInjected = false
        isPageLoaded = false
        displayedModel = targetModel
        currentSession = session
        currentAutomationJs = buildAutomationJs(targetModel, session.prompt)
        statusMessage = "Loading LM Arena..."
        webProgress = 0.1f
        val targetUrl = buildTargetUrl(targetModel)
        session.logger?.invoke("ArenaImageActivity: onNewIntent loading $targetUrl")
        webView?.loadUrl(targetUrl)
    }

    override fun onDestroy() {
        cancelPendingInjections()
        val s = currentSession ?: activeSession
        if (s != null && !s.resultDeferred.isCompleted) {
            s.resultDeferred.completeExceptionally(CancellationException("LM Arena activity closed"))
        }
        if (s == null || activeSession === s) {
            activeSession = null
        }
        currentSession = null
        try {
            webView?.let { view ->
                view.stopLoading()
                view.removeJavascriptInterface("AndroidBridge")
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webView = null
        } catch (ignored: Throwable) {}
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
