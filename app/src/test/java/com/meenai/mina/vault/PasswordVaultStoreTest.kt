package com.meenai.mina.vault

import com.meenai.mina.settings.AesGcmKeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class PasswordVaultStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val testCipher = AesGcmKeyCipher { aesKey }

    private fun createStore(dir: File = tmp.root, clock: () -> Long = { 1700000000000L }): PasswordVaultStore =
        PasswordVaultStore(dir, testCipher, clock)

    @Test
    fun keystoreAliasIsDedicated() {
        assertEquals("harness-password-vault", PasswordVaultStore.KEYSTORE_ALIAS)
    }

    @Test
    fun saveAndRetrievePassword() {
        val store = createStore()
        val entry = VaultEntry(
            title = "Google",
            username = "alice@gmail.com",
            url = "accounts.google.com",
            notes = "Work email",
        )
        val password = "SuperSecretPassword123!"

        val saved = store.saveEntry(entry, password)
        assertNotNull(saved.id)
        assertEquals("Google", saved.title)
        assertEquals("alice@gmail.com", saved.username)
        assertEquals("accounts.google.com", saved.url)
        assertEquals("Work email", saved.notes)
        assertEquals(1700000000000L, saved.updatedAt)

        // Verify entries flow / list
        assertEquals(1, store.list().size)
        assertEquals(saved.id, store.list().first().id)

        // Retrieve and decrypt password
        val retrievedPassword = store.getPassword(saved.id)
        assertEquals(password, retrievedPassword)

        // Verify file isolation: metadata in vault.json, secret in vault-secrets.json
        val metadataFile = File(tmp.root, "vault.json")
        val secretsFile = File(tmp.root, "vault-secrets.json")
        assertTrue(metadataFile.exists())
        assertTrue(secretsFile.exists())

        val metaContent = metadataFile.readText()
        val secretsContent = secretsFile.readText()

        // Metadata file has title & username but NEVER plaintext password
        assertTrue(metaContent.contains("Google"))
        assertTrue(metaContent.contains("alice@gmail.com"))
        assertFalse(metaContent.contains(password))

        // Secrets file has encrypted data but NEVER plaintext password
        assertFalse(secretsContent.contains(password))
        assertTrue(secretsContent.contains(saved.id))
    }

    @Test
    fun deleteEntryRemovesMetadataAndSecret() {
        val store = createStore()
        val saved = store.saveEntry(
            VaultEntry(title = "GitHub", username = "alice", url = "github.com"),
            "gh_pass_456",
        )

        assertEquals(1, store.list().size)
        assertNotNull(store.getPassword(saved.id))

        val deleted = store.deleteEntry(saved.id)
        assertTrue(deleted)
        assertTrue(store.list().isEmpty())
        assertNull(store.getPassword(saved.id))

        // Secrets file no longer contains secret
        val secretsContent = File(tmp.root, "vault-secrets.json").readText()
        assertFalse(secretsContent.contains(saved.id))
    }

    @Test
    fun updateEntryReplacesExisting() {
        var currentTime = 1000L
        val store = createStore(clock = { currentTime })

        val initial = store.saveEntry(
            VaultEntry(title = "Amazon", username = "alice@example.com"),
            "old_pass",
        )
        assertEquals(1, store.list().size)
        assertEquals("old_pass", store.getPassword(initial.id))

        currentTime = 2000L
        val updated = store.saveEntry(
            initial.copy(title = "Amazon Prime", username = "alice_new@example.com"),
            "new_pass_789",
        )

        assertEquals(1, store.list().size)
        assertEquals(initial.id, updated.id)
        assertEquals("Amazon Prime", store.list().first().title)
        assertEquals("alice_new@example.com", store.list().first().username)
        assertEquals(2000L, store.list().first().updatedAt)
        assertEquals("new_pass_789", store.getPassword(initial.id))
    }

    @Test
    fun persistenceAcrossStoreReload() {
        val dir = tmp.newFolder()
        val store1 = PasswordVaultStore(dir, testCipher) { 5000L }
        val e1 = store1.saveEntry(VaultEntry(title = "Netflix", username = "user1@test.com"), "netflix_pass_1")
        val e2 = store1.saveEntry(VaultEntry(title = "Spotify", username = "user2@test.com"), "spotify_pass_2")

        // Create new instance pointing to same directory
        val store2 = PasswordVaultStore(dir, testCipher)
        assertEquals(2, store2.list().size)

        val loadedE1 = store2.getEntry(e1.id)
        assertNotNull(loadedE1)
        assertEquals("Netflix", loadedE1?.title)
        assertEquals("netflix_pass_1", store2.getPassword(e1.id))

        val loadedE2 = store2.getEntry(e2.id)
        assertNotNull(loadedE2)
        assertEquals("Spotify", loadedE2?.title)
        assertEquals("spotify_pass_2", store2.getPassword(e2.id))
    }

    @Test
    fun searchFiltersAcrossTitleUsernameAndUrl() {
        val store = createStore()
        store.saveEntry(VaultEntry(title = "Google Personal", username = "alice@gmail.com", url = "google.com"), "p1")
        store.saveEntry(VaultEntry(title = "Google Work", username = "alice@company.com", url = "workspace.google.com"), "p2")
        store.saveEntry(VaultEntry(title = "GitHub", username = "alice_dev", url = "github.com"), "p3")

        // Search by title
        val googleResults = store.search("google")
        assertEquals(2, googleResults.size)

        // Search by username
        val companyResults = store.search("company.com")
        assertEquals(1, companyResults.size)
        assertEquals("Google Work", companyResults.first().title)

        // Search by domain
        val githubResults = store.search("github")
        assertEquals(1, githubResults.size)
        assertEquals("GitHub", githubResults.first().title)

        // Blank returns all
        assertEquals(3, store.search("").size)
        assertEquals(3, store.search("   ").size)

        // No match returns empty
        assertTrue(store.search("nonexistent").isEmpty())
    }

    @Test
    fun corruptedOrTamperedSecretHandlesGracefully() {
        val store = createStore()
        val saved = store.saveEntry(VaultEntry(title = "Bank", username = "user"), "bank_pass")

        // Tamper with the secrets file
        val secretsFile = File(tmp.root, "vault-secrets.json")
        secretsFile.writeText("""{"${saved.id}":"invalid_not_encrypted_data"}""")

        // Create a new store instance to load tampered disk file
        val reloadedStore = createStore()
        val pass = reloadedStore.getPassword(saved.id)
        assertNull(pass)
    }
}
