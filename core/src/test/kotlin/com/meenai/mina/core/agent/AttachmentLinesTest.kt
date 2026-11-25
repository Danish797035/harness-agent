package com.meenai.mina.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** The way the attachment lines are written: words first, one line for each attachment at the end, then split back; the way it was written in the old dialogue just looked like the words in the attachment lines. */
class AttachmentLinesTest {
    @Test fun composeWritesWhatParseReads() {
        val files = listOf("/workspace/inbox/a.png" to "3 KB", "/workspace/inbox/b.docx" to "1.0 MB")
        val text = AttachmentLines.compose("查一下", files)
        assertEquals("查一下\n附件:/workspace/inbox/a.png(3 KB)\n附件:/workspace/inbox/b.docx(1.0 MB)", text)
        assertEquals(AttachmentLines.Parsed(files, "查一下"), AttachmentLines.parse(text))
        assertEquals("附件:/workspace/inbox/a.png(3 KB)", AttachmentLines.compose("", listOf("/workspace/inbox/a.png" to "3 KB")))
    }

    // The writing method saved in the old conversation: brackets in the name, only attachments without
    // words, and size in bytes
    @Test fun oldMessagesParseTheSameWay() {
        assertEquals(
            AttachmentLines.Parsed(listOf("/workspace/inbox/报表 (1).csv" to "2 KB", "/workspace/inbox/x" to "512 B"), ""),
            AttachmentLines.parse("附件:/workspace/inbox/报表 (1).csv(2 KB)\n附件:/workspace/inbox/x(512 B)"),
        )
    }

    @Test fun linesThatOnlyLookLikeAttachmentsStayInTheWords() {
        for (t in listOf("附件:/etc/passwd(1 KB)", "附件:/workspace/inbox/a.pdf", "附件:/workspace/inbox/a.pdf(很大)", "看看附件:/workspace/inbox/a.pdf(1 KB)")) {
            assertEquals(t, AttachmentLines.Parsed(emptyList(), t), AttachmentLines.parse(t))
        }
        assertEquals(AttachmentLines.Parsed(emptyList(), "普通的一句话"), AttachmentLines.parse("  普通的一句话 "))
    }
}
