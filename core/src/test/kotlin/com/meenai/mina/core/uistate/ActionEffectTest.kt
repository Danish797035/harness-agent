package com.meenai.mina.core.uistate

import com.meenai.mina.core.visual.FrameChange
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The action effect is judged according to the front and back screens (element table + screen): classification of structural differences, input readback, and changing places. The screens are taken from several common interfaces in real operations. */
class ActionEffectTest {
    private var n = 0
    private fun el(name: String, b: List<Int>, role: String = "text", editable: Boolean = false, focused: Boolean = false,
                   hint: Boolean = false, secret: Boolean = false, id: String? = null, states: Set<String> = emptySet()) =
        UiElement("@e${++n}", role, name, 0, 0, b, id, clickable = true, editable = editable, scrollable = false,
            hint = hint, focused = focused, secret = secret, states = states)

    private fun screen(pkg: String, vararg e: UiElement, web: WebInfo? = null) = ScreenSnapshot("s${++n}", pkg, e.toList(), 1080, 2400, web = web)

    /** Set the home page: a list of entries. */
    private fun settings(offset: Int = 0, vararg names: String = arrayOf("网络和互联网", "已连接的设备", "应用", "通知", "电池", "存储", "声音", "显示")) =
        screen("com.android.settings", *names.mapIndexed { i, s -> el(s, listOf(0, 100 + i * 100 - offset, 999, 180 + i * 100 - offset), id = "android:id/title") }.toTypedArray())

    private fun classify(verb: Verb, b: ScreenSnapshot, a: ScreenSnapshot, changed: Boolean? = null, frame: FrameChange? = null,
                         target: UiElement? = null, typed: String? = null, pressedEnter: Boolean = false, secret: Boolean = false, verified: Boolean = false,
                         loading: Boolean = false) =
        ActionEffect.classify(verb, b, a, PortFacts(changed, verified = verified, loading = loading), frame, target, typed, pressedEnter, secret)!!

    @Test fun identicalScreensAreNoEffect() {
        val e = classify(Verb.Tap, settings(), settings())
        assertEquals(EffectKind.NoEffect, e.kind)
        assertTrue(e.diff!!.none)
        assertEquals("no_effect", e.toJson()["kind"]!!.jsonPrimitive.content)
    }

    @Test fun openingASubPageIsDone() {
        val sub = screen("com.android.settings", el("电池", listOf(0, 50, 999, 120)), el("电池用量", listOf(0, 200, 999, 260)),
            el("省电模式", listOf(0, 300, 999, 360)), el("电池百分比", listOf(0, 400, 999, 460)))
        val e = classify(Verb.Tap, settings(), sub)
        assertEquals(EffectKind.Done, e.kind)
        assertTrue(e.diff!!.added >= 3 && e.diff!!.removed >= 3)
    }

    @Test fun scrollingMovesTheSameItems() {
        val e = classify(Verb.Scroll, settings(), settings(offset = 300))
        assertEquals(EffectKind.Done, e.kind)
        assertEquals(8, e.diff!!.moved)
        assertEquals(0, e.diff!!.added)
    }

    @Test fun onlyFocusMovingIsASmallChange() {
        val f = listOf(0, 100, 999, 180)
        val before = screen("com.shop", el("搜索", f, "edit", editable = true, hint = true, id = "q"), el("推荐", listOf(0, 300, 999, 400)))
        val after = screen("com.shop", el("搜索", f, "edit", editable = true, hint = true, focused = true, id = "q"), el("推荐", listOf(0, 300, 999, 400)))
        val e = classify(Verb.Tap, before, after)
        assertEquals(EffectKind.SmallChange, e.kind)
        assertTrue(e.diff!!.focusMoved)
    }

    @Test fun oneElementTogglingIsASmallChangeNotDone() {
        val before = settings(0, "蓝牙", "已关闭", "配对新设备")
        val after = settings(0, "蓝牙", "已开启", "配对新设备")
        assertEquals(EffectKind.SmallChange, classify(Verb.Tap, before, after).kind)
    }

    @Test fun anotherAppInFrontIsPlaceChangedWithAppDetail() {
        val e = classify(Verb.Tap, settings(), screen("com.android.permissioncontroller", el("允许", listOf(0, 0, 10, 10))))
        assertEquals(EffectKind.PlaceChanged, e.kind)
        val j = e.toJson()
        assertEquals("place_changed", j["kind"]!!.jsonPrimitive.content)
        assertEquals("com.android.permissioncontroller", j["to"]!!.jsonPrimitive.content)
        assertEquals("com.android.permissioncontroller", j["app"]!!.jsonObject["package"]!!.jsonPrimitive.content)
    }

    @Test fun newAddressIsPlaceChangedWithWebDetailButAnchorIsNot() {
        val list = screen("shop.example.com", el("商品一", listOf(0, 0, 999, 100)), web = WebInfo("https://shop.example.com/list", "列表", "t1", 200))
        val item = screen("shop.example.com", el("加入购物车", listOf(0, 0, 999, 100)), web = WebInfo("https://shop.example.com/item/1", "商品一", "t1", 200))
        val e = classify(Verb.Tap, list, item)
        assertEquals(EffectKind.PlaceChanged, e.kind)
        assertEquals("https://shop.example.com/item/1", e.toJson()["web"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // After only changing #: the same page, judged by structure
        val anchored = list.copy(web = list.web!!.copy(url = "https://shop.example.com/list#reviews"))
        assertEquals(EffectKind.NoEffect, classify(Verb.Tap, list, anchored).kind)
    }

    @Test fun typedTextIsReadBackFromTheField() {
        val f = listOf(0, 100, 999, 180)
        val field = el("搜索商品", f, "edit", editable = true, hint = true, focused = true, id = "q")
        val before = screen("com.shop", field)
        val landed = screen("com.shop", el("咖啡豆", f, "edit", editable = true, focused = true, id = "q"))
        val ok = classify(Verb.TypeText, before, landed, target = field, typed = "咖啡豆")
        assertEquals(EffectKind.Done, ok.kind)
        assertEquals(Readback("咖啡豆", secret = false, matches = true), ok.readback)
        assertEquals(1, ok.diff!!.edited)
        // The box has changed to something else (rewritten or truncated by the App): the readback is
        // inconsistent, please take a look
        val other = classify(Verb.TypeText, before, screen("com.shop", el("咖啡", f, "edit", editable = true, focused = true, id = "q")), target = field, typed = "咖啡豆")
        assertEquals(EffectKind.SmallChange, other.kind)
        assertFalse(other.readback!!.matches)
        assertEquals("咖啡", other.toJson()["readback"]!!.jsonPrimitive.content)
        // Nothing went in
        val nothing = classify(Verb.TypeText, before, before, target = field, typed = "咖啡豆")
        assertEquals(EffectKind.NoEffect, nothing.kind)
        // Spaces in number groups are not inconsistent
        val spaced = classify(Verb.TypeText, before, screen("com.shop", el("138 0000 0000", f, "edit", editable = true, focused = true, id = "q")), target = field, typed = "13800000000")
        assertTrue(spaced.readback!!.matches)
    }

    @Test fun secretFieldsAreNeverReadBack() {
        val f = listOf(0, 100, 999, 180)
        val pw = el("", f, "edit", editable = true, focused = true, secret = true, id = "pw")
        val after = screen("com.bank", el("••••••", f, "edit", editable = true, focused = true, secret = true, id = "pw"))
        val e = classify(Verb.TypeText, screen("com.bank", pw), after, target = pw, typed = "pw123456", secret = true)
        assertEquals(ActionEffect.HIDDEN, e.toJson()["readback"]!!.jsonPrimitive.content)
        assertFalse("pw123456" in e.toJson().toString())
    }

    @Test fun portVerifiedTypingIsDoneWhenTheFieldIsNotInTheTree() {
        val s = screen("com.x", el("标题", listOf(0, 0, 999, 100)))
        assertEquals(EffectKind.Done, classify(Verb.TypeText, s, s, typed = "hi", verified = true).kind)
        assertEquals(EffectKind.NoEffect, classify(Verb.TypeText, s, s, typed = "hi").kind)
    }

    @Test fun afterEnterTheFieldIsNotReadBack() {
        val f = listOf(0, 100, 999, 180)
        val field = el("搜索", f, "edit", editable = true, hint = true, focused = true, id = "q")
        val results = screen("com.shop", el("搜索", f, "edit", editable = true, hint = true, id = "q"), *(1..6).map { el("结果$it", listOf(0, 200 + it * 100, 999, 280 + it * 100)) }.toTypedArray())
        val e = classify(Verb.TypeText, screen("com.shop", field), results, target = field, typed = "咖啡", pressedEnter = true)
        assertNull(e.readback)
        assertEquals(EffectKind.Done, e.kind)
    }

    @Test fun clearedFieldReadsBackEmpty() {
        val f = listOf(0, 100, 999, 180)
        val full = el("旧的字", f, "edit", editable = true, focused = true, id = "q")
        val e = classify(Verb.ClearText, screen("com.x", full), screen("com.x", el("搜索", f, "edit", editable = true, hint = true, focused = true, id = "q")), target = full)
        assertEquals(EffectKind.Done, e.kind)
        assertEquals("", e.readback!!.text)
    }

    @Test fun frameDecidesWhenTheTreeSaysNothing() {
        val s = settings()
        // The screen changed a piece at the target (the state after the switch and ripples): small
        // changes
        assertEquals(EffectKind.SmallChange, classify(Verb.Tap, s, s, frame = FrameChange(0.02, 0.4)).kind)
        // Only the screen is moving but the element table remains unchanged: it is considered a small
        // change at most (videos and animations are not considered "done")
        assertEquals(EffectKind.SmallChange, classify(Verb.Tap, s, s, frame = FrameChange(0.6, 0.6)).kind)
        // Self-drawn interface (almost no elements): a large area has been changed and it’s done
        val drawn = screen("com.game")
        assertEquals(EffectKind.Done, classify(Verb.Tap, drawn, drawn, frame = FrameChange(0.6, null)).kind)
        assertEquals(EffectKind.NoEffect, classify(Verb.Tap, drawn, drawn, frame = FrameChange(0.0, 0.0)).kind)
    }

    @Test fun withoutTheBeforeScreenOnlyThePortAndFrameDecide() {
        val a = settings()
        assertEquals(EffectKind.NoEffect, ActionEffect.classify(Verb.Tap, null, a, PortFacts(changed = false), null)!!.kind)
        assertEquals(EffectKind.SmallChange, ActionEffect.classify(Verb.Tap, null, a, PortFacts(changed = true), null)!!.kind)
        assertNull(ActionEffect.classify(Verb.Tap, null, a, PortFacts(), null))
    }

    /** Flip the switch: the name and position remain unchanged, only the status of the control changes. */
    @Test fun toggledSwitchIsSeenFromItsState() {
        val off = screen("com.android.settings", el("WLAN", listOf(0, 100, 999, 180), "Switch", id = "android:id/switch_widget"), el("已保存的网络", listOf(0, 300, 999, 380)))
        val on = screen("com.android.settings", el("WLAN", listOf(0, 100, 999, 180), "Switch", id = "android:id/switch_widget", states = setOf(UiStates.CHECKED)), el("已保存的网络", listOf(0, 300, 999, 380)))
        val e = classify(Verb.Tap, off, on)
        assertEquals(EffectKind.SmallChange, e.kind)
        assertEquals(1, e.diff!!.toggled)
        assertEquals(1, e.toJson()["toggled"]!!.jsonPrimitive.content.toInt())
    }

    /** There is no effect if you don't tell when you are not sure: the port has been verified to have changed, but the structure cannot be seen, at least it is a small change. */
    @Test fun portSaysChangedNeverReadsAsNoEffect() {
        val s = settings()
        assertEquals(EffectKind.SmallChange, classify(Verb.Tap, s, s, changed = true).kind)
        assertEquals(EffectKind.SmallChange, classify(Verb.Tap, s, s, changed = true, frame = FrameChange(0.0, 0.0)).kind)
    }

    @Test fun stillLoadingIsSmallChangeNotNoEffect() {
        val s = settings()
        val e = classify(Verb.Tap, s, s, loading = true)
        assertEquals(EffectKind.SmallChange, e.kind)
        assertTrue(e.loading)
        assertEquals(true, e.toJson()["mayStillBeLoading"]!!.jsonPrimitive.content.toBoolean())
        // There is a progress bar on the interface
        val spinning = screen("com.shop", el("加载中", listOf(400, 400, 600, 600), "ProgressBar"))
        assertTrue(classify(Verb.Tap, spinning, spinning).loading)
        // The interface is still moving while reading.
        assertEquals(EffectKind.SmallChange, classify(Verb.Tap, s, s.copy(changedDuringRead = true)).kind)
    }
}
