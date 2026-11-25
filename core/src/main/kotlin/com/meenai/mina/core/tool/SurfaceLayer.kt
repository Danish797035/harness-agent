package com.meenai.mina.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * The layer that is shared by interface capabilities (mobile phones and web pages) does what it does
 * before and after each call: which screen is being operated, a glance at the actual effect before and
 * after the action, the interface after the action is attached, etc. The interface is stable and what it
 * looks like at the moment. The distributor only uses this interface and does not know the interface
 * states such as screen reading, elements, and numbers; the specific implementation is provided by the
 * assembly layer at the beginning. The id of the screen capability.
 */
internal interface SurfaceLayer {
    /** The screen being operated at the moment; the screen that was last successfully operated when no call is being made, and the first screen when the task starts. */
    val current: String

    /** At the beginning of a call, the effect is on the [id] screen; if it is not a screen or is not provided, the current screen is used. */
    fun enter(id: String?)

    /** When that call ends; [succeeded], its screen becomes the most recently operated screen. */
    fun leave(succeeded: Boolean)

    /** Which screen this tool acts on; null if the tool is not a screen. */
    fun surfaceOf(tool: AgentTool?): String?

    /** The result given to the model when the tool on this screen is operated with the number of another screen (not executed); if it is not recognized or used, the error is null. */
    fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution?

    /** This call is not on the interface (or it does not go to [around]): the action target left last time is not retained. */
    fun skip()

    /**
     * Wrap a tool for execution: prepare the interface before, [body] to get whether "this time the
     * actual effect will be reported from one side of the interface", and then do the finishing touches
     * on the interface (press [inlineScreen] to attach the interface differences, attach the screen,
     * report the effect, and clear the points obtained by looking at the picture).
     */
    suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution

    /** When it is on, the target element and anchor point are taken before the write operation is executed (set by the loop when the playback switch is on). */
    var captureTargets: Boolean

    /** The running record uses: the port number corresponding to the model number at this moment; if not, it is empty. */
    fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject

    /** Which reads are also visible to the model at this time (by number). */
    fun setVisible(ids: Set<String>)

    /** Wait until the recently operated screen is stable, at most [timeoutMs]; if the status cannot be read, return as usual. */
    suspend fun settle(timeoutMs: Long)

    /** The words that can be seen on the screen that was recently operated (one for each element, no number is registered); if not read, it is empty. */
    suspend fun visibleText(): List<String>

    /**
     * On the recently operated screen, wait until [until] is established, or you see an accident, the
     * time limit is reached, [interrupted] is true (the user interrupted), and then wait no longer; the
     * time is calculated as [clock]. Screen reading does not register the number.
     */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome

    /** The frame of the screen that was recently operated and its frame number at this moment; if it is protected or cannot be intercepted, it is null. */
    suspend fun imageNow(): Pair<ByteArray, Int>?

    /** What it looks like at the moment (the content and the screen are at the same moment); null when nothing is opened. The observation has not yet been recorded in the ledger. */
    suspend fun viewNow(): ToolDispatcher.Glance?

    /** The version of that place at the moment (mobile version, computer version of the web page); if it is not available or cannot be read, it is null. */
    suspend fun mode(): String?
}
