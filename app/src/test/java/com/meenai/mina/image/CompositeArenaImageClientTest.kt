package com.meenai.mina.image

import com.meenai.mina.core.image.ImageClient
import com.meenai.mina.core.image.ImageClientException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean

class CompositeArenaImageClientTest {

    @Test
    fun generateUsesRemoteClientWhenOnline() = runBlocking {
        val remoteBytes = "REMOTE_IMAGE".toByteArray()
        val nativeCalled = AtomicBoolean(false)

        val remoteClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray = remoteBytes
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray = remoteBytes
        }
        val nativeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                nativeCalled.set(true)
                return "NATIVE_IMAGE".toByteArray()
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                nativeCalled.set(true)
                return "NATIVE_IMAGE".toByteArray()
            }
        }

        val composite = CompositeArenaImageClient(remoteClient, nativeClient)
        val result = composite.generate("key", "mai-image-2.6", "prompt", null)

        assertTrue(result.contentEquals(remoteBytes))
        assertEquals(false, nativeCalled.get())
    }

    @Test
    fun generateFallsBackToNativeClientWhenRemoteConnectionRefused() = runBlocking {
        val nativeBytes = "NATIVE_IMAGE".toByteArray()
        val nativeCalled = AtomicBoolean(false)

        val remoteClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                throw ConnectException("Failed to connect to /127.0.0.1:3000")
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                throw ConnectException("Failed to connect to /127.0.0.1:3000")
            }
        }
        val nativeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                nativeCalled.set(true)
                return nativeBytes
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                nativeCalled.set(true)
                return nativeBytes
            }
        }

        val composite = CompositeArenaImageClient(remoteClient, nativeClient)
        val result = composite.generate("key", "mai-image-2.6", "prompt", null)

        assertTrue(result.contentEquals(nativeBytes))
        assertEquals(true, nativeCalled.get())
    }

    @Test
    fun editFallsBackToNativeClientWhenRemoteOffline() = runBlocking {
        val nativeBytes = "NATIVE_EDIT_IMAGE".toByteArray()
        val nativeCalled = AtomicBoolean(false)

        val remoteClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray =
                throw ConnectException("Connection refused")
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray =
                throw ConnectException("Connection refused")
        }
        val nativeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray = nativeBytes
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                nativeCalled.set(true)
                return nativeBytes
            }
        }

        val composite = CompositeArenaImageClient(remoteClient, nativeClient)
        val result = composite.edit("key", "mai-image-2.6", "edit prompt", "b64")

        assertTrue(result.contentEquals(nativeBytes))
        assertEquals(true, nativeCalled.get())
    }

    @Test
    fun generateFallsBackToNativeClientOnCleartextOrRemoteError() = runBlocking {
        val nativeBytes = "NATIVE_IMAGE".toByteArray()
        val nativeCalled = AtomicBoolean(false)

        val remoteClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray =
                throw java.io.IOException("CLEARTEXT communication to 127.0.0.1 not permitted by network security policy")
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray =
                throw java.io.IOException("CLEARTEXT communication to 127.0.0.1 not permitted by network security policy")
        }
        val nativeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                nativeCalled.set(true)
                return nativeBytes
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                nativeCalled.set(true)
                return nativeBytes
            }
        }

        val composite = CompositeArenaImageClient(remoteClient, nativeClient)
        val result = composite.generate("key", "mai-image-2.6", "prompt", null)

        assertTrue(result.contentEquals(nativeBytes))
        assertEquals(true, nativeCalled.get())
    }

    @Test
    fun generateAndEditRethrowCancellationWithoutFallback() = runBlocking {
        val nativeCalled = AtomicBoolean(false)
        val cancellingRemote = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        val nativeClient = object : ImageClient {
            override suspend fun generate(apiKey: String, model: String, prompt: String, size: String?): ByteArray {
                nativeCalled.set(true)
                return ByteArray(0)
            }
            override suspend fun edit(apiKey: String, model: String, prompt: String, imageBase64: String): ByteArray {
                nativeCalled.set(true)
                return ByteArray(0)
            }
        }
        val composite = CompositeArenaImageClient(cancellingRemote, nativeClient)
        try {
            composite.generate("k", "mai-image-2.6", "cat", null)
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        try {
            composite.edit("k", "mai-image-2.6", "edit", "AQID")
            fail("Expected CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        assertEquals(false, nativeCalled.get())
    }
}
