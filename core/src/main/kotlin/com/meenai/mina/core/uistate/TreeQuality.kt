package com.meenai.mina.core.uistate

/**
 * Whether the screen's accessibility tree is usable; written to `treeQuality` when insufficient.
 * [Partial]: the tree is missing a piece (most controls lack labels, or on-screen text is missing from
 * the tree); [Unusable]: almost nothing readable in the tree; [Protected]: screenshot-blocked page
 * whose screen cannot be captured (handled as `protected`).
 */
internal enum class TreeQuality(val wire: String) {
    Adequate("adequate"), Partial("partial"), Unusable("unusable"), Protected("protected"),
}

/** One judgment. [quality] is null: it is not accurate to judge by just looking at the tree. You have to read the text on the screen to determine (it will always be null if you cannot read the words); [reason] is not enough, and it is imageReason when the picture is attached. */
internal data class TreeCheck(val quality: TreeQuality?, val reason: String? = null) {
    /** The tree is missing something or cannot be used: the model must be supplemented by pictures or on-screen text. */
    val poor: Boolean get() = quality == TreeQuality.Partial || quality == TreeQuality.Unusable
}

/**
 * First use a cheap tree to judge. If the judgment is not accurate, then read the text on the screen
 * (OCR) to compare. The tree cannot read words unless it is "obviously useful": there are enough
 * elements with names, most controls have names, and there is not a large area in the App window without
 * any words (canvas, map, picture, web page whose content cannot be read). For the rest of the words
 * that you are not sure about, just read them. It is better to read them one more time.
 */
internal object TreeAdequacy {
    /** Having less than this number of named elements makes you uncertain: a simple page with just a few words will look the same as a tree with a chunk missing. */
    private const val ENOUGH_NAMED = 6
    /** A node with no words in the subtree takes up so much of the screen (0–1) that it is a blind spot. */
    private const val BLIND_AREA = 0.2
    /** There are so many named elements inside, just the base plate (background image, container) underneath, not a blind area. */
    private const val NAMED_INSIDE = 3
    /** OCR fills in so many lines of words that are not in the tree, even if the tree is missing a piece. */
    private const val MISSING_LINES = 3
    private const val APPLICATION = "application"

    fun ofTree(s: ScreenSnapshot): TreeCheck {
        if (s.protected) return TreeCheck(TreeQuality.Protected)
        if (s.unreadableApp != null && s.elements.none { it.meaningful }) return TreeCheck(TreeQuality.Unusable, "unreadable_window")
        if (!s.hasWindow && s.web == null) return TreeCheck(TreeQuality.Adequate)
        if (s.elements.isEmpty()) return TreeCheck(TreeQuality.Unusable, "empty_elements")
        val named = s.elements.filter { it.name.isNotBlank() }
        val controls = s.elements.filter { it.clickable || it.editable }
        // The resourceId is often the developer's internal number and cannot be used as a control name
        // that users can recognize.
        val namedControls = controls.count { it.name.isNotBlank() || !it.placeholder.isNullOrBlank() }
        if (controls.isNotEmpty() && (namedControls == 0 || (controls.size >= 4 && namedControls * 3 <= controls.size)))
            return TreeCheck(if (named.isEmpty()) TreeQuality.Unusable else TreeQuality.Partial, "unlabeled_controls")
        if (named.isEmpty()) return TreeCheck(TreeQuality.Unusable, "no_text")
        if (named.size < ENOUGH_NAMED || blindArea(s, named)) return TreeCheck(null)
        return TreeCheck(TreeQuality.Adequate)
    }

    /** If the tree judgment is not correct, it will be determined based on the characters that are added by OCR and are not in the tree ([missing], the error recognition line has been removed); if the word is not read ([missing] is null), the judgment is still inaccurate. */
    fun withText(tree: TreeCheck, missing: List<VisibleTextLine>?): TreeCheck {
        if (tree.quality != null || missing == null) return tree
        return if (missing.size >= MISSING_LINES) TreeCheck(TreeQuality.Partial, "text_outside_tree") else TreeCheck(TreeQuality.Adequate)
    }

    /** There is a blind spot in the app window: the subtree is not a word, it is large enough, and it is not a base under the named element. */
    private fun blindArea(s: ScreenSnapshot, named: List<UiElement>): Boolean {
        var found = false
        // Returns whether there are words in this subtree; after one pass, each node is only viewed once
        fun visit(node: UiTreeNode): Boolean {
            var text = !node.text.isNullOrBlank() || !node.description.isNullOrBlank() || !node.hintText.isNullOrBlank()
            for (child in node.children) if (visit(child)) text = true
            if (!text && !found && area(node.bounds) >= BLIND_AREA && named.count { inside(it.bounds, node.bounds) } < NAMED_INSIDE) found = true
            return text
        }
        s.windows.filter { it.type == APPLICATION }.forEach { w -> w.nodes.forEach { visit(it) } }
        return found
    }

    private fun area(b: List<Int>): Double =
        if (b.size != 4) 0.0 else (b[2] - b[0]).coerceAtLeast(0).toDouble() * (b[3] - b[1]).coerceAtLeast(0) / (999.0 * 999.0)

    private fun inside(e: List<Int>, b: List<Int>): Boolean {
        if (e.size != 4 || b.size != 4) return false
        val x = (e[0] + e[2]) / 2; val y = (e[1] + e[3]) / 2
        return x in b[0]..b[2] && y in b[1]..b[3]
    }
}
