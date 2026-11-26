package com.meenai.mina.image

import android.content.Context
import com.meenai.mina.core.image.ImageClient
import com.meenai.mina.core.image.ImageClientException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Native Android in-app client for LM Arena (arena.ai/image/direct).
 * Launches the visible ArenaImageActivity so the user can see the generation live,
 * tap Cloudflare verification if required, and automatically receive the generated image.
 */
class NativeArenaImageClient(
    private val context: Context,
    private val logger: ((String) -> Unit)? = null,
) : ImageClient {

    companion object {
        const val TIMEOUT_MS = 120_000L // 2 minutes to allow user to complete verification if Cloudflare appears
    }

    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray = withContext(Dispatchers.Main) {
        val resultDeferred = CompletableDeferred<ByteArray>()
        logger?.invoke("NativeArenaImageClient: launching visible ArenaImageActivity for model=$model")

        ArenaImageActivity.start(
            context = context,
            model = model,
            prompt = prompt,
            size = size,
            resultDeferred = resultDeferred,
            logger = logger,
        )

        try {
            withTimeout(TIMEOUT_MS) {
                resultDeferred.await()
            }
        } catch (tce: kotlinx.coroutines.TimeoutCancellationException) {
            logger?.invoke("NativeArenaImageClient: timed out after ${TIMEOUT_MS}ms")
            ArenaImageActivity.cancel()
            throw ImageClientException("LM Arena timed out after ${TIMEOUT_MS / 1000}s", tce)
        } catch (e: Throwable) {
            ArenaImageActivity.cancel()
            throw e
        }
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        throw UnsupportedOperationException("LM Arena WebView does not support image editing")
    }
}
