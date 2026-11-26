package com.meenai.mina.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentGateTest {
    @Test fun classifierSeesCurrentMessageAndRecentContextWithoutTools() = runTest {
        val judge = FakeModel(com.meenai.mina.core.testing.answerReply("DISCUSS"))
        val decision = IntentGate(judge).assess("以后可以帮我同步发布，对吧？",
            listOf(Turn("打开两个 App 看看", "看过了", "succeeded")))
        assertEquals(IntentGate.Intent.Discuss, decision)
        assertTrue(judge.offeredTools.single().isEmpty())
        val input = judge.requests.single().single().parts.single() as com.meenai.mina.core.model.Part.Text
        assertTrue(input.text.contains("The user's current message:\n以后可以帮我同步发布，对吧？"))
        // The previous round of dialogue preceded the current sentence
        assertTrue(input.text, "打开两个 App 看看" in input.text)
        assertTrue(input.text, input.text.indexOf("打开两个 App 看看") < input.text.indexOf("The user's current message:"))
    }

    @Test fun onlyKnownIntentLabelsAreAccepted() {
        assertEquals(IntentGate.Intent.Read, IntentGate.parse(" READ "))
        assertEquals(IntentGate.Intent.Act, IntentGate.parse("ACT"))
        assertNull(IntentGate.parse("maybe"))
    }

    @Test fun fallsBackToMainModelWhenLightJudgeFails() = runTest {
        val light = FakeModel(IllegalStateException("unavailable"))
        val main = FakeModel(com.meenai.mina.core.testing.answerReply("READ"))
        assertEquals(IntentGate.Intent.Read, IntentGate(light, main).assess("查一下订单", emptyList()))
        assertTrue(light.offeredTools.single().isEmpty())
        assertTrue(main.offeredTools.single().isEmpty())
    }

    @Test fun clearCommandsSkipTheJudge() = runTest {
        val judge = FakeModel(IllegalStateException("judge must not be called"))
        val gate = IntentGate(judge)
        assertEquals(IntentGate.Intent.Act, gate.assess("open YouTube", emptyList()))
        assertEquals(IntentGate.Intent.Act, gate.assess("search Motu Patlu", emptyList()))
        assertEquals(IntentGate.Intent.Act, gate.assess("can you open YouTube", emptyList()))
        assertEquals(IntentGate.Intent.Act, gate.assess("youtube kholo", emptyList()))
        assertEquals(IntentGate.Intent.Act, gate.assess("youtube par yo yo honey singh search karo", emptyList()))
        assertEquals(IntentGate.Intent.Act, gate.assess("gana bajao please", emptyList()))
        assertTrue("a clear command must not cost a judge call", judge.requests.isEmpty())
    }

    @Test fun questionsStillGoToTheJudge() = runTest {
        assertEquals(IntentGate.Intent.Discuss, IntentGate(FakeModel(com.meenai.mina.core.testing.answerReply("DISCUSS")))
            .assess("what can you do", emptyList()))
        assertEquals(IntentGate.Intent.Discuss, IntentGate(FakeModel(com.meenai.mina.core.testing.answerReply("DISCUSS")))
            .assess("how do I open YouTube", emptyList()))
        assertEquals(IntentGate.Intent.Discuss, IntentGate(FakeModel(com.meenai.mina.core.testing.answerReply("DISCUSS")))
            .assess("kya aap youtube open kar sakte ho?", emptyList()))
    }

    @Test fun negationsMidSentenceQuestionsAndStativeWordsGoToJudge() = runTest {
        val inputs = listOf(
            "youtube open mat karo",
            "whatsapp mat kholo",
            "flipkart nahi kholna",
            "flipkart kaise open kare",
            "youtube kab chalao",
            "music kahan bajao",
            "kya aap youtube khol sakte ho",
            "ye kyun kholo",
            "phone band hai",
            "app chala gaya",
        )
        for (msg in inputs) {
            val judge = FakeModel(com.meenai.mina.core.testing.answerReply("DISCUSS"))
            assertEquals("Expected judge call for: $msg", IntentGate.Intent.Discuss, IntentGate(judge).assess(msg, emptyList()))
            assertEquals("Judge must be invoked for: $msg", 1, judge.requests.size)
        }
    }

    @Test fun expandedHinglishVerbsAndImperativeBigramsSkipJudge() = runTest {
        val judge = FakeModel(IllegalStateException("judge must not be called"))
        val gate = IntentGate(judge)
        val commands = listOf(
            "flipkart kholna",
            "screen dekho",
            "time batao",
            "song dhundho",
            "thoda ruko",
            "wifi band kar",
            "bluetooth band karo",
            "music chala de",
            "video chala do",
        )
        for (cmd in commands) {
            assertEquals("Expected Act for: $cmd", IntentGate.Intent.Act, gate.assess(cmd, emptyList()))
        }
        assertTrue(judge.requests.isEmpty())
    }

    @Test fun appLaunchShortcutMatchesOnlyClearSingleStepOpenCommands() {
        val apps = listOf(
            "com.flipkart.android" to "Flipkart",
            "com.google.android.youtube" to "YouTube",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
        )
        assertEquals("com.flipkart.android" to "Flipkart", AppLaunchShortcut.match("open Flipkart", apps))
        assertEquals("com.google.android.youtube" to "YouTube", AppLaunchShortcut.match("please launch YouTube app", apps))
        assertEquals("com.whatsapp" to "WhatsApp", AppLaunchShortcut.match("WhatsApp kholo", apps))
        assertNull(AppLaunchShortcut.match("open Flipkart and search shoes", apps))
        assertNull(AppLaunchShortcut.match("flipkart kaise open kare", apps))
        assertNull(AppLaunchShortcut.match("don't open WhatsApp", apps))
        assertNull(AppLaunchShortcut.match("open flipkart.com", apps))
        assertNull(AppLaunchShortcut.match("open NonExistentApp", apps))
    }
}
