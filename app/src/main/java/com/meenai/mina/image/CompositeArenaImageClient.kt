package com.meenai.mina.image

import com.meenai.mina.core.image.ImageClient
import kotlinx.coroutines.CancellationException

/**
 * Composite LM Arena client:
 * 1. Probes remote / local microservice on port 3000 first (if user is running harness-image on PC or Termux).
 * 2. If port 3000 is unreachable, automatically and silently uses the native in-app Android WebView automation!
 */
class CompositeArenaImageClient(
    private val remoteClient: ImageClient,
    private val nativeClient: ImageClient,
    private val logger: ((String) -> Unit)? = null,
) : ImageClient {

    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray {
        try {
            logger?.invoke("CompositeArenaImageClient: checking remote/local harness service first...")
            return remoteClient.generate(apiKey, model, prompt, size)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger?.invoke("CompositeArenaImageClient: remote microservice unavailable (${t.message}), seamlessly running Native In-App LM Arena automation")
            return nativeClient.generate(apiKey, model, prompt, size)
        }
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        try {
            return remoteClient.edit(apiKey, model, prompt, imageBase64)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger?.invoke("CompositeArenaImageClient: remote microservice unavailable for edit (${t.message}), seamlessly running Native In-App LM Arena for edit")
            return nativeClient.edit(apiKey, model, prompt, imageBase64)
        }
    }
}
