package com.meenai.mina.core.uistate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AccuracyTest {
    private fun loc(
        name: String, x: Int = 100, y: Int = 100, ctx: String? = "上一项", role: String = "TextView", rid: String? = null,
        hint: Boolean = false, inList: Boolean = false,
    ) = ElementLocator(role, name, rid, x, y, ctx, hint, inList)

    @Test fun findsSameElementAfterSmallShift() {
        val prev = loc("蓝牙")
        val after = listOf(loc("WLAN", ctx = null), loc("蓝牙", y = 150))
        assertEquals(Relocation.Found(1), relocate(prev, listOf(prev), after, 720))
    }

    @Test fun goneWhenNotInNewList() {
        assertEquals(Relocation.Gone, relocate(loc("蓝牙"), listOf(loc("蓝牙")), listOf(loc("WLAN")), 720))
    }

    @Test fun ambiguousWhenDuplicatedOrNameless() {
        val prev = loc("删除")
        assertEquals(Relocation.Ambiguous, relocate(prev, listOf(prev), listOf(prev, prev.copy(centerY = 300)), 720))
        assertEquals(Relocation.Ambiguous, relocate(prev, listOf(prev, prev.copy(centerY = 300)), listOf(prev), 720))
        assertEquals(Relocation.Ambiguous, relocate(loc(""), listOf(loc("")), listOf(loc("")), 720))
    }

    @Test fun namelessWithResourceIdCanBeFound() {
        val prev = loc("", rid = "com.x:id/cart")
        assertEquals(Relocation.Found(0), relocate(prev, listOf(prev), listOf(prev.copy(centerX = 120)), 720))
    }

    @Test fun movedWhenContextChangesOrShiftTooFar() {
        val prev = loc("加入购物车", ctx = "商品 A")
        assertEquals(Relocation.Moved, relocate(prev, listOf(prev), listOf(prev.copy(context = "商品 B")), 720))
        // 0.15 × 720 = 108px
        assertEquals(Relocation.Moved, relocate(prev, listOf(prev), listOf(prev.copy(centerY = 100 + 109)), 720))
        assertEquals(Relocation.Found(0), relocate(prev, listOf(prev), listOf(prev.copy(centerY = 100 + 108)), 720))
    }

    // The rest of the page remains unchanged (three items in the bottom column), only the target itself
    // changes: countdown, preset words for carousel, and context changes caused by carousel
    private val tabs = listOf(loc("首页", 100, 1500, null), loc("消息", 360, 1500, "首页"), loc("我的", 620, 1500, "消息"))

    @Test fun sameSpotFoundWhenOnlyItsTextChangedOnAStillPage() {
        val prev = loc("跳过 3", 600, 50, ctx = "广告", role = "Button", rid = "com.x:id/skip")
        val after = tabs + prev.copy(name = "跳过 2")
        assertEquals(Relocation.Found(3), relocate(prev, tabs + prev, after, 720))
    }

    @Test fun countdownOnASparseSplashPageIsFound() {
        // The opening ad page only has a few famous elements
        val prev = loc("跳过 3", 600, 50, ctx = null, role = "TextView", rid = "com.x:id/skip")
        val rest = listOf(loc("广告", 600, 1500, "跳过 3"), loc("浏览器", 360, 1550, "广告"))
        assertEquals(Relocation.Found(0), relocate(prev, listOf(prev) + rest, listOf(prev.copy(name = "跳过 2")) + rest, 720))
    }

    @Test fun rotatingPlaceholderInSearchBarIsFound() {
        val prev = loc("宝安租房", 360, 200, ctx = "定位", role = "EditText", rid = "com.x:id/search_bar", hint = true)
        val after = tabs + prev.copy(name = "平板电脑")
        assertEquals(Relocation.Found(3), relocate(prev, tabs + prev, after, 720))
    }

    @Test fun differentControlInTheSameSpotIsNeverTaken() {
        // The name has changed and the numbers and blanks have been removed and it still doesn’t match:
        // it is another control (or the same control has changed its meaning) and cannot be clicked for
        // the model.
        val follow = loc("关注", 600, 300, role = "Button", rid = "com.x:id/follow")
        assertEquals(Relocation.Gone, relocate(follow, tabs + follow, tabs + follow.copy(name = "已关注"), 720))
        val next = loc("下一步", 360, 1300, role = "Button", rid = "com.x:id/primary")
        assertEquals(Relocation.Gone, relocate(next, tabs + next, tabs + next.copy(name = "确认支付"), 720))
        // Even the countdown is not relaxed when there is no resource ID.
        val skip = loc("跳过 3", 600, 50, role = "Button")
        assertEquals(Relocation.Gone, relocate(skip, tabs + skip, tabs + skip.copy(name = "跳过 2"), 720))
        // Neither the old nor new parties have resource IDs and will not be relaxed.
        val skipRid = skip.copy(resourceId = "com.x:id/skip")
        assertEquals(Relocation.Gone, relocate(skipRid, tabs + skipRid, tabs + skip.copy(name = "跳过 2"), 720))
        // Ordinary text that is not a prompt does not count if it is changed.
        val plain = loc("宝安租房", 360, 200, role = "TextView", rid = "com.x:id/title")
        assertEquals(Relocation.Gone, relocate(plain, tabs + plain, tabs + plain.copy(name = "平板电脑"), 720))
    }

    @Test fun contextChangeInsideAListIsNotRelaxed() {
        val prev = loc("购买", 360, 200, ctx = "商品 A", role = "Button", inList = true)
        assertEquals(Relocation.Moved, relocate(prev, tabs + prev, tabs + prev.copy(context = "商品 B"), 720))
    }

    @Test fun contextChangedByCarouselOnAStillPageIsFound() {
        val prev = loc("搜索", 360, 200, ctx = "轮播图 A", role = "Button")
        val after = tabs + prev.copy(context = "轮播图 B")
        assertEquals(Relocation.Found(3), relocate(prev, tabs + prev, after, 720))
    }

    @Test fun sameSpotNotTrustedWhenThePageScrolledOrChanged() {
        val prev = loc("加入购物车", 360, 400, ctx = "商品 A", role = "Button")
        val rows = listOf(loc("商品 A", 100, 300, null), loc("商品 B", 100, 500, "商品 A"), loc("商品 C", 100, 700, "商品 B"))
        // Move the list up one line: the buttons for other products just move to their original
        // positions
        val scrolled = rows.map { it.copy(centerY = it.centerY - 200) } + prev.copy(context = "商品 B")
        assertEquals(Relocation.Moved, relocate(prev, rows + prev, scrolled, 720))
        // Changed the page: the names of the buttons at the same location have also changed.
        val otherPage = listOf(loc("设置", 100, 300, null), loc("关于", 100, 500, "设置"), prev.copy(name = "退出登录"))
        assertEquals(Relocation.Gone, relocate(prev, rows + prev, otherPage, 720))
    }

    /** Simulate input box: When [accepts] is false, writing is ignored; when [clearable] is false, clearing is ignored; [format] simulates the input box to rewrite the text by itself. */
    private class FakeField(
        var text: String?,
        val accepts: Boolean = true,
        val clearable: Boolean = true,
        val writeResult: Boolean = true,
        val format: (String) -> String = { it },
    ) : TextField {
        var writes = 0
        override fun read() = text
        override fun write(text: String): Boolean {
            writes++
            if (text.isEmpty()) { if (clearable && this.text != null) this.text = "" }
            else if (accepts && this.text != null) this.text = format(text)
            return writeResult
        }
    }

    private fun type(f: FakeField, t: String) = runBlocking { typeAndVerify(f, t) }

    @Test fun matchWhenTextLands() {
        assertEquals(Verification.MATCH, type(FakeField(""), "WLAN"))
        assertEquals(Verification.MATCH, type(FakeField("旧内容"), "WLAN"))
        // The spaces added to the number grouping do not affect
        assertEquals(Verification.MATCH, type(FakeField("", format = { "138 0013 8000" }), "13800138000"))
    }

    @Test fun retriesOnlyOnceAndOnlyWhenTheTextDidNotLand() {
        val landed = FakeField("")
        assertEquals(Verification.MATCH, type(landed, "WLAN"))
        assertEquals(1, landed.writes)
        val first = object : TextField {
            var writes = 0; var text = ""
            override fun read() = text
            override fun write(text: String): Boolean { writes++; if (writes == 2) this.text = text; return true }
        }
        assertEquals(Verification.MATCH, runBlocking { typeAndVerify(first, "WLAN") })
        assertEquals(2, first.writes)
    }

    @Test fun passwordFieldIsWrittenOnceAndNeverRetried() {
        // Even if you can read something (dot) in the password box or it doesn't match, you can only
        // write it once.
        val f = FakeField("", accepts = false)
        assertEquals(Verification.UNKNOWN, runBlocking { typeAndVerify(f, "pw123456", secret = true) })
        assertEquals(1, f.writes)
    }

    @Test fun ignoredWriteIsNotMatch() {
        val f = FakeField("", accepts = false)
        assertEquals(Verification.MISMATCH, type(f, "WLAN"))
        assertEquals(2, f.writes) // Tried again
    }

    @Test fun writeReportedFailedIsAFailedAttempt() {
        // The text in the box has changed but the action failed: it is not considered a success and will
        // still be treated as a failure after retrying.
        assertEquals(Verification.MISMATCH, type(FakeField("", accepts = false, writeResult = false), "WLAN"))
    }

    @Test fun containsIsNotEqual() {
        assertEquals(Verification.MISMATCH, type(FakeField("", format = { "${it}设置" }), "WLAN"))
    }

    @Test fun baselineAlreadyTargetAndWriteIgnoredIsNotMatch() {
        // Can't clear or write in: same as before and after, no way to judge
        assertEquals(Verification.UNKNOWN, type(FakeField("WLAN", accepts = false, clearable = false), "WLAN"))
        // Can be cleared but cannot be written: after clearing, it is read back as empty, and after
        // writing, it is still empty.
        assertEquals(Verification.MISMATCH, type(FakeField("WLAN", accepts = false), "WLAN"))
        // Can be cleared and written: really written in
        assertEquals(Verification.MATCH, type(FakeField("WLAN"), "WLAN"))
    }

    @Test fun fieldStuckOnSameTextButAppReactedIsUnknown() {
        // The accessibility box keeps reporting the same text (such as a prompt), but an associated word
        // appears on the interface: The App has received the word, but cannot read it back.
        fun run(f: FakeField, reacted: Boolean) = runBlocking { typeAndVerify(f, "天气", reacted = { reacted }) }
        assertEquals(Verification.UNKNOWN, run(FakeField("请输入关键词", accepts = false), reacted = true))
        assertEquals(Verification.MISMATCH, run(FakeField("请输入关键词", accepts = false), reacted = false))
        // Every write fails: no matter how the interface changes, it is not done by us.
        assertEquals(Verification.MISMATCH, run(FakeField("请输入关键词", accepts = false, writeResult = false), reacted = true))
        // The words in the box have changed but are wrong: it’s not “can’t read back”, it’s still not
        // typed correctly.
        assertEquals(Verification.MISMATCH, run(FakeField("", format = { "${it}设置" }), reacted = true))
    }

    @Test fun unreadableFieldIsUnknown() {
        val f = FakeField(null)
        assertEquals(Verification.UNKNOWN, type(f, "secret"))
        assertEquals(1, f.writes)
    }

    @Test fun emptyTextMeansClear() {
        assertEquals(Verification.MATCH, type(FakeField("旧内容"), ""))
        assertEquals(Verification.MISMATCH, type(FakeField("旧内容", clearable = false), ""))
    }
}
