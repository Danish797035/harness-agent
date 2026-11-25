package com.meenai.mina.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Test

class HantTest {
    // Matching two strings word by word: unequal lengths will be quietly truncated and the entire table
    // will be misaligned; one traditional Chinese character can only match one simplified character
    @Test fun tableLinesUp() {
        assertEquals(Hant.TRADITIONAL.length, Hant.SIMPLIFIED.length)
        assertEquals(Hant.TRADITIONAL.length, Hant.TRADITIONAL.toSet().size)
        assertEquals("", Hant.TRADITIONAL.filter { it in Hant.SIMPLIFIED })
    }

    @Test fun foldsWordsToSimplified() {
        assertEquals("注销账户", Hant.fold("註銷帳户"))
        assertEquals("回复", Hant.fold("回覆"))
        assertEquals("发布", Hant.fold("發佈"))
        assertEquals("联系对方", Hant.fold("聯繫對方"))
        assertEquals("已经简体", Hant.fold("已经简体"))
    }
}
