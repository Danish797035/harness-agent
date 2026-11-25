package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.uistate.UiStates
import com.meenai.mina.core.uistate.normalize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Element fusion: The element table of the page script (the visible and uncovered columns) and the
 * DevTools accessibility tree are combined into one element list based on backendNodeId. Pure function.
 * - - Both sides have: name, role, disabled/focus and other status to get the accessibility tree,
 * position, visible, occlusion to get the page script. Ignored nodes in the accessibility tree are not
 * considered.
 * Input boxes and drop-down boxes with filled-in values in their names retain page scripts; file
 * controls and embedded page placeholder roles retain page scripts.
 * - - Only the accessibility tree has: only interactive characters are accepted. in a cross-origin
 * framework that cannot be read by page scripts (it only lists an "embedded page" placeholder),
 * The frame is cut into the visible range of the placeholder and inserted behind the placeholder; in the
 * document that can be read by the page script (such as a closed shadow root), hit detection must be
 * confirmed at the top before being closed.
 * - - Only the page script has: keep it as it is; the plain text/image with the click listener attached
 * to the element itself will be upgraded to clickable.
 * Order: Document order. When it exceeds [Input.budget], the ones with no information will be discarded
 * first, then the plain text, and finally the interactive ones. The number of discarded items will be
 * recorded in omitted. The numbers are rearranged in order as @e1...;[Result.pageRefs] is the new number
 * → the number in the page script number table (only the elements in the accessibility tree are not).
 */
object ElementFusion {
    const val DEFAULT_BUDGET = 400
    /** Only the accessibility tree has a maximum of so many elements to fill in (each requires one frame removal and one hit check). */
    const val MAX_CANDIDATES = 60
    /** Check listeners for up to so many page script elements. */
    const val MAX_LISTENER_PROBES = 40
    private const val NAME_CHARS = 80

    /**
     * A node in the accessibility tree. [id]/[parent] is the node number in the tree of this frame,
     * [order] is the depth-first order of the tree in this frame; [states] is the true
     * Boolean/three-state attribute name (disabled, focused, readonly, editable, checked...).
     */
    data class AxNode(
        val id: String,
        val parent: String?,
        val frameId: String,
        val order: Int,
        val backendId: Int?,
        val role: String,
        val name: String,
        val ignored: Boolean = false,
        val states: Set<String> = emptySet(),
    )

    /** A rectangle of top-level viewport CSS pixels. */
    data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    data class Frame(val id: String, val parent: String?)

    enum class Origin { PAGE, BOTH, AX }

    /**
     * [page] is the result (view pixel) of PageTree, [pageIds] is subscript-aligned with it; [owners] is
     * the backendNodeId of the frame element → the frameId of the document inside it. [boxes]/[onTop]
     * gives only candidate nodes (by backendNodeId) in the accessibility tree: box (replaced with
     * top-level viewport coordinates) and hit detection results. [listeners] is an element with a click
     * listener attached. [scale] is a multiple of CSS pixels → view pixels.
     */
    class Input(
        val page: List<UiElement>,
        val pageIds: List<Int?>,
        val owners: Map<Int, String> = emptyMap(),
        val frames: List<Frame> = emptyList(),
        val ax: List<AxNode> = emptyList(),
        val boxes: Map<Int, Box> = emptyMap(),
        val onTop: Map<Int, Boolean> = emptyMap(),
        val listeners: Set<Int> = emptySet(),
        val scale: Double = 1.0,
        val width: Int,
        val height: Int,
        val budget: Int = DEFAULT_BUDGET,
    )

    class Result(
        val elements: List<UiElement>,
        val pageRefs: Map<String, String>,
        val backendIds: Map<String, Int>,
        val origins: Map<String, Origin>,
        /** Only the placeholder element of the frame where the element in the accessibility tree is located (new number → backendNodeId of the placeholder); in the main document is empty. */
        val frameOwners: Map<String, Int>,
        val omitted: Int,
    )

    /** A candidate node: [placeholder] is the placeholder subscript of the cross-source framework in which it is located in the page script element table, and is null in the document read by the page script. */
    class Candidate(val node: AxNode, val placeholder: Int?, val ownerId: Int?)

    // ---------- Role ----------

    private val CLICK_ROLES = setOf(
        "link", "button", "checkbox", "radio", "combobox", "switch", "tab", "menuitem", "option", "slider",
        "menuitemcheckbox", "menuitemradio", "treeitem", "listbox",
    )
    private val EDIT_ROLES = setOf("textbox")
    /** These roles given by the page script do not allow accessibility changes: file is required for uploading, and frame is a placeholder for the cross-origin framework. */
    private val KEEP_PAGE_ROLES = setOf("file", "frame")
    private val NON_INTERACTIVE_ROLES = setOf("heading", "image")

    /** The role of the accessibility tree → is the same set of words as the page script; those without semantics (generic, StaticText...) are null. */
    fun role(ax: String): String? = when (ax) {
        "searchbox", "spinbutton" -> "textbox"
        "img" -> "image"
        "PopUpButton" -> "combobox"
        "DisclosureTriangle", "ToggleButton" -> "button"
        "Iframe", "IframePresentational" -> "frame"
        in CLICK_ROLES, in EDIT_ROLES, in NON_INTERACTIVE_ROLES -> ax
        else -> null
    }

    private fun interactive(role: String?) = role != null && (role in CLICK_ROLES || role in EDIT_ROLES)

    // ---------- Step 1: Candidates for frame filling ----------

    /** Only the nodes in the accessibility tree that are interactive, worth adding; the first ones in the cross-origin framework that cannot be read by page scripts. */
    fun candidates(input: Input): List<Candidate> {
        val matched = input.pageIds.filterNotNull().toSet()
        val byFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        val pageIndex = input.pageIds.withIndex().filter { it.value != null }.associate { it.value!! to it.index }
        val behind = placeholders(input)
        val out = mutableListOf<Candidate>()
        for (n in input.ax) {
            val id = n.backendId ?: continue
            if (n.ignored || id in matched || !interactive(role(n.role))) continue
            val ph = behind[n.frameId]
            if (ph == null) {
                // In the readable document, the outer layer contains interactive elements that the page
                // script has listed: the page script sees the outer layer but does not list it, and it
                // is probably covered or only part of the outer layer.
                val nodes = byFrame[n.frameId].orEmpty()
                var p = n.parent?.let(nodes::get)
                var covered = false
                while (p != null) {
                    val pi = p.backendId?.let(pageIndex::get)
                    if (pi != null && input.page[pi].let { it.clickable || it.editable }) { covered = true; break }
                    p = p.parent?.let(nodes::get)
                }
                if (covered) continue
            }
            out += Candidate(n, ph?.first, ph?.second)
        }
        return out.sortedBy { if (it.placeholder != null) 0 else 1 }.take(MAX_CANDIDATES)
    }

    /** frameId → (the subscript of the placeholder in the page script element table, the placeholder's backendNodeId): the placeholder frame itself and the frames inside it. */
    private fun placeholders(input: Input): Map<String, Pair<Int, Int>> {
        val direct = HashMap<String, Pair<Int, Int>>()
        input.pageIds.forEachIndexed { i, id ->
            if (id != null && input.page[i].role == "frame") input.owners[id]?.let { direct[it] = i to id }
        }
        if (direct.isEmpty()) return direct
        val parent = input.frames.associate { it.id to it.parent }
        val out = HashMap<String, Pair<Int, Int>>()
        for (f in input.frames.map { it.id } + direct.keys) {
            var cur: String? = f
            var hops = 0
            while (cur != null && hops++ < 32) {
                direct[cur]?.let { out[f] = it }
                if (out.containsKey(f)) break
                cur = parent[cur]
            }
        }
        return out
    }

    /** The backendNodeId corresponding to a node's descendants (including itself) in the accessibility tree of this frame: any hit detection that falls on these nodes will count as hitting it. */
    fun subtreeIds(ax: List<AxNode>, node: AxNode): Set<Int> {
        val children = ax.filter { it.frameId == node.frameId }.groupBy { it.parent }
        val out = HashSet<Int>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty() && out.size < 500) {
            val n = stack.removeLast()
            n.backendId?.let(out::add)
            children[n.id]?.let(stack::addAll)
        }
        return out
    }

    /** Visible range of candidate nodes (CSS pixels): The visible part of the cross-source frame is the placeholder, and the rest is the viewport. The hit detection point is the center of the intersection of the box and it. */
    fun clip(input: Input, c: Candidate): Box {
        val k = input.scale.takeIf { it > 0 } ?: 1.0
        val r = c.placeholder?.let { rectOf(input.page[it], input.width, input.height) }
            ?: Rect(0.0, 0.0, input.width.toDouble(), input.height.toDouble())
        return Box(r.l / k, r.t / k, r.r / k, r.b / k)
    }

    /** Check the page script element subscript of the listener: plain text and pictures that cannot be clicked, and the accessibility tree does not say that it is interactive. */
    fun listenerProbes(input: Input): List<Int> {
        val axById = axByBackend(input.ax)
        return input.page.indices.filter { i ->
            val e = input.page[i]
            val id = input.pageIds.getOrNull(i) ?: return@filter false
            !e.clickable && !e.editable && (e.role == "text" || e.role == "image") &&
                !interactive(axById[id]?.takeIf { !it.ignored }?.let { role(it.role) })
        }.take(MAX_LISTENER_PROBES)
    }

    private fun axByBackend(ax: List<AxNode>): Map<Int, AxNode> {
        val m = HashMap<Int, AxNode>()
        for (n in ax) {
            val id = n.backendId ?: continue
            val old = m[id]
            if (old == null || (old.ignored && !n.ignored)) m[id] = n
        }
        return m
    }

    // ---------- Step 2: Merge ----------

    fun fuse(input: Input): Result {
        val axById = axByBackend(input.ax)
        val merged = input.page.mapIndexed { i, e ->
            val id = input.pageIds.getOrNull(i)
            Item(merge(e, id?.let(axById::get), id != null && id in input.listeners), id, e.ref, if (id?.let(axById::get) != null) Origin.BOTH else Origin.PAGE, null)
        }
        val extra = axOnly(input)
        // Insertion: in the cross-source frame, it follows the placeholder; in the readable document, it
        // follows the same frame, and the accessibility tree order is after the last aligned element
        // before it.
        val pageOrder = HashMap<Int, Pair<String, Int>>()
        input.pageIds.forEachIndexed { i, id -> id?.let(axById::get)?.let { pageOrder[i] = it.frameId to it.order } }
        val after = extra.groupBy { c ->
            c.placeholder ?: pageOrder.entries.filter { it.value.first == c.node.frameId && it.value.second < c.node.order }
                .maxByOrNull { it.value.second }?.key ?: (input.page.size - 1)
        }
        val all = ArrayList<Item>()
        if (input.page.isEmpty()) extra.forEach { all += it.item }
        merged.forEachIndexed { i, item ->
            all += item
            after[i]?.sortedBy { it.node.order }?.forEach { all += it.item }
        }
        val kept = budget(all, input.budget)
        val elements = ArrayList<UiElement>(kept.size)
        val pageRefs = HashMap<String, String>()
        val ids = HashMap<String, Int>()
        val origins = HashMap<String, Origin>()
        val owners = HashMap<String, Int>()
        kept.forEachIndexed { i, it ->
            val ref = "@e${i + 1}"
            elements += it.element.copy(ref = ref)
            it.pageRef?.let { r -> pageRefs[ref] = r }
            it.backendId?.let { b -> ids[ref] = b }
            it.ownerId?.let { o -> owners[ref] = o }
            origins[ref] = it.origin
        }
        return Result(elements, pageRefs, ids, origins, owners, all.size - kept.size)
    }

    private class Item(val element: UiElement, val backendId: Int?, val pageRef: String?, val origin: Origin, val ownerId: Int?)

    private class Extra(val node: AxNode, val placeholder: Int?, val item: Item)

    private fun merge(e: UiElement, ax: AxNode?, listener: Boolean): UiElement {
        val heard = listener && !e.clickable && !e.editable && (e.role == "text" || e.role == "image")
        val base = if (heard) e.copy(role = if (e.role == "text") "button" else e.role, clickable = true) else e
        if (ax == null || ax.ignored) return base
        val axRole = role(ax.role)
        val roleOut = when {
            base.role in KEEP_PAGE_ROLES || axRole == null || axRole == "frame" -> base.role
            interactive(axRole) -> axRole
            base.role == "text" -> axRole
            else -> base.role
        }
        val axName = cut(ax.name)
        // There are values in the names of input boxes, drop-down boxes, and sliders (the password box
        // only says how many people have been filled in): keep the page script
        val valued = base.editable || base.role in setOf("textbox", "combobox", "slider") || roleOut in EDIT_ROLES
        val name = when {
            valued -> base.name.ifBlank { axName }
            axName.isNotBlank() -> axName
            else -> base.name
        }
        val disabled = "disabled" in ax.states
        val readonly = "readonly" in ax.states
        return base.copy(
            role = roleOut,
            name = name,
            clickable = !disabled && (base.clickable || roleOut in CLICK_ROLES),
            editable = !disabled && !readonly && (base.editable || (roleOut in EDIT_ROLES && "editable" in ax.states)),
            focused = base.focused || "focused" in ax.states,
            states = base.states + ax.states.filter { it in UiStates.ALL },
        )
    }

    private fun axOnly(input: Input): List<Extra> {
        val w = input.width
        val h = input.height
        val view = Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        val pageRects = input.page.map { rectOf(it, w, h) }
        val out = ArrayList<Extra>()
        val keptRects = ArrayList<Pair<Extra, Rect>>()
        val nodesByFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        for (c in candidates(input)) {
            val id = c.node.backendId ?: continue
            val box = input.boxes[id] ?: continue
            val top = input.onTop[id]
            // In the document that can be read: the page script does not list it, and the hit detection
            // is clearly stated at the top; in the cross-origin framework: the hit detection says that
            // it will not be collected unless it is covered.
            if (c.placeholder == null && top != true) continue
            if (c.placeholder != null && top == false) continue
            val clip = c.placeholder?.let { pageRects[it] } ?: view
            val raw = Rect(box.l * input.scale, box.t * input.scale, box.r * input.scale, box.b * input.scale)
            val vis = raw.intersect(clip)?.intersect(view) ?: continue
            if (vis.r - vis.l < 1 || vis.b - vis.t < 1) continue
            val roleOut = role(c.node.role) ?: continue
            val name = cut(c.node.name)
            // The page script does not match the node number, but it is actually the same element (same
            // name or interactive and mostly overlapping): no duplicate columns
            val dup = input.page.indices.any { i ->
                val e = input.page[i]
                if (input.pageIds[i] != null || e.role == "frame") return@any false
                val r = pageRects[i]
                r.contains(vis.cx, vis.cy) && (e.name.equals(name, ignoreCase = true) || ((e.clickable || e.editable) && r.iou(vis) > 0.5))
            }
            if (dup) continue
            // Interactive nodes with the same name (buttons in links) nested in the accessibility tree,
            // with the same name as the character and almost the same frame: only the outer/first one is
            // left
            val nested = keptRects.any { (k, r) ->
                val sameName = k.item.element.name.equals(name, ignoreCase = true)
                (sameName || name.isBlank()) && isAncestor(nodesByFrame[c.node.frameId].orEmpty(), k.node, c.node) ||
                    (sameName && k.item.element.role == roleOut && r.iou(vis) > 0.8)
            }
            if (nested) continue
            val disabled = "disabled" in c.node.states
            val l = vis.l.roundToInt()
            val t = vis.t.roundToInt()
            val r = vis.r.roundToInt()
            val b = vis.b.roundToInt()
            val el = UiElement(
                ref = "",
                role = roleOut,
                name = name,
                centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
                centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
                bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
                resourceId = null,
                clickable = !disabled && roleOut in CLICK_ROLES,
                editable = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
                scrollable = false,
                focused = "focused" in c.node.states,
                states = c.node.states.filter { it in UiStates.ALL }.toSet(),
                // Only the input boxes visible in the accessibility tree (not listed in cross-source
                // frameworks and screen reading scripts): It is not clear whether it is a password box,
                // so it is counted as a password box.
                secret = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
            )
            val x = Extra(c.node, c.placeholder, Item(el, id, null, Origin.AX, c.ownerId))
            out += x
            keptRects += x to vis
        }
        return out
    }

    private fun isAncestor(nodes: Map<String, AxNode>, outer: AxNode, inner: AxNode): Boolean {
        if (outer.frameId != inner.frameId) return false
        var p = inner.parent?.let(nodes::get)
        var hops = 0
        while (p != null && hops++ < 256) {
            if (p.id == outer.id) return true
            p = p.parent?.let(nodes::get)
        }
        return false
    }

    /** When the budget exceeds the budget, the order of "no information → plain text and other non-interactive → interactive" will be thrown from back to front, and the remaining ones will still be in the original order. */
    private fun budget(all: List<Item>, budget: Int): List<Item> {
        if (all.size <= budget) return all
        fun tier(e: UiElement) = when {
            !e.meaningful -> 0
            !(e.clickable || e.editable || e.scrollable) -> 1
            else -> 2
        }
        val drop = HashSet<Int>()
        var need = all.size - budget.coerceAtLeast(0)
        for (t in 0..2) {
            for (i in all.indices.reversed()) {
                if (need == 0) break
                if (tier(all[i].element) == t) { drop += i; need-- }
            }
        }
        return all.filterIndexed { i, _ -> i !in drop }
    }

    private fun cut(s: String): String {
        val c = s.replace(Regex("\\s+"), " ").trim()
        return if (c.length > NAME_CHARS) c.substring(0, NAME_CHARS) else c
    }

    private data class Rect(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun intersect(o: Rect): Rect? {
            val nl = max(l, o.l); val nt = max(t, o.t); val nr = min(r, o.r); val nb = min(b, o.b)
            return if (nr > nl && nb > nt) Rect(nl, nt, nr, nb) else null
        }
        fun contains(x: Double, y: Double) = x >= l && x <= r && y >= t && y <= b
        private fun area() = max(0.0, r - l) * max(0.0, b - t)
        fun iou(o: Rect): Double {
            val i = intersect(o)?.area() ?: return 0.0
            val u = area() + o.area() - i
            return if (u <= 0) 0.0 else i / u
        }
    }

    // Normalized 0–999 to return view pixels; the error is less than one pixel
    private fun rectOf(e: UiElement, w: Int, h: Int): Rect {
        fun dx(v: Int) = v * (w - 1).coerceAtLeast(1) / 999.0
        fun dy(v: Int) = v * (h - 1).coerceAtLeast(1) / 999.0
        return Rect(dx(e.bounds[0]), dy(e.bounds[1]), dx(e.bounds[2]), dy(e.bounds[3]))
    }
}
