package com.meenai.mina.core.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {
    private fun obs(
        answer: String = "",
        screens: List<String> = emptyList(),
        confirmation: Map<String, String>? = null,
        screenHas: Map<String, Boolean> = emptyMap(),
        probes: Map<String, String> = emptyMap(),
    ) = Observed("succeeded", answer, screens, confirmation, screenHas, probes, 1, 1000, 0, 0, 0, null)

    private val screens = listOf(
        "首页 推荐 秋天第一杯奶茶 周末去哪玩",
        "推荐 上海 咖啡 地图 十月的银杏大道",
    )

    @Test fun answerContainsAll() {
        val c = Check.AnswerContains(listOf("14", "安卓"))
        assertTrue(Checks.passes(c, obs("你的安卓版本是 14")))
        assertFalse(Checks.passes(c, obs("你的版本是 14")))
    }

    @Test fun answerGroundedAcceptsItemsSeenOnScreen() {
        val answer = """
            首页前三条标题如下:
            1. 「秋天第一杯奶茶」
            2、"周末去哪玩"
            3) 十月的银杏 大道
        """.trimIndent()
        assertTrue(Checks.passes(Check.AnswerGrounded(3), obs(answer, screens)))
        // Not enough entries
        assertFalse(Checks.passes(Check.AnswerGrounded(4), obs(answer, screens)))
    }

    // A list item without a value only counts if the entire paragraph is on the screen; a compiled list
    // item does not count if the number of items is not enough.
    @Test fun answerGroundedRejectsInventedItem() {
        val answer = "1. 秋天第一杯奶茶\n2. 冬天第一顿火锅"
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs(answer, screens)))
        // A made-up title written as a citation is straightforward.
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("1. 「秋天第一杯奶茶」\n2. 「冬天第一顿火锅」", screens)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("", screens)))
    }

    @Test fun answerGroundedIgnoresEvidenceLine() {
        // The same rule as checking the bottom line: "Basis:" will be the basis from now on, and will
        // not be counted as an item in the answer.
        val answer = "1. 秋天第一杯奶茶\n2. 周末去哪玩\n依据:我在首页看到了这些标题,共两条"
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs(answer, screens)))
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs("1. 秋天第一杯奶茶\n依据:首页\n2. 周末去哪玩", screens)))
        val fullWidth = "- 周末去哪玩\n依据：首页截图"
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(fullWidth, screens)))
    }

    // Screen-reading text for calculators, weather cards, and application details pages (excerpts from
    // real machine running records)
    private val calc = listOf("更多选项\n56,088\nMC\nM+\n清除\n乘\n7\n8\n9")
    private val weather = listOf("今天天气 - 百度\n宝安区 商业街\n23°\n23~31°C\n晴\n24 优\n体感23° 北风3级")
    private val market = listOf("哔哩哔哩\n3.6 9150 人评分\n3.6\n9150 人评分\n46 亿 次安装\n216 MB")

    @Test fun proseAnswerIsGroundedByItsValues() {
        val a = "计算器屏幕上显示的结果是 56,088（即 56088）。\n\n依据: 计算器界面顶部显示区文本为 \"56,088\"。"
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(a, calc)))
        val w = "百度结果页顶部天气卡片显示如下：\n\n- 城市：宝安区（商业街）\n- 今天天气状况：晴\n- 气温范围：23~31°C（当前 23°，体感 23°，北风3级，空气质量 24 优）"
        assertTrue(Checks.passes(Check.AnswerGrounded(3), obs(w, weather)))
        val m = "在应用市场搜索「哔哩哔哩」后看到：\n\n- 评分：3.6（9150 人评分）\n- 下载次数：46 亿次安装\n\n未点击安装或更新按钮。"
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs(m, market)))
    }

    @Test fun wordsFromTheGoalNeedNotBeOnScreen() {
        val a = "用手机自带「计算器」计算 123×456,屏幕显示的结果是 56,088。"
        val o = obs(a, calc).copy(goal = "用手机自带的计算器算 123×456,告诉我结果")
        assertTrue(Checks.passes(Check.AnswerGrounded(1), o))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs(a, calc)))
        // The words in the title cannot cover up the made-up values.
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("用「计算器」算出来是 57,088。", calc).copy(goal = o.goal)))
    }

    @Test fun fabricatedValueStillFails() {
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("计算器屏幕上显示的结果是 57,088。", calc)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：4.2（9150 人评分）", market)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 城市：北京", weather)))
        // The unquoted word ("cloudy") in the line with the value is not a fact to be verified
        // literally. What the value belongs to is determined by the verification model; it is verified
        // when written as a citation.
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：晴 23°", weather)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：「多云」 23°", weather)))
        // Only connectivity, no checkable value: not enough entries
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("我已经看完了,结果很好。", calc)))
    }

    @Test fun commentaryLabelsAreProseNotLabelledValues() {
        // "Supplement:" "Explanation:" These are incidental words, not values to be read from the
        // screen.
        val a = "计算器屏幕上显示的结果是 56,088。\n补充：我没有改动计算器里的任何设置。\n- 说明：只看了一眼结果"
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(a, calc)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：晴 23°\n备注：页面里还有广告,没有点开", weather)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：3.6\n另外：没有点安装\n具体依据：应用详情页", market)))
        // There are numerical values written in the accompanying words, but the numerical values
        // should still be displayed on the screen.
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：晴 23°\n补充：明天 35°", weather)))
    }

    @Test fun zeroWidthCharactersOnScreenAreIgnored() {
        // Some apps insert zero-width characters between the words in the product title. The 316 in the
        // screen-reading text is actually 3, 1, and 6, each with a zero-width character in between.
        val zw = listOf("\u200B", "\u200C", "\u200D", "\uFEFF", "\u2060")
        for (z in zw) {
            val shop = listOf("保${z}温${z}杯 3${z}1${z}6${z}L${z}不${z}锈${z}钢 79.00 0人付款")
            assertTrue(z, Checks.passes(Check.AnswerGrounded(2), obs("1. 保温杯 316L 不锈钢 — 79.00 元", shop)))
            assertFalse(z, Checks.passes(Check.AnswerGrounded(1), obs("1. 保温杯 304L 不锈钢 — 79.00 元", shop)))
        }
        // The same applies to responses containing zero-width characters.
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：3\u200B.6", market)))
    }

    @Test fun itemsAreCountedByGroundedValuesNotLines() {
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs("下载量 46亿次,评分 3.6 分(9150 人评分)", market)))
    }

    @Test fun partialNumbersDoNotMatchInsideOtherNumbers() {
        // The elements on the screen cannot be combined into a whole string and then find the substring;
        // the value is based on the whole number.
        for (fake in listOf("6088", "88")) assertFalse(fake, Checks.passes(Check.AnswerGrounded(1), obs("结果是 $fake。", calc)))
        for (fake in listOf("4.6", "1.5")) assertFalse(fake, Checks.passes(Check.AnswerGrounded(1), obs("- 评分：$fake", market)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：3.6", market)))
    }

    // A list item without a value ("Weather: Cloudy") is only counted if the entire paragraph is on a
    // certain line on the screen; only a part of a word ("Sunny" in "Sunny Baby") is not counted.
    @Test fun bareListItemsCountOnlyWhenOnScreen() {
        val far = listOf("晴天娃娃 推荐\n广告\n广告\n广告\n23°\n23~31°C\n多云")
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs("- 天气：晴\n- 气温：23~31°C", far)))
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs("- 天气：多云\n- 气温：23~31°C", far)))
    }

    @Test fun goalNumbersAreKnownOnlyAsWholeNumbers() {
        val goal = "用计算器算 123×456"
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("结果是 56,088,中间值 3456。", calc).copy(goal = goal)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("结果是 56,088,中间值 1234。", calc).copy(goal = goal)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("123×456 的结果是 56,088。", calc).copy(goal = goal)))
    }

    @Test fun confirmationShowsMatchesFields() {
        val card = mapOf("action" to "发送消息", "target" to "张三(同事)", "content" to "晚上好", "amount" to "")
        val o = obs(confirmation = card)
        assertTrue(Checks.passes(Check.ConfirmationShows("发送", "张三", null, null), o))
        assertFalse(Checks.passes(Check.ConfirmationShows("发送", "李四", null, null), o))
        assertFalse(Checks.passes(Check.ConfirmationShows(null, null, null, "10"), o))
        assertFalse(Checks.passes(Check.ConfirmationShows("发送", null, null, null), obs()))
    }

    @Test fun answerGroundedCountsDuplicatesOnce() {
        val answer = "1. 秋天第一杯奶茶\n2. 秋天第一杯奶茶\n3. 「秋天第一杯 奶茶」"
        assertFalse(Checks.passes(Check.AnswerGrounded(3), obs(answer, screens)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(answer, screens)))
    }

    @Test fun answerGroundedDropsTrivialItems() {
        // Entries such as words and punctuation are available on every screen and cannot be used to make
        // up the numbers.
        val answer = "1. 首\n2. 推\n3. 。"
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs(answer, screens)))
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs("1. 秋天第一杯奶茶\n2. 页", screens)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("1. 秋天第一杯奶茶\n2. 页", screens)))
    }

    @Test fun answerGroundedStripsMarkdown() {
        val answer = "1. **秋天第一杯奶茶**\n2. `周末去哪玩`\n3. __十月的银杏大道__\n4. **依据**:首页读屏"
        assertTrue(Checks.passes(Check.AnswerGrounded(3), obs(answer, screens)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("1. 秋天第一杯奶茶\n2. 依据:首页上看到的", screens)))
    }

    @Test fun probeEqualsTrimsAndContains() {
        val o = obs(probes = mapOf("android_release" to " 14\n", "default_home" to "com.example.launcher/.Home"))
        assertTrue(Checks.passes(Check.ProbeEquals("android_release", "14"), o))
        assertFalse(Checks.passes(Check.ProbeEquals("android_release", "1"), o))
        assertTrue(Checks.passes(Check.ProbeEquals("default_home", "com.example.launcher", contains = true), o))
        assertFalse(Checks.passes(Check.ProbeEquals("default_home", "com.other", contains = true), o))
        assertFalse(Checks.passes(Check.ProbeEquals("wifi_on", "1"), o))
    }

    @Test fun screenHasLooksUpByKey() {
        val c = Check.ScreenHas("com.example.notes", "Mina评测-r1")
        assertTrue(Checks.passes(c, obs(screenHas = mapOf(Checks.screenKey(c) to true))))
        assertFalse(Checks.passes(c, obs(screenHas = mapOf(Checks.screenKey(c) to false))))
        assertFalse(Checks.passes(c, obs(screenHas = mapOf(Checks.screenKey(c.copy(text = "别的")) to true))))
    }

    @Test fun answerRegexMatches() {
        val c = Check.AnswerRegex("版本\\s*1[0-9]")
        assertTrue(Checks.passes(c, obs("安卓版本 14")))
        assertFalse(Checks.passes(c, obs("安卓版本九")))
    }
}
