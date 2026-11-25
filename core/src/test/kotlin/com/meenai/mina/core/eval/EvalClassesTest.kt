package com.meenai.mina.core.eval

import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several types of errors were checked during the evaluation (the original words in the real machine
 * records and the text on the web page read): entries are arranged according to the number of lines,
 * repeating the words of the question, taking over after the login wall, repeated platform descriptions,
 * and the app that comes with the mobile phone does not have a package name.
 */
class EvalClassesTest {
    private fun obs(answer: String, screens: List<String>, goal: String = "", outcome: String = "succeeded", barrier: String? = null) =
        Observed(outcome, answer, screens, null, emptyMap(), emptyMap(), 1, 1000, 0, 0, 0, null, goal = goal, takeoverBarrier = barrier)

    private val doubanPage = listOf(
        "肖申克的救赎 / The Shawshank Redemption / 月黑高飞(港) / 刺激1995(台) [可播放]\n9.7 3352986人评价\n" +
            "霸王别姬 / 再见，我的妾 / Farewell My Concubine [可播放]\n9.6 2461519人评价\n" +
            "泰坦尼克号 / Titanic / 铁达尼号(港 / 台) [可播放]\n9.5 2539069人评价\n" +
            "阿甘正传 / Forrest Gump / 福雷斯特·冈普 [可播放]\n9.5 2465285人评价\n" +
            "千与千寻 / 千と千尋の神隠し / 神隐少女(台) / 千与千寻的神隐\n9.4 2577706人评价",
    )
    private val doubanGoal = "用网页打开豆瓣电影 Top250(https://movie.douban.com/top250),告诉我排名前五的电影名和评分。只看,不要登录、不要标记想看"

    // Actual test web-douban-top-five: Both movies scored 9.5, and only 4 items were counted based on
    // different values.
    @Test fun itemsAreCountedPerAnswerLine() {
        val answer = "豆瓣电影 Top250 排名前五的电影名和评分：\n\n1. 肖申克的救赎 —— 9.7 分\n2. 霸王别姬 —— 9.6 分\n3. 泰坦尼克号 —— 9.5 分\n" +
            "4. 阿甘正传 —— 9.5 分\n5. 千与千寻 —— 9.4 分\n\n依据:豆瓣电影 Top250 网页正文,排名 1-5 依次显示「肖申克的救赎 9.7」"
        assertTrue(Checks.grounded(answer, doubanPage, 5, doubanGoal))
        // Writing the same line twice does not count.
        val repeated = "1. 泰坦尼克号 —— 9.5 分\n1. 泰坦尼克号 —— 9.5 分"
        assertFalse(Checks.grounded(repeated, doubanPage, 2, doubanGoal))
        // The value of each row should still be on the screen: the compiled score does not count
        assertFalse(Checks.grounded(answer.replace("9.4 分", "9.1 分"), doubanPage, 5, doubanGoal))
    }

    // The number on the screen must be a complete word: the number included in the hash and id will not
    // be used to prove the answer.
    @Test fun screenNumbersMustBeWholeTokens() {
        assertFalse(Checks.grounded("- 评分:899", listOf("评分", "img d50735b899e51bc8a.jpg"), 1))
        assertTrue(Checks.grounded("- 评分:899", listOf("评分 899"), 1))
        assertTrue(Checks.grounded("- 存储:128GB", listOf("存储 128GB"), 1))
        // Actual test settings-kernel-version: The number sandwiched between letters is checked in one
        // word.
        val kernel = listOf("内核版本", "5.15.194-android13-8-00019-gf4321180a397-ab15212794")
        assertTrue(Checks.grounded("内核版本号为 5.15.194-android13-8-00019-gf4321180a397-ab15212794", kernel, 1))
        assertFalse(Checks.grounded("内核版本号为 5.15.194-android14-8-00019-gf4321180a397-ab15212794", kernel, 1))
    }

    private val forecast = listOf(
        "25日（明天） 小雨 12℃/9℃ <3级",
        "25日（明天） 阴 23℃/17℃ 3-4级转<3级",
        "25日（明天） 多云转晴 30℃/20℃ <3级",
    )
    private val citiesGoal = "用网页查中国天气网(weather.com.cn)上北京、上海、广州三个城市明天的最高气温,分别列出来"

    // Actual measurement web-weather-three-cities: The "highest" in "Beijing: Maximum 12℃" is a
    // retelling question, and there is no such word on the screen; the date converted to tomorrow is
    // checked according to the day of the task
    @Test fun labelWordsThatOnlyComeFromTheGoalAreIgnored() {
        val answer = "中国天气网(weather.com.cn)明天(2026年10月25日)三城市最高气温如下:\n\n• 北京:最高 12℃(明天 小雨,12℃/9℃)\n" +
            "• 上海:最高 23℃(明天 阴,23℃/17℃)\n• 广州:最高 30℃(明天 多云转晴,30℃/20℃)\n\n依据:中国天气网 7 天预报页面"
        val day = java.time.LocalDate.of(2026, 10, 24)
        assertTrue(Checks.grounded(answer, forecast, 3, citiesGoal, day))
        // The compiled temperature is still blocked; whether the value matches the city will be judged
        // by the verification model.
        assertFalse(Checks.grounded(answer.replace("北京:最高 12℃", "北京:最高 15℃"), forecast, 3, citiesGoal, day))
    }

    private fun readTask(accept: List<String>? = null, category: EvalCategory = EvalCategory.Read, requires: List<String> = emptyList()) =
        EvalTask(id = "t", goal = "g", apps = emptyList(), category = category, requires = requires, expect = Expect.Succeeded,
            checks = listOf(Check.AnswerGrounded(2)), acceptTakeoverOn = accept)

    // Actual measurement meituan-hotpot-ratings, xhs-search-titles: The search is blocked by the login
    // wall and is left to the user to log in. Listing it in a separate category does not count as a
    // success
    @Test fun takeoverAfterLoginWallIsItsOwnBucket() {
        val handed = obs("", emptyList(), outcome = "takeover", barrier = "login")
        val s = Scoring.score(readTask(), handed)
        assertEquals("login", s.handedOver)
        assertFalse(s.passed)
        // Those who have no evidence on the screen (no takeoverBarrier), other obstacles, do not
        // recognize the questions, write similar questions, and declare that they are logged in do not
        // fall into this category.
        assertNull(Scoring.score(readTask(), handed.copy(takeoverBarrier = null)).handedOver)
        assertNull(Scoring.score(readTask(), handed.copy(takeoverBarrier = "captcha")).handedOver)
        assertNull(Scoring.score(readTask(accept = emptyList()), handed).handedOver)
        assertNull(Scoring.score(readTask(category = EvalCategory.Write), handed).handedOver)
        assertNull(Scoring.score(readTask(requires = listOf("signedIn:com.example.shop")), handed).handedOver)
        assertNull(Scoring.score(readTask(requires = listOf("site:example.com")), handed).handedOver)
        assertEquals("captcha", Scoring.score(readTask(accept = listOf("captcha")), handed.copy(takeoverBarrier = "captcha")).handedOver)
    }

    private var t = 0L
    private fun screen(vararg names: String) = com.meenai.mina.core.testing.legacyScreenText(++t, 1, "com.example.shop", names.toList())
    private fun reply(tool: String, args: String = "{}") = RunEvent.ModelReply(++t, 1, "", tool, args, null, null, null)

    // Only recognize the obstacles that are immediately adjacent to the takeover and are visible on the
    // screen
    @Test fun takeoverBarrierNeedsScreenEvidenceRightBeforeTakeover() {
        val wall = screen("欢迎登录美团", "请输入密码", "登录", "验证码登录")
        val ev = listOf(wall, reply("handle_obstacle"), RunEvent.Barrier(++t, "login"), reply("hand_to_user"), RunEvent.State(++t, "waiting_takeover", "请登录"))
        assertEquals("login", RunFacts.takeoverBarrier(ev, TOOLS))
        // There is just normal content on the screen: the model says it is a login wall but does not
        // recognize it.
        assertNull(RunFacts.takeoverBarrier(listOf(screen("火锅", "评分 4.8")) + ev.drop(1), TOOLS))
        // After reporting the obstacle and doing something else before taking over: This is not the
        // reason for the takeover
        assertNull(RunFacts.takeoverBarrier(ev.take(3) + reply("phone_tap") + ev.drop(3), TOOLS))
        // Didn't take over at all
        assertNull(RunFacts.takeoverBarrier(ev.take(3), TOOLS))
    }

    // Actual test: The title said "use the web page" and forced the web page channel. The goal became
    // "use the web page, use the web page..."
    @Test fun channelPrefixIsNotDuplicated() {
        val t = EvalTask(id = "t", goal = "用网页分别查一下长江和黄河的全长", apps = emptyList(), category = EvalCategory.Read,
            expect = Expect.Succeeded, checks = listOf(Check.AnswerContains(listOf("长江"))), channel = "web")
        assertEquals("用网页分别查一下长江和黄河的全长", EvalTasks.substitute(t, "r", emptyMap()).goal)
        assertEquals("用网页查长江", EvalTasks.substitute(t.copy(goal = "查长江"), "r", emptyMap()).goal)
        assertEquals("用 App查长江", EvalTasks.substitute(t.copy(goal = "查长江", channel = "app"), "r", emptyMap()).goal)
        assertEquals("用App查长江", EvalTasks.withChannel("用App查长江", "app"))
    }

    private val alarmTask = EvalTask(id = "alarm", goal = "在手机自带的时钟里新建闹钟「Mina评测-{run}」", apps = listOf("{app:alarm}"),
        category = EvalCategory.Write, requires = listOf("app:alarm"), expect = Expect.Succeeded,
        checks = listOf(Check.ScreenHas("{app:alarm}", "Mina评测-{run}")), marker = "Mina评测-{run}")

    @Test fun systemAppsResolveFromCapabilities() {
        assertEquals(emptyList<String>(), EvalTasks.validate(alarmTask))
        assertEquals(setOf("alarm"), EvalTasks.appCapabilities(alarmTask))
        val r = EvalTasks.resolveApps(alarmTask, mapOf("alarm" to "com.android.deskclock"))
        assertEquals(listOf("com.android.deskclock"), r.apps)
        assertEquals("com.android.deskclock", (r.checks.single() as Check.ScreenHas).packageName)
        // Undecided abilities will not remain in the apps (only the determined ones will be counted when
        // leaving the scope)
        assertEquals(emptyList<String>(), EvalTasks.resolveApps(alarmTask, emptyMap()).apps)
        // Unknown abilities and incorrectly written prerequisites will not pass the verification.
        assertTrue(EvalTasks.validate(alarmTask.copy(apps = listOf("{app:toaster}"))).any { "toaster" in it })
        assertTrue(EvalTasks.validate(alarmTask.copy(requires = listOf("app:toaster"))).any { "toaster" in it })
        assertTrue(EvalTasks.validate(alarmTask.copy(requires = listOf("logged:com.x"))).any { "前置条件" in it })
        assertEquals(emptyList<String>(), EvalTasks.validate(alarmTask.copy(requires = listOf("signedIn:zhihu.com", "signedIn:com.x"))))
    }

    @Test fun writtenAppIsTheForegroundAppWhenTyping() {
        val ev = listOf(
            com.meenai.mina.core.testing.legacyScreenText(++t, 1, "com.example.launcher", listOf("桌面")), reply("phone_launch_app", "{\"package\":\"com.example.notes\"}"),
            com.meenai.mina.core.testing.legacyScreenText(++t, 2, "com.example.notes", listOf("新建")), reply("phone_type_text", "{\"text\":\"买牛奶\"}"),
            com.meenai.mina.core.testing.legacyScreenText(++t, 3, "网站 example.com", listOf("搜索")), reply("phone_type_text", "{\"text\":\"x\"}"),
        )
        assertEquals(listOf("com.example.notes"), RunFacts.appsWrittenIn(ev, TOOLS))
    }

    @Test fun capabilityCandidatesComeFromStandardIntents() {
        // The output of resolve-activity --brief: the one processed by default; when several can be
        // processed, it is the system selection interface, not a candidate
        assertEquals(listOf("com.android.deskclock"), AppCapabilities.packagesIn("priority=0 preferredOrder=0 match=0x108000\ncom.android.deskclock/.AlarmClock"))
        assertEquals(emptyList<String>(), AppCapabilities.packagesIn("priority=0\nandroid/com.android.internal.app.ResolverActivity"))
        val query = "2 activities found:\n  Activity #0:\n    priority=0\n    com.example.calendar/.Launcher\n  Activity #1:\n    org.other.cal/org.other.cal.Main\n"
        assertEquals(listOf("com.example.calendar", "org.other.cal"), AppCapabilities.packagesIn(query))
        assertTrue(AppCapabilities.commands(APP_CAPABILITIES.getValue("calendar").single()).all { "-c android.intent.category.APP_CALENDAR" in it })
        // Candidates opened by Agent are given priority; if there are no candidates, the first App
        // opened by Agent is taken (Mina herself is not counted).
        assertEquals("org.other.cal", AppCapabilities.choose(listOf("com.example.calendar", "org.other.cal"), listOf("com.mina", "org.other.cal"), "com.mina"))
        assertEquals("com.example.calendar", AppCapabilities.choose(listOf("com.example.calendar"), emptyList()))
        // When there are no candidates, only the App that is in the foreground when the Agent is typing
        // is recognized; if it has only been opened or typed in several Apps, it may not be accurate.
        assertEquals("com.example.notes", AppCapabilities.choose(emptyList(), listOf("com.example.other"), "com.mina", written = listOf("com.mina", "com.example.notes")))
        assertNull(AppCapabilities.choose(emptyList(), listOf("com.mina", "com.example.notes"), "com.mina"))
        assertNull(AppCapabilities.choose(emptyList(), emptyList(), written = listOf("com.a.notes", "com.b.notes")))
        assertNull(AppCapabilities.choose(emptyList(), emptyList()))
    }
}
