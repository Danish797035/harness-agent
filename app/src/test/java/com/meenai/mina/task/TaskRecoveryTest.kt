package com.meenai.mina.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.meenai.mina.settings.KeyCipher
import org.junit.Test

class TaskRecoveryTest {
    private class FakeAudio(val modes: MutableMap<String, String>) : AudioPort {
        override fun mode(packageName: String) = modes[packageName] ?: "default"
        override fun mute(packageName: String): Boolean { modes[packageName] = "ignore"; return true }
        override fun setMode(packageName: String, mode: String): Boolean { modes[packageName] = mode; return true }
    }

    // Review Focus 5: The two tasks each muted their own apps and the processes were killed; both
    // records will be restored next time they are started.
    @Test fun restoresAllMutedPackagesOfAllRuns() {
        val audio = FakeAudio(mutableMapOf("settings.app" to "allow", "clock.app" to "default"))
        val a = MemoryMarker()
        val b = MemoryMarker()
        assertTrue(AudioMute(audio, a).mute("settings.app"))
        assertTrue(AudioMute(audio, b).mute("clock.app"))

        assertTrue(TaskRecovery.restoreRuns(mapOf("run-a" to a, "run-b" to b), audio, live = emptySet()))
        assertEquals("allow", audio.modes["settings.app"])
        assertEquals("default", audio.modes["clock.app"])
        assertNull(a.saved)
        assertNull(b.saved)
    }

    // Restoration when the second task is started cannot mute the first task that is still running.
    @Test fun leavesLiveRunsAlone() {
        val audio = FakeAudio(mutableMapOf("settings.app" to "allow"))
        val a = MemoryMarker()
        AudioMute(audio, a).mute("settings.app")

        assertTrue(TaskRecovery.restoreRuns(mapOf("run-a" to a), audio, live = setOf("run-a")))
        assertEquals("ignore", audio.modes["settings.app"])
        assertFalse(a.saved.isNullOrEmpty())
    }

    private class XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray) = encrypt(data)
    }

    // Focus 5's clipboard half: Mina was killed when pasting through the clipboard. The user's original
    // text will be returned next time it is started; the remaining bottom is encrypted and will be
    // deleted after use.
    @Test fun restoresClipboardFromEncryptedBackupAfterKill() {
        var stored: String? = null
        val backup = ClipBackup({ stored }, { stored = it }, XorCipher())
        backup.save("用户原来的文字", "Mina 要粘贴的")
        assertFalse(stored!!.contains("用户原来的文字"))

        val restored = mutableListOf<Pair<String, String>>()
        assertTrue(TaskRecovery.restoreClip(ClipBackup({ stored }, { stored = it }, XorCipher())) { o, u -> restored += o to u })
        assertEquals(listOf("用户原来的文字" to "Mina 要粘贴的"), restored)
        assertNull(stored)
        // Do nothing without leaving any trace
        assertTrue(TaskRecovery.restoreClip(backup) { _, _ -> error("nothing to restore") })
    }
}
