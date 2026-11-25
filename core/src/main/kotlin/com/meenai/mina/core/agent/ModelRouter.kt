package com.meenai.mina.core.agent

import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.tool.Verb

/** Look at the previous step when deciding which model to use next. */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** The screen has not changed after the previous step; if the screen reading type does not report the time, it is null. */
    val lastChanged: Boolean?,
    /** Playback return, batch stop, verification return, takeover or recovery after questioning. */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** There are screenshots in the context to be sent; lightweight models without pictures cannot be accepted. */
    val hasImages: Boolean = false,
)

/**
 * Experimental switch lightSteps division of labor: simple steps are given to the lightweight model, and
 * the rest are given to the main model. When the lightweight model is not configured and does not
 * support tool calls, the main model is always used. If there is an error in one step, the guard prompts
 * or the screen does not change, the next [TROUBLE_STEPS] step forces the main model.
 */
class ModelRouter(private val main: ChatModel, private val light: ChatModel?) {
    private var forcedMain = 0
    private var disabled = false

    /** Whether the lightweight model can receive mobile phone steps; if not, lightSteps will not take effect. */
    val active: Boolean get() = !disabled && light != null && light.capabilities.toolCalls

    /** There is an error in the lightweight model that makes retry useless (the Key, quota, model name, and request are incorrect): the main model is used for the rest. A router only serves one task. */
    fun disable() { disabled = true }

    fun pick(ctx: StepContext): ChatModel {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) noteTrouble()
        if (forcedMain > 0) {
            forcedMain--
            return main
        }
        val l = light?.takeIf { active } ?: return main
        val simple = ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && (!ctx.hasImages || l.capabilities.vision)
        return if (simple) l else main
    }

    /** Something went wrong (including the lightweight model call failed and the reply was redirected to the main model): The main model is then used in the [TROUBLE_STEPS] step. */
    fun noteTrouble() { forcedMain = TROUBLE_STEPS }

    fun isLight(m: ChatModel): Boolean = light != null && m === light

    companion object {
        const val TROUBLE_STEPS = 2
        /** These calls (and do_steps) are not executed when the lightweight model is given. This step is changed to the main model: abandon, confirm, take over, challenge, and sandbox commands are all determined by the main model. */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand) +
            // The commands written by the model are run in the sandbox: only the main model is allowed
            // to write
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile)
        /** Can only be called by the main model ([name] is the tool name, [verb] is the action it does). */
        fun mainOnly(name: String, verb: Verb?): Boolean = name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS

        /** The step after these actions counts as recovery (waiting for the user). */
        internal val RECOVERY_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        /** These actions will change the front-end App or web page (open the URL, change the tab); the subsequent steps are not considered the same place. */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop usage: write down the situation of each step and spell out [StepContext] for the next step. */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && app != null && app == appBefore, hasImages)

    /** [app] is null, which means that the result of this step is not reported to the front-end App, and the previous one is used. */
    /** Just recovered from the situation of playback return and batch stop: the next step is to follow the recovery steps. Called after [after]. */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}
