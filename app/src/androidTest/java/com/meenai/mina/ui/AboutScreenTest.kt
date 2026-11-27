package com.meenai.mina.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ClipboardManager
import com.meenai.mina.about.CheckStatus
import com.meenai.mina.about.HarnessLinks
import com.meenai.mina.about.ReleaseInfo
import com.meenai.mina.about.UpdateState
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule val compose = createComposeRule()

    private val r020 = ReleaseInfo(
        "0.2.0", "Mina 0.2.0", "### 新增\n- 附件面板", "2026-11-05",
        "https://github.com/wangzan101/Mina/releases/tag/v0.2.0", "https://github.com/x/mina-0.2.0.apk",
    )

    private class Calls {
        val urls = mutableListOf<String>()
        var checks = 0
        var saves = 0
        var licenses = 0
    }

    private val sampleDiagnostics = "Mina 诊断信息 / Mina diagnostics\n\n[App]\nversion: 0.1.0 (build abc1234)\n"

    private fun about(update: UpdateState = UpdateState()): Calls {
        val calls = Calls()
        compose.setContent {
            MinaTheme {
                AboutScreen(
                    versionName = "0.1.0", build = "abc1234", beta = true, update = update, issueUrl = "issue://prefilled",
                    onCheck = { calls.checks++ }, onOpenUrl = { calls.urls += it },
                    onSaveQr = { calls.saves++; true }, onOpenLicense = { calls.licenses++ }, onBack = {},
                    diagnostics = { sampleDiagnostics },
                )
            }
        }
        return calls
    }

    private fun scrollTo(text: String) {
        if (compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        }
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun showsHeroRowsNoteAndFooter() {
        about()
        compose.onNodeWithText("Mina").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.TAGLINE).assertIsDisplayed()
        compose.onNodeWithText("版本 0.1.0").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("· 构建 abc1234").assertIsDisplayed()
        for (t in listOf("检查更新", "更新记录", "反馈问题", "开源仓库", "使用说明与参与方式", AboutCopy.DATA_NOTE, AboutCopy.AUTHOR)) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        // The license is only in the footer, no longer a separate line
        compose.onAllNodesWithText("开源许可").assertCountEquals(0)
    }

    @Test
    fun externalRowsOpenLinks() {
        val calls = about()
        for (t in listOf("开源仓库", "使用说明与参与方式")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf(HarnessLinks.REPO, HarnessLinks.README), calls.urls)
    }

    @Test
    fun footerOpensLicense() {
        val calls = about()
        compose.onNodeWithTag("about-license").performScrollTo().performClick()
        assertEquals(1, calls.licenses)
    }

    @Test
    fun feedbackSheetHasTwoOptions() {
        val calls = about()
        compose.onNodeWithText("反馈问题").performClick()
        compose.awaitOne(AboutCopy.FEEDBACK_SHEET_SUB)
        compose.onAllNodesWithTag("feedback-option").assertCountEquals(2)
        compose.onNodeWithText("复制诊断信息").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.FEEDBACK_GITHUB).performClick()
        compose.waitForIdle()
        assertEquals(listOf("issue://prefilled"), calls.urls)
    }

    /** "Copy diagnostic information" first opens the preview: the entire text is visible, click "Copy" to enter the clipboard. */
    @Test
    fun feedbackDiagnosticsPreviewsThenCopies() {
        about()
        compose.onNodeWithText("反馈问题").performClick()
        compose.awaitOne("复制诊断信息").performClick()
        compose.awaitOne("不包含 API Key、聊天内容和屏幕内容")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(sampleDiagnostics).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diagnostics-text").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-share").assertExists()
        compose.onNodeWithTag("diagnostics-copy").performClick()
        compose.waitForIdle()
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
            clip = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(sampleDiagnostics, clip)
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    @Test
    fun checkRowStartsCheckWhenNoUpdate() {
        val calls = about(UpdateState(latest = r020.copy(version = "0.1.0")))
        compose.onNodeWithText(AboutCopy.UP_TO_DATE).assertIsDisplayed()
        compose.onNodeWithText("检查更新").performClick()
        assertEquals(1, calls.checks)
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun checkingShowsSpinner() {
        about(UpdateState(status = CheckStatus.Checking))
        compose.onNodeWithTag("update-spinner").assertExists()
    }

    @Test
    fun offlineShowsReason() {
        about(UpdateState(status = CheckStatus.Failed))
        compose.onNodeWithText(AboutCopy.OFFLINE).assertIsDisplayed()
    }

    @Test
    fun updateRowOpensUpdateSheet() {
        val calls = about(UpdateState(latest = r020, update = r020, status = CheckStatus.Done))
        compose.onNodeWithText("0.2.0 可更新").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("检查更新").performClick()
        compose.awaitOne("发现新版本 0.2.0")
        compose.onNodeWithText("当前 0.1.0 Beta · 2026-11-05 发布").assertExists()
        compose.onNodeWithText("以后再说").assertExists()
        compose.onNodeWithText("下载更新").assertExists()
        compose.waitForIdle()
        assertEquals(0, calls.checks)
        assertTrue(calls.urls.isEmpty())
    }

    @Test
    fun updateWithoutApkStillOpensReleasePage() {
        val r = r020.copy(apkUrl = null)
        val calls = about(UpdateState(latest = r, update = r))
        compose.onNodeWithText("检查更新").performClick()
        compose.awaitOne("从 GitHub 下载").performClick()
        assertEquals(listOf(r.pageUrl), calls.urls)
    }

    @Test
    fun downloadedUpdateOffersInstallAndDelete() {
        var installs = 0
        var deletes = 0
        compose.setContent {
            MinaTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.meenai.mina.about.ApkDownloadState(com.meenai.mina.about.DownloadPhase.Ready),
                        false, null, {}, { installs++ }, { deletes++ })
                }
            }
        }
        compose.onNodeWithText("安装更新").performClick()
        compose.onNodeWithText("删除下载").performClick()
        assertEquals(1, installs)
        assertEquals(1, deletes)
    }

    @Test
    fun downloadingShowsProgressAndCanBeCancelled() {
        var deletes = 0
        compose.setContent {
            MinaTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.meenai.mina.about.ApkDownloadState(com.meenai.mina.about.DownloadPhase.Downloading, 50, 100),
                        false, null, {}, {}, { deletes++ })
                }
            }
        }
        compose.onNodeWithText("正在下载 50%").assertIsNotEnabled()
        compose.onNodeWithText("删除下载").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun failedDownloadCanBeRetried() {
        var retries = 0
        compose.setContent {
            MinaTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.meenai.mina.about.ApkDownloadState(com.meenai.mina.about.DownloadPhase.Failed),
                        false, null, { retries++ }, {}, {})
                }
            }
        }
        compose.onNodeWithText("重新下载").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun changelogSheetShowsLatestNotes() {
        val calls = about(UpdateState(latest = r020))
        compose.onNodeWithText("更新记录").performClick()
        compose.awaitOne(AboutCopy.CHANGELOG_ALL)
        compose.onNodeWithText("0.2.0 · 2026-11-05").assertExists()
        compose.onNodeWithText(AboutCopy.CHANGELOG_ALL).performClick()
        compose.waitForIdle()
        assertEquals(listOf(HarnessLinks.RELEASES), calls.urls)
    }

    /** There are two lines in the bottom row of the sidebar: Settings on the left, About Mina on the right (the red dot is on the far right); About has a red dot when there is a new version. */
    @Test
    fun drawerFooterHasSettingsAndAbout() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MinaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {},
                    onSettings = { calls += "settings" }, onOpen = {},
                    onAbout = { calls += "about" }, aboutDot = true,
                )
            }
        }
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("关于 Mina").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        val footer = compose.onNodeWithTag("drawer-footer").getUnclippedBoundsInRoot()
        // Measuring the text itself (unmerged tree): The merged clickable line node is wider than the
        // text, and the left and right halves cannot be determined by measuring it.
        val settings = compose.onNodeWithText("设置", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val about = compose.onNodeWithText("关于 Mina", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val dot = compose.onNodeWithTag("update-dot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val mid = (footer.left + footer.right) / 2
        assertTrue(settings.right < mid)
        assertTrue(about.left > mid)
        // Regarding which item is on the right side: the red dot is only separated from the right side
        // of the row by two layers of padding (row 12dp + item 12dp)
        assertTrue(footer.right - dot.right <= 25.dp)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("关于 Mina").performClick()
        assertEquals(listOf("settings", "about"), calls)
    }

    @Test
    fun drawerFooterNoDotWithoutUpdate() {
        compose.setContent {
            MinaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("关于 Mina").assertIsDisplayed()
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    /** Set the "About Mina" line on the homepage: If there is a new version, write "x can be updated" with a red dot. */
    @Test
    fun settingsAboutRowShowsUpdate() {
        compose.setContent {
            MinaTheme {
                SettingsContent(
                    status = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true),
                    androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                    versionName = "0.1.0", updateVersion = "0.2.0",
                )
            }
        }
        scrollTo("0.2.0 可更新")
        compose.onNodeWithText("0.2.0 可更新").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
    }
}
