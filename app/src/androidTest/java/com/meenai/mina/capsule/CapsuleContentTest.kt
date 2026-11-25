package com.meenai.mina.capsule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleContentTest {

    @get:Rule val compose = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun show(view: CapsuleView.Shown) {
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(view, onTap = { clicks += "open" }, onStop = { clicks += "stop" }, onHandBack = { clicks += "back" })
            }
        }
    }

    @Test fun runningIsABallWithRingThatSaysWhatItDoesAndOpensOnTap() {
        show(CapsuleView.Shown(CapsuleKind.Running, "在搜牙膏", count = 1, runKey = "a", conversationId = "c"))
        compose.onNodeWithContentDescription("Mina 在干活：在搜牙膏，点开看详情").assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        // Don’t write that sentence on one
        compose.onNodeWithText("在搜牙膏").assertDoesNotExist()
        compose.onNodeWithTag("capsule-ball").performClick()
        assertEquals(listOf("open"), clicks)
    }

    @Test fun eachBallStateShowsItsMarks() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Running, "在搜", count = 3))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        // Several pieces: Write the number of pieces in the corner
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        // Other conditions: gray circle, no number of pieces
        view = CapsuleView.Shown(CapsuleKind.Held, "先等等：排队中", count = 1)
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertDoesNotExist()
        // I want you to deal with what you have seen: There is still one, orange circle
        view = CapsuleView.Shown(CapsuleKind.NeedConfirm, "要你同意：发送", count = 1, needCount = 1)
        compose.onNodeWithContentDescription("要你同意：发送，点开处理").assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        // Completed, not completed, stopped: corner mark, no circle
        for (kind in listOf(CapsuleKind.Done, CapsuleKind.Failed, CapsuleKind.Cancelled)) {
            view = CapsuleView.Shown(kind, "结果")
            compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        // Idle: only icons
        view = CapsuleView.Shown(CapsuleKind.Idle, "")
        compose.onNodeWithContentDescription(CapsuleCopy.IDLE).assertIsDisplayed()
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun pillShowsItsLineAndOpensOnTap() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedTakeover, "需要你：付款", pill = true))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, { clicks += "open" }, {}, {}) }
        }
        for (v in listOf(
            CapsuleView.Shown(CapsuleKind.NeedTakeover, "需要你：付款", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedConfirm, "要你同意：发送", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, "Mina 问你：要哪个？", pill = true),
            CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果", pill = true),
            CapsuleView.Shown(CapsuleKind.Failed, "没办成 · 点开看原因", pill = true),
            CapsuleView.Shown(CapsuleKind.Cancelled, "已停止", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, "1 件要你处理 · 共 2 件", count = 2, needCount = 1, pill = true),
        )) {
            view = v
            compose.onNodeWithText(v.text).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("capsule").performClick()
        assertEquals(listOf("open"), clicks)
    }

    /** Expand and retract: Change the width of the same base, and it will be as wide as a star after retracting. */
    @Test fun pillShrinksBackToBall() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果", pill = true))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}, side = DockSide.Left) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(pill = false)
        compose.waitForIdle()
        val ball = compose.onNodeWithTag("capsule-ball").getBoundsInRoot()
        assertTrue("ball should be narrower: ${ball.width} vs $wide", ball.width < wide)
        assertEquals(ball.height.value, ball.width.value, 1f)
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun handedOverBarHasStopAndHandBack() {
        show(CapsuleView.Shown(CapsuleKind.HandedOver, CapsuleModel.HANDED_OVER, detail = "付款 · 弄好后点右边", runKey = "a"))
        compose.onNodeWithTag("capsule-bar").assertIsDisplayed()
        compose.onNodeWithText("付款 · 弄好后点右边").assertIsDisplayed()
        compose.onNodeWithText(CapsuleCopy.STOP).performClick()
        compose.onNodeWithText("处理好了").performClick()
        assertEquals(listOf("stop", "back"), clicks)
    }

    /** If the same paragraph ([CapsuleView.Shown.widthEpoch] remains unchanged) is changed to a short sentence, the capsule will not be narrowed; the width will be reset after changing a paragraph. */
    @Test fun widthOnlyGrowsWithinEpoch() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedAnswer, "Mina 问你：要三家店里哪一家的", pill = true, widthEpoch = 1))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(text = "短")
        compose.onNodeWithTag("capsule").assertWidthIsAtLeast(wide)
        view = view.copy(widthEpoch = 2)
        compose.waitForIdle()
        val narrow = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        assertTrue("new epoch should re-fit: $narrow vs $wide", narrow < wide)
    }

    /** When the star fades out, the outer ring and corner mark are still there; when reading the screen, it can be "moved to the other side of the screen". */
    @Test fun fadedBallKeepsMarksAndOffersSwapSide() {
        var swapped = 0
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Running, "在搜", count = 2), {}, {}, {},
                    alpha = CapsuleFade.BALL, onSwapSide = { swapped++ },
                )
            }
        }
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == CapsuleCopy.SWAP_SIDE }.action() }
        assertEquals(1, swapped)
    }

    /** When reading the screen, there is an additional "Talk" instead of long pressing; long pressing and talking will make the capsule light up with a blue border. */
    @Test fun talkActionAndHoldingRing() {
        var talked = 0
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Idle, ""), {}, {}, {},
                    holding = true, onTalk = { talked++ }, talkLabel = "说话",
                )
            }
        }
        compose.onNodeWithTag("capsule-holding", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "说话" }.action() }
        assertEquals(1, talked)
    }
}
