package com.meenai.mina.core.tool

import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.risk.IrrevAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Signal for terminating/interrupting tool loops to Agent. Normally completed without tools: the reply without calling is the answer given to the user. */
sealed interface Outcome {
    data class Failure(val message: String, val subtype: String = "interaction_failed", val recoverable: Boolean = false) : Outcome
    /** When paying, [item]/[amount]/[address] are listed separately on the takeover card, which is the confirmation of payment. */
    data class Takeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : Outcome
    data class Input(val prompt: String, val options: List<String>? = null) : Outcome
    /** Model declaration is an irreversible operation; [foregroundApp] is the front-end package name read during declaration, and it is null if it cannot be read. */
    data class Confirm(val request: ConfirmationRequest, val foregroundApp: String?) : Outcome
}

/**
 * [changed]: Whether the interface changes before and after the action verified by the port; if not
 * verified, it is null. [frame]: Only for the screen reading screen of the running record (not sent to
 * the model); use it when [image] is not empty. [images]: Pictures sent to the model with the results
 * (there can be multiple pictures when looking back at historical screenshots); [image] is the first
 * picture among them. [record]: Only enters the running record and does not send fields to the model
 * (such as the port number corresponding to the stable number). [screenElements]: Write the complete
 * element list of the screen with the operation result (only the difference is sent) at the moment; the
 * anti-stuck guard presses it to compare the screen. [imageIds]: The screen number corresponding to
 * [images] one-to-one (only for the interface screen; the image file read is null, if it is
 * insufficient, press null). [observations]: What is read in this call (the ability is given, see
 * [Observation]); the distributor records it in the evidence ledger and replaces it with the items in
 * the ledger (with numbers). [place]: Where is the task after this call (given by the result from the
 * capability side); if not stated, it is null (the previous one is used). [waitMs]: In this call, the
 * ability intentionally stops for the sake of rhythm (the pause is made when the page is changed before
 * the page is read enough); the running record counts this as waiting.
 */
class ToolExecution(
    val content: JsonObject,
    image: ByteArray? = null,
    val changed: Boolean? = null,
    val outcome: Outcome? = null,
    val frame: ByteArray? = null,
    val images: List<ByteArray> = listOfNotNull(image),
    val record: JsonObject = JsonObject(emptyMap()),
    val screenElements: JsonArray? = null,
    val imageIds: List<Int?> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val place: Place? = null,
    val waitMs: Long = 0,
) {
    val image: ByteArray? get() = images.firstOrNull()

    /** Replace a few of them, but the others (including the observations you read) remain the same. */
    fun with(
        content: JsonObject = this.content, changed: Boolean? = this.changed, frame: ByteArray? = this.frame, images: List<ByteArray> = this.images,
        record: JsonObject = this.record, screenElements: JsonArray? = this.screenElements, imageIds: List<Int?> = this.imageIds,
        observations: List<Observation> = this.observations, place: Place? = this.place, waitMs: Long = this.waitMs,
    ) = ToolExecution(content, changed = changed, outcome = outcome, frame = frame, images = images, record = record,
        screenElements = screenElements, imageIds = imageIds, observations = observations, place = place, waitMs = waitMs)

    /** Also read [more]. */
    fun observing(vararg more: Observation?): ToolExecution = more.filterNotNull().let { if (it.isEmpty()) this else with(observations = observations + it) }
}

/** Android package name: Each paragraph is separated by dots, and the first paragraph starts with a letter. launch_app, confirm declaration, jump App link sharing. */
val PACKAGE_NAME_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** Reviewing retains the most recent screen readings; you can review at most a few pictures at a time. */
internal const val RECALL_KEEP = 20
internal const val RECALL_MAX_IDS = 5

val FAILURE_SUBTYPES = listOf("blocked", "login_incomplete", "captcha_incomplete", "route_failed", "device_unavailable", "interaction_failed")
/** The target/content of request_confirmation is truncated to this length (written by the model, the interface must be displayed as it is). */
const val CONFIRM_FIELD_MAX = 200
/** request_confirmation action accepted; payment goes hand_to_user. */
val CONFIRM_ACTIONS = IrrevAction.entries.filter { it != IrrevAction.pay }.map { it.name }
val BARRIER_KINDS = listOf("popup", "login", "captcha", "access_blocked", "payment")
val TAKEOVER_REASONS = listOf("captcha", "login", "access_blocked", "payment", "decision", "other")
/** Common explanations of reasons for takeover and reasons before name change in skill documents can be replaced with the above reasons. The imported documents and saved skills do not need to be changed. */
val TAKEOVER_REASON_ALIASES = mapOf("password_required" to "login", "2fa" to "login", "manual_review" to "other", "risk_control" to "access_blocked")

/** The end of the takeover. aborted means the user canceled the entire task. */
enum class HandBackOutcome { complete, skipped, timeout, aborted }

/**
 * Result of taking over control, passed to the model as the tool result of `hand_to_user`.
 * [userFinished]: user indicated they completed it themselves; [durationMs]: duration spent by the
 * user; [inputCount]: number of user actions in the app; [stoppedAt]: the app screen when handed back
 * (null if unknown).
 */
data class HandBack(
    val outcome: HandBackOutcome, val userFinished: Boolean = false,
    val durationMs: Long? = null, val inputCount: Int? = null, val stoppedAt: String? = null,
    /** Notes written by the user when handing it back (the user’s original words, which can be used as a source of memory). */
    val note: String? = null,
)

