package com.meenai.mina.chat

import com.meenai.mina.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun persistsAcrossInstances() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        a.append(id, Turn("订票", "验证码\n\"过不去\"", "failed"))
        val b = ConversationStore(dir)
        assertEquals(listOf(Turn("查天气", "晴", "succeeded"), Turn("订票", "验证码\n\"过不去\"", "failed")), b.turns(id))
        assertEquals(emptyList<Turn>(), b.turns(b.newConversation()))
        assertNotEquals(id, b.newConversation())
    }

    /** The page (site) left running in the background and the reason for its subsequent shutdown are saved with that round; these two fields are not written in the round where no page is left. */
    @Test fun persistsKeptPage() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("播放课程,挂着别关", "开始播放了", "succeeded", runId = "r1", keptPage = "course.example.com"))
        a.append(id, Turn("谢谢", "不客气", "succeeded", runId = "r2"))
        assertEquals(listOf("course.example.com", null), ConversationStore(dir).turns(id).map { it.keptPage })
        a.updateTurn(id, "r1") { it.copy(keptPageClosed = "expired") }
        assertEquals("expired", ConversationStore(dir).turns(id).first().keptPageClosed)
        assertFalse("keptPage" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** The ability groups used in this round will be saved accordingly (the next round will be pre-expanded accordingly); if they have not been used, this field will not be written. */
    @Test fun persistsUsedGroups() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("打开设置", "好了", "succeeded", groups = listOf("phone", "web")))
        a.append(id, Turn("谢谢", "不客气", "succeeded"))
        assertEquals(listOf(listOf("phone", "web"), emptyList()), ConversationStore(dir).turns(id).map { it.groups })
        assertFalse("groups" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** The reply interrupted by the interruption is stored in one-to-one correspondence with the interruption; this field is not written in the rounds that have not been interrupted, and the old file is empty when read. */
    @Test fun persistsInterruptedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        val read = ConversationStore(dir).turns(id)
        assertEquals(listOf(listOf("今天晴", ""), emptyList()), read.map { it.interruptedReplies })
        assertEquals(listOf("改成查明天", "顺便看看后天"), read.first().steers)
        assertFalse("interruptedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** Replies that have been finished before interrupting are also stored in one-to-one correspondence; if there are none, this field will not be written. */
    @Test fun persistsFinishedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        assertEquals(listOf(listOf("今天晴,最高 25 度。"), emptyList()), ConversationStore(dir).turns(id).map { it.finishedReplies })
        assertFalse("finishedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** Delete a conversation: The conversation file, title, damaged files that were moved aside, and temporary files are all deleted. They are no longer in the list, and other conversations remain unchanged. After deletion, post the changes. */
    @Test fun deleteRemovesTheConversationAndItsSidecars() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        val other = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.setTitle(id, "天气")
        store.setPinned(id, true, at = 5L)
        java.io.File(dir, "$id.json.corrupt-1").writeText("x")
        java.io.File(dir, "$id.json.tmp").writeText("x")
        store.append(other, Turn("订票", "好了", "succeeded"))
        val changes = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.changes.collect { changes += it } }
        val gone = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deletions.collect { deletions += it } }
        store.delete(id)
        kotlinx.coroutines.yield()
        job.cancel()
        gone.cancel()
        assertEquals(listOf(id), changes)
        assertEquals(listOf(id), deletions)
        assertEquals(listOf("$other.json"), dir.list()!!.sorted())
        assertEquals(listOf(other), store.list().map { it.id })
        assertEquals(emptyList<Turn>(), store.turns(id))
        assertEquals(null, store.title(id))
        assertEquals(null, store.pinnedAt(id))
    }

    /** The name changed by the user has the final say: the title will not be overwritten even if it is late in the background; the user can still change the title that has been created; the title that is empty will not be changed. */
    @Test fun renameByUserWinsOverAutoTitle() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查一下明天上海的天气", "晴", "succeeded"))
        assertTrue(store.rename(id, "  天气  "))
        assertFalse(store.setTitle(id, "上海天气"))
        assertEquals("天气", ConversationStore(dir).title(id))

        val other = store.newConversation()
        store.append(other, Turn("订票", "好了", "succeeded"))
        assertTrue(store.setTitle(other, "订高铁票"))
        assertTrue(store.rename(other, "回家的票"))
        assertFalse(store.rename(other, "   "))
        assertEquals("回家的票", store.title(other))
        assertEquals(setOf("天气", "回家的票"), store.list().map { it.title }.toSet())

        store.delete(other)
        assertFalse(store.rename(other, "再改"))
    }

    /** The pin is recorded in `.pin` (the time it was pinned), and it will still be there if you restart it; the pin time will be included in the list, and the order of the conversation will not be changed; if you cancel the pin, it will be gone. */
    @Test fun pinPersistsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("一", "好", "succeeded"))
        store.append("b", Turn("二", "好", "succeeded"))
        assertTrue(store.setPinned("a", true, at = 123L))
        val again = ConversationStore(dir)
        assertEquals(123L, again.pinnedAt("a"))
        assertEquals(null, again.pinnedAt("b"))
        assertEquals(mapOf("a" to 123L, "b" to null), again.list().associate { it.id to it.pinnedAt })
        assertTrue(again.setPinned("a", false))
        assertEquals(null, again.pinnedAt("a"))
        assertFalse(java.io.File(dir, "a.pin").exists())
        // Deleted conversations will no longer be pinned back to the top
        again.delete("b")
        assertFalse(again.setPinned("b", true))
    }

    /** The rewriting that comes after deletion (the title created in the background, the ending added after the ending) does not write back the dialogue; saving it for another round means using the same number again. */
    @Test fun lateWritesDoNotReviveADeletedConversation() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r1"))
        store.delete(id)
        assertFalse(store.setTitle(id, "天气"))
        store.updateTurn(id, "r1") { it.copy(outcome = "补上的") }
        assertTrue(dir.list()!!.isEmpty())

        store.append(id, Turn("再来", "好", "succeeded"))
        assertTrue(store.setTitle(id, "再来"))
        assertEquals(listOf("再来"), store.list().map { it.title })
    }

    @Test fun corruptFileReadsAsEmpty() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        assertEquals(emptyList<Turn>(), s.turns("x"))
        s.append("x", Turn("a", "b", "cancelled"))
        assertEquals(1, s.turns("x").size)
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        s.append("x", Turn("a", "b", "cancelled"))
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("x.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{not json", aside.single().readText())
        assertEquals(listOf(Turn("a", "b", "cancelled")), s.turns("x"))
        assertEquals(listOf("x"), s.list().map { it.id })
    }

    @Test fun listsConversationsNewestFirst() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        val a = s.newConversation()
        val b = s.newConversation()
        s.append(a, Turn("查天气", "晴", "succeeded"))
        s.append(b, Turn("订票", "好了", "succeeded"))
        s.append(b, Turn("改签", "好了", "succeeded"))
        java.io.File(dir, "$a.json").setLastModified(1_000)
        java.io.File(dir, "$b.json").setLastModified(2_000)
        java.io.File(dir, "broken.json").writeText("{")
        assertEquals(listOf(b to "订票", a to "查天气"), s.list().map { it.id to it.title })
    }

    @Test fun unverifiedTurnKeepsItsNote() {
        val dir = tmp.newFolder()
        val id = ConversationStore(dir).let { s -> s.newConversation().also { s.append(it, Turn("查余额", "余额 10 元", "unverified", note = "防截屏")) } }
        assertEquals(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")), ConversationStore(dir).turns(id))
    }

    @Test fun turnKeepsItsRunId() {
        val dir = tmp.newFolder()
        val s = ConversationStore(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded", runId = "20261023-120000-abc123"))
        s.append(id, Turn("旧的", "好", "succeeded"))
        assertEquals(listOf("20261023-120000-abc123", null), ConversationStore(dir).turns(id).map { it.runId })
    }

    @Test fun memoryNotesStoredWithoutContentAndStripped() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val note = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.added, "m1", "t1", null)
        val other = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.pending, "m2", "t2", "m2")
        store.append("c1", Turn("点外卖", "ok", "succeeded", runId = "r1"))
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note, other), usedMemory = listOf("m1", "m3")) }
        val raw = java.io.File(dir, "c1.json").readText()
        assertTrue(raw.contains("\"memoryId\":\"m1\""))
        assertFalse(raw.contains("text"))
        assertEquals(listOf(note, other), ConversationStore(dir).turns("c1").single().memoryNotes)
        store.stripMemory(setOf("m1"))
        val t = ConversationStore(dir).turns("c1").single()
        assertEquals(listOf(other), t.memoryNotes)
        assertEquals(listOf("m3"), t.usedMemory)
        store.stripMemory(null)
        assertTrue(ConversationStore(dir).turns("c1").single().memoryNotes.isEmpty())
    }

    @Test fun filesKeptPerTurn() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        val f = SharedFile("图.png", 1234, "Download/Harness/图.png", "content://media/external/downloads/7")
        a.append(id, Turn("画图", "好了", "succeeded"), listOf(f))
        a.append(id, Turn("再来", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(listOf(listOf(f), emptyList()), b.records(id).map { it.second })
        assertEquals(2, b.turns(id).size)
    }

    // When adding memory reminders or deleting memories after the mission, the file cards for this round
    // cannot be lost.
    @Test fun filesSurviveNoteUpdatesAndStrip() {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        val f = SharedFile("表.csv", 10, "Download/Harness/表.csv", "content://media/external/downloads/8")
        store.append("c1", Turn("导出", "好了", "succeeded", runId = "r1"), listOf(f))
        val note = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.added, "m1", "t1", null)
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note)) }
        store.stripMemory(null)
        val r = ConversationStore(dir).records("c1").single()
        assertEquals(listOf(f), r.second)
        assertTrue(r.first.memoryNotes.isEmpty())
    }

    // Each dialogue that has been written is assigned its number: save one round, change one round,
    // remove memory prompts
    @Test fun announcesChangedConversations() = kotlinx.coroutines.test.runTest {
        val s = ConversationStore(tmp.newFolder("c"))
        val seen = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.changes.collect { seen += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.deletions.collect { deleted += it } }
        s.append("a", Turn("查天气", "晴", "succeeded", runId = "r1", usedMemory = listOf("m1")))
        s.append("b", Turn("订票", "好了", "succeeded"))
        s.updateTurn("a", "r1") { it.copy(outcome = "晴转多云") }
        s.updateTurn("a", "missing") { it }
        s.stripMemory(setOf("m1"))
        assertEquals(listOf("a", "b", "a", "a"), seen)
        // Send deletions only if you delete the conversation
        assertEquals(emptyList<String>(), deleted)
    }

    /** Which page the conversation starts from: record it in the.page file next to it and keep it in the list; old conversations can be read as usual without it; delete the conversation and delete it together. */
    @Test fun pageOriginPersistsAndIsOptional() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val plain = a.newConversation()
        a.append(plain, Turn("查天气", "晴", "succeeded"))
        val paged = a.newConversation()
        assertTrue(a.setPageOrigin(paged, PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")))
        a.append(paged, Turn("挑一个", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索"), b.pageOrigin(paged))
        assertEquals(null, b.pageOrigin(plain))
        val listed = b.list().associateBy { it.id }
        assertEquals("shop.example.com", listed.getValue(paged).page?.host)
        assertEquals(null, listed.getValue(plain).page)
        // The format of the conversation file remains unchanged: no extra fields
        assertFalse(java.io.File(dir, "$paged.json").readText().contains("shop.example.com"))
        // A broken.page is treated as if it does not exist
        java.io.File(dir, "$plain.page").writeText("{not json")
        assertEquals(null, b.pageOrigin(plain))
        b.delete(paged)
        assertFalse(java.io.File(dir, "$paged.page").exists())
        assertFalse(b.setPageOrigin(paged, PageOrigin("https://a.example/", "a.example")))
    }

    /**
     * "Which conversation the user is in at the moment" is also recorded: after the process is
     * restarted, the capsule will continue that conversation based on this, rather than guessing the
     * latest one based on the file time; newly opened conversations that have not yet been spoken will
     * be recorded as [CurrentChat.Fresh]; when deleting it, it will be cleared together; this file will
     * not be included in the conversation list.
     */
    @Test fun remembersTheConversationOnScreen() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals(null, a.currentChat())

        a.setCurrent(CurrentChat.Fresh)
        assertEquals(CurrentChat.Fresh, a.currentChat())
        // The newly opened conversation does not have a conversation file yet. You can still read it
        // after restarting.
        assertEquals(CurrentChat.Fresh, ConversationStore(dir).currentChat())
        assertEquals(listOf(id), ConversationStore(dir).list().map { it.id })

        a.setCurrent(CurrentChat.Open(id))
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).currentChat())
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).current.value)
        // Note it in the current file next to it, and do not move the conversation file or enter the
        // list.
        assertEquals(setOf("$id.json", "current"), dir.list()!!.toSet())
        assertEquals(listOf(id), a.list().map { it.id })

        // Delete this conversation: the records pointing to it will be cleared together, and the capsule
        // will fall back to the most recent one.
        a.delete(id)
        assertEquals(null, ConversationStore(dir).currentChat())
    }
}
