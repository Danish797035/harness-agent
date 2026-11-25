package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimateTest {
    @Test fun hanAndLatin() {
        // 8 non-Chinese characters / 4 = 2; 4 Chinese characters × 0.75 = 3
        assertEquals(5, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Text("abcdefgh打开设置")), Author.Mina))))
        // The names and parameters of tool calls, and tool results are all included
        val call = Part.ToolCall("c1", "tap", "{}")
        val result = Part.ToolResult("c1", "12345678", isError = false)
        assertEquals(4, TokenEstimate.of(listOf(Message(Role.Assistant, listOf(call), Author.Mina), Message(Role.Tool, listOf(result), Author.Mina))))
        assertEquals(0, TokenEstimate.of(emptyList()))
    }

    @Test fun imagesCountFixed() {
        val big = "A".repeat(400_000)
        assertEquals(1000, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image(big)), Author.Mina))))
        assertEquals(2001, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image("x"), Part.Text("abcd"), Part.Image("y")), Author.Mina))))
    }

    @Test fun cjkPunctuationFullWidthKanaHangulCountAsWide() {
        // "." "," "A", kana, proverb each 0.75:8 → 6
        assertEquals(6, TokenEstimate.of("。，Ａあアカ한글"))
        assertEquals(1, TokenEstimate.of("abcd"))
    }

    @Test fun imagesWithSizeCountByPixels() {
        // Original resolution 1224×2700 and compressed 580×1280 (long side 1280)
        val (w, h) = com.meenai.mina.core.vision.ModelImage.scaled(1224, 2700)
        assertEquals(580 to 1280, w to h)
        assertEquals(4407, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image("x", width = 1224, height = 2700)), Author.Mina))))
        assertEquals(990, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image("x", width = w, height = h)), Author.Mina))))
    }
}
