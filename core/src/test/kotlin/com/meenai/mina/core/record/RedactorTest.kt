package com.meenai.mina.core.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.verb

class RedactorTest {
    /** Google 新格式的认证 Key(AQ. 开头)和旧的 AIza 一样,经 Redactor 的每条路都遮住。 */
    @Test fun googleAuthKeyMaskedEverywhere() {
        val key = "AQ." + "Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB"
        fun gone(s: String) { assertFalse(s, s.contains(key)); assertFalse(s, s.contains("Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB")) }
        assertEquals("key=*** ok", Redactor.text("key=$key ok"))
        gone(Redactor.evidence("Key: $key"))
        gone(Redactor.args(verb("phone_type_text"), """{"text":"$key"}""", secretField = false))
        gone(Redactor.pageData("""{"value":"$key"}"""))
        val n = Redactor.network(url = "https://a.test/p?x=$key", body = """{"k":"$key"}""", headers = mapOf("X-Goog-Api-Key" to key, "X-Note" to key))
        gone(n.url!!); gone(n.body!!); n.headers!!.values.forEach(::gone)
        // 太短的、不是开头的不算
        assertEquals("AQ.short", Redactor.text("AQ.short"))
        assertEquals("XAQ.Ab8RN6Lq_x-Zt3VwP0kJ9sY", Redactor.text("XAQ.Ab8RN6Lq_x-Zt3VwP0kJ9sY"))
    }

    @Test fun passwordFieldTextMasked() {
        val out = Redactor.args(verb("phone_type_text"), """{"text":"hunter2","ref":"@e3","snapshotId":"s1"}""", secretField = true)
        val o = Json.parseToJsonElement(out).jsonObject
        assertEquals("***", o["text"]!!.jsonPrimitive.content)
        assertEquals("@e3", o["ref"]!!.jsonPrimitive.content)
        assertFalse(out.contains("hunter2"))
    }

    @Test fun apiKeyLikeStringsMasked() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2"
        val t = Redactor.text("key is $key and header Authorization: Bearer abc.DEF-123_xyz done")
        assertFalse(t.contains(key))
        assertFalse(t.contains("abc.DEF-123_xyz"))
        assertTrue(t.contains("***"))
        assertTrue(t.endsWith(" done"))
        val google = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
        val gh = "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val t2 = Redactor.text("g=$google h=$gh")
        assertFalse(t2.contains(google)); assertFalse(t2.contains(gh))
        // 不是密码框,但参数里有 Key 样子的串也要遮
        val a = Redactor.args(verb("phone_type_text"), """{"text":"$key"}""", secretField = false)
        assertFalse(a.contains(key))
    }

    @Test fun ordinaryTextKept() {
        val s = "打开设置,搜索 sky-blue 主题 task-1234 Bearer"
        assertEquals(s, Redactor.text(s))
        val args = """{"text":"你好","pressEnter":true}"""
        assertEquals("你好", Json.parseToJsonElement(Redactor.args(verb("phone_type_text"), args, secretField = false)).jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("""{"ref":"@e1","snapshotId":"s"}""", Redactor.args(verb("phone_tap"), """{"ref":"@e1","snapshotId":"s"}""", secretField = false))
    }

    private fun scriptCode(code: String): String {
        val args = """{"code":${kotlinx.serialization.json.JsonPrimitive(code)}}"""
        return Json.parseToJsonElement(Redactor.args(verb("web_script"), args, secretField = false)).jsonObject["code"]!!.jsonPrimitive.content
    }

    @Test fun scriptStringLiteralsAllMasked() {
        // 不管字怎么交出去:直接给 mina.type、先存变量、拼接、写进 DOM
        val c = scriptCode("const s = await mina.snapshot();\nawait mina.type(find(s, '密码'), 'hunter2');\nconst pw = \"abc\" + 'def';\n" +
            "document.querySelector('#p').value = pw;\nawait mina.type(s.elements[0], `x${'$'}{pw}y${'$'}{'zz'}`);")
        for (secret in listOf("hunter2", "abc", "def", "密码", "#p", "zz", "`x")) assertFalse(secret, secret in c)
        // 只留形状:引号种类、模板里表达式的位置、代码本身
        assertTrue(c, "mina.type(find(s, '***'), '***')" in c)
        assertTrue(c, "\"***\" + '***'" in c)
        assertTrue(c, "`***${'$'}{pw}***${'$'}{'***'}***`" in c)
        assertEquals("const a = '';", scriptCode("const a = '';"))
    }

    @Test fun scriptEscapedQuotesStayInsideLiteral() {
        val c = scriptCode("const a = 'it\\'s hunter2'; const b = \"say \\\"hi\\\"\";")
        assertEquals("const a = '***'; const b = \"***\";", c)
    }

    @Test fun indianDataAndGroqKeyMasked() {
        // gsk_ (Groq) key: pakdo & mat pakdo
        val groq = "gsk_" + "1234567890abcdef1234567890abcdef"
        assertEquals("groq=*** ok", Redactor.text("groq=$groq ok"))
        assertEquals("gsk_short", Redactor.text("gsk_short"))

        // Indian mobile: pakdo (+91, 0, bina prefix, space/dash) & mat pakdo
        assertEquals("call ***", Redactor.text("call +919876543210"))
        assertEquals("call ***", Redactor.text("call +91 9876543210"))
        assertEquals("call ***", Redactor.text("call +91-9876543210"))
        assertEquals("call ***", Redactor.text("call 09876543210"))
        assertEquals("call ***", Redactor.text("call 9876543210"))
        assertEquals("call ***", Redactor.text("call 98765 43210"))
        assertEquals("saal 2026", Redactor.text("saal 2026"))
        assertEquals("order 12345", Redactor.text("order 12345"))
        assertEquals("id 5432167890", Redactor.text("id 5432167890"))

        // Aadhaar: pakdo (12 digit, 4-4-4 space/dash) & mat pakdo
        assertEquals("aadhaar: ***", Redactor.text("aadhaar: 123456789012"))
        assertEquals("aadhaar: ***", Redactor.text("aadhaar: 1234 5678 9012"))
        assertEquals("aadhaar: ***", Redactor.text("aadhaar: 1234-5678-9012"))
        assertEquals("year 2026", Redactor.text("year 2026"))
        assertEquals("partial 1234 5678", Redactor.text("partial 1234 5678"))

        // PAN: pakdo (ABCDE1234F) & mat pakdo
        assertEquals("pan: ***", Redactor.text("pan: ABCDE1234F"))
        assertEquals("pan: ABCDE12345", Redactor.text("pan: ABCDE12345"))
        assertEquals("pan: ABCD1234F", Redactor.text("pan: ABCD1234F"))

        // UPI ID: pakdo (naam@bank) & mat pakdo (normal email with dot domain)
        assertEquals("pay ***", Redactor.text("pay rahul@okhdfcbank"))
        assertEquals("pay ***", Redactor.text("pay 9876543210@paytm"))
        assertEquals("email naam@gmail.com", Redactor.text("email naam@gmail.com"))
        assertEquals("email user@example.co.in", Redactor.text("email user@example.co.in"))

        // OTP English keywords: otp, one time password, verification code (in evidence)
        assertEquals("Your otp is ***", Redactor.evidence("Your otp is 482913"))
        assertEquals("Your one time password is ***", Redactor.evidence("Your one time password is 482913"))
        assertEquals("Your verification code is ***", Redactor.evidence("Your verification code is 482913"))
        assertEquals("meeting in 2026", Redactor.evidence("meeting in 2026"))
        assertEquals("count 1234 items", Redactor.evidence("count 1234 items"))
    }
}
