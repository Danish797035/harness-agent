package com.meenai.mina.core.model

import com.meenai.mina.core.agent.FakeModel
import com.meenai.mina.core.model.ModelJson.field
import com.meenai.mina.core.verify.Criteria
import com.meenai.mina.core.verify.Evidence
import com.meenai.mina.core.verify.Verdict
import com.meenai.mina.core.verify.Verifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The appearance of JSON returned by each model is different: a tolerant reading method, if you can't read it, ask again and just ask for JSON. */
class ModelJsonTest {
    private val verdict = """{"checks": [{"criterion": "电量", "status": "met", "quote": "86%"}], "verdict": "pass", "reason": "屏上有"}"""

    @Test fun readsObjectsInTheShapesModelsReplyIn() {
        val shapes = listOf(
            verdict,
            "```json\n$verdict\n```",
            "好的,核对结果如下:\n$verdict\n以上。",
            // The object is followed by a description with curly braces: you cannot get from the first {
            // to the last }
            "$verdict\n注:引用格式为 {原文}。",
            // There is a paragraph of curly braces in front of it that is not JSON.
            "按 {checks, verdict} 的格式回答:$verdict",
            // Parentheses in strings do not disrupt balancing
            """{"checks": [{"criterion": "标题含 } 和 {", "status": "met", "quote": "a}b{"}], "verdict": "pass", "reason": "x"}""",
        )
        for (s in shapes) assertTrue(s, Verifier.parse(s)?.verdict == Verdict.Pass)
        assertNull(ModelJson.firstObject("没有 JSON"))
        assertNull(ModelJson.firstObject("{坏的"))
    }

    @Test fun keyVariantsAreAccepted() {
        val o = ModelJson.firstObject("""{"Final_Verdict": "x", "VERDICT": "pass"}""")!!
        assertEquals("pass", o.field("verdict")!!.jsonPrimitive.content)
        val v = Verifier.parse("""{"Checks": [{"Criterion": "电量", "Status": "MET", "Quote": "86%"}], "Verdict": "Pass", "Reason": "ok"}""")!!
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals("met", v.checks.single().status)
    }

    @Test fun verifierRetriesOnceAskingForJsonOnly() = runTest {
        val criteria = Criteria(listOf("电量"), "read", fallback = false)
        val ev = Evidence(com.meenai.mina.core.verify.freshOf("当前 App:设置\n电量 86%"), null, com.meenai.mina.core.verify.EvidenceLedger(), emptyList(), protected = false)
        fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
        val m = FakeModel(reply("我认为通过了。"), reply(verdict))
        val v = Verifier(m, null).verify("看电量", criteria, "电量 86%\n依据: 「电量 86%」", ev)
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals(2, m.requests.size)
        assertEquals(ModelJson.JSON_ONLY, (m.requests[1].last().parts.single() as Part.Text).text)
        // Can't read it twice: failed to verify, tell the truth
        val bad = Verifier(FakeModel(reply("通过"), reply("还是通过")), null).verify("看电量", criteria, "电量 86%\n依据: 「电量 86%」", ev)
        assertEquals(Verdict.CannotVerify, bad.verdict)
        assertEquals("核查器的回复无法解析", bad.reason)
    }
}
