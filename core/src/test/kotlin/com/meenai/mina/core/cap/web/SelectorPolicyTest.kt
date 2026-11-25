package com.meenai.mina.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

class SelectorPolicyTest {
    companion object {
        /**
         * They are all written to select elements according to the value attribute (in the browser, when
         * facing two input boxes with different values, the hits are different).
         * ReadScriptEnforcementTest uses the same batch to run the JS version in the page, and both
         * sides must be the same.
         */
        val BY_VALUE = listOf(
            """input[value^="h"]""",
            """input[ VALUE ]""",
            """input[v\61 lue^="h"]""",
            """input[\76 alue]""",
            """input[v\000061lue^="h"]""",
            """input[\v\61 l\u\65 ^="h"]""",
            """input[*|value^="h"]""",
            """input[|value]""",
            """input[/* x */value^="h"]""",
            """input[value/* x */^="h"]""",
            """input[value^='h' i]""",
            """form:has(input[value^="h"])""",
            """input:is([value^="h"])""",
            // Comment marks within quotation marks do not count: if you comment just literally, the
            // [value in the middle will be treated as a comment.
            """input:not([data-x="/*"])[value^="h"]:not([data-y="*/"])""",
            // The newline after the escape in quotation marks is included in the escape, and the
            // backslash and newline in the continuation line do not end the paragraph.
            "input:not([data-x=\"\\41\n\"])[value^=\"h\"]",
            "input:not([data-x=\"\\41\r\n\"])[value^=\"h\"]",
            "input:not([data-x=\"a\\\nb\"])[value^=\"h\"]",
            // The browser will fill in the unfinished quotation marks and square brackets after reading
            // to the end.
            """input[value^="h""",
            // The url without quotation marks (up to ) is a token, and the comment marks and quotation
            // marks inside do not count; if:is() discards this item, the following will still take
            // effect
            """input:is(url(/*),[value^="h"])""",
            """input:is(url(x"),[value^="h"])""",
            """input:where(u\72 l(/*),[value^="h"])""",
            """input:is(URL( /*),[value^="h"])""",
        )

        /** If the selection is not based on the value attribute, it will be released as usual. */
        val NOT_BY_VALUE = listOf(
            "li.item",
            """input[name="q"]""",
            "input[data-value]",
            "input[value-x]",
            "input[valuex]",
            ".value",
            "#value",
            """input[type="value"]""",
            """[title="[value"]""",
            """[title='[value^=h]']""",
            """[data-x|="value"]""",
            "input:not([type=hidden])",
            """[d\61 ta-id]""",
            """[title="/*"], [data-x="*/"]""",
            """a[data-url="x"]""",
            "a.url",
            // The url( in quotation marks is not counted as:url( and must be found after restoration.
            """[style*="url("]""",
        )
    }

    @Test fun valueSelectorsAreCaught() {
        for (s in BY_VALUE) assertTrue(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun otherSelectorsPass() {
        for (s in NOT_BY_VALUE) assertFalse(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun readsLikeCss() {
        assertEquals("[value]", SelectorPolicy.plain("""[v\61 lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[v\000061lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[\v\61 l\u\65 ]"""))
        assertEquals("a b", SelectorPolicy.plain("a/* [value] */b"))
        assertEquals("a ", SelectorPolicy.plain("a/* not closed"))
        assertEquals("""[title=""][x=""]""", SelectorPolicy.plain("""[title="[value\"/*"][x='\27']"""))
        // Line breaks are always counted as \n; unescaped line breaks end the words in quotation marks.
        assertEquals("[a=\"\"\n[value]", SelectorPolicy.plain("[a=\"x\r\n[value]"))
        // 0. Code points in the proxy area and out of range are replaced with replacement characters;
        // the same is true for a single backslash at the end.
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", SelectorPolicy.plain("""\0 \D800 \110000 \"""))
    }

    /** The JS version and the Kotlin version on the page have the same judgment: the above two groups, plus a batch of randomly spelled error-prone fragments before and after the attribute name. Skip if there is no Node on the machine. */
    @Test fun jsVersionAgrees() {
        val node = ReadScriptEnforcementTest.node()
        assumeTrue("no node on this machine", node != null)
        val names = listOf("value", "VaLuE", "v\\61 lue", "\\76 alue", "v\\000061lue", "*|value", "|value", " value", "valu", "valuex", "value-x", "data-value", "val/**/ue", "va\\\nlue")
        val parts = listOf(
            "[", "]", "v", "e", "\\61 ", "\\76", "\\", "\"", "'", "/*", "*/", "*|", "|", " ", "\t", "\n", "\r", "\r\n", "\u000C",
            "x", "=\"h\"", "^", "(", ")", ":is(", "\\\n", "\\41\n", "-", "0", "\u00E9", "url(", "URL(", "u\\72 l(",
        )
        val rnd = Random(7)
        fun noise(n: Int) = buildString { repeat(rnd.nextInt(n)) { append(parts[rnd.nextInt(parts.size)]) } }
        val random = List(3000) { noise(4) + (if (rnd.nextBoolean()) "[" else "") + noise(2) + names[rnd.nextInt(names.size)] + noise(4) }
        val all = BY_VALUE + NOT_BY_VALUE + random
        val dir = kotlin.io.path.createTempDirectory("selector-policy").toFile()
        val input = File(dir, "all.json").apply { writeText(JsonArray(all.map(::JsonPrimitive)).toString()) }
        val script = File(dir, "check.js").apply {
            writeText("'use strict';\n" + SelectorPolicy.JS + "\nconst all = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));\nprocess.stdout.write(JSON.stringify(all.map(selectsByValue)));\n")
        }
        val p = ProcessBuilder(node, script.path, input.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
        dir.deleteRecursively()
        val js = runCatching { Json.parseToJsonElement(out).jsonArray.map { it.jsonPrimitive.boolean } }.getOrElse { error("node output: $out") }
        assertEquals(all.size, js.size)
        for ((i, s) in all.withIndex()) assertEquals(s, SelectorPolicy.selectsByValue(s), js[i])
        // There must be a lot of both results in the random spell, otherwise the comparison will only be
        // one side.
        val hits = random.count { SelectorPolicy.selectsByValue(it) }
        assertTrue("$hits of ${random.size}", hits > 300 && random.size - hits > 300)
    }
}
