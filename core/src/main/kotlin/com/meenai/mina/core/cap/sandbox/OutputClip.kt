package com.meenai.mina.core.cap.sandbox

/** The command output given to the model: beginning + end, with the number of omitted words indicated in the middle. */
object OutputClip {
    fun clip(s: String, head: Int = 4000, tail: Int = 12000): Pair<String, Boolean> {
        if (s.length <= head + tail) return s to false
        // Not splitting an agent pair in half
        val h = if (head > 0 && s[head - 1].isHighSurrogate()) head - 1 else head
        var start = s.length - tail
        if (start < s.length && s[start].isLowSurrogate()) start++
        return "${s.substring(0, h)}\n…(${start - h} characters omitted)…\n${s.substring(start)}" to true
    }
}
