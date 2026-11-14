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
    // 1. seedream-5.0-lite (Harness local service driving LM Arena)
    // 2. Qwen/Qwen-Image-2.1 (1 Magicube)
    // 3. Qwen/Qwen-Image (2 Magicube)
    val DEFAULT_IMAGE_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("seedream-5.0-lite", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image-2.1", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 1.0),
        ImageModelCandidate("Qwen/Qwen-Image", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 2.0),
    )

    // Default image edit models:
    val DEFAULT_IMAGE_EDIT_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("seedream-5.0-lite", provider = PROVIDER_HARNESS, isPaid = false, costMagicube = 0.0),
        ImageModelCandidate("Qwen/Qwen-Image-Edit", provider = PROVIDER_MODELSCOPE, isPaid = false, costMagicube = 2.0),
    )

    // OpenRouter image models (paid, default disabled)
    val OPENROUTER_PAID_IMAGE_MODELS: List<ImageModelCandidate> = listOf(
        ImageModelCandidate("black-forest-labs/flux-1-schnell", provider = PROVIDER_OPENROUTER, isPaid = true, costMagicube = 0.0),
    )
}
