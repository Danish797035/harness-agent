package com.meenai.mina.ui

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.chat.DeleteImpact
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Long press a conversation in the sidebar to pop up a menu in place (pin to top, rename, delete, click
 * outside to close): to delete, confirm first, delete, and the list is updated on the spot; after
 * renaming, the list is replaced with a new name; after pinning, it is rowed to the top with a pushpin.
 * There is also a long-press answer in the conversation page, and the user's words will pop up to
 * copy/quote/select text: after selecting the text, you can select it, and the quote will be sent with
 * the next sentence. A task environment that does not require a real machine.
 */
@RunWith(AndroidJUnit4::class)
class ChatDeleteAndSelectTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-delete-test-" + UUID.randomUUID()))
    private val removed = mutableListOf<String>()
    private val started = mutableListOf<String>()

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { started += goal }
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun vm(impact: DeleteImpact = DeleteImpact(runningTask = false, schedules = 0)) = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        deletionImpact = { impact },
        removeConversation = { removed += it; store.delete(it) },
    )

    private fun show(vm: ChatViewModel, drawerOpen: Boolean) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list() }, conversationChanges = store.changes, conversationTitle = store::title,
                onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, reopen = drawerOpen,
            ) { open ->
                ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false), reduceMotion = true,
                    onOpenDrawer = open, openSettings = {})
            }
        }
        compose.waitForIdle()
    }

    @Test fun longPressARowAsksThenDeletes() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        store.append("b", Turn("订票", "好了", "succeeded"))
        val vm = vm(DeleteImpact(runningTask = true, schedules = 2))
        show(vm, drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithText("删除这个对话？").assertExists()
        val body = compose.onNodeWithTag("delete-conversation-body").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(body, "天气" in body && "停止" in body && "2" in body)

        // Cancel: delete nothing
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertTrue(removed.isEmpty())

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithTag("confirm-delete-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { removed.isNotEmpty() }
        assertEquals(listOf("a"), removed)
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        compose.onNodeWithText("订票").assertExists()
    }

    /** Long press to pop up the menu in place: the raised row (still the same title) and three items: pin, rename, delete; just open the menu and nothing will be changed. */
    @Test fun longPressShowsTheThreeActions() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu").assertExists()
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("置顶"))
        compose.onNodeWithTag("drawer-menu-rename").assert(hasText("改名"))
        compose.onNodeWithTag("drawer-menu-delete").assert(hasText("删除"))
        compose.onNodeWithText("取消置顶").assertDoesNotExist()
        // When the menu is open, the content behind it is not accessible and only the raised line is
        // left.
        assertEquals(1, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals("天气", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** When you click outside the menu and close the menu, nothing changes; the row in the sidebar is still there. */
    @Test fun tappingOutsideClosesTheMenu() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assertExists()
        // Lower right corner: The menu is connected to the left below that line and cannot be touched.
        compose.onNodeWithTag("drawer-menu").performTouchInput { click(percentOffset(0.95f, 0.95f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer-menu-pin").assertDoesNotExist()
        compose.onNodeWithText("天气").assertExists()
        assertTrue(removed.isEmpty())
        assertEquals("天气", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** Rename: The current title in the input box is replaced with a new one and saved; the list is renamed on the spot, and the saved one is also new. */
    @Test fun renameUpdatesTheRow() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-rename").performClick()
        compose.onNodeWithTag("rename-conversation-field").assert(hasText("天气"))
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("   ")
        compose.onNodeWithTag("confirm-rename-conversation").assertIsNotEnabled()
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("上海出行")
        compose.onNodeWithTag("confirm-rename-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("上海出行").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("天气").fetchSemanticsNodes().size)
        assertEquals("上海出行", store.title("a"))
        // The title I came up with later will not be overwritten.
        assertTrue(!store.setTitle("a", "天气"))
    }

    /** Pin: Arrange to the top of the "Pin" section with a thumbtack; then cancel pin and return to "Recent". */
    @Test fun pinMovesTheRowToTheTop() {
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setTitle("a", "天气")
        store.append("b", Turn("订票", "好了", "succeeded"))
        show(vm(), drawerOpen = true)
        compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).assertCountEquals(0)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("置顶")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("置顶").assertExists()
        compose.onAllNodesWithTag("conversation-row")[0].assert(hasText("天气"))
        assertTrue(store.pinnedAt("a") != null)

        compose.onNodeWithText("天气").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("取消置顶")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("置顶").assertDoesNotExist()
        assertEquals(null, store.pinnedAt("a"))
    }

    // Long-press the message to pop up the menu; after selecting "Select Text", the message returns to
    // the system's long-press selection, and the sidebar does not open accordingly.
    @Test fun answerAndUserTextAreSelectableFromTheMenu() {
        store.append("s", Turn("今天天气怎么样", "上海今天**晴**,详见 [天气](https://example.com)", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNode(hasText("上海今天", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer"))).assertDoesNotExist()
        compose.onNodeWithText("上海今天", substring = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").assert(hasText("选择文字")).performClick()
        compose.waitForIdle()
        val answer = compose.onNode(hasText("上海今天", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer")))
        answer.assertExists()
        answer.performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer").assertIsNotDisplayed()

        compose.onNodeWithText("今天天气怎么样").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("今天天气怎么样") and hasAnyAncestor(hasTestTag("selectable-user"))).assertExists()
    }

    // Long press the answer and select "Quote": a small quotation bar will appear above the input box.
    // Write a sentence and send it. The quotation will be handed over to the task along with this
    // sentence. The small bar will disappear; the quotation bar will be displayed above the bubble.
    @Test fun longPressQuoteThenSend() {
        store.append("q", Turn("今天天气怎么样", "上海今天**晴**", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("q") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("上海今天晴").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-copy").assert(hasText("复制"))
        compose.onNodeWithTag("message-menu-quote").assert(hasText("引用")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("quote-chip").assertExists()

        compose.onNodeWithTag("composer-field").performTextReplacement("那明天呢")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals(listOf("> [Quoting your earlier reply]\n> 上海今天晴\n\n那明天呢"), started)
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
        compose.onNodeWithTag("sent-quote").assertExists()

        // × Remove citation
        compose.runOnUiThread { vm.quote(QuoteMessage.From.You, "今天天气怎么样") }
        compose.onNodeWithContentDescription("去掉引用").performClick()
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
    }
}
