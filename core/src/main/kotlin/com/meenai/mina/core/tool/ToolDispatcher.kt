package com.meenai.mina.core.tool

import com.meenai.mina.core.knowledge.Link
import com.meenai.mina.core.observation.CORE_SOURCE
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.Change
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.meenai.mina.core.run.waitOrSteer
import com.meenai.mina.core.verify.Evidence
import com.meenai.mina.core.vision.ImageOps
import com.meenai.mina.core.vision.ModelImage
import com.meenai.mina.core.vision.VisionService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.meenai.mina.core.visual.Shots
import com.meenai.mina.core.capability.Capability
import com.meenai.mina.core.capability.Paced
import com.meenai.mina.core.capability.PromptEnv
import com.meenai.mina.core.capability.PromptSections
import com.meenai.mina.core.guard.OffScreenGuard

/**
 * The entrance to tool calling: parse the parameters, find the tool in [ToolRegistry] by name and
 * execute it, and then do the finishing touches common to all tools (attach the screen after the action,
 * clear the points obtained from the picture, action list, and verification materials). The specific
 * tools are all in their respective abilities, so I don’t know them here. It is created by the assembly
 * layer according to the ability of this task: [tools] is all the tools (in the order sent to the
 * model), [sections] is each group of paragraphs of the system prompt words (in the splicing order).
 *
 * The interface status of interface capabilities (mobile phones, web pages) is not here: the assembly
 * layer gives a [SurfaceLayer], and the dispatcher only does interface things through it before and
 * after each call. Before each call, press the screen to which the tool belongs and note which screen
 * you are operating ([aim]). If you use the wrong screen number, do not hand it over to the port and
 * directly indicate which set of tools to use.
 */
class ToolDispatcher internal constructor(
    private val capabilities: List<Capability>,
    tools: List<AgentTool>,
    private val sections: List<PromptSections>,
    config: ToolConfig,
    /** There is a layer shared by interface capabilities; it is built according to the tool context of this task (the interface state is put into the context, and the capability tools are taken from there). */
    surfaceLayer: (ToolContext) -> SurfaceLayer,
    /** How to recognize the tool name before it was renamed (see [LegacyNames]). */
    formerPrefix: Map<String, String> = emptyMap(),
    renamed: Map<String, String> = emptyMap(),
    /** Old name removed without replacement → What to do now (see [LegacyNames]). */
    retired: Map<String, String> = emptyMap(),
    /** The entire renamed old name → current name (see [LegacyNames]). */
    moved: Map<String, List<String>> = emptyMap(),
) {
    private val evidenceLog = EvidenceLog()
    private val shots = Shots()
    internal val ctx = ToolContext(
        config, evidenceLog, ToolIndex(tools), capabilities.firstNotNullOfOrNull { it.scripts }, shots,
    )
    /** There is a layer shared by interface capabilities (mobile phones, web pages); everything on the interface goes through it. */
    private val surfaces = surfaceLayer(ctx)

    init {
        capabilities.forEach { it.onTaskStart(ctx) }
    }

    /** Groups that are expanded only when used: one group for the collapsed abilities, and one group for common tools shared by the screen abilities ([ToolGroups.SCREENS]; they are always there when there is no collapsed screen). */
    private val folding: List<String> = capabilities.filter { it.folds }.map { it.id }
    private val screenGroups: Set<String> = capabilities.filter { it.folds && it.family != null }.map { it.id }.toSet()

    private fun groupOf(t: AgentTool): String? = when {
        t.capability in folding -> t.capability
        t.withScreens && screenGroups.isNotEmpty() -> ToolGroups.SCREENS
        else -> null
    }

    private val registry = ToolRegistry(ctx, tools, ::groupOf)

    /** The ability groups that have been expanded for this task (in order of expansion). */
    private val opened = linkedSetOf<String>()
    /** Ability groups that have actually been used in this mission (tools in the group have been adjusted; entrances that are sufficient for single use are not counted): The next round will be started accordingly. */
    private val usedGroups = linkedSetOf<String>()

    /** To the group that is given tools in this task, but not given an entrance (no commands are given in the read-only task): Don’t accept it, expand it from the beginning, so that the whole group will not be invisible. */
    private val entryless: Set<String> by lazy { folding.filter { registry.members(it).isNotEmpty() && !registry.hasEntry(it) }.toSet() }

    /** Expanded ability group (excluding [ToolGroups.SCREENS]). */
    val expanded: Set<String> get() = entryless + opened

    /** Ability group that was really used in this task. */
    val used: Set<String> get() = usedGroups.toSet()

    /** Expand [group](This task will be expanded only after its tools are provided); the newly expanded one returns true. */
    fun expand(group: String): Boolean = group in folding && group !in entryless && registry.members(group).isNotEmpty() && opened.add(group)

    /**
     * The start is pre-expanded: the groups used in the last round ([previous]), and the abilities that
     * I think will be useful this time (for the sandbox when bringing files in the workspace, see
     * [Capability.preload]). Returns the expanded group.
     */
    fun preload(task: String, previous: Collection<String>): List<String> =
        capabilities.filter { it.folds && (it.id in previous || it.preload(task)) }.map { it.id }.filter(::expand)

    /** In this sentence, the user removes the description of each ability (see [Capability.userWords]); in his typing this time, he also needs to remove the reference and attachment lines, see [com.meenai.mina.core.agent.UserWords.typed]. */
    fun userWords(text: String): String = capabilities.fold(text) { t, c -> c.userWords(t) }

    private fun openSet(): Set<String> = expanded.let { e -> if (e.any { it in screenGroups }) e + ToolGroups.SCREENS else e }

    /**
     * The tools sent to the model at this time: the always-present, expanded group (full text), the
     * closed group only provides access (short description), and the order is fixed (see [ToolGroups]).
     * [screenExtra] is a separate definition given by the loop itself that appears with groups with
     * screens (multiple steps at a time). [all] when each group is expanded.
     */
    fun offeredNow(screenExtra: List<ToolSpec> = emptyList(), all: Boolean = false): List<ToolSpec> =
        registry.layered(if (all) folding.toSet() + ToolGroups.SCREENS else openSet(), if (screenGroups.isEmpty()) emptyMap() else mapOf(ToolGroups.SCREENS to screenExtra)).let {
            if (screenGroups.isEmpty()) it + screenExtra else it
        }

    /** The tool name before the name change → the current name; used when reading old personal skills and practices, and prompting for the new name when calling the old name. */
    val legacy = LegacyNames(ctx.tools, formerPrefix, renamed, retired, moved)

    /** Picture viewing model; when it is empty (there is no model that can view pictures in the settings), no visual tools are provided. */
    val vision: VisionService? get() = ctx.config.vision
    /** The main model itself can be seen in the picture. */
    val seesImages: Boolean get() = ctx.config.visionEnabled
    /** After the writing operation is successful, wait until the interface is stable before reading another screen, and put the difference with the previous screen into the screen field of the result. */
    val inlineScreen: Boolean get() = ctx.config.inlineScreen
    /** What the user said this time, the quotation of memory_save is checked accordingly; AgentLoop adds it. */
    val utterances: UserUtterances? get() = ctx.config.utterances
    private val imageOps: ImageOps? get() = ctx.config.imageOps

    /** Use "Available Channels" for looping (see if the skill has a web page method). */
    internal val skillCatalog: GuideCatalog? get() = ctx.config.skills
    /** The ID of the recently used interface capability; it was the first one (mobile phone) when the task started. */
    val surface: String get() = surfaces.current

    /** The version of the current place written in the running status (mobile version, computer version of the web page); if it is not available or cannot be read, it is null. */
    suspend fun placeMode(): String? = surfaces.mode()

    /** This task provides tools for running skill scripts (notes for reference materials are written according to it). */
    fun scriptsOffered(): Boolean = ctx.scripts?.offered() == true

    /**
     * For each ability and core paragraph in the system prompt word, use the same tool list [specs] sent
     * to the model to determine whether it exists; the closed ability group will only have its own
     * paragraph after being expanded. When [all] is used, each group is expanded (the prompt word
     * version is calculated as all of them).
     */
    fun promptSections(specs: List<ToolSpec>, all: Boolean = false): List<String> {
        val offered = specs.map { it.name }.toSet()
        val screens = capabilities.filter { it.family != null }.count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val open = if (all) null else capabilities.filter { !it.folds || it.id in expanded }.map { it.id }.toSet()
        val openScreens = capabilities.filter { it.family != null && (open == null || it.id in open) }
            .count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val env = PromptEnv(offered, inlineScreen, screens, open, openScreens)
        return sections.flatMap { it.sections(env) }
    }

    /** The guard of the tool that does not move the screen (one copy of this task); if not, it is null. */
    fun offScreenGuard(): OffScreenGuard? = capabilities.firstNotNullOfOrNull { it.guard() }

    fun definitions(): List<ToolSpec> = registry.definitions()

    /** Will change the screen and page status (you need to pass the lock screen/pause level): introduced by the risk declared by the tool. */
    fun writesScreen(name: String): Boolean = registry.declared(name)?.writesScreen == true

    /** Tools that do not move the screen (such as sandboxes): do not enter the screen guard, and are managed by their respective guards; the pictures in the results are files. */
    fun offScreen(name: String): Boolean = registry.declared(name)?.offScreen == true

    /** The purpose written by the model for this call (additional reason parameter); the tool itself has a field called reason, and it is null when it is not written or is empty. */
    fun callReason(name: String, args: JsonObject): String? {
        if (registry.declared(name)?.def?.ownReason == true) return null
        return (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    }

    /** Check the statement of the tool by name (action, whether it can be batched, what it says on the interface...). */
    val index: ToolIndex get() = ctx.tools

    /** Tool names that can be put into do_steps, in the order listed in the definition. */
    fun batchable(): List<String> = registry.batchable()

    /** The action performed by this tool; null for unknown names. */
    fun verb(name: String?): Verb? = ctx.tools.verb(name)

    /** Which screen this tool acts on (the ability id of the screen tool); if it is not a screen tool, it is null. */
    fun surfaceOf(name: String): String? = surfaces.surfaceOf(registry.declared(name))

    /** The interface does not move, and the actions inside are returned one by one through the execution entrance (page script). */
    fun composite(name: String): Boolean = registry.declared(name)?.composite == true

    /** Irreversible determination of what to look at this tool's target; only tools declared as External have it. */
    fun gateTarget(name: String): GateTarget? = registry.declared(name)?.takeIf { it.risk == Risk.External }?.gateTarget

    /** Each time the loop cuts and compresses the history, it tells the distributor which reads the model can still see (by number); if the base of the difference is not among them, the full amount is sent. */
    fun setVisibleObservations(readIds: Set<String>) = surfaces.setVisible(readIds)

    /**
     * Start with a call: record which screen it is operating (the screen tool is its own screen, and the
     * general tool with surface parameter is parameterized; the rest uses the current screen) until the
     * corresponding [release]. The execution entry is entered once before judgment and again during
     * distribution.
     */
    fun aim(name: String, args: JsonObject) {
        surfaces.enter(registry.declared(name)?.aimAt(args))
    }

    /** That call ends; the screen that succeeds is considered the most recently operated screen (failed calls do not change the default value). */
    fun release(succeeded: Boolean) = surfaces.leave(succeeded)

    /**
     * Use the number of another screen to operate the tool on this screen: If it is not handed over to
     * the port, the result is wrong_surface, indicating which screen the number belongs to and which
     * tool should be used. If you cannot recognize which screen the number belongs to (it has expired
     * too long or was spelled incorrectly), it will be null, and it will be handed over to the tool as
     * usual (processed as expired).
     */
    fun misaimed(name: String, args: JsonObject): ToolExecution? = registry.declared(name)?.let { surfaces.misaimed(it, args, ctx.tools) }

    /** Read-only calls (screen reading, failure reporting, etc.): Tool statements that will change the screen, page or sandbox are all rejected, and those can only be done through the execution entrance. */
    suspend fun executeRead(call: Part.ToolCall, step: Int? = null): ToolExecution {
        val tool = registry.declared(call.name)
        if (tool != null && !tool.readOnly) {
            return fail("not_a_read", Feedback.format("not_a_read", "tool" to call.name), recoverable = false)
        }
        return dispatch(call, step, null)
    }

    /**
     * [pass] can only be obtained from the execution entrance (guards, levels, irreversible
     * determinations, and records are all there). [step]: The round number of the loop is consistent
     * with the "Nth step" of the folded summary; if not given, the number of calls executed will be
     * used. [sub]: The actions in web_script return to the loop (guards, levels, and records as usual)
     * through it; if not provided, all actions in the script will not be performed.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execute(call: Part.ToolCall, pass: ActionPass, step: Int? = null, sub: SubActions? = null): ToolExecution = dispatch(call, step, sub)

    private var executed = 0

    private suspend fun dispatch(call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        surfaces.skip()
        val tool = registry[call.name] ?: return unknown(call.name, parseArgs(call.argsJson))
        // The tools in the closed group (entrance, or the model adjusted from memory without showing it)
        // are executed as usual; its group is expanded, and as a result, the entire group can be seen
        // from the next call.
        val group = groupOf(tool)
        if (group != null && group != ToolGroups.SCREENS && tool.entry != Entry.Alone) usedGroups += group
        val opening = when {
            group == null || tool.entry == Entry.Alone -> null
            group == ToolGroups.SCREENS -> surfaces.current.takeIf { it in screenGroups }
            else -> group
        }?.takeIf(::expand)
        val result = dispatchTool(tool, call, step, sub)
        return if (opening == null) result else result.with(content = JsonObject(result.content + (MORE_TOOLS to JsonPrimitive(Feedback.format("group_opened",
            "group" to opening, "examples" to registry.members(opening).filter { registry[it]?.entry == null }.take(4).joinToString())))))
    }

    private suspend fun dispatchTool(tool: AgentTool, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        val args = try {
            val el = if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson)
            Args(tool.def(ctx), el as? JsonObject ?: throw ArgError("arguments", "must be a JSON object"))
        } catch (e: ArgError) {
            return invalid(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            return invalid(ArgError("arguments", "not valid JSON"))
        }
        shots.idAsSnapshot(args.o)?.let { return it }
        misaimed(call.name, args.o)?.let { return it }
        aim(call.name, args.o)
        var ok = false
        try {
            return run(tool, args, call, step, sub).also { ok = it.content["ok"] == JsonPrimitive(true) }
        } finally {
            release(ok)
        }
    }

    private suspend fun run(tool: AgentTool, args: Args, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        executed++
        ctx.currentStep = step ?: executed
        evidenceLog.step = ctx.currentStep
        ctx.callId = call.id
        ctx.sub = sub
        // The actions in the page script also go through this: each remembers its own stop, and the
        // outer layer still contains the inner one.
        val outerWait = ctx.pacedWaitMs
        val outerIdle = ctx.idleScrolls
        ctx.pacedWaitMs = 0
        ctx.idleScrolls = 0
        // The preparation and finishing on the interface (goals on the route, taking a look before
        // changing the actions on the interface) and finishing are all on one side of the interface.
        val result = surfaces.around(tool, args.o, inlineScreen) { reportsEffect ->
            ctx.reportsEffect = reportsEffect
            try {
                if (!tool.yieldsToSteer) tool.execute(args, ctx)
                else waitOrSteer { tool.execute(args, ctx) } ?: failCode(SKIPPED_FOR_NEW_MESSAGE)
            } catch (e: com.meenai.mina.core.access.AppAccessDenied) {
                throw e
            } catch (e: ArgError) {
                invalid(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("operation_failed", Feedback.format("operation_failed", "tool" to tool.name, "error" to (e.message ?: e::class.simpleName)))
            } finally {
                ctx.reportsEffect = false
            }
        }
        val waited = ctx.pacedWaitMs
        ctx.pacedWaitMs = outerWait + waited
        val idled = ctx.idleScrolls
        ctx.idleScrolls = outerIdle + idled
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (tool.logged) evidenceLog.logAction(stepLabel(), tool.name, tool.logTarget(args.o, result, ctx), tool.logTyped(args.o, result), result, tool.wideTarget)
        // What is read (the ones given by ability in the results, and the tools given separately
        // according to the results) are recorded in the ledger, and the results are replaced by the
        // items in the ledger.
        val seen = remember(result.observations + tool.observations(args.o, result, evidenceLog), tool.capability)
        // The words typed into the input box (the port clearly states that it is not a password box):
        // After reading the input box on the screen, the echo does not provide evidence of the answer.
        if (tool.verb == Verb.TypeText && ok && result.content["secret"] == JsonPrimitive(false)) args.o.argStr("text")?.let(evidenceLog::noteTyped)
        // While staying, gently scroll through the page: only the running record will be recorded, not
        // the model.
        val record = if (idled > 0) JsonObject(result.record + ("idleScrolls" to JsonPrimitive(idled))) else result.record
        return result.with(observations = seen, place = placeAfter(tool.verb, result.content), waitMs = waited, record = record)
    }

    /** Record it in the ledger; if no source is written, press [capability] (the core tool is core). Return the items recorded in the ledger and give each item to [observer]. */
    private fun remember(observations: List<Observation>, capability: String?): List<Observation> =
        observations.mapNotNull { o -> evidenceLog.observe(if (o.source != null) o else o.copy(source = capability ?: CORE_SOURCE))?.also { observer?.invoke(it) } }

    /** Each observation is recorded in the evidence ledger (the running record is recorded accordingly, and the assessment can still reconstruct the ledger); set by the loop. */
    var observer: ((Observation) -> Unit)? = null

    /** If you don’t recognize the tool; if it is the name before the name change, please specify what it is called now (do not replace the model with a new name). */
    private fun unknown(name: String, args: JsonObject) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", "unknown_tool"); put("tool", name)
        val now = legacy.replacements(name, args).filter { registry[it] != null }
        val instead = legacy.retiredAs(name)
        put("message", if (instead != null) Feedback.format("unknown_tool_retired", "name" to name, "instead" to instead)
            else if (now.isNotEmpty()) Feedback.format("unknown_tool_renamed", "name" to name, "tools" to now.joinToString(" or "))
            else Feedback.format("unknown_tool", "name" to name, "tools" to (registry.listedNames().joinToString())))
        put("recoverable", true)
    })

    /** The current position of the evidence ledger (see [EvidenceLedger.lastId]); write it down before the action, and then use [readSince] to see if it has been re-read after the action. */
    fun ledgerMark(): Int = evidenceLog.ledger.lastId

    fun readSince(mark: Int): Boolean = evidenceLog.ledger.readSince(mark)

    /** Find the tool registered for this task by name; if not recognized, it will be null. */
    internal fun declared(name: String): AgentTool? = registry.declared(name)

    /** Risk of tool declaration; null for unrecognized names. */
    fun declaredRisk(name: String): Risk? = registry.declared(name)?.risk
    fun approvalRisk(name: String): com.meenai.mina.core.action.Risk? = registry.declared(name)?.approvalRisk

    /** The user answer obtained by ask_user; the checker compares the facts given by the user. */
    /** The screen (words on the screen) sent by the user is also based on: the content on the screen mentioned in the answer comes from here. */
    fun noteUserScreen(screen: com.meenai.mina.core.userscreen.UserScreen) {
        if (screen.blocked || screen.lines.isEmpty()) return
        evidenceLog.observe(Observation(ObsKind.Read, screen.lines.joinToString("\n") { it.text }, source = CORE_SOURCE,
            place = screen.packageName?.let(com.meenai.mina.core.observation.Place::app),
            trust = com.meenai.mina.core.observation.Trust.Untrusted))
    }

    fun noteUserAnswer(prompt: String, answer: String) {
        evidenceLog.observe(Observation(ObsKind.UserSaid, "问用户「${prompt.take(200)}」,用户答:$answer", source = CORE_SOURCE))
        capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /**
     * Evidence for the verifier: Take another look at the recently used ability ([Capability.freshLook],
     * which does not affect the things in the model's hand), plus the evidence ledger and action list of
     * this task. [withImage] together with one frame; anti-screenshot page will not be captured. When
     * [fresh] is false, no longer look at it (as long as the ledger is verified literally).
     */
    suspend fun evidence(withImage: Boolean = false, fresh: Boolean = true): Evidence {
        val e = evidenceLog
        // Even if you can’t see what it looks like at the moment, you can still hand over what you read
        // before, and it will be judged by the verifier.
        val look = if (fresh) capabilities.firstOrNull { it.id == surface }?.freshLook(ctx, withImage) else null
        return Evidence(look?.observations.orEmpty(), look?.image, e.ledger.copy(), e.actionLog.toList(), look?.protected == true, ctx.appOpened,
            e.takeovers.toList(), userFacts = e.userFacts.toList())
    }

    /** When it is on, the target element and anchor point are taken before the write operation is executed (set by the loop when the playback switch is on). */
    var captureTargets: Boolean
        get() = surfaces.captureTargets
        set(v) { surfaces.captureTargets = v }

    /** For running records: the port number corresponding to the model number at the moment (given on the interface side; if not, it is empty). */
    fun portRecord(tool: String, args: JsonObject): JsonObject = surfaces.portRecord(registry.declared(tool), args)

    /** When the place being used at the moment stabilizes, it will be at most [timeoutMs]; if the status cannot be read, it will return as usual. */
    suspend fun settle(timeoutMs: Long) = surfaces.settle(timeoutMs)

    /** The words that can be seen in the place in use at the moment (one for each element, no registration number, no change of the things in the model's hand); if it cannot be read, it is empty. */
    suspend fun visibleText(): List<String> = surfaces.visibleText()

    /** Wait until [until] is established in the place currently in use (or see the unexpected, time limit, [interrupted]); see [SurfaceLayer.waitUntil]. */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome = surfaces.waitUntil(until, clock, interrupted)

    /** At this moment, an external action (send, submit) needs to be done on this screen: give it to the ability of this screen ([Capability.onOutwardStart]), and after completion, it must be followed by [outwardEnd]. */
    fun outwardStart(site: String) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardStart(ctx, site)
    }

    /** The external action is completed ([done]: Done). */
    fun outwardEnd(site: String, done: Boolean) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardEnd(ctx, site, done)
    }

    /** At this moment, there is an action to be made on this screen to change the screen: stop for a while according to the rhythm of this screen's capabilities ([Capability.beforeWrite]). */
    internal suspend fun beforeWrite(tool: String, outward: Boolean): Paced =
        capabilities.firstOrNull { it.id == surface }?.beforeWrite(ctx, verb(tool), outward) ?: Paced.NONE

    /** That action is done: hand it over to the ability of this screen ([Capability.afterWrite]). */
    internal fun afterWrite(tool: String, result: JsonObject) {
        capabilities.firstOrNull { it.id == surface }?.afterWrite(ctx, verb(tool), result)
    }

    /** The outcome of the takeover is known only after it is handed over, and is added to the action list through the loop (the verifier knows based on this whether the takeover actually occurred). */
    fun noteTakeover(reason: String, back: HandBack) {
        val effect = when (back.outcome) {
            HandBackOutcome.complete -> if (back.userFinished) "用户说已自己办完(未核实)" else "用户已交回(未核实)"
            HandBackOutcome.skipped -> "接管被跳过"
            HandBackOutcome.timeout -> "接管超时,无人处理"
            HandBackOutcome.aborted -> "任务被取消"
        }
        evidenceLog.noteTakeover("${stepLabel()} ${ctx.tools.nameOf(Verb.HandToUser)}($reason) $effect")
        if (back.outcome == HandBackOutcome.complete) capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /** The "Nth step" in the action list is consistent with the round of the cycle (several steps in the batch belong to the same round); there is no round in playback. */
    private fun stepLabel() = if (ctx.currentStep > 0) "第${ctx.currentStep}步" else "回放"

    /** A picture sent to the main model is uniformly coded as [ModelImage]; [screenshotId] is the review number of the screen. */
    fun modelImage(img: ByteArray, screenshotId: Int?): Part.Image = ModelImage.part(img, imageOps, screenshotId)

    /**
     * The main model can view a frame and its number (can be viewed back) in the place where the picture
     * is being used at the moment; it is not given for protected pages and payment pages, and it is null
     * when nothing is opened or cannot be obtained. Use it when replaying a handoff.
     */
    suspend fun imageNow(): Pair<ByteArray, Int>? = surfaces.imageNow()

    /** What it looks like at this moment: the content of the model ([content], the same registration number as the read) together with the scene at the same time ([image], null if not). */
    class Glance(val content: JsonObject, val image: ByteArray?, val imageId: Int, val observations: List<Observation> = emptyList())

    /** The main model can take a look after the action when viewing the picture: the content and the picture are at the same time; it is null when nothing is opened. What is read is recorded in the ledger. */
    suspend fun lookNow(): Glance? = surfaces.viewNow()?.let { v -> Glance(v.content, v.image, v.imageId, remember(v.observations, surface)) }

    /** Will the tool that opens the link release this set of parameters at this moment (only look at the link rules and reference materials, not execute them). */
    fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean =
        LinkPolicy.check(action, uri, pkg) == null && (action != null || (uri to pkg) in ctx.allowedLinks)

    /** Registers linked references when the loop hands them to the model. */
    fun allowLinks(links: List<Link>) {
        links.forEach { ctx.allowedLinks += it.uri to it.packageName }
    }

    /** Saved by memory_save in this task (the result card shows "remembered" and can be revoked). */
    val savedChanges: List<Change> get() = ctx.savedChanges

    private companion object {
        /** The field specified in the results when a group is expanded. */
        const val MORE_TOOLS = "moreTools"
    }

    /** For testing: Look back to see if there are any pictures in the record. */
    internal fun keepsRecallImages(): Boolean = shots.recalls.any { it.png != null }
}
