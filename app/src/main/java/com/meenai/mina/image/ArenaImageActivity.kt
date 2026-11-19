package com.meenai.mina.image

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive

/**
 * Visible In-App Activity for LM Arena automation (arena.ai/image/direct).
 * Gives the user full visibility over page load, prompt entry, and allows
 * tapping the Cloudflare "Verify you are human" checkbox directly if requested.
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
            instance?.finish()
        }
    }

    private var webView: WebView? = null
    private var statusMessage by mutableStateOf("Loading LM Arena...")
    private var webProgress by mutableFloatStateOf(0.1f)
    private var isPageLoaded by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
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
        val promptEscaped = JsonPrimitive(session.prompt).toString()

        val modelParam = when {
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
        val targetUrl = "$ARENA_URL?model_a=$modelParam&model=$modelParam"

        val automationJs = """
            (function() {
                function log(msg) {
                    if (window.AndroidBridge && window.AndroidBridge.log) {
                        window.AndroidBridge.log(msg);
                    } else {
                        console.log(msg);
                    }
                }

                const targetModel = "$targetModel";
                const promptText = $promptEscaped;
                log("Visible Arena automation started for: " + targetModel);

                function dismissPopups() {
                    try {
                        const buttons = Array.from(document.querySelectorAll('button'));
                        const agreeBtn = buttons.find(b => {
                            const t = (b.textContent || '').trim().toLowerCase();
                            return t === 'agree' || t === 'i agree' || t === 'accept';
                        });
                        if (agreeBtn) {
                            log("Dismissing Terms modal (Agree)");
                            agreeBtn.click();
                        }

                        const bannerBtn = document.querySelector('button[aria-label="Dismiss banner"]');
                        if (bannerBtn) bannerBtn.click();

                        const closeBtn = document.querySelector('button[aria-label="Close"], button[aria-label="dismiss" i]');
                        if (closeBtn) closeBtn.click();
                    } catch (e) {
                        log("dismissPopups err: " + e.message);
                    }
                }

                let modelAttempts = 0;
                function ensureModel(onReady) {
                    dismissPopups();
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
                                log("Model not found in list, dismissing menu");
                                document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
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
                    dismissPopups();
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
                        dismissPopups();
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

                        setTimeout(() => {
                            dismissPopups();
                            document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
                        }, 800);

                        pollForOutput();
                    }, 600);
                }

                function pollForOutput() {
                    log("Polling for generated image...");
                    let checks = 0;
                    const maxChecks = 130;

                    const timer = setInterval(() => {
                        checks++;
                        dismissPopups();

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

                setTimeout(() => {
                    ensureModel(() => {
                        enterPrompt();
                    });
                }, 1500);
            })();
        """.trimIndent()

        class ArenaBridge {
            @JavascriptInterface
            fun onImageReady(base64Data: String) {
                session.logger?.invoke("ArenaImageActivity: onImageReady called, payload length=${base64Data.length}")
                try {
                    val cleanB64 = base64Data.substringAfter("base64,").trim()
                    val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        statusMessage = "Image ready! Returning to chat..."
                        session.resultDeferred.complete(bytes)
                        activeSession = null
                        finish()
                    } else {
                        session.resultDeferred.completeExceptionally(ImageClientException("Decoded image bytes are empty"))
                        activeSession = null
                        finish()
                    }
                } catch (e: Exception) {
                    session.resultDeferred.completeExceptionally(ImageClientException("Failed to decode image: ${e.message}", e))
                    activeSession = null
                    finish()
                }
            }

            @JavascriptInterface
            fun onGenerationError(errorMsg: String) {
                session.logger?.invoke("ArenaImageActivity: generation error: $errorMsg")
                statusMessage = "Error: $errorMsg"
                session.resultDeferred.completeExceptionally(ImageClientException("LM Arena error: $errorMsg"))
                activeSession = null
                finish()
            }

            @JavascriptInterface
            fun log(msg: String) {
                session.logger?.invoke("ArenaWeb[JS]: $msg")
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
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
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

            var automationInjected = false
            fun tryInject(view: WebView?) {
                if (automationInjected || view == null) return
                view.evaluateJavascript("""
                    (function() {
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
                    val cleanState = state?.replace("\"", "")?.trim()
                    if (cleanState == "challenge") {
                        statusMessage = "Cloudflare verification needed — please tap checkbox below"
                        // Do not abort! Allow user to tap the Turnstile checkbox on screen
                        view.postDelayed({ tryInject(view) }, 1000)
                    } else if (cleanState == "ready" && !automationInjected) {
                        automationInjected = true
                        isPageLoaded = true
                        statusMessage = "LM Arena ready. Automating prompt..."
                        view.evaluateJavascript(automationJs, null)
                    } else if (!automationInjected) {
                        view.postDelayed({ tryInject(view) }, 600)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val mainUrl = view?.url ?: url ?: ""
                    if (!automationInjected && (mainUrl.contains("arena.ai") || mainUrl.contains("lmarena.ai"))) {
                        tryInject(view)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "network error"
                        session.logger?.invoke("ArenaImageActivity: main frame error: $desc")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    webProgress = newProgress / 100f
                    if (newProgress >= 100 && !automationInjected) {
                        tryInject(view)
                    }
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

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "LM Arena",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "• $targetModel",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        text = statusMessage,
                                        fontSize = 11.sp,
                                        color = if (statusMessage.contains("Cloudflare")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { handleUserClose() }) {
                                    Icon(
                                        imageVector = MinaIcons.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            )
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
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

    private fun handleUserClose() {
        activeSession?.resultDeferred?.completeExceptionally(CancellationException("LM Arena cancelled by user"))
        activeSession = null
        finish()
    }

    override fun onDestroy() {
        val s = activeSession
        if (s != null && !s.resultDeferred.isCompleted) {
            s.resultDeferred.completeExceptionally(CancellationException("LM Arena activity closed"))
            activeSession = null
        }
        try {
            webView?.apply {
                stopLoading()
                removeJavascriptInterface("AndroidBridge")
                destroy()
            }
            webView = null
        } catch (ignored: Throwable) {}
        instance = null
        super.onDestroy()
    }
}
