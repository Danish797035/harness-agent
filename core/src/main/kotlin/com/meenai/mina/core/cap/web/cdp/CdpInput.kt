package com.meenai.mina.core.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.meenai.mina.core.uistate.CdpKey
import com.meenai.mina.core.uistate.CdpKeys
import com.meenai.mina.core.uistate.KeyCombo
import com.meenai.mina.core.uistate.KeyModifier

// Real mouse and keyboard (Input field) on the web page. The coordinates are always the CSS pixels of
// the main frame viewport, consistent with those given by the page script. The rhythm (track, hold
// duration, key interval, scroll wheel division) is given by [InputPacing], and the default is
// [InputPacing.Instant] without pause. The scroll wheel can only be used on the label being displayed:
// the mouseWheel on the background label will hang, and all Input commands after this page will be
// blocked (the caller is responsible for the judgment).

/** Conversion between view pixels and CSS pixels; [scale] is how many view pixels each CSS pixel occupies (view width ÷ page viewport width). */
object CdpGeometry {
    fun scale(viewWidth: Int, cssViewportWidth: Double): Double =
        if (viewWidth > 0 && cssViewportWidth > 0) viewWidth / cssViewportWidth else 1.0

    fun toCss(viewPx: Int, scale: Double): Double = if (scale > 0) viewPx / scale else viewPx.toDouble()
}

// ---------- One input action ----------

/**
 * The result of an input action. [NotSent]: Not even an event that would change the page has been sent
 * (the session has been disconnected, and the first send was rejected by the protocol). It is safe to
 * change the native input and redo it. [Uncertain]: After sending the event, it failed (including
 * timeout). The page may have been received and cannot be done again; at this time, the pressed mouse
 * button and keyboard key have been lifted as much as possible.
 */
sealed interface InputOutcome<out T> {
    data class Done<T>(val value: T) : InputOutcome<T>
    data class NotSent(val error: Throwable) : InputOutcome<Nothing>
    data class Uncertain(val error: Throwable) : InputOutcome<Nothing>
}

/**
 * All commands issued in an input action pass through it: note whether there may have been an event
 * arriving on the page, as well as the mouse button and keyboard key pressed at the moment, and lift it
 * accordingly if it fails. Read-only queries (get viewport, read options) are not counted as events.
 */
class InputTrack internal constructor(
    val session: CdpSession,
    private val timeoutMs: Long,
    val pacing: InputPacing = InputPacing.Instant,
) {
    var mayHaveDelivered = false
        private set
    private var pressedAt: Pair<Double, Double>? = null
    private var dragging = false
    private val heldKeys = ArrayList<CdpKey>()

    /** Commands that will change the page. The session was disconnected before sending: not delivered. The agreement returned an error: this issue has not taken effect. If it has not been sent before, it is still not delivered. */
    internal suspend fun effect(method: String, params: JsonObject): JsonObject {
        if (session.closed.value) throw CdpConnectionClosedException("CDP session closed before $method")
        val before = mayHaveDelivered
        mayHaveDelivered = true
        try {
            return session.send(method, params, timeoutMs = timeoutMs)
        } catch (e: CdpException) {
            mayHaveDelivered = before
            throw e
        }
    }

    internal suspend fun query(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        session.send(method, params, timeoutMs = timeoutMs)

    internal suspend fun mouse(type: String, x: Double, y: Double, button: String, buttons: Int, clickCount: Int) {
        val s = session.inputScale
        // Write it down first before sending it: the press may have timed out or has been pressed
        if (type == "mousePressed") pressedAt = x to y
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", type); put("x", x * s); put("y", y * s)
                put("button", button); put("buttons", buttons); put("clickCount", clickCount)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
        if (type == "mouseReleased") pressedAt = null
    }

    internal suspend fun wheel(x: Double, y: Double, deltaY: Double) {
        val s = session.inputScale
        effect(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", "mouseWheel"); put("x", x * s); put("y", y * s)
                put("button", "none"); put("buttons", 0); put("deltaX", 0); put("deltaY", deltaY)
                put("pointerType", "mouse")
            },
        )
        session.pointer = x to y
    }

    /** Stop for a while according to the rhythm; [Instant][InputPacing.Instant] without stopping. */
    internal suspend fun pause(ms: Long) {
        if (ms > 0) delay(ms)
    }

    internal suspend fun drag(type: String, x: Double, y: Double, data: JsonObject) {
        dragging = true
        val s = session.inputScale
        effect("Input.dispatchDragEvent", buildJsonObject { put("type", type); put("x", x * s); put("y", y * s); put("data", data) })
        if (type == "drop") { dragging = false; pressedAt = null }
    }

    internal suspend fun key(type: String, k: CdpKey, modifiers: Int, text: String?) {
        if (type != "keyUp") heldKeys += k
        effect(
            "Input.dispatchKeyEvent",
            buildJsonObject {
                put("type", type)
                if (modifiers != 0) put("modifiers", modifiers)
                put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
                if (text != null) { put("text", text); put("unmodifiedText", text) }
            },
        )
        if (type == "keyUp") heldKeys.remove(k)
    }

    /** Try your best to lift the pressed key: each shot has a short upper limit, no matter if it fails (the page Input may be stuck). */
    internal suspend fun release() {
        suspend fun quiet(method: String, params: JsonObject) {
            runCatching { session.send(method, params, timeoutMs = RELEASE_TIMEOUT_MS) }
        }
        if (dragging) quiet("Input.cancelDragging", JsonObject(emptyMap()))
        pressedAt?.let { (x, y) ->
            quiet("Input.dispatchMouseEvent", buildJsonObject {
                put("type", "mouseReleased"); put("x", x * session.inputScale); put("y", y * session.inputScale)
                put("button", "left"); put("buttons", 0); put("clickCount", 1); put("pointerType", "mouse")
            })
        }
        for (k in heldKeys.asReversed()) {
            quiet("Input.dispatchKeyEvent", buildJsonObject {
                put("type", "keyUp"); put("key", k.key); put("code", k.code)
                put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
            })
        }
        pressedAt = null; dragging = false; heldKeys.clear()
    }

    companion object {
        const val RELEASE_TIMEOUT_MS = 1_000L
    }
}

/** Make an unrhymed typing motion (see below). */
suspend fun <T> CdpSession.input(timeoutMs: Long, block: suspend InputTrack.() -> T): InputOutcome<T> = input(timeoutMs, InputPacing.Instant, block)

/** Make an input action, the rhythm is [pacing]; the upper limit of each command is [timeoutMs]. Failure is divided into "not delivered" and "uncertain", the latter has raised the pressed key. */
suspend fun <T> CdpSession.input(timeoutMs: Long, pacing: InputPacing, block: suspend InputTrack.() -> T): InputOutcome<T> {
    val t = InputTrack(this, timeoutMs, pacing)
    return try {
        InputOutcome.Done(t.block())
    } catch (e: CancellationException) {
        withContext(NonCancellable) { t.release() }
        throw e
    } catch (e: Exception) {
        if (!t.mayHaveDelivered) InputOutcome.NotSent(e)
        else {
            t.release()
            InputOutcome.Uncertain(e)
        }
    }
}

/**
 * The CSS width of the page's visual viewport comes from the browser's layout data
 * (Page.getLayoutMetrics) without going through the page script: the page cannot change it, so it is
 * used to replace the view pixels with the CSS pixels used for Input.
 */
suspend fun InputTrack.viewportCssWidth(): Double {
    val m = query("Page.getLayoutMetrics")
    fun width(k: String) = (m[k] as? JsonObject)?.get("clientWidth")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
    return width("cssVisualViewport") ?: width("cssLayoutViewport") ?: throw CdpException(0, "no viewport size", "Page.getLayoutMetrics")
}

/** Move over (follow a track according to the rhythm, stop for a while), press, hold for a while, and lift; what you see on the page is a trusted mouse click (pointerType mouse). */
suspend fun InputTrack.mouseClick(x: Double, y: Double) {
    approach(x, y)
    pause(pacing.dwell())
    mouse("mousePressed", x, y, "left", 1, 1)
    pause(pacing.press())
    mouse("mouseReleased", x, y, "left", 0, 1)
}

/** Just move the mouse (rhythmically along a trajectory): trigger mouseover/mouseenter and CSS:hover. */
suspend fun InputTrack.mouseMove(x: Double, y: Double) = approach(x, y)

/** The pointer moves from the last position (I don't know, it's from somewhere nearby) to (x, y), and the last shot falls exactly on the target. */
private suspend fun InputTrack.approach(x: Double, y: Double) {
    val from = session.pointer ?: pacing.origin(x, y)
    val points = pacing.path(from, x to y)
    points.forEachIndexed { i, (px, py) ->
        if (i > 0) pause(pacing.moveStep())
        mouse("mouseMoved", px, py, "none", 0, 0)
    }
}

/**
 * Move the pointer to (x, y) and then turn the scroll wheel to scroll [distance] CSS pixels one grid at
 * a time (positive means downward), pausing between grids. Only used for the label being displayed (see
 * beginning of file).
 */
suspend fun InputTrack.mouseWheel(x: Double, y: Double, distance: Double) {
    approach(x, y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(x, y, d)
    }
}

/**
 * The pointer does not move. Turn the scroll wheel at its current position (I don’t know where it is
 * ([x], [y])), and scroll [distance] CSS pixels one by one (positive means downward). When staying, just
 * scroll and use: no mouse movement, no hovering. Only used for the label being displayed (see beginning
 * of file).
 */
suspend fun InputTrack.wheelInPlace(x: Double, y: Double, distance: Double) {
    val (px, py) = session.pointer ?: (x to y)
    pacing.notches(distance).forEachIndexed { i, d ->
        if (i > 0) pause(pacing.wheelGap())
        wheel(px, py, d)
    }
}

/**
 * Hold down the left button and drag from the starting point to the end point in [steps] steps. Drags
 * (slider, drag sort) written according to pointer events receive the movement directly; when the page
 * uses HTML native draggable (draggable), first let the browser hand over the drag to us
 * (setInterceptDrags), and then add dragEnter/dragOver/drop at the end point after getting the drag
 * data. Returns whether native drag and drop is gone.
 */
suspend fun InputTrack.mouseDrag(fromX: Double, fromY: Double, toX: Double, toY: Double, steps: Int, stepDelayMs: Long = 16): Boolean = coroutineScope {
    val intercept = runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", true) }) }.isSuccess
    val intercepted = if (intercept) async(start = CoroutineStart.UNDISPATCHED) { session.events.first { it.method == "Input.dragIntercepted" } } else null
    try {
        approach(fromX, fromY)
        pause(pacing.dwell())
        mouse("mousePressed", fromX, fromY, "left", 1, 1)
        pause(pacing.press())
        val n = steps.coerceAtLeast(1)
        for (i in 1..n) {
            delay(stepDelayMs)
            if (intercepted?.isCompleted == true) break
            val f = i.toDouble() / n
            mouse("mouseMoved", fromX + (toX - fromX) * f, fromY + (toY - fromY) * f, "left", 1, 0)
        }
        val data = intercepted?.takeIf { it.isCompleted }?.await()?.params?.get("data") as? JsonObject
        if (data != null) {
            for (type in listOf("dragEnter", "dragOver", "drop")) drag(type, toX, toY, data)
        } else {
            mouse("mouseReleased", toX, toY, "left", 0, 1)
        }
        data != null
    } finally {
        intercepted?.cancel()
        if (intercept) runCatching { query("Input.setInterceptDrags", buildJsonObject { put("enabled", false) }) }
    }
}

/** Press the key combination once: press the modifier keys in sequence, press the main key to lift, and lift the modifier keys in reverse order. Use keyDown (with text) for the key to produce words, and rawKeyDown for the rest. */
suspend fun InputTrack.pressCombo(combo: KeyCombo) {
    var held = 0
    for (m in combo.modifiers) {
        held = held or m.bit
        key("rawKeyDown", m.key, held, null)
        pause(pacing.modifierGap())
    }
    val text = combo.text
    val k = if (text != null && text != combo.key.text) combo.key.copy(key = text) else combo.key
    key(if (text != null) "keyDown" else "rawKeyDown", k, held, text)
    pause(pacing.keyHold())
    key("keyUp", k, held, null)
    for (m in combo.modifiers.asReversed()) {
        pause(pacing.modifierGap())
        held = held and m.bit.inv()
        key("keyUp", m.key, held, null)
    }
}

/**
 * Typing: When there is a rhythm, letters, numbers, and spaces are typed one by one (capital letters
 * with Shift), and the key intervals are of different lengths; the rest of the words (punctuation,
 * Chinese, line breaks) are handed over to the focus paragraph by paragraph, just like input method
 * submission. If there is no rhythm, or the text is longer than [InputPacing.Limits.maxKeyedChars], the
 * entire paragraph is given to the focus (like pasting). You won't press Enter because of a line break
 * in the text.
 */
suspend fun InputTrack.typeText(text: String) {
    if (!pacing.paced || text.length > pacing.limits.maxKeyedChars) return insertText(text)
    var first = true
    for (run in TypingRuns.split(text)) {
        if (!first) pause(pacing.keyGap())
        first = false
        if (!run.keyed) {
            insertText(run.text)
            continue
        }
        run.text.forEachIndexed { i, c ->
            if (i > 0) pause(pacing.keyGap())
            val k = CdpKeys.named(c.toString())!!
            if (c in 'A'..'Z') pressCombo(KeyCombo(listOf(KeyModifier.Shift), k))
            else {
                key("keyDown", k, 0, k.text)
                pause(pacing.keyHold())
                key("keyUp", k, 0, null)
            }
        }
    }
}

/** How to segment [keyed] into sections when typing: type one section of [keyed] key by key, and submit the rest as a whole. */
object TypingRuns {
    data class Run(val text: String, val keyed: Boolean)

    fun keyable(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' '

    fun split(text: String): List<Run> {
        val out = ArrayList<Run>()
        val cur = StringBuilder()
        var keyed: Boolean? = null
        for (c in text) {
            val k = keyable(c)
            if (keyed != null && k != keyed) {
                out += Run(cur.toString(), keyed)
                cur.clear()
            }
            keyed = k
            cur.append(c)
        }
        if (keyed != null) out += Run(cur.toString(), keyed)
        return out
    }
}

/** Give the entire text to the current focus (trigger the input event the same as input method submission). */
suspend fun InputTrack.insertText(text: String) {
    effect("Input.insertText", buildJsonObject { put("text", text) })
}

// ---------- Native drop-down box ----------

data class SelectOption(val text: String, val value: String, val disabled: Boolean)

sealed interface SelectOutcome {
    /** [byScript]: The keyboard is not selected, the value is set by the script and input/change is reissued (not a trusted event). */
    data class Selected(val index: Int, val text: String, val changed: Boolean, val byScript: Boolean) : SelectOutcome
    data object NotASelect : SelectOutcome
    data object Disabled : SelectOutcome
    data class NoSuchOption(val available: List<String>) : SelectOutcome
    data class OptionDisabled(val text: String) : SelectOutcome
    /** Neither the keyboard nor the script made it select this item. */
    data class NotApplied(val text: String) : SelectOutcome
}

object SelectMatch {
    /** First, press the value to be completely equal, then press the display text (remove blanks) to be equal, and finally, it is case-insensitive; neither is null. */
    fun indexOf(options: List<SelectOption>, wanted: String): Int? {
        val w = wanted.trim()
        options.indexOfFirst { it.value == wanted }.takeIf { it >= 0 }?.let { return it }
        options.indexOfFirst { it.text.trim() == w }.takeIf { it >= 0 }?.let { return it }
        return options.indexOfFirst { it.text.trim().equals(w, ignoreCase = true) || it.value.equals(w, ignoreCase = true) }.takeIf { it >= 0 }
    }
}

private const val SELECT_OF = "function(){var n=this;while(n&&!(n.tagName&&n.tagName.toUpperCase()==='SELECT')){" +
    "n=n.parentElement||(n.parentNode&&n.parentNode.host)||null;}return n;}"
private const val SELECT_INFO = "function(){return {disabled:!!this.disabled,multiple:!!this.multiple,index:this.selectedIndex," +
    "options:Array.prototype.slice.call(this.options,0,500).map(function(o){return {text:String(o.label||o.text||''),value:String(o.value),disabled:!!o.disabled};})};}"
private const val SELECT_INDEX = "function(i){return this.multiple?(this.options[i]&&this.options[i].selected?i:-1):this.selectedIndex;}"
private const val SELECT_SET = "function(i){if(this.multiple){this.options[i].selected=true;}else{" +
    "Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'selectedIndex').set.call(this,i);}" +
    "this.dispatchEvent(new Event('input',{bubbles:true}));this.dispatchEvent(new Event('change',{bubbles:true}));" +
    "return this.multiple?(this.options[i].selected?i:-1):this.selectedIndex;}"

private fun callParams(objectId: String, fn: String, args: List<Int>, byValue: Boolean) = buildJsonObject {
    put("objectId", objectId); put("functionDeclaration", fn); put("returnByValue", byValue)
    if (args.isNotEmpty()) put("arguments", buildJsonArray { args.forEach { add(buildJsonObject { put("value", it) }) } })
}

private fun callResult(r: JsonObject): JsonObject {
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/**
 * Select an item in the native drop-down box at (x, y). Method: Find the node according to the
 * coordinates (DOM.getNodeForLocation, penetrate shadow and iframe), go up to find select; after
 * focusing, use the direction keys to move to the target item one by one, and read back the current item
 * each time you press it. Arrow keys are trusted keyboard events. What the page frame sees is the real
 * input/change, and the system selection box will not pop up. Only when the arrow keys cannot be reached
 * (buttons and multi-select boxes are blocked by the page) can scripts be used to set values and
 * reissue input/change. Focus, key press, and script setting are all events that change the page.
 */
suspend fun InputTrack.selectOptionAt(x: Double, y: Double, wanted: String): SelectOutcome {
    runCatching { query("DOM.enable") }
    val loc = query(
        "DOM.getNodeForLocation",
        buildJsonObject { put("x", x.toInt()); put("y", y.toInt()); put("includeUserAgentShadowDOM", false); put("ignorePointerEventsNone", true) },
    )
    val backend = (loc["backendNodeId"] as? JsonPrimitive)?.intOrNull ?: return SelectOutcome.NotASelect
    val node = query("DOM.resolveNode", buildJsonObject { put("backendNodeId", backend) })["object"]?.jsonObject
    val nodeId = (node?.get("objectId") as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    val sel = callResult(query("Runtime.callFunctionOn", callParams(nodeId, SELECT_OF, emptyList(), byValue = false)))
    val selectId = (sel["objectId"] as? JsonPrimitive)?.contentOrNull ?: return SelectOutcome.NotASelect
    try {
        val info = callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INFO, emptyList(), byValue = true)))["value"] as? JsonObject
            ?: return SelectOutcome.NotASelect
        if ((info["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return SelectOutcome.Disabled
        val multiple = (info["multiple"] as? JsonPrimitive)?.booleanOrNull == true
        val options = (info["options"] as? JsonArray).orEmpty().mapNotNull { o ->
            val m = o as? JsonObject ?: return@mapNotNull null
            SelectOption(
                (m["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
                (m["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (m["disabled"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        val target = SelectMatch.indexOf(options, wanted) ?: return SelectOutcome.NoSuchOption(options.map { it.text })
        val want = options[target]
        if (want.disabled) return SelectOutcome.OptionDisabled(want.text)
        suspend fun current() =
            (callResult(query("Runtime.callFunctionOn", callParams(selectId, SELECT_INDEX, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull ?: -1
        val start = (info["index"] as? JsonPrimitive)?.intOrNull ?: -1
        if (!multiple && start == target || multiple && current() == target) return SelectOutcome.Selected(target, want.text, changed = false, byScript = false)
        if (!multiple) {
            effect("DOM.focus", buildJsonObject { put("objectId", selectId) })
            var at = start
            var presses = 0
            // Read back every time you press it; if you press it but do not move (the page blocks the
            // button), press it again. The total number of times does not exceed the number of options.
            while (at != target && presses <= options.size) {
                pressCombo(KeyCombo(emptyList(), CdpKeys.named(if (at < target) "ArrowDown" else "ArrowUp")!!))
                presses++
                val now = current()
                if (now == at) break
                at = now
            }
            if (at == target) return SelectOutcome.Selected(target, want.text, changed = true, byScript = false)
        }
        val set = (callResult(effect("Runtime.callFunctionOn", callParams(selectId, SELECT_SET, listOf(target), byValue = true)))["value"] as? JsonPrimitive)?.intOrNull
        return if (set == target) SelectOutcome.Selected(target, want.text, changed = true, byScript = true) else SelectOutcome.NotApplied(want.text)
    } finally {
        runCatching { query("Runtime.releaseObject", buildJsonObject { put("objectId", selectId) }) }
    }
}
