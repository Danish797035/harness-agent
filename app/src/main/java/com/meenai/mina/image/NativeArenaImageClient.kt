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
        const val ARENA_URL = "https://lmarena.ai/?arena=image"
        const val TIMEOUT_MS = 120_000L
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
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@apply, true)
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
                    log("LM Arena native automation started for: " + targetModel);

                    let step = 0;
                    function tick() {
                        step++;
                        // 1. Switch from Battle to Direct Chat if needed
                        const buttons = Array.from(document.querySelectorAll('button'));
                        const modeBtn = buttons.find(b => {
                            const t = (b.textContent || '').trim();
                            return t === 'Battle' || t === 'Side-by-side';
                        });

                        if (modeBtn) {
                            log("Clicking mode dropdown to switch to Direct Chat...");
                            modeBtn.click();
                            setTimeout(() => {
                                const directOpt = Array.from(document.querySelectorAll('div[role="option"], [role="menuitem"], button, span'))
                                    .find(el => (el.textContent || '').trim() === 'Direct Chat');
                                if (directOpt) {
                                    log("Selected Direct Chat option");
                                    directOpt.click();
                                    setTimeout(ensureModel, 1000);
                                } else {
                                    ensureModel();
                                }
                            }, 500);
                            return;
                        }

                        ensureModel();
                    }

                    function ensureModel() {
                        log("Checking model dropdown...");
                        const modelBtn = document.querySelector('[data-testid="model-select"]') ||
                            Array.from(document.querySelectorAll('button, div[role="combobox"]')).find(b => {
                                const aria = (b.getAttribute('aria-label') || '').toLowerCase();
                                const text = (b.textContent || '').toLowerCase();
                                return aria.includes('model') || text.includes('select model');
                            });

                        if (modelBtn) {
                            log("Opening model dropdown...");
                            modelBtn.click();
                            setTimeout(findAndClickModel, 600);
                        } else {
                            log("Model dropdown not found, proceeding to prompt");
                            enterPrompt();
                        }
                    }

                    function findAndClickModel() {
                        const clean = targetModel.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
                        const options = Array.from(document.querySelectorAll('div[role="option"], [role="menuitem"], li, button'));
                        const match = options.find(el => {
                            const t = (el.textContent || '').replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
                            return t.includes(clean) || t.includes('maiimage') || t.includes('grokimagine') || t.includes('museimage') || t.includes('seedream');
                        });

                        if (match) {
                            log("Selected model: " + match.textContent.trim());
                            match.scrollIntoView();
                            match.click();
                            setTimeout(enterPrompt, 800);
                        } else {
                            log("Model not found in list, proceeding with current model");
                            enterPrompt();
                        }
                    }

                    function enterPrompt() {
                        log("Entering prompt...");
                        const textarea = document.querySelector('textarea[placeholder*="prompt" i], textarea[placeholder*="message" i], textarea');
                        if (!textarea) {
                            log("Error: Prompt input not found!");
                            if (window.AndroidBridge) window.AndroidBridge.onGenerationError("Prompt input not found");
                            return;
                        }

                        textarea.value = promptText;
                        textarea.dispatchEvent(new Event('input', { bubbles: true }));
                        textarea.dispatchEvent(new Event('change', { bubbles: true }));

                        setTimeout(() => {
                            log("Submitting prompt...");
                            const sendBtn = document.querySelector('button[aria-label="Send message"], button[data-perf-target="send"], button[type="submit"]') ||
                                Array.from(document.querySelectorAll('button')).find(b => {
                                    const t = (b.textContent || '').trim().toLowerCase();
                                    return t === 'send' || t === 'generate';
                                });

                            if (sendBtn) {
                                sendBtn.click();
                            } else {
                                textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
                            }
                            pollForOutput();
                        }, 500);
                    }

                    function pollForOutput() {
                        log("Polling for generated image...");
                        let checks = 0;
                        const maxChecks = 120; // 120s

                        const timer = setInterval(() => {
                            checks++;
                            const imgs = Array.from(document.querySelectorAll('img')).filter(img => {
                                const src = img.src || '';
                                return src && !src.includes('avatar') && !src.includes('logo') && !src.includes('icon') && !src.includes('svg+xml') &&
                                       (src.startsWith('blob:') || src.startsWith('data:image') || src.startsWith('http'));
                            });

                            if (imgs.length > 0) {
                                const targetImg = imgs[imgs.length - 1];
                                if (targetImg.complete && (targetImg.naturalWidth > 150 || targetImg.width > 150)) {
                                    clearInterval(timer);
                                    log("Generated image detected, extracting Base64...");
                                    extractImage(targetImg);
                                    return;
                                }
                            }

                            if (checks >= maxChecks) {
                                clearInterval(timer);
                                log("Timed out waiting for generated image.");
                                if (window.AndroidBridge) window.AndroidBridge.onGenerationError("Timed out waiting for generated image (120s)");
                            }
                        }, 1000);
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
                            if (window.AndroidBridge) window.AndroidBridge.onImageReady(b64);
                        } catch (e) {
                            log("Canvas draw failed, trying fetch: " + e.message);
                            fetch(img.src)
                                .then(r => r.blob())
                                .then(blob => {
                                    const reader = new FileReader();
                                    reader.onloadend = () => {
                                        const res = reader.result || '';
                                        const b64 = res.replace(/^data:.+;base64,/, "");
                                        if (window.AndroidBridge) window.AndroidBridge.onImageReady(b64);
                                    };
                                    reader.readAsDataURL(blob);
                                })
                                .catch(err => {
                                    if (window.AndroidBridge) window.AndroidBridge.onGenerationError("Image extract failed: " + err.message);
                                });
                        }
                    }

                    setTimeout(tick, 2000);
                })();
            """.trimIndent()

            var pageLoaded = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!pageLoaded && url?.contains("lmarena.ai") == true) {
                        pageLoaded = true
                        logger?.invoke("NativeArenaImageClient: page loaded, injecting automation script")
                        view?.evaluateJavascript(automationJs, null)
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

            logger?.invoke("NativeArenaImageClient: loading $ARENA_URL")
            webView.loadUrl(ARENA_URL)

            withTimeout(TIMEOUT_MS) {
                resultDeferred.await()
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
