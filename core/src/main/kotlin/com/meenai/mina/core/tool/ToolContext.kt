package com.meenai.mina.core.tool

import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.Change
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.memory.ProfileStore
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.vision.ImageOps
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.core.visual.Shots
import com.meenai.mina.core.capability.CapabilityKey
import com.meenai.mina.core.knowledge.BundledScripts
import com.meenai.mina.core.trigger.Scheduled
import com.meenai.mina.core.trigger.TriggerDefinition
import com.meenai.mina.core.trigger.ScheduleAdmin
import com.meenai.mina.core.child.ChildRunHost
import com.meenai.mina.core.run.RunSpec

/** The configuration of this task (model, skills, memory, switches); the respective ports and switches of the abilities are in the abilities. */
internal class ToolConfig(
    val visionEnabled: Boolean = false,
    val skills: GuideCatalog? = null,
    val recordFrames: Boolean = false,
    val vision: VisionService? = null,
    val inlineScreen: Boolean = false,
    val adaptiveVision: Boolean = false,
    val memory: MemoryStore? = null,
    val profile: ProfileStore? = null,
    val utterances: UserUtterances? = null,
    val conversationId: String? = null,
    val imageOps: ImageOps? = null,
    /** Save a scheduled task and indicate whether it can be on time (see [Scheduled]). */
    val scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    val scheduleAdmin: ScheduleAdmin? = null,
    val runSpec: RunSpec? = null,
    val childHost: ChildRunHost? = null,
    /** The screen sent by the user in this conversation; view_user_screen is not provided if there is none. */
    val userScreens: com.meenai.mina.core.userscreen.UserScreenSource? = null,
    /** The user's personal skills; skill_save is provided only after it is given (and there is a skill catalog and what the user said this time). */
    val personalSkills: com.meenai.mina.core.skills.PersonalSkills? = null,
    /** Change the whitelist entry set by Mina itself; when it is empty (the user does not allow it), settings_list and settings_set will not be provided. */
    val selfConfig: com.meenai.mina.core.settings.SelfConfig? = null,
    /** Saved run records; history_read is provided when this conversation has previously recorded runs. */
    val runRecords: com.meenai.mina.core.record.RunRecords? = null,
    val imageService: com.meenai.mina.core.image.ImageGenerationService? = null,
    val onImageProgress: ((isGenerating: Boolean, prompt: String, model: String) -> Unit)? = null,
    val onImageSaved: ((bytes: ByteArray, prompt: String, model: String) -> String)? = null,
    val attachedPhoto: com.meenai.mina.core.image.AttachedPhoto? = null,
    val isSensitivePackage: ((String?) -> Boolean)? = null,
    val visionCheck: (() -> Boolean)? = null,
) {
    val effectiveVisionEnabled: Boolean get() = visionCheck?.invoke() ?: visionEnabled
}

/**
 * Things that are common when the tool is executed: configuration, verification materials, things put in
 * by capabilities ([get]), and a small amount of status across tools (rejected payment declarations,
 * allowed links to be opened...). The tool itself is stateless, everything is taken from here. The
 * screen reading status with the interface capability is also put in and is accessed from the interface
 * status side.
 */
internal class ToolContext(
    val config: ToolConfig,
    val evidence: EvidenceLog,
    /** A statement of all tools that may be provided by this task (the page script looks for tools by action). */
    val tools: ToolIndex,
    /** The ability to run skill scripts; null if not. */
    val scripts: BundledScripts?,
    val shots: Shots,
) {
    private val parts = HashMap<CapabilityKey<*>, Any>()

    /** What the ability puts in at the beginning of the task; null if not put. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: CapabilityKey<T>): T? = parts[key] as T?

    operator fun <T : Any> set(key: CapabilityKey<T>, value: T) { parts[key] = value }

    /** The latest rejected payment declaration; when taking over the payment, make up for the goods and amounts not written in the model. */
    var refusedPay: ConfirmationRequest? = null
    /** Whether the app has been successfully opened during this task; if not, the checker knows that there is no screen to compare. */
    var appOpened = false
    /** The image path that has been successfully generated in this task; after generation, it prevents the model from repeatedly calling generate_image. */
    var lastGeneratedImagePath: String? = null
    /** The image path that has been successfully edited in this task; prevents the model from repeatedly calling edit_image after editing. */
    var lastEditedImagePath: String? = null
    /** The number of this call; the actions in the page script are numbered according to this number. */
    var callId = ""
    /** The actions in the tools (page scripts) that do not move the interface are returned to the execution entrance through it; when the read-only call is not given in the test, the actions in it will not be executed. */
    var sub: SubActions? = null
    /** The round this call is in (the "Nth step" of the loop). */
    var currentStep: Int
        get() = shots.step
        set(v) { shots.step = v }

    /** The links to the models are given in the reference materials; open_link without action can only open these (uri and package name must be consistent). */
    val allowedLinks = mutableSetOf<Pair<String, String?>>()

    private val saved = mutableListOf<Change>()
    val savedChanges: List<Change> get() = synchronized(saved) { saved.toList() }
    fun noteSaved(c: Change) = synchronized(saved) { saved += c }

    /** The tool called this time will report the actual effect (effect) from the distributor: "No change". In terms of effect, there will be no further prompts here. */
    var reportsEffect = false

    /** The ability in this call is the time when the rhythm is intentionally stopped (see [ToolExecution.waitMs]); the ability is increased when it stops, and the result is handed over after the distributor is cleared for each call. */
    var pacedWaitMs = 0L

    /** When staying in this call, the page scrolled slightly a few times (see [com.meenai.mina.core.uistate.IdleScroll]); like [pacedWaitMs], it is cleared every time it is called, and only the running record is entered. */
    var idleScrolls = 0
}

/** The declaration fields written by the model are truncated so that they can be displayed as they are on the interface. */
internal fun capField(s: String): String = s.trim().let { if (it.length <= CONFIRM_FIELD_MAX) it else it.take(CONFIRM_FIELD_MAX - 1) + "…" }
