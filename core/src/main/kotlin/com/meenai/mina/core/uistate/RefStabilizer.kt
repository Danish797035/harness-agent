package com.meenai.mina.core.uistate

import kotlin.math.hypot
import kotlin.math.min

/**
 * A screen for the model: the ref of [elements] has been replaced by the model number, [snapshotId] is
 * the prefix plus serial number of this screen ("p1", "w2"...); [port] is a snapshot of the original
 * port, and [toPort] is the model number → port number.
 */
data class ModelSnapshot(
    val snapshotId: String,
    val port: ScreenSnapshot,
    val elements: List<UiElement>,
    val toPort: Map<String, String>,
) {
    /** Replace it with a snapshot of the model number; use it for irreversible determination and coordinate conversion. */
    val screen: ScreenSnapshot by lazy {
        val refs = toPort.entries.associate { (model, original) -> original to model }
        port.copy(
            snapshotId = snapshotId,
            elements = elements,
            windows = port.windows.map { window -> window.copy(nodes = window.nodes.map { it.withRefs(refs) }) },
        )
    }
}

sealed interface Resolved {
    /** [relocated]: The model gives the snapshotId of the old screen, and the number still refers to the same element in the latest screen. */
    data class Current(val portRef: String, val portSnapshotId: String, val relocated: Boolean) : Resolved
    data object Stale : Resolved
}

/** The maximum center displacement allowed by the old numbering is inherited: this ratio of the short side of the screen (consistent with the upper limit of port retrieval). */
const val STABLE_REF_MAX_SHIFT = RELOCATE_MAX_SHIFT

/** The old snapshotId can be translated up to a few screens ago; the earlier snapshotId will be treated as expired. */
private const val KEEP_CENTERS = 20

/**
 * Stable element numbers across reading screens. The port number is only valid within one screen read;
 * here, the unchanged elements in two adjacent screens are kept with the same model number, and are
 * translated back to the port number during action. The port is still responsible for expiration
 * judgment and retrieval.
 *
 * The conditions for inheriting the old number are no wider than the port retrieval ([relocate]): the
 * key (role + name + resource ID) is unique in both old and new screens, the front-end app has not
 * changed, the previous named element (context) in the traversal sequence is the same or the page as a
 * whole has not moved and it is still in place, the center displacement does not exceed the short side
 * [STABLE_REF_MAX_SHIFT]; the element in the list (falling in a scrollable container) must also have the
 * page not scrolled, and it has not moved. Repeated elements with the same name in the list (a row of
 * "Follow") always get new numbers: after scrolling, the same position is already in another row.
 * Elements without names and resource IDs cannot be recognized as being the same, so they will be given
 * new numbers. The numbered tasks increase monotonically, and retired ones are no longer reused.
 *
 * The snapshotId is [prefix] plus a serial number: a prefix for each screen. You can know which screen
 * it is by looking at the number.
 */
class RefStabilizer(private val prefix: String = "s") {
    private var nextRef = 1
    private var count = 0
    var latest: ModelSnapshot? = null
        private set
    /** The screen on which each number in the latest screen appears for the first time; once the number disappears from a certain screen, it will not appear again, so it exists continuously in [born, latest screen]. */
    private var born = mapOf<String, Int>()
    /** The center of each model number in the recent [KEEP_CENTERS] screen; the old snapshotId limits the cumulative displacement during translation. */
    private val centers = LinkedHashMap<Int, Map<String, Pair<Int, Int>>>()

    fun next(port: ScreenSnapshot): ModelSnapshot {
        val n = ++count
        val prev = latest?.takeIf { it.port.packageName == port.packageName }
        val shortSide = min(port.width, port.height)
        val before = prev?.let { locators(it.screen) }.orEmpty()
        val after = locators(port)
        val still by lazy { stationary(before, after, shortSide * SAME_SPOT_SHIFT) }
        val prevByKey = prev?.elements?.withIndex()?.groupBy { key(it.value) }.orEmpty()
        val newCount = port.elements.groupingBy(::key).eachCount()
        val toPort = LinkedHashMap<String, String>()
        val bornNow = HashMap<String, Int>()
        val elements = port.elements.mapIndexed { i, e ->
            val k = key(e)
            val old = prevByKey[k]?.singleOrNull()?.takeIf { (j, _) ->
                identifiable(e) && newCount[k] == 1 && relocate(before[j], before, after, shortSide) == Relocation.Found(i) &&
                    // List items will be reused: only if the page is not scrolled and it is still in
                    // place is it considered the same
                    (!(before[j].inList || after[i].inList) || (still && shift(before[j], after[i]) <= shortSide * SAME_SPOT_SHIFT))
            }?.value
            val ref = old?.ref ?: "@e${nextRef++}"
            bornNow[ref] = born[ref] ?: n
            toPort[ref] = e.ref
            e.copy(ref = ref)
        }
        born = bornNow
        centers[n] = elements.associate { it.ref to (it.centerX to it.centerY) }
        while (centers.size > KEEP_CENTERS) centers.remove(centers.keys.first())
        return ModelSnapshot("$prefix$n", port, elements, toPort).also { latest = it }
    }

    fun resolve(ref: String, snapshotId: String): Resolved {
        val cur = latest ?: return Resolved.Stale
        val n = snapshotId.removePrefix(prefix).takeIf { snapshotId.startsWith(prefix) }?.toIntOrNull() ?: return Resolved.Stale
        if (n < 1 || n > count) return Resolved.Stale
        // The numbers are exactly as they appear on that screen, and the wrong combinations of models
        // will not be missed.
        val from = born[ref] ?: return Resolved.Stale
        if (from > n) return Resolved.Stale
        val portRef = cur.toPort[ref] ?: return Resolved.Stale
        if (n != count) {
            // Each screen has not moved far, and it may have moved far cumulatively: starting from the
            // screen seen by the model
            val then = centers[n]?.get(ref) ?: return Resolved.Stale
            val now = centers[count]?.get(ref) ?: return Resolved.Stale
            val limit = min(cur.port.width, cur.port.height) * STABLE_REF_MAX_SHIFT
            if (hypot((then.first - now.first).toDouble(), (then.second - now.second).toDouble()) > limit) return Resolved.Stale
        }
        return Resolved.Current(portRef, cur.port.snapshotId, relocated = n != count)
    }

    private fun identifiable(e: UiElement) = e.name.isNotBlank() || !e.resourceId.isNullOrBlank()

    private fun key(e: UiElement) = e.role + "\u0000" + e.name + "\u0000" + (e.resourceId ?: "")

    private fun shift(a: ElementLocator, b: ElementLocator) =
        hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

    /**
     * The same characteristics as when reading the port screen: the context is the previous named
     * element in the traversal order; the center falling within the range of other scrollable elements
     * is counted in the list (there is no parent-child relationship in the port snapshot, it is inferred
     * based on the range).
     */
    private fun locators(s: ScreenSnapshot): List<ElementLocator> {
        val panes = s.elements.filter { it.scrollable && it.bounds.size == 4 }
        var last: String? = null
        return s.elements.map { e ->
            val x = normalize(e.centerX, s.width); val y = normalize(e.centerY, s.height)
            val inList = panes.any { p -> p !== e && x in p.bounds[0]..p.bounds[2] && y in p.bounds[1]..p.bounds[3] }
            ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, inList)
                .also { if (e.name.isNotBlank()) last = e.name }
        }
    }
}
