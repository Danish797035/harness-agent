package com.meenai.mina.core.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * There are three types of wrong answers that are mistakenly rejected (the original words in the real
 * machine records and the text on the web page read), as well as their respective counterexamples: the
 * evidence only proves what it has proven, and all wrong answers are blocked.
 * - - Running clock: If "tomorrow" or "Tuesday" is written in the question or evidence, the day will be
 * calculated based on the clock;
 * - - Simple operations: Answer the stated calculation formula (a operator b = c), a and b are all
 * matched in the evidence; clearly explain the rounding of divisors (approximately, ≈);
 * - - Truncation: Answer yourself with... Omit the citation. The line on the screen is complete and does
 * not count as truncation.
 */
class GroundingRecordedRunsTest {
    /** The day of verification in the record: 2026-10-24, Saturday. */
    private val today = LocalDate.of(2026, 10, 24)

    private val beijingPage = listOf(
        "18:00更新 | 数据来源 中央气象台",
        "周末两天（10月24日至25日），我国华东沿海多地仍有降雨，其中今天雨势较强；同时，西北地区东部和华北等地降水增多。",
        "25日（明天） 小雨 12℃/9℃ <3级",
        "26日（后天） 阴转晴 16℃/6℃ <3级",
    ).joinToString("\n")
    private val beijingGoal = "用网页查一下明天北京的天气,告诉我最高和最低气温"
    private val beijingAnswer = "明天(10月25日,周日)北京天气已查到。\n\n- 天气:小雨\n- 最高气温:12℃\n- 最低气温:9℃\n\n" +
        "依据:中国天气网北京7天预报页面,「25日(明天)」一栏显示「小雨 12℃/9℃ <3级」,更新时间 18:00,数据来源中央气象台。"

    // Actual test web-weather-beijing: The page only writes "25th (tomorrow)", and the answer is
    // converted to "October 25th" and is regarded as a date that cannot be found.
    @Test fun dateResolvedFromRunClockIsKnown() {
        val without = Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage))
        assertEquals(false, without.found)
        assertEquals(listOf("10月25日"), without.unmatched.map { it.text })
        assertEquals(true, Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage), today).found)
        // web-weather-three-cities: Same goes for "October 25, 2026" with the year
        val cities = "中国天气网(weather.com.cn)明天(2026年10月25日)三城市最高气温如下:\n\n• 北京:最高 12℃(明天 小雨,12℃/9℃)\n\n" +
            "依据:中国天气网 7 天预报页面,北京显示\"25日(明天) 小雨 12℃/9℃\"。"
        assertEquals(true, Grounding.check("用网页查北京明天的最高气温", cities, listOf(beijingPage), today).found)
        // The page says "Tuesday": Tuesday is October 27th
        val week = "27日（周二） 晴 16℃/2℃ <3级"
        assertEquals(true, Grounding.check("北京周二天气", "周二(10月27日)北京晴,最高气温:16℃\n依据:「晴 16℃/2℃」", listOf(week), today).found)
    }

    @Test fun clockOnlyVouchesForRelativeDaysTheEvidenceMentions() {
        fun unmatched(answer: String, page: String = beijingPage, goal: String = beijingGoal) =
            Grounding.check(goal, answer, listOf(page), today).unmatched.map { it.text }
        // "Tuesday" only appears in the answer: neither the question nor the page says Tuesday, and the
        // clock does not bear witness for it.
        assertEquals(listOf("10月27日"), unmatched("周二(10月27日)北京晴,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // The title and page say tomorrow (25th) and the day after tomorrow (26th): the 27th is
        // incorrect.
        assertEquals(listOf("10月27日"), unmatched(beijingAnswer.replace("10月25日", "10月27日"), beijingPage + "\n29日 小雨 10月29日"))
        // There is no relative statement in the title or page: the date within a week is not certified
        // by a clock.
        val plainPage = "25日 小雨 12℃/9℃ <3级"
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」", plainPage, "北京天气"))
        // The answer is that it is the date read from the page (in the citation, in the basis), and the
        // clock does not prove it.
        assertTrue("10月25日" in unmatched("明天北京「10月25日 小雨」,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:页面写着 10月25日 小雨 12℃/9℃"))
        // Time is never witnessed by the clock
        assertEquals(listOf("10:27"), unmatched("今天 10:27 更新,明天小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // distant date
        assertEquals(listOf("11月5日"), unmatched(beijingAnswer.replace("10月25日", "11月5日")))
        // The clock only gives evidence of the date, not the number: the made-up temperature is still
        // recorded
        assertEquals(listOf("31"), unmatched(beijingAnswer.replace("最高气温:12℃", "最高气温:31℃")))
    }

    private val riverPages = listOf(
        "长江，中国境内太平洋水系河流，最后注入东海。全长6363千米 [16]（一说有6236千米，一说6380千米，一说6397千米，一说 6403千米，一说6300余千米 [66]）。是中国、亚洲第一大河。",
        "黄河，是位于中国北方地区的大河，最后在东营市垦利区流入渤海。黄河全长约5464千米 [51]，流域面积约79.47万平方千米。",
        "web_navigate:网址 https://baike.example.com/cj,网页标题「长江（中国境内太平洋水系河流）_百科」",
        "web_navigate:网址 https://baike.example.com/hh,网页标题「黄河（中国北方独流入渤海水系河流）_百科」",
    )
    private val riverGoal = "用网页分别查一下长江和黄河的全长,告诉我哪条更长、大约长多少公里"
    private fun riverAnswer(diff: String) = "长江和黄河的全长都已在百度百科词条正文中查到:\n\n" +
        "- 长江:全长 6363 千米(词条还注明另有 6236、6380、6397、6403、6300 余千米等说法)。\n- 黄河:全长约 5464 千米。\n\n" +
        "对比结果:长江更长,$diff。\n\n" +
        "依据:百度百科「长江(中国境内太平洋水系河流)」词条正文「全长6363千米」;「黄河(中国北方独流入渤海水系河流)」词条正文「全长约5464千米」。"

    // Actual measurement web-rivers-length-compare: difference 899 = 6363 − 5464, naturally not found on
    // the screen. The calculation must be clearly stated and both operands must be included in the
    // evidence.
    @Test fun explicitComputationOfGroundedNumbersIsGrounded() {
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=899 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363 千米 - 5464 千米 = 899 千米"), riverPages).found)
        // If it is stated that the divisor (≈, = approx.) is rounded according to the 0 at the end
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464≈900 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=约900 公里"), riverPages).found)
        // Percentage: The result of ÷ is rounded to the number of decimal places written.
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363÷5464=116.5%"), riverPages).found)
        // price difference
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(true, Grounding.check("比较价格", "手机 ¥1999,耳机 ¥299,¥1999 − ¥299 = ¥1700\n依据:「手机 ¥1999」", prices).found)
        // A single number that clearly states the divisor, rounded to the precision written out, is
        // equal to the number in the evidence
        assertEquals(emptyList<String>(), Grounding.check("多少钱", "售价约 2000元\n依据:「售价 1999元」", listOf("售价 1999元")).unmatched.map { it.text })
    }

    @Test fun inventedNumberIsStillRejected() {
        fun unmatched(goal: String, answer: String, screens: List<String>) = Grounding.check(goal, answer, screens).unmatched.map { it.text }
        // No calculation is written: the difference is just "not on the screen"
        assertEquals(listOf("899"), unmatched(riverGoal, riverAnswer("比黄河长约 899 公里"), riverPages))
        assertEquals(listOf("1234"), unmatched(riverGoal, riverAnswer("比黄河长 1234 公里"), riverPages))
        // The calculation is wrong
        assertEquals(listOf("999"), unmatched(riverGoal, riverAnswer("6363−5464=999 公里"), riverPages))
        // The divisor cannot be rough enough to match a large number: 1000 is more than 10% away from
        // 899
        assertEquals(listOf("1000"), unmatched(riverGoal, riverAnswer("6363−5464≈1000 公里"), riverPages))
        // Operand is not in evidence
        assertEquals(listOf("5063", "1300"), unmatched(riverGoal, riverAnswer("6363−5063=1300 公里"), riverPages))
        // It is exactly equal to the difference between the two prices, but there is no calculation
        // formula: words such as "total" and "difference" are not considered evidence.
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,共 ¥1700\n依据:「手机 ¥1999」", prices))
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,平板 ¥1700\n依据:「手机 ¥1999」", prices))
        // The compiled percentage: 96% is not 100%, nor is it 40%
        assertTrue("100" in unmatched("电量多少", "电量:100%\n依据:「电量 96%」", listOf("电量 96%")))
        assertTrue("40" in unmatched("电量多少", "电量 96%,已用约 40%\n依据:「电量 96%」", listOf("电量 96%")))
        // Didn’t say the approximate number: 1999 yuan, not 2000 yuan
        assertEquals(listOf("2000"), unmatched("多少钱", "售价 2000元\n依据:「售价 1999元」", listOf("售价 1999元")))
        // The result of the calculation of the numbers given in the question still has to be read on the
        // screen (calculator question: there are only calculation formulas on the screen, but no
        // results)
        assertEquals(listOf("3950.6"), unmatched("用计算器算 98765 除以 25", "98765÷25=3950.6\n依据:计算器屏上「98765÷25」", listOf("98765÷25")))
    }

    // Actual measurement web-rivers-length-compare: The URL hash of a picture on the page contains 899,
    // which is regarded as the number on the screen.
    @Test fun numbersOnScreenMustBeWholeTokens() {
        val hash = "https://bkimg.cdn.bcebos.com/smart/d50735fae6cd7b899e51bc8a177c55a7d933c895a038-bkimg-process,v_1"
        val answer = riverAnswer("比黄河长约 899 公里")
        assertEquals(listOf("899"), Grounding.check(riverGoal, answer, riverPages + hash).unmatched.map { it.text })
        assertEquals(emptyList<String>(), Grounding.check(riverGoal, answer, riverPages + "长江比黄河长约 899 公里").unmatched.map { it.text })
        // The numbers in the id and the version number with letters are not counted; the unit is written
        // (128GB), decimals and thousandths are still counted.
        assertEquals(false, Grounding.check("编号", "编号是 899 号码\n依据:「订单」", listOf("订单 a3f899c")).found)
        assertEquals(true, Grounding.check("存储多少", "存储:128GB\n依据:「存储 128GB」", listOf("存储 128GB")).found)
        assertEquals(true, Grounding.check("价格", "价格:1,299.50元\n依据:「价格」", listOf("价格 ¥1,299.50")).found)
        // The numbers sandwiched in the letters are compared in one word: G7541 matches, and the
        // compiled G9999 blocks it.
        assertEquals(emptyList<String>(), Grounding.check("车次", "车次:G7541\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        assertEquals(listOf("G9999"), Grounding.check("车次", "车次 G9999 05:52 出发\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        // The date is the same: 1025 in the URL is not October 25th
        assertEquals(listOf("10月25日"), Grounding.check("哪天", "日期是 10月25日\n依据:「图片」", listOf("图片 https://x.cn/p/img10x25b.jpg")).unmatched.map { it.text })
    }

    // The numbers in the questions are recognized by their values: write 1000.0 for the question and
    // 1000 for the answer. They should not be regarded as the values to be found on the screen.
    @Test fun goalNumbersAreKnownByValue() {
        assertEquals(true, Grounding.check("用计算器算 1000.0 除以 8", "1000 除以 8 等于 125.0\n依据:计算器屏上「125」", listOf("125")).found)
    }

    private val trainRows = listOf(
        "预订G7541次车05:52从上海虹桥出发，06:51到杭州东,当日到达历时00:59的车票",
        "预订G1321次车06:07从上海虹桥出发，06:56到杭州东,当日到达历时00:49的车票",
        "预订G7331次车06:13从上海虹桥出发，07:07到杭州东,当日到达历时00:54的车票",
    ).joinToString("\n")
    private val trainAnswer = "明天(2026-10-25)上海到杭州的高铁车次:\n\n| G7541 | 上海虹桥 05:52 | 杭州东 06:51 |\n| G1321 | 上海虹桥 06:07 | 杭州东 06:56 |\n\n" +
        "依据:12306 官网余票页查询结果列表,页面元素逐条显示\"预订G7541次车05:52从上海虹桥出发,06:51到杭州东…\"\"预订G1321次车06:07从上海虹桥出发,06:56到杭州东…\"等车次信息。"

    // Actual test web-12306-trains: Answer by yourself... The second half of the quotation is omitted,
    // the lines on the screen are complete, and it is judged to be truncated.
    @Test fun agentEllipsisOnCompleteLineIsNotTruncation() {
        assertFalse(Grounding.builtOnTruncated(trainAnswer, listOf(trainRows)))
        val pass = Verification(Verdict.Pass, listOf(CriterionCheck("列出至少三个车次号", "met", "预订G7541次车05:52从上海虹桥出发")), "屏上有")
        assertEquals(Verdict.Pass, Verifier.backstop(pass, "在 12306 上查明天上海到杭州的高铁", trainAnswer, listOf(trainRows), imageSent = false, today).verdict)
    }

    @Test fun truncatedScreenLineIsStillTruncation() {
        // The line itself on the screen is truncated (ending with...), and the quotation of the answer
        // is continued up to the truncation: only part of it is seen
        val cut = "预订G7541次车05:52从上海虹桥出发，06:51到杭州东…\n预订G1321次车06:07从上海虹桥出发，06:56到杭州东…"
        assertTrue(Grounding.builtOnTruncated(trainAnswer, listOf(cut)))
        // The first half of the line on the screen that begins with the citation is cut off
        assertTrue(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf("…到杭州东,当日到达")))
        assertFalse(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf(trainRows)))
    }

    // ---------- Only literal verification: correct answers will not be blocked due to writing (real
    // machine record emu-web-smoke-2), wrong answers will still be blocked ----------

    private val doubanRows = listOf("1", "肖申克的救赎 / The Shawshank Redemption", "9.7 3353059人评价", "4", "阿甘正传 / Forrest Gump", "9.5 2465343人评价",
        "5", "千与千寻 / 千と千尋の神隠し", "9.4 2577773人评价").joinToString("\n")

    // Actual measurement web-douban-top-five: After the list number in the next line is removed, "9.5"
    // and "Spirited Away" in the next line are spelled into "9.5 thousand"; the reading date written in
    // it is regarded as the date to be verified.
    @Test fun answerLinesAreNotJoinedAndBasisProseIsNotAClaim() {
        val a = "前五如下:\n\n1. 肖申克的救赎 — 评分 9.7\n4. 阿甘正传 — 评分 9.5\n5. 千与千寻 — 评分 9.4\n\n" +
            "依据:网页正文「1 肖申克的救赎 … 9.7 3353059人评价」「4 阿甘正传 … 9.5 2465343人评价」「5 千与千寻 … 9.4 2577773人评价」(movie.douban.com/top250,2026-10-24 读取)。"
        val g = Grounding.check("豆瓣 Top250 前五的电影名和评分", a, listOf(doubanRows), today)
        assertEquals(g.unmatched.toString(), true, g.found)
        assertFalse(g.claims.toString(), g.claims.any { "千" in it.text && it.kind == Grounding.Kind.Number })
        // The citations in the basis are still checked: the compiled number of people and the
        // incorrectly cited film titles are blocked.
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("2577773人评价", "2577999人评价"), listOf(doubanRows), today).found)
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("「5 千与千寻", "「5 天空之城"), listOf(doubanRows), today).found)
    }

    // Actual measurement web-rivers-length-compare: "Yellow River: total length is about 5464 kilometers
    // (entry text "...")" is regarded as "tag: value", and the entire section is required to be on the
    // screen
    @Test fun unquotedLabelValuesAreNotLiteralClaims() {
        val a = "- 黄河:全长约 5464 千米(词条正文「全长约5464千米 [51],流域面积约79.47万平方千米」)\n依据:黄河词条「全长约5464千米 [51],流域面积约79.47万平方千米」"
        assertEquals(true, Grounding.check(riverGoal, a, riverPages).found)
        assertEquals(listOf("5465"), Grounding.check(riverGoal, a.replaceFirst("5464 千米", "5465 千米"), riverPages).unmatched.map { it.text })
    }

    // Actual test web-github-trending-top: "Today + N" use N instead of answering the number you have
    // written, not the words on the screen
    @Test fun quoteThatNamesTheAnswersOwnWordingIsNotAScreenClaim() {
        val page = listOf("## cloudflare / security-audit-skill", "JavaScript 43,250 2,699 Built by", "2,271 stars today")
        val a = "1. cloudflare / security-audit-skill — 43,250 颗星(今日 +2,271)\n说明:「今日 +N」是当天新增星标数。\n依据:「2,271 stars today」"
        assertEquals(true, Grounding.check("GitHub Trending 前三", a, page).found)
        // There is no corresponding number in the answer: the citation will still be on the screen; the
        // compiled citation will also be blocked.
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("(今日 +2,271)", ""), page).found)
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("「今日 +N」", "「Top N」"), page).found)
    }

    // Measured settings-android-version: Divided into two lines on the screen, numbers sandwiched
    // between letters in quotations (AQ3A) are not treated as separate numbers.
    @Test fun basisQuoteAcrossTwoLinesOfOneEntryLocates() {
        val about = listOf("当前 App:com.android.settings\nAndroid 版本\n15 AQ3A.240829.003")
        val a = "安卓版本是 Android 15(系统版本号 AQ3A.240829.003)。\n依据:关于手机页显示「Android 版本：15 AQ3A.240829.003」"
        assertEquals(true, Grounding.check("安卓版本", a, about).found)
        // Several paragraphs of the quotation should be in the same place: those that are read twice are
        // not considered to be located.
        val split = listOf("当前 App:com.android.settings\nAndroid 版本\n16", "15 AQ3A.240829.003")
        assertEquals(false, Grounding.check("安卓版本", a, split).found)
    }
}
