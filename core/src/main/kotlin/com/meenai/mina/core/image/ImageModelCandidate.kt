package com.meenai.mina.core.image

data class ImageModelCandidate(
    val id: String,
    val provider: String,
    val isPaid: Boolean = false,
    val costMagicube: Double = 2.0,
)

object ImageModelCatalog {
    const val PROVIDER_HARNESS = "harness"
    const val PROVIDER_MODELSCOPE = "modelscope"
    const val PROVIDER_OPENROUTER = "openrouter"

    // Default image models (ordered by priority):
    // 1. mai-image-2.6 (Microsoft MAI-Image-2.6, LM Arena Side-by-Side #1)
    // 2. grok-imagine-2.0 (xAI Grok Imagine Image 2.0, LM Arena Side-by-Side #2)
    // 3. muse-image (Meta Muse Image, LM Arena Side-by-Side #3)
    // 4. seedream-5.0-lite (ByteDance Seedream 5.0 Lite, Harness local service)
    // 5. Qwen/Qwen-Image-2.1 (1 Magicube)
    // 6. Qwen/Qwen-Image (2 Magicube)
    val DEFAULT_IMAGE_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("mai-image-2.6", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("grok-imagine-2.0", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("muse-image", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("seedream-5.0-lite", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image-2.1", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 1.0),
        ImageModelCandidate("Qwen/Qwen-Image", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 2.0),
    )

    // Default image edit models:
    val DEFAULT_IMAGE_EDIT_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("mai-image-2.6", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("grok-imagine-2.0", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("muse-image", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("seedream-5.0-lite", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image-Edit", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 2.0),
    )

    // OpenRouter image models (paid, default disabled)
    val OPENROUTER_PAID_IMAGE_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("black-forest-labs/flux-1-schnell", provider = PROVIDER_OPENROUTER, isPaid = true, costMagicube = 0.0),
    )
}
