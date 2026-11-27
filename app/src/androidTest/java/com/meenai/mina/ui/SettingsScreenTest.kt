package com.meenai.mina.ui

import com.meenai.mina.core.shell.HelperRestart
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.Problem
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.task.ParallelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import org.junit.runner.RunWith
import com.meenai.mina.core.cap.phone.OperatingPace

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun home(
        status: SystemStatus = ready,
        mainModel: String? = "DeepSeek",
        onProblem: (Problem) -> Unit = {},
        opened: MutableList<String> = mutableListOf(),
        showDeveloper: Boolean = true,
        usageTokens: Long? = 123_000,
    ) {
        compose.setContent {
            SettingsContent(
                status = status, androidRelease = "14", mainModel = mainModel, onBack = {}, onProblem = onProblem,
                onOpenModels = { opened += "models" }, usageTokens = usageTokens, onOpenUsage = { opened += "usage" }, onOpenPhoneOps = { opened += "phone" }, onOpenDuringWork = { opened += "work" },
                capsuleMode = CapsuleMode.DuringTasks, onOpenCapsule = { opened += "capsule" }, onOpenNotifications = { opened += "notifications" },
                onOpenMemory = { opened += "memory" },
                webSitesCount = 3, onOpenWebSites = { opened += "web" },
                sandbox = SandboxStatus.Ready(network = false), onOpenSandbox = { opened += "sandbox" },
                routes = 8, onOpenRoutes = { opened += "routes" }, onOpenRecovery = { opened += "recovery" },
                showDeveloper = showDeveloper, onOpenDeveloper = { opened += "dev" },
                versionName = "0.1.0", onOpenAbout = { opened += "about" },
            )
        }
    }

    /** The page is a scrolling list: bring the target row into the viewport before asserting or clicking. */
    private fun scrollTo(text: String, substring: Boolean = false) {
        if (compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = substring))
        }
        if (substring) {
            compose.onNodeWithText(text, substring = true).performScrollTo()
        } else {
            compose.awaitOne(text).performScrollTo()
        }
        compose.waitForIdle()
    }

    @Test
    fun homeShowsGroupsStatusAndValues() {
        home()
        compose.onNodeWithText("一切就绪").assertIsDisplayed()
        compose.onNodeWithText("基本").assertIsDisplayed()
        // The home page does not fit on one screen (it will be longer after adding the "Language" line):
        // scroll each value into the viewport before viewing. In order, they are model, mobile phone
        // operation, notification, website login, operation route, computing environment, and about
        for (v in listOf("DeepSeek", "可用", "正常", "3 个", "8 条", "已就绪", "0.1.0")) {
            scrollTo(v)
            compose.onNodeWithText(v).assertIsDisplayed()
        }
    }

    @Test
    fun homeRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        home(opened = opened)
        for (t in listOf("模型", "Token 用量", "手机操作", "干活时", "悬浮胶囊", "通知", "记忆与技能", "网站登录", "文件与计算环境", "记住的操作路线", "恢复与修复", "开发者选项", "关于 Mina")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf("models", "usage", "phone", "work", "capsule", "notifications", "memory", "web", "sandbox", "routes", "recovery", "dev", "about"), opened)
        compose.onNodeWithTag("switch-notice-toggle").assertDoesNotExist()
    }

    /** For "Token Usage", write the total of the past 7 days; if there is no usage, write "Not Yet"; if it has not yet been calculated, this line will not be displayed. */
    @Test
    fun usageRowShowsWeekTotal() {
        var tokens by mutableStateOf<Long?>(123_000)
        compose.setContent {
            SettingsContent(
                status = ready, androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                usageTokens = tokens,
            )
        }
        compose.onNodeWithText("近 7 天 12.3 万").assertIsDisplayed()
        tokens = 0
        compose.awaitOne("还没有").assertIsDisplayed()
        tokens = null
        compose.waitForIdle()
        compose.onAllNodesWithText("Token 用量").assertCountEquals(0)
    }

    /** Release builds don't show developer options. */
    @Test
    fun developerRowHiddenInRelease() {
        home(showDeveloper = false)
        compose.onAllNodesWithText("开发者选项").assertCountEquals(0)
    }

    /** When there is a problem, the status card changes to a prompt, and the button hands the problem to onProblem. */
    @Test
    fun statusCardTurnsIntoFixPrompt() {
        val clicked = mutableListOf<Problem>()
        home(
            SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
            onProblem = { clicked += it },
        )
        compose.onNodeWithText("无线调试关着").assertIsDisplayed()
        compose.onAllNodesWithText("一切就绪").assertCountEquals(0)
        compose.onNodeWithText("需要处理").assertIsDisplayed()
        compose.awaitOne("去打开").performClick()
        assertEquals(listOf<Problem>(Problem.PhoneHelper(BuiltinShellStatus.WirelessDebuggingOff)), clicked)
    }

    @Test
    fun noModelAndOldSystem() {
        home(SystemStatus(30, BuiltinShellStatus.Running, AccessibilityState.EnabledNotRunning, notificationsAllowed = false), mainModel = null)
        compose.onNodeWithText("手机操作不可用").assertIsDisplayed()
        compose.onNodeWithText("未设置").assertIsDisplayed()
        compose.onNodeWithText("不支持").assertIsDisplayed()
        // The operation of mobile phones below Android 12 is unavailable: recovery and repair will not
        // be provided
        compose.onAllNodesWithText("恢复与修复").assertCountEquals(0)
    }

    @Test
    fun phoneOpsPageShowsPermissionRowsAndPairing() {
        var repaired = 0
        compose.setContent {
            PhoneOpsScreen(
                SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
                "14", onProblem = {}, onOpenNotifications = {}, onRepair = { repaired++ }, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("无障碍服务").assertIsDisplayed()
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.onNodeWithText("无线调试已关").assertIsDisplayed()
        compose.onNodeWithText("正常").assertIsDisplayed()
        compose.onNodeWithText("安卓 14").assertIsDisplayed()
        compose.onNodeWithText("满足").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.usageNote(HelperRestart.None)).assertExists()
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performClick()
        assertEquals(1, repaired)
    }

    /** The "Notification" row on the mobile phone operation page displays the overall status. Click it to enter the notification page. */
    @Test
    fun phoneOpsNotificationRowOpensNotifications() {
        var opened = 0
        compose.setContent {
            PhoneOpsScreen(
                ready.copy(notificationsAllowed = false), "14", onProblem = {}, onOpenNotifications = { opened++ },
                onRepair = {}, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("已关闭").assertIsDisplayed()
        compose.onNodeWithText("通知").performClick()
        assertEquals(1, opened)
    }

    /** Notification page: When the master switch is enabled, a channel is turned down, or the progress is turned off, each row displays the status. Click each row to hand it over to onFix. */
    @Test
    fun notificationsPageShowsChannelsAndOpensThem() {
        val fixes = mutableListOf<Fix>()
        compose.setContent {
            NotificationsScreen(
                ready.copy(attentionImportance = 4, resultImportance = 2, progressImportance = 0), onFix = { fixes += it }, onBack = {},
            )
        }
        compose.onNodeWithText("已允许").assertIsDisplayed()
        compose.onNodeWithText("需要你接管、确认、回答时").assertIsDisplayed()
        compose.onNodeWithText("会弹出").assertIsDisplayed()
        compose.onNodeWithText("只进通知栏").assertIsDisplayed()
        compose.onNodeWithText("已关闭").assertIsDisplayed()
        compose.onNodeWithText(StatusCopy.NOTIF_FOOTER).assertExists()
        compose.onNodeWithText("需要你处理").performClick()
        compose.onNodeWithText("任务结果").performClick()
        compose.onNodeWithText("任务进度").performClick()
        assertEquals(listOf(Fix.OpenAttentionChannel, Fix.OpenResultChannel, Fix.OpenProgressChannel), fixes)
    }

    /** The main switch is off: "Turn it on" and all three channels are shown as closed. */
    @Test
    fun notificationsPageWhenAppOff() {
        val fixes = mutableListOf<Fix>()
        compose.setContent { NotificationsScreen(ready.copy(notificationsAllowed = false), onFix = { fixes += it }, onBack = {}) }
        compose.onAllNodesWithText("已关闭").assertCountEquals(3)
        compose.onNodeWithText("去打开").performClick()
        assertEquals(listOf(Fix.AllowNotifications), fixes)
    }

    @Test
    fun unpairAsksFirst() {
        var unpaired = 0
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onOpenNotifications = {}, onRepair = {}, onUnpair = { unpaired++ }, onBack = {}) }
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR).performClick()
        assertEquals(0, unpaired)
        compose.clickInDialog(PhoneSetupCopy.UNPAIR_CONFIRM)
        assertEquals(1, unpaired)
    }

    /** When the parallel switch is turned on but the device does not support it: this line is grayed out, the reason is stated, and no addition or subtraction is given. */
    @Test
    fun parallelDisabledShowsReason() {
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(2, ParallelCapability.detect(33, lowRam = false, userMax = 2, secondDisplayFailed = false)),
                onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithText("同时进行的任务").assertIsDisplayed()
        compose.onNodeWithText("安卓 14 以上才支持同时进行多个任务").assertIsDisplayed()
        compose.onNodeWithText("1 个").assertIsDisplayed()
        compose.onNodeWithText("+").assertDoesNotExist()
    }

    @Test
    fun parallelMaxAdjustable() {
        val picked = mutableListOf<Int>()
        var max by mutableStateOf(2)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(max, ParallelCapability.detect(34, lowRam = false, userMax = max, secondDisplayFailed = false)),
                onParallelMax = { max = it; picked += it }, onBack = {},
            )
        }
        compose.onNodeWithText("屏幕保持常亮").assertIsDisplayed()
        compose.onNodeWithText("2 个").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 个").assertIsDisplayed()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf(3, 2), picked)
    }

    @Test
    fun duringWorkWithoutParallelHasNoParallelRow() {
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {})
        }
        compose.onNodeWithText("被操作的 App 静音").assertIsDisplayed()
        compose.onAllNodesWithText("同时进行的任务").assertCountEquals(0)
    }

    /** The two items "While working" are on by default, and the user can turn them off: clicking the switch will call back the new value. */
    @Test
    fun duringWorkTogglesCanBeTurnedOff() {
        val changes = mutableListOf<String>()
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = { changes += "screen:$it" },
                muteTarget = true, onMuteTarget = { changes += "mute:$it" },
                parallel = null, onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("work-keep-screen-on").performClick()
        compose.onNodeWithTag("work-mute-target").performClick()
        assertEquals(listOf("screen:false", "mute:false"), changes)
    }

    /** When working › Operation rhythm: choose one of three: fast, standard (default), human-like, click to call back the new value. */
    @Test
    fun operatingPaceIsOneChoiceOfThree() {
        val picked = mutableListOf<OperatingPace>()
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {}, onOperatingPace = { picked += it })
        }
        compose.onNodeWithText("操作节奏").assertIsDisplayed()
        compose.onNodeWithText("快").assertIsDisplayed()
        compose.onNodeWithText("标准（默认）").assertIsDisplayed()
        compose.onNodeWithText("像人").performClick()
        compose.onNodeWithText("快").performClick()
        assertEquals(listOf(OperatingPace.HUMAN, OperatingPace.FAST), picked)
    }

    /** The current selection is written on the "Suspended Capsule" line on the home page. */
    @Test
    fun capsuleRowShowsCurrentChoice() {
        compose.setContent {
            SettingsContent(status = ready, androidRelease = "14", mainModel = "m", onBack = {}, onProblem = {}, onOpenModels = {}, capsuleMode = CapsuleMode.Always)
        }
        scrollTo("一直显示")
        compose.awaitOne("一直显示").assertIsDisplayed()
    }

    /** Choose one of the three sub-pages of the suspended capsule and select the callback new value; the footer indicates that the accessibility service is used. */
    @Test
    fun capsulePageOffersThreeChoices() {
        var mode by mutableStateOf(CapsuleMode.DuringTasks)
        val picked = mutableListOf<CapsuleMode>()
        compose.setContent { CapsuleScreen(mode, onMode = { picked += it; mode = it }, onBack = {}) }
        compose.awaitOne("有任务时显示").assertIsDisplayed()
        scrollTo("关闭")
        compose.onNodeWithText("关闭").assertIsDisplayed()
        // The footnotes are below the three options. If you have a short screen or a large font size,
        // you have to scroll to see them.
        scrollTo("无障碍服务", substring = true)
        compose.onNodeWithText("无障碍服务", substring = true).assertIsDisplayed()
        scrollTo("一直显示")
        compose.onNodeWithText("一直显示").performClick()
        scrollTo("关闭")
        compose.onNodeWithText("关闭").performClick()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), picked)
    }

    /** "Clear all" first ask; after confirming, it will be cleared, the number of items will become 0, and the button will disappear; if canceled, it will not move. */
    @Test
    fun clearRoutesAsksThenEmpties() {
        var routes by mutableStateOf(3)
        var cleared = 0
        compose.setContent { RoutesScreen(routes, onClear = { cleared++; routes = 0 }, onBack = {}) }
        compose.onNodeWithText("已记住的操作路线").assertIsDisplayed()
        compose.onNodeWithText("3 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("全部清除").performClick()
        compose.clickInDialog("取消")
        assertEquals(0, cleared)
        compose.onNodeWithText("全部清除").performClick()
        compose.awaitOne("清除全部已记住的操作路线?", inDialog = true).assertIsDisplayed()
        compose.clickInDialog("清除")
        assertEquals(1, cleared)
        compose.onNodeWithText("0 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("全部清除").assertDoesNotExist()
    }

    /** Developer options: The eight switches are divided into two groups: speed and experiment. Click to open the corresponding one. */
    @Test
    fun developerOptionsHasTwoGroupsAndToggles() {
        val changed = mutableListOf<SpeedFlags>()
        var flags by mutableStateOf(SpeedFlags.OFF)
        compose.setContent { DeveloperOptionsScreen(flags, onSpeed = { flags = it; changed += it }, onBack = {}) }
        compose.onNodeWithText("速度").assertIsDisplayed()
        for (t in listOf("先读元素，必要时看图", "操作后带回屏幕变化", "有把握时连做几步", "直接打开要去的页面", "简单步骤交给轻量模型")) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        scrollTo("实验")
        compose.onNodeWithText("实验").assertIsDisplayed()
        scrollTo("照着上次的做法做")
        compose.onNodeWithText("照着上次的做法做").performClick()
        assertEquals(listOf(SpeedFlags(replay = true)), changed)
        scrollTo("不同对话同时进行")
        scrollTo("长任务的摘要分条写")
        compose.onNodeWithText("长任务的摘要分条写").assertIsDisplayed()
    }

    /** "Autonomous Mode" is at the front of the developer options and is off by default; click the switch or switch the entire row. */
    @Test
    fun autonomousSwitchComesFirstAndToggles() {
        val toggled = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        compose.setContent { DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, autonomous = on, onAutonomous = { toggled += it; on = it }) }
        compose.onNodeWithText(AutonomyCopy.GROUP).assertIsDisplayed()
        compose.onNodeWithText(AutonomyCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("dev-autonomous").assertIsOff().performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOn()
        compose.onNodeWithText(AutonomyCopy.TITLE).performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOff()
        assertEquals(listOf(true, false), toggled)
    }

    /** "View image positioning has been verified" moved to the developer options: it will only appear if there is a view model, click to switch. */
    @Test
    fun groundingSwitchShowsOnlyWithVisionModel() {
        val toggled = mutableListOf<Boolean>()
        var grounding by mutableStateOf<GroundingSetting?>(null)
        compose.setContent {
            DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, grounding = grounding, onGrounding = { toggled += it })
        }
        compose.onNodeWithText(GroundingCopy.TITLE).assertDoesNotExist()
        grounding = GroundingSetting("qwen-vl-max", verified = false)
        scrollTo(GroundingCopy.TITLE)
        compose.onNodeWithText(GroundingCopy.TITLE).performClick()
        assertEquals(listOf(true), toggled)
    }

    private class Calls { var reset = 0; val network = mutableListOf<Boolean>() }

    private fun advanced(status: SandboxStatus, network: Boolean = false, isolationIssue: Boolean = false): Calls {
        val c = Calls()
        compose.setContent {
            Column {
                AdvancedSection(
                    status = status, networkAllowed = network, isolationIssue = isolationIssue,
                    onNetworkChange = { c.network += it }, onReset = { c.reset++ },
                )
            }
        }
        return c
    }

    @Test
    fun unsupportedHidesAdvanced() {
        advanced(SandboxStatus.Unsupported("此设备不支持(需要 arm64)"))
        compose.onAllNodesWithText(SandboxCopy.NETWORK_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText("重置运行环境").assertCountEquals(0)
    }

    @Test
    fun advancedHasNoInstallOrSandboxWording() {
        advanced(SandboxStatus.NotInstalled)
        compose.onNodeWithText(SandboxCopy.NETWORK_TITLE).assertIsDisplayed()
        compose.onNodeWithText("重置运行环境").assertIsDisplayed()
        for (t in listOf("沙箱", "安装沙箱", "软件源", "清空工作区")) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
    }

    @Test
    fun resetAsksFirst() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithText("重置运行环境").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText(SandboxCopy.RESET_BODY).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText("重置运行环境").performClick()
        compose.onNodeWithText("重置").performClick()
        assertEquals(1, c.reset)
    }

    @Test
    fun isolationIssueShown() {
        advanced(SandboxStatus.Ready(network = true), network = true, isolationIssue = true)
        compose.onNodeWithText(SandboxCopy.NETWORK_EXPLAIN).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.ISOLATION_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun networkToggleReportsChange() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithTag("sandbox-network").performClick()
        assertTrue(c.network == listOf(true))
    }

    /** Used space: one line in the workspace for each conversation; if there are tasks running, indicate that they cannot be cleared or cannot be clicked; clearing must be confirmed first, and then called back after confirmation. */
    @Test
    fun usageClearsAWorkspaceOnlyAfterConfirming() {
        val usage = com.meenai.mina.cap.sandbox.SandboxUsage(
            runtime = 300L shl 20, skills = 2L shl 20,
            workspaces = listOf(
                com.meenai.mina.cap.sandbox.WorkspaceUsage("c-a", "a", "查快递", 5L shl 20, busy = true),
                com.meenai.mina.cap.sandbox.WorkspaceUsage("c-b", "b", "整理表格", 1L shl 20, busy = false),
                com.meenai.mina.cap.sandbox.WorkspaceUsage("c-gone", null, null, 1024, busy = false),
            ),
        )
        val cleared = mutableListOf<String>()
        var all = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdvancedSection(
                    status = SandboxStatus.Ready(network = false), networkAllowed = false, isolationIssue = false,
                    onNetworkChange = {}, onReset = {}, usage = usage,
                    onClearWorkspace = { cleared += it.dirName }, onClearAll = { all++ },
                )
            }
        }
        compose.onNodeWithText(SandboxCopy.USAGE_TITLE).assertExists()
        compose.onNodeWithText("已删除的对话").assertExists()
        compose.onNodeWithText("这个对话有任务在跑，暂时不能清理").assertExists()
        compose.onNodeWithText("查快递").performScrollTo().performClick()
        compose.onAllNodesWithText(SandboxCopy.CLEAR_TITLE).assertCountEquals(0)
        compose.onNodeWithText("整理表格").performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_TITLE).assertIsDisplayed()
        assertEquals(emptyList<String>(), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(listOf("c-b"), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL).performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL_BODY).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(1, all)
    }
}
