package com.meenai.mina.diag

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogTest {

    @Test
    fun formatCrashBlockIncludesVersionThreadAndCauseChain() {
        val root = IllegalStateException("root cause boom")
        val top = RuntimeException("outer failure", root)
        val block = ChatLog.formatCrashBlock(
            threadName = "main",
            throwable = top,
            versionName = "0.1.63-325",
            gitHash = "20ecf55",
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(block.contains("FATAL CRASH"))
        assertTrue(block.contains("v0.1.63-325 (20ecf55)"))
        assertTrue(block.contains("thread=main"))
        assertTrue(block.contains("RuntimeException: outer failure"))
        assertTrue(block.contains("IllegalStateException: root cause boom"))
    }

    @Test
    fun formatCrashSummaryIsSingleLineAndBounded() {
        val error = IllegalArgumentException("bad\nline\rvalue")
        val line = ChatLog.formatCrashSummary(
            threadName = "worker-1",
            throwable = error,
            versionName = "0.1.63-325",
            gitHash = "20ecf55",
        )
        assertTrue(line.startsWith("FATAL v0.1.63-325 (20ecf55) [worker-1]"))
        assertTrue(!line.contains('\n'))
        assertTrue(!line.contains('\r'))
    }

    @Test
    fun writeFallbackCrashFileCreatesDirAndWritesCrashText() {
        val tempRoot = Files.createTempDirectory("chatlog-test").toFile()
        try {
            val filesDir = File(tempRoot, "nested")
            val block = "=== FATAL CRASH ===\nboom\n"
            ChatLog.writeFallbackCrashFile(filesDir, block)
            val saved = File(filesDir, "last-crash.txt")
            assertTrue(saved.exists())
            assertEquals(block, saved.readText(Charsets.UTF_8))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun uncaughtHandlerRecordsCrashEvenIfPreviousDelegatesAndHandlesRecursion() {
        var delegatedThread: Thread? = null
        var delegatedError: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { thread, throwable ->
            delegatedThread = thread
            delegatedError = throwable
        }
        var calls = 0
        lateinit var handler: Thread.UncaughtExceptionHandler
        handler = ChatLog.installUncaughtHandler(previous) { _, _ ->
            calls++
            if (calls == 1) {
                // Re-entering while handling must be skipped without infinite recursion.
                handler.uncaughtException(Thread.currentThread(), IllegalStateException("nested"))
            }
            throw RuntimeException("disk full")
        }

        val err = NullPointerException("boom")
        handler.uncaughtException(Thread.currentThread(), err)

        assertEquals(1, calls)
        assertSame(Thread.currentThread(), delegatedThread)
        assertSame(err, delegatedError)
    }
}
