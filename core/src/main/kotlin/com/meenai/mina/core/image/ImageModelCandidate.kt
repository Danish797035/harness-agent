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
    const val PROVIDER_POLLINATIONS = "pollinations"
    const val PROVIDER_OPENROUTER = "openrouter"

    // Default image models (ordered by priority):
    // 1. mai-image-2.6 (Microsoft MAI-Image-2.6, Arena Web #1)
    // 2. grok-imagine-2.0 (xAI Grok Imagine Image 2.0, Arena Web #2)
    // 3. muse-image (Meta Muse Image, Arena Web #3)
    // 4. seedream-5.0-lite (ByteDance Seedream 5.0 Lite, Arena Web #4)
    // 5. Qwen/Qwen-Image-2.1 (ModelScope backup)
    // 6. flux-pollinations (Pollinations FLUX backup)
    // 7. Qwen/Qwen-Image (ModelScope backup)
    val DEFAULT_IMAGE_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("mai-image-2.6", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("grok-imagine-2.0", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("muse-image", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("seedream-5.0-lite", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image-2.1", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 1.0),
        ImageModelCandidate("flux-pollinations", provider = PROVIDER_POLLINATIONS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 2.0),
    )

    // Default image edit models:
    val DEFAULT_IMAGE_EDIT_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("mai-image-2.6", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("grok-imagine-2.0", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("muse-image", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("seedream-5.0-lite", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image-Edit", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 2.0),
        ImageModelCandidate("flux-pollinations", provider = PROVIDER_POLLINATIONS, isPaid = false, costMagicube = 0.0),
    )

    // OpenRouter image models (paid, default disabled)
    val OPENROUTER_PAID_IMAGE_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("google/gemini-2.5-flash-image", provider = PROVIDER_OPENROUTER, isPaid = true, costMagicube = 0.0),
        ImageModelCandidate("google/gemini-3.1-flash-image", provider = PROVIDER_OPENROUTER, isPaid = true, costMagicube = 0.0),
        ImageModelCandidate("black-forest-labs/flux-1-schnell", provider = PROVIDER_OPENROUTER, isPaid = true, costMagicube = 0.0),
    )
}
