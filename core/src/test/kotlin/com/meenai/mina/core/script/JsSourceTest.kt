package com.meenai.mina.core.script

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceTest {
    private fun bad(code: String) = assertTrue(code, JsSource.usesImport(code))
    private fun fine(code: String) = assertFalse(code, JsSource.usesImport(code))

    @Test fun importKeywordAnywhereInCodeIsFound() {
        bad("await import('x')")
        bad("import ( u )")
        bad("import/**/('x')")
        bad("import/* a */ /* b */('x')")
        bad("import//c\n('x')")
        bad("import\n\t(\n'x')")
        bad("import.meta")
        bad("\\u0069mport('x')")
        bad("\\u{69}mport('x')")
        bad("const f = () => import('x')")
        bad("`a\${import('x')}b`")
        bad("`a\${ { k: `\${import('x')}` } }b`")
        bad("x = a\n<!-- import('x')")
    }

    @Test fun wordInsideStringsCommentsAndRegexesIsFine() {
        fine("'import(x)'")
        fine("\"import\"")
        fine("// import('x')\nreturn 1")
        fine("/* import('x') */ 1")
        fine("`import \${1 + 1} import`")
        fine("/import\\(/.test(s)")
        fine("const important = 1; return important")
        fine("[...document.querySelectorAll('li')].map(e => e.textContent)")
    }

    @Test fun ambiguousSlashCannotHideImport() {
        // If the / after `)` is regarded as a division sign, the following quotation marks will swallow
        // the import into the "string"; both reading methods are used, so it is still found
        bad("if (a) /'/; import('x'); '/")
        bad("if (a) {} /'/; import('x'); '/")
        // On the other hand: it is really a division sign. If it is regarded as a regular expression,
        // the import will be swallowed into the "regular expression".
        bad("x = (a) / 2; import('x'); y = 1 / 3")
        bad("i++ /'/; import('x'); '/")
        bad("of /'/; import('x'); '/")
    }

    @Test fun ordinaryDivisionAndRegexesStillReadFine() {
        fine("const r = (a + b) / 2; return r / 3")
        fine("return /a\\/b[/]c/g.test(s) ? x / y : 0")
        fine("const s = 'a/b'; const t = s.split('/')")
    }
}
