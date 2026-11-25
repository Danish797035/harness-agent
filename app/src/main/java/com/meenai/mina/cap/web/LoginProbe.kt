package com.meenai.mina.cap.web

import android.util.Log
import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.util.concurrent.Executors

/** Login status as seen on the page. */
enum class LoginState { SignedIn, SignedOut, Unknown }

/**
 * The page signs used to judge the login status are only the visible things in the account area (header,
 * navigation bar, banner, or the top section of the page) at the top of the page: [url] current address;
 * [passwordVisible] visible password box on the page; [account] visible links and button text in the
 * account area; [avatars] visible avatars in the account area (comments, lists, and articles are not
 * counted).
 */
data class LoginSignals(
    val url: String?,
    val passwordVisible: Boolean,
    val account: List<String>,
    val avatars: Int,
)

/**
 * General login status judgment, does not recognize specific websites:
 * - Logged in: In the account area, you can see links such as "log out/log out" (the entire text is it,
 * or starts with it), or entrances such as "personal center/my account".
 * And there is no visible login entrance;
 * - Logged out: the password box is visible, or the "login / sign in" entrance is visible in the account
 * area and there is no account entrance or avatar;
 * - The avatar is only used to deny "logged out". You cannot judge the status as logged in; no
 * conclusion can be drawn when the address is like the login page (single sign-on, pass jump).
 * The rest are [LoginState.Unknown]: The registration form does not move.
 */
object LoginProbe {
    private val LOGIN_PATH = Regex("""(^|[/_.-])(log-?in|sign-?in|sign_in|logon|passport|sso|oauth|authorize|auth/login|account/login|user/login)([/_.?#-]|$)""", RegexOption.IGNORE_CASE)
    /** It counts as the entire text (too common, most words starting with it mean something else, such as "exit full screen"). */
    private val LOGOUT_EXACT = setOf("退出", "注销", "logout", "signout")
    /** The entire text is it, or starts with it (followed by at most [LOGOUT_SLACK] characters). */
    private val LOGOUT = listOf("退出登录", "退出账号", "安全退出", "注销登录", "log out", "sign out", "log off")
    /** Those with these words are not exit links (instructions for logging out of your account, help pages). */
    private val NOT_LOGOUT = listOf("说明", "帮助", "须知", "协议", "账号注销", "注销账号", "注销账户", "help", "how to", "delete account")
    private val SIGN_IN = listOf("登录", "登入", "请登录", "立即登录", "登录/注册", "注册/登录", "登录 / 注册", "注册 / 登录", "log in", "login", "sign in", "signin", "sign in / register", "sign up / log in")
    private val ACCOUNT = listOf("个人中心", "我的账号", "我的账户", "账号设置", "账户设置", "my account", "your account", "account settings")
    /** "Exit" type words can be followed by at most a few words (for example, "Log out >" and "Log out of Example" do not count, and those that are too long are sentences). */
    private const val LOGOUT_SLACK = 4

    fun classify(s: LoginSignals): LoginState {
        val texts = s.account.map(::norm)
        val logout = texts.any(::isLogout)
        val account = texts.any { t -> ACCOUNT.any { t == it || t.startsWith(it) } }
        val signIn = texts.any { it in SIGN_IN }
        // Login, single sign-on, and authorization pages are often redirected: no conclusion can be
        // drawn, and the registration form does not move.
        if (loginUrl(s.url)) return LoginState.Unknown
        if (s.passwordVisible && !logout) return LoginState.SignedOut
        if ((logout || account) && !signIn && !s.passwordVisible) return LoginState.SignedIn
        if (signIn && !logout && !account && s.avatars == 0) return LoginState.SignedOut
        return LoginState.Unknown
    }

    private fun isLogout(t: String) =
        NOT_LOGOUT.none { t.contains(it) } &&
            (t in LOGOUT_EXACT || LOGOUT.any { w -> t == w || (t.startsWith(w) && t.length <= w.length + LOGOUT_SLACK) })

    /** The address is a login, single sign-on or authorization page: identified by path (the mention of login in the query string does not count). */
    fun loginUrl(url: String?): Boolean {
        val path = url?.let { runCatching { URI(it.trim()).rawPath }.getOrNull() } ?: return false
        return LOGIN_PATH.containsMatchIn(path)
    }

    private fun norm(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Parse the result returned by evaluateJavascript (a JSON string literal) for [SCRIPT]; if not read, it will be null. */
    fun parse(raw: String?): LoginSignals? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        return LoginSignals(
            url = (o["url"] as? JsonPrimitive)?.contentOrNull,
            passwordVisible = (o["pw"] as? JsonPrimitive)?.booleanOrNull == true,
            account = (o["account"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            avatars = (o["avatars"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * A read-only script that collects signs in the main frame of the page: only reads the DOM, does not
     * change the page, and does not send requests. At most [SCAN_BUDGET] elements are moved in document
     * order; the account area is in the header/navigation/banner, or the top edge is within the top 160
     * pixels of the page; avatars in comments, list items, and articles are not counted.
     */
    val SCRIPT = """(function(){try{
var out={url:location.href,pw:false,account:[],avatars:0},budget=$SCAN_BUDGET;
function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return null;var c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&+c.opacity>0.05?r:null;}
function top(e,r){return r.top+scrollY<160||!!e.closest('header,nav,[role=banner],[role=navigation]');}
var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_ELEMENT),e,n=0;
while((e=w.nextNode())&&n++<budget){var t=e.tagName,r;
if(t==='INPUT'){var y=(e.getAttribute('type')||'').toLowerCase();if(y==='password'&&!out.pw&&vis(e))out.pw=true;if(y!=='submit'&&y!=='button')continue;}
if(t==='A'||t==='BUTTON'||t==='INPUT'||e.getAttribute('role')==='button'||e.getAttribute('role')==='link'||e.getAttribute('role')==='menuitem'){
if(out.account.length>=60)continue;var s=String(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim();
if(s&&s.length<=24&&(r=vis(e))&&top(e,r))out.account.push(s);continue;}
if(t==='IMG'&&out.avatars<3){var k=(e.className&&typeof e.className==='string'?e.className:'')+' '+(e.getAttribute('alt')||'')+' '+(e.getAttribute('src')||'');
if(/avatar|头像/i.test(k)&&(r=vis(e))&&top(e,r)&&!e.closest('article,li,[class*=comment],[class*=list],[class*=feed]'))out.avatars++;}}
return JSON.stringify(out);}catch(x){return null;}})()"""

    private const val SCAN_BUDGET = 4000
    private const val TAG = "MinaLoginProbe"
    /** Parsing, judgment and writing the registration form are all placed on it, and it does not occupy the main thread. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mina-login-probe").apply { isDaemon = true } }

    /**
     * Main thread adjustment: Take a look at the login status on the current page of [tab] (the script
     * runs on the page), parsing and judgment are done on the background thread, and callbacks (site
     * host name, status) are also on the background thread. There is no callback when the tag is no
     * longer available, is still loading, cannot be read, or has been moved to another website while
     * reading.
     */
    fun probe(tab: WebTab, onResult: (String, LoginState) -> Unit) {
        if (tab.destroyed || tab.crashed || tab.loading) return
        val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
        tab.view.evaluateJavascript(SCRIPT) { raw ->
            worker.execute {
                val signals = parse(raw) ?: return@execute
                if (signals.url?.let(WebUrlPolicy::host) != host) return@execute
                val state = classify(signals)
                Log.i(TAG, "login probe $host: $state")
                onResult(host, state)
            }
        }
    }
}

