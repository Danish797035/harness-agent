package com.meenai.mina.core.cap.web

import com.meenai.mina.core.capability.Capability
import com.meenai.mina.core.capability.CapabilityKey
import com.meenai.mina.core.capability.CapabilityStatus
import com.meenai.mina.core.capability.PromptEnv
import com.meenai.mina.core.uistate.Surface
import com.meenai.mina.core.observation.Look
import com.meenai.mina.core.net.UrlHost
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import com.meenai.mina.core.uistate.sessions
import com.meenai.mina.core.uistate.DwellStop
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PlacePace

private const val EVIDENCE_PAGE_CHARS = 3000
private val HOST_RE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

/**
 * Built-in browser: a set of screen tools for web pages, plus opening web pages, reading pages, page
 * scripts, downloading and uploading; tools are only provided after connecting to the web port ([port]).
 * [workspacePath] is the workspace path check when uploading (normalization and symbolic link resolution
 * are done by the app side). [pacing] is the rhythm of reading page by page (see [PagePacing]): the
 * pause on this page is made up before the page change action; [sitePace] gives the website knowledge
 * about the browsing speed according to the host name; [idle] gently scrolls the page a few times during
 * the pause (see [IdleScroll]).
 */
internal class WebCapability(
    val port: WebPort?,
    val workspacePath: ((String) -> WorkspacePathCheck)?,
    pacing: PagePacing = PagePacing.Off,
    sitePace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
) : Capability {
    override val id = WEB

    val dwell = PageDwell(pacing, sitePace, idle)

    override val folds = true

    override fun status(): CapabilityStatus = if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no browser")

    override fun tools(): List<AgentTool> = WebGroup.tools

    /** This time, the question was asked from the browser page to a page (with the explanation [AttachedPage]): That page is already the current tab, and a group of web pages will be expanded at the beginning. */
    override fun preload(task: String): Boolean = port != null && AttachedPage.has(task)

    /** The title, URL and selected words in the page description come from the web page, and after being removed, the words typed by the user remain. */
    override fun userWords(text: String): String = AttachedPage.words(text)

    override val family = WEB_FAMILY

    override val surface = port?.let { p ->
        Surface(WEB_FAMILY, WebScreen(p, dwell), declaredTarget = ::declaredHost, targetNote = "the site host shown as currentSite (e.g. shop.example.com)",
            mode = { siteMode(p) })
    }

    /** The group of web pages will not be given when they are collected. */
    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && WebGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(WEB))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onWait = { ctx.pacedWaitMs += it }
        dwell.onIdleScroll = { ctx.idleScrolls += it }
        dwell.afterIdle = { ctx.sessions[id]?.reprobe() }
    }

    /** The user replied: The upper limit of the number of pages on the same website will be recalculated (the user agreed and continued reading). */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** External actions on the web page: This time you do it, it is not restricted by the page limit; after it is completed, it will no longer count against the page limit of the website (batch contact and delivery are up to the website's own limit). */
    override fun onOutwardStart(ctx: ToolContext, site: String) = dwell.outwardStart(site)

    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /** Take another look: the interface status of this page at this moment, plus the beginning of the entire page of text (the interface status only contains elements in the viewport). */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? {
        val p = port ?: return null
        return ctx.sessions[id]?.freshLook(withImage) { pageStart(p)?.let { webRead(it, null) } }
    }

    /** Confirm that the declared app is the site host name on the web page (the international domain name is in ASCII form), which is consistent with the package name of the web page. */
    private fun declaredHost(app: String): String {
        val host = app.takeIf { it.isNotEmpty() && it.none { c -> c in "/:?#@\\ " } }?.let { UrlHost.of("https://$it") }
        if (host == null || !HOST_RE.matches(host))
            throw ArgError("app", "on the web surface, must be the site host shown as currentSite, e.g. shop.example.com")
        return host
    }

    /** The beginning of a full page of text. */
    private suspend fun pageStart(p: WebPort): String? {
        val text = try { p.readPage(EVIDENCE_PAGE_CHARS, 0, scrollLazy = false).text } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return text?.takeIf { it.isNotBlank() }?.let { "网页正文(开头):\n$it" }
    }

    /** The version (mobile / desktop) used by the current tag at the moment; if it cannot be read, it will be null. */
    private suspend fun siteMode(p: WebPort): String? = try { p.siteMode() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * The set of screen tools on the web page are used for screen reading and actions: the return key is
     * to go back on the page; the package name for screen reading is replaced with the site host name
     * (confirm the level and press it to check "whether the one you are reporting is the one in front of
     * you"). When reading the screen, give the address to [dwell] to recognize a new page; before the
     * page change action (click, back, key press, scroll down, slide), you must first make up for the
     * stay on this page, typing, clearing, scrolling up, etc. When you press the numbered point, back,
     * press the button, or scroll down, you do not press the screen coordinates. You can gently scroll
     * the page a few times while you are staying. You do not scroll when you press the coordinate point
     * and slide to stay (the coordinates will not match up). When the pages in this task of this website
     * reach the upper limit, no action other than scrolling down will be performed, and page_limit will
     * be reported.
     */
    private class WebScreen(private val port: WebPort, private val dwell: PageDwell) : ScreenPort by port {
        override suspend fun back(): ActionResult = paced(idle = true) { port.historyBack() }
        override suspend fun snapshot() = port.snapshot().withSite()
        override suspend fun peek() = port.peek().withSite()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.tapRef(ref, snapshotId) }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.tapXY(x, y) }
        override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.doubleTapRef(ref, snapshotId) }
        override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.doubleTapXY(x, y) }
        override suspend fun pressKey(keyCode: Int): ActionResult = paced(idle = true) { port.pressKey(keyCode) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
            paced(idle = false) { port.swipe(x1, y1, x2, y2, durationMs) }

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            if (!down) return port.scroll(ref, snapshotId, false)
            // Scroll down to read the rest of the page: scroll even when the page limit is reached;
            // scroll down if the user interrupts while staying.
            try { dwell.before(port = port, gate = false) } catch (e: DwellStop) { return ActionResult.Failed(e.code, e.detail) }
            val mark = dwell.scrollMark(port)
            return port.scroll(ref, snapshotId, true).also { dwell.afterScroll(port, mark) }
        }

        /** First make up for the pause (when the page limit is reached, the pause will not be done if the user interrupts, and page_limit or skipped_for_new_message will be reported), and then [action] will be done. */
        private suspend fun paced(idle: Boolean, action: suspend () -> ActionResult): ActionResult {
            try {
                dwell.before(port = port.takeIf { idle })
            } catch (e: DwellStop) {
                return ActionResult.Failed(e.code, e.detail)
            }
            return action()
        }

        private fun ScreenSnapshot.withSite() = web?.let { w ->
            dwell.seen(w.url)
            copy(packageName = UrlHost.of(w.url) ?: packageName)
        } ?: this
    }

    companion object {
        val KEY = CapabilityKey<WebCapability>(WEB)
    }
}

/** The web page port of this task; null if there is no web page. */
internal val ToolContext.web: WebPort? get() = this[WebCapability.KEY]?.port

/** This task stays page by page; no page capability is null. */
internal val ToolContext.dwell: PageDwell? get() = this[WebCapability.KEY]?.dwell

internal val ToolContext.workspacePath: ((String) -> WorkspacePathCheck)? get() = this[WebCapability.KEY]?.workspacePath
