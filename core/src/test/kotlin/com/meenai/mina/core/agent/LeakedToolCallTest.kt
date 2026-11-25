package com.meenai.mina.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeakedToolCallTest {
    // Just like the real tool list, put tool names like wait and find that are also common words first.
    private val tools = listOf("wait", "find", "ask_user", "web_read_page", "phone_observe")

    /** The call to XML writing is not parsed by the server and becomes the text as it is. */
    @Test fun xmlStyleCallIsFound() {
        val text = "<tool_call>\n<function=ask_user>\n<parameter=question>\n想先确认一下:是要开户,还是了解集合竞价?\n</parameter>\n</function>\n</tool_call>"
        assertEquals("ask_user", LeakedToolCall.find(text, tools))
    }

    @Test fun jsonStyleCallIsFound() {
        val text = "<tool_call>\n{\"name\": \"web_read_page\", \"arguments\": {}}\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    @Test fun otherMarkersAreFound() {
        assertEquals("phone_observe", LeakedToolCall.find("[TOOL_CALLS][{\"name\": \"phone_observe\", \"arguments\": {}}]", tools))
        assertEquals("phone_observe", LeakedToolCall.find("<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>phone_observe", tools))
    }

    /** The user asked about these formats: the answer mentioned tags, but did not mention any tools this time, which is a normal answer. */
    @Test fun markupWithoutAKnownToolIsNotALeak() {
        assertNull(LeakedToolCall.find("有的模型写工具调用时用 `<tool_call>` 包起来,里面是 `<function=名字>`。", tools))
    }

    /** If the tool name is mentioned in the answer (for example, what has been done), but there is no call tag, it does not count. */
    @Test fun toolNameWithoutMarkupIsNotALeak() {
        assertNull(LeakedToolCall.find("我用 phone_observe 看过屏幕,安卓版本是 14。", tools))
    }

    /** Common words with the same name as tools (wait, find) that appear before the mark are not counted: the tool that is actually called after the mark is reported. */
    @Test fun toolNameIsTakenAfterTheMarker() {
        val text = "Wait, let me find the page first.\n<tool_call>\n<function=web_read_page>\n</function>\n</tool_call>"
        assertEquals("web_read_page", LeakedToolCall.find(text, tools))
    }

    /** A tool name does not refer to a tool when it is only part of another word. */
    @Test fun toolNameMustBeAWholeWord() {
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"ask_user_later\"}</tool_call>", tools))
        assertNull(LeakedToolCall.find("<tool_call>{\"name\": \"my_ask_user\"}</tool_call>", tools))
    }
}
