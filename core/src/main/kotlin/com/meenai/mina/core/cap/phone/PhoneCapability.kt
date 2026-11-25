package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.capability.Capability
import com.meenai.mina.core.capability.CapabilityKey
import com.meenai.mina.core.capability.CapabilityStatus
import com.meenai.mina.core.capability.Paced
import com.meenai.mina.core.uistate.DwellStop
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.uistate.PlacePace
import com.meenai.mina.core.uistate.dwellStopped
import com.meenai.mina.core.capability.PromptEnv
import com.meenai.mina.core.uistate.Surface
import com.meenai.mina.core.observation.Look
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.PACKAGE_NAME_RE
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.uistate.EffectKind
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.sessions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Mobile phone: The set of screen tools, open apps and links on mobile phones. [pacing] is the rhythm of
 * the step-by-step operation in the App (see [AppPacing]): make up for pauses before changing the action
 * on the screen, and there is an upper limit on the number of screens in the App that limits the
 * browsing speed; [appPace] gives the app's knowledge of the browsing speed according to the package
 * name; [idle] gives a new pause on the screen a few times (see [IdleScroll]).
 */
internal class PhoneCapability(
    val screen: ScreenPort,
    /** Speed-up switch deepLinks: Provides tools for opening links. */
    val deepLinks: Boolean,
    pacing: AppPacing = AppPacing.Off,
    appPace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
) : Capability {
    val dwell = AppDwell(pacing, appPace, idle)

    override val id = PHONE
    private val tools = PhoneGroup.tools

    override val folds = true

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = tools

    override val family = PHONE_FAMILY

    override val surface = Surface(PHONE_FAMILY, screen, declaredTarget = { app ->
        if (!PACKAGE_NAME_RE.matches(app)) throw ArgError("app", "must be an Android package name")
        app
    }, targetNote = "the exact package name of the app")

    /**
     * The mobile phone group follows the tools for opening and listing apps; the mobile phone group is
     * not given when it is stored. The rules for application access only follow when the user has
     * disabled the app (or the settings cannot be read): when the list is empty, the mobile tool will
     * not be rejected because of it, and the model does not need this section.
     */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || tools.none { it.verb in SECTION_VERBS && it.name in env.offered }) return emptyList()
        val access = screen.appAccess.current()
        return listOfNotNull(
            env.gate(Prompts.capability(PHONE)),
            Prompts.capability(APP_ACCESS).takeIf { !access.available || access.blocked.isNotEmpty() },
        )
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onIdleScroll = { ctx.sessions[id]?.movedByMina() }
    }

    /** The user replied: The number of screens for apps that limit browsing speed will be recalculated (the user agreed and continued to browse). */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** After the external actions in the App are completed, the App will no longer have an upper limit on the number of screens (batch contact and delivery will reach the upper limit of the App itself). */
    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /**
     * Before changing the screen, stop according to the rhythm in the app: which app you are in at the
     * moment and the number of words on this screen are calculated based on the screen that the model
     * recently read. The actions of changing screens (clicking, sliding, returning, key pressing, and
     * gestures) in apps that limit browsing speed will not be performed when the number of screens
     * reaches the upper limit, and page_limit will be reported; typing, clearing, scrolling, turning the
     * scroll wheel, opening other apps, and external actions themselves will not be blocked. When the
     * next action is not performed according to the position on the screen (return, press the button,
     * scroll according to the direction), you can roll lightly a few times while staying.
     */
    override suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced {
        if (!dwell.paced) return Paced.NONE
        val shown = ctx.sessions[id]?.lastSnapshot
        return try {
            val d = dwell.before(shown?.packageName, shown?.let(::textChars) ?: 0, gate = !outward && verb in BROWSES,
                port = screen.takeIf { verb in IDLE_BEFORE })
            Paced(d.waitMs, d.idleScrolls)
        } catch (e: DwellStop) {
            Paced(stop = dwellStopped(e))
        }
    }

    /** After the action is completed: the interface has changed drastically or the app has been changed, it counts as switching to a new screen (scrolling down to read the remaining content on the same page does not count as the number of screens entered). */
    override fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {
        if (!dwell.paced) return
        val effect = result["effect"] as? JsonObject
        val kind = EffectKind.of((effect?.get("kind") as? JsonPrimitive)?.contentOrNull)
        val newScreen = kind == EffectKind.Done || kind == EffectKind.PlaceChanged
        val app = (effect?.get("to") as? JsonPrimitive)?.contentOrNull ?: ctx.sessions[id]?.lastSnapshot?.packageName
        dwell.after(app, newScreen, counts = verb != Verb.Scroll)
    }

    /** Take another look: the interface status of the App on your phone at this moment. */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = ctx.sessions[id]?.freshLook(withImage)

    /** The system command fails several times in a row; it is cleared once it succeeds. */
    var commandFailures = 0

    /** This task has reminded the system command to change back to a special tool: commands to do those types of things later will not be executed. */
    var commandSteered = false

    companion object {
        val KEY = CapabilityKey<PhoneCapability>(PHONE)
        private val SECTION_VERBS = setOf(Verb.LaunchApp, Verb.ListApps)

        /** The section that applies access rules (capabilities/app-access.md). */
        private const val APP_ACCESS = "app-access"

        /** The action of changing screens in an app: The number of screens in an app that is restricted by browsing speed is limited. */
        private val BROWSES = setOf(Verb.Tap, Verb.Swipe, Verb.Back, Verb.PressKey, Verb.PhoneGesture)

        /** Actions that do not follow the position on the screen: rolling a few times during the previous stop will not cause it to deviate. */
        private val IDLE_BEFORE = setOf(Verb.Back, Verb.PressKey, Verb.Scroll)

        /** The number of words displayed on one screen (words in the input box and prompts are not counted). */
        internal fun textChars(s: ScreenSnapshot): Int =
            s.elements.filter { !it.editable && !it.hint }.sumOf { it.name.codePointCount(0, it.name.length) }

        /** All the tools a mobile phone set might offer. */
        fun tools(): List<AgentTool> = PhoneGroup.tools
    }
}

/** Mobile phone capabilities for this task. */
internal val ToolContext.phone: PhoneCapability get() = this[PhoneCapability.KEY]!!
