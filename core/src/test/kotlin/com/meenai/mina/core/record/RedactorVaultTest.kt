package com.meenai.mina.core.record

import com.meenai.mina.core.testing.verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RedactorVaultTest {
    @Before
    fun setUp() {
        Redactor.clearSecrets()
    }

    @After
    fun tearDown() {
        Redactor.clearSecrets()
    }

    @Test
    fun registeredSecretIsMaskedInText() {
        val vaultPass = "SuperSecretP@ssw0rd!"
        Redactor.registerSecret(vaultPass)

        val result = Redactor.text("Logging into account with $vaultPass right now")
        assertFalse(result.contains(vaultPass))
        assertTrue(result.contains("***"))
        assertEquals("Logging into account with *** right now", result)
    }

    @Test
    fun unregisterSecretStopsMasking() {
        val tempPass = "MyVaultPassword123"
        Redactor.registerSecret(tempPass)
        assertTrue(Redactor.text("pass: $tempPass").contains("***"))

        Redactor.unregisterSecret(tempPass)
        assertEquals("pass: MyVaultPassword123", Redactor.text("pass: $tempPass"))
    }

    @Test
    fun shortSecretsAreNotRegisteredToAvoidFalsePositives() {
        Redactor.registerSecret("abc")
        assertEquals("abc 123", Redactor.text("abc 123"))
    }

    @Test
    fun registeredSecretMaskedInEvidenceAndPageData() {
        val pass = "BankPortalSecret999"
        Redactor.registerSecret(pass)

        assertFalse(Redactor.evidence("Screen text showing $pass").contains(pass))
        assertFalse(Redactor.pageData("""{"token":"$pass"}""").contains(pass))
    }

    @Test
    fun argsMasksSecretFieldsAutomaticallyEvenWhenSecretFieldFlagIsFalse() {
        val jsonArgs = """{"username":"alice@example.com","password":"SuperSecretPassword99","site":"example.com"}"""
        val redacted = Redactor.args(verb("phone_type_text"), jsonArgs, secretField = false)

        val o = Json.parseToJsonElement(redacted).jsonObject
        assertEquals("***", o["password"]!!.jsonPrimitive.content)
        assertEquals("alice@example.com", o["username"]!!.jsonPrimitive.content)
        assertFalse(redacted.contains("SuperSecretPassword99"))
    }
}
