package com.meenai.mina.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Background Screen" page: empty, one screen per line, click to open a small screen (only fake data is used, no real screen is created). */
@RunWith(AndroidJUnit4::class)
class ScreensScreenTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(rows: List<ScreenRow>) = compose.setContent {
        MinaTheme {
            ScreensContent(rows, onBack = { calls += "back" }, onOpenConversation = { calls += "open $it" })
        }
    }

    private fun row(displayId: Int, key: String = "k$displayId") =
        ScreenRow(key, "conv-$key", "打开设置看一下电池", displayId, TaskState.Running(1, "点了一下"), foreground = "设置")

    @Test fun emptyPageSaysNoTaskIsRunning() {
        show(emptyList())
        compose.onNodeWithText(ScreensCopy.EMPTY).assertIsDisplayed()
    }

    /** One card for each task: what you are doing, which app you are using, and the status are all there; click on that row to return to the conversation to which it belongs. */
    @Test fun oneCardPerTaskOpensItsConversation() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithText("正在用设置").assertIsDisplayed()
        compose.onNodeWithText("还没打开 App").assertIsDisplayed()
        compose.onAllNodesWithText("打开设置看一下电池").assertCountEquals(2)
        compose.onAllNodesWithText("正在执行").assertCountEquals(2)
        compose.onNodeWithText("正在用设置").performClick()
        assertEquals(listOf("open conv-k12"), calls)
    }

    /** There is no "viewing screen" in the area where the screen has not yet been built: there is no screen to see, and there is no entry for no response after clicking on it. */
    @Test fun aScreenThatIsNotBuiltYetHasNoPreview() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithTag("screens-preview-queued").assertDoesNotExist()
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().assertIsDisplayed()
    }

    /** Click once to expand and then click to collapse: the words on the button will change accordingly. */
    @Test fun previewToggles() {
        show(listOf(row(12)))
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().performClick()
        compose.onNodeWithText(ScreensCopy.HIDE_PREVIEW).assertExists()
        compose.onNodeWithTag("screens-preview-k12").performClick()
        compose.onNodeWithText(ScreensCopy.PREVIEW).assertExists()
        assertEquals(emptyList<String>(), calls)
    }
}
