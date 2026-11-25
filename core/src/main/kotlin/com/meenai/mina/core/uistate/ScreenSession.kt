package com.meenai.mina.core.uistate

import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.visual.FrameDiff
import com.meenai.mina.core.visual.FrameGrid
import com.meenai.mina.core.visual.Recall
import com.meenai.mina.core.visual.Shots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import com.meenai.mina.core.vision.LocateRegistry
import com.meenai.mina.core.vision.Locator
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.core.observation.InputHints
import com.meenai.mina.core.observation.Look
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.Trust
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.HINT_PROTECTED
import com.meenai.mina.core.tool.MAX_READ_CHARS
import com.meenai.mina.core.tool.NOTE_CHANGED_DURING_READ
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.okObj
import com.meenai.mina.core.net.UrlHost

internal const val MAX_ELEMENTS = 200
/** The maximum number of differences can be picked up after a full screen. */
private const val MAX_CHAIN_DIFFS = 5
/** The upper limit of the stability of the interface after writing operations and before reading the screen. */
private const val INLINE_SETTLE_MS = 1500L
/** OCR is a supplement to screen reading and cannot block the next model call indefinitely. */
private const val OCR_WAIT_MS = 2000L
/** When the tree is not enough, the text on the screen is the main material: wait a while (the port needs to wait for the screen to stop before recognizing it). */
private const val OCR_WAIT_POOR_MS = 5000L
/** For irreversible determination, the element must be found according to the screen reading to which ref belongs, leaving the most recent ones. */
private const val RECENT_SNAPSHOTS = 8
/** Remember how many snapshotIds have been issued. */
private const val ISSUED_MEMORY = 256
/** In the result, the sentence "The limit has been reached" can only be kept this long at most. */
private const val LIMIT_TEXT_MAX = 120

/** find_visually finds a point: what you are looking for, the original text given, the words seen by the visual model; irreversible determination must be looked at. [unverified]: Just by looking at pictures, without supporting evidence. */
internal class Located(val target: String, val text: String?, val seen: String?, val unverified: Boolean = false)

/**
 * The screen reading status of a screen: stable number, the screen and difference chain recently
 * obtained by the model, the last few screen readings, and the points found by looking at the picture.
 * One copy for each screen, and that set of screen tools only uses its own copy; the screen number is
 * shared with several screens for review ([shots]).
 */
internal class ScreenSession(
    val surface: Surface,
    stableRefs: Boolean,
    private val visionEnabled: Boolean,
    private val recordFrames: Boolean,
    /** The interface text that was last read (shared by several screens); if it is the same as it is read after the action, it will not be remembered again. */
    private val lastRead: () -> String?,
    val shots: Shots,
    vision: VisionService? = null,
    private val adaptiveVision: Boolean = false,
) {
    /** Mobile model results are not truncated by the number of elements; the web page still uses its own output budget. */
    private val modelElementsLimit get() = if (surface.id == "phone") Int.MAX_VALUE else MAX_ELEMENTS
    val screen: ScreenPort get() = surface.screen
    private var textOnlyDepth = 0

    /** The composite phone command only leaves text and status inside, and the model will request the picture when needed. */
    suspend fun <T> textOnly(block: suspend () -> T): T {
        textOnlyDepth++
        try { return block() } finally { textOnlyDepth-- }
    }

    val stabilizer = if (stableRefs) RefStabilizer(surface.family.snapshotPrefix) else null
    /** The last screen obtained by the model (observe or screen in the write operation result), the base of the difference. */
    private var shown: ModelSnapshot? = null
    /** The snapshotId of the most recent full screen and subsequent differences: The difference requires the entire chain to be understood. If one is missing, the full screen will be sent. */
    private val chain = mutableListOf<String>()
    /** The model can still see the screen reading snapshotId at this moment; null means that the loop has not been told yet (all can be seen). */
    var visible: Set<String>? = null
    var lastSnapshot: ScreenSnapshot? = null
    val recentSnapshots = ArrayDeque<ScreenSnapshot>()
    val readAt = mutableMapOf<String, Long>()
    /** The snapshotId sent by this screen (longer than [RECENT_SNAPSHOTS]: the model may use a very early number). */
    private val issued = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > ISSUED_MEMORY
    }

    /** find_visually found point (@vN) on this screen; cleared after any writing operation on this screen. */
    val located = LocateRegistry()
    val locatedInfo = mutableMapOf<String, Located>()
    /** I read it after the last writing operation. describe: The coordinate clicks during this period are treated as points obtained from the picture. */
    var described = false
    val locator = vision?.let { Locator(screen, it, located, surface.family) }

    fun clearLocated() { located.clear(); locatedInfo.clear(); described = false }

    private var accessRevision = screen.appAccess.current().revision

    suspend fun checkAppAccess(current: Boolean = true) {
        val state = screen.appAccess.current()
        if (state.revision != accessRevision) {
            clearLocated()
            clearPrimed()
            lastSnapshot = null
            recentSnapshots.clear()
            readAt.clear()
            shown = null
            chain.clear()
            stale = true
            accessRevision = state.revision
        }
        if (current) screen.checkAppAccess()
    }

    /** This snapshotId (read the screen, or find the point by looking at the picture) is sent by this screen. */
    fun owns(snapshotId: String): Boolean = snapshotId in issued || located.has(snapshotId)

    /** This screen has been read several times (reading of the registration number); when judging the effect, it will be known whether it has been read after the action. */
    var reads = 0
        private set

    /**
     * The most recent screen in the model's hand is out of date: the interface has changed after the
     * action, and the screen after the action is not brought back in the results. It remains true until
     * the next screen read.
     */
    var stale = false
        private set

    /** Mina moved the screen herself (scrolled a few times while staying there): the position of the screen in the model's hand was no longer accurate and was marked as outdated. */
    fun movedByMina() { stale = true }

    /**
     * Read the screen and register a snapshot ID. [internal]: not shown to the model (used when locating
     * targets by description), leaving staleness of the model's current screen unchanged; this raw port
     * screen is kept as the "before action" frame ([probe] reuses it instead of re-reading).
     */
    suspend fun snapshot(internal: Boolean = false): ScreenSnapshot = screen.snapshot().let { p ->
        (stabilizer?.next(p)?.screen ?: p).also { primed = if (internal) it.snapshotId to p else null }
    }.also {
        reads++
        if (!internal) stale = false
        lastSnapshot = it
        recentSnapshots.addLast(it)
        readAt[it.snapshotId] = System.currentTimeMillis()
        issued[it.snapshotId] = Unit
        while (recentSnapshots.size > RECENT_SNAPSHOTS) recentSnapshots.removeFirst().snapshotId.let { id -> readAt.remove(id) }
    }

    /** At this moment, re-read the screen without registering the number or changing the screen reading in the model's hand; if it cannot be read, it will be null. */
    suspend fun peek(): ScreenSnapshot? = screenAttempt { screen.peek() }

    suspend fun observe(withImage: Boolean, adaptiveImage: Boolean = false, recordFrame: Boolean = true): ToolExecution {
        val s = snapshot()
        val (tree, ocr, ocrStatus) = readTree(s)
        val imageReason = if (adaptiveImage && tree.poor) tree.reason else null
        val sendImage = withImage || imageReason != null
        stabilizer?.latest?.let { shown = it; chain.clear(); chain += it.snapshotId }
        val seen = interfaceRead(s, ocr.orEmpty())
        val (elements, hidden) = ScreenJson.elements(s, modelElementsLimit)
        val shotId = shots.next()
        val content = okObj {
            val w = s.web
            if (w == null) put("currentApp", s.packageName ?: s.unreadableApp)
            else { put("currentSite", UrlHost.of(w.url)); put("url", w.url); put("title", w.title); put("tabId", w.tabId) }
            w?.dialog?.let { put("dialog", dialogJson(it)) }
            put("snapshotId", s.snapshotId)
            put("screenshotId", shotId)
            imageReason?.let { put("imageReason", it) }
            quality(tree)?.let { put("treeQuality", it) }
            ocrStatus?.let { put("ocr", it) }
            put("screenChangedDuringRead", s.changedDuringRead)
            if (s.changedDuringRead) put("note", NOTE_CHANGED_DURING_READ)
            s.opened?.let { put("opened", openedJson(it)) }
            if (s.protected) { put("protected", true); put("hint", HINT_PROTECTED) }
            else if (unreadable(s) != null && ocr.isNullOrEmpty()) { put("readable", false); put("hint", unreadable(s)) }
            else if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) put("hint", Feedback[surface.family.emptyHint])
            else treeHint(tree, ocrStatus)?.let { put("hint", it) }
            put("viewport", buildJsonObject { put("width", s.width); put("height", s.height) })
            put("elements", elements)
            if (surface.id == "phone" && s.windows.isNotEmpty()) put("tree", UiTreeJson.windows(s))
            ocr?.let { put("visibleText", VisibleText.json(it)) }
            if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { put("screenMap", it) }
            val omitted = s.web?.omitted ?: 0
            put("truncated", hidden > 0 || omitted > 0)
            if (omitted > 0) put("omitted", hidden + omitted)
        }
        // The anti-screenshot page captures a black picture, which will only mislead the model.
        val png = if ((sendImage && textOnlyDepth == 0 || (recordFrames && recordFrame && textOnlyDepth == 0)) && !s.protected) screen.screenshotPng() else null
        // Only the main model can view the pictures. The payment page and the ordinary page are
        // processed according to the same rules.
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, observationText(s, ocr.orEmpty())))
        val image = png.takeIf { sendImage }
        return ToolExecution(content, image = image, frame = png, imageIds = listOfNotNull(image?.let { shotId }), observations = listOf(seen))
    }

    /**
     * The main model can view a frame of the screen at the moment of the picture (the original picture)
     * and its screenshotId, and register it for review. The same rules as screen reading: no screenshots
     * are taken on protected pages; if there is no app on the screen and no screenshots are available,
     * it is null.
     */
    suspend fun currentFrame(): Pair<ByteArray, Int>? {
        if (!visionEnabled) return null
        val s = screenAttempt { screen.peek() } ?: return null
        if (s.protected) return null
        if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) return null
        val png = screenAttempt { if (screen.isProtected()) null else screen.screenshotPng() } ?: return null
        val shotId = shots.next()
        shots.remember(Recall(shotId, shots.step, s.packageName, png, screenText(s)))
        return png to shotId
    }

    /**
     * The main model can display a screen after the action when viewing the picture: the picture and the
     * element table must be at the same time. The model operates according to the ref/snapshotId in the
     * element table, and the picture is only used for understanding. The same as observe: no screen will
     * be posted on a protected page; it will be null when there is no app on the screen.
     */
    suspend fun currentView(): ToolDispatcher.Glance? {
        if (!visionEnabled && !adaptiveVision) return null
        val peeked = screenAttempt { screen.peek() } ?: return null
        if (peeked.packageName == null && peeked.elements.isEmpty() && !peeked.hasWindow) return null
        if (textOnlyDepth > 0) {
            val obs = screenAttempt { observe(withImage = false, recordFrame = false) } ?: return null
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), null, shotId, obs.observations)
        }
        if (adaptiveVision) {
            val obs = screenAttempt { observe(withImage = false, adaptiveImage = visionEnabled) } ?: return null
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), obs.image, shotId, obs.observations)
        }
        // First read the element table (register snapshotId), and then press this screen to determine
        // whether it can be screenshot: protected pages will not be screenshot
        val obs = screenAttempt { observe(withImage = false) } ?: return null
        val s = lastSnapshot ?: return null
        val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val png = if (s.protected) null else screenAttempt { obs.frame ?: if (screen.isProtected()) null else screen.screenshotPng() }
        // Looking back, I will add this picture
        val recalls = shots.recalls
        if (png != null) recalls.indexOfLast { it.id == shotId }.takeIf { it >= 0 }?.let { i ->
            val r = recalls[i]; recalls[i] = Recall(r.id, r.step, r.app, png, r.elementsText)
        }
        return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), png, shotId, obs.observations)
    }

    /** After the action is successful, the current screen is attached (the port has waited for the interface change during the action): the element table is put into the screen field, the screen is attached, and its screenshotId is written in the result. */
    suspend fun withFrame(r: ToolExecution): ToolExecution {
        val v = currentView() ?: return r
        return r.with(content = JsonObject(r.content + ("screen" to v.content) + ("screenshotId" to JsonPrimitive(v.imageId))), images = listOfNotNull(v.image),
            screenElements = v.content["elements"] as? JsonArray ?: r.screenElements, imageIds = if (v.image != null) listOf(v.imageId) else emptyList(),
            observations = r.observations + v.observations)
    }

    /**
     * After the write operation is successful: wait for the interface to be stable (upper limit
     * [INLINE_SETTLE_MS]) and then read another screen. Compared with the last screen obtained by the
     * model, the difference is put into the screen field. Full volume is sent when base or the most
     * recent full volume screen model is no longer visible. If the screen cannot be read, the action
     * result will be returned as is.
     */
    suspend fun withScreen(r: ToolExecution): ToolExecution {
        val cur = try {
            screen.waitStable(INLINE_SETTLE_MS)
            snapshot()
            stabilizer!!.latest!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowAppAccess()

            return r
        }
        val v = visible
        // Send the full amount once if the maximum number of consecutive differences is MAX_CHAIN_DIFFS:
        // the longer the chain, the harder it is to read, and the more round trips need to be retained
        // during compression.
        val seen = chain.isNotEmpty() && chain.size - 1 < MAX_CHAIN_DIFFS && (v == null || chain.all { it in v })
        val s = cur.screen
        val (tree, ocr, ocrStatus) = readTree(s)
        val shotId = shots.next()
        val diff = if (s.protected) {
            // The screen and elements cannot be seen on the protected page; start over from the full
            // page next time
            shown = null; chain.clear()
            buildJsonObject {
                put("snapshotId", cur.snapshotId); put("currentApp", s.packageName); put("full", false)
                put("protected", true); put("hint", HINT_PROTECTED)
                put("screenshotId", shotId)
            }
        } else {
            val d = InterfaceUpdate.between(shown, cur, seen, modelElementsLimit)
            shown = cur
            if (d["full"] == JsonPrimitive(true)) chain.clear()
            chain += cur.snapshotId
            JsonObject(d + ("screenshotId" to JsonPrimitive(shotId)) +
                (ocr?.let { mapOf("visibleText" to VisibleText.json(it)) } ?: emptyMap()) +
                (if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { mapOf("screenMap" to it) } ?: emptyMap() else emptyMap()) +
                (if (s.changedDuringRead) mapOf("note" to JsonPrimitive(NOTE_CHANGED_DURING_READ)) else emptyMap()) +
                (s.opened?.let { mapOf("opened" to openedJson(it)) } ?: emptyMap()) +
                (s.web?.dialog?.let { mapOf("dialog" to dialogJson(it)) } ?: emptyMap()) +
                (quality(tree)?.let { mapOf("treeQuality" to JsonPrimitive(it)) } ?: emptyMap()) +
                (ocrStatus?.let { mapOf("ocr" to JsonPrimitive(it)) } ?: emptyMap()) +
                ((unreadable(s)?.takeIf { ocr.isNullOrEmpty() }?.let { mapOf("readable" to JsonPrimitive(false), "hint" to JsonPrimitive(it)) })
                    ?: treeHint(tree, ocrStatus)?.let { mapOf("hint" to JsonPrimitive(it)) } ?: emptyMap()))
        }
        val text = observationText(s, ocr.orEmpty())
        // If it is the same as what you read last time, you won’t memorize it again, so as not to
        // squeeze out the earlier screen reading.
        val read = if (lastRead() != text) interfaceRead(s, ocr.orEmpty()) else null
        // The same rules as observe: to prevent screenshots, the page will not be taken; only the main
        // model can view the pictures, and then the pictures will be left for review.
        val imageReason = if (adaptiveVision && visionEnabled && tree.poor) tree.reason else null
        val sendImage = visionEnabled && (!adaptiveVision || imageReason != null)
        val png = if ((recordFrames || sendImage) && textOnlyDepth == 0 && !s.protected) {
            screenAttempt { screen.screenshotPng() }
        } else null
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, text))
        // The old mode always attached images; the graded mode only attached images when elements were
        // obviously insufficient.
        val attach = kept?.takeIf { sendImage && r.images.isEmpty() }
        val shownDiff = if (imageReason == null) diff else JsonObject(diff + ("imageReason" to JsonPrimitive(imageReason)))
        return r.with(content = JsonObject(r.content + ("screen" to onSurface(shownDiff))),
            frame = r.frame ?: png?.takeIf { recordFrames }, images = attach?.let { listOf(it) } ?: r.images,
            screenElements = ScreenJson.elements(s, modelElementsLimit).first, imageIds = if (attach != null) listOf(shotId) else r.imageIds).observing(read)
    }

    /** The interface state before the action (no registration number) and one frame of picture are used to judge the effect; if it cannot be read, it is null. */
    class EffectProbe internal constructor(before: ScreenSnapshot?, frame: FrameGrid?) {
        var before: ScreenSnapshot? = before
            internal set
        var frame: FrameGrid? = frame
            internal set
    }

    /** The glance before this action is retained before the action is judged and the effect is judged: before the action starts, Mina will re-read it by [reprobe] when she moves the interface again. */
    private var pendingProbe: EffectProbe? = null

    /** The effect of this action has been judged (or not judged). */
    fun probeDone() { pendingProbe = null }

    /**
     * Before the action started, Mina had moved the interface by herself (scrolling the page slightly
     * while staying on the web page): taking another look at it as it was before the action, so as not
     * to count this scrolling as the effect of the action; the position of the screen in the model's
     * hand was also inaccurate and marked as outdated. Do nothing while waiting for an action to take
     * effect.
     */
    suspend fun reprobe() {
        val p = pendingProbe ?: return
        stale = true
        withTimeoutOrNull(EffectTuning.PROBE_MS) { p.before = peek(); p.frame = grid() }
    }

    /** The screen just read to find the target according to the description: the snapshotId and port of the model are as they are; valid until they are taken away or invalidated by [probe]. */
    private var primed: Pair<String, ScreenSnapshot>? = null

    fun clearPrimed() { primed = null }

    /**
     * Taking a look before taking action will cost you at most [EffectTuning.PROBE_MS]; the part that is
     * too late is null (then it will only be judged based on the fact of the port). The action uses the
     * exact screen you just read according to the description ([snapshotId] matches). Just use it and
     * don't read it again.
     */
    suspend fun probe(snapshotId: String? = null): EffectProbe {
        val ready = primed?.takeIf { it.first == snapshotId }?.second
        primed = null
        if (ready != null) {
            var frame: FrameGrid? = null
            withTimeoutOrNull(EffectTuning.PROBE_MS) { frame = grid() }
            return EffectProbe(ready, frame).also { pendingProbe = it }
        }
        var before: ScreenSnapshot? = null
        var frame: FrameGrid? = null
        withTimeoutOrNull(EffectTuning.PROBE_MS) { before = peek(); frame = grid() }
        return EffectProbe(before, frame).also { pendingProbe = it }
    }

    private suspend fun grid(): FrameGrid? = screenAttempt { screen.frameGrid() }

    /**
     * After the action is successful, determine its actual effect on the interface state (see
     * [ActionEffect]), write it into the effect field of the result, and give a prompt according to the
     * effect; the changed result follows the effect. If it has been read after the action (and read
     * again after [readsAtAction], that is, the interface is included in the result), use that copy.
     * Otherwise: the port has already waited for the interface in the action (the report has changed or
     * the page is silent), just take a look at it, and wait for the interface to stabilize without
     * waiting (upper limit [EffectTuning.SETTLE_MS]). If it seems the same, wait for
     * [EffectTuning.RECHECK_MS] and watch it again (asynchronous update). The entire judgment will cost
     * at most [EffectTuning.BUDGET_MS], and when it comes time to draw conclusions based on the facts of
     * the port. Read-only interface, take the picture, do not perform any action. When the interface
     * changes and there is no new interface in the results, the copy in the model's hand is marked as
     * outdated ([stale]).
     */
    suspend fun withEffect(
        r: ToolExecution, probe: EffectProbe, verb: Verb, readsAtAction: Int, target: UiElement?, region: List<Int>?, typed: String?,
    ): ToolExecution {
        val c = r.content
        val opened = c["opened"] as? JsonObject
        val port = PortFacts(
            changed = r.changed,
            switchedTo = (c["app_switched"] as? JsonPrimitive)?.content,
            verified = c["verification"] == JsonPrimitive("match"),
            loading = c["settled"] == JsonPrimitive(false),
            onUserScreen = opened?.takeIf { it["onUserScreen"] == JsonPrimitive(true) }?.let { (it["package"] as? JsonPrimitive)?.content },
        )
        val portWaited = r.changed != null || "settled" in c
        val fresh = reads > readsAtAction
        fun judge(after: ScreenSnapshot?, frame: FrameGrid?) = ActionEffect.classify(
            verb, probe.before, after, port, FrameDiff.compare(probe.frame, frame, region), target, typed,
            pressedEnter = c["pressedEnter"] == JsonPrimitive(true), secret = c["secret"] == JsonPrimitive(true),
        )
        var effect: ActionEffect? = judge(null, null)
        // A new statement "The limit has been reached" appears in the interface you see after the action
        // (if you see it twice, it will count if you see it once: the prompt will disappear after a
        // while)
        var limit: String? = null
        withTimeoutOrNull(EffectTuning.BUDGET_MS) {
            if (!fresh && !portWaited) settle(EffectTuning.SETTLE_MS)
            val after = if (fresh) lastSnapshot else peek()
            limit = LimitNotice.find(probe.before, after)
            effect = judge(after, grid())
            if (effect?.kind == EffectKind.NoEffect) {
                // The interface may be updated asynchronously: wait a while and watch it again. If it
                // still hasn’t changed, it’s considered ineffective.
                delay(EffectTuning.RECHECK_MS)
                val again = peek()
                limit = limit ?: LimitNotice.find(probe.before, again)
                effect = judge(again, grid())
            }
        }
        // The point where the tree cannot be read (only judged on the mobile phone: there is no question
        // of whether the web page is enough for the tree): not being able to see the change does not
        // mean that it is not clicked.
        val e = effect?.let { judged ->
            if (surface.id != "phone") return@let judged
            val quality = lastTree?.quality ?: probe.before?.let { TreeAdequacy.ofTree(it).quality }
            if (judged.kind == EffectKind.NoEffect && ActionEffect.treeBlind(verb, quality, probe.before, target, region)) judged.copy(treeBlind = true) else judged
        } ?: return r
        if ("screen" !in c && e.kind != EffectKind.NoEffect) stale = true
        val changed = e.kind != EffectKind.NoEffect
        val hint = listOfNotNull(limit?.let { Feedback.format("effect.limit_reached", "text" to it.take(LIMIT_TEXT_MAX)) },
            EffectFeedback.hint(e, verb, portSwitched = "app_switched" in c || opened != null),
            Feedback["effect.stale"].takeIf { stale && e.kind != EffectKind.SmallChange }, (c["hint"] as? JsonPrimitive)?.content)
        val content = JsonObject(c - "hint" + ("effect" to e.toJson()) +
            (if ("changed" in c) mapOf("changed" to JsonPrimitive(changed)) else emptyMap()) +
            (if (verb == Verb.Scroll && e.kind == EffectKind.NoEffect) mapOf("atEnd" to JsonPrimitive(true)) else emptyMap()) +
            (if (stale) mapOf("interfaceStale" to JsonPrimitive(true)) else emptyMap()) +
            (limit?.let { mapOf("limitReached" to JsonPrimitive(it.take(LIMIT_TEXT_MAX))) } ?: emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap()))
        return r.with(content = content, changed = changed)
    }

    /** The most recent screen reading judged whether the trees were enough; when judging the effect, it was said that "no changes can be seen" or "no clicks". */
    private var lastTree: TreeCheck? = null

    /**
     * The tree on this screen is missing a piece or cannot be used (only judged on the mobile phone, the
     * same caliber as the treeQuality in the screen reading results): If you cannot judge accurately by
     * just looking at the tree, rely on the judgment of the most recent screen reading (reading the text
     * on the screen). If the search input box by description cannot be found in the tree, look at the
     * picture accordingly.
     */
    fun treeBlind(s: ScreenSnapshot): Boolean = surface.id == "phone" &&
        (TreeAdequacy.ofTree(s).quality ?: lastTree?.quality).let { it == TreeQuality.Partial || it == TreeQuality.Unusable }

    private suspend fun settle(ms: Long) {
        screenAttempt { screen.waitStable(ms) }
    }

    /** The App that was just moved back from the user's home screen before reading the screen: the same field as opened in the action result, with the additional description. */
    private fun openedJson(o: OpenedElsewhere): JsonObject = JsonObject(o.toJson() + ("note" to JsonPrimitive(openedHint(o))))

    /**
     * The description given to the model when the foreground App is open but the window cannot be read
     * (not "not open", do not start it again); it is null when it can be read or the person in the
     * foreground is not known.
     */
    private fun unreadable(s: ScreenSnapshot): String? {
        val app = s.unreadableApp ?: return null
        if (s.elements.any { it.meaningful }) return null
        return Feedback.format("unreadable_window", "app" to app)
    }

    /** The interface status read this time: the text of the readable element, where it is, the title of the web page, and the words in the input box. */
    private fun interfaceRead(s: ScreenSnapshot, ocr: List<VisibleTextLine> = emptyList()): Observation {
        val f = inputFields(s)
        val place = s.web?.url?.let(Place::page) ?: s.packageName?.let(Place::app)
        return Observation(ObsKind.Read, observationText(s, ocr), source = surface.id, place = place, title = s.web?.title,
            trust = if (s.web != null) Trust.Untrusted else Trust.Trusted, inputs = InputHints(f.names, f.focused))
    }

    /**
     * Whether the tree on this screen is enough ([TreeAdequacy]), and the on-screen text that needs to
     * be added (OCR, only leave the ones that are not in the tree, and remove the lines that admit
     * mistakes). When the tree is obviously sufficient and protected, the words will not be read; when
     * it is judged that it is not accurate, the words will be read; when the tree is not enough, the
     * words must be read: the main model that cannot see the picture relies on it, and the main model
     * that can see the picture also uses its position to click. The evidence ledger (answer source,
     * verification) and phone_collect can only get what is written on the screen from here.
     */
    private suspend fun readTree(s: ScreenSnapshot): TreeRead {
        val tree = TreeAdequacy.ofTree(s)
        if (tree.quality == TreeQuality.Adequate || tree.quality == TreeQuality.Protected) return TreeRead(tree, null, null).also { lastTree = it.tree }
        val (ocr, status) = visibleText(s, if (tree.poor) OCR_WAIT_POOR_MS else OCR_WAIT_MS)
        val checked = TreeAdequacy.withText(tree, ocr)
        lastTree = checked
        // There are not enough trees, and there is not a single line of text on the screen: tell the
        // truth why, and the model will know to look at the picture instead of reading it again.
        return TreeRead(checked, ocr, status.takeIf { quality(checked) != null && ocr.isNullOrEmpty() })
    }

    /**
     * The reasons why there are no words in the tree on the screen, and not even a line is read: empty
     * (the characters were not recognized on the screen), illegible (the recognized words were all
     * recognized by mistake), timeout (the recognition was not completed within the time limit),
     * unavailable (the port cannot be given: there is no screen, the screen keeps changing), failed
     * (recognition error). It is null when not reading (webpage, protected, no window). Even if the tree
     * changes during the reading process, it will still be read: the position of the words comes from
     * the screen itself, the port itself will wait for the screen to stop, and it will be recognized
     * again if the entire page changes during the recognition process.
     */
    private suspend fun visibleText(s: ScreenSnapshot, waitMs: Long): Pair<List<VisibleTextLine>?, String?> {
        if (surface.id != "phone" || s.protected || !s.hasWindow) return null to null
        var timedOut = true
        val raw = try {
            withTimeoutOrNull(waitMs) { screen.readVisibleText().also { timedOut = false } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { e.rethrowAppAccess(); return null to "failed" }
        if (raw == null) return null to if (timedOut) "timeout" else "unavailable"
        if (raw.isEmpty()) return raw to "empty"
        val legible = VisibleText.legible(raw)
        if (legible.isEmpty()) return legible to "illegible"
        return VisibleText.missing(s, legible) to null
    }

    /** The treeQuality written into the screen reading result: only written on mobile phones and when the tree is not enough (the web page has no on-screen text to supplement). */
    private fun quality(tree: TreeCheck): String? = tree.quality?.takeIf { surface.id == "phone" && tree.poor }?.wire

    /** When the tree is not enough, explain to the model: where the text on the screen comes from and how to click it; when the model is equipped with a picture view, explain how to find the icon without words; when you can't even read the words, explain why and how to find it. */
    private fun treeHint(tree: TreeCheck, ocrStatus: String? = null): String? = when {
        quality(tree) == null -> null
        ocrStatus != null && locator != null -> Feedback.format("tree_poor.no_text.visual", "ocr" to ocrStatus)
        ocrStatus != null -> Feedback.format("tree_poor.no_text", "ocr" to ocrStatus)
        locator != null -> Feedback.format("tree_poor.visual", "tree_poor" to Feedback["tree_poor"])
        else -> Feedback["tree_poor"]
    }

    /**
     * Take another look before checking: read the screen again at this time (do not register the number,
     * the ref in the model's hand will not be affected), take another picture when [withImage] (the
     * anti-screenshot page will not be taken); when you read it, add the [extra] (the beginning of the
     * web page text: read the screen only elements in the viewport) provided by the ability. If it
     * cannot be read, it will still be returned (no observation).
     */
    suspend fun freshLook(withImage: Boolean, extra: (suspend () -> Observation?)? = null): Look {
        val s = screenAttempt { screen.snapshot() }
        val png = if (withImage && s != null && !s.protected) {
            screenAttempt { screen.screenshotPng() }
        } else null
        // When you don’t have enough trees, you can still read the text on the screen: What you need to
        // check is what is written on the screen.
        val seen = s?.let { interfaceRead(it, readTree(it).ocr.orEmpty()) }?.takeIf { it.text.isNotEmpty() }
        val more = if (s != null) extra?.invoke() else null
        return Look(listOfNotNull(seen, more), png, s?.protected == true)
    }

    /** "Where" is reported according to the meaning of this screen (the web page is the site): Just like observe, the "where" of the mobile phone and the web page are not mixed. */
    private fun onSurface(o: JsonObject): JsonObject {
        val key = surface.family.placeKey
        if (key == ScreenFamily.APP_PLACE || ScreenFamily.APP_PLACE !in o) return o
        return JsonObject(o.filterKeys { it != ScreenFamily.APP_PLACE } + (key to (o[ScreenFamily.APP_PLACE] ?: JsonNull)))
    }
}

/** The words in the input box on this screen (not the prompt, not the password box), and the one with the input focus; the evidence ledger will distinguish the words returned by the input box and filled in by the user when accepting. */
internal fun inputFields(s: ScreenSnapshot): InputFields {
    val fields = s.elements.filter { it.editable && !it.hint && !it.secret && it.name.isNotBlank() }
    return InputFields(fields.map { it.name.replace('\n', ' ') }, fields.firstOrNull { it.focused }?.name?.replace('\n', ' '))
}

internal class InputFields(val names: List<String>, val focused: String?)

/** The tree judgment of one screen reading, the added on-screen text, and the reason why the tree is not enough and there is no line of text (see ScreenSession.visibleText). */
internal data class TreeRead(val tree: TreeCheck, val ocr: List<VisibleTextLine>?, val ocrStatus: String?)

/** The element text for screen reading is one per line; the first line is the front-end App. If there is no text, it is an empty string. Don't remember the words in the password box. */
internal fun screenText(s: ScreenSnapshot): String {
    val names = s.elements.filter { it.meaningful && it.name.isNotBlank() && !it.secret }.map { it.name.replace('\n', ' ') }
    // The fact that the webpage title is not in the element is also a fact of this screen (it is given
    // by the page and is the same material to be checked as the element text); webpages without readable
    // elements are still retained.
    val title = s.web?.title?.takeIf { it.isNotBlank() }?.let { "网页标题:${it.replace('\n', ' ')}" }
    val dialog = s.web?.dialog?.let { "网页弹框(${it.kind}):${it.message.replace('\n', ' ')}" }
    if (names.isEmpty() && title == null && dialog == null) return ""
    val where = s.web?.let { "当前网页:${it.url}" } ?: "当前 App:${s.packageName ?: "未知"}"
    return (listOfNotNull(where, title, dialog) + names).joinToString("\n").take(MAX_READ_CHARS)
}

/** Accessible text remains the primary evidence; native OCR only appends screen text that is missing from the tree. */
private fun observationText(s: ScreenSnapshot, ocr: List<VisibleTextLine>): String {
    val tree = screenText(s)
    if (ocr.isEmpty()) return tree
    return listOf(tree, "屏幕可见文字(OCR):", ocr.joinToString("\n") { it.text }).filter(String::isNotEmpty)
        .joinToString("\n").take(MAX_READ_CHARS)
}

internal fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

/** Page pop-up box in the screen reading result: The model must first respond with the dialog of web_interact before the page can continue to run. */
internal fun dialogJson(d: PageDialog): JsonObject = buildJsonObject {
    put("kind", d.kind)
    put("message", d.message.take(PageDialog.MAX_MESSAGE))
    d.defaultText?.let { put("defaultText", it.take(PageDialog.MAX_MESSAGE)) }
    put("hint", Feedback["page_dialog"])
}
