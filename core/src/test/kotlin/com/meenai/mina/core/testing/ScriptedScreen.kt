package com.meenai.mina.core.testing

import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement

/** A screen in the script. */
data class Screen(val app: String, val elements: List<UiElement>)

fun button(ref: String, name: String, y: Int = 500) =
    UiElement(ref, "button", name, 540, y * 24 / 10, listOf(0, y - 40, 999, y + 40), null, true, false, false)

fun label(ref: String, name: String, y: Int = 200) =
    UiElement(ref, "text", name, 540, y * 24 / 10, listOf(0, y - 40, 999, y + 40), null, false, false, false)

fun field(ref: String, name: String, y: Int = 900, hint: Boolean = true) =
    UiElement(ref, "edit", name, 540, y * 24 / 10, listOf(0, y - 40, 999, y + 40), null, true, true, false, hint = hint)

/**
 * The mobile phone screen changes according to the script: reading the screen (numbered s1, s2...) gives
 * the current screen; each writing operation takes the next result in [results] (when used up, it is Ok
 * (changed=true)). If the result is that the screen has changed, Ok will change to the next screen (it
 * will stop at the last screen after the last screen). The peek numbers p1, p2... are not registered.
 */
class ScriptedScreen(private val screens: List<Screen>, results: List<ActionResult> = emptyList()) : ScreenPort {
    private val results = ArrayDeque(results)
    private var index = 0
    private var reads = 0
    private var peeks = 0
    val calls = mutableListOf<String>()
    val current: Screen get() = screens[index]

    private fun read(id: String) = ScreenSnapshot(id, current.app, current.elements, 1080, 2400)
    override suspend fun snapshot() = read("s${++reads}")
    override suspend fun peek() = read("p${++peeks}")
    override suspend fun screenshotPng(): ByteArray? = null

    private fun act(what: String): ActionResult {
        calls += what
        val r = results.removeFirstOrNull() ?: ActionResult.Ok(changed = true)
        if (r is ActionResult.Ok && r.changed != false && index < screens.lastIndex) index++
        return r
    }

    override suspend fun tapRef(ref: String, snapshotId: String) = act("tap $ref $snapshotId")
    override suspend fun tapXY(x: Int, y: Int) = act("tapXY $x $y")
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = act("type $ref $text")
    override suspend fun clearText(ref: String?, snapshotId: String?) = act("clear $ref")
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = act("scroll $down")
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = act("phone_swipe")
    override suspend fun back() = act("phone_back")
    override suspend fun pressKey(keyCode: Int) = act("key $keyCode")
    override suspend fun openApp(packageName: String, cold: Boolean) = act("open $packageName")
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = screens.map { it.app }.distinct().map { it to it }
}
