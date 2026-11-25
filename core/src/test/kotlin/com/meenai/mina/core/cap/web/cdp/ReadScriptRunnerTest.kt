package com.meenai.mina.core.cap.web.cdp

import com.meenai.mina.core.cap.web.EvalJs
import com.meenai.mina.core.cap.web.ReadScriptProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read the script executor against the fake DevTools: The world is left to reuse, rebuild the page after changing the document, as well as jump and non-GET requests. */
class ReadScriptRunnerTest {
    private val origin = "https://shop.test"

    /** [onRun] What to do when receiving the message Runtime.evaluate from running the script: return a result, or send an event, but do not return. */
    private fun server(onRun: (FakeDevTools.Conn, JsonObject) -> Unit): FakeDevTools {
        val s = FakeDevTools()
        var ctx = 0
        s.onMessage = { c, m ->
            val method = m.getValue("method").jsonPrimitive.content
            val params = m["params"]?.jsonObject ?: JsonObject(emptyMap())
            fun reply(json: String) = c.reply(m, Json.parseToJsonElement(json))
            when (method) {
                "Page.getFrameTree" -> reply("""{"frameTree":{"frame":{"id":"M"}}}""")
                "Page.createIsolatedWorld" -> reply("""{"executionContextId":${++ctx}}""")
                "Runtime.evaluate" -> {
                    val e = params.getValue("expression").jsonPrimitive.content
                    when {
                        e == ReadScriptProtocol.PROBE -> reply("""{"result":{"type":"string","value":"$origin"}}""")
                        e.startsWith("globalThis.__minaReadRun(") -> onRun(c, m)
                        else -> reply("""{"result":{"type":"undefined"}}""")
                    }
                }
                else -> reply("{}")
            }
        }
        return s
    }

    private fun <T> withRunner(server: FakeDevTools, block: suspend (ReadScriptRunner) -> T): T = runBlocking {
        withTimeout(20_000) {
            val s = CdpSession.connect(server, "x", "/devtools/page/A")
            try { block(ReadScriptRunner(s)) } finally { s.close() }
        }
    }

    private fun methods(s: FakeDevTools) = s.connections.single().received.map { it.getValue("method").jsonPrimitive.content }

    private fun answer(c: FakeDevTools.Conn, m: JsonObject, json: String) =
        c.reply(m, buildJsonObject { put("result", buildJsonObject { put("type", "object"); put("value", Json.parseToJsonElement(json)) }) })

    @Test fun keepsTheWorldUntilThePageChanges() {
        var destroyNext = false
        lateinit var srv: FakeDevTools
        srv = server { c, m ->
            // There is no context destruction event when Runtime is not enabled: the main frame has
            // changed the document and is reported by Page.frameNavigated
            if (destroyNext) { destroyNext = false; c.event("Page.frameNavigated", buildJsonObject { put("frame", buildJsonObject { put("id", "M"); put("url", "https://shop.test/2") }) }) }
            else answer(c, m, """{"ok":true,"json":"1","truncated":false}""")
        }
        val (first, second, gone, third) = withRunner(srv) { r ->
            val a = r.run("1", null, 2_000)
            val b = r.run("1", null, 2_000)
            destroyNext = true
            val g = r.run("1", null, 2_000)
            listOf(a, b, g, r.run("1", null, 2_000))
        }
        assertEquals(EvalJs.Ok("1", false, fresh = true), first)
        assertEquals(EvalJs.Ok("1", false, fresh = false), second)
        assertEquals("navigated", (gone as EvalJs.Failed).error)
        assertEquals(EvalJs.Ok("1", false, fresh = true), third)
        assertEquals(2, methods(srv).count { it == "Page.createIsolatedWorld" })
        // Page detects debugger when Runtime is on: never opens
        assertTrue("Runtime.enable" !in methods(srv))
    }

    @Test fun onlyTheMainFrameChangingDocumentCounts() {
        fun nav(json: String) = Json.parseToJsonElement(json).jsonObject
        assertTrue(mainFrameNavigated(nav("""{"frame":{"id":"M","url":"https://a.test/"}}"""), "M"))
        assertTrue(!mainFrameNavigated(nav("""{"frame":{"id":"F","parentId":"M","url":"https://ads.test/"}}"""), "M"))
        assertTrue(!mainFrameNavigated(nav("""{"frame":{"id":"N","url":"https://a.test/"}}"""), "M"))
        assertTrue(!mainFrameNavigated(nav("""{"frame":{"id":"M"}}"""), null))
    }

    @Test fun navigationWhileRunningIsStopped() {
        val srv = server { c, _ -> c.event("Page.frameRequestedNavigation", buildJsonObject { put("frameId", "M"); put("url", "https://evil.test/") }) }
        val r = withRunner(srv) { it.run("1", null, 5_000) }
        assertEquals("blocked_navigation", (r as EvalJs.Failed).error)
        val sent = methods(srv)
        assertTrue(sent.toString(), "Page.stopLoading" in sent)
        // Make fetch and sleep after the script fail at the end
        assertTrue(srv.connections.single().received.any { it["params"]?.jsonObject?.get("expression")?.jsonPrimitive?.content == ReadScriptProtocol.STOP })
    }

    @Test fun postFromTheScriptEndsTheRun() {
        val srv = server { c, _ ->
            c.event("Network.requestWillBeSent", Json.parseToJsonElement("""
                {"request":{"method":"POST","url":"https://shop.test/api/cart"},
                 "initiator":{"type":"script","stack":{"callFrames":[{"url":"${ReadScriptProtocol.SOURCE_URL}"}]}}}""").jsonObject)
        }
        val r = withRunner(srv) { it.run("1", null, 5_000) }
        assertEquals("blocked_network", (r as EvalJs.Failed).error)
    }

    @Test fun timesOut() {
        val srv = server { _, _ -> }
        val r = withRunner(srv) { it.run("for(;;){}", null, 1_000) }
        assertEquals("timeout", (r as EvalJs.Failed).error)
    }
}
