package com.meenai.mina.core.general

import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonObject
import com.meenai.mina.core.uistate.focus

private const val SURFACE = "surface"

/**
 * Which screen is the general tool for (confirmation, takeover, failure reporting): If there are more
 * than two screens in this task, there is an extra surface parameter. If not, it is the most recent
 * screen parameter. Before calling the dispenser, press it to record which screen it is operating on,
 * and the tool will still use that screen at the moment.
 */
internal interface SurfaceAware : AgentTool {
    override fun def(ctx: ToolContext): ToolDef {
        val ids = ctx.focus.surfaces.map { it.id }
        if (ids.size < 2) return def
        return ToolDef(def.name, def.description, def.props + (SURFACE to str(SURFACE_NOTE, ids)), def.required)
    }

    override fun aimAt(o: JsonObject): String? = o.argStr(SURFACE)

    /** When surface is given, check that it is a screen for this task. */
    fun checkSurface(a: Args, ctx: ToolContext) {
        val id = a.str(SURFACE) ?: return
        if (ctx.focus[id] == null) throw ArgError(SURFACE, "must be one of ${ctx.focus.surfaces.joinToString("|") { it.id }}")
    }

    companion object {
        const val SURFACE_NOTE = "Which screen this is about. Default: the screen your latest screen tool acted on."
    }
}
