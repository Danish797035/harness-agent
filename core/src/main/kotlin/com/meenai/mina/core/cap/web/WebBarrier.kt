package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.ScreenCheck
import com.meenai.mina.core.uistate.ScreenDiagnosis
import com.meenai.mina.core.uistate.ScreenFacts
import com.meenai.mina.core.uistate.ScreenState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * Facts collected on the web page: [base] is the same as the mobile phone (visible text, password box,
 * black frame...), plus the HTTP status of the main document, the address of the frame in the page (only
 * cross-origin), and the host name of the current page.
 */
data class WebFacts(val base: ScreenFacts, val httpStatus: Int?, val frameUrls: List<String>, val host: String?)

/** Web page obstacle determination: first look at the unique signs of the web page, and then fall into the same set of [ScreenDiagnosis.classify] as the mobile phone. */
object WebBarrier {
    private val RISK_STATUS = setOf(403, 429, 503)
    private val RISK_WORDS = listOf("访问受限", "频繁", "异常", "blocked", "too many requests")

    /** The framework host of the human-machine authentication service (identified by domain name suffix). */
    private val CAPTCHA_HOSTS = listOf(
        "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com", "geetest.com", "arkoselabs.com", "funcaptcha.com",
    )
    /** The host name itself is not specifically verified and is distinguished by the path (host, path prefix). */
    private val CAPTCHA_PATHS = listOf("google.com" to "/recaptcha", "gstatic.com" to "/recaptcha")
    private val CAPTCHA_HOST_WORDS = listOf("captcha")

    /**
     * The statement on the risk control interception page (the website feels that this visit does not
     * look like a human being and the environment is wrong), even if it appears alone (remove the blank
     * space when comparing, and do not distinguish between upper and lower case). Only identify pages
     * with few words: pages with long text (articles about this type of issue) are not counted.
     */
    private val INTERSTITIAL_WORDS = listOf(
        "访问异常", "异常访问", "访问行为异常", "访问存在异常", "环境异常", "环境存在异常", "存在安全风险", "当前访问存在风险",
        "操作频繁", "操作过于频繁", "访问过于频繁", "请求过于频繁", "访问频繁",
        "unusualtraffic", "unusualactivity", "suspiciousactivity", "automatedqueries", "automatedrequests", "checkingyourbrowser",
        "accesstemporarilyrestricted", "requestwasblocked", "requesthasbeenblocked",
    )
    /** It's just the title ("Safety Tips" and "Security Check"), and there are also words such as exception, verification, and frequent on the same page. */
    private val INTERSTITIAL_TITLES = listOf("安全提示", "安全检查", "安全警告", "securitycheck", "securitynotice", "securityverification")
    private val INTERSTITIAL_CUES = listOf("异常", "验证", "频繁", "风险", "unusual", "verify", "suspicious", "robot", "automated")
    /** Calculate the upper limit of "the page does not have many words": the total number of visible words (excluding blank spaces) and the number of lines. */
    private const val INTERSTITIAL_MAX_CHARS = 800
    private const val INTERSTITIAL_MAX_TEXTS = 40

    /** The statement in the interface response indicating that it was blocked by risk control: the same type as [INTERSTITIAL_WORDS], plus the common writing method of the interface. */
    private val API_RISK_WORDS = INTERSTITIAL_WORDS + listOf(
        "环境存在风险", "账号存在风险", "请求异常", "访问受限", "abnormal", "riskcontrol", "toomanyrequests", "ratelimit",
    )
    /** Only look at these fields (lowercase ratio) in response to the top-level report information. */
    private val API_MESSAGE_KEYS = setOf("message", "msg", "errmsg", "errormsg", "error_msg", "errormessage", "error", "tip", "tips", "toast", "reason")
    private const val API_MESSAGE_MAX = 200

    /** Remove the blank space when comparing: "Please download the App to continue" is the same as "Please download the App to continue". */
    private val UNSUPPORTED_WORDS = listOf(
        "请在浏览器中打开", "当前浏览器不支持", "不支持此浏览器", "请下载app继续", "browserisnotsupported", "browsernotsupported",
    )

    fun classify(f: WebFacts): ScreenCheck {
        // The same set of rules for mobile phones has been determined to be handled by someone (password
        // box, verification code words, payment), which shall prevail.
        val base = ScreenDiagnosis.classify(f.base)
        if (base.state == ScreenState.NEEDS_HUMAN) return base
        val text = f.base.texts.joinToString("\n").lowercase()
        val compact = text.filterNot(Char::isWhitespace)
        val app = f.base.currentApp ?: f.host
        if (f.httpStatus in RISK_STATUS) {
            RISK_WORDS.firstOrNull { it in text }?.let {
                return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "HTTP ${f.httpStatus} with text: $it")
            }
        }
        f.frameUrls.firstOrNull { captchaFrame(it, f.host) }?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "captcha", "verification frame from ${WebUrlPolicy.host(it)}")
        }
        interstitial(f.base.texts, compact)?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "risk-control page: \"$it\"")
        }
        UNSUPPORTED_WORDS.firstOrNull { it in compact }?.let {
            return ScreenCheck(ScreenState.NORMAL, app, false, ScreenDiagnosis.UNSUPPORTED_BROWSER,
                "the site refuses this embedded browser (\"$it\"): switch route to the app if it is installed, or end_not_done")
        }
        return base
    }

    /** Argument for the risk control interception page on a page with few words; not null. */
    private fun interstitial(texts: List<String>, compact: String): String? {
        if (texts.size > INTERSTITIAL_MAX_TEXTS || compact.length > INTERSTITIAL_MAX_CHARS) return null
        INTERSTITIAL_WORDS.firstOrNull { it in compact }?.let { return it }
        val title = INTERSTITIAL_TITLES.firstOrNull { it in compact } ?: return null
        return INTERSTITIAL_CUES.firstOrNull { it in compact }?.let { "$title … $it" }
    }

    /**
     * Does the interface response [body] received by the page itself mean that it was blocked by risk
     * control: there is such a statement in the fields (message, msg, error...) of the top-level
     * information reported in the JSON object. Returns the statement (truncated); is not JSON, has no
     * such field, or is null if not mentioned.
     */
    fun apiRisk(body: String?): String? {
        val text = body?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        for ((k, v) in o) {
            if (k.lowercase() !in API_MESSAGE_KEYS) continue
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val compact = s.lowercase().filterNot(Char::isWhitespace)
            if (compact.length > API_MESSAGE_MAX) continue
            if (API_RISK_WORDS.any { it in compact }) return s.trim().take(API_MESSAGE_MAX)
        }
        return null
    }

    /** A framework that is cross-origin (different host names and pages) and points to the human-machine authentication service. */
    private fun captchaFrame(url: String, pageHost: String?): Boolean {
        val h = WebUrlPolicy.host(url) ?: return false
        if (pageHost != null && h == pageHost.lowercase().removePrefix("www.")) return false
        fun under(d: String) = h == d || h.endsWith(".$d")
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return CAPTCHA_HOSTS.any(::under) || CAPTCHA_PATHS.any { (d, p) -> under(d) && path.startsWith(p) } ||
            CAPTCHA_HOST_WORDS.any { it in h }
    }
}

