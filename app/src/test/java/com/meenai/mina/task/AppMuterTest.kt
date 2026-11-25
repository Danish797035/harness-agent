package com.meenai.mina.task

import com.meenai.mina.cap.phone.shell.AppAudioKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mute rules: mute before starting, mute once for new apps that pop up in the middle, mute another one separately, restore all at the end, record everything if it is not muted. */
class AppMuterTest {
    /** Note the dummy shell that writes PLAY_AUDIO each time; the key is [AppAudioKey]. */
    private class FakeAudio : AudioPort {
        val modes = mutableMapOf<String, String>()
        val mutes = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        override fun mode(packageName: String) = if (packageName in failing) null else modes[packageName] ?: "default"
        override fun mute(packageName: String): Boolean {
            if (packageName in failing) return false
            mutes += packageName
            modes[packageName] = "ignore"
            return true
        }
        override fun setMode(packageName: String, mode: String): Boolean {
            if (packageName in failing) return false
            modes[packageName] = mode
            return true
        }
    }

    private val audio = FakeAudio()
    private val store = MemoryMarker()
    private val record = AudioMute(audio, store)
    private var enabled = true
    private val onMain = mutableSetOf<String>()
    private val failed = mutableListOf<String>()
    private val muter = AppMuter(
        record,
        enabled = { enabled },
        mutable = { it != "mina.self" && it != "system.ui" },
        onMainScreen = { pkg, user -> AppAudioKey.of(pkg, user) in onMain },
        failed = { failed += it },
    )

    @Test fun mutesBeforeLaunchAndDoesNotMuteAgainOnceOpened() {
        muter.beforeOpen("video.app", null)
        // The startup has not been issued yet, but it has already gone silent.
        assertEquals(listOf("video.app"), audio.mutes)
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), audio.mutes)
        assertTrue(failed.isEmpty())
    }

    @Test fun refusedLaunchRestoresTheEarlyMute() {
        audio.modes["video.app"] = "allow"
        muter.beforeOpen("video.app", null)
        muter.notOpened("video.app", null)
        assertEquals("allow", audio.modes["video.app"])
        assertNull(store.saved)
        // It has been opened, but the startup is refused and cannot be restored (it is still on the
        // screen)
        muter.beforeOpen("music.app", null)
        muter.opened("music.app", null)
        muter.notOpened("music.app", null)
        assertEquals("ignore", audio.modes["music.app"])
    }

    @Test fun earlyMuteFailureIsReportedOnlyWhenTheAppOpens() {
        audio.failing += "video.app"
        muter.beforeOpen("video.app", null)
        assertTrue(failed.isEmpty())
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), failed)
    }

    @Test fun newPackageMidTaskIsMutedOnce() {
        muter.beforeOpen("feed.app", null)
        muter.opened("feed.app", null)
        // Another app clicked from the target app: it will be silent the first time it is seen, and it
        // will not be repeated again when it is seen.
        muter.opened("player.app", null)
        muter.opened("player.app", null)
        muter.opened("feed.app", null)
        assertEquals(listOf("feed.app", "player.app"), audio.mutes)
    }

    @Test fun copyInAnotherUserIsMutedSeparately() {
        muter.beforeOpen("chat.app", 10)
        muter.opened("chat.app", 10)
        assertEquals(listOf("chat.app@10"), audio.mutes)
        assertFalse("chat.app" in audio.modes)
        // When the original one comes out, it will be quiet again.
        muter.opened("chat.app", null)
        assertEquals(listOf("chat.app@10", "chat.app"), audio.mutes)
    }

    @Test fun restoreAllAtEndCoversEarlyAndMidTaskMutes() {
        audio.modes["feed.app"] = "allow"
        audio.modes["chat.app@10"] = "deny"
        muter.beforeOpen("feed.app", null)
        muter.opened("feed.app", null)
        muter.opened("player.app", null)
        muter.opened("chat.app", 10)
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["feed.app"])
        assertEquals("default", audio.modes["player.app"])
        assertEquals("deny", audio.modes["chat.app@10"])
        assertNull(store.saved)
    }

    @Test fun neverTouchesOwnOrSystemOrMainScreenApps() {
        muter.opened("mina.self", null)
        muter.opened("system.ui", null)
        onMain += "maps.app"
        muter.beforeOpen("maps.app", null)
        assertTrue(audio.mutes.isEmpty())
        // What the user can see on the home screen: If it is not silent, write it down as it is and it
        // may make noise
        muter.opened("maps.app", null)
        assertTrue(audio.mutes.isEmpty())
        assertEquals(listOf("maps.app"), failed)
    }

    @Test fun switchedOffMeansNothingIsMuted() {
        enabled = false
        muter.beforeOpen("video.app", null)
        muter.opened("video.app", null)
        assertTrue(audio.mutes.isEmpty())
        assertTrue(failed.isEmpty())
    }

    @Test fun midTaskFailureIsNoted() {
        audio.failing += "player.app"
        muter.opened("player.app", null)
        assertEquals(listOf("player.app"), failed)
    }

    @Test fun releaseForTakeoverThenMuteAgain() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.release("video.app", null))
        assertEquals("allow", audio.modes["video.app"])
        muter.opened("video.app", null)
        assertEquals("ignore", audio.modes["video.app"])
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["video.app"])
    }

    @Test fun audioKeyRoundTrips() {
        assertEquals("a.app", AppAudioKey.of("a.app", null))
        assertEquals("a.app@999", AppAudioKey.of("a.app", 999))
        assertEquals("a.app" to null, AppAudioKey.parse("a.app"))
        assertEquals("a.app" to 999, AppAudioKey.parse("a.app@999"))
        assertNull(AppAudioKey.parse("a app; rm"))
    }
}
