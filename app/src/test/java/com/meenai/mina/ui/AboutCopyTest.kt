package com.meenai.mina.ui

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.i18n.ResFileTexts
import com.meenai.mina.i18n.UiText
import com.meenai.mina.about.CheckStatus
import com.meenai.mina.about.ReleaseInfo
import com.meenai.mina.about.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyTest {
    @get:Rule val texts = EnglishTexts()

    private val r020 = ReleaseInfo("0.2.0", "Mina 0.2.0", "- 新", "2026-11-05", "https://github.com/wangzan101/Mina/releases/tag/v0.2.0", null)
    private val r010 = r020.copy(version = "0.1.0")

    @Test
    fun checkRowStates() {
        assertEquals(AboutCopy.CheckValue(null), AboutCopy.checkValue(UpdateState()))
        assertTrue(AboutCopy.checkValue(UpdateState(status = CheckStatus.Checking)).spinner)
        // Unable to connect but I have checked the new version earlier: it still prompts that there is a
        // new version
        assertTrue(AboutCopy.checkValue(UpdateState(latest = r020, update = r020, status = CheckStatus.Failed)).dot)
    }

    /** Contact author panel: English interface X Group chat first. */
    @Test
    fun xGroupFirstOnlyInEnglish() {
        assertTrue(AboutCopy.X_GROUP_FIRST)
    }
}
