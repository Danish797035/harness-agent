package com.meenai.mina.core.action

/** How to save the sub-step screen: leave it to the caller (the model steps are saved in their own order), save or not save on the spot when the interface changes. */
enum class Frames { Caller, WhenChanged, None }

/** Who should write the instructions when being stopped: model (Chinese instructions, confirmation process), page script (errors received by the script), and the caller's own code instructions. */
enum class RefusalStyle { Model, Script, Code }

/**
 * Intentional differences between one source and others are only written here; the order of execution
 * itself is the same for all sources. [source] is the source in the running record.
 */
data class OriginPolicy(
    val source: String,
    /** Payments can be released with user consent; other sources will stop when encountering payments and return the model, never assuming user consent. */
    val usesGrant: Boolean,
    /** It is also considered irreversible when there is no confirmation level: the sub-step stops when it hits, and does not depend on the level. */
    val judgeWithoutGate: Boolean,
    /** Cannot find which element the point is on: true does not execute; false allows it to be executed but leaves traces. */
    val refuseUnresolved: Boolean,
    /** The guard will not execute it once prompted (playback will follow the route, and if there is any abnormality, the model will be returned). */
    val stopOnGuardWarning: Boolean,
    /** The caller has just waited for the lock screen level before reading the screen (playback requires checking the screen after waiting), and will not wait again before execution. */
    val gatedByCaller: Boolean,
    /** The read is only counted in the call it belongs to, and the maximum reminder is that the task will not be stopped (polling read in the page script). */
    val isolatedReads: Boolean,
    /** When not null: Once the action is started, it will be completed and recorded (cannot be cancelled), and the maximum wait time is this long. */
    val actionCapMs: Long?,
    val frames: Frames,
    /** Note the target element and the front and rear anchor points of the route (when playback is on). */
    val routeTargets: Boolean,
    /** The call blocked by the confirmation level is itself the result of being handed over to the model, and is recorded in the running record as usual. */
    val recordsRefusal: Boolean,
    val refusals: RefusalStyle,
)

/** Where does an action come from; [step] is the cycle round it is in. */
sealed interface Origin {
    val step: Int
    val policy: OriginPolicy

    /** A tool call for the model. */
    data class Model(override val step: Int) : Origin {
        override val policy get() = MODEL
    }

    /** A step in do_steps. */
    data class Batch(override val step: Int) : Origin {
        override val policy get() = BATCH
    }

    /** A `mina.*` action in web_script. */
    data class Script(override val step: Int) : Origin {
        override val policy get() = SCRIPT
    }

    /** One step of experience replay (without tuning the model). */
    data object Replay : Origin {
        override val step = 0
        override val policy get() = REPLAY
    }

    /** Low-level tool calls on the MCP server; there is no model, but it still has to go through the same execution entry. */
    data class McpTool(override val step: Int) : Origin {
        override val policy get() = MCP_TOOL
    }

    companion object {
        /** The upper limit of script actions on a page: open the URL and wait up to 20 seconds to load, then leave a margin until the page is quiet. */
        const val SCRIPT_ACTION_CAP_MS = 40_000L

        // Only the model step can release the payment with the user's consent; other sources will stop
        // and return when the payment is encountered.
        private val MODEL = OriginPolicy(
            "model", usesGrant = true, judgeWithoutGate = false, refuseUnresolved = false, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.Caller, routeTargets = true,
            recordsRefusal = true, refusals = RefusalStyle.Model,
        )
        private val BATCH = OriginPolicy(
            "batch", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.WhenChanged, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        // A script is a program on a web page and cannot be played back as a step in the route.
        private val SCRIPT = OriginPolicy(
            "script", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = true, actionCapMs = SCRIPT_ACTION_CAP_MS, frames = Frames.WhenChanged, routeTargets = false,
            recordsRefusal = false, refusals = RefusalStyle.Script,
        )
        // The playback is consistent with the model step: no judgment is made when there is no
        // confirmation level.
        private val REPLAY = OriginPolicy(
            "replay", usesGrant = false, judgeWithoutGate = false, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = true, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        private val MCP_TOOL = OriginPolicy(
            "mcp_tool", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = false,
            recordsRefusal = true, refusals = RefusalStyle.Code,
        )
    }
}

/** The initiator of the action. Leave Origin as the name of the old caller; RunOrigin simply indicates where this run came from. */
typealias Invoker = Origin
