package com.meenai.mina.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The read-only constraint of the read script pre-script, run it again: install
 * [ReadScriptProtocol.PRELUDE] in Node with a set of fake DOM. Viewing and modifying pages, clicks,
 * jumps, any network requests, reading secrets, and modifying built-in objects are all blocked, and
 * reading is normal. Skip if there is no Node on the machine.
 */
class ReadScriptEnforcementTest {
    companion object {
        private val cases = linkedMapOf<String, Pair<String, JsonObject?>>()
        private val pages = mutableMapOf<String, String>()
        private lateinit var cache: JsonObject

        /** [page]: Run what the previous page did (pageActions of harness.js), and restore it after running. */
        private fun case(name: String, code: String, args: JsonObject? = null, page: String? = null) { cases[name] = code to args; page?.let { pages[name] = it } }

        internal fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
            runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().let { it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
        }

        @BeforeClass @JvmStatic fun runAll() {
            case("count", "document.querySelectorAll('li').length")
            case("texts", "[...document.querySelectorAll('li')].map(e => e.textContent)")
            case("attrs", "const out = []; document.querySelectorAll('li').forEach(e => out.push(e.getAttribute('data-id'))); return out;")
            case("title", "document.title")
            case("setText", "document.querySelector('li').textContent = 'x'; return 1;")
            case("setTitle", "document.title = 'x'")
            case("append", "document.body.appendChild(document.querySelector('li'))")
            case("create", "document.createElement('div')")
            case("click", "document.querySelector('li').click()")
            case("setAttr", "document.querySelector('li').setAttribute('a', 'b')")
            case("callbackSet", "document.querySelectorAll('li').forEach(e => e.setAttribute('a', 'b')); return 1;")
            case("viaCall", "const li = document.querySelector('li'); return li.setAttribute.call(li, 'a', 'b');")
            case("viaReflect", "const li = document.querySelector('li'); return Reflect.apply(li.setAttribute, li, ['a', 'b']);")
            case("viaProto", "const p = Object.getPrototypeOf(document.querySelector('li')); return p === null;")
            case("defineProp", "Object.defineProperty(document.querySelector('li'), 'x', { value: 1 })")
            case("setValue", "document.querySelector('input').value = 'x'")
            case("navigateHref", "location.href = 'https://evil.test/'")
            case("navigateAssign", "location.assign('https://evil.test/')")
            case("openWindow", "window.open('https://evil.test/')")
            case("cookie", "document.cookie")
            case("password", "document.querySelector('input').value")
            case("hiddenAttr", "document.querySelectorAll('input')[1].getAttribute('value')")
            case("visibleValue", "document.querySelectorAll('input')[2].value")
            case("formHtml", "document.querySelector('form').outerHTML")
            case("byValue", "document.querySelector('input[value^=h]')")
            for ((i, sel) in SelectorPolicyTest.BY_VALUE.withIndex()) case("byValue$i", "document.querySelector(args.s)", buildJsonObject { put("s", sel) })
            for ((i, sel) in SelectorPolicyTest.NOT_BY_VALUE.withIndex()) case("notByValue$i", "document.querySelector(args.s)", buildJsonObject { put("s", sel) })
            case("selectorObject", "document.querySelector({ toString: () => 'input[value^=h]' })")
            case("selectorArray", "document.querySelector(['input[value^=h]'])")
            case("selectorFlip", "let n = 0; return document.querySelectorAll({ toString: () => (n++ ? 'input[value^=h]' : 'li') }).length;")
            case("attrFlip", "let n = 0; return document.querySelectorAll('input')[1].getAttribute({ toString: () => (n++ ? 'value' : 'x') });")
            case("textareas", "const [code, note] = document.querySelectorAll('textarea'); return [code.value, code.textLength, code.outerText, code.innerText, code.name, note.value, note.textLength, note.outerText];")
            case("areaText", "const [code, note] = document.querySelectorAll('textarea'); const c = code.firstChild, n = note.firstChild; return [c.data, c.nodeValue, c.textContent, c.wholeText, c.length, c.substringData(0, 3), code.childNodes.length, n.data, n.substringData(0, 5)];")
            case("areaCdata", "const c = document.querySelector('iframe').contentDocument.body.children[0].children[3].firstChild; return [c.nodeType, c.data, c.nodeValue, c.textContent, c.wholeText, c.length];")
            case("areaContainers", "const f = document.querySelector('form'); const b = document.body.textContent; return [f.textContent, f.innerText, b.includes('otp-in-textarea'), b.includes('leave at door'), document.querySelector('div').shadowRoot.textContent, document.querySelector('iframe').contentDocument.body.textContent];")
            // Take the child text node and value attribute node and do not read them until the page
            // changes the word and deletes the attribute (the node is separated from the document) and
            // then reads it again.
            case("holdArea", "state.area = document.querySelector('div').shadowRoot.childNodes[2].firstChild; return 1;")
            case("heldArea", "const n = state.area; return [n.parentNode === null, n.data, n.nodeValue, n.textContent, n.wholeText, n.length, n.substringData(0, 3)];", page = "typeBack")
            case("holdAreaIter", "for (const n of document.querySelector('div').shadowRoot.childNodes[2].childNodes) state.area2 = n; return 1;")
            case("heldAreaIter", "const n = state.area2; return [n.parentNode === null, n.data, n.textContent];", page = "typeBack")
            case("holdAttr", "state.attr = document.querySelector('input').attributes.getNamedItem('value'); return 1;")
            case("heldAttr", "const a = state.attr; return [a.ownerElement === null, a.value, a.nodeValue, a.textContent];", page = "dropValue")
            case("areaDesc", "const a = document.querySelector('a'), s = document.querySelector('select'), o = s.options[1]; return [a.text, o.text, o.label, o.value, s.value, s.selectedIndex, s.options[0].value];")
            case("areaShown", "const p = document.querySelector('p'), s = document.querySelector('select'); return [p.innerText, p.outerText, p.textContent, s.innerText, s.options[1].innerText];")
            case("areaDesc2", "const s = document.querySelectorAll('select')[1], o = s.options[0], out = document.querySelector('output'); return [s.value, o.value, o.text, o.label, out.value, out.defaultValue];")
            case("noSelection", "const s = document.querySelector('select'); return [s.selectedIndex, s.value];", page = "unselect")
            // When the node is obtained, the frame is not yet secret (it can be read first). The page
            // then changes the frame to secret, writes the words into the node, and then the node leaves
            // the document.
            case("holdLate", "state.late = document.querySelectorAll('textarea')[1].firstChild; state.lateAttr = document.querySelectorAll('input')[2].attributes.getNamedItem('value'); return [state.late.data, state.lateAttr.value];")
            case("heldLate", "const n = state.late, a = state.lateAttr; return [n.parentNode === null, n.data, n.textContent, a.ownerElement === null, a.value, a.nodeValue];", page = "lateSecret")
            case("frameFields", "const f = document.querySelector('iframe').contentDocument.body.children[0]; return [f.children[0].value, f.children[1].value, f.children[0].selectionEnd, f.children[1].type, f.children[2].value];")
            case("frameHtml", "document.querySelector('iframe').contentDocument.body.innerHTML")
            case("shadowHtml", "document.querySelector('div').shadowRoot.innerHTML")
            case("shadowValue", "document.querySelector('div').shadowRoot.childNodes[0].value")
            case("toggledValue", "document.querySelector('input').value", page = "showPassword")
            case("toggledHtml", "document.querySelector('form').outerHTML", page = "showPassword")
            case("templateHtml", "document.querySelector('form').innerHTML")
            case("svgTemplate", "const h = document.body.innerHTML; return [h.includes('<circle'), h.includes('hunter2'), h.includes('tpl-secret')];")
            case("attrNode", "const a = document.querySelector('input').attributes; return [a.getNamedItem('value').value, Array.from(a, (x) => x.name + '=' + x.value), Array.from(document.querySelectorAll('input')).find((x) => x.name === 'q').attributes.getNamedItem('value').value];")
            case("frameAttr", "const f = document.querySelector('iframe').contentDocument.body.children[0].children; return [f[1].attributes[2].name, f[1].attributes[2].value, f[2].attributes[2].value];")
            case("fieldProps", "const xs = document.querySelectorAll('input'); return [xs[3].valueAsNumber, xs[0].selectionEnd, xs[0].type, xs[3].name, xs[0].tagName, xs[2].selectionEnd];")
            case("post", "await fetch('/api', { method: 'POST' })")
            case("body", "await fetch('/api', { body: 'x' })")
            case("crossOrigin", "await fetch('https://evil.test/api')")
            case("get", "const r = await fetch('/api/items?page=2'); return { status: r.status, url: r.url, data: await r.json() };")
            case("plainFetch", "fetch('/api/items')")
            case("xhr", "const x = new window.XMLHttpRequest(); x.open('GET', '/api'); x.send(); return 1;")
            case("beacon", "window.navigator.sendBeacon('/log', 'x')")
            case("image", "new window.Image()")
            case("socket", "new window.WebSocket('wss://shop.test/s')")
            case("events", "new window.EventSource('/stream')")
            case("readNavigator", "window.navigator.userAgent")
            case("stateSet", "state.n = 41; return 1;")
            case("stateGet", "state.n + 1")
            case("args", "args.q + '!'", buildJsonObject { put("q", "shoes") })
            case("argsFrozen", "args.q = 'x'; return args.q;", buildJsonObject { put("q", "shoes") })
            case("hiddenGlobals", "[typeof process, typeof globalThis, typeof self, typeof setTimeout]")
            case("stringCode", "(() => {}).constructor('return 1')()")
            case("patchBuiltin", "WeakMap.prototype.get = () => 1; return 1;")
            case("rebindGlobal", "String = () => 'x'; return document.querySelectorAll('input')[1].getAttribute('value');")
            case("afterRebind", "[String(1), document.querySelectorAll('input')[1].getAttribute('value')]")
            case("big", "'x'.repeat(100)")
            case("syntax", "return (")
            case("errorLine", "const a = 1;\nnull.x;")
        }

        private fun results(): JsonObject {
            if (::cache.isInitialized) return cache
            val n = node()
            assumeTrue("no node on this machine", n != null)
            val dir = kotlin.io.path.createTempDirectory("read-script").toFile()
            val prelude = File(dir, "prelude.js").apply { writeText(ReadScriptProtocol.PRELUDE) }
            val list = File(dir, "cases.json").apply {
                writeText(buildJsonArray {
                    for ((name, c) in cases) add(buildJsonObject {
                        put("name", name); put("code", c.first); c.second?.let { put("args", it) }; pages[name]?.let { put("page", it) }
                        if (name == "big") put("resultChars", 10)
                    })
                }.toString())
            }
            val harness = File(dir, "harness.js").apply { writeText(ReadScriptEnforcementTest::class.java.getResource("/read-script/harness.js")!!.readText()) }
            val p = ProcessBuilder(n, harness.path, prelude.path, list.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
            cache = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrElse { error("harness output: $out") }
            dir.deleteRecursively()
            return cache
        }
    }

    private fun r(name: String): JsonObject = results().getValue(name).jsonObject
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun ok(name: String): String { val o = r(name); assertEquals("$name: $o", "true", o.s("ok")); return o.s("json")!! }
    private fun blocked(name: String, code: String) {
        val o = r(name)
        assertEquals("$name: $o", "false", o.s("ok"))
        assertEquals("$name: $o", code, o.s("error"))
        assertEquals("$name changed the page", 0, o.getValue("mutations").jsonPrimitive.int)
        assertEquals("$name clicked", 0, o.getValue("clicks").jsonPrimitive.int)
        assertEquals("$name navigated", 0, o.getValue("navigations").jsonPrimitive.int)
    }

    @Test fun readsTheRealDom() {
        assertEquals("2", ok("count"))
        assertEquals("""["apple","pear"]""", ok("texts"))
        assertEquals("""["1","2"]""", ok("attrs"))
        assertEquals("\"Shop\"", ok("title"))
        assertEquals("true", ok("viaProto"))
        assertEquals("\"shoes\"", ok("visibleValue"))
    }

    @Test fun mutationIsBlocked() {
        for (n in listOf("setText", "setTitle", "append", "create", "click", "setAttr", "callbackSet", "viaCall", "viaReflect", "defineProp", "setValue")) blocked(n, "read_only")
    }

    @Test fun navigationIsBlocked() {
        for (n in listOf("navigateHref", "navigateAssign", "openWindow")) blocked(n, "read_only")
    }

    @Test fun secretsStayHidden() {
        blocked("cookie", "read_only")
        assertEquals("\"***\"", ok("password"))
        assertEquals("\"***\"", ok("hiddenAttr"))
        val html = ok("formHtml")
        assertTrue(html, "hunter2" !in html && "tok-123" !in html && "482913" !in html && "otp-in-textarea" !in html && "shoes" in html && "leave at door" in html)
        blocked("byValue", "invalid_selector")
        // Attributes that are not in the whitelist on the secret input box interface (those whose values
        // or lengths can be calculated) are also ***; only the settings set on the page are read, and
        // other elements are read as usual.
        assertEquals("""["***","***","password","otp","INPUT",5]""", ok("fieldProps"))
        assertEquals("""["***","***","***","***","code","leave at door",13,"leave at door"]""", ok("textareas"))
    }

    @Test fun framesShadowRootsAndCopiesStayHidden() {
        // The nodes in the same-origin iframe belong to that frame's own set of interfaces: recognized
        // by the native getter, the attributes of value, hidden field, and length are still the same
        // ***, and the ordinary frame reads them as usual
        assertEquals("""["***","***","***","hidden","frame-q"]""", ok("frameFields"))
        val frame = ok("frameHtml")
        assertTrue(frame, "tok-frame" !in frame && "frame-q" in frame)
        // The innerHTML of the shadow root is also coded
        val shadow = ok("shadowHtml")
        assertTrue(shadow, "shadow-default" !in shadow && "shadow-token" !in shadow && "password" in shadow)
        assertEquals("\"***\"", ok("shadowValue"))
        // Read the password box that was changed to text after the world was built: both the value and
        // HTML are still *** (code to see the original, there is no such record on the copy)
        assertEquals("\"***\"", ok("toggledValue"))
        val toggled = Json.parseToJsonElement(ok("toggledHtml")).jsonPrimitive.content
        assertTrue(toggled, "hunter2" !in toggled && "type=\"text\"" in toggled)
        // The input box in the template is also coded; the element with the same name in the SVG has no
        // content, and the HTML is read as usual.
        val template = ok("templateHtml")
        assertTrue(template, "tpl-secret" !in template && "<template>" in template)
        assertEquals("[true,false,false]", ok("svgTemplate"))
        // Attribute node: The value of the secret input box is read as ***, and the ordinary box is read
        // as follows
        assertEquals("""["***",["type=password","value=***"],"shoes"]""", ok("attrNode"))
        assertEquals("""["value","***","frame-q"]""", ok("frameAttr"))
        // The copy used for coding is not built into the page document: reading the HTML will not
        // construct the page's custom elements, and the original will not be modified.
        val html = listOf("formHtml", "frameHtml", "shadowHtml", "toggledHtml", "templateHtml", "svgTemplate")
        for (n in html) assertEquals(n, 0, r(n).getValue("pageCode").jsonPrimitive.int)
        for (n in html) assertEquals(n, "false", r(n).s("pageChanged"))
    }

    @Test fun secretTextareaTextStaysHidden() {
        // The words in the secret textarea are their initial values: the sub-text nodes (including CDATA
        // segments) are read as ***; the textContent in the outer element, shadow root, homologous
        // iframe, and the innerText of the outer element that is not rendered are all coded on the copy
        // and then fetched; the words in the ordinary textarea are read as follows
        assertEquals("""["***","***","***","***","***","***",1,"leave at door","leave"]""", ok("areaText"))
        assertEquals("""[4,"***","***","***","***","***"]""", ok("areaCdata"))
        assertEquals("""["***leave at doorcard","***leave at doorcard",false,true,"***","***"]""", ok("areaContainers"))
        // The child text node and value attribute node in the hand: after the page replaces the word and
        // deletes the attribute, the node is separated from the document, but the word is still on it,
        // and it is read as ***
        assertEquals("""[true,"***","***","***","***","***","***"]""", ok("heldArea"))
        assertEquals("""[true,"***","***"]""", ok("heldAreaIter"))
        assertEquals("""[true,"***","***","***"]""", ok("heldAttr"))
        // The frame is not secret when the node is obtained: the frame later becomes secret, words are
        // written into the same node, and after the node leaves the document, the calculation is based
        // on that frame
        assertEquals("""["leave at door","shoes"]""", ok("holdLate"))
        assertEquals("""[true,"***","***",true,"***","***"]""", ok("heldLate"))
        // a. The text of option is calculated based on the text of descendants; the value of select is
        // calculated based on the item selected on the page.
        assertEquals("""["Link ***","Card ***","Card ***","Card ***","Card ***",1,"One"]""", ok("areaDesc"))
        // When the option has a value attribute, value is that attribute; the value and defaultValue of
        // the output are also calculated as descendants; when there is no selected item, the value of
        // select is empty.
        assertEquals("""["v-attr","v-attr","Pick ***","Pick ***","Out ***","Out ***"]""", ok("areaDesc2"))
        assertEquals("""[-1,""]""", ok("noSelection"))
        // The innerText of the typeset element does not contain the words in the textarea, and is given
        // as it is; the option is written according to its own text, and then fetched from the copy
        assertEquals("""["Code: ","Code: ","Code: ***","OneCard ***","Card ***"]""", ok("areaShown"))
        for (n in listOf("areaText", "areaCdata", "areaContainers", "heldArea", "heldAreaIter", "heldAttr", "heldLate", "areaDesc", "areaDesc2", "noSelection", "areaShown")) {
            assertEquals(n, 0, r(n).getValue("pageCode").jsonPrimitive.int)
            assertEquals(n, "false", r(n).s("pageChanged"))
        }
    }

    @Test fun valueSelectorsAreRefused() {
        // Escapes, namespaces, comments, url(,:has() are all blocked (use the same batch as the Kotlin
        // version); other selectors are handed to the page as usual
        for (i in SelectorPolicyTest.BY_VALUE.indices) blocked("byValue$i", "invalid_selector")
        for (i in SelectorPolicyTest.NOT_BY_VALUE.indices) assertTrue("notByValue$i: ${r("notByValue$i")}", r("notByValue$i").s("error") != "invalid_selector")
        // When the selector or attribute name is not a string, the string is only changed once: the one
        // checked and handed to the page are the same, and it is useless to give different toString
        // values every time.
        blocked("selectorObject", "invalid_selector")
        blocked("selectorArray", "invalid_selector")
        // If you transfer it once when checking and again when handing it to the browser, toString will
        // be changed to the one selected by value the second time and it will pass.
        assertEquals("2", ok("selectorFlip"))
        assertEquals("null", ok("attrFlip"))
    }

    @Test fun noNetworkAtAll() {
        // Same-origin GET does not work either; other ways to send requests (XHR, sendBeacon, pictures,
        // WebSocket, EventSource) also report blocked_network, and not a single request is sent.
        for (n in listOf("post", "body", "crossOrigin", "get", "plainFetch", "xhr", "beacon", "image", "socket", "events")) {
            blocked(n, "blocked_network")
            assertTrue(n, r(n).s("message").orEmpty().contains("web_capture"))
            assertEquals(n, 0, (r(n).getValue("requests") as JsonArray).size)
        }
        // Read existing content on the page as usual
        assertEquals("\"test\"", ok("readNavigator"))
    }

    @Test fun stateAndArgs() {
        ok("stateSet")
        assertEquals("42", ok("stateGet"))
        assertEquals("\"shoes!\"", ok("args"))
        assertEquals("script_error", r("argsFrozen").s("error"))
    }

    @Test fun noWayOut() {
        assertEquals("""["undefined","undefined","undefined","undefined"]""", ok("hiddenGlobals"))
        blocked("stringCode", "read_only")
        assertEquals("script_error", r("patchBuiltin").s("error"))
        // The global name cannot be changed: rewriting String will invalidate the property name check
        // and secret input box judgment, as well as subsequent script readings.
        assertEquals("script_error", r("rebindGlobal").s("error"))
        assertEquals("""["1","***"]""", ok("afterRebind"))
    }

    @Test fun resultsAreCappedAndErrorsLocated() {
        assertEquals("\"xxxxxxxxx", ok("big"))
        assertEquals("true", r("big").s("truncated"))
        assertEquals("script_error", r("syntax").s("error"))
        assertEquals("2", r("errorLine").s("line"))
    }
}
