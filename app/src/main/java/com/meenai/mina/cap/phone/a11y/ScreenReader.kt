package com.meenai.mina.cap.phone.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.meenai.mina.core.cap.phone.WheelMath
import com.meenai.mina.core.uistate.ElementLocator
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.uistate.UiStates
import com.meenai.mina.core.uistate.UiTreeNode
import com.meenai.mina.core.uistate.UiWindowTree
import com.meenai.mina.core.uistate.normalize

/**
 * Read the result of the screen once. [hash] covers the text, type, position, check, input focus and
 * cursor/selection of all visible nodes (including those marked "not important for accessibility"), and
 * is used to determine whether the interface has changed: clicking on the input box only changes the
 * focus, which counts as a change.
 */
internal class ScreenRead(
    val entries: List<NodeEntry>,
    val hash: Long,
    val texts: List<String>,
    val passwordField: Boolean,
    val hasAppWindow: Boolean,
    val topPackage: String?,
    val windows: List<UiWindowTree> = emptyList(),
    /** There is an application window (regardless of whether the root node is readable or not). */
    val anyAppWindow: Boolean = hasAppWindow,
)

/** Read the element list, interface fingerprint and diagnostic text from the window of a certain screen. */
internal object ScreenReader {
    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80
    private const val AGGREGATE_MAX = 60

    // The input method and accessibility overlay do not belong to the operated App
    private val SKIPPED_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    )

    /** Windows arranged from top to bottom and belonging to the interface content. */
    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED_TYPES }.sortedByDescending { it.layer }

    // The top application window may be a floating layer with unreadable content (pop-up menu, word
    // selection floating window of input box, etc.), skip them
    fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        contentWindows(windows).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() }

    fun read(windows: List<AccessibilityWindowInfo>, width: Int, height: Int): ScreenRead {
        val content = contentWindows(windows)
        val entries = mutableListOf<NodeEntry>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = FNV_OFFSET
        var password = false
        var lastLabel: String? = null
        val rect = Rect()

        fun visit(n: AccessibilityNodeInfo, depth: Int, inList: Boolean): List<UiTreeNode> {
            if (depth > MAX_DEPTH) return emptyList()
            val visible = n.isVisibleToUser
            var ref: String? = null
            var clippedBounds = listOf(0, 0, 0, 0)
            if (visible) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, n.text, n.contentDescription, rect.flattenToString(),
                    states(n))
                if (n.isPassword) password = true
                val own = if (n.isPassword) n.hintText?.toString()?.trim()?.ifEmpty { null } else ownLabel(n)
                own?.let(texts::add)
                clip(rect, width, height)
                clippedBounds = listOf(normalize(rect.left, width), normalize(rect.top, height),
                    normalize(rect.right, width), normalize(rect.bottom, height))
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = n.isClickable || n.isEditable || n.isScrollable
                if (usable && (own != null || interactive)) {
                    val label = labelOf(n, own)
                    val role = roleOf(n)
                    if (seen.add("$role|$label|${rect.flattenToString()}")) {
                        val id = "@e${entries.size + 1}"
                        ref = id
                        val rid = n.viewIdResourceName
                        val element = UiElement(
                            ref = id, role = role, name = label,
                            centerX = rect.centerX(), centerY = rect.centerY(),
                            bounds = listOf(
                                normalize(rect.left, width), normalize(rect.top, height),
                                normalize(rect.right, width), normalize(rect.bottom, height),
                            ),
                            // The input box always carries the resource ID: you need to rely on it to
                            // identify the search box before pressing Enter (the name is often just a
                            // recommended word)
                            resourceId = if (label.isEmpty() || n.isEditable) readableResourceId(rid) else null,
                            clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
                            hint = own != null && showingHint(n),
                            focused = n.isFocused,
                            secret = n.isPassword,
                            placeholder = if (n.isEditable) n.hintText?.toString()?.trim()?.ifEmpty { null } else null,
                            // Android's search box control (input box in SearchView) is identified by
                            // class name
                            searchField = n.isEditable && n.className?.toString()?.contains("Search", ignoreCase = true) == true,
                            states = uiStates(n),
                        )
                        val locator = ElementLocator(role, label, rid, rect.centerX(), rect.centerY(), lastLabel, element.hint, inList)
                        entries += NodeEntry(element, locator, n)
                        if (label.isNotEmpty()) lastLabel = label
                    }
                }
            }
            val childrenInList = inList || isList(n)
            val children = buildList {
                for (i in 0 until n.childCount) n.getChild(i)?.let { addAll(visit(it, depth + 1, childrenInList)) }
            }
            if (!visible) return children
            return listOf(UiTreeNode(
                role = roleOf(n), bounds = clippedBounds, ref = ref,
                text = n.text?.toString()?.takeUnless { n.isPassword || it.isBlank() },
                description = n.contentDescription?.toString()?.takeUnless { n.isPassword || it.isBlank() },
                hintText = n.hintText?.toString()?.takeUnless { it.isBlank() },
                resourceId = n.viewIdResourceName,
                clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
                checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected,
                enabled = n.isEnabled, focused = n.isFocused, secret = n.isPassword,
                wheel = isWheel(n),
                children = children,
            ))
        }

        val trees = content.map { w ->
            val root = w.root
            UiWindowTree(
                id = w.id,
                type = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    else -> "other"
                },
                layer = w.layer, title = w.title?.toString(), packageName = root?.packageName?.toString(),
                active = w.isActive, focused = w.isFocused,
                nodes = root?.let { visit(it, 0, false) }.orEmpty(),
            )
        }
        return ScreenRead(
            entries = entries,
            hash = hash,
            texts = texts,
            passwordField = password,
            hasAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null },
            anyAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION },
            topPackage = topPackage(windows),
            windows = trees,
        )
    }

    /** The current characteristics of a single node (without context); null if the node is not visible or is too small. */
    fun locatorOf(n: AccessibilityNodeInfo, width: Int, height: Int): ElementLocator? {
        if (!n.isVisibleToUser) return null
        val rect = Rect().also { n.getBoundsInScreen(it) }
        clip(rect, width, height)
        if (rect.width() < MIN_SIZE || rect.height() < MIN_SIZE) return null
        val own = ownLabel(n)
        return ElementLocator(
            roleOf(n), labelOf(n, own), n.viewIdResourceName, rect.centerX(), rect.centerY(), null,
            hint = own != null && showingHint(n), inList = inList(n),
        )
    }

    /**
     * It looks like a scroll wheel selector: the original digital scroll wheel control, or it can
     * scroll, and there are several short values arranged equidistantly vertically in the center (see
     * [WheelMath.looksLikeWheel] for rules, I would rather miss it). If you can't pronounce the words
     * for one item, it won't count.
     */
    fun isWheel(n: AccessibilityNodeInfo): Boolean {
        if (n.className?.toString()?.endsWith("NumberPicker") == true) return true
        if (!n.isScrollable || n.childCount !in 3..12) return false
        val box = Rect().also(n::getBoundsInScreen)
        val r = Rect()
        val items = mutableListOf<WheelMath.Item>()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            val label = ownLabel(c) ?: c.takeIf { it.childCount == 1 }?.getChild(0)?.let(::ownLabel) ?: return false
            c.getBoundsInScreen(r)
            items += WheelMath.Item(label, r.centerX(), r.centerY())
        }
        return WheelMath.looksLikeWheel(items.sortedBy { it.centerY }, box.left, box.right)
    }

    /** List container: sub-items will be reused, and the same position may be replaced by other items. */
    private fun isList(n: AccessibilityNodeInfo): Boolean {
        if (n.collectionInfo != null) return true
        val cls = n.className?.toString() ?: return false
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")
    }

    private fun inList(n: AccessibilityNodeInfo): Boolean {
        var p = n.parent
        var depth = 0
        while (p != null && depth++ < MAX_DEPTH) {
            if (isList(p)) return true
            p = p.parent
        }
        return false
    }

    private fun clip(rect: Rect, width: Int, height: Int) {
        if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
    }

    private fun roleOf(n: AccessibilityNodeInfo) = n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"

    private fun labelOf(n: AccessibilityNodeInfo, own: String?) = own ?: if (n.isClickable) descendantLabel(n) else ""

    /** Only interface fingerprints are calculated, and element tables are not built (used for comparison before and after actions). */
    fun hash(windows: List<AccessibilityWindowInfo>): Long {
        var hash = FNV_OFFSET
        val rect = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, n.text, n.contentDescription, rect.flattenToString(),
                    states(n))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        for (w in contentWindows(windows)) w.root?.let { visit(it, 0) }
        return hash
    }

    /** The control status used to judge the action effect (the same batch as [hash]). */
    @Suppress("DEPRECATION")
    private fun uiStates(n: AccessibilityNodeInfo): Set<String> = buildSet {
        if (n.isChecked) add(UiStates.CHECKED)
        if (n.isSelected) add(UiStates.SELECTED)
        if (!n.isEnabled) add(UiStates.DISABLED)
    }

    // isChecked has been replaced by the tri-state checked since API 36; minSdk 30 still needs to use it
    @Suppress("DEPRECATION")
    private fun states(n: AccessibilityNodeInfo) =
        "${n.isChecked}${n.isSelected}${n.isEnabled}${n.isFocused}${n.textSelectionStart}:${n.textSelectionEnd}"

    /** The node's own name: text (the prompt also counts, and the input box relies on it to explain its purpose). If not, use the content description. */
    fun ownLabel(n: AccessibilityNodeInfo): String? {
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = n.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return n.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    /**
     * What is displayed on the node is the prompt: the system is marked showingHintText, or there is no
     * actual text, the name comes from the prompt, or the reported text is the prompt itself (some input
     * boxes do not have that mark).
     */
    private fun showingHint(n: AccessibilityNodeInfo): Boolean {
        if (n.isShowingHintText) return true
        val hint = n.hintText?.toString()?.trim()
        if (hint.isNullOrEmpty()) return false
        val text = n.text?.toString()?.trim()
        return if (text.isNullOrEmpty()) n.contentDescription.isNullOrBlank() else text == hint
    }

    /** The prompt that the input box is displaying; it is null when the prompt is not displayed. */
    fun placeholderOf(n: AccessibilityNodeInfo): String? =
        if (showingHint(n)) n.text?.toString()?.trim()?.ifEmpty { null } ?: n.hintText?.toString()?.trim() else null

    /**
     * The actual text visible to the user in the input box; it is an empty string when the prompt is
     * displayed, and it is null when the password box cannot be read. [placeholder] is the prompt you
     * see before typing: some input boxes are no longer marked "Show Prompt" after typing, but they are
     * still reported as text, which is considered empty at this time. But when the [typing] to be input
     * itself is equal to the prompt, the same text without the "Show Prompt" mark will be deemed to have
     * been entered.
     */
    fun fieldText(n: AccessibilityNodeInfo, placeholder: String? = null, typing: String? = null): String? {
        if (n.isPassword) return null
        if (n.isShowingHintText) return ""
        val text = n.text?.toString() ?: return ""
        val shown = text.trim()
        if (typing != null && shown == typing.trim()) return text
        if (showingHint(n) || (placeholder != null && shown == placeholder)) return ""
        return text
    }

    /** Clickable containers without names use the text of the child nodes as names. */
    private fun descendantLabel(n: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6 || parts.joinToString(" ").length > AGGREGATE_MAX) return
            for (i in 0 until c.childCount) {
                val child = c.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                ownLabel(child)?.let { if (it !in parts) parts += it }
                walk(child, depth + 1)
            }
        }
        walk(n, 0)
        val joined = parts.joinToString(" ")
        return if (joined.length > AGGREGATE_MAX) joined.take(AGGREGATE_MAX) + "…" else joined
    }

    /** `com.x:id/cart_icon` → `cart_icon`; the confusing short name cannot be recognized and will not be used. */
    private fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf { Regex("^[A-Za-z0-9_]{4,}$").matches(it) && Regex("[A-Za-z]{3}").containsMatchIn(it) }
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private fun mix(h0: Long, vararg parts: Any?): Long {
        var h = h0
        for (p in parts) {
            val s = p?.toString() ?: "\u0000"
            for (c in s) h = (h xor c.code.toLong()) * FNV_PRIME
            h = (h xor 0x1f) * FNV_PRIME
        }
        return h
    }
}
