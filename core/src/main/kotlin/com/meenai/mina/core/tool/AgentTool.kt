package com.meenai.mina.core.tool

import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.action.Risk as ApprovalRisk
import kotlinx.serialization.json.JsonObject

/**
 * The tool itself declares what it will do to the outside world. Read: read-only (read screen, read web
 * page, check memory, report failure, etc.), read-only calls can be made directly; Write: change the
 * screen, page or sandbox, only through the execution entrance; External: in addition to changing the
 * interface, it is also possible to issue, pay, and submit according to the target element (the
 * determination of irreversibility depends on its target).
 */
enum class Risk { Read, Write, External }

/** Entry tool: [Opens] expands its group after being called; [Alone] alone is enough (listing Apps, reading the text of a URL), and the call does not expand. */
enum class Entry { Opens, Alone }

/** Can it be done at the same time as other calls? The default is one at a time. */
enum class Concurrency { Serial, ParallelSafe }

/**
 * What action does the tool do? Where judgments are made based on actions (guarding, cropping, routing,
 * batching, model division of labor...), all look at it, not the tool name; on-screen actions and screen
 * reading each have a tool with the same verb on several interfaces.
 */
enum class Verb {
    Observe, ListElements, Query, Collect, PhoneRun, PhoneFind, PhoneExtract, PhoneDialog, PhoneEvent, PhoneGesture, PhoneCommand, Screenshot, ViewPastScreens, ViewUserScreen, Describe, FindVisually, Diagnose,
    Tap, TypeText, ClearText, Scroll, Swipe, Back, PressKey,
    ListApps, LaunchApp, OpenLink, ShareText, AppAfterTask,
    SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent, SetWheel,
    Navigate, ReadPage, EvaluateJs, SiteCommand, Tabs, Downloads, Upload, SiteMode, Script, Interact, Capture, WaitFor, KeepOpen,
    RunCommand, RunBundledScript, ReadFile, WriteFile, SearchFiles, ShareFile,
    Wait, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
    KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, ProfileEdit, HistoryRead,
    ScheduleTask, SpawnAgent, SettingsList, SettingsSet,
    GenerateImage, EditImage,
}

/** Tool declarations retrieved by name. */
interface ToolInfo {
    val name: String
    val verb: Verb
    /** The id of the ability to which it belongs; null for core's own tools. */
    val capability: String?
    /** Multiple steps (do_steps) can be entered at once. */
    val batchable: Boolean
    /** Record it and replay it through the experience route. */
    val routable: Boolean
    /** It is said on the interface; when it is not available, the tool name is displayed. */
    val label: String?
    /** `mina phone <operation>` in the sandbox can adjust it (mobile phone capabilities, the result is text); the rest are rejected. */
    val sandboxCallable: Boolean get() = false
}

/**
 * Search tool declarations by name; unknown names are null. [former] recognizes the name before the name
 * change as the current one (used when reading old running records, see LegacyNames); [names] only has
 * the currently registered name.
 */
class ToolIndex(tools: Collection<ToolInfo>, private val former: (String) -> String? = { null }) {
    private val byName: Map<String, ToolInfo> = tools.associateBy { it.name }

    val names: Set<String> get() = byName.keys

    operator fun get(name: String?): ToolInfo? = name?.let { byName[it] ?: former(it)?.let(byName::get) }

    fun verb(name: String?): Verb? = get(name)?.verb

    /** The name of the tool that performs this action: [capability]. If it is given, only the one with that ability will be found (the group whose core tool is null). If it is not given, the first registered one will be taken. */
    fun nameOf(verb: Verb, capability: String? = null): String? =
        byName.values.firstOrNull { it.verb == verb && (capability == null || it.capability == capability) }?.name

    /** Screen capability [from] What is the name of the tool that performs the same action in the [to] set? If not, it is null. */
    fun counterpart(name: String, to: String): String? = get(name)?.let { nameOf(it.verb, to) }
}

/**
 * A tool: definition (name, description, parameters) + declared risk + execution. New tool = Add an
 * implementation and register it in the corresponding capability. The distributor does not know the
 * specific tool. The status is all in [ToolContext], and the tool itself has no status.
 */
internal interface AgentTool : ToolInfo {
    val def: ToolDef
    override val capability: String? get() = null
    override val batchable: Boolean get() = false
    override val routable: Boolean get() = false
    override val label: String? get() = null
    /** Do not move the screen (sandbox type): However, the screen is locked and the screen guard is not entered. The pictures in the results are files and not screen images. */
    val offScreen: Boolean get() = false
    val risk: Risk
    /** Non-interface capabilities can declare approval risks directly; interface tools are still judged by the target and current observation. */
    val approvalRisk: ApprovalRisk? get() = null
    val concurrency: Concurrency get() = Concurrency.Serial
    /** Irreversible judgment depends on the target of this call; the External tool must be given, and the rest are null (no judgment). */
    val gateTarget: GateTarget? get() = null
    /** If you don't move the interface, the actions inside it will be returned one by one through the execution entrance (page script): However, the lock screen level cannot be called as a read-only call. */
    val composite: Boolean get() = false
    /**
     * The entrance to the ability group is also provided when closed (see [ToolGroups]): use a short
     * description (prompts/entries/<tool name>.md) when closed, and return to the full text after expansion.
     * Not the entry is null.
     */
    val entry: Entry? get() = null
    /** It is a general tool that does not belong to any ability and is only provided when the groups with screens are expanded (taking over, reporting obstacles, and reviewing the screen). */
    val withScreens: Boolean get() = false
    /**
     * Cancel the call without losing anything (just wait, or just ask to see the graph model, without
     * moving the interface): cancel it as soon as the user interrupts, and the result will be reported
     * as [com.meenai.mina.core.run.SKIPPED_FOR_NEW_MESSAGE]. The model will read this sentence first,
     * and call it again if it is needed.
     */
    val yieldsToSteer: Boolean get() = false

    override val name: String get() = def.name
    /** The definition sent to the model; according to this task, there are several screens and additional parameter tools (surface) to cover it. */
    fun def(ctx: ToolContext): ToolDef = def
    /** Indicate which file it comes from (prompts/tools/<file>.md); the default name is the same, if several tools share a template, write it separately. */
    val promptFile: String get() = name
    val description: String get() = def.description
    /** The JSON schema of the parameters (the one sent to the model). */
    val params: String get() = def.spec().parametersJsonSchema

    /**
     * Which screen this call works on (the id of the screen capability): the screen tool is your own; it
     * refers to which screen is determined by the parameters of the general tool to see the parameters;
     * the rest are null (unchanged). Based on this, the dispatcher remembers which screen it is
     * operating on before calling.
     */
    fun aimAt(o: JsonObject): String? = capability

    /** Whether this task can be called (look at the configuration: whether there is a graph model, skill catalog, web port...). */
    fun available(ctx: ToolContext): Boolean = true

    /** Whether to enter the tool list sent to the model at this time; the default is the same as [available]. */
    fun offered(ctx: ToolContext): Boolean = available(ctx)

    suspend fun execute(a: Args, ctx: ToolContext): ToolExecution

    /** After success, the result will include the screen after the action (when the screen with the action is on), and a separate frame of the main model that can be viewed. */
    val showsScreenAfter: Boolean get() = false

    /**
     * Actions that change the interface (click, enter, clear, key, return, slide...): The distributor
     * looks at the screen (element table + screen) before and after the action, and writes the actual
     * effect into the effect field of the result (see ActionEffect on the interface status side). Only
     * reads the screen, no other actions are taken.
     */
    val reportsEffect: Boolean get() = false

    /** Enter the action list (actions that will change something or go to where in the mobile phone, web page, or sandbox); the object will be longer when [wideTarget]. */
    val logged: Boolean get() = false
    val wideTarget: Boolean get() = false
    /** The object written in the action list; when writing the name of the element by number, take the latest screen read of this screen from [ctx]. */
    fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = null
    /** The input text written in the action list (only when you are sure it is not a password box). */
    fun logTyped(o: JsonObject, r: ToolExecution): String? = null

    /**
     * The model in the result may be referenced in the final answer, and no other observations are given
     * during execution (which web page was opened, the output of the command...); the distributor
     * records them in the verification material together with [ToolExecution.observations] in the
     * result. [log] contains a small amount of cross-tool status (listed App names, written files).
     */
    fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> = emptyList()
}

/** Tools that will change the status of the screen and page: pass the lock screen/pause level. Following the stated risks, non-screen and page scripts are not among them. */
internal val AgentTool.writesScreen: Boolean get() = risk != Risk.Read && !offScreen && !composite

/** Can read-only calls be made: read-only, move the screen (there are guards for those that don’t move the screen), and not page scripts. */
internal val AgentTool.readOnly: Boolean get() = risk == Risk.Read && !offScreen && !composite
