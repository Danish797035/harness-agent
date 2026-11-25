package com.meenai.mina.ui

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.chat.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatCopyTest {
    @get:Rule val texts = EnglishTexts()

    @Test fun sixDistinctNonBlankExamples() {
        assertEquals(8, ChatCopy.examples.size)
        assertEquals(8, ChatCopy.examples.map { it.text }.filter { it.isNotBlank() }.toSet().size)
    }

    @Test fun titleIsTheFirstLineOfTheFirstMessage() {
        val first = Exchange("查车次\n附件:/workspace/inbox/a.pdf(1 KB)", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("查车次", ChatCopy.title(listOf(first, first.copy(goal = "再查"))))
        assertEquals("", ChatCopy.title(emptyList()))
        // Only sent attachments: no path as title
        assertEquals("Photo", ChatCopy.title(listOf(first.copy(goal = "附件:/workspace/inbox/a.jpg(1 KB)"))))
        assertEquals("a.pdf", ChatCopy.plainTitle("附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals(ScreenCopy.TAG, ChatCopy.plainTitle("[[screen:s1]]"))
        // Asked about a page on the browser page: The page description has an inappropriate title.
        val paged = com.meenai.mina.core.cap.web.AttachedPage.compose(com.meenai.mina.core.cap.web.AttachedPage.Page("https://a.example/", "页"), "挑一个\n第二行")
        assertEquals("挑一个", ChatCopy.plainTitle(paged))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun drawerListsRunningThenRecent() {
        val now = at(2026, 10, 27, 15)
        val all = listOf(
            ConversationSummary("a", "附近的川菜", at(2026, 10, 27, 14)),
            ConversationSummary("b", "明天的高铁票", at(2026, 10, 26)),
            ConversationSummary("c", "PDF 要点整理", at(2026, 10, 26)),
        )
        val current = CurrentConversation("a", running = true, title = "附近的川菜", subtitle = "正在操作外卖")
        val s = drawerSections(
            all, current, others = listOf("c", "d"), otherTitle = { if (it == "d") "第二个任务" else null },
            canSwitch = true, now = now, zone = zone,
        )
        assertEquals(listOf("a", "c", "d"), s.running.map { it.id })
        assertEquals(listOf("附近的川菜", "PDF 要点整理", "第二个任务"), s.running.map { it.title })
        assertEquals("正在操作外卖", s.running.first().subtitle)
        assertTrue(s.running.first().current)
        assertEquals(listOf("b"), s.recent.map { it.id })
        assertEquals("", s.recent.single().subtitle)
    }

    /** The conversations that are pinned to the top are in the "Pin" section, and the most recently pinned conversations are first and are not repeated in "Recent"; those with tasks running are only listed as in progress (with push pins). */
    @Test fun drawerListsPinnedAboveRecent() {
        val all = listOf(
            ConversationSummary("a", "一", 40L),
            ConversationSummary("b", "二", 30L, pinnedAt = 100L),
            ConversationSummary("c", "三", 20L),
            ConversationSummary("d", "四", 10L, pinnedAt = 200L),
            ConversationSummary("e", "五", 5L, pinnedAt = 300L),
        )
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), others = listOf("e"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("e"), s.running.map { it.id })
        assertTrue(s.running.single().pinned)
        assertEquals(listOf("d", "b"), s.pinned.map { it.id })
        assertTrue(s.pinned.all { it.pinned })
        assertEquals(listOf("a", "c"), s.recent.map { it.id })
        assertTrue(s.recent.none { it.pinned })
        // Unpin: Return to the position ranked by change time in "Recent"
        val unpinned = drawerSections(all.map { if (it.id == "b") it.copy(pinnedAt = null) else it }, CurrentConversation("a", false, "一", ""), emptyList(), { null }, true, 0L, zone)
        assertEquals(listOf("e", "d"), unpinned.pinned.map { it.id })
        assertEquals(listOf("a", "b", "c"), unpinned.recent.map { it.id })
    }

    /** A conversation started on a browser page facing one page: the one in the sidebar carries the website (drawn as a small label "Web Page" and the name of the website), both in progress and the most recent paragraphs. */
    @Test fun drawerMarksConversationsStartedFromAPage() {
        val page = com.meenai.mina.chat.PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")
        val all = listOf(ConversationSummary("a", "一", 0L, page = page), ConversationSummary("b", "二", 0L), ConversationSummary("c", "三", 0L, page = page))
        val s = drawerSections(all, CurrentConversation("x", false, "", ""), others = listOf("c"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("shop.example.com", null), s.recent.map { it.site })
        assertEquals("shop.example.com", s.running.single().site)
    }

    /** The task is in progress and parallelism is not enabled: only the current dialogue can be clicked. */
    @Test fun drawerLocksOtherConversationsWhileBusy() {
        val all = listOf(ConversationSummary("a", "一", 0L), ConversationSummary("b", "二", 0L))
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), emptyList(), { null }, canSwitch = false, now = 0L, zone = zone)
        assertEquals(listOf(true, false), s.recent.map { it.enabled })
        assertEquals(listOf(true, false), s.recent.map { it.current })
        // When the current conversation has not been saved in the list (the first sentence has just been
        // sent), it is also listed as in progress.
        val fresh = drawerSections(emptyList(), CurrentConversation(null, true, "", "正在处理"), emptyList(), { null }, false, 0L, zone)
        assertEquals("New chat", fresh.running.single().title)
        assertTrue(fresh.running.single().enabled)
    }

    @Test fun greetingFollowsTheTimeOfDay() {
        assertEquals("Good morning", ChatCopy.greeting(7))
        assertEquals("Good afternoon", ChatCopy.greeting(12))
        assertEquals("Good afternoon", ChatCopy.greeting(15))
        assertEquals("Good evening", ChatCopy.greeting(20))
        assertEquals("Up late", ChatCopy.greeting(2))
        assertEquals("Up late", ChatCopy.greeting(23))
    }

    @Test fun clockLabel() {
        assertEquals("0:00", ChatCopy.clock(0))
        assertEquals("0:24", ChatCopy.clock(24_900))
        assertEquals("12:05", ChatCopy.clock(725_000))
        assertEquals("1:02:03", ChatCopy.clock(3_723_000))
        assertEquals("0:00", ChatCopy.clock(-5))
    }

    @Test fun subtitleSaysWhatIsGoingOn() {
        val working = Exchange("x", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("Working" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, null))
        assertEquals("Working in 设置" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, "设置"))
        assertEquals("Waiting for you" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.NeedsInput("?", null)), null))
        assertEquals("Waiting for you" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.Paused("why")), null))
        assertEquals("Waiting for you to unlock" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.WaitingUnlock), null))
        assertNull(ChatCopy.subtitle(working.copy(status = ExchangeStatus.Done("好", null)), "设置"))
        assertNull(ChatCopy.subtitle(null, null))
    }

    @Test fun progressLineSaysPausedWhileWaitingForYou() {
        val ex = Exchange("x", emptyList(), ExchangeStatus.Working, live = true, line = "按评分排序")
        assertEquals("按评分排序", ChatCopy.progressLine(ex))
        assertEquals("Paused", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsTakeover("login", "登录"))))
        assertEquals("Paused", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsConfirmation("t", "b", 1))))
        assertEquals("Paused", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.WaitingUnlock)))
        assertEquals("Working", ChatCopy.progressLine(ex.copy(line = "", waitingForModel = true)))
    }

    @Test fun placeholderFollowsTheComposer() {
        assertEquals(ChatCopy.EMPTY_PROMPT, ChatCopy.placeholder(ComposerMode.Idle, hasHistory = false))
        assertEquals("Say something more…", ChatCopy.placeholder(ComposerMode.Idle, hasHistory = true))
        assertEquals("Answer Harness Agent’s question", ChatCopy.placeholder(ComposerMode.Answer, hasHistory = true))
        assertEquals("Add or change details…", ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true))
    }

    @Test fun modelWaitLabelFollowsAppLocaleForEnglishAndHindi() {
        // EnglishTexts @Rule is active by default: ui_locale = "en"
        assertEquals(
            "Thinking · 1s · Gemini Flash",
            ChatCopy.modelWaitLabelForLocale(1, "Gemini Flash"),
        )
        assertEquals(
            "Thinking · 5s",
            ChatCopy.modelWaitLabelForLocale(5, ""),
        )
        assertEquals(false, ChatCopy.isHindiLocale("en"))
        assertEquals(false, ChatCopy.isHindiLocale("en-US"))

        // Switch UiText to Hindi resource bundle ("values-hi" -> ui_locale = "hi")
        val previous = com.meenai.mina.i18n.UiText.source
        try {
            com.meenai.mina.i18n.UiText.source =
                com.meenai.mina.i18n.ResFileTexts("values-hi", java.util.Locale.forLanguageTag("hi"))
            assertEquals(
                "Soch raha hai · 1s · Gemini Flash",
                ChatCopy.modelWaitLabelForLocale(1, "Gemini Flash"),
            )
            assertEquals(
                "Soch raha hai · 5s",
                ChatCopy.modelWaitLabelForLocale(5, ""),
            )
            assertTrue(ChatCopy.isHindiLocale("hi"))
            assertTrue(ChatCopy.isHindiLocale("hi-IN"))
        } finally {
            com.meenai.mina.i18n.UiText.source = previous
        }
    }

    @Test fun liveActionLabelProvidesClaudeStyleRealTimeStatus() {
        assertEquals("YouTube khol raha hai…", ChatCopy.liveActionLabel("phone_launch_app", "youtube", isHindi = true))
        assertEquals("Opening YouTube…", ChatCopy.liveActionLabel("phone_launch_app", "youtube", isHindi = false))
        assertEquals("Type kar raha hai…", ChatCopy.liveActionLabel("phone_type_text", "honey singh", isHindi = true))
        assertEquals("Typing…", ChatCopy.liveActionLabel("phone_type_text", "honey singh", isHindi = false))
        assertEquals("Screen par dhoondh raha hai…", ChatCopy.liveActionLabel("phone_find", null, isHindi = true))
        assertEquals("Finding on screen…", ChatCopy.liveActionLabel("phone_find", null, isHindi = false))
        assertEquals("Screen dekh raha hai…", ChatCopy.liveActionLabel("phone_observe", null, isHindi = true))
        assertEquals("Reading screen…", ChatCopy.liveActionLabel("phone_observe", null, isHindi = false))
        assertEquals("Opening YouTube…", ChatCopy.liveActionLabel("phone_launch_app", "phone_launch_app", isHindi = false, target = "com.google.android.youtube"))
        assertEquals("YouTube khol raha hai…", ChatCopy.liveActionLabel("phone_launch_app", null, isHindi = true, target = "YouTube"))
        assertEquals("Finding \"Search\"…", ChatCopy.liveActionLabel("phone_find", null, isHindi = false, target = "Search"))
        assertEquals("\"Search\" dhoondh raha hai…", ChatCopy.liveActionLabel("phone_find", null, isHindi = true, target = "Search"))
    }
}
