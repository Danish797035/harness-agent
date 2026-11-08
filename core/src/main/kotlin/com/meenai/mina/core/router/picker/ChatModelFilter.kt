package com.meenai.mina.core.router.picker

/**
 * Filter for identifying valid LLM chat models and excluding non-chat models:
 * - Image-edit and image-generation models (e.g. Qwen-Image-Edit, MusePublic/Qwen-Image-Edit, FLUX, Stable Diffusion, t2i, i2i)
 * - Embeddings, rerankers, text-to-speech (TTS), speech-to-text (ASR/whisper), audio, moderation, guard models.
 *
 * Rules:
 * 1. Image-edit / image-generation models are NOT chat models and must NEVER enter Main, Light, Vision, or any role.
 * 2. Models containing image-edit, image-gen, t2i, i2i must NEVER be tagged as Vision models.
 */
object ChatModelFilter {

    private val IMAGE_EDIT_OR_GEN_PATTERNS = listOf(
        Regex("""(?i)\b(?:image[-_]?edit|imageedit)\b"""),
        Regex("""(?i)\b(?:image[-_]?gen(?:eration)?|imagegen)\b"""),
        Regex("""(?i)\b(?:t2i|i2i|text[-_]?to[-_]?image|image[-_]?to[-_]?image)\b"""),
        Regex("""(?i)\b(?:flux|stable[-_]?diffusion|diffusers|midjourney|dall[-_]?e)\b"""),
    )

    private val NON_CHAT_PATTERNS = listOf(
        Regex("""(?i)\b(?:embedding|embeddings|rerank|reranker|bge)\b"""),
        Regex("""(?i)\b(?:tts|stt|asr|whisper|speech|voice|audio|transcribe)\b"""),
        Regex("""(?i)\b(?:moderation|guard|reward|discriminator|ocr)\b"""),
    )

    /**
     * Checks if a model ID or name represents an image generation or editing model.
     * Examples: Qwen-Image-Edit, MusePublic/Qwen-Image-Edit, FLUX.1-schnell, SDXL, t2i-adapter.
     */
    fun isImageEditOrGen(modelId: String, name: String = ""): Boolean {
        val combined = "$modelId $name"
        return IMAGE_EDIT_OR_GEN_PATTERNS.any { it.containsMatchIn(combined) }
    }

    /**
     * Checks if a model should be excluded from chat/routing candidates.
     * Excludes: embedding, rerank, tts, asr, speech, video, audio, moderation, image-gen, image-edit, etc.
     */
    fun isExcluded(modelId: String, name: String = ""): Boolean {
        if (isImageEditOrGen(modelId, name)) return true
        val combined = "$modelId $name"
        return NON_CHAT_PATTERNS.any { it.containsMatchIn(combined) }
    }

    /**
     * Determines if a model genuinely supports multimodal vision input for chat.
     * Image-edit and image-generation models MUST return false.
     */
    fun supportsVision(modelId: String, declaredVision: Boolean = false): Boolean {
        if (isImageEditOrGen(modelId)) return false
        if (declaredVision) return true
        val lower = modelId.lowercase()
        return lower.contains("vl") || lower.contains("vision") || lower.contains("omni")
    }
}
