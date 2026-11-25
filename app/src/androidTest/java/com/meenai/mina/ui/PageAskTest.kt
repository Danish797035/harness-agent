package com.meenai.mina.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.browser.PageAskMode
import com.meenai.mina.browser.UserBrowser
import com.meenai.mina.capsule.CapsuleModel
import com.meenai.mina.capsule.CapsuleRun
import com.meenai.mina.capsule.ChatThread
import com.meenai.mina.capsule.ThreadItem
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.task.TaskGate
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Ask Mina" section on the browser page looks like: normal input bar → half-screen panel during
 * work (light edge, step, stop) → after you do something on the page, you receive a small card with a
 * "Continue" on top; the panel opens by itself when Mina asks you. Don't run tasks, just give status
 * directly.
 */
@RunWith(AndroidJUnit4::class)
class PageAskTest {

    @get:Rule val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var chat: PageChat
    private val model = CapsuleModel()

    private fun card(state: TaskState) = model.cardRunOf(CapsuleRun("run-1", "conv-1", state, goal = "挑一个评价最好的", onScreen = true))
    private val thread = ChatThread(listOf(ThreadItem.User("u0", "挑一个评价最好的"), ThreadItem.Answer("a0", "好的，我先看一下这一页。")))

    private fun start() {
        compose.setContent {
            MinaTheme {
                val scope = rememberCoroutineScope()
                chat = remember { PageChat(ctx, UserBrowser.of(ctx), scope) }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { PageAskOverlay(chat, site = "shop.example.com", pageTitle = "降噪耳机搜索结果") }
                    if (chat.mode == PageAskMode.Bar) PageAskBar(chat)
                }
            }
        }
    }

    @Test
    fun barThenPanelThenPausedCard() {
        start()
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.HINT).assertIsDisplayed()

        // The user asked: The panel is open, Mina is working, there is light all around, and the stop
        // button is on the right side of the input box.
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Running(2, "", reason = "正在点「加入购物车」")), thread)
            chat.open = true
        }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertExists()
        compose.onNodeWithText(PageAskCopy.FROM_PAGE, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("page-panel-open-chat").assertIsDisplayed()
        compose.onNodeWithText("好的，我先看一下这一页。").assertIsDisplayed()
        compose.onNodeWithTag("capsule-stop").assertIsDisplayed()

        // You do it on the page: collect the small cards, put a "Continue" on the top, and no longer
        // highlight the edges.
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.onNodeWithTag("page-paused-banner").assertIsDisplayed()
        compose.onNodeWithTag("page-card-resume").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertDoesNotExist()
        compose.onNodeWithTag("page-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PageAskMode.Card, chat.mode) }

        // Click on the card to expand back to the panel
        compose.onNodeWithTag("page-card").performClick()
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-panel-collapse").performClick()
        compose.onNodeWithTag("page-card").assertIsDisplayed()
    }

    @Test
    fun questionOpensThePanelAndEndedRunReturnsToTheBar() {
        start()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Running(1, "")), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.WaitingInput("要哪个颜色？", listOf("午夜黑", "云白"))), thread) }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithText("午夜黑").assertIsDisplayed()
        // It’s over, put it away: return to the input bar
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Succeeded("加好了")), thread)
            chat.open = false
        }
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
    }

    /** Conversations starting from one page in the sidebar: the small "Web Page" label behind the title, and the line below is the website; there are no other conversations. */
    @Test
    fun drawerRowShowsTheWebTagAndSite() {
        compose.setContent {
            MinaTheme {
                Column {
                    ConversationRowContent(DrawerConversation("a", "挑一个降噪耳机", "", false, false, true, site = "shop.example.com"))
                    ConversationRowContent(DrawerConversation("b", "明早的闹钟", "", false, false, true))
                }
            }
        }
        compose.onNodeWithTag("conversation-web").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.WEB).assertIsDisplayed()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("明早的闹钟").assertIsDisplayed()
        compose.onAllNodesWithTag("conversation-web").assertCountEquals(1)
    }
}
