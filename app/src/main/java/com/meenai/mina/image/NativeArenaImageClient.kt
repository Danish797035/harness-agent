package com.meenai.mina.image

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.meenai.mina.R
import com.meenai.mina.core.image.ImageClient
import com.meenai.mina.core.image.ImageClientException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

/**
 * Native Android WebView automation for LM Arena (https://lmarena.ai/?arena=image).
 * Runs completely in-app without requiring external Node.js/Playwright microservices,
 * generating images via LM Arena's top models (MAI-Image-2.6, Grok Imagine 2.0, Muse Image)
 * directly inside an off-screen WebView.
 */
class NativeArenaImageClient(
    private val context: Context,
    private val logger: ((String) -> Unit)? = null,
) : ImageClient {

    companion object {
        const val ARENA_URL = "https://arena.ai/image/direct"
        const val TIMEOUT_MS = 60_000L
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray = withContext(Dispatchers.Main) {
        val resultDeferred = CompletableDeferred<ByteArray>()
        var webView: WebView? = null

        try {
            logger?.invoke("NativeArenaImageClient: initializing off-screen WebView for model=$model")
            val themedContext = android.view.ContextThemeWrapper(context, R.style.Theme_Harness)
            webView = WebView(themedContext).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1366, 768)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = DESKTOP_UA
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    javaScriptCanOpenWindowsAutomatically = true
                }
                CookieManager.getInstance().let { cm ->
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                }
            }

            class ArenaBridge {
                @JavascriptInterface
                fun onImageReady(base64Data: String) {
                    logger?.invoke("NativeArenaImageClient: onImageReady called, payload length=${base64Data.length}")
                    try {
                        val cleanB64 = base64Data.substringAfter("base64,").trim()
                        val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                        if (bytes.isNotEmpty()) {
                            resultDeferred.complete(bytes)
                        } else {
                            resultDeferred.completeExceptionally(ImageClientException("Decoded image bytes are empty"))
                        }
                    } catch (e: Exception) {
                        resultDeferred.completeExceptionally(ImageClientException("Failed to decode image: ${e.message}", e))
                    }
                }

                @JavascriptInterface
                fun onGenerationError(errorMsg: String) {
                    logger?.invoke("NativeArenaImageClient: generation error from page: $errorMsg")
                    resultDeferred.completeExceptionally(ImageClientException("LM Arena error: $errorMsg"))
                }

                @JavascriptInterface
                fun log(msg: String) {
                    logger?.invoke("NativeArena[JS]: $msg")
                }
            }

            webView.addJavascriptInterface(ArenaBridge(), "AndroidBridge")

            val promptEscaped = JsonPrimitive(prompt).toString()
            val targetModel = model.ifBlank { "mai-image-2.6" }
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
                    log("Arena Web native automation started for: " + targetModel);

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
                        const textarea = document.querySelector('textarea[placeholder*="Describe the image" i], textarea[name="message"], textarea[placeholder*="prompt" i], textarea[placeholder*="message" i], textarea');
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
                        const maxChecks = 110;

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
                                if (window.AndroidBridge) window.AndroidBridge.onGenerationError("Timed out waiting for generated image (55s)");
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

            var pageLoaded = false
            fun tryInject(view: WebView?) {
                if (pageLoaded || view == null) return
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
                        logger?.invoke("NativeArenaImageClient: Cloudflare Turnstile detected in page DOM")
                        resultDeferred.completeExceptionally(ImageClientException("LM Arena Cloudflare challenge detected"))
                    } else if (cleanState == "ready" && !pageLoaded) {
                        pageLoaded = true
                        logger?.invoke("NativeArenaImageClient: page loaded, injecting automation script")
                        view.evaluateJavascript(automationJs, null)
                    } else if (!pageLoaded) {
                        view.postDelayed({ tryInject(view) }, 600)
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val mainUrl = view?.url ?: url ?: ""
                    // Only abort if main frame is actually on a Cloudflare challenge interstitial page
                    if (mainUrl.contains("challenges.cloudflare.com") ||
                        (mainUrl.contains("/cdn-cgi/") && mainUrl.contains("chl_page"))) {
                        logger?.invoke("NativeArenaImageClient: Cloudflare challenge page detected ($mainUrl)")
                        resultDeferred.completeExceptionally(ImageClientException("LM Arena Cloudflare challenge detected"))
                        return
                    }
                    if (!pageLoaded && (mainUrl.contains("arena.ai") || mainUrl.contains("lmarena.ai"))) {
                        tryInject(view)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "network error"
                        logger?.invoke("NativeArenaImageClient: main frame error: $desc")
                    }
                }
            }

            webView.webChromeClient = object : WebChromeClient() {}

            logger?.invoke("NativeArenaImageClient: loading $targetUrl")
            webView.loadUrl(targetUrl)

            try {
                withTimeout(TIMEOUT_MS) {
                    resultDeferred.await()
                }
            } catch (tce: kotlinx.coroutines.TimeoutCancellationException) {
                logger?.invoke("NativeArenaImageClient: timed out after ${TIMEOUT_MS}ms")
                throw ImageClientException("LM Arena timed out after ${TIMEOUT_MS / 1000}s", tce)
            }
        } finally {
            try {
                webView?.apply {
                    stopLoading()
                    removeJavascriptInterface("AndroidBridge")
                    destroy()
                }
            } catch (ignored: Throwable) {}
        }
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        return generate(apiKey, model, "$prompt [with reference image]", null)
    }
}
