package com.meenai.mina.core.general

import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.CONFIRM_ACTIONS
import com.meenai.mina.core.tool.CONFIRM_FIELD_MAX
import com.meenai.mina.core.tool.HINT_PAYMENT_TAKEOVER
import com.meenai.mina.core.tool.Outcome
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.capField
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.okObj
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.put
import com.meenai.mina.core.uistate.confirmTarget
import com.meenai.mina.core.uistate.focus
import com.meenai.mina.core.uistate.lastSnapshot
import com.meenai.mina.core.uistate.session

/**
 * Please ask the user for consent before the step of spending money (placement of order, submission when
 * there is an amount due on the screen, etc.); this is the only way to ask when asking. The payment
 * itself is not here, it is left to the user to pay by hand. The front-end check of the report uses the
 * screen that is re-read at this time (do not register the number, do not move the ref in the model
 * hand).
 */
internal object RequestConfirmation : SurfaceAware {
    override val verb = Verb.RequestConfirmation
    override val label = "Ask for your OK"
    override val risk = Risk.Read
    override val def = ToolDef("request_confirmation",
        Prompts.tool("request_confirmation"),
        mapOf(
            "action" to str("Kind of action that spends the money (order for placing an order or booking).", CONFIRM_ACTIONS),
            "target" to str("Who or where the money goes (shop, seller, service), as shown on screen. At most $CONFIRM_FIELD_MAX characters."),
            "content" to str("What is bought: item, spec, quantity. At most $CONFIRM_FIELD_MAX characters."),
            "amount" to str("Amount and currency the user will pay, as shown on screen."),
            "app" to str(APP_NOTE),
        ), listOf("action", "target", "content", "amount", "app"))

    /** What to write in the app parameters depends on the screen of this task: if there is one screen, write the wording of that piece, and if there are several screens, write one sentence each. */
    override fun def(ctx: ToolContext): ToolDef {
        val base = super.def(ctx)
        val surfaces = ctx.focus.surfaces
        val how = if (surfaces.size == 1) surfaces.single().targetNote
        else surfaces.joinToString("; ") { "on the ${it.id} screen, ${it.targetNote}" }
        return ToolDef(base.name, base.description, base.props + ("app" to str("$APP_NOTE: $how.")), base.required)
    }

    private const val APP_NOTE = "Where the action happens, as on screen now"

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val action = a.str("action")!!.trim()
        val kind = IrrevAction.entries.firstOrNull { it.name == action }
            ?: throw ArgError("action", "must be one of ${CONFIRM_ACTIONS.joinToString("|")}")
        if (kind == IrrevAction.pay) {
            ctx.refusedPay = ConfirmationRequest(kind, capField(a.str("target", max = 10000)!!), capField(a.str("content", max = 10000)!!),
                a.str("amount", max = 10000)?.let(::capField), a.str("app")!!.trim())
            return fail("payment_requires_takeover", HINT_PAYMENT_TAKEOVER)
        }
        // The only thing you need to ask about is spending money: If you can’t write the amount, you
        // don’t need to apply, just do it directly
        if (a.str("amount")?.any(Char::isDigit) != true) throw ArgError("amount",
            "required: the total the user will pay, as shown on screen. An action that spends no money needs no approval: do it directly")
        val app = ctx.confirmTarget(a.str("app")!!.trim())
        val req = ConfirmationRequest(
            kind, capField(a.str("target", max = 10000)!!), capField(a.str("content", max = 10000)!!),
            a.str("amount", max = 10000)?.let(::capField), app,
        )
        val fg = ctx.session.peek()?.packageName ?: ctx.lastSnapshot?.packageName
        return ToolExecution(okObj { put("asked", true) }, outcome = Outcome.Confirm(req, fg))
    }
}
