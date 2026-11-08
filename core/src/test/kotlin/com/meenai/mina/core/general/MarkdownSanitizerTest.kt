package com.meenai.mina.core.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownSanitizerTest {

    @Test
    fun markdownFreeNotification() {
        val input = "### Title\n**Important:** Please check `system_code` and [view details](https://example.com) for *details*."
        val stripped = MarkdownSanitizer.strip(input)

        // Must not contain raw markdown markers
        assertFalse(stripped.contains("###"))
        assertFalse(stripped.contains("**"))
        assertFalse(stripped.contains("`"))
        assertFalse(stripped.contains("["))
        assertFalse(stripped.contains("]"))
        assertFalse(stripped.contains("(https://example.com)"))
        assertFalse(stripped.contains("*details*"))

        assertEquals("Title\nImportant: Please check system_code and view details for details.", stripped)
    }

    @Test
    fun bulletListSanitized() {
        val input = "* Item 1\n* Item 2\n- Item 3"
        val stripped = MarkdownSanitizer.strip(input)
        assertEquals("• Item 1\n• Item 2\n• Item 3", stripped)
    }

    @Test
    fun blankOrPlainTextPreserved() {
        assertEquals("", MarkdownSanitizer.strip(""))
        assertEquals("Plain text", MarkdownSanitizer.strip("Plain text"))
    }
}
