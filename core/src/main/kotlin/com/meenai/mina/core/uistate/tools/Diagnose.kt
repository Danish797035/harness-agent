package com.meenai.mina.core.uistate.tools

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.uistate.ScreenDiagnosis
import com.meenai.mina.core.uistate.ScreenState
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.HINT_PROTECTED
import com.meenai.mina.core.tool.HINT_UNSUPPORTED_BROWSER
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.ok
import kotlinx.serialization.json.put
import com.meenai.mina.core.uistate.screen
import com.meenai.mina.core.uistate.session

/** Diagnose blank, loading, locked or protected screens; you need to pass the lock screen level (nothing can be diagnosed on a locked screen). */
internal class Diagnose(f: ScreenFamily) : ScreenTool(f, "diagnose") {
    override val verb = Verb.Diagnose
    override val label = "Check screen"
    override val risk = Risk.Write
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val place = family.placeKey
        ctx.screen.checkScreen()?.let { c ->
            return ok {
                put(place, c.currentApp)
                put("state", c.state.wire)
                put("usable", c.state == ScreenState.NORMAL)
                put("needsHuman", c.needsHuman)
                c.takeoverReason?.let { put("takeoverReason", it) }
                c.cause?.let { put("cause", it) }
                when (c.state) {
                    ScreenState.NORMAL -> if (c.takeoverReason == ScreenDiagnosis.UNSUPPORTED_BROWSER) put("hint", HINT_UNSUPPORTED_BROWSER)
                    ScreenState.BLANK -> put("hint", when {
                        c.currentApp == null && c.cause == ScreenDiagnosis.NO_WINDOW -> Feedback[family.emptyHint]
                        // The foreground app is there, but the window cannot be read: It’s not that it’s
                        // not open, so don’t start it again.
                        c.currentApp != null && c.cause == ScreenDiagnosis.UNREADABLE_WINDOW -> Feedback.format("unreadable_window", "app" to c.currentApp)
                        else -> Feedback["screen.blank"]
                    })
                    ScreenState.LOADING -> put("hint", Feedback["screen.loading"])
                    ScreenState.PROTECTED -> put("hint", HINT_PROTECTED)
                    // Restricted access: In addition to handing it over to the user, do not try again
                    // immediately
                    ScreenState.NEEDS_HUMAN -> put("hint", if (c.takeoverReason == "access_blocked") Feedback["barrier.access_blocked"]
                        else Feedback.format("screen.needs_human", "reason" to c.takeoverReason))
                }
            }
        }
        val s = ctx.session.snapshot()
        val usable = s.elements.any { it.meaningful }
        return ok {
            put(place, s.packageName ?: s.unreadableApp)
            put("usable", usable)
            put("needsHuman", false)
            if (!usable) put("hint", s.unreadableApp?.let { Feedback.format("unreadable_window", "app" to it) } ?: Feedback["screen.no_elements"])
        }
    }
}
