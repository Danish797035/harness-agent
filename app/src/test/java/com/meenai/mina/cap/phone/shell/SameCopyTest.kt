package com.meenai.mina.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Main screen occupancy, Mina other screen occupancy, and cold start tasks: Apps with two copies installed are calculated separately according to "package name + user", while apps with only one copy installed remain the same. */
class SameCopyTest {
    private val chat = "com.example.chat"

    @Test fun theUserOnTheMainCopyDoesNotBlockTheCloneAndViceVersa() {
        // The home screen is for user 0
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf(chat), 0, chat, 128))
        // The home screen is the clone
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, 128))
        assertFalse(ShellService.sameCopy(listOf(chat), 128, chat, 0))
    }

    @Test fun unknownTaskUserCountsAsTheSameCopy() {
        // Unable to read which user the task belongs to: rather treat it as occupied
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 128))
        assertTrue(ShellService.sameCopy(listOf(chat), null, chat, 0))
    }

    @Test fun singleCopyAndPackageOnlyChecksAreUnchanged() {
        // Only one copy is installed: the tasks are all in user 0, and are calculated by package name as
        // before.
        assertTrue(ShellService.sameCopy(listOf("x.launcher", chat), 0, chat, 0))
        assertFalse(ShellService.sameCopy(listOf("x.other"), 0, chat, 0))
        // No matter which portion (share): any portion counts
        assertTrue(ShellService.sameCopy(listOf(chat), 128, chat, null))
        assertTrue(ShellService.sameCopy(listOf(chat), 0, chat, null))
    }
}
