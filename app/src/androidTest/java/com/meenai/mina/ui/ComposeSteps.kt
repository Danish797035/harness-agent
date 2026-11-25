package com.meenai.mina.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.R
import com.meenai.mina.i18n.AppLanguage

/**
 * Several steps of interface testing do not rely on the state left by the previous test: the activity of
 * each test is newly opened, but it takes time for the windows (activities, pop-up windows) of the
 * previous test to exit. Before clicking, wait until only one of the test points actually appears on the
 * screen. The one you click will not be in the old window or has not been placed yet.
 */
internal fun ComposeContentTestRule.awaitOne(text: String, inDialog: Boolean = false, timeoutMs: Long = 5_000): SemanticsNodeInteraction {
    val matcher = if (inDialog) hasText(text) and hasAnyAncestor(isDialog()) else hasText(text)
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    return onNode(matcher, useUnmergedTree = true)
}

/** Buttons in pop-up windows: Wait for the pop-up window to appear before clicking it. */
internal fun ComposeContentTestRule.clickInDialog(text: String) {
    awaitOne(text, inDialog = true).performClick()
    waitForIdle()
}

/**
 * Write [text] in the input box of the dialog page and click Send. Send the click action of the button
 * without pressing the screen coordinate point: when writing, the input box gets the focus, the input
 * method starts to pop up, and the input field moves up with the keyboard; the click of the coordinate
 * point takes the position during the animation, which often falls outside the input field - it is not
 * clicked to send, and the keyboard is put away as "clicked outside the input area", the words are left
 * in the box, and the task does not start. The button name is based on the current interface language of
 * the App.
 */
internal fun ComposeContentTestRule.typeAndSend(text: String) {
    onNode(hasSetTextAction()).performTextInput(text)
    onNodeWithContentDescription(appString(R.string.chat_send)).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** Text based on the current interface language of the app. */
internal fun appString(id: Int): String =
    AppLanguage.resources(InstrumentationRegistry.getInstrumentation().targetContext).getString(id)
