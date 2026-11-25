package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.uistate.tools.ScreenTool
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ToolContext

/** Web-specific tools: only provided when the web port is connected. */
internal interface WebTool : AgentTool {
    override val capability: String get() = WEB
    override fun available(ctx: ToolContext) = ctx.web != null
}

/** The id of the web page capability is also the prefix of the name of the set of tools on the web page. */
const val WEB = "web"

/** On the web page, press_key only accepts these keys: Enter, Backspace, Delete, Tab, arrow keys, and page turning keys. */
private val KEYS = setOf(66, 160, 67, 112, 61, 19, 20, 21, 22, 92, 93)
private const val KEYS_HINT = "on a web page only Enter, Backspace, Delete, Tab, arrow and page keys are allowed"

/** The set of screen tools on the web page: the screen reading number starts with w, and the site is reported "where"; the experience route does not remember the practices on the web page. */
internal val WEB_FAMILY = ScreenFamily(
    WEB, snapshotPrefix = "w", placeKey = ScreenFamily.SITE_PLACE, keys = KEYS, keysHint = KEYS_HINT,
    ownPrompts = setOf("observe", "back"), emptyHint = "empty_page",
)

/** A set of web pages: a set of screen tools, plus web page-specific navigation, page reading, tags, downloading and uploading, scripts, waiting, and leaving this page after the task is completed. In the order issued to the model. */
internal object WebGroup {
    val tools: List<AgentTool> = ScreenTool.of(WEB_FAMILY) + listOf(
        Navigate, ReadPage, EvaluateJs, RunSiteCommand, Tabs, Downloads, Upload, SiteMode, WebScriptTool, Interact, Capture, WebWaitFor, KeepOpen,
    )
}
