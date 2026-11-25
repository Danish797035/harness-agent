package com.meenai.mina.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaImageActivityTest {

    @Test
    fun allowedNavigationPermitsArenaAuthAndCaptchaHostsOverHttps() {
        val allowed = listOf(
            "about:blank",
            "ABOUT:BLANK",
            "https://arena.ai/image/direct",
            "https://beta.arena.ai/login",
            "https://lmarena.ai/",
            "https://chat.lmarena.ai/api/session",
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://dash.cloudflare.com/login",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://apis.google.com/js/platform.js",
            "https://ssl.gstatic.com/accounts/ui/logo.png",
            "https://lh3.googleusercontent.com/a/avatar",
            "https://hcaptcha.com/1/api.js",
            "https://newassets.hcaptcha.com/captcha/v1/challenge",
            "https://discord.com/oauth2/authorize",
            "https://github.com/login/oauth/authorize",
        )

        for (url in allowed) {
            assertTrue("Expected allowed navigation for $url", ArenaImageActivity.isAllowedNavigationUrl(url))
        }
    }

    @Test
    fun allowedNavigationBlocksUntrustedHostsAndNonHttpsSchemes() {
        val blocked = listOf(
            "",
            "   ",
            "http://arena.ai/image/direct",
            "http://lmarena.ai/",
            "http://accounts.google.com/",
            "javascript:alert(1)",
            "file:///data/data/com.meenai.mina/files/vault.json",
            "content://com.android.providers.downloads/1",
            "intent://arena.ai/#Intent;scheme=https;end",
            "data:text/html,<script>alert(1)</script>",
            "https://evil.com/arena.ai",
            "https://evil.com/?redirect=https://arena.ai",
            "https://arena.ai.evil.com/",
            "https://lmarena.ai.attacker.net/",
            "https://fakearena.ai/",
            "https://not-cloudflare.com/",
            "https://sub.discord.com/login",
            "https://gist.github.com/user",
            "https://arena.ai@evil.com/login",
        )

        for (url in blocked) {
            assertFalse("Expected blocked navigation for $url", ArenaImageActivity.isAllowedNavigationUrl(url))
        }

        // Also check raw host tricks directly
        assertFalse(ArenaImageActivity.isAllowedNavigation("https", "evil.com\\@arena.ai", "https://evil.com\\@arena.ai"))
        assertFalse(ArenaImageActivity.isAllowedNavigation("http", "arena.ai", "http://arena.ai"))
        assertFalse(ArenaImageActivity.isAllowedNavigation("https", null, "https:///path"))
        assertFalse(ArenaImageActivity.isAllowedNavigation("https", "", "https:///path"))
    }

    @Test
    fun arenaAutomationTargetMatchesOnlyArenaAndLmArenaHttpsHosts() {
        assertTrue(ArenaImageActivity.isArenaUrl("https://arena.ai/image/direct"))
        assertTrue(ArenaImageActivity.isArenaUrl("https://chat.arena.ai/image"))
        assertTrue(ArenaImageActivity.isArenaUrl("https://lmarena.ai/"))
        assertTrue(ArenaImageActivity.isArenaUrl("https://www.lmarena.ai/image"))

        // OAuth, captcha, about:blank, HTTP, or attacker lookalikes must NOT receive automation JS
        assertFalse(ArenaImageActivity.isArenaUrl("https://accounts.google.com/"))
        assertFalse(ArenaImageActivity.isArenaUrl("https://challenges.cloudflare.com/"))
        assertFalse(ArenaImageActivity.isArenaUrl("https://github.com/"))
        assertFalse(ArenaImageActivity.isArenaUrl("https://discord.com/"))
        assertFalse(ArenaImageActivity.isArenaUrl("about:blank"))
        assertFalse(ArenaImageActivity.isArenaUrl("http://arena.ai/image/direct"))
        assertFalse(ArenaImageActivity.isArenaUrl("https://evil.com/arena.ai"))
        assertFalse(ArenaImageActivity.isArenaUrl("https://arena.ai.evil.com/"))
        assertFalse(ArenaImageActivity.isArenaAutomationTarget("https", "user@arena.ai"))
    }

    @Test
    fun buildAutomationJsEscapesTargetModelAndPromptAgainstJsInjection() {
        val maliciousModel = "\"; window.AndroidBridge.onGenerationError('pwned'); //` \n</script>"
        val maliciousPrompt = "draw a cat\"; alert(1); //"

        val js = ArenaImageActivity.buildAutomationJs(maliciousModel, maliciousPrompt)

        val expectedModelLiteral = JsonPrimitive(maliciousModel).toString()
        val expectedPromptLiteral = JsonPrimitive(maliciousPrompt).toString()

        assertTrue(js.contains("const targetModel = $expectedModelLiteral;"))
        assertTrue(js.contains("const promptText = $expectedPromptLiteral;"))

        // Extract assigned literal from generated JS and verify round-trip JSON decoding
        val modelLine = js.lineSequence().first { it.trim().startsWith("const targetModel = ") }.trim()
        val literalText = modelLine.removePrefix("const targetModel = ").removeSuffix(";")
        val decoded = Json.parseToJsonElement(literalText).jsonPrimitive.content
        assertEquals(maliciousModel, decoded)
    }
}
