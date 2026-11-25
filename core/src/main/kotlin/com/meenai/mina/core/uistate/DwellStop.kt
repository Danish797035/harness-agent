package com.meenai.mina.core.uistate

import com.meenai.mina.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.meenai.mina.core.tool.FAILURE_MESSAGES
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.fail

/** The pages viewed in this task on this website and the screens changed by this app have reached the upper limit: no more page or screen changes (see [PageLimitReached]). */
const val PAGE_LIMIT = "page_limit"

/** The pause before page change or screen change does not allow this action to be performed: the caller does not perform it and press [code] to report (see [dwellStopped]). Shared on web page ([com.meenai.mina.core.cap.web.PageDwell]) and App (mobile phone capability stay). */
internal sealed class DwellStop(message: String) : Exception(message) {
    abstract val code: String
    /** Details to append to the description; null if not. */
    open val detail: String? get() = null
}

/** In this task, the page from the website and the screen change of the App that limits the browsing speed have reached the upper limit: no more page or screen changes, but answer or ask the user first. [site] is the website host name or App package name. */
internal class PageLimitReached(val site: String, val pages: Int, val cap: Int) :
    DwellStop("Pages or screens opened in $site in this task: $pages (limit $cap).") {
    override val code get() = PAGE_LIMIT
    override val detail get() = message
}

/** During the pause, the user interrupted: I won’t do it this time, but read that sentence first. */
internal class DwellInterrupted : DwellStop("The pause before this action was cut short by a new message from the user.") {
    override val code get() = SKIPPED_FOR_NEW_MESSAGE
}

/** The tool result of not doing this action: explain why it stopped and what to do next (when the limit is reached, attach this website and how many pages the App has read). */
internal fun dwellStopped(e: DwellStop): ToolExecution =
    fail(e.code, listOfNotNull(FAILURE_MESSAGES.getValue(e.code), e.detail).joinToString(" "))
