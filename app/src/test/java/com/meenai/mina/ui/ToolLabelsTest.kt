package com.meenai.mina.ui

import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.i18n.ResFileTexts
import com.meenai.mina.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolLabelsTest {
    @get:Rule val texts = EnglishTexts()

    /** Every tool with a declared label has a localized UI label. */
    @Test
    fun everyDeclaredLabelIsLocalized() {
        val tools = knownTools()
        val labels = ToolLabels(tools)
        val declared = tools.names.mapNotNull { n -> tools[n]?.label?.let { n to it } }
        assertTrue(declared.size > 40)
        for ((name, label) in declared) {
            assertTrue("$name: $label", label in ToolLabels.TOOL_LABELS)
            val localized = labels.label(name)
            assertTrue("$name should have localized label", localized.isNotBlank())
        }
        assertEquals("Tap", labels.label("tap"))
        assertEquals("Open web page", labels.label("browser_navigate"))
    }
}
