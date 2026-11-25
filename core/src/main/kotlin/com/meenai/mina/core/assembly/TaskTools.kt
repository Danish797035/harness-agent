package com.meenai.mina.core.assembly

import com.meenai.mina.core.agent.CoreSections
import com.meenai.mina.core.agent.DO_STEPS_TOOL
import com.meenai.mina.core.cap.phone.AppPacing
import com.meenai.mina.core.cap.phone.PHONE
import com.meenai.mina.core.cap.phone.PhoneCapability
import com.meenai.mina.core.cap.sandbox.SandboxCapability
import com.meenai.mina.core.cap.sandbox.SANDBOX
import com.meenai.mina.core.cap.sandbox.SandboxGroup
import com.meenai.mina.core.cap.sandbox.SandboxPort
import com.meenai.mina.core.cap.web.WEB
import com.meenai.mina.core.cap.web.WebCapability
import com.meenai.mina.core.cap.web.WebGroup
import com.meenai.mina.core.cap.web.WebPort
import com.meenai.mina.core.uistate.IdleScroll
import com.meenai.mina.core.cap.web.PagePacing
import com.meenai.mina.core.uistate.PlacePace
import com.meenai.mina.core.cap.mcp.McpConnection
import com.meenai.mina.core.general.GeneralGroup
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.record.RunRecords
import com.meenai.mina.core.memory.ProfileStore
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.skills.PersonalSkills
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.LegacyNames
import com.meenai.mina.core.tool.ToolConfig
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.vision.ImageOps
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.core.workspace.WorkspacePathCheck
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.trigger.Scheduled
import com.meenai.mina.core.trigger.TriggerDefinition
import com.meenai.mina.core.trigger.ScheduleAdmin
import com.meenai.mina.core.child.ChildRunHost
import com.meenai.mina.core.userscreen.UserScreenSource

/**
 * A tool for one task: equipped with three capabilities and core tools: mobile phone, web page, and
 * sandbox. This is the only place where several abilities are recognized simultaneously. Capabilities
 * that are not provided with ports are still installed (no tools are provided), and their tools can also
 * be found when searching for declarations by name.
 */
fun toolDispatcher(
    screen: ScreenPort,
    visionEnabled: Boolean = false,
    /** Read-only knowledge and skills directory; knowledge_search / knowledge_read / skill_search / skill_read is not provided when empty. */
    skills: GuideCatalog? = null,
    /** When reading the screen, take another screenshot for the running record (the model that cannot see the picture also keeps the screenshot). */
    recordFrames: Boolean = false,
    /** Picture viewing model; when it is empty (there is no model that can view pictures in the settings), no visual tools are provided. */
    vision: VisionService? = null,
    /** Built-in browser; when empty, no web tools are provided, only the mobile interface. */
    web: WebPort? = null,
    /** Check the workspace path for web page upload (normalization and symbolic link resolution are done by the app side). */
    workspacePath: ((String) -> WorkspacePathCheck)? = null,
    /** The rhythm of reading web pages page by page (make up the pause before page change, see [PagePacing]); the default is not equal. */
    webPacing: () -> PagePacing = { PagePacing.Off },
    /** The website's requirements for browsing speed (according to host name); the default is the pace of the site knowledge in [skills]. */
    webPlacePace: (String) -> PlacePace = { host -> PlacePace.of(skills?.sitePace(host)) },
    /** When the web page stays, gently scroll the page a few times (see [IdleScroll]); the default is not to scroll. */
    webIdleScroll: () -> IdleScroll? = { null },
    /** The rhythm of step-by-step operations in the App (complementary pause before changing the action on the screen, see [AppPacing]); the default is not equal. */
    appPacing: () -> AppPacing = { AppPacing.Off },
    /** App's requirements for browsing speed (according to package name); the default is the pace of App knowledge in [skills]. */
    appPace: (String) -> PlacePace = { pkg -> PlacePace.of(skills?.appPace(pkg)) },
    /** When staying on a new screen in the App, gently scroll a few times (see [IdleScroll]); the default is not to scroll. */
    appIdleScroll: () -> IdleScroll? = { null },
    /** Stable numbering across reading screens: Screen reading gives the model number and s<N>, and translates it back to the port number before action. */
    stableRefs: Boolean = false,
    /** After the write operation is successful, wait until the interface is stable before reading another screen, and put the difference with the previous screen into the screen field of the result; [stableRefs] is implied. */
    inlineScreen: Boolean = false,
    /** A table of model elements is given first; drawings are automatically attached only when the element information is obviously insufficient. Action still brings back elemental changes. */
    adaptiveVision: Boolean = false,
    /** Speed-up switch deepLinks: Provides tools for opening links. */
    deepLinks: Boolean = false,
    /** Memory; and [utterances] are both given before providing memory_search / memory_save. */
    memory: MemoryStore? = null,
    profile: ProfileStore? = null,
    /** What the user said this time, the quotation of memory_save is checked accordingly; AgentLoop adds it. */
    utterances: UserUtterances? = null,
    /** The conversation in which this mission is located; the source of recording it into memory. */
    conversationId: String? = null,
    /** Linux sandbox; sandbox tool is provided when the device supports it (the tool call will wait for it when it is not ready). */
    sandbox: SandboxPort? = null,
    /** The workspace (conversation ID) of this task. */
    workspace: () -> String = { "default" },
    /** When the sandbox starts running a command or script (the task card displays "Sandbox:..."). */
    onSandboxCommand: (String) -> Unit = {},
    /** The progress reported by `mina progress` in the sandbox. */
    onSandboxProgress: (String) -> Unit = {},
    /** The sandbox tool is waiting for the running environment to be ready (the task card shows "Preparing"). */
    onSandboxPreparing: () -> Unit = {},
    /** When starting to run the skill script; if not, press [onSandboxCommand] to report. */
    onSandboxScript: ((String) -> Unit)? = null,
    /** The pictures sent to the main model are uniformly encoded; when empty, they are sent as they are. */
    imageOps: ImageOps? = vision?.ops,
    /** Capabilities allowed by opening constraints; old callers are not passed when still getting today's tools. */
    runSpec: RunSpec = RunSpec(RunId("legacy"), "legacy", "legacy"),
    mcpServers: List<McpConnection> = emptyList(),
    /** Save a scheduled task and indicate whether it can be on time (see [Scheduled]). */
    scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    scheduleAdmin: ScheduleAdmin? = null,
    childHost: ChildRunHost? = null,
    /** The screen sent by the user in this conversation; provided view_user_screen either this time or in previous rounds. */
    userScreens: UserScreenSource? = null,
    /** The user's personal skills; and [skills], [utterances] are all given before skill_save is provided (write down or change a personal skill when the user requests it). */
    personalSkills: PersonalSkills? = null,
    /** Change the whitelist entry set by Mina itself; when it is empty, settings_list and settings_set will not be provided (see [SelfConfig]). */
    selfConfig: com.meenai.mina.core.settings.SelfConfig? = null,
    /** Saved running records; provide history_read (check what was read in previous rounds) when this conversation has previously recorded running rounds. */
    runRecords: RunRecords? = null,
    imageService: com.meenai.mina.core.image.ImageGenerationService? = null,
    onImageProgress: ((isGenerating: Boolean, prompt: String, model: String) -> Unit)? = null,
    onImageSaved: ((bytes: ByteArray, prompt: String, model: String) -> String)? = null,
    attachedPhoto: com.meenai.mina.core.image.AttachedPhoto? = null,
    isSensitivePackage: ((String?) -> Boolean)? = null,
): ToolDispatcher {
    val config = ToolConfig(visionEnabled, skills, recordFrames, vision, inlineScreen, adaptiveVision, memory, profile, utterances, conversationId,
        imageOps, scheduleTrigger, scheduleAdmin, runSpec, childHost, userScreens, personalSkills, selfConfig, runRecords,
        imageService, onImageProgress, onImageSaved, attachedPhoto, isSensitivePackage)
    val providers: List<CapabilityProvider> = listOf(
        object : CapabilityProvider {
            override val id = PHONE
            override fun open(spec: RunSpec) = PhoneCapability(screen, deepLinks, appPacing(), appPace, appIdleScroll())
        },
        object : CapabilityProvider {
            override val id = WEB
            override fun open(spec: RunSpec) = WebCapability(web, workspacePath, webPacing(), webPlacePace, webIdleScroll())
        },
        object : CapabilityProvider {
            override val id = SANDBOX
            override fun open(spec: RunSpec) = SandboxCapability(sandbox, workspace, visionEnabled,
                onSandboxCommand, onSandboxProgress, onSandboxPreparing, onSandboxScript)
        },
    ) + mcpServers.map(::McpClientProvider)
    return RunAssembler(providers).assemble(runSpec, config, stableRefs, FORMER_PREFIX, RENAMED, RETIRED, MOVED)
}

/** The prefix of each capability tool name before the name change: there is no prefix for the mobile phone, and it is browser_ for the web page. */
private val FORMER_PREFIX = mapOf(PHONE to "", WEB to "browser_")
/** When the screen tool is renamed for the second time, the change in the verb part (previous → now) is the same for mobile phones and web pages. */
private val SCREEN_VERBS_MOVED = mapOf(
    "dump_elements" to "list_elements", "locate_on_screen" to "find_visually",
    "screen_description" to "describe", "check_screen" to "diagnose",
)

/** Except for the prefix, the rest has also been changed (current name → earliest name; the screen tool that was renamed for the second time was the earliest name shared by several screens). */
private val RENAMED = mapOf("web_wait_for" to "browser_wait") +
    SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$now" to old } }

/**
 * The entire name has been changed (previous name → current name). One is divided into two columns:
 * knowledge and skills: when the model adjusts the old name, select it according to the parameters (see
 * [LegacyNames.replacements]), and write both the old names in the text.
 */
private val MOVED: Map<String, List<String>> = mapOf(
    "find_skills" to listOf("knowledge_search", "skill_search"),
    "skill_view" to listOf("knowledge_read", "skill_read"),
    "run_script" to listOf("run_bundled_script"),
    "report_barrier" to listOf("handle_obstacle"),
    "report_failure" to listOf("end_not_done"),
    "request_input" to listOf("ask_user"),
    "user_takeover" to listOf("hand_to_user"),
    "recall_screenshot" to listOf("view_past_screens"),
) + SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$old" to listOf("${it}_$now") } }

/** Removed and no replacement tool (previous name → how to do it now): In the past, the tool was used to deliver the results, but now the reply without the call is the answer. */
private val RETIRED = mapOf(
    "finish" to "reply to the user with your answer as text, without calling any tool",
    "phone_ask_app_ai" to "read the app's own screens yourself (asking an app's built-in AI assistant is no longer available)",
)

/** The tool name before the name change → the current name (used when reading old routes, running records, and personal skills). */
fun legacyNames(): LegacyNames =
    LegacyNames(knownTools(), FORMER_PREFIX, RENAMED, retired = RETIRED, moved = MOVED)

/** Statement of the tool used to read the running records: the old name written down before the name change is also recognized as the current tool (the actions and abilities are the same), and the old records and new ratings are the same. */
fun recordedTools(): ToolIndex = legacyNames().let { legacy -> ToolIndex(allTools()) { legacy.resolve(it) } }

/** All possible tools provided by Mina, in the order in which they are issued to the model. */
internal fun allTools(): List<AgentTool> =
    GeneralGroup.tools + PhoneCapability.tools() + WebGroup.tools + SandboxGroup.tools

/** Declaration of all tools Mina may provide. */
fun knownTools(): ToolIndex = ToolIndex(allTools())

/** All possible tool names provided by Mina (the tool names mentioned in the skill document are verified against this). */
fun knownToolNames(): Set<String> = knownTools().names + DO_STEPS_TOOL
