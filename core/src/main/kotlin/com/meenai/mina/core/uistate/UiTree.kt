package com.meenai.mina.core.uistate

/** One accessible window on one screen. When the root node cannot be read, the window is still retained to let the model know that there is an obstruction or a loading interface. */
data class UiWindowTree(
    val id: Int,
    val type: String,
    val layer: Int,
    val title: String?,
    val packageName: String?,
    val active: Boolean,
    val focused: Boolean,
    val nodes: List<UiTreeNode>,
)

/** Visible accessible nodes; only nodes that can be parsed from this screen's element table have [ref]. */
data class UiTreeNode(
    val role: String,
    val bounds: List<Int>,
    val ref: String? = null,
    val text: String? = null,
    val description: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val secret: Boolean = false,
    /** It looks like a scroll wheel selector (time, date, region... rotated one by one): use the scroll wheel tool to move to the value one at a time. */
    val wheel: Boolean = false,
    val children: List<UiTreeNode> = emptyList(),
) {
    /** The stable number only replaces the model address; the node itself and the reference table on the Android side remain unchanged. */
    fun withRefs(refs: Map<String, String>): UiTreeNode = copy(
        ref = ref?.let { refs[it] ?: it },
        children = children.map { it.withRefs(refs) },
    )
}
