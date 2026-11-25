package com.meenai.mina.eval

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.AndroidScreen
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.core.eval.APP_CAPABILITIES
import com.meenai.mina.core.eval.AppCapabilities
import com.meenai.mina.core.eval.PROBE_ALLOWLIST
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.core.cap.web.WebUrlPolicy
import com.meenai.mina.cap.web.PageScript
import com.meenai.mina.cap.web.WebHost
import com.meenai.mina.cap.web.WebSites
import com.meenai.mina.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The evaluator's own read-only view of the device: whitelist probes, preconditions, and opening the app
 * on the virtual screen to find words after the task is completed. [sh] executes the command as a shell;
 * [loggedIn] is a package name list file (one package name per line) manually marked as "logged in".
 */
class EvalProbes(private val sh: (String) -> String, private val loggedIn: File) {

    /** Only the commands in [PROBE_ALLOWLIST] are executed; no commands can be written into the task file. */
    fun run(name: String): String {
        val cmd = PROBE_ALLOWLIST[name] ?: error("probe not in allowlist: $name")
        return sh(cmd).trim()
    }

    /**
     * Apps on this phone that can handle certain types of things (see [APP_CAPABILITIES]): Check by
     * standard Intent, the ones that handle it by default are ranked first. The command is only spelled
     * out from the capability table, and the task file cannot be written into it. No standard Intent has
     * the ability to return null.
     */
    fun appCandidates(capability: String): List<String> = APP_CAPABILITIES[capability].orEmpty()
        .flatMap { i -> AppCapabilities.commands(i).flatMap { cmd -> AppCapabilities.packagesIn(runCatching { sh(cmd) }.getOrDefault("")) } }
        .distinct()

    /**
     * `installed:<package name>`, `app:<capability>`, `signedIn:<package name or website>` (old writing `login:<package name>`, `site:<host name>`). Login
     * status: The website only recognizes "the user has logged in in Mina" in the site registration form
     * (logged in in the "Website Login" setting); the App only recognizes manual marking ([loggedIn]),
     * and the evaluator does not determine the App's login status for the user (there is no reliable
     * detection method for all Apps). Questions that are not logged in are skipped and the reason is
     * written as "not logged in".
     */
    fun requirementMet(req: String): Pair<Boolean, String> {
        val kind = req.substringBefore(':')
        val pkg = req.substringAfter(':', "")
        when (kind) {
            "site" -> return siteSignedIn(pkg)?.let { if (it) true to "" else false to "未登录:$pkg 没有在设置「网站登录」里登录过" }
                ?: (false to "前置条件写法不对:$req")
            "signedIn" -> return if (siteSignedIn(pkg) == true || appMarked(pkg)) true to ""
                else false to "未登录:$pkg(网站要在设置「网站登录」里登录过,App 要标记在 ${loggedIn.name} 里)"
            "app" -> return if (appCandidates(pkg).isNotEmpty()) true to "" else false to "这台手机上没有能处理「$pkg」的 App"
        }
        if (!PACKAGE.matches(pkg)) return false to "前置条件写法不对:$req"
        return when (kind) {
            "installed" ->
                if (sh("pm path $pkg").contains("package:")) true to "" else false to "没装 $pkg"
            "login" -> if (appMarked(pkg)) true to "" else false to "未登录:$pkg 没有标记为已登录(${loggedIn.name})"
            else -> false to "不认识的前置条件:$req"
        }
    }

    /** Apps manually marked as logged in (one package name per line). */
    private fun appMarked(pkg: String) = runCatching { loggedIn.readLines().map { it.trim() } }.getOrDefault(emptyList()).contains(pkg)

    /** The website has been logged in in the "Website Login" setting: the same registrable domain name counts (if the registrable domain name cannot be recognized, the host name must be exactly the same); if the writing method is not a website, it is null. */
    private fun siteSignedIn(site: String): Boolean? {
        val host = WebUrlPolicy.host("https://$site") ?: return null
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val want = WebUrlPolicy.registrable(host) ?: host
        return WebSites.registry(ctx).list().any { it.loggedInByUser && (WebUrlPolicy.registrable(it.host) ?: it.host) == want }
    }

    /** Cold-start [pkg] (or use it to open a deep link) on a new virtual screen, and read the screen until you see [text] or timeout. */
    suspend fun screenHas(pkg: String, text: String, deepLink: String?): Boolean {
        require(PACKAGE.matches(pkg)) { "bad package: $pkg" }
        val shell = DeviceSupport.shell()
        val metrics = DisplayMetrics().also {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display))
            if (deepLink == null) {
                if (screen.openApp(pkg, cold = true) !is ActionResult.Ok) return false
            } else {
                // The link only transmits data to am, and cannot use quotation marks to spell out other
                // commands.
                require('\'' !in deepLink && '\n' !in deepLink) { "bad deep link" }
                sh("am start --display ${display.displayId} -a android.intent.action.VIEW -d '$deepLink' -p $pkg")
            }
            val want = text.replace(WS, "")
            val end = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (true) {
                val seen = screen.snapshot().elements.joinToString("") { it.name }.replace(WS, "")
                if (want in seen) return true
                if (System.currentTimeMillis() > end) return false
                delay(500)
            }
        } finally {
            display.release()
        }
    }

    /**
     * Open [url] in a new background web page tab, read the entire page of text (at most [WEB_PAGES]
     * paragraphs), and look for [text] (ignore whitespace). Use mina web page configuration, the login
     * status is the same as that used by Agent.
     */
    suspend fun webHas(url: String, text: String): Boolean {
        require(WebUrlPolicy.checkNavigable(url) == null) { "bad url: $url" }
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val metrics = DisplayMetrics().also {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        lateinit var host: WebHost
        lateinit var tab: WebTab
        withContext(Dispatchers.Main) {
            host = WebHost(ctx, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            tab = WebTab(ctx, "probe", "eval")
            host.attach(tab)
        }
        try {
            tab.load(url)
            val page = PageScript(tab)
            page.install()
            val want = text.replace(WS, "")
            var offset = 0
            repeat(WEB_PAGES) {
                val o = runCatching { Json.parseToJsonElement(page.readPage(30_000, offset)).jsonObject }.getOrNull() ?: return false
                val got = (o["text"] as? JsonPrimitive)?.content.orEmpty().replace(WS, "")
                if (want in got) return true
                offset = (o["nextOffset"] as? JsonPrimitive)?.intOrNull ?: return false
            }
            return false
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                tab.destroy()
                host.close()
            }
        }
    }

    private companion object {
        const val WEB_PAGES = 5
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val WS = Regex("\\s+")
        const val SCREEN_WAIT_MS = 10_000L
    }
}
