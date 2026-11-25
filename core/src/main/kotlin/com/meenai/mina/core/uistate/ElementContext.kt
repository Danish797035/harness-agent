package com.meenai.mina.core.uistate

/** An element's own context, when judging what it does, only look at these, not the entire screen: text on the same line (attached labels, descriptions in the line). */
object ElementContext {
    /** Same line: The center falls within the upper and lower range of the element, or the center of the element falls within its upper and lower range. */
    fun row(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.bounds.size != 4) return emptyList()
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return s.elements.filter { o ->
            o !== e && o.bounds.size == 4 && ((o.bounds[1] + o.bounds[3]) / 2 in e.bounds[1]..e.bounds[3] || cy in o.bounds[1]..o.bounds[3])
        }
    }
}
