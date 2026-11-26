package com.meenai.mina.memory

import android.content.ContextWrapper
import com.meenai.mina.core.memory.MemoryKind
import com.meenai.mina.core.memory.MemorySource
import com.meenai.mina.core.memory.ProfileChange
import com.meenai.mina.settings.AesGcmKeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class MemoryRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun newAesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun wipeAllDeletesProfileBinResetsCachesAndAllowsCleanProfileRead() {
        var activeKey = newAesKey()
        var keyAliasDeletes = 0
        var strippedConversations = 0
        val runtime = MemoryRuntime(
            app = object : ContextWrapper(null) {},
            baseDir = tmp.root,
            cipherFactory = { AesGcmKeyCipher { activeKey } },
            deleteKeyAlias = {
                keyAliasDeletes++
                activeKey = newAesKey()
            },
            stripStoredConversations = { strippedConversations++ },
        )

        val oldProfile = runtime.profile()
        val oldStore = runtime.store()
        val oldFile = runtime.file()
        val oldSkills = runtime.skills()
        val oldKnowledge = runtime.appKnowledge()

        val added = oldProfile.add("User prefers concise answers")
        assertTrue(added is ProfileChange.Applied)
        assertTrue(File(tmp.root, "memory/profile.bin").exists())

        oldStore.add(MemoryKind.fact, "User lives in Pune", listOf("city"), MemorySource("manual", atMs = 1L)).getOrThrow()
        assertTrue(File(tmp.root, "memory/memory.bin").exists())

        runtime.wipeAll()

        assertEquals(1, keyAliasDeletes)
        assertEquals(1, strippedConversations)
        assertFalse("memory/profile.bin must be deleted by wipeAll()", File(tmp.root, "memory/profile.bin").exists())
        assertFalse("memory/memory.bin must be deleted by wipeAll()", File(tmp.root, "memory/memory.bin").exists())

        val freshProfile = runtime.profile()
        val freshStore = runtime.store()
        val freshFile = runtime.file()
        val freshSkills = runtime.skills()
        val freshKnowledge = runtime.appKnowledge()

        assertNotSame(oldProfile, freshProfile)
        assertNotSame(oldStore, freshStore)
        assertNotSame(oldFile, freshFile)
        assertNotSame(oldSkills, freshSkills)
        assertNotSame(oldKnowledge, freshKnowledge)

        // Must never crash with decryption failure after key alias rotation
        assertEquals(emptyList<Any>(), freshProfile.snapshot())
    }

    @Test
    fun itemsReturnsSharedStateFlowAcrossCalls() {
        val activeKey = newAesKey()
        val runtime = MemoryRuntime(
            app = object : ContextWrapper(null) {},
            baseDir = tmp.root,
            cipherFactory = { AesGcmKeyCipher { activeKey } },
            deleteKeyAlias = {},
            stripStoredConversations = {},
        )
        val first = runtime.items()
        val second = runtime.items()
        assertSame(first, second)
    }

    @Test
    fun fileProfileBackendHandlesCorruptFilesUnknownFieldsAndMissingParents() {
        val key1 = newAesKey()
        val key2 = newAesKey()
        val profileFile = File(tmp.root, "nested/memory/profile.bin")
        val cipher1 = AesGcmKeyCipher { key1 }
        profileFile.parentFile?.mkdirs()

        // Payload with extra unknown JSON field must deserialize cleanly
        val jsonWithUnknown = """[{"id":"p1","text":"Keep it short","atMs":10,"status":"active","unknown_future":42}]"""
        profileFile.writeBytes(cipher1.encrypt(jsonWithUnknown.toByteArray(Charsets.UTF_8)))

        val backend1 = FileProfileBackend(profileFile, cipher1)
        val decoded = backend1.read()
        assertEquals(1, decoded.size)
        assertEquals("Keep it short", decoded.single().text)

        // Reading with mismatched key recovers gracefully without throwing
        val wrongKeyBackend = FileProfileBackend(profileFile, AesGcmKeyCipher { key2 })
        assertEquals(emptyList<Any>(), wrongKeyBackend.read())
        assertFalse(profileFile.exists())
    }
}
