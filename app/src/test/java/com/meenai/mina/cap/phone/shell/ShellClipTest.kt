package com.meenai.mina.cap.phone.shell

import com.meenai.mina.cap.phone.shell.ShellClip.Now
import com.meenai.mina.cap.phone.shell.ShellClip.Read
import com.meenai.mina.cap.phone.shell.ShellClip.Restore
import org.junit.Assert.assertEquals
import org.junit.Test

/** Paste the input into the clipboard and then restore it: if it cannot be read, it will never be regarded as empty. If the clipboard does not belong to Mina, it will not move. */
class ShellClipTest {
    @Test fun readDistinguishesEmptyFromUnreadable() {
        assertEquals(Read.Has, ShellClip.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true))
        assertEquals(Read.Empty, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false))
        // What is obtained is empty, but the system says there is content: cannot be read, it is not
        // empty.
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = false))
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = true))
    }

    @Test fun restoreOnlyTouchesOursOrEmpty() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Ours))
        assertEquals(Restore.Clear, ShellClip.restore(priorEmpty = true, now = Now.Ours))
        // The user copied something else in the middle, or cannot read it at the moment: does not move
        for (prior in listOf(true, false)) {
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Other))
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Unreadable))
        }
    }

    @Test fun clearedByOthersGetsUsersClipBack() {
        // Mina's content has been cleared by others (for example, the system clears it regularly): put
        // it back if there is content originally, and don't worry about it if it is empty.
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Empty))
        assertEquals(Restore.Leave, ShellClip.restore(priorEmpty = true, now = Now.Empty))
    }
}
